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
        self.forward_client_message_ids = forward_client_message_ids
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

    def _headers(self, principal: Principal | None = None) -> dict[str, str]:
        extra: dict[str, str] = {}
        if self.headers_builder is not None and principal is not None:
            extra = self.headers_builder(principal)
        return extra

    def _session(self, headers: dict[str, str] | None = None) -> aiohttp.ClientSession:
        combined_headers = {"Authorization": f"Bearer {self.token}", **current_trace_headers(), **(headers or {})}
        return aiohttp.ClientSession(
            headers=combined_headers,
            timeout=aiohttp.ClientTimeout(total=self.timeout_seconds),
            json_serialize=session_request_json,
        )

    async def _request(
        self,
        method: str,
        path: str,
        body: Any = None,
        *,
        headers: dict[str, str] | None = None,
    ) -> Any:
        try:
            async with self._session(headers) as session:
                async with session.request(
                    method,
                    self.base_url + self.prefix + path,
                    json=body,
                    allow_redirects=False,
                ) as response:
                    value = await response.json()
                    if not isinstance(value, dict):
                        raise AdapterError("HOST_INCOMPATIBLE", 502)
                    if response.status != 200:
                        details = value.get("details")
                        public = (_public_approval(details) if isinstance(details, Mapping)
                                  and "/approvals/" in path and path.endswith("/decisions") else {})
                        raise AdapterError(str(value.get("code", "AGENT_UNAVAILABLE")), response.status, details=public)
                    return value
        except (aiohttp.ClientError, TimeoutError, ValueError) as exc:
            raise AdapterError("AGENT_UNAVAILABLE", 503) from exc

    async def status(self) -> bool:
        try:
            result = await self._request("GET", "/status")
            self.capabilities = {
                key: result.get("capabilities", {}).get(key) is True for key in CAPABILITIES
            }
            return result.get("online") is True
        except AdapterError:
            self.capabilities = dict.fromkeys(CAPABILITIES, False)
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
        return result.get("sessions", [])

    async def history(self, principal: Principal, session_id: str) -> list[dict[str, Any]]:
        headers = self._headers(principal)
        result = await self._request("GET", f"/sessions/{quote(session_id, safe='')}/history", headers=headers)
        messages = result.get("messages", [])
        if self.attachment_core is not None:
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
        return await self._request("GET", f"/sessions/{quote(session_id, safe='')}/events?{urlencode(query)}",
            headers={**self._headers(principal), "X-OAI-Observer": consumer_id})

    async def ack_events(self, principal: Principal, session_id: str, *, consumer_id: str, cursor: str) -> dict[str, Any]:
        return await self._request("POST", f"/sessions/{quote(session_id, safe='')}/events/ack", {"cursor": cursor},
            headers={**self._headers(principal), "X-OAI-Observer": consumer_id})

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
            async with self._session(headers) as session:
                async with session.put(self.base_url + self.prefix + f"/attachments/{quote(attachment_id, safe='')}/content",
                        data=chunks(), timeout=aiohttp.ClientTimeout(total=self.stream_timeout_seconds), allow_redirects=False) as response:
                    value = await response.json()
                    if response.status != 200:
                        raise AdapterError(str(value.get("code", "ATTACHMENT_READ_FAILED")), response.status)
                    if value.get("status") != "uploaded":
                        raise AdapterError("ATTACHMENT_READ_FAILED", 502)
        except (aiohttp.ClientError, TimeoutError, ValueError) as exc:
            raise AdapterError("ATTACHMENT_UPLOAD_FAILED", 502) from exc
        self._remember_attachment(attachment, attachment_id)
        return attachment_id

    async def _send(
        self,
        session_id: str,
        text: str,
        *,
        headers: dict[str, str] | None = None,
        body: dict[str, Any] | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        try:
            async with self._session(headers) as session:
                async with session.post(
                    self.base_url + self.prefix + f"/sessions/{quote(session_id, safe='')}/messages",
                    json=body if body is not None else {"text": text},
                    timeout=aiohttp.ClientTimeout(total=self.stream_timeout_seconds),
                    allow_redirects=False,
                    read_bufsize=MAX_STREAM_EVENT_BYTES // 2,
                ) as response:
                    if response.status != 200:
                        value = await response.json()
                        raise AdapterError(str(value.get("code", "AGENT_UNAVAILABLE")), response.status)
                    terminal = False
                    while True:
                        try:
                            line = await response.content.readline()
                        except (ValueError, aiohttp.http_exceptions.LineTooLong):
                            # Parser exceptions may include a transcript prefix.
                            # Expose only the framing error, never that content.
                            raise AdapterError("AGENT_STREAM_TOO_LARGE", 502) from None
                        if not line:
                            break
                        if len(line) > MAX_STREAM_EVENT_BYTES:
                            raise AdapterError("AGENT_STREAM_TOO_LARGE", 502)
                        if not line.strip():
                            continue
                        value = json.loads(line)
                        if not isinstance(value, dict) or value.get("type") not in STREAM_EVENT_TYPES:
                            raise AdapterError("HOST_INCOMPATIBLE", 502)
                        yield value
                        if value["type"] in {"done", "error"}:
                            terminal = True
                            break
                    if not terminal:
                        raise AdapterError("STREAM_INTERRUPTED", 502)
        except (aiohttp.ClientError, TimeoutError, ValueError) as exc:
            raise AdapterError("AGENT_UNAVAILABLE", 503) from exc

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
