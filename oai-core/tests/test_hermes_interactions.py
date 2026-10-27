"""Native approval cards and generation identities over the real session seam."""
import asyncio
import json
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace

import pytest
from aiohttp.test_utils import TestClient, TestServer

from oai_core.auth import Principal
from oai_core.server import create_core
from oai_core.sessions import AdapterError
from oai_hermes_adapter.bridge import UnsupportedApprovalMixin, create_hermes_bridge_app
from oai_hermes_adapter.sessions import HermesSessionAdapter


@pytest.fixture
def interaction_core(tmp_path):
    core = create_core({"storageRoot": str(tmp_path / "accounts"),
                        "masterKeyFile": str(tmp_path / "master"),
                        "contractRoot": str(Path(__file__).resolve().parents[2] / "gateway-contract")})
    for owner in ("alice", "bob"):
        with closing(core.open_gateway_account(owner)) as account:
            account.credentials.create_password("test-interaction-password")
            created = account.conversations.create(owner, owner, owner)
            account.agent_sessions.record(created["conversationId"], "conversation-create")
            account.agent_sessions.attach(created["conversationId"], "native_" + owner, "key_" + owner)
    core.history_reader = lambda account, conversation: []
    return core


class PromptRuntime(UnsupportedApprovalMixin):
    _unsupported_approval_result = staticmethod(lambda: SimpleNamespace(success=True))
    _approval_result = staticmethod(lambda approval_id: SimpleNamespace(success=True, message_id=approval_id))

    def __init__(self, core):
        self.services = SimpleNamespace(core=core)
        self._loop = asyncio.get_running_loop()
        self._session_store = object()
        self._message_handler = lambda: None
        self._conv_to_account = {}
        self.prompted = asyncio.Event()
        # 新版 Hermes 的 metadata 只有 thread_id/reply_to_message_id：
        # 关掉 request_id 以覆盖"从宿主队列里取回"的兼容路径。
        self.include_request_id = True
        self.release = asyncio.Event()
        self.tasks = set()
        self.timeout_seconds = 30
        self.steered = []
        self.approval_controls = []

    def _track_background_task(self, task, label):
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    def native_steering_available(self):
        return True

    async def steer_native_turn(self, account_id, conversation_id, generation_id, text, *, client_message_id=None):
        self.steered.append((account_id, conversation_id, generation_id, text, client_message_id))
        return {"generationId": generation_id, "outcome": "accepted"}

    async def dispatch_approval_control(self, account_id, conversation_id, text, client_message_id):
        self.approval_controls.append((account_id, conversation_id, text, client_message_id))

    async def _notify_agent_inbound(self, path, body, response, owner):
        accepted = response["data"]
        conversation = accepted["conversationId"]
        self._conv_to_account[conversation] = owner
        with closing(self.services.core.open_gateway_account(owner)) as account:
            account.conversations.claim_dispatch(json.loads(body)["clientMessageId"])
        result = await self._send_exec_approval_prompt(SimpleNamespace(
            chat_id=conversation, session_key="key_" + owner, command="sensitive-test-command",
            description="需要用户确认", smart_denied=False,
            actions=[("允许一次", "once", "primary"), ("拒绝", "deny", "danger")],
            metadata={"account_id": owner, "timeout_seconds": self.timeout_seconds,
                      **({"request_id": "private-host-request"} if self.include_request_id else {})}))
        assert result.success
        self.prompted.set()
        await self.release.wait()
        with closing(self.services.core.open_gateway_account(owner)) as account:
            account.conversations.workflow.settle(accepted["messageId"], "completed")


