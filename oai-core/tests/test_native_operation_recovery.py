"""Operation receipts are scoped facts; HTTP cancellation is not execution cancellation."""
import asyncio
from contextlib import suppress
import sqlite3
import threading

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
import pytest

from oai_core.auth import Principal
from oai_core.operations import AsyncNativeOperations, NativeOperations
from oai_core.sessions import AgentRegistry, create_agent_routes
from test_native_transport_lifecycle import Host
from test_core_transport import gateway

BASE = "/open-android-intelligence/v2/agents/probe"


@pytest.mark.asyncio
async def test_operation_receipt_is_pending_only_for_live_owner_and_resolved_is_account_agent_scoped():
    host, handlers = Host(), []
    registry = AgentRegistry([host])
    async def authenticate(request):
        handlers.append(asyncio.current_task())
        return Principal(request.headers.get("X-Test-Account", "alice"))
    app = web.Application()
    app.add_routes(create_agent_routes(registry, authenticate, lambda _: True))
    async with TestClient(TestServer(app)) as client:
        pending = asyncio.create_task(client.post(BASE + "/sessions", json={"title": "synthetic"},
            headers={"Idempotency-Key": "stable-create"}))
        try:
            await asyncio.wait_for(host.entered.wait(), 1)
            response = await client.get(BASE + "/operations/stable-create")
            assert await response.json() == {"state": "pending"}
            assert response.headers["Cache-Control"] == "no-store"
            handlers[0].cancel()
            await asyncio.gather(handlers[0], return_exceptions=True)
            response = await client.get(BASE + "/operations/stable-create")
            assert await response.json() == {"state": "pending"}
            host.release.set()
            await asyncio.gather(*tuple(registry._operation_tasks))
            response = await client.get(BASE + "/operations/stable-create")
            assert await response.json() == {"state": "resolved", "receipt": {"session": {"id": "new-native", "title": "synthetic"}}}
            assert (await client.get(BASE + "/operations/stable-create", headers={"X-Test-Account": "bob"})).status == 404
            # Same request ID in another Agent scope cannot reveal this receipt.
            assert await registry.operations.lookup("alice", "stable-create", "another-agent") is None
            assert host.calls == 1
        finally:
            host.release.set()
            pending.cancel()
            await asyncio.gather(pending, return_exceptions=True)
            await registry.close()


@pytest.mark.asyncio
async def test_abandoned_or_restarted_operation_is_unknown_not_pending(tmp_path):
    path = tmp_path / "ledger.sqlite"
    ledger = AsyncNativeOperations(path)
    await ledger.begin("alice", "original", "POST", BASE + "/sessions", {}, agent_id="probe")
    await ledger.close()
    ledger = AsyncNativeOperations(path)
    try:
        assert await ledger.lookup("alice", "original", "probe") == {"state": "unknown"}
        assert await ledger.lookup("bob", "original", "probe") is None
        assert await ledger.lookup("alice", "original", "other") is None
    finally:
        await ledger.close()


@pytest.mark.asyncio
async def test_real_sqlite_write_lock_keeps_unrelated_event_loop_work_runnable(tmp_path, monkeypatch):
    path = tmp_path / "ledger.sqlite"
    ledger = AsyncNativeOperations(path, capacity=1)
    await ledger.count()  # Open and initialize on the owning worker.
    lock = sqlite3.connect(path, isolation_level=None)
    lock.execute("BEGIN IMMEDIATE")
    entered = asyncio.Event()
    loop = asyncio.get_running_loop()
    original = NativeOperations.begin
    worker_threads = set()
    def begin(self, *args, **kwargs):
        worker_threads.add(threading.get_ident())
        loop.call_soon_threadsafe(entered.set)
        return original(self, *args, **kwargs)
    monkeypatch.setattr(NativeOperations, "begin", begin)
    pending = asyncio.create_task(ledger.begin("alice", "locked", "POST", "/synthetic", {}, agent_id="probe"))
    try:
        await asyncio.wait_for(entered.wait(), 1)
        progressed = loop.create_future()
        loop.call_soon(progressed.set_result, True)
        assert await asyncio.wait_for(progressed, 1)
        assert not pending.done(), "SQLite lock was not active at the tested boundary"
        assert threading.get_ident() not in worker_threads
        lock.execute("ROLLBACK")
        assert await asyncio.wait_for(pending, 1) is None
    finally:
        with suppress(sqlite3.Error):
            lock.execute("ROLLBACK")
        lock.close()
        await asyncio.gather(pending, return_exceptions=True)
        await ledger.close()


