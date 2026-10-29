"""Signed phone requests through Core and the real Hermes HTTP session bridge.

Only the Agent's execution is controlled: no model or shell command is run.
Account stores, native session bindings, approval storage, HTTP, NDJSON and SSE
are the production implementations on both sides of the bridge.
"""
from __future__ import annotations

import asyncio
from contextlib import suppress
import json
import logging
from pathlib import Path
import threading
from types import SimpleNamespace

import pytest
from aiohttp.test_utils import TestClient, TestServer

from oai_core.adapters.hermes import HermesRemoteSessionAdapter
from oai_core.server import create_app, create_core
from oai_core.sessions import AgentRegistry
from oai_hermes_adapter import approvals
from oai_hermes_adapter.backend import SessionBackend
from oai_hermes_adapter.bridge import UnsupportedApprovalMixin, create_hermes_bridge_app
from oai_hermes_adapter.sessions import HermesSessionAdapter
from test_core_transport import phone_login, signed

CONTRACT = Path(__file__).resolve().parents[2] / "gateway-contract"
BASE = "/open-android-intelligence/v2"
AGENT = BASE + "/agents/hermes"
TOKEN = "test-only-interaction-session-bridge-token-123456"


async def next_event(response):
    """Read one actual SSE data frame, keeping the stream open for a decision."""
    async with asyncio.timeout(5):
        while line := await response.content.readline():
            if line.startswith(b"data: "):
                return json.loads(line[6:])
    raise AssertionError("native conversation stream ended without its terminal event")


async def request(client, identity, method, path, body=None, request_id=None):
    raw, headers = signed(*identity, method, path, body, request_id=request_id)
    return await client.request(method, path, data=raw, headers=headers)


class ControlledExecution(UnsupportedApprovalMixin):
    """Control only the host task, while exercising real native session state."""

    def __init__(self, backend, conversations):
        self.services = SimpleNamespace(core=backend)
        self._loop = asyncio.get_running_loop()
        self._session_store = object()
        self._message_handler = lambda: None
        self._conv_to_account = {conversation: owner for owner, conversation in conversations.items()}
        self.tasks = set()
        self.turns = {}
        self.waiting = {}
        self.resolved = []
        self.interrupted = []
        self.approval_timeout_seconds = 30
        self.resolver_return_gate = None
        self.host_finished = asyncio.Event()
        self.observer_release = asyncio.Event()
        backend.generation_canceller = self.cancel

    @staticmethod
    def _approval_result(approval_id):
        return SimpleNamespace(success=True, message_id=approval_id)

    @staticmethod
    def _unsupported_approval_result():
        return SimpleNamespace(success=True)

    def _track_background_task(self, task, _label):
        self.tasks.add(task)

    def resolve(self, session_key, choice, request_id=None):
        pending = self.waiting.get((session_key, request_id))
        if pending is None or pending.choice is not None:
            return 0
        pending.choice = choice
        self.resolved.append((session_key, choice, request_id))
        self._loop.call_soon_threadsafe(pending.release.set)
        if self.resolver_return_gate is not None:
            assert self.resolver_return_gate.wait(3), "controlled approval resolver was not released"
        return 1

    def cancel(self, owner, conversation, generation_id):
        async def stop():
            task = self.turns.get((owner, conversation, generation_id))
            if task is None or task.done():
                return "OUTCOME_UNKNOWN"
            self.interrupted.append((owner, conversation, generation_id))
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task
            return "CANCELLED"

        return asyncio.run_coroutine_threadsafe(stop(), self._loop).result(4)

    def emit_text(self, owner, conversation, generation_id, text):
        account = self.services.core.open_gateway_account(owner)
        try:
            account.events.append("conversation.message.delta", generation_id, {
                "conversationId": conversation, "messageId": "reply_" + generation_id,
                "sender": "assistant", "parts": [{"type": "text", "text": text}],
                "text": text, "timestamp": 1000, "revision": 0,
                "generationId": generation_id,
            })
        finally:
            account.close()

    async def _notify_agent_inbound(self, _path, body, response, owner):
        accepted = response["data"]
        generation_id = accepted["generationId"]
        conversation = accepted["conversationId"]
        self.turns[(owner, conversation, generation_id)] = asyncio.current_task()
        account = self.services.core.open_gateway_account(owner)
        try:
            assert account.conversations.workflow.claim(accepted["messageId"])
            binding = account.agent_sessions.lookup(conversation)
            session_key = binding["sessionKey"]
        finally:
            account.close()
        text = json.loads(body)["text"]
        self.emit_text(owner, conversation, generation_id, "开始")
        if text == "hold":
            await asyncio.Event().wait()
        elif text == "disconnect":
            await self.observer_release.wait()
            self.emit_text(owner, conversation, generation_id, "开始：观察断开后完成")
        elif text == "approval":
            host_request = "host-request-secret-" + generation_id
            pending = SimpleNamespace(release=asyncio.Event(), choice=None)
            self.waiting[(session_key, host_request)] = pending
            result = await self._send_exec_approval_prompt(SimpleNamespace(
                chat_id=conversation, session_key=session_key,
                command="printf 'controlled approval fixture'", description="测试执行审批",
                smart_denied=False, text="测试审批",
                actions=[("允许一次", "once", "primary"), ("拒绝", "deny", "danger")],
                metadata={"account_id": owner, "request_id": host_request,
                          "timeout_seconds": self.approval_timeout_seconds},
            ))
            assert result.success
            await pending.release.wait()
            self.emit_text(owner, conversation, generation_id,
                           "开始：已允许" if pending.choice == "once" else "开始：已拒绝")
        else:
            self.emit_text(owner, conversation, generation_id, "开始：完成")
        account = self.services.core.open_gateway_account(owner)
        try:
            with account.store.transaction():
                account.conversations.workflow.settle(accepted["messageId"], "completed")
        finally:
            account.close()
        self.host_finished.set()

    async def close(self):
        for task in self.tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)


