"""通信观察器的离线、只读测试。"""
from __future__ import annotations

from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import threading
import time
from urllib.parse import urlsplit

import pytest


SPEC = importlib.util.spec_from_file_location(
    "observe_communication", Path(__file__).with_name("observe-communication.py"))
probe = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = probe
SPEC.loader.exec_module(probe)

CORE = "/open-android-intelligence/core/v1"
AGENTS = "/open-android-intelligence/v2/agents"
OLD_TOKEN = "fake-old-token"
NEW_TOKEN = "fake-new-token"
REFRESH = "fake-refresh"
PRIVATE = "PRIVATE-MESSAGE-MUST-NOT-LEAK"


class Stub:
    def __init__(self):
        self.requests = []
        self.redirect_agents = False
        self.agents = [{"id": "hermes", "online": True,
                        "capabilities": {"list": True, "history": True, "send": False}}]


@contextmanager
def http_stub():
    state = Stub()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            return

        def send_json(self, status, value):
            raw = json.dumps(value).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def _handle(self):
            path = urlsplit(self.path).path
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length)) if length else None
            state.requests.append({"method": self.command, "path": path,
                                   "authorization": self.headers.get("Authorization"), "body": body})
            if path == CORE + "/tokens/refresh":
                self.send_json(200, {"token": NEW_TOKEN, "expiresAt": "2099-01-01T00:00:00Z",
                                     "refreshCredential": "new-refresh", "clientId": "client-1",
                                     "loginSessionId": "session-1"})
            elif path == CORE + "/devices":
                self.send_json(200, {"devices": []})
            elif path == AGENTS and state.redirect_agents:
                self.send_response(302)
                self.send_header("Location", "/redirected")
                self.end_headers()
            elif path == AGENTS:
                self.send_json(200, {"agents": state.agents})
            elif path.endswith("/history"):
                self.send_json(200, {"messages": [{"text": PRIVATE}]})
            elif path.endswith("/generation"):
                self.send_json(200, {"generation": None})
            elif path == "/redirected":
                state.requests.append({"method": self.command, "path": path, "authorization": None, "body": None})
                self.send_json(200, {"agents": []})
            else:
                self.send_json(404, {"error": {"code": "NOT_FOUND"}})

        do_GET = _handle
        do_POST = _handle

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", state
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)


def config(tmp_path: Path, url: str, *, expired: bool = True) -> Path:
    path = tmp_path / "client.json"
    value = {"url": url, "token": OLD_TOKEN, "refreshCredential": REFRESH,
             "clientId": "client-1", "loginSessionId": "session-1",
             "expiresAt": "2001-01-01T00:00:00Z" if expired else "2099-01-01T00:00:00Z"}
    path.write_text(json.dumps(value), encoding="utf-8")
    path.chmod(0o600)
    return path


def rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def sample(*, online: bool = True) -> dict:
    return {
        "time": "2026-11-08T00:00:00.000Z",
        "httpStatus": {"devices": 200, "agents": 200, "session": 200},
        "agentOnline": {"hermes": online},
        "capabilities": {"hermes": {"list": True, "history": True}},
        "requestId": "obs_test",
        "latencyMs": {"devices": 1, "agents": 1, "session": 1, "total": 3},
        "errorCode": None if online else "AGENT_OFFLINE",
    }


def summarize(rows_value, **changes):
    values = {
        "started_at": "2026-11-08T00:00:00.000Z", "ended_at": "2026-11-09T00:00:01.000Z",
        "actual_duration": 86401.0, "requested_duration": 86400.0, "interval": 10.0,
        "stop_reason": "completed", "manifest_status": "ok", "changed_paths": [],
        "target_agent_id": "hermes",
    }
    values.update(changes)
    return probe.summarize(rows_value, **values)