@pytest.mark.asyncio
async def test_native_prompt_waits_for_opaque_approval_decision_and_streams_both_edges(interaction_core, monkeypatch):
    calls = []
    monkeypatch.setattr("oai_hermes_adapter.approvals.resolve_host_approval",
                        lambda session, choice, request_id=None: calls.append((session, choice, request_id)) or 1)
    monkeypatch.setattr("oai_hermes_adapter.approvals.host_approval_available", lambda: True, raising=False)
    runtime = PromptRuntime(interaction_core)
    adapter = HermesSessionAdapter(interaction_core, runtime)
    principal = Principal("alice", device_id="phone", pairing_generation=7, grant_revision=9)
    conversation = (await adapter.list_sessions(principal))[0]["id"]
    received = []

    async def consume():
        async for event in adapter.send(principal, conversation, "run sensitive command"):
            received.append(event)

    pending = asyncio.create_task(consume())
    try:
        prompted = asyncio.create_task(runtime.prompted.wait())
        done, _ = await asyncio.wait({prompted, pending}, timeout=3, return_when=asyncio.FIRST_COMPLETED)
        if pending in done:
            await pending
        assert prompted in done, "the native approval prompt was not reached"
        await asyncio.sleep(.01)
        assert calls == [], "rendering an approval must not preemptively deny the native wait"
        assert received[0]["type"] == "generation"
        requested = next(event for event in received if event["type"] == "approval_requested")
        card = requested["approval"]
        assert card["command"] == "sensitive-test-command"
        assert [option["choice"] for option in card["options"]] == ["once", "deny"]
        assert "key_alice" not in str(received)
        assert "private-host-request" not in str(received)
        decision = await adapter.decide_approval(principal, conversation, card["approvalId"], "once")
        assert decision["approval"]["decision"] == "once"
        assert calls == [("key_alice", "once", "private-host-request")]
        assert await adapter.decide_approval(principal, conversation, card["approvalId"], "once") == decision
        assert len(calls) == 1
        with pytest.raises(AdapterError) as error:
            await adapter.decide_approval(principal, conversation, card["approvalId"], "deny")
        assert error.value.code == "APPROVAL_ALREADY_RESOLVED"
        runtime.release.set()
        await asyncio.wait_for(pending, 3)
        assert any(event["type"] == "approval_resolved" for event in received)
        assert received[-1]["type"] == "done"
        assert received[-1]["outcome"] == "completed"
        assert received[-1]["generationId"] == received[0]["generationId"]
    finally:
        runtime.release.set()
        pending.cancel()
        for task in tuple(runtime.tasks):
            task.cancel()
        await asyncio.gather(pending, *tuple(runtime.tasks), return_exceptions=True)


def test_pending_host_request_id_uses_the_host_queue(monkeypatch):
    import sys
    import types
    from oai_hermes_adapter import approvals as approval_module

    fake = types.ModuleType("tools.approval")
    fake.list_gateway_approvals = lambda session: [
        {"request_id": "req-old", "command": "old"},
        {"request_id": "req-match", "command": "sensitive-test-command"},
    ]
    monkeypatch.setitem(sys.modules, "tools.approval", fake)
    assert approval_module.pending_host_request_id("key_alice", "sensitive-test-command") == "req-match"
    # Two different pending commands cannot be disambiguated from a missing
    # request_id. Guessing the oldest one would approve the wrong command.
    assert approval_module.pending_host_request_id("key_alice", "unknown") is None
    assert approval_module.pending_host_request_id("") is None


def test_pending_host_request_id_rejects_duplicate_command_matches(monkeypatch):
    import sys
    import types
    from oai_hermes_adapter import approvals as approval_module

    fake = types.ModuleType("tools.approval")
    fake.list_gateway_approvals = lambda session: [
        {"request_id": "req-first", "command": "same command"},
        {"request_id": "req-second", "command": "same command"},
    ]
    monkeypatch.setitem(sys.modules, "tools.approval", fake)

    assert approval_module.pending_host_request_id("key_alice", "same command") is None


def test_pending_host_request_id_accepts_a_single_unmatched_entry(monkeypatch):
    import sys
    import types
    from oai_hermes_adapter import approvals as approval_module

    fake = types.ModuleType("tools.approval")
    fake.list_gateway_approvals = lambda session: [
        {"request_id": "req-only", "command": "redacted command"},
    ]
    monkeypatch.setitem(sys.modules, "tools.approval", fake)

    assert approval_module.pending_host_request_id("key_alice", "different display text") == "req-only"


