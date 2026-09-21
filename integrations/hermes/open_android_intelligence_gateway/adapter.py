"""Hermes Platform Adapter for Open Android Intelligence (Gateway v2)."""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Mapping, Optional, Set

from urllib.parse import parse_qs, unquote, urlsplit

from .local_keys import master_key_unavailable_reason
from .core import (
    APPROVAL_CHOICES,
    APPROVAL_DEFAULT_TIMEOUT_SECONDS,
    NEW_CONVERSATION_COMMAND,
    AgentSessionBindings,
    VerifiedGatewayRequest,
    VerifiedRequestContext,
    canonicalize_target,
    iso_millis,
    request_signature_preimage,
)

try:
    from aiohttp import WSMsgType, web
    AIOHTTP_AVAILABLE = True
except ImportError:
    web = None  # type: ignore
    WSMsgType = None  # type: ignore
    AIOHTTP_AVAILABLE = False

try:
    from gateway.config import Platform, PlatformConfig
    from gateway.platforms.base import (
        BasePlatformAdapter,
        MessageEvent,
        MessageType,
        SendResult,
    )
except ImportError:
    # Standalone / test fallback
    class Platform:  # type: ignore
        def __init__(self, val: str):
            self.value = val

    class PlatformConfig:  # type: ignore
        def __init__(self, **kwargs: Any):
            self.extra = kwargs.get("extra", {})

    class SendResult:  # type: ignore
        def __init__(self, success: bool, message_id: str | None = None, error: str | None = None, retryable: bool = False):
            self.success = success
            self.message_id = message_id
            self.error = error
            self.retryable = retryable

    class MessageType:  # type: ignore
        TEXT = "text"

    class Source:  # type: ignore
        def __init__(self, platform: Any = "open_android", chat_id: str = "", chat_name: str = "", chat_type: str = "dm", user_id: str = "", user_name: str = "", **kwargs: Any):
            self.platform = platform
            self.chat_id = chat_id
            self.chat_name = chat_name
            self.chat_type = chat_type
            self.user_id = user_id
            self.user_name = user_name

    class MessageEvent:  # type: ignore
        def __init__(self, text: str, source: Any, message_type: Any = MessageType.TEXT, message_id: str | None = None, **kwargs: Any):
            self.text = text
            self.source = source
            self.message_type = message_type
            self.message_id = message_id

    class BasePlatformAdapter:  # type: ignore
        supports_code_blocks: bool = True
        supports_status_text: bool = False
        supports_async_delivery: bool = True
        interactive_resume: bool = True
        splits_long_messages: bool = True

        def __init__(self, config: Any, platform: Any = "open_android"):
            self.config = config
            self.platform = platform
            self._message_handler: Any = None
            self._running: bool = False
            # Standalone runs have no host session store; the real host injects one
            # through `set_session_store`, and a binding without it stays honestly
            # incomplete instead of naming a session nobody created.
            self._session_store: Any = None

        def set_message_handler(self, handler: Any) -> None:
            self._message_handler = handler

        def set_session_store(self, session_store: Any) -> None:
            self._session_store = session_store

        def set_fatal_error_handler(self, handler: Any) -> None:
            pass

        def _mark_connected(self) -> None:
            self._running = True

        def _mark_disconnected(self) -> None:
            self._running = False

        def _set_fatal_error(self, code: str, msg: str, retryable: bool = False) -> None:
            pass

        def build_source(self, **kwargs: Any) -> Any:
            return Source(platform=self.platform, **kwargs)

        async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
            return {"name": f"Android Session ({chat_id})", "type": "dm", "chat_id": chat_id}

        async def handle_message(self, event: Any) -> None:
            if self._message_handler:
                await self._message_handler(event)


logger = logging.getLogger("hermes.platforms.open_android")

DEFAULT_PORT = 8045
# Bound to 0.0.0.0 by default to allow LAN and public network access from mobile devices.
# Can be explicitly overridden via OPEN_ANDROID_GATEWAY_HOST environment variable or extra.host.
DEFAULT_HOST = "0.0.0.0"


class LocalCredentialVerifier:
    """Sandbox credential verifier that must be supplied explicitly.

    Accepts any non-empty password as long as the username matches the account,
    which is only appropriate for a local sandbox driven without provisioning a
    password. It is never selected by default: composed Hermes deployments use
    :class:`AccountPasswordVerifier`, which reads the digest recorded by the
    local admin surface. It has no power to bring an account into existence.
    """

    def verify(self, account_id: str, username: str, password: str, installation: Mapping[str, Any]) -> bool:
        if not account_id or not username or not password:
            return False
        if not isinstance(installation, Mapping) or not installation.get("installationId"):
            return False
        return str(username).strip() == str(account_id).strip()


class AccountPasswordVerifier:
    """Verifies the password the local admin surface recorded for the account.

    Fails closed on every other case: unknown account, account with no recorded
    password, empty password, or a username that does not address the account.
    An account nobody gave a password to cannot be logged into at all.
    """

    def __init__(self, core: Any):
        self._core = core

    def verify(self, account_id: str, username: str, password: str, installation: Mapping[str, Any]) -> bool:
        if not isinstance(account_id, str) or not account_id:
            return False
        if not isinstance(username, str) or username.strip() != account_id:
            return False
        if not isinstance(password, str) or not password:
            return False
        if not isinstance(installation, Mapping) or not installation.get("installationId"):
            return False
        exists = getattr(self._core, "account_exists", None)
        if callable(exists) and not exists(account_id):
            return False
        try:
            account = self._core.open_gateway_account(account_id)
        except Exception:
            return False
        try:
            return bool(account.credentials.verify_password(password))
        finally:
            account.close()


# The authenticated header set of contract §6.1. A header that the protocol
# treats as a conditional singleton may not appear twice: two conflicting
# values are exactly how a replay or identity substitution is smuggled past a
# parser that silently keeps one.
_SINGLETON_REQUEST_HEADERS = frozenset({
    "authorization",
    "x-open-android-intelligence-protocol",
    "x-open-android-intelligence-account",
    "x-open-android-intelligence-device",
    "x-open-android-intelligence-session",
    "x-open-android-intelligence-request-id",
    "x-open-android-intelligence-timestamp",
    "x-open-android-intelligence-nonce",
    "x-open-android-intelligence-signature",
    "idempotency-key",
    "last-event-id",
    "content-type",
})

_WIRE_ID = re.compile(r"^[A-Za-z0-9._~-]{1,128}$")
_TIMESTAMP = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$")
_BASE64URL = re.compile(r"^[A-Za-z0-9_-]+$")


def _header_pairs(input: Mapping[str, Any]) -> list[tuple[str, str]]:
    raw = input.get("rawHeaders", input.get("raw_headers"))
    pairs: list[tuple[str, str]] = []
    if raw:
        items = list(raw)
        if items and isinstance(items[0], (list, tuple)) and len(items[0]) == 2:
            return [(str(name), str(value)) for name, value in items]
        return [(str(items[index]), str(items[index + 1])) for index in range(0, len(items) - 1, 2)]
    headers = input.get("headers") or {}
    if isinstance(headers, Mapping):
        pairs = [(str(name), str(value)) for name, value in headers.items()]
    return pairs