@pytest.fixture
async def pipeline(tmp_path, monkeypatch):
    host_settings = {
        "storageRoot": str(tmp_path / "host-data"),
        "masterKeyFile": str(tmp_path / "host-master"), "contractRoot": str(CONTRACT),
    }
    legacy = create_core(host_settings)
    conversations = {}
    for owner in ("native-alice", "native-bob"):
        account = legacy.open_gateway_account(owner)
        try:
            created = account.conversations.create("existing_" + owner, owner, "seed_" + owner)
            conversation = conversations[owner] = created["conversationId"]
            account.agent_sessions.record(conversation, "conversation-create")
            account.agent_sessions.attach(conversation, "native-session-" + owner, "native-key-secret-" + owner)
        finally:
            account.close()
    backend = SessionBackend(legacy.storage_root, list(conversations), secret_store=legacy.secret_store)
    backend.acquire_accounts()
    backend.history_reader = lambda owner, conversation: []
    runtime = ControlledExecution(backend, conversations)
    monkeypatch.setattr(approvals, "resolve_host_approval", runtime.resolve)
    monkeypatch.setattr(approvals, "host_approval_available", lambda: True, raising=False)
    catalog = [
        {"id": "help", "invocation": "/help", "title": "帮助", "description": "宿主帮助",
         "acceptsArguments": False, "availability": "available"},
        {"id": "plugin-check", "invocation": "/plugin-check", "title": "插件命令", "description": "宿主插件扩展",
         "acceptsArguments": True, "availability": "available", "argumentHint": "<target>"},
    ]
    monkeypatch.setattr("oai_hermes_adapter.host_commands.command_catalog", lambda: list(catalog))
    native = HermesSessionAdapter(backend, runtime)
    settings = {
        "storageRoot": str(tmp_path / "core-data"),
        "masterKeyFile": str(tmp_path / "core-master"), "contractRoot": str(CONTRACT),
    }
    core = create_core(settings)
    for owner in ("alice", "bob"):
        account = core.open_gateway_account(owner)
        try:
            account.credentials.create_password("test-only-password")
        finally:
            account.close()
    try:
        async with TestClient(TestServer(create_hermes_bridge_app(native, TOKEN, list(conversations)))) as host:
            remote = HermesRemoteSessionAdapter(str(host.make_url("/")).rstrip("/"), TOKEN,
                                                {"alice": "native-alice", "bob": "native-bob"})
            registry = AgentRegistry([remote])
            assert (await registry.discover())[0]["online"]
            async with TestClient(TestServer(create_app(settings, registry=registry, core=core))) as client:
                alice = await phone_login(client, core, "alice")
                bob = await phone_login(client, core, "bob")
                yield SimpleNamespace(client=client, core=core, backend=backend, runtime=runtime,
                                      alice=alice, bob=bob, catalog=catalog,
                                      conversation=conversations["native-alice"], host=host)
    finally:
        await runtime.close()
        backend.close()


