"""Real Gateway account stores and wire events around native runtime seams."""
import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.adapters.hermes import HermesRemoteSessionAdapter
from oai_hermes_adapter.sessions import HermesSessionAdapter
from oai_core.auth import Principal
from oai_hermes_adapter.bridge import UnsupportedApprovalMixin, create_hermes_bridge_app
from oai_core.server import create_core
from oai_core.sessions import (AdapterError, AgentRegistry, SESSION_REQUEST_MAX_BYTES,
                               create_agent_routes, session_request_json)

CONTRACT = Path(__file__).resolve().parents[2] / "gateway-contract"


@pytest.fixture
def core(tmp_path):
    core = create_core({"storageRoot": str(tmp_path / "accounts"), "masterKeyFile": str(tmp_path / "master"), "contractRoot": str(CONTRACT)})
    for owner in ("alice", "bob"):
        account = core.open_gateway_account(owner)
        account.credentials.create_password("test-password")
        created = account.conversations.create("native_" + owner, owner, "create_" + owner)
        account.agent_sessions.record(created["conversationId"], "conversation-create")
        account.agent_sessions.attach(created["conversationId"], "session_" + owner, "key_" + owner)
        account.close()
    def history(owner, conversation):
        account = core.open_gateway_account(owner)
        try:
            assert account.agent_sessions.lookup(conversation)["agentSessionId"] == "session_" + owner
        finally:
            account.close()
        return [{"messageId": "message_" + owner, "sender": "assistant", "text": "private " + owner, "timestamp": 1000}]
    core.history_reader = history
    return core


@pytest.mark.asyncio
async def test_account_binding_is_authority_and_unbound_conversations_are_not_native(core):
    adapter = HermesSessionAdapter(core)
    alice = await adapter.list_sessions(Principal("alice"))
    bob = await adapter.list_sessions(Principal("bob"))
    assert alice[0]["id"] != bob[0]["id"]
    assert (await adapter.history(Principal("alice"), alice[0]["id"]))[0]["text"] == "private alice"
    with pytest.raises(AdapterError) as error:
        await adapter.history(Principal("bob"), alice[0]["id"])
    assert error.value.status == 404
    account = core.open_gateway_account("alice")
    account.conversations.create("unbound", "placeholder", "unbound")
    account.close()
    assert len(await adapter.list_sessions(Principal("alice"))) == 1
    assert not await adapter.status()
    assert not adapter.descriptor()["capabilities"]["create"]
    with pytest.raises(AdapterError):
        await adapter.create(Principal("alice"), "new")


@pytest.mark.asyncio
async def test_remote_mapping_is_one_to_one_and_header_identity_cannot_be_injected(core):
    adapter = HermesSessionAdapter(core)
    token = "test-only-hermes-bridge-token-1234567890"
    app = create_hermes_bridge_app(adapter, token, ["alice", "bob"])
    async with TestClient(TestServer(app)) as client:
        url = str(client.make_url("/")).rstrip("/")
        remote = HermesRemoteSessionAdapter(url, token, {"core-alice": "alice", "core-bob": "bob"})
        assert (await remote.list_sessions(Principal("core-alice")))[0]["title"] == "alice"
        assert (await remote.list_sessions(Principal("core-bob")))[0]["title"] == "bob"
        with pytest.raises(AdapterError) as error:
            await remote.list_sessions(Principal("unconnected"))
        assert error.value.status == 403
        response = await client.get("/oai/session-adapter/v1/sessions", headers={"X-OAI-Account": "alice"})
        assert response.status == 401
        response = await client.get("/oai/session-adapter/v1/sessions?accountId=bob", headers={"Authorization": "Bearer " + token, "X-OAI-Account": "alice"})
        assert response.status == 400
        response = await client.post("/oai/session-adapter/v1/sessions", json={"title": "new", "accountId": "bob"}, headers={"Authorization": "Bearer " + token, "X-OAI-Account": "alice"})
        assert response.status == 400
    with pytest.raises(ValueError):
        HermesRemoteSessionAdapter("http://localhost:8792", token, {"first": "alice", "second": "alice"})


