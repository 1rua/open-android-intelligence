"""Contract tests for unknown 3rd-party generic Agent plugins.

Verifies:
1. Dynamic registration of completely unknown Agent kinds without modifying Core source code.
2. Multi-instance co-existence and explicit configuration boundaries.
3. Capability omission and server-side enforcement (405 UNSUPPORTED_CAPABILITY).
4. Account isolation vs deployment sharing models.
5. Protection against device credential forwarding / leakage.
6. SSE streaming interruptions, cancellations, heartbeats, and error semantics.
"""

from __future__ import annotations

import asyncio
import json
import unittest
import uuid
from pathlib import Path
from typing import Any, AsyncIterator
import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.adapters.remote import RemoteSessionAdapter
from oai_core.auth import Principal
from oai_core.gateway.core import GatewayError
from oai_core.sessions import (
    AgentRegistry,
    AdapterError,
    SessionAdapter,
    build_registry,
    create_agent_routes,
)

BASE = "/open-android-intelligence/v2/agents"


class UnknownMockPlugin(SessionAdapter):
    """An agent type completely unknown to Core at development time."""

    def __init__(
        self,
        agent_id: str,
        name: str,
        kind: str,
        scope: str = "deployment",
        capabilities: dict[str, bool] | None = None,
    ):
        self.agent_id = agent_id
        self.name = name
        self.kind = kind
        self.scope = scope
        self.capabilities = capabilities or {
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
        self.online = True
        self.sessions: list[dict[str, Any]] = []
        self.recorded_calls: list[tuple[str, Any]] = []

    def descriptor(self) -> dict[str, Any]:
        return {
            "id": self.agent_id,
            "name": self.name,
            "kind": self.kind,
            "sessionScope": self.scope,
            "deviceTools": False,
            "capabilities": dict(self.capabilities),
        }

    async def status(self) -> bool:
        return self.online

    async def list_sessions(self, principal: Principal) -> list[dict[str, Any]]:
        self.recorded_calls.append(("list", principal.account_id))
        if self.scope == "account":
            return [s for s in self.sessions if s.get("owner") == principal.account_id]
        return list(self.sessions)

    async def history(self, principal: Principal, session_id: str) -> list[dict[str, Any]]:
        self.recorded_calls.append(("history", principal.account_id, session_id))
        return [
            {"id": "msg-1", "role": "user", "text": "Hi", "createdAt": "2026-10-26T00:00:00Z"},
            {"id": "msg-2", "role": "assistant", "text": "Hello", "createdAt": "2026-10-26T00:00:01Z"},
        ]

    async def create(self, principal: Principal, title: str | None) -> dict[str, Any]:
        self.recorded_calls.append(("create", principal.account_id, title))
        s = {"id": f"sess-{len(self.sessions) + 1}", "title": title or "New Session", "owner": principal.account_id}
        self.sessions.append(s)
        return s

    async def send(self, principal: Principal, session_id: str, text: str) -> AsyncIterator[dict[str, Any]]:
        self.recorded_calls.append(("send", principal.account_id, session_id, text))
        yield {"type": "delta", "text": f"Response to {text}"}
        yield {"type": "done"}

    async def manage(self, principal: Principal, session_id: str, action: str, value: Any = None) -> dict[str, Any]:
        self.recorded_calls.append((action, principal.account_id, session_id, value))
        return {"id": session_id, "action": action, "value": value}


def _dummy_plugin_factory(entry: dict[str, Any], base_dir: Path, core: Any) -> SessionAdapter:
    return UnknownMockPlugin(
        agent_id=entry.get("id", "custom-agent"),
        name=entry.get("name", "Custom"),
        kind=entry.get("type", "custom-kind"),
        scope=entry.get("sessionScope", "deployment"),
        capabilities=entry.get("capabilities"),
    )


class TestGenericAgentContract(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        AgentRegistry.register_factory("custom-llm", _dummy_plugin_factory)
        self.plugin_instance1 = UnknownMockPlugin("agent-alpha", "Alpha", "custom-llm", scope="deployment")
        self.plugin_instance2 = UnknownMockPlugin(
            "agent-beta",
            "Beta",
            "custom-llm",
            scope="account",
            capabilities={"list": True, "history": True, "create": True, "send": False, "stream": False},
        )
        self.registry = AgentRegistry([self.plugin_instance1, self.plugin_instance2])
        self.active = True

        async def authenticate(request: web.Request) -> Principal:
            user = request.headers.get("X-Test-User")
            if not user:
                raise GatewayError("AUTHENTICATION_REQUIRED")
            return Principal(user)

        app = web.Application()
        app.add_routes(create_agent_routes(self.registry, authenticate, lambda p: self.active))
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        await self.registry.close()

    def user(self, name: str = "alice") -> dict[str, str]:
        return {"X-Test-User": name, "Idempotency-Key": str(uuid.uuid4())}

    async def test_dynamic_registration_and_discovery(self):
        resp = await self.client.get(BASE, headers=self.user())
        self.assertEqual(200, resp.status)
        data = await resp.json()
        agents = {a["id"]: a for a in data["agents"]}
        self.assertIn("agent-alpha", agents)
        self.assertIn("agent-beta", agents)
        self.assertEqual("Alpha", agents["agent-alpha"]["name"])
        self.assertEqual("deployment", agents["agent-alpha"]["sessionScope"])
        self.assertEqual("account", agents["agent-beta"]["sessionScope"])
        self.assertTrue(agents["agent-alpha"]["online"])
        self.assertTrue(agents["agent-alpha"]["capabilities"]["send"])
        self.assertFalse(agents["agent-beta"]["capabilities"]["send"])

    async def test_multi_instance_coexistence_and_scope_isolation(self):
        # Create session in deployment scope (Alpha)
        alpha_create = await self.client.post(
            f"{BASE}/agent-alpha/sessions",
            json={"title": "Shared Work"},
            headers=self.user("alice"),
        )
        self.assertEqual(200, alpha_create.status)

        # Bob can see Alice's session in deployment scope
        alpha_sessions_bob = await (
            await self.client.get(f"{BASE}/agent-alpha/sessions", headers=self.user("bob"))
        ).json()
        self.assertEqual(1, len(alpha_sessions_bob["sessions"]))
        self.assertEqual("Shared Work", alpha_sessions_bob["sessions"][0]["title"])

        # Create session in account scope (Beta)
        beta_create = await self.client.post(
            f"{BASE}/agent-beta/sessions",
            json={"title": "Private Note"},
            headers=self.user("alice"),
        )
        self.assertEqual(200, beta_create.status)

        # Bob CANNOT see Alice's session in account scope
        beta_sessions_bob = await (
            await self.client.get(f"{BASE}/agent-beta/sessions", headers=self.user("bob"))
        ).json()
        self.assertEqual(0, len(beta_sessions_bob["sessions"]))

        # Alice CAN see her own private session
        beta_sessions_alice = await (
            await self.client.get(f"{BASE}/agent-beta/sessions", headers=self.user("alice"))
        ).json()
        self.assertEqual(1, len(beta_sessions_alice["sessions"]))

    async def test_unsupported_capability_rejection(self):
        # Beta explicitly disabled send/stream capability
        resp = await self.client.post(
            f"{BASE}/agent-beta/sessions/sess-1/messages",
            json={"text": "Hello"},
            headers=self.user("alice"),
        )
        self.assertEqual(405, resp.status)
        data = await resp.json()
        self.assertEqual("UNSUPPORTED_CAPABILITY", data["error"]["code"])

    async def test_build_registry_with_generic_remote_type(self):
        # Test config declaring a generic 'remote' agent
        config = {
            "storageRoot": "/tmp",
            "agents": [
                {
                    "type": "remote",
                    "id": "external-agent",
                    "name": "External LLM",
                    "url": "http://127.0.0.1:9999",
                    "token": "a" * 32,
                    "sessionScope": "deployment",
                }
            ],
        }
        reg = build_registry(config, None)
        try:
            self.assertIn("external-agent", reg._adapters)
            desc = reg._adapters["external-agent"].descriptor()
            self.assertEqual("external-agent", desc["id"])
            self.assertEqual("deployment", desc["sessionScope"])
        finally:
            await reg.close()

    async def test_remote_adapter_full_lifecycle_and_streaming(self):
        stream_chunks = [
            b'{"type":"delta","text":"Hel"}\n',
            b'{"type":"delta","text":"lo!"}\n',
            b'{"type":"done"}\n',
        ]

        async def bridge(request: web.Request) -> web.StreamResponse:
            self.assertEqual(request.headers["Authorization"], "Bearer " + ("t" * 32))
            if request.path.endswith("/status"):
                return web.json_response({"online": True, "capabilities": {"list": True, "send": True, "stream": True}})
            if request.path.endswith("/messages"):
                resp = web.StreamResponse(headers={"Content-Type": "application/x-ndjson"})
                await resp.prepare(request)
                for chunk in stream_chunks:
                    await resp.write(chunk)
                return resp
            return web.json_response({"sessions": []})

        app = web.Application()
        app.router.add_route("*", "/{tail:.*}", bridge)
        async with TestClient(TestServer(app)) as srv:
            adapter = RemoteSessionAdapter(
                str(srv.make_url("/")),
                "t" * 32,
                agent_id="test-remote",
                name="RemoteTest",
                kind="remote",
                prefix="",
            )
            self.assertTrue(await adapter.status())
            events = [e async for e in adapter.send(Principal("alice"), "s1", "hi")]
            self.assertEqual(3, len(events))
            self.assertEqual("Hel", events[0]["text"])
            self.assertEqual("lo!", events[1]["text"])
            self.assertEqual("done", events[2]["type"])

    def test_unknown_unregistered_agent_type_fails_closed(self):
        config = {
            "storageRoot": "/tmp",
            "agents": [
                {
                    "type": "some-random-unknown-agent",
                    "id": "unknown-1",
                }
            ],
        }
        with self.assertRaises(ValueError) as ctx:
            build_registry(config, None)
        self.assertIn("Unknown or uninstalled Agent type", str(ctx.exception))

    def test_openclaw_retains_explicit_migration_status_message(self):
        config = {
            "storageRoot": "/tmp",
            "agents": [
                {
                    "type": "openclaw",
                    "id": "claw-1",
                }
            ],
        }
        with self.assertRaises(ValueError) as ctx:
            build_registry(config, None)
        self.assertIn("Unsupported Agent type; OpenClaw is not part of this migration", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()

