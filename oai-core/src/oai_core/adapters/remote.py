"""Generic HTTP/SSE remote session adapter for OAI Core.

Provides standard HTTP session bridge communication, capability negotiation,
streaming responses (SSE / NDJSON), error translation, and compact UTF-8 JSON budgets.
Both deployment-shared and account-isolated remote agents can build on this.
"""
from __future__ import annotations

import json
import logging
from typing import Any, AsyncIterator, Mapping
from urllib.parse import quote, urlsplit

import aiohttp

from ..auth import Principal
from ..gateway.core import _public_approval
from ..sessions import CAPABILITIES, STREAM_EVENT_TYPES, AdapterError, session_request_json

logger = logging.getLogger(__name__)

REMOTE_PREFIX = "/oai/session-adapter/v1"


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
        combined_headers = {"Authorization": f"Bearer {self.token}", **(headers or {})}
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
        return result.get("messages", [])

    async def create(self, principal: Principal, title: str | None) -> dict[str, Any]:
        headers = self._headers(principal)
        result = await self._request("POST", "/sessions", {"title": title}, headers=headers)
        return result.get("session", {})

    async def send(self, principal: Principal, session_id: str, text: str) -> AsyncIterator[dict[str, Any]]:
        headers = self._headers(principal)
        async for event in self._send(session_id, text, headers=headers):
            yield event

    async def _send(
        self,
        session_id: str,
        text: str,
        *,
        headers: dict[str, str] | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        try:
            async with self._session(headers) as session:
                async with session.post(
                    self.base_url + self.prefix + f"/sessions/{quote(session_id, safe='')}/messages",
                    json={"text": text},
                    timeout=aiohttp.ClientTimeout(total=self.stream_timeout_seconds),
                    allow_redirects=False,
                ) as response:
                    if response.status != 200:
                        value = await response.json()
                        raise AdapterError(str(value.get("code", "AGENT_UNAVAILABLE")), response.status)
                    terminal = False
                    async for line in response.content:
                        if not line or not line.strip():
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
