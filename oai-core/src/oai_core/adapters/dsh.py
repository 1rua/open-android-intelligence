"""Session-only bridge to one explicitly configured DSH deployment.

The host owns enumeration, persistence, replay and writes. No OAI account id is
sent to DSH: its configured native corpus is shared by authenticated Core users.
"""
from __future__ import annotations

import json
from typing import Any, AsyncIterator
from urllib.parse import quote, urlsplit

import aiohttp

from ..sessions import AdapterError


CAPABILITIES = ("list", "history", "create", "send", "rename", "archive", "delete", "cancel", "stream")
PREFIX = "/oai/session-adapter/v1"


class DshSessionAdapter:
    def __init__(self, base_url: str, token: str, *, agent_id: str = "dsh", name: str = "DSH"):
        parsed = urlsplit(base_url)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                or parsed.username or parsed.password or parsed.query or parsed.fragment
                or parsed.path not in {"", "/"}):
            raise ValueError("DSH adapter URL must be an explicit HTTP origin")
        if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("DSH adapter requires HTTPS outside loopback")
        if not isinstance(token, str) or len(token) < 32:
            raise ValueError("DSH adapter token must contain at least 32 characters")
        self.base_url, self.token, self.agent_id, self.name = base_url.rstrip("/"), token, agent_id, name
        self.capabilities = dict.fromkeys(CAPABILITIES, False)

    def descriptor(self) -> dict[str, Any]:
        return {"id": self.agent_id, "name": self.name, "kind": "dsh", "sessionScope": "deployment",
                "capabilities": dict(self.capabilities)}

    def _session(self, headers: dict[str, str] | None = None) -> aiohttp.ClientSession:
        return aiohttp.ClientSession(headers={"Authorization": f"Bearer {self.token}", **(headers or {})},
                                     timeout=aiohttp.ClientTimeout(total=30))

    async def _request(self, method: str, path: str, body: Any = None, *, headers: dict[str, str] | None = None) -> Any:
        try:
            async with self._session(headers) as session:
                async with session.request(method, self.base_url + PREFIX + path, json=body,
                                           allow_redirects=False) as response:
                    value = await response.json()
                    if response.status != 200:
                        raise AdapterError(str(value.get("code", "AGENT_UNAVAILABLE")), response.status)
                    return value
        except (aiohttp.ClientError, TimeoutError, ValueError) as exc:
            raise AdapterError("AGENT_UNAVAILABLE", 503) from exc

    async def status(self) -> bool:
        try:
            result = await self._request("GET", "/status")
            self.capabilities = {key: result.get("capabilities", {}).get(key) is True for key in CAPABILITIES}
            return result.get("online") is True
        except AdapterError:
            self.capabilities = dict.fromkeys(CAPABILITIES, False)
            return False

    async def list_sessions(self, principal: Any) -> list[dict[str, Any]]:
        return (await self._request("GET", "/sessions"))["sessions"]

    async def history(self, principal: Any, session_id: str) -> list[dict[str, Any]]:
        return (await self._request("GET", f"/sessions/{quote(session_id, safe='')}/history"))["messages"]

    async def create(self, principal: Any, title: str | None) -> dict[str, Any]:
        return (await self._request("POST", "/sessions", {"title": title}))["session"]

    async def send(self, principal: Any, session_id: str, text: str) -> AsyncIterator[dict[str, Any]]:
        async for event in self._send(session_id, text):
            yield event

    async def _send(self, session_id: str, text: str, *, headers: dict[str, str] | None = None) -> AsyncIterator[dict[str, Any]]:
        # Newline-delimited host facts, including real stream deltas. HTTP
        # cancellation closes the observer; it does not pretend the turn stopped.
        try:
            async with self._session(headers) as session:
                async with session.post(self.base_url + PREFIX + f"/sessions/{quote(session_id, safe='')}/messages",
                                        json={"text": text}, timeout=aiohttp.ClientTimeout(total=1800),
                                        allow_redirects=False) as response:
                    if response.status != 200:
                        value = await response.json()
                        raise AdapterError(str(value.get("code", "AGENT_UNAVAILABLE")), response.status)
                    terminal = False
                    async for line in response.content:
                        value = json.loads(line)
                        if value.get("type") not in {"delta", "done", "error"}:
                            raise AdapterError("HOST_INCOMPATIBLE", 502)
                        yield value
                        if value["type"] in {"done", "error"}:
                            terminal = True
                            break
                    if not terminal:
                        raise AdapterError("STREAM_INTERRUPTED", 502)
        except (aiohttp.ClientError, TimeoutError, ValueError) as exc:
            raise AdapterError("AGENT_UNAVAILABLE", 503) from exc

    async def manage(self, principal: Any, session_id: str, action: str, value: Any = None) -> dict[str, Any]:
        if action not in {"rename", "archive", "delete", "cancel"}:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)
        return await self._request("POST", f"/sessions/{quote(session_id, safe='')}/{action}", {"value": value})
