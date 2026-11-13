"""可选宿主发现失败不得破坏已有 Agent 的事实与基础协商。"""

import json
import sys
from types import SimpleNamespace

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

import oai_core
from oai_core import configuration
from oai_core.auth import Principal
from oai_core.server import CoreTransport, REGISTRY_KEY, errors
from oai_core.sessions import AgentRegistry, create_agent_routes


class Host:
    def __init__(self, online):
        self.online = online

    def descriptor(self):
        return {"id": "hermes", "name": "Hermes", "sessionScope": "account", "capabilities": {"list": True}}

    async def status(self):
        return self.online


@pytest.mark.asyncio
@pytest.mark.parametrize("registered_online", [True, False])
async def test_missing_optional_discovery_module_keeps_registered_agents_and_negotiation(tmp_path, monkeypatch, registered_online):
    config = tmp_path / "core.json"
    config.write_text(json.dumps({"storageRoot": "accounts", "masterKeyFile": "master", "agents": []}))
    registration = SimpleNamespace(entries=lambda: [], mappings=lambda *_: {})
    core = SimpleNamespace(agent_registration=registration, identity_store=None)
    registry = AgentRegistry([Host(registered_online)], core=core, config_path=config)
    monkeypatch.setenv("OAI_ENABLE_HOST_AUTODISCOVERY_IN_TESTS", "1")
    monkeypatch.setattr(configuration, "detect_host_agents", lambda: [])

    async def authenticate(_request):
        return Principal("synthetic")

    route = SimpleNamespace(path="/open-android-intelligence/v2/negotiate", match="exact",
                            _handle_raw=lambda raw: {"statusCode": 200, "headers": {}, "body": {"synthetic": True}})
    transport = CoreTransport(SimpleNamespace(core=core, exposure=SimpleNamespace(routes=[route])))
    app = web.Application(middlewares=[errors])
    app[REGISTRY_KEY] = registry
    app.add_routes(create_agent_routes(registry, authenticate, lambda principal: True))
    app.router.add_post(route.path, transport.dispatch)

    async def assert_available(client):
        agents = await client.get("/open-android-intelligence/v2/agents")
        negotiate = await client.post(route.path, json={})
        assert (agents.status, negotiate.status) == (200, 200)
        assert (await agents.json())["agents"][0]["online"] is registered_online
        assert negotiate.headers["X-OAI-Agent-Sessions"] == "1"

    try:
        async with TestClient(TestServer(app)) as client:
            await assert_available(client)
            with monkeypatch.context() as missing:
                # 保留正确 sys.path，只模拟已加载父包仍指向已移动的候选目录。
                package_path = [str(tmp_path / "moved-candidate" / "oai_core")]
                missing.setattr(oai_core, "__path__", package_path)
                missing.setattr(oai_core.__spec__, "submodule_search_locations", package_path)
                missing.delitem(sys.modules, "oai_core.configuration")
                missing.delattr(oai_core, "configuration")
                await assert_available(client)
            await assert_available(client)
    finally:
        await registry.close()
