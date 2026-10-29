"""Tool attribution uses only native identities in synthetic history rows."""
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