def _singleton_headers(input: Mapping[str, Any]) -> dict[str, str] | None:
    """Case-folded headers, or None when a singleton arrived more than once."""
    result: dict[str, str] = {}
    for name, value in _header_pairs(input):
        key = name.lower()
        if not key or any(character.isspace() for character in key):
            return None
        # An obs-fold continuation arrives as a value starting with space or
        # tab; treating it as an independent header would let it add a second
        # meaning to a singleton.
        value = value.strip() if value[:1] in (" ", "\t") else value
        if key in _SINGLETON_REQUEST_HEADERS and result.get(key, value) != value:
            return None
        result[key] = value
    return result


def _b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _epoch_millis(value: str) -> int:
    """Contract timestamps are fixed-format UTC with exactly three digits.

    A value that is not in that form is a caller bug: substituting the current
    time would silently move an event on the phone's timeline.
    """
    parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
    return int(parsed.timestamp() * 1000)


def _ed25519_verify(public_key: str, message: bytes, signature: str) -> bool:
    try:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    except ImportError:
        logger.error("[open_android] cryptography is unavailable; request signatures cannot be verified")
        return False
    try:
        key = Ed25519PublicKey.from_public_bytes(_b64url_decode(public_key))
        key.verify(_b64url_decode(signature), message)
        return True
    except Exception:
        return False


def _decode_body(body: Any, headers: Mapping[str, str]) -> Any:
    """The body Core expects: parsed JSON for JSON requests, bytes otherwise."""
    if body is None:
        return None
    if isinstance(body, (bytes, bytearray, memoryview)):
        raw = bytes(body)
    else:
        return body
    if not raw:
        return None
    content_type = (headers.get("content-type") or "").split(";")[0].strip().lower()
    if content_type != "application/json":
        return raw
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None


class GatewayRequestVerifier:
    """Turns a raw HTTP request into the typed verified-request seam.

    This is the host-side half of contract §6: the phone signs the canonical
    target and the digest of the exact body bytes with the device key it
    registered at login, so a proxy that rewrote either cannot produce a
    signature that still verifies. Every failure returns None rather than
    raising — an authentication seam must fail closed without telling the
    caller which of the checks it failed.
    """

    def __init__(self, core: Any, max_clock_skew_seconds: int = 120):
        self._core = core
        self._max_clock_skew_seconds = int(max_clock_skew_seconds)

    @property
    def maxClockSkewSeconds(self) -> int:
        return self._max_clock_skew_seconds

    def __call__(self, input: Mapping[str, Any]) -> VerifiedGatewayRequest | None:
        try:
            return self._verify(input)
        except Exception:
            return None

    # Host-adapter naming alias.
    verify = __call__

    def _verify(self, input: Mapping[str, Any]) -> VerifiedGatewayRequest | None:
        method = input.get("method")
        target = input.get("target")
        # PATCH carries the conversation title update (contract §7); leaving it out
        # of the signed method set made every rename fail closed with 401 while the
        # HTTP boundary above already accepted PATCH.
        if method not in {"GET", "POST", "PUT", "DELETE", "PATCH"} or not isinstance(target, str):
            return None
        headers = _singleton_headers(input)
        if headers is None:
            return None
        if headers.get("x-open-android-intelligence-protocol") != "2.0":
            return None
        access_token = self._bearer_token(headers)
        account_id = headers.get("x-open-android-intelligence-account")
        device_id = headers.get("x-open-android-intelligence-device")
        session_id = headers.get("x-open-android-intelligence-session")
        request_id = headers.get("x-open-android-intelligence-request-id")
        timestamp = headers.get("x-open-android-intelligence-timestamp")
        nonce = headers.get("x-open-android-intelligence-nonce")
        signature = headers.get("x-open-android-intelligence-signature")
        for value in (access_token, account_id, device_id, session_id, request_id, timestamp, nonce, signature):
            if not isinstance(value, str) or not value:
                return None
        for value in (account_id, device_id, session_id, request_id):
            if _WIRE_ID.fullmatch(value) is None:
                return None
        if _TIMESTAMP.fullmatch(timestamp) is None:
            return None
        if _BASE64URL.fullmatch(nonce) is None or len(_b64url_decode(nonce)) != 16:
            return None
        if _BASE64URL.fullmatch(signature) is None or len(_b64url_decode(signature)) != 64:
            return None

        signed_at = datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
        if abs((datetime.now(timezone.utc) - signed_at).total_seconds()) > self._max_clock_skew_seconds:
            return None

        session = self._resolve_session(account_id, device_id, session_id, access_token, timestamp)
        if session is None:
            return None

        canonical = canonicalize_target(target)
        if canonical != target:
            return None
        preimage = request_signature_preimage({
            "method": method, "target": canonical,
            "accountId": account_id, "deviceId": device_id, "sessionId": session_id,
            "requestId": request_id, "timestamp": timestamp, "nonce": nonce,
            "bodyHex": _raw_body(input).hex(),
        })
        if not _ed25519_verify(session["devicePublicKey"], preimage, signature):
            return None

        return VerifiedGatewayRequest(
            context=VerifiedRequestContext(
                accountId=account_id, deviceId=device_id, sessionId=session_id,
                requestId=request_id, correlationId=request_id,
                pairingGeneration=session["pairingGeneration"],
                grantRevision=session["grantRevision"],
                installationId=session["installationId"],
            ),
            method=method,
            target=canonical,
            body=_decode_body(input.get("body"), headers),
            idempotencyKey=headers.get("idempotency-key"),
            lastEventId=headers.get("last-event-id"),
            now=timestamp,
        )

    def _bearer_token(self, headers: Mapping[str, str]) -> str | None:
        authorization = headers.get("authorization")
        if not isinstance(authorization, str):
            return None
        scheme, separator, token = authorization.partition(" ")
        if not separator or scheme.strip().lower() != "bearer":
            return None
        return token.strip() or None

    def _resolve_session(
        self, account_id: str, device_id: str, session_id: str, access_token: str, timestamp: str,
    ) -> Mapping[str, Any] | None:
        exists = getattr(self._core, "account_exists", None)
        if callable(exists) and not exists(account_id):
            return None
        account = self._core.open_gateway_account(account_id)
        try:
            resolver = getattr(account.sessions, "resolve_session", None)
            if not callable(resolver):
                return None
            return resolver(access_token, session_id, device_id, timestamp)
        except Exception:
            return None
        finally:
            account.close()


EVENT_STREAM_PATH = "/open-android-intelligence/v2/events"
EVENT_STREAM_WS_PATH = "/open-android-intelligence/v2/events/ws"
# Heartbeats are SSE comments: they carry no event id, so a client cannot mistake
# one for a resumable event.
SSE_HEARTBEAT = b": ping\n\n"
SSE_HEARTBEAT_SECONDS = 15.0
SSE_QUEUE_SIZE = 100
# How many conversations keep "which id did this turn's reply get" in memory. One
# entry per conversation that published something since its last user message.
MAX_REMEMBERED_TURNS = 256


def _sse_frame(event: Mapping[str, Any]) -> bytes:
    """One SSE frame in the shape contract section 9 defines.

    The `event:` line carries the event type, so `data:` holds only the
    correlation id, the timestamp and the payload the phone decodes.
    """
    data = {
        "correlationId": event.get("correlationId"),
        "occurredAt": event.get("occurredAt"),
        "payload": event.get("payload") or {},
    }
    return (
        f"id: {event.get('eventId')}\n"
        f"event: {event.get('eventType')}\n"
        f"data: {json.dumps(data, ensure_ascii=False, separators=(',', ':'))}\n\n"
    ).encode("utf-8")


