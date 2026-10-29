"""Public tool observers retain exact accepted identity and native revisions."""
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace

import pytest

from oai_core.server import create_core
from oai_hermes_adapter.context import trusted_turn
from oai_hermes_adapter.host_history import HermesHistoryPort
from oai_hermes_adapter.tool_events import NativeToolObserver, NOTICE_CODE, execution_key, post_tool_call, pre_tool_call


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
        assert completed["executionSteps"][0]["eventId"] == "native-session:2"
        assert completed["executionSteps"][0]["eventId"] != notices[-1]["eventId"]
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


@pytest.fixture
def owned_execution_history(tmp_path, monkeypatch):
    import json
    import sqlite3
    core = create_core({"storageRoot": str(tmp_path / "accounts"), "masterKeyFile": str(tmp_path / "master"),
        "contractRoot": str(Path(__file__).resolve().parents[2] / "gateway-contract")})
    connection = sqlite3.connect(":memory:")
    connection.execute("CREATE TABLE state_meta(key TEXT PRIMARY KEY,value TEXT)")
    connection.execute("CREATE TABLE messages(id INTEGER,session_id TEXT,role TEXT,display_kind TEXT,display_metadata TEXT)")
    connection.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY,message_count INTEGER,tool_call_count INTEGER)")
    sessions, identities = {}, {}
    def delete_sessions(values, **kwargs):
        for value in values:
            sessions.pop(value, None)
    class NativeDatabase(SimpleNamespace):
        __hash__ = object.__hash__
    database = NativeDatabase(get_session=sessions.get, get_messages=lambda *args, **kwargs: [],
        get_meta=lambda key: next((row[0] for row in connection.execute("SELECT value FROM state_meta WHERE key=?", (key,))), None),
        set_meta=lambda key, value: connection.execute("INSERT OR REPLACE INTO state_meta VALUES (?,?)", (key, value)),
        list_meta_prefix=lambda prefix: list(connection.execute("SELECT key,value FROM state_meta WHERE substr(key,1,?)=?", (len(prefix), prefix))),
        _execute_write=lambda callback: callback(connection), _active_transcript_counts=lambda conn, session: (0, 0),
        set_session_archived=lambda *args: True, delete_sessions=delete_sessions)
    for owner, label in (("one", "delete"), ("one", "keep"), ("two", "other")):
        with closing(core.open_gateway_account(owner)) as account:
            if not account.credentials.has_password():
                account.credentials.create_password("synthetic-password")
            conversation = account.conversations.create(label, label, label)["conversationId"]
            session_id = "native-" + label
            sessions[session_id] = {"id": session_id}
            connection.execute("INSERT INTO sessions VALUES (?,0,0)", (session_id,))
            account.agent_sessions.record(conversation, label)
            account.agent_sessions.attach(conversation, session_id, session_id)
            identities[label] = (owner, conversation, "generation-" + label)
    history = HermesHistoryPort(core, SimpleNamespace(_db_for_key=lambda key: database))
    monkeypatch.setattr("oai_hermes_adapter.host_history.HistoryMedia.retire_account_media", lambda *args: None)
    keys = {}
    for label, (owner, conversation, generation) in identities.items():
        key = execution_key(history.storage_owner, owner, conversation, generation)
        database.set_meta(key, json.dumps({"storageOwner": history.storage_owner, "accountId": owner,
            "conversationId": conversation, "generationId": generation, "revision": 1,
            "steps": [{"stepId": "call", "arguments": "private synthetic parameter", "output": "private synthetic result"}]}))
        keys[label] = key
    return history, database, identities, keys


def test_delete_native_conversation_retires_only_its_owned_execution_content(owned_execution_history):
    history, database, identities, keys = owned_execution_history
    owner, conversation, _ = identities["delete"]
    history.manage(owner, conversation, "delete")
    assert database.get_meta(keys["delete"]) is None
    assert database.get_meta(keys["keep"]) is not None
    assert database.get_meta(keys["other"]) is not None


def test_remove_account_retires_execution_content_without_touching_other_accounts(owned_execution_history):
    history, database, _, keys = owned_execution_history
    history.cleanup_account("one")
    assert database.get_meta(keys["delete"]) is None
    assert database.get_meta(keys["keep"]) is None
    assert database.get_meta(keys["other"]) is not None


def test_failed_notice_commit_keeps_native_source_identity_and_does_not_publish_uncommitted_event(native_tools):
    core, history, observer, context, event, rows, accepted = native_tools
    from oai_core.gateway.core import TransactionOutcomeUnknown
    real_open = core.open_gateway_account
    injected = real_open("synthetic")
    injected.store.fail_next_commit = True
    core.open_gateway_account = lambda owner: injected if owner == "synthetic" else real_open(owner)
    delivered = []
    core.register_event_sink(lambda owner, value: delivered.append(value))
    try:
        with pytest.raises(TransactionOutcomeUnknown):
            observer.record(context, **event)
    finally:
        core.open_gateway_account = real_open
        injected.close()
    saved = observer.snapshot("synthetic", context["conversationId"], accepted["generationId"])
    assert saved["executionSteps"][0]["eventId"] == "native-session:2"
    assert delivered == []
    with closing(real_open("synthetic")) as account:
        notices = [item for item in account.events.read_after(None) if item["eventType"] == "gateway.notice"]
    assert notices == []
    rows.append({"id": 3, "role": "tool", "tool_call_id": "native-call", "content": "durable result", "timestamp": 3})
    projected = next(row for row in history.read("synthetic", context["conversationId"]) if row.get("executionSteps"))
    assert projected["executionSteps"][0]["status"] == "completed"
    assert len(rows) == 3  # Recovery observes stored facts; it never executes a tool.
