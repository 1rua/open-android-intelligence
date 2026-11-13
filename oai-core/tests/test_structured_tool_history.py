"""Tool attribution uses only native identities in synthetic history rows."""
import pytest

from oai_hermes_adapter.tool_history import project_execution


def test_tool_result_keeps_native_step_identity_and_generation():
    rows = [
        {"id": 1, "role": "user", "platform_message_id": "client-one"},
        {"id": 2, "role": "assistant", "tool_calls": [{"id": "call-one", "function": {"name": "read_file", "arguments": '{"path":"demo"}'}}]},
        {"id": 3, "role": "tool", "tool_call_id": "call-one", "content": "synthetic result"},
        {"id": 4, "role": "assistant", "content": "final"},
    ]
    projected = project_execution(rows, lambda client: "generation-one" if client == "client-one" else None)
    assert projected[2]["generationId"] == projected[4]["generationId"] == "generation-one"
    assert projected[2]["executionSteps"] == [{
        "stepId": "call-one", "eventId": "3", "toolName": "read_file", "arguments": '{"path":"demo"}',
        "output": "synthetic result", "status": "completed", "revision": 3,
    }]


def test_same_tool_name_and_output_in_two_turns_never_reassign_steps():
    rows = [
        {"id": 1, "role": "user", "platform_message_id": "client-one"},
        {"id": 2, "role": "assistant", "tool_calls": [{"id": "call-one", "function": {"name": "read", "arguments": "{}"}}]},
        {"id": 3, "role": "assistant", "content": "first answer"},
        {"id": 4, "role": "user", "platform_message_id": "client-two"},
        {"id": 5, "role": "assistant", "tool_calls": [{"id": "call-two", "function": {"name": "read", "arguments": "{}"}}]},
        {"id": 6, "role": "tool", "tool_call_id": "call-one", "content": "late wrong turn result"},
    ]
    projected = project_execution(rows, lambda client: {"client-one": "g-one", "client-two": "g-two"}.get(client))
    assert projected[2]["generationId"] == "g-one"
    assert projected[2]["executionSteps"][0]["status"] == "unknown"
    assert projected[5]["generationId"] == "g-two"
    assert projected[5]["executionSteps"][0]["status"] == "unknown"


def test_unbound_history_does_not_guess_generation_or_tool_steps():
    rows = [{"id": 1, "role": "user", "content": "same prompt"},
            {"id": 2, "role": "assistant", "tool_calls": [{"id": "call", "function": {"name": "read", "arguments": "{}"}}]}]
    assert project_execution(rows, lambda client: None) == {}


def test_missing_result_and_explicit_error_are_distinct_facts():
    rows = [{"id": 1, "role": "user", "platform_message_id": "client"},
            {"id": 2, "role": "assistant", "tool_calls": [
                {"id": "no-result", "function": {"name": "one", "arguments": "{}"}},
                {"id": "failed", "function": {"name": "two", "arguments": "{}"}}]},
            {"id": 3, "role": "tool", "tool_call_id": "failed", "content": "result", "is_error": True}]
    steps = project_execution(rows, lambda client: "g")[2]["executionSteps"]
    assert [step["status"] for step in steps] == ["unknown", "failed"]