@pytest.mark.asyncio
async def test_negotiation_advertises_only_requested_native_interactions(pipeline):
    p = pipeline
    negotiation = json.loads((CONTRACT / "vectors/protocol-negotiation.json").read_text())["cases"][0]["input"]["value"]
    negotiation["negotiationId"] = "pipeline-native-features"
    negotiation["schemaHashes"]["core"] = p.core.contracts.core_schema_hash
    native_features = ["agent-command-catalog-v1", "agent-approval-cards-v1", "generation-cancel-v1"]
    negotiation["features"]["conversationUi"] = native_features + ["message-batches-v1", "conversation-management-v1"]
    response = await p.client.post(BASE + "/negotiate", json=negotiation)
    assert response.status == 200, await response.text()
    assert response.headers["X-OAI-Agent-Sessions"] == "1"
    assert (await response.json())["data"]["features"]["conversationUi"] == native_features
    negotiation["negotiationId"] = "pipeline-command-only"
    negotiation["features"]["conversationUi"] = ["agent-command-catalog-v1"]
    response = await p.client.post(BASE + "/negotiate", json=negotiation)
    assert response.status == 200, await response.text()
    assert (await response.json())["data"]["features"]["conversationUi"] == ["agent-command-catalog-v1"]


@pytest.mark.asyncio
async def test_signed_discovery_and_versioned_host_catalog_cross_both_http_layers(pipeline):
    p = pipeline
    response = await request(p.client, p.alice, "GET", BASE + "/agents")
    assert response.status == 200, await response.text()
    capabilities = (await response.json())["agents"][0]["capabilities"]
    assert all(capabilities[name] for name in ("commands", "approvals", "cancel"))
    assert capabilities["generationRecovery"]
    response = await request(p.client, p.alice, "GET", AGENT + "/commands?languageCode=zh-CN")
    assert response.status == 200, await response.text()
    first = await response.json()
    assert first["format"] == "agent-command-catalog-1.0"
    assert first["languageCode"] == "zh-CN"
    assert first["commands"] == p.catalog
    assert first["commands"][1]["argumentHint"] == "<target>"
    response = await request(p.client, p.alice, "GET", AGENT + "/commands?languageCode=zh-CN")
    assert (await response.json())["catalogVersion"] == first["catalogVersion"]
    p.catalog[1] = {**p.catalog[1], "description": "插件更新后的描述"}
    response = await request(p.client, p.alice, "GET", AGENT + "/commands?languageCode=zh-CN")
    assert (await response.json())["catalogVersion"] != first["catalogVersion"]


@pytest.mark.asyncio
async def test_boundary_timing_logs_public_identity_without_transcript_or_tokens(pipeline, caplog):
    p = pipeline
    caplog.set_level(logging.DEBUG, logger="oai_core.native_sessions")
    private_text = "private-synthetic-transcript-must-not-enter-diagnostics"
    path = AGENT + "/sessions/" + p.conversation + "/messages"
    stream = await request(p.client, p.alice, "POST", path,
        {"text": private_text, "clientMessageId": "timing-client"})
    try:
        events = []
        while True:
            event = await next_event(stream)
            events.append(event)
            if event["type"] in {"done", "error"}:
                break
        assert events[-1]["type"] == "done"
    finally:
        stream.close()
    traces = [json.loads(record.getMessage().removeprefix("native_session "))
              for record in caplog.records if record.name == "oai_core.native_sessions"]
    assert traces[0]["stage"] == "T2"
    writes = [trace for trace in traces if trace["stage"] == "T5"]
    assert [trace["eventType"] for trace in writes] == ["generation", "delta", "done"]
    assert all(trace["clientMessageId"] == "timing-client" for trace in traces)
    assert all(trace["monotonicNs"] > 0 and trace["elapsedMs"] >= 0 for trace in traces)
    assert all(left["monotonicNs"] <= right["monotonicNs"] for left, right in zip(traces, traces[1:]))
    serialized = json.dumps(traces)
    assert private_text not in serialized
    assert TOKEN not in serialized
    assert "native-key-secret" not in serialized
    assert "Authorization" not in serialized


