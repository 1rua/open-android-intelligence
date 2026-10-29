"""Real Gateway account stores and wire events around native runtime seams."""
import asyncio
from contextlib import closing
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
    assert adapter.descriptor()["capabilities"]["generationRecovery"]
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
        registry = AgentRegistry([remote])
        try:
            discovered = await registry.discover()
            assert not discovered[0]["online"]
            assert discovered[0]["capabilities"]["generationRecovery"]
        finally:
            await registry.close()
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
                          "generationId": events[0]["generationId"], "messageId": "reply",
                          "contentRevision": 0, "eventId": events[-2]["eventId"],
                          "clientMessageId": events[0]["clientMessageId"], "userMessageId": events[0]["userMessageId"]}
    assert all(event["generationId"] == events[0]["generationId"] for event in events)
    assert [event["contentRevision"] for event in events if event["type"] == "delta"] == [0, 0]
    assert core._event_sinks == []
    with pytest.raises(AdapterError) as error:
        await anext(adapter.send(Principal("bob", device_id="phone"), conversation, "hello"))
    assert error.value.status == 404


@pytest.mark.asyncio
async def test_slow_stream_replays_every_native_message_in_commit_order(core):
    """A stalled phone cannot evict earlier real replies behind a delta burst."""
    principal = Principal("alice", device_id="phone")

    class Runtime:
        _loop = asyncio.get_running_loop()
        _session_store = object()
        _message_handler = staticmethod(lambda: None)
        services = SimpleNamespace(core=core)

        def _track_background_task(self, task, label):
            self.task = task

        async def _notify_agent_inbound(self, path, body, response, owner):
            accepted = response["data"]
            with closing(core.open_gateway_account(owner)) as account, account.store.transaction():
                core.request_command_approval(account, "burst-approval", accepted["conversationId"],
                    "synthetic command", "synthetic approval", [{"choice": "once", "label": "允许一次"}], 60)
                account.approvals.settle("burst-approval", "once")
                account.events.append("conversation.approval.resolved", "burst-approval", {
                    "approvalId": "burst-approval", "conversationId": accepted["conversationId"],
                    "decision": "once", "decidedAt": 1000,
                })
                for index in range(1030):
                    account.events.append("conversation.message.delta", "burst", {
                        "conversationId": accepted["conversationId"],
                        "messageId": f"reply-{index}",
                        "sender": "assistant", "parts": [{"type": "text", "text": str(index)}],
                        "text": str(index), "timestamp": 1000, "revision": index,
                        "generationId": accepted["generationId"],
                    })
                account.conversations.workflow.settle(accepted["messageId"], "completed")

    runtime = Runtime()
    adapter = HermesSessionAdapter(core, runtime)
    conversation = (await adapter.list_sessions(principal))[0]["id"]
    stream = adapter.send(principal, conversation, "synthetic", client_message_id="slow-client")
    try:
        first = await anext(stream)
        assert first["type"] == "generation"
        # Leave the observer suspended until the entire host burst commits.
        await runtime.task
        await asyncio.sleep(0)
        events = [event async for event in stream]
    finally:
        await stream.aclose()
    replies = [event for event in events if event["type"] == "delta"]
    assert [event["type"] for event in events[:2]] == ["approval_requested", "approval_resolved"]
    assert [event["messageId"] for event in replies] == [f"reply-{index}" for index in range(1030)]
    assert events[-1]["type"] == "done"
    assert events[-1]["text"] == "1029"
    assert core._event_sinks == []


def test_hermes_runtime_must_share_its_gateway_core(core):
    with pytest.raises(ValueError):
        HermesSessionAdapter(core, SimpleNamespace(services=SimpleNamespace(core=object())))