def test_history_port_and_session_adapter_deliver_structured_tool_facts(tmp_path):
    import asyncio
    from contextlib import closing
    from pathlib import Path
    from types import SimpleNamespace
    from oai_core.server import create_core
    from oai_core.auth import Principal
    from oai_hermes_adapter.host_history import HermesHistoryPort
    from oai_hermes_adapter.sessions import HermesSessionAdapter

    core = create_core({"storageRoot": str(tmp_path / "accounts"), "masterKeyFile": str(tmp_path / "master"),
                        "contractRoot": str(Path(__file__).resolve().parents[2] / "gateway-contract")})
    with closing(core.open_gateway_account("synthetic")) as account:
        account.credentials.create_password("synthetic-test-password")
        conversation = account.conversations.create("native", "tools", "create")["conversationId"]
        account.agent_sessions.record(conversation, "create")
        account.agent_sessions.attach(conversation, "host-session", "host-key")
        accepted = account.conversations.accept_message(conversation, "client", "prompt", [], "phone", "request", "correlation")
    rows = [{"id": 1, "role": "user", "platform_message_id": "client", "content": "prompt", "timestamp": 1},
            {"id": 2, "role": "assistant", "content": "", "timestamp": 2, "tool_calls": [
                {"id": "native-call", "function": {"name": "read", "arguments": "{}"}}]},
            {"id": 3, "role": "assistant", "content": "", "timestamp": 3, "tool_calls": [
                {"id": "native-call", "function": {"name": "read", "arguments": "{}"}}]},
            {"id": 4, "role": "tool", "tool_call_id": "native-call", "content": "result", "timestamp": 4},
            {"id": 5, "role": "assistant", "content": "final", "timestamp": 5}]
    metadata = {}
    database = SimpleNamespace(get_session=lambda session: {"id": session}, get_messages=lambda *args, **kw: rows,
        get_meta=metadata.get, set_meta=lambda key, value: metadata.update({key: value}),
        list_meta_prefix=lambda prefix: [(key, value) for key, value in metadata.items() if key.startswith(prefix)])
    port = HermesHistoryPort(core, SimpleNamespace(_db_for_key=lambda key: database))
    core.history_reader = port.read
    history = asyncio.run(HermesSessionAdapter(core).history(Principal("synthetic"), conversation))
    tool = next(row for row in history if row.get("executionSteps"))
    final = next(row for row in history if row["text"] == "final")
    assert tool["generationId"] == final["generationId"] == accepted["generationId"]
    assert tool["text"] == ""
    assert not any(row["text"].startswith("调用工具：") for row in history)
    assert tool["executionSteps"][0]["stepId"] == "native-call"
    assert tool["executionSteps"][0]["output"] == "result"
    assert tool["executionSteps"][0]["status"] == "completed"


def test_malformed_native_tool_fields_do_not_break_history_page():
    rows = [{"id": 1, "role": "user", "platform_message_id": "client"},
            {"id": 2, "role": "assistant", "content": "answer", "tool_calls": 123},
            {"id": 3, "role": "tool", "tool_call_id": ["not", "an", "id"], "content": "result"},
            {"id": 4, "role": "assistant", "tool_calls": [{"id": [], "function": {"name": "read", "arguments": "{}"}}]}]
    assert project_execution(rows, lambda client: "g") == {2: {"generationId": "g"}, 4: {"generationId": "g"}}


def test_compression_summary_without_request_identity_closes_tool_attribution():
    rows = [{"id": 1, "role": "user", "platform_message_id": "client"},
            {"id": 2, "role": "assistant", "tool_calls": [{"id": "before", "function": {"name": "read", "arguments": "{}"}}]},
            {"id": 3, "role": "user", "_compressed_summary": True, "content": "summary"},
            {"id": 4, "role": "assistant", "tool_calls": [{"id": "after", "function": {"name": "read", "arguments": "{}"}}]}]
    projected = project_execution(rows, lambda client: "g")
    assert 2 in projected
    assert 4 not in projected


@pytest.fixture
def steering_history(tmp_path, monkeypatch):
    from contextlib import closing
    from pathlib import Path
    from types import SimpleNamespace

    monkeypatch.setenv("HERMES_HOME", str(tmp_path / "hermes"))
    monkeypatch.setenv("HERMES_TEST_ISOLATION", str(tmp_path / "hermes"))
    from hermes_state import SessionDB
    from oai_core.server import create_core
    from oai_hermes_adapter.host_history import HermesHistoryPort, NATIVE_INPUT_KEY

    core = create_core({"storageRoot": str(tmp_path / "accounts"), "masterKeyFile": str(tmp_path / "master"),
        "contractRoot": str(Path(__file__).resolve().parents[2] / "gateway-contract")})
    database = SessionDB(tmp_path / "native.sqlite")
    database.create_session("native-session", "cli")
    with closing(core.open_gateway_account("synthetic")) as account:
        account.credentials.create_password("synthetic-test-password")
        conversation = account.conversations.create("tools", "tools", "create")["conversationId"]
        account.agent_sessions.record(conversation, "create")
        account.agent_sessions.attach(conversation, "native-session", "native-key")
        accepted = account.conversations.accept_message(conversation, "client", "prompt", [], "phone", "request", "correlation")
        account.conversations.workflow.claim(accepted["messageId"])
    port = HermesHistoryPort(core, SimpleNamespace(_db_for_key=lambda key: database))
    database.append_message("native-session", "user", "prompt", platform_message_id="client", timestamp=1)

    def steer(client="steer", generation=None):
        staged = port.stage_native_input({"accountId": "synthetic", "conversationId": conversation,
            "sessionKey": "native-key", "clientMessageId": client, "text": "prompt", "timestamp": 3000,
            "generationId": generation or accepted["generationId"], "source": {"platform": "synthetic"}})
        port.accept_native_input(staged)
        row_id = database.append_message("native-session", "user", "model input with origin",
            platform_message_id=client, timestamp=3, display_metadata={NATIVE_INPUT_KEY: {
                "version": 1, "storageOwner": port.storage_owner, "accountId": "synthetic",
                "conversationId": conversation, "inputKeys": [staged["key"]]}})
        return row_id, staged

    try:
        yield SimpleNamespace(core=core, database=database, port=port, conversation=conversation,
            accepted=accepted, steer=steer)
    finally:
        database.close()


