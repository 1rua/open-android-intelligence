"""Sample custom agent adapter for OAI Core.

Can be run locally or used as a template for 3rd-party agent integration.
"""
from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any, AsyncIterator

from oai_core.auth import Principal
from oai_core.sessions import SessionAdapter, AdapterError


class SampleCustomAdapter:
    """A minimal runnable custom agent adapter."""

    def __init__(
        self,
        agent_id: str = "sample-custom",
        name: str = "Sample Custom Agent",
        session_scope: str = "deployment",
    ):
        self.agent_id = agent_id
        self.name = name
        self.session_scope = session_scope
        self.capabilities = {
            "list": True,
            "history": True,
            "create": True,
            "send": True,
            "rename": True,
            "archive": False,
            "delete": False,
            "cancel": True,
            "stream": True,
        }
        self.sessions: list[dict[str, Any]] = [
            {"id": "sample-session-1", "title": "默认示例会话", "archived": False}
        ]

    def descriptor(self) -> dict[str, Any]:
        return {
            "id": self.agent_id,
            "name": self.name,
            "kind": "sample-custom",
            "sessionScope": self.session_scope,
            "deviceTools": False,
            "capabilities": dict(self.capabilities),
        }

    async def status(self) -> bool:
        return True

    async def list_sessions(self, principal: Principal) -> list[dict[str, Any]]:
        return list(self.sessions)

    async def history(self, principal: Principal, session_id: str) -> list[dict[str, Any]]:
        return [
            {"id": "msg-1", "role": "user", "text": "你好！", "createdAt": "2026-10-26T12:00:00Z"},
            {"id": "msg-2", "role": "assistant", "text": "您好，我是自定义 Agent 插件示例。", "createdAt": "2026-10-26T12:00:02Z"},
        ]

    async def create(self, principal: Principal, title: str | None) -> dict[str, Any]:
        new_session = {
            "id": f"sess-{len(self.sessions) + 1}",
            "title": title or f"会话 #{len(self.sessions) + 1}",
            "archived": False,
        }
        self.sessions.append(new_session)
        return new_session

    async def send(self, principal: Principal, session_id: str, text: str) -> AsyncIterator[dict[str, Any]]:
        yield {"type": "delta", "text": f"收到您的输入：'{text}'。正在思考..."}
        await asyncio.sleep(0.01)
        yield {"type": "delta", "text": "\n这里是示例插件的回复内容。"}
        yield {"type": "done"}

    async def manage(self, principal: Principal, session_id: str, action: str, value: Any = None) -> dict[str, Any]:
        if action == "rename":
            for s in self.sessions:
                if s["id"] == session_id:
                    s["title"] = str(value)
            return {"id": session_id, "action": action, "value": value}
        if action == "cancel":
            return {"id": session_id, "action": action}
        raise AdapterError("UNSUPPORTED_CAPABILITY", 405)


def create_sample_adapter(entry: dict[str, Any], storage_root: Path, core: Any) -> SessionAdapter:
    """Plugin factory function matching OAI Core entry points protocol."""
    return SampleCustomAdapter(
        agent_id=entry.get("id", "sample-custom"),
        name=entry.get("name", "Sample Custom Agent"),
        session_scope=entry.get("sessionScope", "deployment"),
    )
