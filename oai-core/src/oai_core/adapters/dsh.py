"""Session-only bridge to one explicitly configured DSH deployment.

The host owns enumeration, persistence, replay and writes. No OAI account id is
sent to DSH: its configured native corpus is shared by authenticated Core users.
"""
from __future__ import annotations

from typing import Any

from .remote import RemoteSessionAdapter, REMOTE_PREFIX

CAPABILITIES = ("list", "history", "create", "send", "rename", "archive", "delete", "cancel", "stream")
PREFIX = REMOTE_PREFIX


class DshSessionAdapter(RemoteSessionAdapter):
    def __init__(self, base_url: str, token: str, *, agent_id: str = "dsh", name: str = "DSH"):
        super().__init__(
            base_url,
            token,
            agent_id=agent_id,
            name=name,
            kind="dsh",
            session_scope="deployment",
            prefix=PREFIX,
        )