@pytest.mark.asyncio
async def test_completed_generation_remains_queryable_after_stream_and_runtime_close(core):
    adapter = HermesSessionAdapter(core)
    alice = Principal("alice", device_id="phone")
    conversation = (await adapter.list_sessions(alice))[0]["id"]
    account = core.open_gateway_account("alice")
    try:
        accepted = account.conversations.accept_message(
            conversation, "durable-client", "synthetic", [], "phone", "accept", "accept")
        assert account.conversations.workflow.for_message(accepted["messageId"])["revision"] == 0
        account.conversations.workflow.claim(accepted["messageId"])
        assert account.conversations.workflow.for_message(accepted["messageId"])["revision"] == 1
        account.conversations.workflow.settle(accepted["messageId"], "completed")
        account.conversations.workflow.settle(accepted["messageId"], "running")
    finally:
        account.close()

    receipt = await adapter.generation(alice, conversation, client_message_id="durable-client")
    assert receipt == {
        "conversationId": conversation, "generationId": accepted["generationId"],
        "clientMessageId": "durable-client", "userMessageId": accepted["messageId"],
        "state": "completed", "revision": 2,
    }
    assert await adapter.generation(alice, conversation, generation_id=accepted["generationId"]) == receipt
    assert await asyncio.wait_for(adapter.generation(
        alice, conversation, generation_id=accepted["generationId"], after_revision=2, wait_ms=25000), .5) == receipt
    with pytest.raises(AdapterError) as mismatch:
        await adapter.generation(alice, conversation, generation_id=accepted["generationId"],
                                 client_message_id="different-client")
    assert mismatch.value.code == "GENERATION_NOT_FOUND"
    with pytest.raises(AdapterError) as another_account:
        await adapter.generation(Principal("bob"), conversation, generation_id=accepted["generationId"])
    assert another_account.value.status == 404

    token = "synthetic-terminal-query-bridge-token-1234567890"
    async with TestClient(TestServer(create_hermes_bridge_app(adapter, token, ["alice", "bob"]))) as client:
        headers = {"Authorization": "Bearer " + token, "X-OAI-Account": "alice"}
        path = "/oai/session-adapter/v1/sessions/" + conversation + "/generation"
        response = await client.get(path, params={"clientMessageId": "durable-client"}, headers=headers)
        assert response.status == 200
        assert (await response.json())["generation"] == receipt
        for query in ("clientMessageId=", "accountId=bob", "clientMessageId=a&clientMessageId=b"):
            response = await client.get(path + "?" + query, headers=headers)
            assert response.status == 400


@pytest.mark.asyncio
async def test_generation_long_poll_wakes_on_committed_turn_event(core, monkeypatch):
    adapter = HermesSessionAdapter(core)
    principal = Principal("alice", device_id="phone")
    conversation = (await adapter.list_sessions(principal))[0]["id"]
    with closing(core.open_gateway_account("alice")) as account:
        accepted = account.conversations.accept_message(
            conversation, "wait-client", "synthetic", [], "phone", "accept", "accept")
    registered = asyncio.Event()
    original_register = core.register_event_sink
    def register(sink):
        original_register(sink)
        registered.set()
    monkeypatch.setattr(core, "register_event_sink", register)
    waiter = asyncio.create_task(adapter.generation(
        principal, conversation, client_message_id="wait-client", after_revision=0, wait_ms=25000))
    await asyncio.wait_for(registered.wait(), .5)
    with closing(core.open_gateway_account("alice")) as account:
        account.conversations.workflow.settle(accepted["messageId"], "failed")
        account.events.append("conversation.message.status", "terminal-wake", {
            "conversationId": conversation, "messageId": accepted["messageId"],
            "clientMessageId": "wait-client", "status": "failed", "revision": 1,
            "errorCode": "AGENT_UNAVAILABLE", "generationId": accepted["generationId"],
        })
    result = await asyncio.wait_for(waiter, .5)
    assert result["state"] == "failed"
    assert result["revision"] == 1
    assert core._event_sinks == []


@pytest.mark.asyncio
async def test_history_preserves_authoritative_identity_revision_and_generation(core):
    adapter = HermesSessionAdapter(core)
    alice = Principal("alice")
    conversation = (await adapter.list_sessions(alice))[0]["id"]
    core.history_reader = lambda *_args: [{
        "messageId": "native-assistant", "sender": "assistant", "text": "synthetic",
        "timestamp": 1000, "generationId": "generation-native", "contentRevision": 7,
        "state": "CONFIRMED", "anchorClientMessageId": "client-native", "parts": [],
    }]
    message = (await adapter.history(alice, conversation))[0]
    assert message["generationId"] == "generation-native"
    assert message["contentRevision"] == 7
    assert message["state"] == "CONFIRMED"


