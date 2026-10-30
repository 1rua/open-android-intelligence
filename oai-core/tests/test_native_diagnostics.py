"""Trace correlation is optional and may never become authorization or transcript logging."""
import json
import logging
import re

from oai_core.native_diagnostics import current_trace_headers, trace_context, trace_native_stage


def test_trace_context_validates_propagates_and_restores():
    assert current_trace_headers() == {}
    parent = "00-" + "a" * 32 + "-" + "b" * 16 + "-01"
    with trace_context({"Traceparent": parent}, request_id="original") as context:
        headers = current_trace_headers()
        assert headers["X-OAI-Request-Id"] == "original"
        assert context.trace_id == "a" * 32
        assert headers["traceparent"] != parent
        assert headers["traceparent"].endswith("-01")
    assert current_trace_headers() == {}
    with trace_context({"traceparent": "00-" + "a" * 32 + "-" + "b" * 16 + "-ff"}):
        assert current_trace_headers()["traceparent"].endswith("-01")
    for invalid in ("bad\nheader", "00-" + "0" * 32 + "-" + "b" * 16 + "-00", "x" * 10000):
        with trace_context({"traceparent": invalid}) as context:
            assert re.fullmatch("[0-9a-f]{32}", context.trace_id)
            assert context.trace_id != "0" * 32


def test_trace_logs_only_selected_bounded_metadata(caplog):
    caplog.set_level(logging.DEBUG, logger="oai_core.native_sessions")
    with trace_context(request_id="request-original") as context:
        trace_native_stage("core.received", client_message_id="client-original", state="bad\nlog", conversation_id="x" * 1025, message_id={"text": "PRIVATE"})
        expected_trace = context.trace_id
    record = json.loads(caplog.records[0].getMessage().removeprefix("native_session "))
    assert record["traceId"] == expected_trace
    assert record["requestId"] == "request-original"
    assert record["bootId"] and record["monotonicNs"] > 0
    assert "state" not in record and "conversationId" not in record and "messageId" not in record
    assert not {"text", "token", "Authorization"} & record.keys()


def test_request_identity_never_accepts_authorization_text(caplog):
    caplog.set_level(logging.DEBUG, logger="oai_core.native_sessions")
    with trace_context(request_id="Bearer SYNTHETIC_PRIVATE"):
        assert "X-OAI-Request-Id" not in current_trace_headers()
        trace_native_stage("core.boundary", request_id="Bearer SYNTHETIC_PRIVATE")
    assert "SYNTHETIC_PRIVATE" not in caplog.text
