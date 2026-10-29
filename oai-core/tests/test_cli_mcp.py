"""CLI/MCP exercise the same real HTTP contract without importing device stores."""

import asyncio
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import errno
import json
import os
from pathlib import Path
import sys
import threading

import pytest

from oai_core.cli import main, _wait
from oai_core.client import CoreClient, CoreClientError, load_client_config, save_client_config, validate_url
from oai_core.mcp import create_mcp
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


@contextmanager
def core_http():
    received = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            return

        def handle_request(self):
            size = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(size)) if size else None
            received.append((self.command, self.path, dict(self.headers), body))
            if self.path.endswith("/tokens"):
                result = {"token": "private-core-token", "expiresAt": "2026-12-01T00:00:00Z"}
            elif self.path.endswith("/capabilities"):
                result = {"capabilities": [{"capabilityId": "screen.read", "capabilityVersion": "1.0.0"}]}
            elif self.path.endswith("/cancel"):
                result = {"requestId": "req_native", "state": "cancel_requested"}
            elif self.path.endswith("/redirect"):
                self.send_response(302)
                self.send_header("Location", "/credential-recipient")
                self.end_headers()
                return
            else:
                result = {"requestId": "req_native", "state": "pending"}
            raw = json.dumps({"data": result}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        do_GET = handle_request
        do_POST = handle_request
        do_DELETE = handle_request

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", received
    finally:
        server.shutdown()
        server.server_close()
        worker.join()


def test_cli_login_and_submission_share_authenticated_core(tmp_path, monkeypatch, capsys):
    path = tmp_path / "client.json"
    with core_http() as (url, received):
        monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: "secret-password")
        assert main(["--client-config", str(path), "login", "--url", url,
                     "--username", "alice", "--device", "paired_phone"]) == 0
        assert "private-core-token" not in capsys.readouterr().out
        assert path.stat().st_mode & 0o077 == 0
        assert load_client_config(path)["token"] == "private-core-token"
        assert main(["--client-config", str(path), "invoke", "screen.read", "--version", "1.0.0",
                     "--parameters", '{"limit":2}', "--idempotency-key", "request-retry-1"]) == 0
        assert json.loads(capsys.readouterr().out)["requestId"] == "req_native"
        _, target, headers, body = received[-1]
        assert target == "/open-android-intelligence/core/v1/device-requests"
        assert headers["Authorization"] == "Bearer private-core-token"
        assert headers["Idempotency-Key"] == "request-retry-1"
        assert body == {"capabilityId": "screen.read", "capabilityVersion": "1.0.0", "parameters": {"limit": 2}}
        assert received[0][3] == {"username": "alice", "password": "secret-password", "deviceId": "paired_phone"}


def test_client_refuses_credential_redirects_and_remote_cleartext():
    with pytest.raises(CoreClientError, match="CORE_TLS_REQUIRED"):
        CoreClient("http://example.com", "private-core-token")
    with pytest.raises(CoreClientError, match="CORE_URL_INVALID"):
        validate_url("http://localhost:bad")
    with core_http() as (url, received):
        client = CoreClient(url, "private-core-token")
        with pytest.raises(CoreClientError) as failure:
            client._request("GET", "/redirect")
        assert failure.value.status == 302
        assert len(received) == 1


def test_config_rejects_unprotected_or_symlinked_credentials(tmp_path):
    path = tmp_path / "client.json"
    save_client_config(path, {"url": "http://127.0.0.1", "token": "secret"})
    os.chmod(path, 0o644)
    with pytest.raises(CoreClientError, match="CLIENT_CONFIG_PERMISSIONS_INVALID"):
        load_client_config(path)
    os.chmod(path, 0o600)
    link = tmp_path / "linked.json"
    try:
        link.symlink_to(path)
    except (NotImplementedError, PermissionError, FileNotFoundError) as exc:
        assert path.is_file() and link.parent.is_dir()
        link = Path(sys.base_prefix) / "bin" / "python3"
        if not link.is_symlink():
            pytest.skip(f"无法创建 fixture，且没有可用的现有符号链接: {type(exc).__name__}")
    with pytest.raises(CoreClientError, match="CLIENT_CONFIG_INVALID") as failure:
        load_client_config(link)
    # ELOOP proves the OS rejected the link before target ownership/content;
    # invalid JSON from reading an executable must not make this test green.
    assert isinstance(failure.value.__cause__, OSError)
    assert failure.value.__cause__.errno == errno.ELOOP