@pytest.mark.asyncio
async def test_core_and_real_hermes_http_bridge_share_the_bounded_message_budget(core):
    received = []

    class Runtime:
        _loop = asyncio.get_running_loop()
        _session_store = object()
        _message_handler = staticmethod(lambda: None)
        services = SimpleNamespace(core=core)

        def _track_background_task(self, task, label):
            self.task = task

        async def _notify_agent_inbound(self, path, body, response, owner):
            received.append(json.loads(body)["text"])
            account = core.open_gateway_account(owner)
            try:
                account.conversations.workflow.settle(response["data"]["messageId"], "completed")
            finally:
                account.close()

    native = HermesSessionAdapter(core, Runtime())
    conversation = (await native.list_sessions(Principal("alice")))[0]["id"]
    token = "test-only-hermes-bridge-token-1234567890"
    bridge = create_hermes_bridge_app(native, token, ["alice"])
    async with TestClient(TestServer(bridge)) as host:
        remote = HermesRemoteSessionAdapter(str(host.make_url("/")).rstrip("/"), token, {"core-alice": "alice"})
        registry = AgentRegistry([remote])
        assert (await registry.discover())[0]["online"]

        async def authenticate(request):
            return Principal("core-alice", device_id="phone")

        app = web.Application(client_max_size=SESSION_REQUEST_MAX_BYTES)
        app.add_routes(create_agent_routes(registry, authenticate, lambda p: True))
        try:
            async with TestClient(TestServer(app)) as client:
                path = f"/open-android-intelligence/v2/agents/hermes/sessions/{conversation}/messages"
                for text in ("a" * 70000, "文" * 24000, "😀" * 100000):
                    body = session_request_json({"text": text}).encode()
                    assert 65536 < len(body) < SESSION_REQUEST_MAX_BYTES
                    response = await client.post(path, data=body, headers={"Idempotency-Key": uuid.uuid4().hex})
                    assert response.status == 200
                    assert '"type": "done"' in await response.text()
                    assert received[-1] == text
                before = len(received)
                headers = {"Idempotency-Key": uuid.uuid4().hex}
                oversized = session_request_json({"text": "😀" * 262144}).encode()
                response = await client.post(path, data=oversized, headers=headers)
                assert response.status == 413
                assert response.content_type == "application/json"
                assert await response.json() == {"error": {"code": "MESSAGE_TOO_LARGE"}}
                assert len(received) == before
                assert registry.operations.database.execute("SELECT COUNT(*) FROM native_operations").fetchone()[0] == before
                retry = await client.post(path, json={"text": "retry"}, headers=headers)
                assert retry.status == 200
                assert '"type": "done"' in await retry.text()
                assert len(received) == before + 1
            # The same original native session remains the sole account binding.
            assert [row["id"] for row in await native.list_sessions(Principal("alice"))] == [conversation]
            account = core.open_gateway_account("alice")
            try:
                assert len(account.agent_sessions.list()) == 1
            finally:
                account.close()
            # The internal listener independently rejects oversized direct requests.
            before = len(received)
            response = await host.post(f"/oai/session-adapter/v1/sessions/{conversation}/messages", data=oversized,
                                       headers={"Authorization": "Bearer " + token, "X-OAI-Account": "alice", "X-OAI-Device": "phone"})
            assert response.status == 413
            assert await response.json() == {"code": "MESSAGE_TOO_LARGE"}
            assert len(received) == before
        finally:
            await registry.close()


@pytest.mark.asyncio
async def test_real_event_store_frames_stream_accumulated_native_text_and_do_not_leak_accounts(core):
    adapter = HermesSessionAdapter(core)
    conversation = (await adapter.list_sessions(Principal("alice")))[0]["id"]

    class Runtime:
        _loop = asyncio.get_running_loop()
        _session_store = object()
        _message_handler = staticmethod(lambda: None)
        services = SimpleNamespace(core=core)
        def _track_background_task(self, task, label):
            self.task = task
        async def _notify_agent_inbound(self, path, body, response, owner):
            account = core.open_gateway_account(owner)
            try:
                accepted = response["data"]
                gid = accepted["generationId"]
                # These are validated/persisted by the actual pinned EventStore,
                # whose discriminator is eventType (not the transport type).
                for content in ("原生", "原生回复"):
                    account.events.append("conversation.message.delta", "stream", {
                        "conversationId": conversation, "messageId": "reply", "sender": "assistant",
                        "parts": [{"type": "text", "text": content}], "text": content,
                        "timestamp": 1000, "revision": 0, "generationId": gid})
                account.conversations.workflow.settle(accepted["messageId"], "completed")
            finally:
                account.close()
    adapter = HermesSessionAdapter(core, Runtime())
    events = [event async for event in adapter.send(Principal("alice", device_id="phone"), conversation, "hello")]
    assert [event["text"] for event in events if event["type"] == "delta"] == ["原生", "回复"]
    assert events[0]["type"] == "generation"
    assert events[-1] == {"type": "done", "text": "原生回复", "outcome": "completed",
                          "generationId": events[0]["generationId"]}
    assert core._event_sinks == []
    with pytest.raises(AdapterError) as error:
        await anext(adapter.send(Principal("bob", device_id="phone"), conversation, "hello"))
    assert error.value.status == 404