def test_history_read_keeps_tools_and_results_across_trusted_same_generation_steer(steering_history):
    case = steering_history
    case.database.append_message("native-session", "assistant", "", timestamp=2, tool_calls=[
        {"id": "before", "function": {"name": "read", "arguments": "{}"}}])
    case.steer()
    case.database.append_message("native-session", "tool", "before result", tool_call_id="before", timestamp=4)
    case.database.append_message("native-session", "assistant", "", timestamp=5, tool_calls=[
        {"id": "after", "function": {"name": "read", "arguments": "{}"}}])
    case.database.append_message("native-session", "tool", "after result", tool_call_id="after", timestamp=6)
    case.database.append_message("native-session", "assistant", "final", timestamp=7)

    history = case.port.read("synthetic", case.conversation)
    tools = [row for row in history if row.get("executionSteps")]
    assert [(step["stepId"], step["status"], step["output"]) for row in tools for step in row["executionSteps"]] == [
        ("before", "completed", "before result"), ("after", "completed", "after result")]
    assert all(row["generationId"] == case.accepted["generationId"] for row in tools)
    assert next(row for row in history if row["text"] == "final")["generationId"] == case.accepted["generationId"]


@pytest.mark.parametrize("corruption", ["client", "status", "snapshot_scope", "snapshot_generation", "key_identity",
    "account", "session", "conversation", "storage_owner"])
def test_history_read_rejects_native_input_records_without_complete_binding_proof(steering_history, corruption):
    import json
    case = steering_history
    row_id, staged = case.steer()
    record = json.loads(case.database.get_meta(staged["key"]))
    if corruption == "client":
        record["clientMessageId"] = "different-client"
    elif corruption == "status":
        record["status"] = "staged"
    elif corruption == "snapshot_scope":
        record["inputs"][0]["conversationId"] = "different-conversation"
    elif corruption == "snapshot_generation":
        record["inputs"][0]["generationId"] = "different-generation"
    elif corruption in {"account", "session", "conversation", "storage_owner"}:
        field = {"account": "accountId", "session": "sessionId", "conversation": "conversationId", "storage_owner": "storageOwner"}[corruption]
        record[field] = "different-owner"
    else:
        # A namespaced but unrelated key is not proof of this client's identity.
        replacement = staged["key"][:-64] + "0" * 64
        case.database._execute_write(lambda conn: conn.execute(
            "UPDATE messages SET display_metadata=? WHERE id=?", (json.dumps({
                "oaiGatewayNativeInputs": {"version": 1, "storageOwner": case.port.storage_owner,
                "accountId": "synthetic", "conversationId": case.conversation, "inputKeys": [replacement]}}), row_id)))
        staged = {**staged, "key": replacement}
    case.database.set_meta(staged["key"], json.dumps(record))
    case.database.append_message("native-session", "assistant", "", timestamp=4, tool_calls=[
        {"id": "unproven", "function": {"name": "read", "arguments": "{}"}}])
    with pytest.raises(RuntimeError, match="HOST_HISTORY_BINDING_CONFLICT"):
        case.port.read("synthetic", case.conversation)