@pytest.mark.asyncio
async def test_cancelled_ledger_waiter_does_not_release_capacity_before_real_worker_finishes(monkeypatch):
    ledger = AsyncNativeOperations(capacity=1)
    entered, release = threading.Event(), threading.Event()
    original = NativeOperations.begin
    def begin(self, *args, **kwargs):
        entered.set()
        assert release.wait(3), "test never released ledger worker"
        return original(self, *args, **kwargs)
    monkeypatch.setattr(NativeOperations, "begin", begin)
    pending = asyncio.create_task(ledger.begin("alice", "stable", "POST", "/synthetic", {}))
    try:
        assert await asyncio.to_thread(entered.wait, 1)
        pending.cancel()
        await asyncio.gather(pending, return_exceptions=True)
        assert ledger._slots.locked()
        release.set()
        assert await asyncio.wait_for(ledger.count(), 1) == 1
    finally:
        release.set()
        await ledger.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("operation", ["create", "send"])
async def test_revocation_during_ledger_wait_prevents_new_host_side_effect(operation, monkeypatch):
    host = Host()
    registry = AgentRegistry([host])
    active = True
    entered, release = asyncio.Event(), asyncio.Event()
    begin = registry.operations.begin
    async def blocked(*args, **kwargs):
        entered.set()
        await release.wait()
        return await begin(*args, **kwargs)
    monkeypatch.setattr(registry.operations, "begin", blocked)
    async def authenticate(_request):
        return Principal("alice")
    app = web.Application()
    app.add_routes(create_agent_routes(registry, authenticate, lambda _: active))
    async with TestClient(TestServer(app)) as client:
        path = BASE + ("/sessions" if operation == "create" else "/sessions/synthetic/messages")
        body = {} if operation == "create" else {"text": "synthetic"}
        request = asyncio.create_task(client.post(path, json=body, headers={"Idempotency-Key": "revoke-wait"}))
        try:
            await asyncio.wait_for(entered.wait(), 1)
            active = False
            release.set()
            response = await asyncio.wait_for(request, 1)
            assert response.status == 401
            assert host.calls == 0
        finally:
            release.set()
            await registry.close()