def test_cli_logout_revokes_before_removing_local_credential(tmp_path, capsys):
    path = tmp_path / "client.json"
    with core_http() as (url, received):
        save_client_config(path, {"url": url, "token": "private-core-token"})
        assert main(["--client-config", str(path), "logout"]) == 0
        assert not path.exists()
        assert json.loads(capsys.readouterr().out) == {"authenticated": False}
        assert received[0][0] == "DELETE"
        assert received[0][1].endswith("/tokens/current")


def test_mcp_tools_use_core_api_and_never_take_account_identity():
    async def scenario(url):
        server = create_mcp(CoreClient(url, "private-core-token"))
        tools = await server.list_tools()
        assert {tool.name for tool in tools} == {
            "oai_capabilities", "oai_invoke", "oai_request_status", "oai_cancel_request", "oai_ack_result",
        }
        for tool in tools:
            assert not {"accountId", "account_id", "deviceId", "device_id"} & set(tool.inputSchema.get("properties", {}))
        await server.call_tool("oai_invoke", {"capability_id": "screen.read", "capability_version": "1.0.0",
            "parameters": {}, "idempotency_key": "mcp-retry-1"})
        await server.call_tool("oai_cancel_request", {"request_id": "req_native"})

    with core_http() as (url, received):
        asyncio.run(scenario(url))
        assert received[0][2]["Idempotency-Key"] == "mcp-retry-1"
        assert received[0][3] == {"capabilityId": "screen.read", "capabilityVersion": "1.0.0", "parameters": {}}
        assert received[1][1].endswith("/req_native/cancel")


def test_cli_wait_timeout_keeps_core_request_running_until_explicit_cancel():
    with core_http() as (url, received):
        result = _wait(CoreClient(url, "private-core-token"), {"requestId": "req_native", "state": "claimed"}, 0)
        assert result == {"requestId": "req_native", "state": "claimed", "clientWaitExpired": True}
        assert received == []


def test_official_mcp_client_negotiates_and_calls_stdio_process(tmp_path):
    path = tmp_path / "mcp-client.json"

    async def scenario():
        parameters = StdioServerParameters(
            command=sys.executable,
            args=["-m", "oai_core.cli", "--client-config", str(path), "mcp"],
            env={"PYTHONPATH": os.environ.get("PYTHONPATH", "")},
        )
        async with stdio_client(parameters) as (read, write):
            async with ClientSession(read, write) as session:
                initialization = await session.initialize()
                assert initialization.serverInfo.name == "OAI"
                result = await session.call_tool("oai_capabilities", {})
                assert not result.isError
                assert "screen.read" in result.content[0].text

    with core_http() as (url, received):
        save_client_config(path, {"url": url, "token": "private-core-token"})
        asyncio.run(scenario())
        assert len(received) == 1
        assert received[0][1].endswith("/capabilities")


def test_local_accounts_stay_isolated_and_legacy_policy_commands_are_retired(tmp_path, monkeypatch, capsys):
    from oai_core.server import create_core, load_config
    settings = tmp_path / "core.json"
    settings.write_text(json.dumps({
        "storageRoot": "state", "masterKeyFile": "secrets/master-key",
        "contractRoot": os.path.relpath(Path(__file__).parents[2] / "gateway-contract", tmp_path),
        "agents": [],
    }))
    monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: "real-test-password")
    for username in ("alice", "bob"):
        assert main(["account", "create", "--config", str(settings), "--username", username]) == 0
        assert json.loads(capsys.readouterr().out) == {"accountId": username, "created": True}
    assert main(["account", "create", "--config", str(settings), "--username", "alice"]) == 1
    assert "ACCOUNT_EXISTS" in capsys.readouterr().err
    # The server command is retired; account authentication remains independent.
    assert main(["policy", "set", "--config", str(settings), "--account", "bob",
                 "--capability", "org.openandroidintelligence.sms.query", "--disabled"]) == 1
    assert "DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID" in capsys.readouterr().err
    core = create_core(load_config(settings))
    for username in ("alice", "bob"):
        account = core.open_gateway_account(username)
        try:
            assert account.credentials.verify_password("real-test-password")
            assert not account.credentials.verify_password("wrong-password")
            assert account.store.database.execute(
                "SELECT 1 FROM account_metadata WHERE key='device-capability-policy:v1'",
            ).fetchone() is None
        finally:
            account.close()
    assert main(["policy", "set", "--config", str(settings), "--account", "unknown",
                 "--capability", "org.openandroidintelligence.sms.query", "--enabled"]) == 1
    assert "DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID" in capsys.readouterr().err
    assert not core.account_exists("unknown")