@pytest.mark.asyncio
async def test_exec_approval_card_works_when_host_metadata_lacks_request_id(interaction_core, monkeypatch):
    calls = []
    monkeypatch.setattr("oai_hermes_adapter.approvals.resolve_host_approval",
                        lambda session, choice, request_id=None: calls.append((session, choice, request_id)) or 1)
    monkeypatch.setattr("oai_hermes_adapter.approvals.host_approval_available", lambda: True, raising=False)
    monkeypatch.setattr("oai_hermes_adapter.approvals.pending_host_request_id",
                        lambda session, command="": "queue-host-request")
    runtime = PromptRuntime(interaction_core)
    runtime.include_request_id = False
    adapter = HermesSessionAdapter(interaction_core, runtime)
    principal = Principal("alice", device_id="phone", pairing_generation=7, grant_revision=9)
    conversation = (await adapter.list_sessions(principal))[0]["id"]
    received = []

    async def consume():
        async for event in adapter.send(principal, conversation, "run sensitive command"):
            received.append(event)

    pending = asyncio.create_task(consume())
    try:
        await asyncio.wait_for(runtime.prompted.wait(), 3)
        await asyncio.sleep(.01)
        card = next(event["approval"] for event in received if event["type"] == "approval_requested")
        assert card["command"] == "sensitive-test-command"
        decision = await adapter.decide_approval(principal, conversation, card["approvalId"], "once")
        assert decision["approval"]["decision"] == "once"
        assert calls == [("key_alice", "once", "queue-host-request")]
    finally:
        runtime.release.set()
        pending.cancel()
        for task in tuple(runtime.tasks):
            task.cancel()
        await asyncio.gather(pending, *tuple(runtime.tasks), return_exceptions=True)


@pytest.mark.asyncio
async def test_steering_uses_the_exact_running_generation_and_does_not_dispatch_a_second_turn(
    interaction_core,
    monkeypatch,
):
    monkeypatch.setattr("oai_hermes_adapter.approvals.host_approval_available", lambda: True, raising=False)
    runtime = PromptRuntime(interaction_core)
    adapter = HermesSessionAdapter(interaction_core, runtime)
    principal = Principal("alice", device_id="phone", pairing_generation=7, grant_revision=9)
    conversation = (await adapter.list_sessions(principal))[0]["id"]
    received = []

    async def consume():
        async for event in adapter.send(principal, conversation, "run sensitive command"):
            received.append(event)

    pending = asyncio.create_task(consume())
    try:
        await asyncio.wait_for(runtime.prompted.wait(), 3)
        generation_id = received[0]["generationId"]
        assert await adapter.current_generation(principal, conversation) == {
            "generationId": generation_id,
            "conversationId": conversation,
            "state": "running",
        }
        result = await adapter.steer(
            principal,
            conversation,
            "check the failure path before continuing",
            generation_id,
            "cm_steer",
        )
        assert result == {"generationId": generation_id, "outcome": "accepted"}
        assert runtime.steered == [
            ("alice", conversation, generation_id, "check the failure path before continuing", "cm_steer"),
        ]
        with pytest.raises(AdapterError) as error:
            await adapter.steer(principal, conversation, "wrong turn", "gen_other", "cm_wrong")
        assert error.value.code == "STEER_UNAVAILABLE"
    finally:
        runtime.release.set()
        pending.cancel()
        for task in tuple(runtime.tasks):
            task.cancel()
        await asyncio.gather(pending, *tuple(runtime.tasks), return_exceptions=True)


@pytest.mark.asyncio
async def test_approval_text_reply_bypasses_the_running_generation_lock(interaction_core, monkeypatch):
    monkeypatch.setattr("oai_hermes_adapter.approvals.host_approval_available", lambda: True, raising=False)
    runtime = PromptRuntime(interaction_core)
    adapter = HermesSessionAdapter(interaction_core, runtime)
    principal = Principal("alice", device_id="phone", pairing_generation=7, grant_revision=9)
    conversation = (await adapter.list_sessions(principal))[0]["id"]
    first_events = []

    async def consume_first():
        async for event in adapter.send(principal, conversation, "run sensitive command"):
            first_events.append(event)

    pending = asyncio.create_task(consume_first())
    try:
        await asyncio.wait_for(runtime.prompted.wait(), 3)
        with closing(interaction_core.open_gateway_account("alice")) as account:
            before = account.store.database.execute(
                "SELECT COUNT(*) FROM messages WHERE conversation_id=?", (conversation,),
            ).fetchone()[0]

        control_events = [
            event async for event in adapter.send(
                principal,
                conversation,
                "/approve once",
                client_message_id="cm_approve_text",
            )
        ]

        assert control_events == [{
            "type": "done",
            "outcome": "completed",
            "text": "",
            "clientMessageId": "cm_approve_text",
        }]
        assert runtime.approval_controls == [
            ("alice", conversation, "/approve once", "cm_approve_text"),
        ]
        with closing(interaction_core.open_gateway_account("alice")) as account:
            after = account.store.database.execute(
                "SELECT COUNT(*) FROM messages WHERE conversation_id=?", (conversation,),
            ).fetchone()[0]
        assert after == before, "an approval reply must not create another user turn"
    finally:
        runtime.release.set()
        pending.cancel()
        for task in tuple(runtime.tasks):
            task.cancel()
        await asyncio.gather(pending, *tuple(runtime.tasks), return_exceptions=True)


