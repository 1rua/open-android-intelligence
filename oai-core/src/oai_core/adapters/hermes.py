"""Account-scoped access to the existing Hermes Gateway session bindings.

There is deliberately no scan of Hermes' global session directory. The
Gateway's account binding and native HistoryPort remain the authority.
"""
from __future__ import annotations

from typing import Any
from urllib.parse import quote

from ..sessions import AdapterError
from .dsh import DshSessionAdapter


class HermesRemoteSessionAdapter(DshSessionAdapter):
    """Only configured Core accounts can select their mapped Hermes account."""
    def __init__(self, base_url: str, token: str, accounts: dict[str, str], *, agent_id="hermes", name="Hermes"):
        if (not isinstance(accounts, dict) or not accounts
                or any(not isinstance(key, str) or not key or not isinstance(value, str) or not value for key, value in accounts.items())
                or len(set(accounts.values())) != len(accounts)):
            raise ValueError("Hermes requires an explicit one-to-one Core account mapping")
        super().__init__(base_url, token, agent_id=agent_id, name=name)
        self.accounts = dict(accounts)

    def descriptor(self) -> dict[str, Any]:
        return {**super().descriptor(), "kind": "hermes", "sessionScope": "account"}

    def _headers(self, principal: Any) -> dict[str, str]:
        account_id = self.accounts.get(getattr(principal, "account_id", None))
        if account_id is None:
            raise AdapterError("AGENT_ACCOUNT_NOT_CONNECTED", 403)
        result = {"X-OAI-Account": account_id}
        device_id = getattr(principal, "device_id", None)
        if device_id:
            result["X-OAI-Device"] = device_id
        for name, attribute in (("X-OAI-Pairing-Generation", "pairing_generation"), ("X-OAI-Grant-Revision", "grant_revision")):
            value = getattr(principal, attribute, None)
            if value is not None:
                result[name] = str(value)
        return result

    async def list_sessions(self, principal):
        return (await self._request("GET", "/sessions", headers=self._headers(principal)))["sessions"]

    async def history(self, principal, session_id):
        return (await self._request("GET", f"/sessions/{quote(session_id, safe='')}/history", headers=self._headers(principal)))["messages"]

    async def create(self, principal, title):
        return (await self._request("POST", "/sessions", {"title": title}, headers=self._headers(principal)))["session"]

    async def send(self, principal, session_id, text):
        async for event in self._send(session_id, text, headers=self._headers(principal)):
            yield event

    async def manage(self, principal, session_id, action, value=None):
        if action not in {"rename", "archive", "delete", "cancel"}:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)
        return await self._request("POST", f"/sessions/{quote(session_id, safe='')}/{action}", {"value": value}, headers=self._headers(principal))