@pytest.mark.asyncio
async def test_unrenderable_exec_approval_falls_back_to_the_host_text_prompt(core, monkeypatch):
    calls, events = [], []
    monkeypatch.setattr("oai_hermes_adapter.approvals.resolve_host_approval",
                        lambda session, choice, request_id=None: calls.append((session, choice, request_id)) or 1)
    monkeypatch.setattr("oai_hermes_adapter.approvals.pending_host_request_id", lambda session, command="": None)
    core.register_event_sink(lambda account, event: events.append((account, event)))
    class Runtime(UnsupportedApprovalMixin):
        _unsupported_approval_result = staticmethod(lambda: SimpleNamespace(success=True))
        _unrenderable_approval_result = staticmethod(
            lambda: SimpleNamespace(success=False, error="NATIVE_APPROVAL_UNAVAILABLE"))
        services = SimpleNamespace(core=core)
        _conv_to_account = {"chat": "alice"}
    result = await Runtime()._send_exec_approval_prompt(SimpleNamespace(chat_id="chat", session_key="native-key", metadata={}))
    # 绝不代替用户拒绝：没有决议调用，也不向 App 报 unsupported。
    assert calls == []
    assert events == []
    # success=False 是宿主的文本兜底开关（发 /approve 说明，由用户手动回复）。
    assert result.success is False
    assert result.error == "NATIVE_APPROVAL_UNAVAILABLE"


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



@pytest.mark.asyncio
@pytest.mark.parametrize("text,filename,media_type,content", [
    ("", "image.png", "image/png", b"png-content" * 10000),
    ("请阅读文档", "report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", b"document-content" * 10000),
], ids=["pure-image", "text-and-docx"])
async def test_native_attachment_flows_between_distinct_account_stores(core, tmp_path, text, filename, media_type, content):
    import hashlib
    upstream = create_core({"storageRoot": str(tmp_path / "upstream"), "masterKeyFile": str(tmp_path / "upstream-key"), "contractRoot": str(CONTRACT)})
    account = upstream.open_gateway_account("core-alice")
    account.credentials.create_password("test-password")
    attachment = account.attachments.create(clientAttachmentId="chosen-file", filename=filename,
        mediaType=media_type, sizeBytes=len(content), sha256=hashlib.sha256(content).hexdigest(),
        deviceId="phone", pairingGeneration=3, correlationId="upload")
    aid = attachment["attachmentId"]
    account.attachments.upload_content_stream(aid, (content[i:i+4096] for i in range(0, len(content), 4096)))
    account.attachments.commit(aid)
    account.close()
    received = []

    class Runtime:
        _loop = asyncio.get_running_loop()
        _session_store = object()
        _message_handler = staticmethod(lambda: None)
        services = SimpleNamespace(core=core)
        def _track_background_task(self, task, label):
            self.task = task
        async def _notify_agent_inbound(self, path, body, response, owner):
            body = json.loads(body)
            with_adapter_account = core.open_gateway_account(owner)
            try:
                record = with_adapter_account.conversations.dispatch_message(body["clientMessageId"])
                metadata = record["attachments"][0]
                data = b"".join(with_adapter_account.attachments.open_verified_stream(metadata["attachmentId"]))
                received.append((record["text"], metadata["filename"], metadata["mediaType"], data))
                core.history_reader = lambda owner, session: [{"messageId": response["data"]["messageId"],
                    "sender": "user", "clientMessageId": body["clientMessageId"], "text": text, "timestamp": 1234,
                    "parts": [{"type": "attachment", **{key: metadata[key] for key in
                        ("attachmentId", "filename", "mediaType", "sizeBytes", "sha256")}}]}]
                with_adapter_account.events.append("conversation.message.completed", "native-reply", {
                    "conversationId": response["data"]["conversationId"], "messageId": "native-reply-19", "sender": "assistant",
                    "text": "已读", "parts": [{"type": "text", "text": "已读"}], "timestamp": 1234, "revision": 1,
                    "generationId": response["data"]["generationId"]})
                with_adapter_account.conversations.workflow.settle(response["data"]["messageId"], "completed")
            finally:
                with_adapter_account.close()

    native = HermesSessionAdapter(core, Runtime())
    conversation = (await native.list_sessions(Principal("alice")))[0]["id"]
    token = "test-only-hermes-bridge-token-1234567890"
    async with TestClient(TestServer(create_hermes_bridge_app(native, token, ["alice"]))) as bridge:
        remote = HermesRemoteSessionAdapter(str(bridge.make_url("/")).rstrip("/"), token, {"core-alice": "alice"})
        registry = AgentRegistry([remote])
        assert (await registry.discover())[0]["capabilities"]["attachments"]
        async def authenticate(request):
            return Principal("core-alice", device_id="phone", pairing_generation=3)
        app = web.Application()
        app.add_routes(create_agent_routes(registry, authenticate, lambda p: True, core=upstream))
        try:
            async with TestClient(TestServer(app)) as client:
                response = await client.post(f"/open-android-intelligence/v2/agents/hermes/sessions/{conversation}/messages",
                    json={"text": text, "attachmentIds": [aid], "clientMessageId": "android-19"},
                    headers={"Idempotency-Key": uuid.uuid4().hex})
                assert response.status == 200, await response.text()
                events = [json.loads(line[6:]) for line in (await response.text()).splitlines() if line.startswith("data: ")]
                assert events[0].get("clientMessageId") == "android-19", events
                assert events[-1]["type"] == "done"
                assert events[-1]["messageId"] == "native-reply-19"
                assert received == [(text, filename, media_type, content)]
                # A fresh adapter reads the durable source-ID mapping and wire
                # digest format, rather than depending on in-memory send state.
                reloaded = HermesRemoteSessionAdapter(str(bridge.make_url("/")).rstrip("/"), token,
                    {"core-alice": "alice"}, attachment_core=upstream)
                history = await reloaded.history(Principal("core-alice"), conversation)
                part = history[0]["attachments"][0]
                assert part["attachmentId"] == aid
                assert part["sha256"] == "sha256:" + hashlib.sha256(content).hexdigest()
                assert part["filename"] == filename
        finally:
            await registry.close()