@pytest.mark.asyncio
async def test_approval_endpoint_checks_account_session_and_option_before_resolving(interaction_core, monkeypatch):
    calls = []
    monkeypatch.setattr("oai_hermes_adapter.approvals.resolve_host_approval",
                        lambda session, choice, request_id=None: calls.append(choice) or 1)
    monkeypatch.setattr("oai_hermes_adapter.approvals.host_approval_available", lambda: True, raising=False)
    runtime = PromptRuntime(interaction_core)
    adapter = HermesSessionAdapter(interaction_core, runtime)
    principal = Principal("alice", device_id="phone")
    conversation = (await adapter.list_sessions(principal))[0]["id"]
    received = []
    async def consume():
        async for event in adapter.send(principal, conversation, "approval"):
            received.append(event)
    pending = asyncio.create_task(consume())
    token = "interaction-test-token-at-least-thirty-two-characters"
    try:
        await asyncio.wait_for(runtime.prompted.wait(), 3)
        await asyncio.sleep(.01)
        card = next(event["approval"] for event in received if event["type"] == "approval_requested")
        path = f"/oai/session-adapter/v1/sessions/{conversation}/approvals/{card['approvalId']}/decisions"
        async with TestClient(TestServer(create_hermes_bridge_app(adapter, token, ["alice", "bob"]))) as client:
            headers = {"Authorization": "Bearer " + token, "X-OAI-Account": "bob", "X-OAI-Device": "phone"}
            response = await client.post(path, json={"decision": "once"}, headers=headers)
            assert response.status == 404
            headers["X-OAI-Account"] = "alice"
            response = await client.post(path, json={"decision": "always"}, headers=headers)
            assert response.status == 400
            response = await client.post(path, json={"decision": "once", "sessionKey": "forged"}, headers=headers)
            assert response.status == 400
            assert calls == []
            response = await client.post(path, json={"decision": "deny"}, headers=headers)
            assert response.status == 200
            assert (await response.json())["approval"]["decision"] == "deny"
            assert calls == ["deny"]
    finally:
        runtime.release.set()
        pending.cancel()
        for task in tuple(runtime.tasks):
            task.cancel()
        await asyncio.gather(pending, *tuple(runtime.tasks), return_exceptions=True)


@pytest.mark.asyncio
async def test_missing_or_old_cancel_target_cannot_stop_a_new_generation(interaction_core):
    calls = []
    interaction_core.generation_canceller = lambda account, conversation, generation: calls.append(generation) or "CANCELLED"
    runtime = PromptRuntime(interaction_core)
    adapter = HermesSessionAdapter(interaction_core, runtime)
    principal = Principal("alice", device_id="phone", pairing_generation=1)
    conversation = (await adapter.list_sessions(principal))[0]["id"]
    with closing(interaction_core.open_gateway_account("alice")) as account:
        first = account.conversations.accept_message(conversation, "old", "old", [], "phone", "old", "old")
        account.conversations.workflow.settle(first["messageId"], "completed")
        second = account.conversations.accept_message(conversation, "new", "new", [], "phone", "new", "new")
    missing = await adapter.manage(principal, conversation, "cancel")
    assert missing["outcome"] == "outcome_unknown"
    old = await adapter.manage(principal, conversation, "cancel", {"generationId": first["generationId"]})
    assert old["outcome"] == "already_completed"
    assert calls == []
    with closing(interaction_core.open_gateway_account("alice")) as account:
        assert account.conversations.workflow.for_message(second["messageId"])["state"] == "queued"