@pytest.mark.parametrize("boundary", ["duplicate", "stale", "unbound", "summary", "compression_child"])
def test_history_read_cannot_resume_a_generation_through_untrusted_boundaries(steering_history, boundary):
    from contextlib import closing
    case = steering_history
    case.database.append_message("native-session", "assistant", "", timestamp=2, tool_calls=[
        {"id": "before", "function": {"name": "read", "arguments": "{}"}}])
    session = "native-session"
    if boundary == "duplicate":
        case.steer()
        case.steer()
    elif boundary == "stale":
        with closing(case.core.open_gateway_account("synthetic")) as account:
            account.conversations.workflow.settle(case.accepted["messageId"], "completed")
            newer = account.conversations.accept_message(case.conversation, "new-client", "prompt", [], "phone", "new-request", "new-correlation")
            account.conversations.workflow.claim(newer["messageId"])
        case.database.append_message(session, "user", "prompt", platform_message_id="new-client", timestamp=3)
        case.steer(client="late-old-input")
    elif boundary in {"unbound", "summary"}:
        case.database.append_message(session, "user", "prompt", _compressed_summary=boundary == "summary", timestamp=3)
        case.steer()
    else:
        case.database.end_session(session, "compression")
        session = "compression-child"
        case.database.create_session(session, "cli", parent_session_id="native-session")
    case.database.append_message(session, "tool", "late result", tool_call_id="before", timestamp=5)
    case.database.append_message(session, "assistant", "", timestamp=6, tool_calls=[
        {"id": "unproven", "function": {"name": "read", "arguments": "{}"}}])
    steps = [step for row in case.port.read("synthetic", case.conversation) for step in row.get("executionSteps", [])]
    assert [(step["stepId"], step["status"], step["output"]) for step in steps] == [("before", "unknown", None)]


def test_history_source_id_survives_binding_to_compression_tip(steering_history):
    from contextlib import closing
    from oai_hermes_adapter.tool_events import NativeToolObserver
    case = steering_history
    case.database.append_message("native-session", "assistant", "", timestamp=2, tool_calls=[
        {"id": "source", "function": {"name": "read", "arguments": "{}"}}])
    observer = NativeToolObserver(case.core, case.port)
    context = {"core": case.core, "accountId": "synthetic", "conversationId": case.conversation,
        "clientMessageId": "client", "generationId": case.accepted["generationId"],
        "sessionId": "native-session", "executionObserver": observer}
    observer.record(context, tool_name="read", args={}, session_id="native-session", tool_call_id="source", turn_id="native-turn")
    before = observer.snapshot("synthetic", case.conversation, case.accepted["generationId"])["executionSteps"][0]
    case.database.end_session("native-session", "compression")
    case.database.create_session("compression-child", "cli", parent_session_id="native-session")
    # Model a persisted account already bound to the legal continuation tip.
    with closing(case.core.open_gateway_account("synthetic")) as account:
        account.store.database.execute("UPDATE conversation_agent_sessions SET agent_session_id=? WHERE conversation_id=?",
            ("compression-child", case.conversation))
    projected = next(row for row in case.port.read("synthetic", case.conversation) if row.get("executionSteps"))
    assert projected["messageId"] == before["sourceMessageId"]
    assert projected["executionSteps"][0]["sourceMessageId"] == projected["messageId"]
    assert projected["executionSteps"][0]["occurredAt"] == before["occurredAt"] == 2000


@pytest.mark.parametrize("carried_tail", [False, True])
def test_real_in_place_compaction_preserves_tool_details_and_public_source(steering_history, carried_tail):
    case = steering_history
    case.database.append_message("native-session", "assistant", "", timestamp=2, tool_calls=[
        {"id": "preserved-call", "function": {"name": "read", "arguments": '{"path":"demo"}'}}])
    case.steer()
    case.database.append_message("native-session", "tool", "original result", tool_call_id="preserved-call", timestamp=4)
    before = case.port.read("synthetic", case.conversation)
    original = next(row for row in before if row.get("executionSteps"))
    native = case.database.get_messages("native-session")
    case.database.archive_and_compact("native-session", native, tail_count=len(native) if carried_tail else 0)

    after = case.port.read("synthetic", case.conversation)
    tools = [row for row in after if row.get("executionSteps")]
    assert [(row["messageId"], row["executionSteps"]) for row in tools] == [
        (original["messageId"], original["executionSteps"])]
    assert next(row for row in after if row["messageId"] == original["messageId"])["state"] == "CONFIRMED"
    assert [(row["messageId"], row["text"]) for row in after if row["sender"] == "user"] == [
        (row["messageId"], row["text"]) for row in before if row["sender"] == "user"]
    assert len(case.database.get_messages("native-session", include_inactive=True)) == len(native) * 2


