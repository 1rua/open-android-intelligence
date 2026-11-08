"""Generic HTTP/SSE remote session adapter for OAI Core.

Provides standard HTTP session bridge communication, capability negotiation,
streaming responses (SSE / NDJSON), error translation, and compact UTF-8 JSON budgets.
Both deployment-shared and account-isolated remote agents can build on this.
"""
from __future__ import annotations

from ..native_diagnostics import current_trace_headers

import asyncio
import hashlib
import json
import logging
import re
import time
import uuid
from typing import Any, AsyncIterator, Mapping
from urllib.parse import quote, urlencode, urlsplit

import aiohttp

from ..auth import Principal
from ..gateway.core import _public_approval
from ..sessions import CAPABILITIES, STREAM_EVENT_TYPES, AdapterError, NativeAttachment, session_request_json

logger = logging.getLogger(__name__)

REMOTE_PREFIX = "/oai/session-adapter/v1"
# Match the existing native Codex frame budget and stay below Android's SSE
# frame budget. aiohttp's default line budget is too small for final snapshots.
MAX_STREAM_EVENT_BYTES = 8 * 1024 * 1024
STREAM_HEARTBEAT_IDLE_SECONDS = 60.0
CONNECT_TIMEOUT_SECONDS = 5.0
BRIDGE_AUTH_CODES = frozenset({"AUTHENTICATION_REQUIRED", "AUTHENTICATION_FAILED", "UNAUTHORIZED", "FORBIDDEN"})


def _transport_log(level: int, message: str, *args: Any) -> None:
    try:
        logger.log(level, message, *args)
    except Exception:
        # A failed diagnostic sink must not replace the actual request result.
        pass