@pytest.mark.asyncio
@pytest.mark.parametrize("decision", ["once", "deny"])
async def test_signed_approval_card_decision_unblocks_host_once_without_leaking_identifiers(pipeline, decision):
    p = pipeline
    session_path = AGENT + "/sessions/" + p.conversation
    stream = await request(p.client, p.alice, "POST", session_path + "/messages", {"text": "approval"})
    assert stream.status == 200, await stream.text()
    try:
        generation = await next_event(stream)
        assert generation["type"] == "generation"
        assert generation["generationId"]
        while True:
            card = await next_event(stream)
            if card["type"] == "approval_requested":
                break
        approval = card["approval"]
        assert approval["conversationId"] == p.conversation
        assert approval["command"] == "printf 'controlled approval fixture'"
        assert approval["reason"] == "测试执行审批"
        assert approval["severity"] in {"info", "elevated", "critical"}
        assert {item["choice"] for item in approval["options"]} == {"once", "deny"}
        assert "sessionKey" not in json.dumps(card)
        assert "hostRequestId" not in json.dumps(card)
        assert "native-key-secret" not in json.dumps(card)
        assert "host-request-secret" not in json.dumps(card)
        decision_path = session_path + "/approvals/" + approval["approvalId"] + "/decisions"
        response = await request(p.client, p.bob, "POST", decision_path, {"decision": decision})
        assert response.status == 404, await response.text()
        assert not p.runtime.resolved
        response = await request(p.client, p.alice, "POST", decision_path,
                                 {"decision": decision, "sessionKey": "native-key-secret-native-alice"})
        assert response.status == 400, await response.text()
        assert not p.runtime.resolved
        response = await request(p.client, p.alice, "POST", decision_path, {"decision": "always"})
        assert response.status == 400, await response.text()
        assert not p.runtime.resolved
        response = await request(p.client, p.alice, "POST", decision_path, {"decision": decision}, "approval-once")
        assert response.status == 200, await response.text()
        receipt = await response.json()
        assert set(receipt["approval"]) == {"approvalId", "conversationId", "decision", "decidedAt"}
        assert receipt["approval"]["decision"] == decision
        assert isinstance(receipt["approval"]["decidedAt"], int)
        response = await request(p.client, p.alice, "POST", decision_path, {"decision": decision}, "approval-once")
        assert response.status == 200, await response.text()
        assert await response.json() == receipt
        conflict_decision = {"decision": "deny" if decision == "once" else "once"}
        response = await request(p.client, p.alice, "POST", decision_path, conflict_decision, "approval-conflict")
        assert response.status == 409, await response.text()
        conflict = await response.json()
        assert conflict["error"]["details"]["decision"] == decision
        assert set(conflict["error"]["details"]) == {"approvalId", "conversationId", "decision", "decidedAt"}
        response = await request(p.client, p.alice, "POST", decision_path, conflict_decision, "approval-conflict")
        assert response.status == 409, await response.text()
        assert await response.json() == conflict
        assert len(p.runtime.resolved) == 1
        events = []
        while True:
            event = await next_event(stream)
            events.append(event)
            if event["type"] in {"done", "error"}:
                break
        assert any(event["type"] == "approval_resolved" and event["approval"] == receipt["approval"] for event in events)
        assert events[-1]["type"] == "done"
        assert events[-1]["outcome"] == "completed"
        assert events[-1]["generationId"] == generation["generationId"]
        assert ("已允许" if decision == "once" else "已拒绝") in "".join(event.get("text", "") for event in events)
    finally:
        stream.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("boundary", ["phone", "bridge"])