@pytest.mark.parametrize("observed_before", [False, True])
def test_real_compression_child_keeps_originals_without_claiming_new_tools(steering_history, observed_before):
    case = steering_history
    case.database.append_message("native-session", "assistant", "", timestamp=2, tool_calls=[
        {"id": "parent-call", "function": {"name": "read", "arguments": '{"path":"demo"}'}}])
    case.steer()
    case.database.append_message("native-session", "tool", "parent result", tool_call_id="parent-call", timestamp=4)
    before = case.port.read("synthetic", case.conversation) if observed_before else None
    case.database.publish_compression_child(parent_session_id="native-session", child_session_id="compression-child",
        source="cli", messages=case.database.get_messages("native-session"), require_compression_lease=False)
    case.database.append_message("compression-child", "assistant", "", timestamp=5, tool_calls=[
        {"id": "unproven-child-call", "function": {"name": "read", "arguments": "{}"}}])
    case.database.append_message("compression-child", "tool", "child result", tool_call_id="unproven-child-call", timestamp=6)

    after = case.port.read("synthetic", case.conversation)
    tools = [row for row in after if row.get("executionSteps")]
    assert [(step["stepId"], step["arguments"], step["output"], step["status"])
            for row in tools for step in row["executionSteps"]] == [
        ("parent-call", '{"path":"demo"}', "parent result", "completed")]
    assert len([row for row in after if row.get("clientMessageId") == "steer"]) == 1
    assert all(step["sourceMessageId"] == row["messageId"] for row in tools for step in row["executionSteps"])
    if before is not None:
        original = next(row for row in before if row.get("executionSteps"))
        assert tools[0]["messageId"] == original["messageId"]
        assert tools[0]["executionSteps"] == original["executionSteps"]
    assert len(case.database.get_messages("native-session", include_inactive=True)) == 4


def test_unobserved_compaction_keeps_archived_tool_details_without_reusing_unproven_source(steering_history):
    case = steering_history
    case.database.append_message("native-session", "assistant", "", timestamp=2, tool_calls=[
        {"id": "archived-call", "function": {"name": "read", "arguments": '{"path":"archived"}'}}])
    case.database.append_message("native-session", "tool", "archived result", tool_call_id="archived-call", timestamp=3)
    case.database.archive_and_compact("native-session", case.database.get_messages("native-session"))
    history = case.port.read("synthetic", case.conversation)
    tools = [row for row in history if row.get("executionSteps")]
    assert [(step["stepId"], step["arguments"], step["output"], step["status"])
            for row in tools for step in row["executionSteps"]] == [
        ("archived-call", '{"path":"archived"}', "archived result", "completed")]
    assert tools[0]["timestamp"] == 2000
    assert tools[0]["executionSteps"][0]["sourceMessageId"] == tools[0]["messageId"]
    assert len(case.database.get_messages("native-session", include_inactive=True)) == 6


def test_real_active_duplicate_input_is_not_a_trusted_compression_copy(steering_history):
    case = steering_history
    case.database.append_message("native-session", "assistant", "", timestamp=2, tool_calls=[
        {"id": "before-duplicate", "function": {"name": "read", "arguments": "{}"}}])
    case.steer()
    case.port.read("synthetic", case.conversation)
    duplicate = case.database.get_messages("native-session")[-1]
    case.database.append_message("native-session", "user", duplicate["content"], timestamp=duplicate["timestamp"],
        platform_message_id=duplicate["platform_message_id"], display_metadata=duplicate["display_metadata"])
    case.database.append_message("native-session", "tool", "must not claim", tool_call_id="before-duplicate", timestamp=4)
    case.database.append_message("native-session", "assistant", "", timestamp=5, tool_calls=[
        {"id": "injected", "function": {"name": "read", "arguments": "{}"}}])
    history = case.port.read("synthetic", case.conversation)
    assert [(step["stepId"], step["status"], step["output"]) for row in history for step in row.get("executionSteps", [])] == [
        ("before-duplicate", "unknown", None)]


@pytest.mark.parametrize("field", ["accountId", "conversationId", "storageOwner", "sessionId", "rowId"])
def test_compaction_origin_outside_scope_never_reuses_public_tool_source(steering_history, field):
    case = steering_history
    case.database.append_message("native-session", "assistant", "", timestamp=2, tool_calls=[
        {"id": "scoped-call", "function": {"name": "read", "arguments": "{}"}}])
    case.database.append_message("native-session", "tool", "scoped result", tool_call_id="scoped-call", timestamp=3)
    before = next(row for row in case.port.read("synthetic", case.conversation) if row.get("executionSteps"))
    native = case.database.get_messages("native-session")
    native[1]["display_metadata"]["oaiGatewayHistoryOrigin"][field] = 999999 if field == "rowId" else "other-owner"
    case.database.archive_and_compact("native-session", native)
    history = case.port.read("synthetic", case.conversation)
    steps = [step for row in history for step in row.get("executionSteps", [])]
    assert steps == before["executionSteps"]
    assert all(row["messageId"] == before["messageId"] for row in history if row.get("executionSteps"))