class RemoteSessionAdapter:
    """Standard HTTP bridge adapter for external Agent hosts."""

    def __init__(
        self,
        base_url: str,
        token: str,
        *,
        agent_id: str,
        name: str,
        kind: str,
        session_scope: str = "deployment",
        prefix: str = REMOTE_PREFIX,
        device_tools: bool = False,
        timeout_seconds: float = 30.0,
        stream_timeout_seconds: float = 1800.0,
        headers_builder: Any = None,
        capabilities: Mapping[str, bool] | None = None,
        attachment_core: Any = None,
        forward_client_message_ids: bool = False,
        origin_registration: Any = None,
    ):
        parsed = urlsplit(base_url)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
            or parsed.path not in {"", "/"}
        ):
            raise ValueError("Remote adapter URL must be an explicit HTTP origin")
        if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("Remote adapter requires HTTPS outside loopback")
        if not isinstance(token, str) or len(token) < 32:
            raise ValueError("Remote adapter token must contain at least 32 characters")
        if session_scope not in {"account", "deployment"}:
            raise ValueError("session_scope must be 'account' or 'deployment'")

        self.base_url = base_url.rstrip("/")
        self.token = token
        self.agent_id = agent_id
        self.name = name
        self.kind = kind
        self.session_scope = session_scope
        self.prefix = prefix.rstrip("/")
        self.device_tools = device_tools
        self.timeout_seconds = timeout_seconds
        self.stream_timeout_seconds = stream_timeout_seconds
        self.headers_builder = headers_builder
        self.attachment_core = attachment_core
        self._configured_client_message_ids = forward_client_message_ids
        self.forward_client_message_ids = forward_client_message_ids
        self.origin_registration = origin_registration
        self._capabilities_verified = capabilities is not None
        self.health_failure: dict[str, Any] | None = None
        self._stream_heartbeat = False
        self._last_transport_failure: tuple[str, int, int | None] | None = None
        self._last_transport_failure_at = 0.0
        self.capabilities = (
            dict(capabilities) if capabilities is not None else dict.fromkeys(CAPABILITIES, False)
        )

    def descriptor(self) -> dict[str, Any]:
        return {
            "id": self.agent_id,
            "name": self.name,
            "kind": self.kind,
            "sessionScope": self.session_scope,
            "deviceTools": self.device_tools,
            "capabilities": dict(self.capabilities),
        }

    def _bridge_failure(self, reason: str, *, status: int = 503, upstream_status: int | None = None) -> AdapterError:
        details: dict[str, Any] = {"source": "agent_bridge", "reason": reason}
        if upstream_status is not None:
            details["upstreamStatus"] = upstream_status
        headers = current_trace_headers()
        request_id = headers.get("X-OAI-Request-Id")
        key = (reason, status, upstream_status)
        now = time.monotonic()
        if request_id is not None or key != self._last_transport_failure or now - self._last_transport_failure_at >= 60:
            _transport_log(logging.WARNING, "agent_transport agent=%s source=agent_bridge reason=%s status=%s upstreamStatus=%s requestId=%s traceId=%s",
                           self.agent_id, reason, status, upstream_status, request_id,
                           headers.get("traceparent", "")[3:35] or None)
            self._last_transport_failure, self._last_transport_failure_at = key, now
        return AdapterError("AGENT_UNAVAILABLE", status, details=details)

    def _headers(self, principal: Principal | None = None) -> dict[str, str]:
        if self.headers_builder is None or principal is None:
            return {}
        try:
            return self.headers_builder(principal)
        except AdapterError as exc:
            # An Agent account binding is separate from the phone's Core login.
            if exc.code == "AGENT_ACCOUNT_NOT_CONNECTED":
                raise self._bridge_failure("account_not_connected", status=502) from None
            raise

    async def _prepare_headers(self, headers: dict[str, str] | None, method: str, path: str,
                               body: Any = None) -> dict[str, str] | None:
        return headers

    def _session(self, headers: dict[str, str] | None = None) -> aiohttp.ClientSession:
        combined_headers = {"Authorization": f"Bearer {self.token}", **current_trace_headers(), **(headers or {})}
        return aiohttp.ClientSession(
            headers=combined_headers,
            timeout=aiohttp.ClientTimeout(total=self.timeout_seconds,
                                          sock_connect=min(CONNECT_TIMEOUT_SECONDS, self.timeout_seconds)),
            json_serialize=session_request_json,
        )

    def _response_error(self, status: int, value: Mapping[str, Any], *, details: Mapping[str, Any] | None = None) -> AdapterError:
        if status in {401, 403}:
            reason = "authentication_failed" if status == 401 else "permission_denied"
            return self._bridge_failure(reason, status=502, upstream_status=status)
        code = value.get("code", "AGENT_UNAVAILABLE")
        if not isinstance(code, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,127}", code):
            code = "AGENT_UNAVAILABLE"
        if code in BRIDGE_AUTH_CODES:
            return self._bridge_failure("authentication_failed", status=502, upstream_status=status)
        return AdapterError(code, status, details=details)

    async def _request(
        self,
        method: str,
        path: str,
        body: Any = None,
        *,
        headers: dict[str, str] | None = None,
        success_json_errors_are_protocol: bool = False,
    ) -> Any:
        try:
            async with self._session(await self._prepare_headers(headers, method, path, body)) as session:
                async with session.request(
                    method, self.base_url + self.prefix + path, json=body, allow_redirects=False,
                ) as response:
                    # Authentication errors may have an HTML/empty body. Their
                    # source is known before parsing any untrusted error text.
                    if response.status in {401, 403}:
                        raise self._response_error(response.status, {})
                    try:
                        value = await response.json()
                    except (aiohttp.ContentTypeError, json.JSONDecodeError, UnicodeDecodeError) as exc:
                        if success_json_errors_are_protocol and response.status == 200:
                            raise AdapterError("HOST_INCOMPATIBLE", 502) from exc
                        raise
                    if not isinstance(value, dict):
                        raise AdapterError("HOST_INCOMPATIBLE", 502)
                    if response.status != 200:
                        details = value.get("details")
                        public = (_public_approval(details) if isinstance(details, Mapping)
                                  and "/approvals/" in path and path.endswith("/decisions") else {})
                        raise self._response_error(response.status, value, details=public)
                    return value
        except AdapterError as exc:
            if exc.code == "AGENT_ACCOUNT_NOT_CONNECTED":
                raise self._bridge_failure("account_not_connected", status=502) from None
            raise
        except (aiohttp.ClientConnectorCertificateError, aiohttp.ClientSSLError) as exc:
            raise self._bridge_failure("tls_rejected", status=502) from exc
        except TimeoutError as exc:
            raise self._bridge_failure("timeout") from exc
        except (aiohttp.ClientError, ValueError) as exc:
            raise self._bridge_failure("transport_failed") from exc

    async def status(self) -> bool:
        try:
            result = await self._request("GET", "/status", success_json_errors_are_protocol=True)
            online = result.get("online")
            capabilities = result.get("capabilities", {})
            if (type(online) is not bool or not isinstance(capabilities, Mapping)
                    or any(type(value) is not bool for value in capabilities.values())):
                raise AdapterError("HOST_INCOMPATIBLE", 502)
            if online is not True:
                if not self._capabilities_verified:
                    # An authenticated read-only bridge may advertise useful
                    # capabilities before its executor is ready. Absence still
                    # means unknown until a full online negotiation succeeds.
                    self.capabilities = {key: self.capabilities.get(key) is True or capabilities.get(key) is True
                                         for key in CAPABILITIES}
                self.health_failure = {"kind": "unavailable", "code": "AGENT_UNAVAILABLE", "source": "agent_bridge"}
                return False
            # Commit only a complete successful observation. A failed probe
            # cannot withdraw capabilities or change the next message's identity.
            snapshot = {key: capabilities.get(key) is True for key in CAPABILITIES}
            identity = self._configured_client_message_ids or capabilities.get("clientMessageIdentity") is True
            self.capabilities = snapshot
            self.forward_client_message_ids = identity
            self._stream_heartbeat = capabilities.get("streamHeartbeat") is True
            self._capabilities_verified = True
            if self._last_transport_failure is not None:
                _transport_log(logging.INFO, "agent_transport agent=%s source=agent_bridge stage=recovered", self.agent_id)
            self._last_transport_failure = None
            self.health_failure = None
            return True
        except AdapterError as exc:
            reason = exc.details.get("reason")
            kind = ("authentication" if reason in {"authentication_failed", "permission_denied", "account_not_connected"}
                    else "security" if reason == "tls_rejected"
                    else "protocol" if exc.code == "HOST_INCOMPATIBLE" else "transient")
            self.health_failure = {"kind": kind, "code": exc.code, "source": "agent_bridge"}
            return False

    async def commands(self, language_code: str = "en", *, principal: Principal | None = None) -> list[dict[str, Any]]:
        result = await self._request("GET", f"/commands?languageCode={quote(language_code, safe='')}",
                                     headers=self._headers(principal))
        cmds = result.get("commands", [])
        if not isinstance(cmds, list):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        return cmds

    async def resolve_approval(self, principal: Principal, session_id: str,
                               approval_id: str, decision: str) -> dict[str, Any]:
        return await self._request("POST",
            f"/sessions/{quote(session_id, safe='')}/approvals/{quote(approval_id, safe='')}/decisions",
            {"decision": decision}, headers=self._headers(principal))

    async def list_sessions(self, principal: Principal) -> list[dict[str, Any]]:
        headers = self._headers(principal)
        result = await self._request("GET", "/sessions", headers=headers)
        sessions = result.get("sessions")
        if not isinstance(sessions, list):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        return sessions

    async def history(self, principal: Principal, session_id: str) -> list[dict[str, Any]]:
        headers = self._headers(principal)
        result = await self._request("GET", f"/sessions/{quote(session_id, safe='')}/history", headers=headers)
        messages = result.get("messages")
        if not isinstance(messages, list):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        if self.attachment_core is not None and any(message.get("attachments") for message in messages):
            account = self.attachment_core.open_gateway_account(principal.account_id)
            try:
                for message in messages:
                    for attachment in message.get("attachments", []):
                        key = self._attachment_key(str(attachment.get("attachmentId", "")))
                        row = account.store.database.execute("SELECT value FROM account_metadata WHERE key=?", (key,)).fetchone()
                        if row:
                            attachment.update(account.store.open_json(row[0], key))
            finally:
                account.close()
        return messages

    async def current_generation(self, principal: Principal, session_id: str) -> dict[str, Any] | None:
        result = await self._request(
            "GET",
            f"/sessions/{quote(session_id, safe='')}/generation",
            headers=self._headers(principal),
        )
        generation = result.get("generation")
        if generation is not None and not isinstance(generation, dict):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        return generation

    async def generation(self, principal: Principal, session_id: str, *, client_message_id: str | None = None,
                         generation_id: str | None = None, after_revision: int | None = None,
                         wait_ms: int = 0) -> dict[str, Any] | None:
        query = {key: value for key, value in (("clientMessageId", client_message_id), ("generationId", generation_id))
                 if value is not None}
        if not query:
            raise AdapterError("INVALID_GENERATION_QUERY")
        if after_revision is not None:
            query["afterRevision"] = after_revision
        if wait_ms:
            query["waitMs"] = wait_ms
        result = await self._request(
            "GET", f"/sessions/{quote(session_id, safe='')}/generation?{urlencode(query)}",
            headers=self._headers(principal),
        )
        generation = result.get("generation")
        if generation is not None and not isinstance(generation, dict):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        return generation

    async def event_page(self, principal: Principal, session_id: str, *, consumer_id: str,
                         cursor: str | None = None, limit: int = 128, wait_ms: int = 0,
                         snapshot: bool = False) -> dict[str, Any]:
        query = {"limit": limit, "waitMs": wait_ms}
        if cursor is not None:
            query["cursor"] = cursor
        if snapshot:
            query["snapshot"] = "true"
        headers = self._headers(principal)
        headers["X-OAI-Observer"] = consumer_id
        return await self._request("GET", f"/sessions/{quote(session_id, safe='')}/events?{urlencode(query)}",
            headers=headers)

    async def ack_events(self, principal: Principal, session_id: str, *, consumer_id: str, cursor: str) -> dict[str, Any]:
        headers = self._headers(principal)
        headers["X-OAI-Observer"] = consumer_id
        return await self._request("POST", f"/sessions/{quote(session_id, safe='')}/events/ack", {"cursor": cursor},
            headers=headers)

    def _attachment_key(self, attachment_id: str) -> str:
        return "native-attachment:" + hashlib.sha256((self.agent_id + ":" + attachment_id).encode()).hexdigest()

    def _remember_attachment(self, attachment: NativeAttachment, remote_id: str) -> None:
        self.attachment_core = attachment.core
        key = self._attachment_key(remote_id)
        metadata = {name: attachment.metadata[name] for name in ("attachmentId", "filename", "mediaType", "sizeBytes", "sha256")}
        metadata["sha256"] = "sha256:" + str(metadata["sha256"]).removeprefix("sha256:")
        account = attachment.core.open_gateway_account(attachment.principal.account_id)
        try:
            with account.store.transaction():
                account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    (key, account.store.seal_json(metadata, key)))
        finally:
            account.close()

    async def create(self, principal: Principal, title: str | None) -> dict[str, Any]:
        headers = self._headers(principal)
        result = await self._request("POST", "/sessions", {"title": title}, headers=headers)
        return result.get("session", {})

    async def send(self, principal: Principal, session_id: str, text: str, *,
                   attachments: list[NativeAttachment] | None = None,
                   client_message_id: str | None = None) -> AsyncIterator[dict[str, Any]]:
        headers = self._headers(principal)
        body: dict[str, Any] = {"text": text}
        if self.origin_registration is not None and getattr(principal, "context", None) is not None:
            turn_id = client_message_id or "core_" + uuid.uuid4().hex
            headers["X-OAI-Origin-Context"] = self.origin_registration.issue_origin(
                principal, agent_id=self.agent_id, session_id=session_id, turn_id=turn_id)
            client_message_id = turn_id
        if client_message_id is not None and self.forward_client_message_ids:
            body["clientMessageId"] = client_message_id
        if attachments:
            if not self.capabilities.get("attachments"):
                raise AdapterError("UNSUPPORTED_CAPABILITY", 405)
            for attachment in attachments:
                await attachment.assert_active()
            body["attachmentIds"] = [await self._upload_attachment(item, headers, client_message_id) for item in attachments]
            # Upload can outlive its authenticated session. Recheck immediately
            # before the native message mutation, after the last file arrives.
            for attachment in attachments:
                await attachment.assert_active()
        async for event in self._send(session_id, text, headers=headers, body=body):
            yield event

    async def steer(self, principal: Principal, session_id: str, text: str, generation_id: str,
                    client_message_id: str | None = None) -> dict[str, Any]:
        body: dict[str, Any] = {"text": text, "generationId": generation_id}
        if client_message_id is not None:
            body["clientMessageId"] = client_message_id
        result = await self._request(
            "POST",
            f"/sessions/{quote(session_id, safe='')}/steer",
            body,
            headers=self._headers(principal),
        )
        steering = result.get("steering")
        if not isinstance(steering, dict):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        return steering

    async def _upload_attachment(self, attachment: NativeAttachment, headers: dict[str, str], client_message_id: str | None) -> str:
        metadata = attachment.metadata
        result = await self._request("POST", "/attachments", {
            "clientAttachmentId": hashlib.sha256((metadata["attachmentId"] + ":" + (client_message_id or uuid.uuid4().hex)).encode()).hexdigest(),
            **{key: metadata[key] for key in ("filename", "mediaType", "sizeBytes", "sha256")}}, headers=headers)
        attachment_id = result.get("attachmentId")
        if not isinstance(attachment_id, str):
            raise AdapterError("HOST_INCOMPATIBLE", 502)
        if result.get("state") == "verified":
            self._remember_attachment(attachment, attachment_id)
            return attachment_id
        async def chunks():
            # Each source chunk is bounded by Core's encrypted attachment format.
            iterator = iter(attachment.chunks())
            def next_chunk():
                try:
                    return next(iterator), False
                except StopIteration:
                    return b"", True
            while True:
                chunk, done = await asyncio.to_thread(next_chunk)
                if done:
                    break
                yield chunk
        try:
            path = f"/attachments/{quote(attachment_id, safe='')}/content"
            headers["X-OAI-Content-SHA256"] = str(metadata["sha256"]).removeprefix("sha256:")
            async with self._session(await self._prepare_headers(headers, "PUT", path)) as session:
                async with session.put(self.base_url + self.prefix + path,
                        data=chunks(), timeout=aiohttp.ClientTimeout(total=self.stream_timeout_seconds), allow_redirects=False) as response:
                    if response.status in {401, 403}:
                        raise self._response_error(response.status, {})
                    value = await response.json()
                    if not isinstance(value, Mapping):
                        raise AdapterError("HOST_INCOMPATIBLE", 502)
                    if response.status != 200:
                        raise self._response_error(response.status, value)
                    if value.get("status") != "uploaded":
                        raise AdapterError("ATTACHMENT_READ_FAILED", 502)
        except (aiohttp.ClientError, TimeoutError, ValueError) as exc:
            raise AdapterError("ATTACHMENT_UPLOAD_FAILED", 502) from exc
        self._remember_attachment(attachment, attachment_id)
        return attachment_id

    async def _stream_lines(self, response: aiohttp.ClientResponse) -> AsyncIterator[bytes]:
        # 空闲预算随实际字节到达重置，不能把完整一帧的组装时间当作空闲。
        idle_seconds = STREAM_HEARTBEAT_IDLE_SECONDS if self._stream_heartbeat else self.stream_timeout_seconds
        pending = bytearray()
        while True:
            async with asyncio.timeout(idle_seconds):
                chunk = await response.content.readany()
            if not chunk:
                if pending:
                    yield bytes(pending)
                return
            parts = chunk.split(b"\n")
            for index, part in enumerate(parts):
                pending.extend(part)
                complete = index < len(parts) - 1
                if len(pending) + int(complete) > MAX_STREAM_EVENT_BYTES:
                    raise AdapterError("AGENT_STREAM_TOO_LARGE", 502)
                if complete:
                    pending.append(10)
                    yield bytes(pending)
                    pending.clear()

    async def _send(
        self,
        session_id: str,
        text: str,
        *,
        headers: dict[str, str] | None = None,
        body: dict[str, Any] | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        try:
            path = f"/sessions/{quote(session_id, safe='')}/messages"
            request_body = body if body is not None else {"text": text}
            async with self._session(await self._prepare_headers(headers, "POST", path, request_body)) as session:
                # 桥在首帧前仍需完成认证和宿主命令；此时尚不能发送保活字节。
                async with asyncio.timeout(self.stream_timeout_seconds):
                    response = await session.post(
                        self.base_url + self.prefix + path,
                        json=request_body,
                        timeout=aiohttp.ClientTimeout(
                            total=None, connect=CONNECT_TIMEOUT_SECONDS, sock_connect=CONNECT_TIMEOUT_SECONDS,
                            sock_read=None,
                        ),
                        allow_redirects=False,
                        read_bufsize=MAX_STREAM_EVENT_BYTES // 2,
                    )
                async with response:
                    if response.status in {401, 403}:
                        raise self._response_error(response.status, {})
                    if response.status != 200:
                        idle_seconds = (STREAM_HEARTBEAT_IDLE_SECONDS if self._stream_heartbeat
                                        else self.stream_timeout_seconds)
                        async with asyncio.timeout(idle_seconds):
                            value = await response.json()
                        if not isinstance(value, Mapping):
                            raise AdapterError("HOST_INCOMPATIBLE", 502)
                        raise self._response_error(response.status, value)
                    terminal = False
                    async for line in self._stream_lines(response):
                        if not line.strip():
                            continue
                        value = json.loads(line)
                        if not isinstance(value, dict) or value.get("type") not in STREAM_EVENT_TYPES:
                            raise AdapterError("HOST_INCOMPATIBLE", 502)
                        code = value.get("code") if value["type"] == "error" else None
                        reason = ("account_not_connected" if code == "AGENT_ACCOUNT_NOT_CONNECTED"
                                  else "authentication_failed" if code in BRIDGE_AUTH_CODES else None)
                        if reason is not None:
                            failure = self._bridge_failure(reason, status=502)
                            value = {"type": "error", "code": failure.code, "details": failure.details}
                        yield value
                        if value["type"] in {"done", "error"}:
                            terminal = True
                            break
                    if not terminal:
                        raise AdapterError("STREAM_INTERRUPTED", 502)
        except AdapterError as exc:
            if exc.code == "AGENT_ACCOUNT_NOT_CONNECTED":
                raise self._bridge_failure("account_not_connected", status=502) from None
            raise
        except (aiohttp.ClientConnectorCertificateError, aiohttp.ClientSSLError) as exc:
            raise self._bridge_failure("tls_rejected", status=502) from exc
        except TimeoutError as exc:
            raise self._bridge_failure("timeout") from exc
        except (aiohttp.ClientError, ValueError) as exc:
            raise self._bridge_failure("transport_failed") from exc

    async def manage(
        self,
        principal: Principal,
        session_id: str,
        action: str,
        value: Any = None,
    ) -> dict[str, Any]:
        if action not in {"rename", "archive", "delete", "cancel"}:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)
        headers = self._headers(principal)
        return await self._request(
            "POST",
            f"/sessions/{quote(session_id, safe='')}/{action}",
            {"value": value},
            headers=headers,
        )