def test_hermes_runtime_must_share_its_gateway_core(core):
    with pytest.raises(ValueError):
        HermesSessionAdapter(core, SimpleNamespace(services=SimpleNamespace(core=object())))


@pytest.mark.asyncio
async def test_unsupported_exec_approval_is_denied_instead_of_waiting_for_an_unrenderable_card(core, monkeypatch):
    calls, events = [], []
    monkeypatch.setattr("oai_hermes_adapter.approvals.resolve_host_approval",
                        lambda session, choice, request_id=None: calls.append((session, choice, request_id)) or 1)
    core.register_event_sink(lambda account, event: events.append((account, event)))
    class Runtime(UnsupportedApprovalMixin):
        _unsupported_approval_result = staticmethod(lambda: SimpleNamespace(success=True))
        services = SimpleNamespace(core=core)
        _conv_to_account = {"chat": "alice"}
    result = await Runtime()._send_exec_approval_prompt(SimpleNamespace(chat_id="chat", session_key="native-key", metadata={"request_id": "native-request"}))
    assert calls == [("native-key", "deny", "native-request")]
    assert result.success
    assert events == [("alice", {"eventType": "core.approval.unsupported", "payload": {"conversationId": "chat", "code": "UNSUPPORTED_APPROVAL"}})]


def test_session_backend_has_no_device_services_and_exclusively_owns_legacy_db(core):
    import sqlite3
    from oai_hermes_adapter.backend import SessionBackend
    from oai_core.gateway.account_paths import account_paths
    from oai_core.gateway.core import GatewayError
    backend = SessionBackend(core.storage_root, ["alice", "bob"], secret_store=core.secret_store)
    backend.acquire_accounts()
    try:
        account = backend.open_gateway_account("alice")
        assert not hasattr(account, "device_requests")
        assert not hasattr(account, "sessions")
        assert not hasattr(account, "credentials")
        assert not hasattr(backend, "dispatch_gateway_request")
        assert account.agent_sessions.list()[0]["agentSessionId"] == "session_alice"
        account.close()
        competitor = sqlite3.connect(str(account_paths(core.storage_root, "alice").database), timeout=.01)
        try:
            with pytest.raises(sqlite3.OperationalError, match="locked"):
                competitor.execute("INSERT INTO account_metadata(key,value) VALUES ('old-gateway-writer','denied')")
        finally:
            competitor.close()
        with pytest.raises(GatewayError, match="AUTHENTICATION_REQUIRED"):
            backend.open_gateway_account("unmapped")
    finally:
        backend.close()
    # Releasing the new process' exclusive ownership allows an offline rollback.
    account = core.open_gateway_account("alice")
    try:
        assert account.agent_sessions.list()[0]["agentSessionId"] == "session_alice"
        assert account.credentials.verify_password("test-password")
    finally:
        account.close()


def test_session_backend_refuses_activation_while_legacy_writer_is_active(core):
    import sqlite3
    from oai_hermes_adapter.backend import SessionBackend
    from oai_core.gateway.account_paths import account_paths
    old_gateway = sqlite3.connect(str(account_paths(core.storage_root, "bob").database), isolation_level=None)
    old_gateway.execute("BEGIN IMMEDIATE")
    backend = SessionBackend(core.storage_root, ["alice", "bob"], secret_store=core.secret_store)
    try:
        with pytest.raises(sqlite3.OperationalError, match="locked"):
            backend.acquire_accounts()
        assert backend._stores == {}
        # Alice was acquired first. A later failure on Bob must release Alice
        # as well, so rollback is not stranded behind a leaked exclusive lock.
        released = sqlite3.connect(str(account_paths(core.storage_root, "alice").database), isolation_level=None, timeout=.01)
        try:
            released.execute("BEGIN IMMEDIATE")
            released.execute("ROLLBACK")
        finally:
            released.close()
    finally:
        old_gateway.execute("ROLLBACK")
        old_gateway.close()
        backend.close()