def test_refresh_agents_readonly_and_jsonl_has_no_secrets(tmp_path):
    with http_stub() as (url, state):
        output = tmp_path / "evidence"
        result = probe.run_observer(
            config_path=config(tmp_path, url), output_dir=output, duration=0.05,
            interval=0.01, timeout=0.5, agent_id="hermes", session_id="session-1",
            read_path="history",
        )
        assert result["completed_duration"] is True
        assert result["passed"] is False
        values = rows(output / "observations.jsonl")
        assert values
        assert set(values[0]) == {"time", "httpStatus", "agentOnline", "capabilities", "requestId", "latencyMs", "errorCode"}
        assert values[0]["httpStatus"] == {"devices": 200, "agents": 200, "session": 200}
        assert values[0]["agentOnline"] == {"hermes": True}
        assert values[0]["capabilities"]["hermes"] == {"list": True, "history": True, "send": False}
        paths = [request["path"] for request in state.requests]
        assert paths.index(CORE + "/tokens/refresh") < paths.index(CORE + "/devices") < paths.index(AGENTS)
        assert next(r for r in state.requests if r["path"] == AGENTS)["authorization"] == "Bearer " + NEW_TOKEN
        assert all(r["method"] == "GET" for r in state.requests if r["path"].startswith(AGENTS))
        for path in output.iterdir():
            if path.is_file():
                text = path.read_text(encoding="utf-8", errors="replace")
                assert all(secret not in text for secret in (OLD_TOKEN, NEW_TOKEN, REFRESH, PRIVATE, "Authorization"))


def test_summary_only_passes_full_24h_with_samples_and_unchanged_source():
    passed = summarize([sample()])
    short = summarize([sample()], actual_duration=10.0, requested_duration=10.0)
    empty = summarize([])
    unverified = summarize([sample()], manifest_status="not_provided")
    core_failure = summarize([{**sample(), "httpStatus": {"devices": 503, "agents": None, "session": None},
                               "agentOnline": {}, "capabilities": {}, "errorCode": "CORE_UNREACHABLE"}])
    agent_offline = summarize([sample(online=False)])
    assert passed["passed"] is True and passed["completed_duration"] is True
    assert short["completed_duration"] is True and short["passed"] is False
    assert empty["passed"] is False and empty["sample_count"] == 0
    assert unverified["passed"] is False and unverified["version_unchanged"] is False
    assert core_failure["core"]["status"] == "failed" and core_failure["agents"]["status"] == "unknown"
    assert agent_offline["core"]["status"] == "healthy" and agent_offline["agents"]["status"] == "degraded"
    assert agent_offline["passed"] is False


def test_manifest_change_invalidates_run(tmp_path):
    root = tmp_path / "repo"
    root.mkdir()
    source = root / "source.py"
    source.write_text("one\n", encoding="utf-8")
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"files": [{
        "path": "source.py", "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    }]}), encoding="utf-8")
    entries = probe.load_manifest(manifest)
    assert probe.check_manifest(entries, root=root)["status"] == "ok"
    source.write_text("two\n", encoding="utf-8")
    changed = probe.check_manifest(entries, root=root)
    assert changed["status"] == "mismatch" and changed["changedPaths"] == ["source.py"]
    result = summarize([sample()], stop_reason="source_changed", manifest_status=changed["status"],
                       changed_paths=changed["changedPaths"])
    assert result["verdict"] == "invalid" and result["passed"] is False


def test_real_http_redirect_is_rejected(tmp_path):
    with http_stub() as (url, state):
        state.redirect_agents = True
        client = probe.CoreClient.from_config(config(tmp_path, url, expired=False))
        client.timeout = 0.5
        result = probe.observe_once(client, agent_id="hermes", session_id=None,
                                    read_path="history", timeout=0.5)
        assert result["httpStatus"]["agents"] == 302
        assert result["errorCode"] == "AGENT_HTTP_ERROR"
        assert not any(request["path"] == "/redirected" for request in state.requests)


def test_existing_core_client_entrypoints_are_real():
    assert callable(getattr(probe.CoreClient, "from_config", None))
    assert callable(getattr(probe.CoreClient, "devices", None))
