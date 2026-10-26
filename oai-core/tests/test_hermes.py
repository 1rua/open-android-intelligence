"""Real Gateway account stores and wire events around native runtime seams."""
import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.adapters.hermes import HermesRemoteSessionAdapter, HermesSessionAdapter
from oai_core.auth import Principal
from oai_core.hermes_host import UnsupportedApprovalMixin, create_hermes_bridge_app
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
    assert events[-1] == {"type": "done", "text": "原生回复"}
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
    monkeypatch.setattr("open_android_intelligence_gateway.core.resolve_host_approval",
                        lambda session, choice, request_id=None: calls.append((session, choice, request_id)) or 1)
    core.register_event_sink(lambda account, event: events.append((account, event)))
    class Runtime(UnsupportedApprovalMixin):
        services = SimpleNamespace(core=core)
        _conv_to_account = {"chat": "alice"}
    result = await Runtime()._send_exec_approval_prompt(SimpleNamespace(chat_id="chat", session_key="native-key", metadata={"request_id": "native-request"}))
    assert calls == [("native-key", "deny", "native-request")]
    assert result.success
    assert events == [("alice", {"eventType": "core.approval.unsupported", "payload": {"conversationId": "chat", "code": "UNSUPPORTED_APPROVAL"}})]