@pytest.mark.asyncio
async def test_operation_recovery_uses_real_phone_signatures_and_rechecks_authority(gateway):
    from oai_core.server import REGISTRY_KEY
    from test_core_transport import PREFIX, phone_login, signed
    client, core, app = gateway
    host = Host()
    app[REGISTRY_KEY].register(host)
    host.release.set()
    bundle, key = await phone_login(client, core)
    async def request(method, path, body=None, operation_id=None):
        raw, headers = signed(bundle, key, method, path, body, request_id=operation_id)
        return await client.request(method, path, data=raw, headers=headers)
    result = await request("POST", BASE + "/sessions", {"title": "synthetic"}, "signed-create")
    assert result.status == 200
    response = await request("GET", BASE + "/operations/signed-create")
    assert response.status == 200
    assert (await response.json())["receipt"]["session"]["id"] == "new-native"
    bob, bob_key = await phone_login(client, core, "bob")
    raw, headers = signed(bob, bob_key, "GET", BASE + "/operations/signed-create")
    assert (await client.get(BASE + "/operations/signed-create", data=raw, headers=headers)).status == 404
    assert (await client.get(BASE + "/operations/signed-create")).status == 401
    assert (await request("GET", BASE + "/operations/signed-create?accountId=alice")).status == 400
    result = await request("GET", PREFIX + "/agents")
    assert next(agent for agent in (await result.json())["agents"] if agent["id"] == "probe")["capabilities"]["operationRecovery"]


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["delete", "cancel", "rename", "archive", "approval", "steer", "steer-discovery"])
async def test_every_control_rechecks_revocation_after_admission_or_generation_wait(action, monkeypatch):
    class ControlHost(Host):
        def descriptor(self):
            result = super().descriptor()
            result["capabilities"].update({name: True for name in ("delete", "cancel", "rename", "archive", "approvals", "steer")})
            return result
        async def manage(self, *args):
            self.calls += 1
            raise AssertionError("revoked management reached host")
        async def resolve_approval(self, *args):
            self.calls += 1
            raise AssertionError("revoked approval reached host")
        async def current_generation(self, principal, session_id):
            nonlocal active
            if action == "steer-discovery":
                active = False
            return {"generationId": "real-generation", "conversationId": session_id, "state": "running"}
        async def steer(self, *args):
            self.calls += 1
            raise AssertionError("revoked steering reached host")
    host = ControlHost()
    registry = AgentRegistry([host])
    active = True
    entered, release = asyncio.Event(), asyncio.Event()
    begin = registry.operations.begin
    async def blocked(*args, **kwargs):
        entered.set()
        await release.wait()
        return await begin(*args, **kwargs)
    monkeypatch.setattr(registry.operations, "begin", blocked)
    async def authenticate(_request):
        return Principal("alice")
    app = web.Application()
    app.add_routes(create_agent_routes(registry, authenticate, lambda _: active))
    async with TestClient(TestServer(app)) as client:
        path, method, body = BASE + "/sessions/synthetic", "POST", {}
        if action == "delete": method = "DELETE"
        elif action == "cancel": path += "/cancel"
        elif action in {"rename", "archive"}:
            method, body = "PATCH", {"action": action, "value": "new title" if action == "rename" else True}
        elif action == "approval": path, body = path + "/approvals/approval-real/decisions", {"decision": "once"}
        else: path, body = path + "/steer", {"text": "synthetic steering"}
        pending = asyncio.create_task(client.request(method, path, json=body, headers={"Idempotency-Key": "revoked-control"}))
        try:
            await asyncio.wait_for(entered.wait(), 1)
            if action != "steer-discovery": active = False
            release.set()
            response = await asyncio.wait_for(pending, 1)
            assert response.status == 401, await response.text()
            assert host.calls == 0
        finally:
            release.set()
            await registry.close()


@pytest.mark.asyncio
async def test_unauthenticated_message_headers_never_enter_diagnostic_records(caplog):
    import logging
    from oai_core.gateway.core import GatewayError
    async def authenticate(_request):
        raise GatewayError("AUTHENTICATION_REQUIRED")
    registry = AgentRegistry([Host()])
    app = web.Application()
    app.add_routes(create_agent_routes(registry, authenticate, lambda _: True))
    with caplog.at_level(logging.DEBUG, logger="oai_core.native_sessions"):
        async with TestClient(TestServer(app)) as client:
            response = await client.post(BASE + "/sessions/synthetic/messages", json={"text": "PRIVATE-MESSAGE"},
                headers={"X-Open-Android-Intelligence-Request-Id": "Bearer SYNTHETIC-CREDENTIAL",
                         "Idempotency-Key": "PRIVATE-KEY", "Authorization": "Bearer PRIVATE-AUTH"})
            assert response.status == 401
    await registry.close()
    for sensitive in ("SYNTHETIC-CREDENTIAL", "PRIVATE-KEY", "PRIVATE-AUTH", "PRIVATE-MESSAGE"):
        assert sensitive not in caplog.text