def _ws_event_frame(event: Mapping[str, Any]) -> str:
    """One WebSocket event frame carrying durable cursor and JSON-encoded data."""
    data = {
        "correlationId": event.get("correlationId"),
        "occurredAt": event.get("occurredAt"),
        "payload": event.get("payload") or {},
    }
    return json.dumps(
        {
            "id": event.get("eventId"),
            "event": event.get("eventType"),
            "data": json.dumps(data, ensure_ascii=False),
        },
        ensure_ascii=False,
    )


def _extract_event_id_from_item(item: Any) -> Optional[str]:
    """Extract eventId from an item queued for SSE/WS subscribers."""
    if isinstance(item, Mapping):
        val = item.get("eventId") or item.get("id")
        return str(val).strip() if val else None
    if isinstance(item, (bytes, bytearray, memoryview)):
        text = bytes(item).decode("utf-8", errors="replace")
    elif isinstance(item, str):
        text = item
    else:
        return None

    lines = [line.rstrip("\r") for line in text.splitlines()]
    non_empty = [l for l in lines if l]
    if non_empty and all(l.startswith(":") for l in non_empty):
        return None
    if not non_empty:
        return None

    event_id: Optional[str] = None
    has_sse_field = False
    for line in lines:
        if line.startswith(":"):
            continue
        if line.startswith("id:"):
            has_sse_field = True
            val = line[3:]
            if val.startswith(" "):
                val = val[1:]
            val = val.strip()
            if val:
                event_id = val
        elif line == "id":
            has_sse_field = True
            event_id = None
        elif line.startswith("event:") or line == "event" or line.startswith("data:") or line == "data":
            has_sse_field = True

    if has_sse_field:
        return event_id

    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict):
            val = parsed.get("eventId") or parsed.get("id")
            return str(val).strip() if val else None
    except Exception:
        pass

    return None


def _ws_message_from_queue_item(item: Any) -> Optional[str]:
    """Convert an item popped from an active subscriber queue into a WebSocket message."""
    if isinstance(item, (bytes, bytearray, memoryview)):
        text = bytes(item).decode("utf-8", errors="replace")
    elif isinstance(item, str):
        text = item
    elif isinstance(item, Mapping):
        return _ws_event_frame(item)
    else:
        return None

    lines = [line.rstrip("\r") for line in text.splitlines()]
    non_empty = [l for l in lines if l]
    if non_empty and all(l.startswith(":") for l in non_empty):
        return None
    if not non_empty:
        return None

    event_id: Optional[str] = None
    event_type: Optional[str] = None
    data_lines: list[str] = []
    is_sse = False
    for line in lines:
        if line.startswith(":"):
            continue
        if line.startswith("id:"):
            is_sse = True
            val = line[3:]
            if val.startswith(" "):
                val = val[1:]
            event_id = val.strip()
        elif line == "id":
            is_sse = True
            event_id = ""
        elif line.startswith("event:"):
            is_sse = True
            val = line[6:]
            if val.startswith(" "):
                val = val[1:]
            event_type = val.strip()
        elif line == "event":
            is_sse = True
            event_type = ""
        elif line.startswith("data:"):
            is_sse = True
            val = line[5:]
            if val.startswith(" "):
                val = val[1:]
            data_lines.append(val)
        elif line == "data":
            is_sse = True
            data_lines.append("")

    if is_sse and (event_id is not None or event_type is not None or data_lines):
        data_str = "\n".join(data_lines) if data_lines else "{}"
        return json.dumps(
            {
                "id": event_id,
                "event": event_type,
                "data": data_str,
            },
            ensure_ascii=False,
        )

    try:
        parsed = json.loads(text)
        if isinstance(parsed, dict) and ("id" in parsed or "event" in parsed):
            return text
        if isinstance(parsed, dict):
            return _ws_event_frame(parsed)
    except Exception:
        pass

    return text


def _header_value(headers: Mapping[str, Any], name: str) -> Optional[str]:
    """One header, matched case-insensitively and never guessed."""
    wanted = name.lower()
    for key, value in headers.items():
        if str(key).lower() == wanted:
            text = str(value).strip()
            return text or None
    return None


def _conversation_id_of(path: str) -> Optional[str]:
    """The conversation a route reads or writes, if any.

    Opening a conversation and reading its timeline are the same act for this
    contract: the phone switches by reading, so both `…/conversations/{id}` and
    `…/conversations/{id}/messages` name the conversation the user moved to.
    Sub-resources that are about something else (`generations`, `attachments`)
    name nothing here, because treating them as an opened conversation would bind
    sessions nobody opened.
    """
    parts = [part for part in path.split("/") if part]
    if "conversations" not in parts:
        return None
    index = parts.index("conversations") + 1
    if index >= len(parts):
        return None
    tail = parts[index + 1:]
    if tail and tail != ["messages"]:
        return None
    return parts[index]


def _raw_body(input: Mapping[str, Any]) -> bytes:
    body = input.get("body")
    if body is None:
        return b""
    if isinstance(body, (bytes, bytearray, memoryview)):
        return bytes(body)
    if isinstance(body, str):
        return body.encode("utf-8")
    return json.dumps(body, separators=(",", ":"), sort_keys=True).encode("utf-8")


def create_gateway_request_verifier(core: Any, max_clock_skew_seconds: int = 120) -> GatewayRequestVerifier:
    return GatewayRequestVerifier(core, max_clock_skew_seconds)


createGatewayRequestVerifier = create_gateway_request_verifier


