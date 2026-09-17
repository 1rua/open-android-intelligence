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

from .local_keys import master_key_unavailable_reason
from .core import (
    VerifiedGatewayRequest,
    VerifiedRequestContext,
    canonicalize_target,
    iso_millis,
    request_signature_preimage,
)

try:
    from aiohttp import web
    AIOHTTP_AVAILABLE = True
except ImportError:
    web = None  # type: ignore
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

        def set_message_handler(self, handler: Any) -> None:
            self._message_handler = handler

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
        if method not in {"GET", "POST", "PUT", "DELETE"} or not isinstance(target, str):
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
# Heartbeats are SSE comments: they carry no event id, so a client cannot mistake
# one for a resumable event.
SSE_HEARTBEAT = b": ping\n\n"
SSE_HEARTBEAT_SECONDS = 15.0
SSE_QUEUE_SIZE = 100


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
        self._active_sse_queues: Dict[str, Set[asyncio.Queue]] = {}
        self._conv_to_account: Dict[str, str] = {}

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
            return False

    async def disconnect(self) -> None:
        """Stop the Gateway Protocol v2 server."""
        self._running = False
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
            message_id = f"msg_{uuid.uuid4().hex[:12]}"
            await self.complete_message(chat_id, message_id, content, occurred_at=now_iso, account_id=target_account)
            return SendResult(success=True, message_id=message_id)
        except Exception as exc:
            logger.error("[open_android] Failed to deliver message to %s: %s", chat_id, exc)
            return SendResult(success=False, error=str(exc), retryable=False)

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
        """Persist one assistant event, then hand its frame to the stream.

        Persisting first is what makes the SSE `id:` a real cursor: after a
        disconnect the phone replays the event from the account store instead of
        depending on this process still holding it in memory.
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
            event = account.events.append(event_type, message_id, payload, occurred_at)
        finally:
            account.close()
        await self._broadcast_sse(target_account, _sse_frame(event))

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

    # Host-adapter naming aliases.
    streamDelta = stream_delta
    completeMessage = complete_message

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

        # If this was an inbound message POST, trigger Hermes agent turn
        if method == "POST" and "/conversations/" in path and path.endswith("/messages") and status in (200, 201):
            asyncio.create_task(self._notify_agent_inbound(
                path, body_bytes, body,
                (raw_req["headers"].get("X-Open-Android-Intelligence-Account")
                 or raw_req["headers"].get("x-open-android-intelligence-account")),
            ))

        clean_headers = {k: v for k, v in headers.items() if k.lower() != "content-type"}
        return web.json_response(body, status=status, headers=clean_headers)

    async def _notify_agent_inbound(
        self, path: str, body_bytes: bytes, response_body: Any, account_id: str | None = None,
    ) -> None:
        """Notify Hermes agent of a user message received from the Android device."""
        source_account = account_id or self._account_id
        if not source_account:
            logger.warning("[open_android] Inbound message carries no account identity; not dispatching")
            return
        try:
            parts = path.split("/")
            # /open-android-intelligence/v2/conversations/{conv_id}/messages
            conv_id = "default"
            for i, p in enumerate(parts):
                if p == "conversations" and i + 1 < len(parts):
                    conv_id = parts[i + 1]
                    break
            self._conv_to_account[conv_id] = source_account

            data = json.loads(body_bytes.decode("utf-8"))
            user_text = data.get("text") or data.get("content") or ""
            client_turn = data.get("clientTurnId") or str(uuid.uuid4())

            source = self.build_source(
                chat_id=conv_id,
                chat_name="Android Client",
                chat_type="dm",
                user_id=source_account,
                user_name=source_account,
            )
            event = MessageEvent(
                text=user_text,
                source=source,
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
        handshake = route.event_backlog(raw_req)
        status = int(handshake.get("statusCode", 200))
        if status != 200:
            return web.json_response(handshake.get("body", {}), status=status)
        account_id = str(handshake["accountId"])

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

        queue: asyncio.Queue = asyncio.Queue(maxsize=SSE_QUEUE_SIZE)
        subscribers = self._active_sse_queues.setdefault(account_id, set())
        subscribers.add(queue)
        try:
            for event in handshake["events"]:
                await response.write(_sse_frame(event))
            while self._running:
                try:
                    frame = await asyncio.wait_for(queue.get(), timeout=SSE_HEARTBEAT_SECONDS)
                except asyncio.TimeoutError:
                    await response.write(SSE_HEARTBEAT)
                    continue
                await response.write(frame)
        except (asyncio.CancelledError, ConnectionResetError):
            pass
        finally:
            subscribers.discard(queue)
            if not subscribers:
                self._active_sse_queues.pop(account_id, None)

        return response

    async def _broadcast_sse(self, account_id: str, frame: bytes) -> None:
        """Hand one persisted frame to the subscribers of that account.

        A subscriber whose queue is full is skipped rather than blocking the
        agent turn: every frame carries a durable event id, so the phone resumes
        from its last complete frame and replays the gap instead of losing it.
        """
        for queue in list(self._active_sse_queues.get(account_id, ())):
            try:
                queue.put_nowait(frame)
            except asyncio.QueueFull:
                logger.warning(
                    "[open_android] SSE subscriber is behind; it will resume from its cursor"
                )