@pytest.mark.asyncio
async def test_attachment_reference_requires_its_original_device_pairing(core):
    import hashlib
    from oai_core.sessions import native_attachments
    account = core.open_gateway_account("alice")
    created = account.attachments.create(clientAttachmentId="private", filename="private.png", mediaType="image/png",
        sizeBytes=3, sha256=hashlib.sha256(b"png").hexdigest(), deviceId="phone", pairingGeneration=3, correlationId="private")
    aid = created["attachmentId"]
    account.attachments.upload_content(aid, b"png")
    account.attachments.commit(aid)
    account.close()
    assert native_attachments(core, Principal("alice", device_id="phone", pairing_generation=3), [aid])[0].metadata["filename"] == "private.png"
    for principal in (Principal("alice", device_id="other", pairing_generation=3),
                      Principal("alice", device_id="phone", pairing_generation=4), Principal("alice")):
        with pytest.raises(AdapterError, match="ATTACHMENT_ACCESS_DENIED"):
            native_attachments(core, principal, [aid])


@pytest.mark.asyncio
async def test_history_correlates_only_the_last_assistant_and_preserves_attachment_parts(core):
    rows = [
        {"messageId": "user", "sender": "user", "clientMessageId": "android", "text": "", "timestamp": 1234,
         "parts": [{"type": "attachment", "attachmentId": "image", "filename": "image.png", "mediaType": "image/png", "sizeBytes": 8}]},
        {"messageId": "process", "sender": "assistant", "anchorClientMessageId": "android", "text": "Reading config", "timestamp": 1235},
        {"messageId": "answer", "sender": "assistant", "anchorClientMessageId": "android", "text": "Answer", "timestamp": 1236},
    ]
    core.history_reader = lambda owner, conversation: rows
    adapter = HermesSessionAdapter(core)
    conversation = (await adapter.list_sessions(Principal("alice")))[0]["id"]
    history = await adapter.history(Principal("alice"), conversation)
    assert [row["clientMessageId"] for row in history] == ["android", None, "android"]
    assert [row["createdAt"] for row in history] == [1234, 1235, 1236]
    assert history[0]["attachments"] == rows[0]["parts"]