@pytest.mark.asyncio
@pytest.mark.parametrize("expired", [False, True])
async def test_native_approval_records_withdrawn_or_authoritative_timeout(interaction_core, monkeypatch, expired):
    calls = []
    monkeypatch.setattr("oai_hermes_adapter.approvals.resolve_host_approval",
                        lambda session, choice, request_id=None: calls.append(choice) or 0)
    monkeypatch.setattr("oai_hermes_adapter.approvals.host_approval_available", lambda: True)
    runtime = PromptRuntime(interaction_core)
    runtime.timeout_seconds = 1 if expired else 30
    adapter = HermesSessionAdapter(interaction_core, runtime)
    principal = Principal("alice", device_id="phone")
    conversation = (await adapter.list_sessions(principal))[0]["id"]
    received = []
    async def consume():
        async for event in adapter.send(principal, conversation, "approval"):
            received.append(event)
    pending = asyncio.create_task(consume())
    try:
        await asyncio.wait_for(runtime.prompted.wait(), 3)
        await asyncio.sleep(.01)
        card = next(event["approval"] for event in received if event["type"] == "approval_requested")
        if expired:
            await asyncio.sleep(1.1)
        else:
            # A reconstructed runtime has no native wait after a host restart.
            replacement = PromptRuntime(interaction_core)
            restarted = HermesSessionAdapter(interaction_core, replacement)
            with pytest.raises(AdapterError) as error:
                await restarted.decide_approval(principal, conversation, card["approvalId"], "once")
            assert error.value.code == "APPROVAL_EXPIRED"
        with closing(interaction_core.open_gateway_account("alice")) as account:
            terminal = account.approvals.lookup(card["approvalId"])
        assert terminal["decision"] == ("timeout" if expired else "withdrawn")
        if expired:
            assert terminal["decidedAt"] == card["expiresAt"]
        await asyncio.sleep(.01)
        resolved = next(event["approval"] for event in received if event["type"] == "approval_resolved")
        assert resolved["decision"] == terminal["decision"]
        with pytest.raises(AdapterError):
            await adapter.decide_approval(principal, conversation, card["approvalId"], "once")
        assert calls == ["deny" if expired else "once"]
    finally:
        runtime.release.set()
        pending.cancel()
        for task in tuple(runtime.tasks):
            task.cancel()
        await asyncio.gather(pending, *tuple(runtime.tasks), return_exceptions=True)


@pytest.mark.asyncio
async def test_simultaneous_native_approval_decisions_resolve_exactly_one_host_wait(interaction_core, monkeypatch):
    import time
    calls = []
    def resolve(session, choice, request_id=None):
        calls.append(choice)
        time.sleep(.03)
        return 1
    monkeypatch.setattr("oai_hermes_adapter.approvals.resolve_host_approval", resolve)
    monkeypatch.setattr("oai_hermes_adapter.approvals.host_approval_available", lambda: True)
    runtime = PromptRuntime(interaction_core)
    adapter = HermesSessionAdapter(interaction_core, runtime)
    principal = Principal("alice", device_id="phone")
    conversation = (await adapter.list_sessions(principal))[0]["id"]
    received = []
    async def consume():
        async for event in adapter.send(principal, conversation, "approval"):
            received.append(event)
    pending = asyncio.create_task(consume())
    try:
        await asyncio.wait_for(runtime.prompted.wait(), 3)
        await asyncio.sleep(.01)
        card = next(event["approval"] for event in received if event["type"] == "approval_requested")
        results = await asyncio.gather(*(adapter.decide_approval(principal, conversation, card["approvalId"], choice)
                                        for choice in ("once", "deny")), return_exceptions=True)
        assert len(calls) == 1
        assert len([result for result in results if isinstance(result, dict)]) == 1
        errors = [result for result in results if isinstance(result, AdapterError)]
        assert len(errors) == 1 and errors[0].code == "APPROVAL_ALREADY_RESOLVED"
    finally:
        runtime.release.set()
        pending.cancel()
        for task in tuple(runtime.tasks):
            task.cancel()
        await asyncio.gather(pending, *tuple(runtime.tasks), return_exceptions=True)