async def test_observer_disconnect_keeps_one_accepted_host_turn_and_recovers_terminal(pipeline, monkeypatch, boundary):
    p = pipeline
    session_path = AGENT + "/sessions/" + p.conversation
    detached = asyncio.Event()
    unregister = p.backend.unregister_event_sink

    def observer_closed(sink):
        unregister(sink)
        detached.set()

    monkeypatch.setattr(p.backend, "unregister_event_sink", observer_closed)
    body = {"text": "disconnect", "clientMessageId": "disconnect-client"}
    if boundary == "phone":
        stream = await request(p.client, p.alice, "POST", session_path + "/messages", body)
        generation = await next_event(stream)
        assert (await next_event(stream))["type"] == "delta"
    else:
        stream = await p.host.post("/oai/session-adapter/v1/sessions/" + p.conversation + "/messages",
            json=body, headers={"Authorization": "Bearer " + TOKEN,
                               "X-OAI-Account": "native-alice", "X-OAI-Device": "phone"})
        generation = json.loads(await stream.content.readline())
        assert json.loads(await stream.content.readline())["type"] == "delta"
    assert generation["type"] == "generation"
    generation_id = generation["generationId"]
    task = p.runtime.turns[("native-alice", p.conversation, generation_id)]
    try:
        stream.connection.transport.abort()
        stream.close()
        # A further committed frame makes both HTTP observers encounter the
        # closed socket, rather than merely assuming the disconnect was seen.
        p.runtime.emit_text("native-alice", p.conversation, generation_id, "开始：观察连接关闭")
        await asyncio.wait_for(detached.wait(), 6)
        assert not task.done()
        assert p.runtime.interrupted == []
        response = await request(p.client, p.alice, "GET", session_path + "/generation?clientMessageId=disconnect-client")
        assert response.status == 200, await response.text()
        assert (await response.json())["generation"]["state"] == "running"
        p.runtime.observer_release.set()
        await asyncio.wait_for(p.runtime.host_finished.wait(), 3)
        response = await request(p.client, p.alice, "GET", session_path + "/generation?clientMessageId=disconnect-client")
        assert response.status == 200, await response.text()
        receipt = (await response.json())["generation"]
        assert receipt["state"] == "completed"
        assert receipt["generationId"] == generation_id
        assert len(p.runtime.turns) == 1
        account = p.backend.open_gateway_account("native-alice")
        try:
            assert account.store.database.execute(
                "SELECT COUNT(*) FROM messages WHERE conversation_id=? AND sender='user'",
                (p.conversation,)).fetchone()[0] == 1
        finally:
            account.close()
    finally:
        stream.close()
        p.runtime.observer_release.set()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
async def test_signed_generation_cancel_targets_current_native_task_and_closes_stream(pipeline):
    p = pipeline
    session_path = AGENT + "/sessions/" + p.conversation
    previous = await request(p.client, p.alice, "POST", session_path + "/messages", {"text": "ordinary"})
    assert previous.status == 200, await previous.text()
    try:
        old_generation = await next_event(previous)
        assert old_generation["type"] == "generation"
        while (await next_event(previous))["type"] != "done":
            pass
    finally:
        previous.close()
    stream = await request(p.client, p.alice, "POST", session_path + "/messages", {"text": "hold"})
    assert stream.status == 200, await stream.text()
    try:
        first = await next_event(stream)
        assert first["type"] == "generation"
        generation_id = first["generationId"]
        assert (await next_event(stream))["type"] == "delta"
        response = await request(p.client, p.alice, "POST", session_path + "/cancel",
                                 {"generationId": old_generation["generationId"]})
        assert response.status == 200, await response.text()
        assert (await response.json())["session"]["outcome"] == "already_completed"
        assert not p.runtime.interrupted
        assert not p.runtime.turns[("native-alice", p.conversation, generation_id)].done()
        response = await request(p.client, p.alice, "POST", session_path + "/cancel", {"generationId": "gen_stale"})
        if response.status == 200:
            assert (await response.json())["session"]["outcome"] == "outcome_unknown"
        else:
            assert response.status in {400, 404, 409}, await response.text()
        assert not p.runtime.interrupted
        assert not p.runtime.turns[("native-alice", p.conversation, generation_id)].done()
        response = await request(p.client, p.alice, "POST", session_path + "/cancel",
                                 {"generationId": generation_id}, "cancel-once")
        assert response.status == 200, await response.text()
        receipt = await response.json()
        assert receipt["session"]["outcome"] == "cancelled"
        assert receipt["session"]["generationId"] == generation_id
        response = await request(p.client, p.alice, "POST", session_path + "/cancel",
                                 {"generationId": generation_id}, "cancel-once")
        assert response.status == 200, await response.text()
        assert await response.json() == receipt
        assert p.runtime.interrupted == [("native-alice", p.conversation, generation_id)]
        assert p.runtime.turns[("native-alice", p.conversation, generation_id)].cancelled()
        terminal = await next_event(stream)
        assert terminal["type"] == "done"
        assert terminal["outcome"] == "cancelled"
        assert terminal["generationId"] == generation_id
    finally:
        stream.close()