@pytest.mark.asyncio
async def test_hermes_private_attachment_upload_checks_pairing_and_actual_digest(core):
    import hashlib
    token = "test-only-hermes-bridge-token-1234567890"
    runtime = SimpleNamespace(_loop=asyncio.get_running_loop(), _session_store=object(), _message_handler=lambda: None,
                              services=SimpleNamespace(core=core))
    bridge = create_hermes_bridge_app(HermesSessionAdapter(core, runtime), token, ["alice"])
    headers = {"Authorization": "Bearer " + token, "X-OAI-Account": "alice", "X-OAI-Device": "phone", "X-OAI-Pairing-Generation": "3"}
    async with TestClient(TestServer(bridge)) as client:
        response = await client.post("/oai/session-adapter/v1/attachments", headers=headers, json={
            "clientAttachmentId": "source", "filename": "image.png", "mediaType": "image/png", "sizeBytes": 4,
            "sha256": hashlib.sha256(b"good").hexdigest()})
        attachment_id = (await response.json())["attachmentId"]
        path = f"/oai/session-adapter/v1/attachments/{attachment_id}/content"
        response = await client.put(path, headers=headers | {"X-OAI-Pairing-Generation": "4"}, data=b"good")
        assert response.status == 403
        assert (await response.json())["code"] == "ATTACHMENT_ACCESS_DENIED"
        response = await client.put(path, headers=headers, data=b"bad!")
        assert response.status == 409
        assert (await response.json())["code"] == "ATTACHMENT_DIGEST_MISMATCH"


@pytest.mark.asyncio
async def test_upload_completion_rechecks_authentication_before_native_send(core):
    from oai_core.sessions import NativeAttachment
    active = True
    sent = []
    async def is_active():
        return active
    class RevokingRemote(HermesRemoteSessionAdapter):
        async def _upload_attachment(self, attachment, headers, client_message_id):
            nonlocal active
            active = False
            return "uploaded"
        async def _send(self, *args, **kwargs):
            sent.append(True)
            yield {"type": "done"}
    adapter = RevokingRemote("http://localhost:8792", "test-only-hermes-bridge-token-1234567890", {"alice": "alice"})
    adapter.capabilities["attachments"] = True
    with pytest.raises(AdapterError, match="SESSION_REVOKED"):
        _ = [event async for event in adapter.send(Principal("alice"), "session", "", attachments=[
            NativeAttachment(core, Principal("alice"), {"attachmentId": "source"}, is_active)])]
    assert sent == []


@pytest.mark.asyncio
async def test_client_message_identity_cannot_rebind_or_redispatch_an_accepted_turn(core):
    notifications = []
    class Runtime:
        _loop = asyncio.get_running_loop()
        _session_store = object()
        _message_handler = staticmethod(lambda: None)
        services = SimpleNamespace(core=core)
        def _track_background_task(self, task, label):
            self.task = task
        async def _notify_agent_inbound(self, path, body, response, owner):
            notifications.append(json.loads(body))
            account = core.open_gateway_account(owner)
            try:
                account.conversations.workflow.settle(response["data"]["messageId"], "completed")
            finally:
                account.close()
    adapter = HermesSessionAdapter(core, Runtime())
    conversation = (await adapter.list_sessions(Principal("alice")))[0]["id"]
    principal = Principal("alice", device_id="phone", pairing_generation=3)
    events = [event async for event in adapter.send(principal, conversation, "once", client_message_id="android-once")]
    assert events[-1]["type"] == "done"
    with pytest.raises(AdapterError, match="REQUEST_ALREADY_EXECUTED"):
        _ = [event async for event in adapter.send(Principal("alice", device_id="other", pairing_generation=4),
            conversation, "once", client_message_id="android-once")]
    assert len(notifications) == 1
    account = core.open_gateway_account("alice")
    try:
        origin = json.loads(account.store.database.execute("SELECT value FROM account_metadata WHERE key=?", ("message-device:android-once",)).fetchone()[0])
        assert origin["deviceId"] == "phone"
        assert origin["pairingGeneration"] == 3
    finally:
        account.close()