def test_uncertain_session_commit_retains_same_exclusive_connection(core):
    import sqlite3
    from oai_hermes_adapter.backend import LockedDatabase, SessionBackend
    from oai_core.gateway.account_paths import account_paths
    from oai_core.gateway.core import TransactionOutcomeUnknown
    backend = SessionBackend(core.storage_root, ["alice"], secret_store=core.secret_store)
    backend.acquire_accounts()
    account = backend.open_gateway_account("alice")
    database = account.store.database
    competitor = sqlite3.connect(str(account_paths(core.storage_root, "alice").database), timeout=.01)
    try:
        account.store.fail_next_commit = True
        with pytest.raises(TransactionOutcomeUnknown):
            with account.store.transaction():
                database.execute("INSERT INTO account_metadata(key,value) VALUES ('synthetic-uncertain','not-committed')")
        assert account.store.database is database
        assert isinstance(database, LockedDatabase)
        assert database.execute("SELECT value FROM account_metadata WHERE key='synthetic-uncertain'").fetchone() is None
        with pytest.raises(sqlite3.OperationalError, match="locked"):
            competitor.execute("INSERT INTO account_metadata(key,value) VALUES ('legacy-writer-after-fault','denied')")
        with account.store.transaction():
            database.execute("INSERT INTO account_metadata(key,value) VALUES ('session-after-fault','exclusive')")
    finally:
        competitor.close()
        account.close()
        backend.close()
    # Shutdown releases the original connection without depending on a
    # replacement connection's implementation or losing the old account data.
    with sqlite3.connect(str(account_paths(core.storage_root, "alice").database), timeout=.01) as restored:
        assert restored.execute("SELECT value FROM account_metadata WHERE key='session-after-fault'").fetchone()[0] == "exclusive"


def test_unrecoverable_session_connection_fails_closed_and_shutdown_still_releases(core):
    import sqlite3
    from oai_hermes_adapter.backend import SessionBackend
    from oai_core.gateway.account_paths import account_paths
    from oai_core.gateway.core import GatewayError
    backend = SessionBackend(core.storage_root, ["alice"], secret_store=core.secret_store)
    backend.acquire_accounts()
    account = backend.open_gateway_account("alice")
    account.store.commit_hook = account.store.database.connection.close
    try:
        with pytest.raises(GatewayError, match="HOST_INCOMPATIBLE"):
            with account.store.transaction():
                account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES ('synthetic-fatal','not-committed')")
        with pytest.raises(GatewayError, match="HOST_INCOMPATIBLE"):
            backend.open_gateway_account("alice")
        with pytest.raises(GatewayError, match="HOST_INCOMPATIBLE"):
            account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES ('unsafe-session-writer','denied')")
        # Once an unusable connection releases the lock, the new adapter can no
        # longer write; an offline rollback can safely become the sole writer.
        with sqlite3.connect(str(account_paths(core.storage_root, "alice").database), timeout=.01) as restored:
            restored.execute("INSERT INTO account_metadata(key,value) VALUES ('legacy-after-fatal','sole-writer')")
    finally:
        account.close()
        backend.close()
    assert backend._stores == {}


@pytest.mark.asyncio
async def test_hermes_bridge_and_adapter_propagate_commands_to_core(core, monkeypatch):
    import oai_hermes_adapter.host_commands as hc
    fake_commands = [
        {"id": "help", "invocation": "/help", "title": "Help", "description": "Help info", "acceptsArguments": False, "availability": "available"},
        {"id": "model", "invocation": "/model", "title": "Model switch", "description": "Switch model", "acceptsArguments": True, "availability": "available"}
    ]
    monkeypatch.setattr(hc, "command_catalog", lambda: fake_commands)

    adapter = HermesSessionAdapter(core)
    token = "test-only-hermes-bridge-token-1234567890"
    app = create_hermes_bridge_app(adapter, token, ["alice"])
    async with TestClient(TestServer(app)) as client:
        # 1. Verify GET /commands on HTTP bridge
        resp = await client.get("/oai/session-adapter/v1/commands", headers={"Authorization": f"Bearer {token}"})
        assert resp.status == 200
        data = await resp.json()
        assert data["commands"] == fake_commands

        # 2. Verify HermesRemoteSessionAdapter commands()
        url = str(client.make_url("/")).rstrip("/")
        remote = HermesRemoteSessionAdapter(url, token, {"core-alice": "alice"})
        cmds = await remote.commands("zh-CN")
        assert cmds == fake_commands

        # 3. Verify AgentRegistry and Core command_catalog response
        registry = AgentRegistry([remote])
        all_cmds = await registry.commands("zh-CN")
        assert "/help" in [c["invocation"] for c in all_cmds]
        assert "/model" in [c["invocation"] for c in all_cmds]
        
        # Test Core command_catalog wiring
        core.command_catalog = lambda: fake_commands
        core_resp = core.command_catalog_response("zh-CN")
        assert core_resp["format"] == "agent-command-catalog-1.0"
        assert len(core_resp["commands"]) == 2

