"""Public tool observers retain exact accepted identity and native revisions."""
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace

import pytest

from oai_core.server import create_core
from oai_hermes_adapter.context import trusted_turn
from oai_hermes_adapter.host_history import HermesHistoryPort
from oai_hermes_adapter.tool_events import NativeToolObserver, NOTICE_CODE, post_tool_call, pre_tool_call


@pytest.fixture
def native_tools(tmp_path):
    core = create_core({"storageRoot": str(tmp_path / "accounts"), "masterKeyFile": str(tmp_path / "master"),
        "contractRoot": str(Path(__file__).resolve().parents[2] / "gateway-contract")})
    with closing(core.open_gateway_account("synthetic")) as account:
        account.credentials.create_password("synthetic-password")
        conversation = account.conversations.create("tools", "tools", "create")["conversationId"]
        account.agent_sessions.record(conversation, "create")
        account.agent_sessions.attach(conversation, "native-session", "native-key")
        accepted = account.conversations.accept_message(conversation, "client", "private synthetic prompt", [], "phone", "request", "correlation")
        account.conversations.workflow.claim(accepted["messageId"])
    rows = [{"id": 1, "role": "user", "platform_message_id": "client", "content": "private synthetic prompt", "timestamp": 1},
        {"id": 2, "role": "assistant", "content": "", "timestamp": 2, "tool_calls": [
            {"id": "native-call", "function": {"name": "read", "arguments": '{"path":"private synthetic path"}'}}]}]
    metadata = {}
    database = SimpleNamespace(get_session=lambda session: {"id": session}, get_messages=lambda *args, **kw: rows,
        get_meta=metadata.get, set_meta=lambda key, value: metadata.update({key: value}),
        list_meta_prefix=lambda prefix: [(key, value) for key, value in metadata.items() if key.startswith(prefix)])
    history = HermesHistoryPort(core, SimpleNamespace(_db_for_key=lambda key: database))
    observer = NativeToolObserver(core, history)
    context = {"core": core, "accountId": "synthetic", "conversationId": conversation, "clientMessageId": "client",
        "generationId": accepted["generationId"], "sessionId": "native-session", "executionObserver": observer}
    arguments = {"path": "private synthetic path"}
    event = {"tool_name": "read", "args": arguments, "session_id": "native-session",
        "tool_call_id": "native-call", "turn_id": "native-turn"}
    return core, history, observer, context, event, rows, accepted


def test_live_hooks_and_history_use_the_same_persistent_step_revision(native_tools):
    core, history, observer, context, event, rows, accepted = native_tools
    token = trusted_turn.set(context)
    try:
        assert pre_tool_call(**event) is None
        running = observer.snapshot("synthetic", context["conversationId"], accepted["generationId"])
        assert running["executionSteps"][0]["status"] == "running"
        assert pre_tool_call(**event) is None
        assert observer.snapshot("synthetic", context["conversationId"], accepted["generationId"]) == running
        assert post_tool_call(**event, status="ok", result="private synthetic result") is None
        completed = observer.snapshot("synthetic", context["conversationId"], accepted["generationId"])
        assert completed["executionSteps"][0]["status"] == "completed"
        assert completed["executionSteps"][0]["revision"] > running["executionSteps"][0]["revision"]
        assert pre_tool_call(**event) is None
        assert observer.snapshot("synthetic", context["conversationId"], accepted["generationId"]) == completed
        projected = next(row for row in history.read("synthetic", context["conversationId"]) if row.get("executionSteps"))
        assert projected["executionSteps"] == completed["executionSteps"]
        with closing(core.open_gateway_account("synthetic")) as account:
            notices = [item for item in account.events.read_after(None) if item["eventType"] == "gateway.notice"]
        assert len(notices) == 2
        assert all(item["payload"] == {"noticeCode": NOTICE_CODE} and item["correlationId"] == accepted["messageId"] for item in notices)
        assert completed["executionSteps"][0]["eventId"] == notices[-1]["eventId"]
        assert event["args"] == {"path": "private synthetic path"}
    finally:
        trusted_turn.reset(token)


@pytest.mark.parametrize("changed", [{"session_id": "other-session"}, {"tool_call_id": "not-a-native-call"}])
def test_hooks_without_exact_native_identity_do_not_claim_the_active_turn(native_tools, changed):
    _, _, observer, context, event, _, accepted = native_tools
    assert pre_tool_call(**event) is None  # no trusted dispatch context
    token = trusted_turn.set(context)
    try:
        pre_tool_call(**{**event, **changed})
    finally:
        trusted_turn.reset(token)
    assert observer.snapshot("synthetic", context["conversationId"], accepted["generationId"]) is None


def test_wrong_post_turn_and_late_terminal_hooks_cannot_reopen_steps(native_tools):
    core, _, observer, context, event, _, accepted = native_tools
    token = trusted_turn.set(context)
    try:
        pre_tool_call(**event)
        post_tool_call(**{**event, "turn_id": "other-turn"}, status="ok", result="wrong")
        running = observer.snapshot("synthetic", context["conversationId"], accepted["generationId"])
        assert running["executionSteps"][0]["status"] == "running"
        observer.close_generation("synthetic", context["conversationId"], accepted["generationId"], accepted["messageId"])
        settled = observer.snapshot("synthetic", context["conversationId"], accepted["generationId"])
        assert settled["executionSteps"][0]["status"] == "unknown"
        with closing(core.open_gateway_account("synthetic")) as account:
            account.conversations.workflow.settle(accepted["messageId"], "completed")
        post_tool_call(**event, status="ok", result="late")
        assert observer.snapshot("synthetic", context["conversationId"], accepted["generationId"]) == settled
    finally:
        trusted_turn.reset(token)


def test_persisted_native_result_recovers_a_missed_post_hook(native_tools):
    _, history, observer, context, event, rows, accepted = native_tools
    token = trusted_turn.set(context)
    try:
        pre_tool_call(**event)
    finally:
        trusted_turn.reset(token)
    first = observer.snapshot("synthetic", context["conversationId"], accepted["generationId"])
    rows.append({"id": 3, "role": "tool", "tool_call_id": "native-call", "content": "native durable result", "timestamp": 3})
    projected = next(row for row in history.read("synthetic", context["conversationId"]) if row.get("executionSteps"))
    assert projected["executionSteps"][0]["status"] == "completed"
    assert projected["executionSteps"][0]["revision"] > first["executionSteps"][0]["revision"]
    assert observer.snapshot("synthetic", context["conversationId"], accepted["generationId"])["executionSteps"] == projected["executionSteps"]


def test_history_initialized_steps_keep_native_order_after_later_pre_hook(native_tools):
    _, history, observer, context, event, rows, accepted = native_tools
    rows[1]["tool_calls"].append({"id": "native-second-call", "function": {"name": "read", "arguments": "{}"}})
    history.read("synthetic", context["conversationId"])
    before = observer.snapshot("synthetic", context["conversationId"], accepted["generationId"])
    assert [step["stepId"] for step in before["executionSteps"]] == ["native-call", "native-second-call"]
    token = trusted_turn.set(context)
    try:
        pre_tool_call(**event)
    finally:
        trusted_turn.reset(token)
    after = observer.snapshot("synthetic", context["conversationId"], accepted["generationId"])
    assert [step["stepId"] for step in after["executionSteps"]] == ["native-call", "native-second-call"]