class OpenAndroidPlatformAdapter(BasePlatformAdapter):
    """Hermes messaging platform adapter hosting the Gateway Protocol v2 HTTP/SSE server."""

    @property
    def authorization_is_upstream(self) -> bool:
        """OpenAndroid gateway connections are already authenticated via Ed25519/MasterKey/HMAC."""
        return True

    supports_code_blocks: bool = True
    supports_status_text: bool = False
    supports_async_delivery: bool = True
    interactive_resume: bool = True
    splits_long_messages: bool = True
    REQUIRES_EDIT_FINALIZE: bool = True

    def __init__(self, config: Any, services: Any):
        try:
            plat = Platform("open_android")
        except Exception:
            plat = getattr(Platform, "LOCAL", None) or Platform("open_android")
        super().__init__(config, plat)
        self.services = services
        extra = getattr(config, "extra", {}) or {}

        # Resolve port: config extra -> env var -> default
        raw_port = extra.get("port") or os.getenv("OPEN_ANDROID_GATEWAY_PORT") or str(DEFAULT_PORT)
        try:
            self._port = int(raw_port)
        except ValueError:
            self._port = DEFAULT_PORT

        self._host = str(extra.get("host") or os.getenv("OPEN_ANDROID_GATEWAY_HOST") or DEFAULT_HOST)
        # No account is guessed: the host names the account it delivers for, and
        # outbound delivery fails loudly instead of writing into an assumed one.
        configured_account = extra.get("account_id") or os.getenv("OPEN_ANDROID_ACCOUNT_ID")
        self._account_id = str(configured_account).strip() if configured_account else None

        self._app: Optional[web.Application] = None
        self._runner: Optional[web.AppRunner] = None
        self._site: Optional[web.TCPSite] = None
        # One subscriber set per account: an event is only ever handed to the
        # stream of the account that produced it.
        self._event_subscribers: Dict[str, Set[asyncio.Queue]] = {}
        self._conv_to_account: Dict[str, str] = {}
        # The loop that owns the subscriber queues, and the one delivery hook the
        # core holds. The hook is stored as an attribute so registering and
        # unregistering it name the very same object.
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._event_sink = self._deliver_committed_event
        # Background work this adapter started and must not lose to collection.
        self._background_tasks: Set[asyncio.Task] = set()
        # Per conversation: the reply text already published in the current turn,
        # and the message id it was published under.
        self._turn_replies: Dict[str, tuple[str, str]] = {}

    @property
    def _active_sse_queues(self) -> Dict[str, Set[asyncio.Queue]]:
        """Compatibility alias for _event_subscribers."""
        return self._event_subscribers

    @_active_sse_queues.setter
    def _active_sse_queues(self, value: Dict[str, Set[asyncio.Queue]]) -> None:
        self._event_subscribers = value


    async def connect(self, *, is_reconnect: bool = False) -> bool:
        """Start the Gateway Protocol v2 HTTP & SSE server."""
        # ADR 0023: a missing or unsafe master key source refuses startup. Serving
        # anyway would answer every authenticated request with 400 while login
        # still succeeded, which is indistinguishable from a broken phone build.
        reason = master_key_unavailable_reason(getattr(self.services, "core", None))
        if reason is not None:
            logger.error("[open_android] %s", reason)
            self._set_fatal_error("MASTER_KEY_UNAVAILABLE", reason, retryable=False)
            return False
        if not AIOHTTP_AVAILABLE:
            logger.error("[open_android] aiohttp is not installed; cannot start HTTP gateway")
            self._set_fatal_error(
                "MISSING_DEPENDENCY",
                "aiohttp is not installed. Install with pip install aiohttp",
                retryable=False,
            )
            return False

        # Every committed event is pushed to this account's live subscribers
        # through this one hook. Registering before the socket is opened means no
        # event produced between "server up" and "first subscriber" is only
        # reachable through a reconnect.
        self._loop = asyncio.get_running_loop()
        core = getattr(self.services, "core", None)
        # Approval cards are only a service this Gateway can deliver when a
        # decision can actually reach the Agent thread waiting on it (§7.2).
        if core is not None:
            core.approval_resolver = self._host_approval_resolver()
        register_sink = getattr(core, "register_event_sink", None)
        if callable(register_sink):
            register_sink(self._event_sink)

        try:
            max_bytes = 10485760
            if self.services and hasattr(self.services, "exposure") and self.services.exposure.routes:
                max_bytes = getattr(self.services.exposure.routes[0]._services, "max_body_bytes", max_bytes)

            # A host outside the verified API range is never patched into looking
            # compatible: the routes answer HOST_INCOMPATIBLE and the management
            # surface stays read-only until a real range is configured.
            self._app = web.Application(client_max_size=max_bytes)

            # Health probe
            self._app.router.add_get("/health", self._handle_health)

            # Gateway Protocol v2 routes
            self._app.router.add_route("*", "/open-android-intelligence/v2/{tail:.*}", self._dispatch_gateway_request)

            self._runner = web.AppRunner(self._app)
            await self._runner.setup()
            self._site = web.TCPSite(self._runner, self._host, self._port, reuse_address=True, reuse_port=True)
            await self._site.start()

            self._mark_connected()
            logger.info(
                "[open_android] Gateway Protocol v2 server listening on %s:%d (default account: %s)",
                self._host, self._port, self._account_id,
            )
            return True
        except Exception as exc:
            logger.error("[open_android] Failed to start HTTP gateway: %s", exc, exc_info=True)
            self._set_fatal_error("CONNECT_FAILED", f"Gateway startup failed: {exc}", retryable=True)
            unregister_sink = getattr(getattr(self.services, "core", None), "unregister_event_sink", None)
            if callable(unregister_sink):
                unregister_sink(self._event_sink)
            self._loop = None
            return False

    async def disconnect(self) -> None:
        """Stop the Gateway Protocol v2 server."""
        self._running = False
        unregister_sink = getattr(getattr(self.services, "core", None), "unregister_event_sink", None)
        if callable(unregister_sink):
            unregister_sink(self._event_sink)
        self._loop = None
        if self._runner:
            try:
                await self._runner.cleanup()
            except Exception as exc:
                logger.debug("[open_android] Error during cleanup: %s", exc)
            self._runner = None
        self._app = None
        self._site = None
        self._mark_disconnected()
        logger.info("[open_android] Gateway Protocol v2 server stopped")

    async def send(
        self,
        chat_id: str,
        content: str,
        reply_to: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> SendResult:
        """Send message from Hermes AI agent back to the mobile client."""
        target_account = (
            (metadata.get("account_id") if isinstance(metadata, dict) else None)
            or self._conv_to_account.get(chat_id)
            or self._account_id
        )
        if not target_account:
            return SendResult(success=False, error="ACCOUNT_NOT_CONFIGURED", retryable=False)
        try:
            now_iso = iso_millis()
            # `reply_to` names the message being answered, not this one: using it
            # as our own id would overwrite the user's row. Only an id the host
            # explicitly hands over as *this* message's is honoured.
            named: Optional[str] = None
            if isinstance(metadata, dict):
                candidate = metadata.get("messageId") or metadata.get("message_id")
                if isinstance(candidate, str) and candidate:
                    named = candidate
            message_id = self._published_reply_id(chat_id, content, named)
            if isinstance(metadata, dict) and metadata.get("expect_edits"):
                await self.stream_delta(chat_id, message_id, content, occurred_at=now_iso, account_id=target_account)
            else:
                await self.complete_message(chat_id, message_id, content, occurred_at=now_iso, account_id=target_account)
            return SendResult(success=True, message_id=message_id)
        except Exception as exc:
            logger.error("[open_android] Failed to deliver message to %s: %s", chat_id, exc)
            return SendResult(success=False, error=str(exc), retryable=False)

    def _published_reply_id(self, chat_id: str, text: str, proposed: Optional[str]) -> str:
        """The message id one logical reply is published under.

        A host can publish the same reply more than once in a turn — a finalize
        plus a final send, a retried delivery. That is one message, not two:
        minting a second id would put the reply in the account's timeline twice,
        and nothing downstream could collapse the copies because the ids really
        do differ. The first id of a turn's reply is therefore kept, and a new
        user message is what starts a new turn.
        """
        remembered = self._turn_replies.get(chat_id)
        if remembered is not None and remembered[0] == text:
            if proposed is not None and proposed != remembered[1]:
                logger.info(
                    "[open_android] %s published the same reply again as %s; keeping %s",
                    chat_id, proposed, remembered[1],
                )
            return remembered[1]
        identity = proposed or f"msg_{uuid.uuid4().hex[:12]}"
        self._remember_turn_reply(chat_id, text, identity)
        return identity

    def _remember_turn_reply(self, chat_id: str, text: str, message_id: str) -> None:
        self._turn_replies.pop(chat_id, None)
        self._turn_replies[chat_id] = (text, message_id)
        while len(self._turn_replies) > MAX_REMEMBERED_TURNS:
            eldest = next(iter(self._turn_replies), None)
            if eldest is None:
                break
            self._turn_replies.pop(eldest, None)

    def _forget_turn_reply(self, chat_id: str) -> None:
        """A new user message starts a new turn: the same text is a new reply."""
        self._turn_replies.pop(chat_id, None)

    async def edit_message(
        self,
        chat_id: str,
        message_id: str,
        content: str,
        *,
        finalize: bool = False,
        **kwargs: Any,
    ) -> bool:
        """Progressive edit support for streaming consumers.

        Streams intermediate deltas via [stream_delta] and final message via [complete_message].
        """
        target_account = (
            kwargs.get("account_id")
            or self._conv_to_account.get(chat_id)
            or self._account_id
        )
        if not target_account:
            logger.warning("[open_android] No account configured; cannot edit message %s", message_id)
            return False
        now_iso = iso_millis()
        if finalize:
            message_id = self._published_reply_id(chat_id, content, message_id)
            await self.complete_message(chat_id, message_id, content, occurred_at=now_iso, account_id=target_account)
        else:
            await self.stream_delta(chat_id, message_id, content, occurred_at=now_iso, account_id=target_account)
        return True

    async def stream_delta(
        self,
        chat_id: str,
        message_id: str,
        accumulated_text: str,
        occurred_at: Optional[str] = None,
        account_id: Optional[str] = None,
    ) -> None:
        """Publish one partial assistant message.

        The phone treats a timeline event as an upsert, not an append, so the
        payload carries everything generated so far rather than only the newest
        fragment. Hosts driving a token stream call this per chunk and then
        finish with [complete_message].
        """
        target_account = account_id or self._conv_to_account.get(chat_id) or self._account_id
        await self._publish_event(
            "conversation.message.delta", chat_id, message_id, accumulated_text, occurred_at, account_id=target_account,
        )

    async def complete_message(
        self,
        chat_id: str,
        message_id: str,
        content: str,
        occurred_at: Optional[str] = None,
        account_id: Optional[str] = None,
    ) -> None:
        """Publish the final assistant message for one turn."""
        target_account = account_id or self._conv_to_account.get(chat_id) or self._account_id
        await self._publish_event(
            "conversation.message.completed", chat_id, message_id, content, occurred_at, account_id=target_account,
        )

    async def _publish_event(
        self,
        event_type: str,
        chat_id: str,
        message_id: str,
        text: str,
        occurred_at: Optional[str] = None,
        account_id: Optional[str] = None,
    ) -> None:
        """Persist one assistant event; the committed event delivers itself.

        Persisting first is what makes the SSE `id:` a real cursor: after a
        disconnect the phone replays the event from the account store instead of
        depending on this process still holding it in memory. Delivery is not
        done here: the store hands every committed event to the registered sink,
        so an event only ever has one path to a live subscriber.
        """
        target_account = account_id or self._conv_to_account.get(chat_id) or self._account_id
        if not target_account:
            logger.warning("[open_android] No account configured; %s was not published", event_type)
            return
        payload = self._message_payload(chat_id, message_id, text, occurred_at)
        account = self.services.core.open_gateway_account(target_account)
        try:
            if event_type == "conversation.message.completed":
                try:
                    account.conversations.record_assistant_message(chat_id, message_id, text, occurred_at)
                except Exception as rec_err:
                    logger.warning("[open_android] Failed to record completed message: %s", rec_err)
            account.events.append(event_type, message_id, payload, occurred_at)
        finally:
            account.close()

    def _message_payload(
        self,
        chat_id: str,
        message_id: str,
        text: str,
        occurred_at: Optional[str] = None,
    ) -> Dict[str, Any]:
        """The event payload the phone's decoder reads.

        `messageId` and the text parts sit at the top level: the decoder reads
        the payload directly and returns nothing at all for a payload that nests
        them, which is how a whole reply can be silently dropped.
        """
        # One formatter for the whole payload: the phone parses `occurredAt` with
        # an ISO instant parser that requires exactly three fractional digits.
        timestamp = iso_millis(occurred_at)
        return {
            "conversationId": chat_id,
            "messageId": message_id,
            "sender": "assistant",
            "parts": [{"type": "text", "text": text}],
            "text": text,
            "timestamp": _epoch_millis(timestamp),
            "revision": 0,
        }

    async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
        """Get information about an Android conversation chat/channel."""
        return {
            "name": f"Android Session ({chat_id})",
            "type": "dm",
            "chat_id": chat_id,
        }

    # ── Command-execution approval (contract §7.2) ─────────────────────────
    # Overriding `_send_exec_approval_prompt` is what tells the host this surface
    # renders approvals natively: the runner then stops sending the plain-text
    # `/approve` prompt and hands the prompt here instead.

    def _host_approval_resolver(self) -> Optional[Callable[..., Optional[int]]]:
        """How a decision reaches the Agent thread blocked on it, when there is one.

        `None` means this process cannot reach the host approval runtime, and the
        Gateway then must not advertise approval cards at all: a card whose
        buttons release nothing is worse than the honest text prompt.
        """
        try:
            from tools.approval import resolve_gateway_approval
        except Exception:
            logger.info(
                "[open_android] No host approval runtime reachable; approval cards stay unavailable"
            )
            return None

        def _resolve(session_key: str, choice: str, request_id: Optional[str]) -> Optional[int]:
            try:
                return int(resolve_gateway_approval(session_key, choice, request_id=request_id))
            except Exception as exc:
                logger.warning("[open_android] Host approval resolution failed: %s", exc)
                return None

        return _resolve

    @staticmethod
    def _approval_timeout_seconds() -> int:
        """The host's configured `approvals.timeout`; 300s when it cannot be read."""
        try:
            from gateway.platforms.base_exec_approval import approval_timeout_seconds
        except Exception:
            return APPROVAL_DEFAULT_TIMEOUT_SECONDS
        try:
            value = int(approval_timeout_seconds())
        except Exception:
            return APPROVAL_DEFAULT_TIMEOUT_SECONDS
        return value if value > 0 else APPROVAL_DEFAULT_TIMEOUT_SECONDS

    @staticmethod
    def _approval_style(choice: str, host_style: str) -> str:
        """One presentation hint per tier: allow-once leads, deny warns."""
        if host_style in {"primary", "secondary", "danger", "neutral"}:
            return host_style
        if choice == "deny":
            return "danger"
        if choice == "once":
            return "primary"
        return "secondary"

    def _approval_options(self, prompt: Any) -> list:
        """The tiers this prompt actually offers, in the protocol's closed set.

        The host decides which tiers exist: a smart deny offers only `once` and
        `deny`, so the phone must never invent a tier the host would refuse.
        """
        options: list = []
        for action in getattr(prompt, "actions", ()) or ():
            values = list(action) + ["", "", ""]
            label, choice, style = str(values[0]), str(values[1]), str(values[2])
            if choice not in APPROVAL_CHOICES:
                continue
            options.append({
                "choice": choice,
                "style": self._approval_style(choice, style),
                **({"label": label} if label else {}),
            })
        return options

    async def _send_exec_approval_prompt(self, prompt: Any) -> SendResult:
        """Publish one approval as a structured card instead of an `/approve` text.

        The card carries `approvalId` and nothing else about the host: the session
        key and the host's request id are persisted on the Gateway side, so a
        phone can answer the approval it was shown but cannot name — or resolve —
        a session of its own choosing.
        """
        chat_id = str(getattr(prompt, "chat_id", "") or "")
        metadata = getattr(prompt, "metadata", None)
        metadata = metadata if isinstance(metadata, Mapping) else {}
        account_id = (
            metadata.get("account_id")
            or self._conv_to_account.get(chat_id)
            or self._account_id
        )
        if not account_id:
            logger.warning("[open_android] No account identity; approval for %s was not published", chat_id)
            return SendResult(success=False, error="ACCOUNT_NOT_CONFIGURED", retryable=False)
        if not chat_id:
            return SendResult(success=False, error="CONVERSATION_REQUIRED", retryable=False)

        options = self._approval_options(prompt)
        if not options:
            # No tier the protocol can express: falling through to the text
            # prompt is honest, while a card with no button would not be.
            logger.warning("[open_android] Approval prompt offers no known tier; not publishing a card")
            return SendResult(success=False, error="APPROVAL_OPTIONS_UNSUPPORTED", retryable=False)

        core = getattr(self.services, "core", None)
        if core is None:
            return SendResult(success=False, error="GATEWAY_CORE_UNAVAILABLE", retryable=False)

        approval_id = "apr_" + uuid.uuid4().hex
        timeout_seconds = self._approval_timeout_seconds()
        severity = metadata.get("severity")
        severity = str(severity) if severity in {"info", "elevated", "critical"} else None

        def _publish() -> Any:
            account = core.open_gateway_account(str(account_id))
            try:
                return core.request_command_approval(
                    account,
                    approval_id=approval_id,
                    conversation_id=chat_id,
                    command=str(getattr(prompt, "command", "") or ""),
                    reason=str(getattr(prompt, "description", "") or ""),
                    options=options,
                    timeout_seconds=timeout_seconds,
                    session_key=str(getattr(prompt, "session_key", "") or "") or None,
                    host_request_id=(
                        str(metadata.get("request_id")) if metadata.get("request_id") else None
                    ),
                    severity=severity,
                    correlation_id=approval_id,
                )
            finally:
                account.close()

        try:
            await asyncio.to_thread(_publish)
        except Exception as exc:
            logger.error("[open_android] Failed to publish approval %s: %s", approval_id, exc)
            return SendResult(success=False, error="APPROVAL_PUBLISH_FAILED", retryable=True)
        logger.info("[open_android] Approval card %s published for %s", approval_id, chat_id)
        return SendResult(success=True, message_id=approval_id)

    # Host-adapter naming aliases.
    streamDelta = stream_delta
    completeMessage = complete_message
    editMessage = edit_message

    async def _handle_health(self, request: web.Request) -> web.Response:
        return web.Response(text="ok", content_type="text/plain")

    def _raw_request(self, request: web.Request, body: bytes) -> Dict[str, Any]:
        """The origin-form target the client actually signed, query included."""
        return {
            "method": request.method,
            # `url` is the origin-form target the client signed: the boundary
            # reads `url` first, and a target that lost its query would make
            # every signed request with a query fail verification.
            "url": request.path_qs,
            "target": request.path_qs,
            "headers": dict(request.headers),
            "rawHeaders": tuple((k, v) for k, v in request.headers.items()),
            "body": body,
        }

    async def _dispatch_gateway_request(self, request: web.Request) -> web.StreamResponse:
        """Route incoming HTTP request into Gateway exposure routes or SSE stream."""
        path = request.path
        method = request.method

        try:
            body_bytes = await request.read()
        except Exception as exc:
            return web.json_response({"errorCode": "REQUEST_BODY_INVALID", "message": str(exc)}, status=400)

        raw_req = self._raw_request(request, body_bytes)

        # Check for WebSocket upgrade on event stream paths
        is_ws_path = path in (EVENT_STREAM_PATH, EVENT_STREAM_WS_PATH)
        is_ws_upgrade = (
            "websocket" in str(request.headers.get("Upgrade", "")).lower()
            or "sec-websocket-key" in request.headers
        )
        if is_ws_path and is_ws_upgrade:
            return await self._handle_ws_stream(request, raw_req)

        # The event stream is the one route whose response is framed as SSE. It
        # is still authenticated as an ordinary request: the route decides that,
        # and a client that asks for JSON gets the same events as a JSON body.
        if (
            method == "GET"
            and path == EVENT_STREAM_PATH
            and "text/event-stream" in str(request.headers.get("Accept", "")).lower()
        ):
            return await self._handle_sse_stream(request, raw_req)

        # Find matching exposure route
        handler_found = None
        for route in self.services.exposure.routes:
            if route.match == "exact" and route.path == path:
                handler_found = route
                break
            elif route.match == "prefix" and path.startswith(route.path):
                handler_found = route
                break

        if handler_found is None:
            return web.json_response({"errorCode": "NOT_FOUND"}, status=404)

        result = handler_found._handle_raw(raw_req)
        status = result.get("statusCode", 200)
        headers = result.get("headers", {})
        body = result.get("body", {})

        account_header = _header_value(raw_req["headers"], "x-open-android-intelligence-account")

        # If this was an inbound message POST, trigger Hermes agent turn
        if method == "POST" and "/conversations/" in path and path.endswith("/messages") and status in (200, 201):
            asyncio.create_task(self._notify_agent_inbound(
                path, body_bytes, body, account_header,
            ))

        # Opening one conversation is the switch itself (ADR 0043): the reply that
        # follows must land in that conversation's own Agent session, so the
        # binding is ensured here instead of being left to whichever message
        # happens to arrive next.
        opened_conversation = _conversation_id_of(path) if method == "GET" else None
        if opened_conversation is not None and status in (200, 201):
            self._schedule_agent_session(
                opened_conversation, account_header or self._account_id,
                force_new=False, created_via=AgentSessionBindings.CREATED_VIA_CONVERSATION_READ,
            )

        clean_headers = {k: v for k, v in headers.items() if k.lower() != "content-type"}
        return web.json_response(body, status=status, headers=clean_headers)

    def _agent_source(self, conversation_id: str, account_id: str) -> Any:
        """The host source that names one Gateway conversation as one Agent chat.

        The Gateway conversation id *is* the chat id: the host keys its session
        from it, which is what makes two conversations two memories rather than
        one shared transcript.
        """
        return self.build_source(
            chat_id=conversation_id,
            chat_name="Android Client",
            chat_type="dm",
            user_id=account_id,
            user_name=account_id,
        )

    def _schedule_agent_session(
        self, conversation_id: str, account_id: Optional[str], *,
        force_new: bool, created_via: str,
    ) -> None:
        """Ensure one conversation's Agent session without blocking the response."""
        if not conversation_id or not account_id:
            logger.warning(
                "[open_android] No account identity for conversation %s; its Agent session was not bound",
                conversation_id,
            )
            return

        def _start() -> None:
            task = asyncio.ensure_future(self._ensure_agent_session(
                conversation_id, account_id, force_new=force_new, created_via=created_via,
            ))
            # Held until done: a task nobody references can be collected mid-flight.
            self._background_tasks.add(task)
            task.add_done_callback(self._background_tasks.discard)

        loop = self._loop
        if loop is None or loop.is_closed():
            return
        try:
            running: Optional[asyncio.AbstractEventLoop] = asyncio.get_running_loop()
        except RuntimeError:
            running = None
        if running is loop:
            _start()
            return
        try:
            loop.call_soon_threadsafe(_start)
        except RuntimeError:
            return

    async def _ensure_agent_session(
        self, conversation_id: str, account_id: str, *, force_new: bool, created_via: str,
    ) -> Optional[str]:
        """Bind one conversation to the Agent session that owns its memory.

        `force_new` is only ever true for a conversation the `/new` command entry
        just created: that conversation owes the user an empty context, while
        every other conversation must land back on the session it already has.

        Returns the host's session id, or None when this host cannot name one —
        an absent binding is recorded as absent rather than filled with an
        invented id.
        """
        store = getattr(self, "_session_store", None)
        entry: Any = None
        if store is not None:
            try:
                entry = await asyncio.to_thread(
                    store.get_or_create_session, self._agent_source(conversation_id, account_id), force_new,
                )
            except Exception as exc:
                logger.warning(
                    "[open_android] Agent session lookup failed for %s: %s", conversation_id, exc
                )
                entry = None
        # The binding write touches SQLite, so it is offloaded like the lookup: a
        # per-message path must not block the loop that serves the event stream.
        await asyncio.to_thread(
            self._record_agent_session_binding, account_id, conversation_id, entry, created_via,
        )
        return getattr(entry, "session_id", None) if entry is not None else None

    def _record_agent_session_binding(
        self, account_id: str, conversation_id: str, entry: Any, created_via: str,
    ) -> None:
        """Persist the binding, including the honest case where there is none yet."""
        agent_session_id = getattr(entry, "session_id", None) if entry is not None else None
        session_key = getattr(entry, "session_key", None) if entry is not None else None
        try:
            account = self.services.core.open_gateway_account(account_id)
        except Exception as exc:
            logger.warning("[open_android] Account %s is unavailable for binding: %s", account_id, exc)
            return
        try:
            with account.store.transaction():
                account.agent_sessions.record(conversation_id, created_via)
                if isinstance(agent_session_id, str) and agent_session_id:
                    account.agent_sessions.attach(conversation_id, agent_session_id, session_key)
        except Exception as exc:
            logger.warning(
                "[open_android] Agent session binding failed for %s: %s", conversation_id, exc
            )
        finally:
            account.close()

    async def _notify_agent_inbound(
        self, path: str, body_bytes: bytes, response_body: Any, account_id: str | None = None,
    ) -> None:
        """Notify Hermes agent of a user message received from the Android device."""
        source_account = account_id or self._account_id
        if not source_account:
            logger.warning("[open_android] Inbound message carries no account identity; not dispatching")
            return
        try:
            conv_id = _conversation_id_of(path) or "default"
            self._conv_to_account[conv_id] = source_account
            # A user turn boundary: whatever the Agent answered before belongs to
            # the previous turn, so the same text from now on is a new message.
            self._forget_turn_reply(conv_id)

            data = json.loads(body_bytes.decode("utf-8"))
            user_text = data.get("text") or data.get("content") or ""
            client_turn = data.get("clientTurnId") or str(uuid.uuid4())

            if user_text.strip() == NEW_CONVERSATION_COMMAND:
                # The Gateway's own command entry owns `/new` (contract §7.1): it
                # has already created the conversation and answered with its id.
                # Handing the same text to the Agent would make the host run its
                # own new-session command on the *source* conversation, wiping the
                # memory the user is still reading.
                logger.info("[open_android] Reserved /new stays a command entry; not dispatched to the agent")
                return

            await self._ensure_agent_session(
                conv_id, source_account, force_new=False,
                created_via=AgentSessionBindings.CREATED_VIA_INBOUND_MESSAGE,
            )

            event = MessageEvent(
                text=user_text,
                source=self._agent_source(conv_id, source_account),
                message_type=MessageType.TEXT,
                message_id=client_turn,
            )
            logger.info("[open_android] Dispatching message from Android to Hermes Agent: %r", user_text[:60])
            await self.handle_message(event)
        except Exception as exc:
            logger.warning("[open_android] Failed to dispatch inbound message to agent: %s", exc)

    async def _handle_sse_stream(
        self, request: web.Request, raw_req: Mapping[str, Any],
    ) -> web.StreamResponse:
        """Authenticated SSE stream with durable, cursor-authoritative recovery.

        The route performs the handshake (signature, cursor, expiry) and returns
        the events to replay; this method only writes them, so there is exactly
        one place that decides who may read an account's stream.
        """
        route = next(
            (item for item in self.services.exposure.routes if item.path == EVENT_STREAM_PATH),
            None,
        )
        if route is None:
            return web.json_response({"errorCode": "NOT_FOUND"}, status=404)

        queue: asyncio.Queue = asyncio.Queue(maxsize=SSE_QUEUE_SIZE)
        registered_account_id: Optional[str] = None

        try:
            handshake = route.event_backlog(raw_req)
            status = int(handshake.get("statusCode", 200))
            if status != 200:
                return web.json_response(handshake.get("body", {}), status=status)
            account_id = str(handshake["accountId"])
            registered_account_id = account_id
            self._event_subscribers.setdefault(account_id, set()).add(queue)

            response = web.StreamResponse(
                status=200,
                reason="OK",
                headers={
                    "Content-Type": "text/event-stream",
                    "Cache-Control": "no-store",
                    "Connection": "keep-alive",
                },
            )
            await response.prepare(request)
            await response.write(SSE_HEARTBEAT)

            seen_event_ids: dict[str, None] = {}
            headers = raw_req.get("headers") or {}
            last_event_id = headers.get("last-event-id") or headers.get("Last-Event-Id")
            if last_event_id:
                seen_event_ids[str(last_event_id)] = None
            query = parse_qs(urlsplit(raw_req.get("url") or raw_req.get("target") or "").query)
            for c in query.get("cursor", ()):
                seen_event_ids[unquote(c)] = None

            for event in handshake.get("events", []):
                eid = event.get("eventId") or event.get("id")
                if eid:
                    seen_event_ids[str(eid)] = None
                await response.write(_sse_frame(event))

            while self._running:
                try:
                    frame = await asyncio.wait_for(queue.get(), timeout=SSE_HEARTBEAT_SECONDS)
                except asyncio.TimeoutError:
                    if not self._running:
                        break
                    await response.write(SSE_HEARTBEAT)
                    continue
                if not self._running:
                    break
                eid = _extract_event_id_from_item(frame)
                if eid is not None and eid in seen_event_ids:
                    continue
                if eid is not None:
                    seen_event_ids[eid] = None
                    if len(seen_event_ids) > 5000:
                        for k in list(seen_event_ids.keys())[:2500]:
                            seen_event_ids.pop(k, None)
                if isinstance(frame, Mapping):
                    frame = _sse_frame(frame)
                elif isinstance(frame, str):
                    frame = frame.encode("utf-8")
                await response.write(frame)
        except (asyncio.CancelledError, ConnectionResetError):
            pass
        finally:
            if registered_account_id:
                subscribers = self._event_subscribers.get(registered_account_id)
                if subscribers:
                    subscribers.discard(queue)
                    if not subscribers:
                        self._event_subscribers.pop(registered_account_id, None)

        return response

    async def _handle_ws_stream(
        self, request: web.Request, raw_req: Mapping[str, Any],
    ) -> web.StreamResponse:
        """Authenticated WebSocket event stream with durable, cursor-authoritative recovery.

        The route performs the handshake (signature, cursor, expiry) and returns
        the events to replay; this method sets up the WebSocket connection, replays
        the backlog, and subscribes to real-time events while handling incoming
        pings and connection lifecycle events.
        """
        route = next(
            (item for item in self.services.exposure.routes if item.path == EVENT_STREAM_PATH),
            None,
        )
        if route is None:
            return web.json_response({"errorCode": "NOT_FOUND"}, status=404)

        queue: asyncio.Queue = asyncio.Queue(maxsize=SSE_QUEUE_SIZE)
        registered_account_id: Optional[str] = None

        try:
            handshake = route.event_backlog(raw_req)
            status = int(handshake.get("statusCode", 200))
            if status != 200:
                return web.json_response(handshake.get("body", {}), status=status)
            account_id = str(handshake["accountId"])
            registered_account_id = account_id
            self._event_subscribers.setdefault(account_id, set()).add(queue)

            ws = web.WebSocketResponse(heartbeat=15.0)
            await ws.prepare(request)

            seen_event_ids: dict[str, None] = {}
            headers = raw_req.get("headers") or {}
            last_event_id = headers.get("last-event-id") or headers.get("Last-Event-Id")
            if last_event_id:
                seen_event_ids[str(last_event_id)] = None
            query = parse_qs(urlsplit(raw_req.get("url") or raw_req.get("target") or "").query)
            for c in query.get("cursor", ()):
                seen_event_ids[unquote(c)] = None

            for event in handshake.get("events", []):
                eid = event.get("eventId") or event.get("id")
                if eid:
                    seen_event_ids[str(eid)] = None
                await ws.send_str(_ws_event_frame(event))

            async def send_loop() -> None:
                while self._running and not ws.closed:
                    try:
                        item = await asyncio.wait_for(queue.get(), timeout=1.0)
                    except asyncio.TimeoutError:
                        if not self._running:
                            if not ws.closed:
                                try:
                                    await ws.close()
                                except Exception:
                                    pass
                            break
                        continue
                    except asyncio.CancelledError:
                        break
                    except Exception:
                        break

                    if not self._running:
                        if not ws.closed:
                            try:
                                await ws.close()
                            except Exception:
                                pass
                        break

                    eid = _extract_event_id_from_item(item)
                    if eid is not None and eid in seen_event_ids:
                        continue
                    if eid is not None:
                        seen_event_ids[eid] = None
                        if len(seen_event_ids) > 5000:
                            for k in list(seen_event_ids.keys())[:2500]:
                                seen_event_ids.pop(k, None)

                    msg = _ws_message_from_queue_item(item)
                    if msg is not None and not ws.closed:
                        try:
                            await ws.send_str(msg)
                        except (asyncio.CancelledError, ConnectionResetError):
                            break
                        except Exception as send_err:
                            logger.debug("[open_android] WebSocket send_str error: %s", send_err)
                            break

            async def receive_loop() -> None:
                while self._running and not ws.closed:
                    try:
                        msg = await ws.receive()
                    except (asyncio.CancelledError, ConnectionResetError):
                        break
                    if msg.type == WSMsgType.TEXT:
                        text_stripped = msg.data.strip().lower()
                        if text_stripped == "ping":
                            if not ws.closed:
                                try:
                                    await ws.send_str("pong")
                                except (asyncio.CancelledError, ConnectionResetError):
                                    break
                        elif text_stripped == "close":
                            if not ws.closed:
                                await ws.close()
                            break
                    elif msg.type == WSMsgType.PING:
                        if not ws.closed:
                            try:
                                await ws.pong(msg.data)
                            except (asyncio.CancelledError, ConnectionResetError):
                                break
                    elif msg.type == WSMsgType.PONG:
                        pass
                    elif msg.type in (
                        WSMsgType.CLOSE,
                        WSMsgType.CLOSING,
                        WSMsgType.CLOSED,
                        WSMsgType.ERROR,
                    ):
                        break

            send_task = asyncio.create_task(send_loop())
            receive_task = asyncio.create_task(receive_loop())
            done, pending = await asyncio.wait(
                [send_task, receive_task],
                return_when=asyncio.FIRST_COMPLETED,
            )
            for t in pending:
                t.cancel()
                try:
                    await t
                except asyncio.CancelledError:
                    pass
            for t in done:
                exc = t.exception()
                if exc and not isinstance(exc, (asyncio.CancelledError, ConnectionResetError)):
                    logger.debug("[open_android] WebSocket task completed with error: %s", exc)
        except (asyncio.CancelledError, ConnectionResetError):
            pass
        finally:
            if registered_account_id:
                subscribers = self._event_subscribers.get(registered_account_id)
                if subscribers:
                    subscribers.discard(queue)
                    if not subscribers:
                        self._event_subscribers.pop(registered_account_id, None)

        return ws

    def _deliver_committed_event(self, account_id: str, event: Mapping[str, Any]) -> None:
        """Immediate delivery hook for every event a committed write produced.

        The core calls this from whatever thread committed the write, which is not
        necessarily the loop that owns the subscriber queues, so the frame is
        handed to that loop rather than mutating its queues from the wrong
        thread. A boundary that never started the server has no subscribers to
        serve: the event stays durable and the phone's cursor recovers it.
        """
        frame = _sse_frame(event)
        if event.get("eventType") == "conversation.command.result":
            self._bind_created_conversation(account_id, event.get("payload"))
        loop = self._loop
        if loop is None or loop.is_closed():
            return
        try:
            running: Optional[asyncio.AbstractEventLoop] = asyncio.get_running_loop()
        except RuntimeError:
            running = None
        if running is loop:
            self._enqueue_frame(account_id, frame)
            return
        try:
            loop.call_soon_threadsafe(self._enqueue_frame, account_id, frame)
        except RuntimeError:
            # The loop stopped between the check and the hand-off; the event is
            # still durable and readable through its cursor.
            return

    def _bind_created_conversation(self, account_id: str, payload: Any) -> None:
        """Give a conversation the `/new` entry created an Agent session of its own.

        This is the "Agent host generates the agent session" half of ADR 0043: the
        command entry decides *that* a new conversation exists, and the host decides
        *which* session owns it. It is forced new because the whole point of `/new`
        is a context that inherits nothing from the conversation it was sent from.
        """
        if not isinstance(payload, Mapping):
            return
        if payload.get("outcome") != "created-conversation":
            return
        conversation_id = payload.get("conversationId")
        if not isinstance(conversation_id, str) or not conversation_id:
            return
        self._schedule_agent_session(
            conversation_id, account_id,
            force_new=True, created_via=AgentSessionBindings.CREATED_VIA_NEW_COMMAND,
        )

    def _enqueue_frame(self, account_id: str, frame: bytes) -> None:
        """Hand one persisted frame to the subscribers of that account.

        A subscriber whose queue is full is skipped rather than blocking the
        agent turn: every frame carries a durable event id, so the phone resumes
        from its last complete frame and replays the gap instead of losing it.
        """
        for queue in list(self._event_subscribers.get(account_id, ())):
            try:
                queue.put_nowait(frame)
            except asyncio.QueueFull:
                logger.warning(
                    "[open_android] Event subscriber queue is full; client will resume from cursor"
                )

    # There is deliberately no second delivery entry point: every frame a
    # subscriber sees went through [self._enqueue_frame], so the account scoping
    # and the queue-full policy exist in exactly one place.