@pytest.mark.asyncio
async def test_regular_native_deltas_still_finish_without_an_approval(pipeline):
    p = pipeline
    path = AGENT + "/sessions/" + p.conversation + "/messages"
    stream = await request(p.client, p.alice, "POST", path, {"text": "ordinary"})
    assert stream.status == 200, await stream.text()
    try:
        events = []
        while True:
            event = await next_event(stream)
            events.append(event)
            if event["type"] in {"done", "error"}:
                break
        assert events[0]["type"] == "generation"
        assert "".join(event.get("text", "") for event in events if event["type"] == "delta") == "开始：完成"
        assert events[-1]["type"] == "done"
        assert events[-1]["outcome"] == "completed"
        assert events[-1]["generationId"] == events[0]["generationId"]
        assert not p.runtime.resolved
    finally:
        stream.close()


@pytest.mark.asyncio
async def test_native_approval_expiry_is_persisted_and_emitted_at_its_deadline(pipeline):
    p = pipeline
    p.runtime.approval_timeout_seconds = 1
    p.runtime.resolver_return_gate = threading.Event()

    async def release_resolver_after_host_completion():
        await p.runtime.host_finished.wait()
        # A host can resume before its resolver returns. Hold that boundary
        # beyond the native stream's polling interval to make the race repeatable.
        await asyncio.sleep(.35)
        p.runtime.resolver_return_gate.set()

    release = asyncio.create_task(release_resolver_after_host_completion())
    session_path = AGENT + "/sessions/" + p.conversation
    stream = await request(p.client, p.alice, "POST", session_path + "/messages", {"text": "approval"})
    assert stream.status == 200, await stream.text()
    try:
        events = []
        while True:
            event = await next_event(stream)
            events.append(event)
            if event["type"] in {"done", "error"}:
                break
        card = next(event["approval"] for event in events if event["type"] == "approval_requested")
        resolved = next((event["approval"] for event in events if event["type"] == "approval_resolved"), None)
        assert resolved is not None, events
        assert card["timeoutSeconds"] == 1
        assert resolved["decision"] == "timeout"
        assert resolved["decidedAt"] == card["expiresAt"]
        assert events[-1]["type"] == "done"
        assert len(p.runtime.resolved) == 1
        assert p.runtime.resolved[0][1] == "deny"
        account = p.backend.open_gateway_account("native-alice")
        try:
            persisted = account.approvals.lookup(card["approvalId"])
            assert persisted["decision"] == "timeout"
            assert persisted["decidedAt"] == card["expiresAt"]
        finally:
            account.close()
        decision_path = session_path + "/approvals/" + card["approvalId"] + "/decisions"
        response = await request(p.client, p.alice, "POST", decision_path, {"decision": "once"})
        assert response.status == 409, await response.text()
        assert len(p.runtime.resolved) == 1
    finally:
        p.runtime.resolver_return_gate.set()
        release.cancel()
        await asyncio.gather(release, return_exceptions=True)
        stream.close()
