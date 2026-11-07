"""Trace correlation is optional and may never become authorization or transcript logging."""
import json
import logging
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

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


def test_a_custom_logging_sink_failure_does_not_fail_the_business_operation(monkeypatch):
    import oai_core.native_diagnostics as diagnostics
    monkeypatch.setattr(diagnostics.logger, "isEnabledFor", lambda _: True)
    def broken(*args, **kwargs):
        raise RuntimeError("closed diagnostic sink")
    monkeypatch.setattr(diagnostics.logger, "debug", broken)
    diagnostics.trace_native_stage("core.operation.storage", request_id="original-request")


def test_connection_diagnostics_are_visible_in_a_default_cli_subprocess():
    repository = Path(__file__).resolve().parents[2]
    source = repository / "oai-core" / "src"
    environment = os.environ.copy()
    environment["PYTHONPATH"] = os.pathsep.join(
        (str(source), *filter(None, environment.get("PYTHONPATH", "").split(os.pathsep)))
    )
    script = """
import json
import logging
from oai_core.native_diagnostics import configure_connection_diagnostics

configure_connection_diagnostics()
logging.getLogger("oai_core.agent_health").info("Agent health agent=synthetic state=recovered")
logging.getLogger("oai_core.adapters.remote").info(
    "agent_transport agent=synthetic source=agent_bridge stage=recovered"
)
logging.getLogger("aiohttp.access").info("GET https://sensitive.invalid/?token=PRIVATE")
root = logging.getLogger()
print(json.dumps({
    "rootHandlers": len(root.handlers),
    "rootLevel": root.level,
    "lastResortLevel": logging.lastResort.level,
}))
"""
    process = subprocess.run(
        [sys.executable, "-B", "-c", script], cwd=repository, env=environment,
        capture_output=True, text=True, timeout=20,
    )
    assert process.returncode == 0, process.stdout + process.stderr
    assert "Agent health agent=synthetic state=recovered" in process.stderr
    assert "agent_transport agent=synthetic source=agent_bridge stage=recovered" in process.stderr
    assert "PRIVATE" not in process.stderr
    assert json.loads(process.stdout) == {
        "rootHandlers": 0,
        "rootLevel": logging.WARNING,
        "lastResortLevel": logging.WARNING,
    }


def test_connection_diagnostics_defer_to_an_existing_host_handler():
    repository = Path(__file__).resolve().parents[2]
    source = repository / "oai-core" / "src"
    environment = os.environ.copy()
    environment["PYTHONPATH"] = os.pathsep.join(
        (str(source), *filter(None, environment.get("PYTHONPATH", "").split(os.pathsep)))
    )
    script = """
import io
import json
import logging
from oai_core.native_diagnostics import configure_connection_diagnostics

stream = io.StringIO()
root = logging.getLogger()
root.addHandler(logging.StreamHandler(stream))
configure_connection_diagnostics()
health = logging.getLogger("oai_core.agent_health")
remote = logging.getLogger("oai_core.adapters.remote")
health.info("health-visible")
remote.info("remote-visible")
print(json.dumps({
    "healthHandlers": len(health.handlers),
    "remoteHandlers": len(remote.handlers),
    "healthPropagates": health.propagate,
    "remotePropagates": remote.propagate,
    "hostOutput": stream.getvalue().splitlines(),
}))
"""
    process = subprocess.run(
        [sys.executable, "-B", "-c", script], cwd=repository, env=environment,
        capture_output=True, text=True, timeout=20,
    )
    assert process.returncode == 0, process.stdout + process.stderr
    assert json.loads(process.stdout) == {
        "healthHandlers": 0,
        "remoteHandlers": 0,
        "healthPropagates": True,
        "remotePropagates": True,
        "hostOutput": ["health-visible", "remote-visible"],
    }


def test_server_run_configures_connection_diagnostics_before_loading_config(monkeypatch):
    import oai_core.server as server

    calls = []
    monkeypatch.setattr(server, "configure_connection_diagnostics", lambda: calls.append("configured"))
    monkeypatch.setattr(server, "load_config", lambda _: (_ for _ in ()).throw(RuntimeError("stop")))
    with pytest.raises(RuntimeError, match="stop"):
        server.run("synthetic-config.json")
    assert calls == ["configured"]
