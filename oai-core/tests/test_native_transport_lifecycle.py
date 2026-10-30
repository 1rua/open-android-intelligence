"""Real HTTP observers must not own accepted Agent work or session creation."""
import asyncio

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.auth import Principal
from oai_core.sessions import AgentRegistry, create_agent_routes

BASE = "/open-android-intelligence/v2/agents/probe/sessions"


class Host:
    def __init__(self):
        self.entered = asyncio.Event()
        self.release = asyncio.Event()
        self.completed = asyncio.Event()
        self.closed = asyncio.Event()
        self.calls = 0

    def descriptor(self):
        return {"id": "probe", "sessionScope": "account", "capabilities": {
            "create": True, "send": True, "stream": True}}

    async def create(self, principal, title):
        self.calls += 1
        self.entered.set()
        try:
            await self.release.wait()
            self.completed.set()
            return {"id": "new-native", "title": title}
        finally:
            self.closed.set()

    async def send(self, principal, session_id, text, *, client_message_id=None):
        self.calls += 1
        try:
            yield {"type": "generation", "generationId": "native-generation", "clientMessageId": client_message_id}
            self.entered.set()
            await self.release.wait()
            self.completed.set()
            yield {"type": "done", "generationId": "native-generation"}
        finally:
            self.closed.set()


async def make_client(host, handlers):
    registry = AgentRegistry([host])
    async def authenticate(request):
        handlers.append(asyncio.current_task())
        return Principal("alice")
    app = web.Application()
    app.add_routes(create_agent_routes(registry, authenticate, lambda p: True))
    client = TestClient(TestServer(app))
    await client.start_server()
    return client, registry


@pytest.mark.asyncio
async def test_cancelled_http_observer_does_not_cancel_accepted_generation():
    host, handlers = Host(), []
    client, registry = await make_client(host, handlers)
    try:
        stream = await client.post(BASE + "/chat/messages", json={"text": "synthetic", "clientMessageId": "cm_probe"},
                                   headers={"Idempotency-Key": "cm_probe"})
        assert stream.status == 200
        await asyncio.wait_for(host.entered.wait(), 1)
        handlers[0].cancel()
        await asyncio.gather(handlers[0], return_exceptions=True)
        host.release.set()
        await asyncio.wait_for(host.closed.wait(), 1)
        assert host.completed.is_set(), "HTTP observer cancellation cancelled the native Agent generation"
        assert host.calls == 1
        assert await registry.operations.begin("alice", "cm_probe", "POST", BASE + "/chat/messages",
            {"text": "synthetic", "clientMessageId": "cm_probe"}) == {"state": "done"}
    finally:
        host.release.set()
        await client.close()
        await registry.close()


@pytest.mark.asyncio
async def test_cancelled_session_create_finishes_receipt_for_the_same_idempotency_key():
    host, handlers = Host(), []
    client, registry = await make_client(host, handlers)
    body, headers = {"title": "synthetic"}, {"Idempotency-Key": "create_probe"}
    pending = asyncio.create_task(client.post(BASE, json=body, headers=headers))
    try:
        await asyncio.wait_for(host.entered.wait(), 1)
        handlers[0].cancel()
        await asyncio.gather(handlers[0], return_exceptions=True)
        host.release.set()
        await asyncio.wait_for(host.closed.wait(), 1)
        response = await client.post(BASE, json=body, headers=headers)
        assert response.status == 200, await response.text()
        assert (await response.json())["session"]["id"] == "new-native"
        assert host.calls == 1
    finally:
        host.release.set()
        pending.cancel()
        await asyncio.gather(pending, return_exceptions=True)
        await client.close()
        await registry.close()


@pytest.mark.asyncio
async def test_fast_adapter_burst_does_not_starve_a_healthy_http_observer():
    class BurstHost(Host):
        async def send(self, principal, session_id, text, *, client_message_id=None):
            for index in range(300):
                yield {"type": "delta", "text": str(index)}
            yield {"type": "done", "generationId": "burst"}

    client, registry = await make_client(BurstHost(), [])
    try:
        stream = await client.post(BASE + "/chat/messages", json={"text": "synthetic"},
                                   headers={"Idempotency-Key": "burst"})
        body = await stream.text()
        assert body.count('"type": "delta"') == 300
        assert '"type": "done"' in body
        assert '"type": "error"' not in body
    finally:
        await client.close()
        await registry.close()


@pytest.mark.asyncio
async def test_slow_native_observer_does_not_backpressure_or_cancel_host_completion(monkeypatch):
    from oai_core import sessions
    blocked, release = asyncio.Event(), asyncio.Event()
    original = web.StreamResponse.write

    async def slow_write(response, data):
        if b'"type": "generation"' in data:
            blocked.set()
            await release.wait()
        return await original(response, data)

    class BurstHost(Host):
        async def send(self, principal, session_id, text, *, client_message_id=None):
            self.calls += 1
            yield {"type": "generation", "generationId": "slow"}
            await blocked.wait()
            for index in range(20):
                yield {"type": "delta", "text": str(index)}
            self.completed.set()
            yield {"type": "done", "generationId": "slow"}

    monkeypatch.setattr(web.StreamResponse, "write", slow_write)
    monkeypatch.setattr(sessions, "NATIVE_OBSERVER_QUEUE_SIZE", 2, raising=False)
    host = BurstHost()
    client, registry = await make_client(host, [])
    try:
        stream = await client.post(BASE + "/chat/messages", json={"text": "synthetic"},
                                   headers={"Idempotency-Key": "slow"})
        await asyncio.wait_for(blocked.wait(), 1)
        await asyncio.wait_for(host.completed.wait(), 1)
        release.set()
        body = await stream.text()
        assert '"code": "AGENT_STREAM_INTERRUPTED"' in body
        assert host.calls == 1
        assert await registry.operations.begin("alice", "slow", "POST", BASE + "/chat/messages",
                                         {"text": "synthetic"}) == {"state": "done"}
    finally:
        release.set()
        await client.close()
        await registry.close()


@pytest.mark.asyncio
async def test_registry_shutdown_releases_its_pending_operations():
    host = Host()
    client, registry = await make_client(host, [])
    try:
        stream = await client.post(BASE + "/chat/messages", json={"text": "synthetic"},
                                   headers={"Idempotency-Key": "shutdown"})
        await asyncio.wait_for(host.entered.wait(), 1)
        assert registry._operation_tasks
        await registry.close()
        assert not registry._operation_tasks
        assert host.closed.is_set()
        stream.close()
    finally:
        await client.close()
