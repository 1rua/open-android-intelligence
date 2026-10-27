"""Account-scoped access to the existing Hermes Gateway session bindings.

There is deliberately no scan of Hermes' global session directory. The
Gateway's account binding and native HistoryPort remain the authority.
"""
from __future__ import annotations

import ipaddress
from typing import Any
from urllib.parse import quote

from yarl import URL

from ..sessions import AdapterError
from .remote import RemoteSessionAdapter, REMOTE_PREFIX


class HermesRemoteSessionAdapter(RemoteSessionAdapter):
    """Only configured Core accounts can select their mapped Hermes account."""

    def __init__(self, base_url: str, token: str, accounts: dict[str, str], *, agent_id="hermes", name="Hermes", attachment_core: Any = None):
        if (
            not isinstance(accounts, dict)
            or not accounts
            or any(
                not isinstance(key, str) or not key or not isinstance(value, str) or not value
                for key, value in accounts.items()
            )
            or len(set(accounts.values())) != len(accounts)
        ):
            raise ValueError("Hermes requires an explicit one-to-one Core account mapping")

        self.accounts = dict(accounts)
        super().__init__(
            base_url,
            token,
            agent_id=agent_id,
            name=name,
            kind="hermes",
            session_scope="account",
            prefix=REMOTE_PREFIX,
            headers_builder=self._hermes_headers,
            attachment_core=attachment_core,
            forward_client_message_ids=True,
        )

    def _hermes_headers(self, principal: Any) -> dict[str, str]:
        account_id = self.accounts.get(getattr(principal, "account_id", None))
        if account_id is None:
            raise AdapterError("AGENT_ACCOUNT_NOT_CONNECTED", 403)
        result = {"X-OAI-Account": account_id}
        device_id = getattr(principal, "device_id", None)
        if device_id:
            result["X-OAI-Device"] = device_id
        for name, attribute in (
            ("X-OAI-Pairing-Generation", "pairing_generation"),
            ("X-OAI-Grant-Revision", "grant_revision"),
        ):
            value = getattr(principal, attribute, None)
            if value is not None:
                result[name] = str(value)
        return result

    @property
    def account_scope(self) -> tuple[str, str, int]:
        """One configured native host, shared by all its Core Agent aliases."""
        # Use the same IDNA and URL rules as aiohttp's actual request target.
        origin = URL(self.base_url)
        host = origin.raw_host.rstrip(".")
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            if host == "localhost":
                host = "loopback"
        else:
            host = "loopback" if address.is_loopback else address.compressed
        return origin.scheme, host, origin.port or (443 if origin.scheme == "https" else 80)
