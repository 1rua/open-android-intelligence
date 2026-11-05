"""CLI/MCP exercise the same real HTTP contract without importing device stores."""

import asyncio
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import errno
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import types

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
        monkeypatch.setattr("oai_core.cli.sys.stdin.isatty", lambda: True)
        assert main(["--client-config", str(path), "login", "--url", url,
                     "--username", "alice", "--device", "paired_phone"]) == 0
        assert "private-core-token" not in capsys.readouterr().out
        assert path.stat().st_mode & 0o077 == 0
        assert load_client_config(path)["token"] == "private-core-token"
        assert main(["--client-config", str(path), "invoke", "screen.read", "--version", "1.0.0",
                     "--agent-name", "CLI-test", "--purpose", "验证调用声明", "--parameters", '{"limit":2}', "--idempotency-key", "request-retry-1"]) == 0
        assert json.loads(capsys.readouterr().out)["requestId"] == "req_native"
        _, target, headers, body = received[-1]
        assert target == "/open-android-intelligence/core/v1/device-requests"
        assert headers["Authorization"] == "Bearer private-core-token"
        assert headers["Idempotency-Key"] == "request-retry-1"
        assert body == {"caller": {"agentName": "CLI-test", "purpose": "验证调用声明"}, "capabilityId": "screen.read", "capabilityVersion": "1.0.0", "parameters": {"limit": 2}}
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


def test_cli_logout_revokes_and_keeps_local_barrier(tmp_path, capsys):
    path = tmp_path / "client.json"
    with core_http() as (url, received):
        save_client_config(path, {"url": url, "token": "private-core-token"})
        assert main(["--client-config", str(path), "logout"]) == 0
        value = load_client_config(path)
        assert value["loggedOut"] is True
        assert "token" not in value
        assert json.loads(capsys.readouterr().out) == {"authenticated": False, "remoteRevocationPending": False}
        assert received[0][0] == "DELETE"
        assert received[0][1].endswith("/tokens/current")


def test_mcp_tools_use_core_api_and_never_take_account_identity():
    async def scenario(url):
        server = create_mcp(CoreClient(url, "private-core-token"))
        tools = await server.list_tools()
        assert {tool.name for tool in tools} == {
            "oai_devices", "oai_capabilities", "oai_invoke", "oai_request_status", "oai_cancel_request", "oai_ack_result",
            "oai_register_endpoint", "oai_endpoints", "oai_unregister_endpoint", "oai_receive_deliveries", "oai_ack_delivery",
        }
        for tool in tools:
            assert not {"accountId", "account_id", "username", "password", "otp", "recovery_code"} & set(tool.inputSchema.get("properties", {}))
        await server.call_tool("oai_invoke", {"capability_id": "screen.read", "capability_version": "1.0.0",
            "parameters": {}, "idempotency_key": "mcp-retry-1", "agent_name": "MCP-test", "purpose": "验证MCP调用声明"})
        await server.call_tool("oai_cancel_request", {"request_id": "req_native"})

    with core_http() as (url, received):
        asyncio.run(scenario(url))
        assert received[0][2]["Idempotency-Key"] == "mcp-retry-1"
        assert received[0][3] == {"caller": {"agentName": "MCP-test", "purpose": "验证MCP调用声明"}, "capabilityId": "screen.read", "capabilityVersion": "1.0.0", "parameters": {}}
        assert received[1][1].endswith("/req_native/cancel")


def test_mcp_devices_and_optional_target_use_actual_http():
    async def scenario(url):
        server = create_mcp(CoreClient(url, "private-core-token"))
        await server.call_tool("oai_devices", {})
        await server.call_tool("oai_capabilities", {"device_id": "phone / one"})
        await server.call_tool("oai_invoke", {"capability_id": "screen.read", "capability_version": "1",
            "parameters": {}, "idempotency_key": "mcp-key", "agent_name": "test", "purpose": "test",
            "device_id": "phone / one"})

    with core_http() as (url, received):
        asyncio.run(scenario(url))
        assert received[0][1].endswith("/devices")
        assert received[1][1].endswith("/capabilities?deviceId=phone%20%2F%20one")
        assert received[2][3]["deviceId"] == "phone / one"


def test_cli_wait_timeout_keeps_core_request_running_until_explicit_cancel():
    with core_http() as (url, received):
        result = _wait(CoreClient(url, "private-core-token"), {"requestId": "req_native", "state": "claimed"}, 0)
        assert result == {"requestId": "req_native", "state": "claimed", "clientWaitExpired": True}
        assert received == []


def test_cli_client_and_mcp_reject_missing_or_blank_caller_without_network(tmp_path, capsys):
    with core_http() as (url, received):
        path = tmp_path / "caller-client.json"
        save_client_config(path, {"url": url, "token": "private-core-token"})
        assert main(["--client-config", str(path), "invoke", "screen.read", "--version", "1.0.0"]) == 1
        assert "CALLER_DECLARATION_REQUIRED" in capsys.readouterr().err
        for agent_name, purpose in ((None, None), (" ", "test"), ("MCP", "\n")):
            with pytest.raises(CoreClientError, match="CALLER_DECLARATION_REQUIRED"):
                CoreClient(url, "private-core-token").invoke("screen.read", "1.0.0", {}, agent_name=agent_name, purpose=purpose)

        async def scenario():
            server = create_mcp(CoreClient(url, "private-core-token"))
            with pytest.raises(Exception, match="CALLER_DECLARATION_REQUIRED"):
                await server.call_tool("oai_invoke", {"capability_id": "screen.read", "capability_version": "1.0.0", "parameters": {}, "idempotency_key": "legacy-mcp"})

        asyncio.run(scenario())
        assert received == []


def test_cli_and_mcp_endpoint_methods_use_authenticated_core_routes(tmp_path, capsys):
    with core_http() as (url, received):
        path = tmp_path / "endpoint-client.json"
        save_client_config(path, {"url": url, "token": "private-core-token"})
        for arguments in (["register", "--registration-key", "receiver-main", "--label", "Hermes"],
                          ["list"], ["receive", "endpoint_test"],
                          ["ack", "endpoint_test", "delivery_test", "event_test"], ["unregister", "endpoint_test"]):
            assert main(["--client-config", str(path), "endpoint", *arguments]) == 0
            capsys.readouterr()
        assert [(method, route) for method, route, _, _ in received] == [
            ("POST", "/open-android-intelligence/core/v1/endpoints"),
            ("GET", "/open-android-intelligence/core/v1/endpoints"),
            ("GET", "/open-android-intelligence/core/v1/endpoints/endpoint_test/deliveries"),
            ("POST", "/open-android-intelligence/core/v1/endpoints/endpoint_test/deliveries/delivery_test/ack"),
            ("DELETE", "/open-android-intelligence/core/v1/endpoints/endpoint_test"),
        ]
        assert received[0][3] == {"registrationKey": "receiver-main", "label": "Hermes", "ttlSeconds": 3600}
        assert received[3][3] == {"eventId": "event_test"}
        assert all(headers["Authorization"] == "Bearer private-core-token" for _, _, headers, _ in received)

        async def scenario():
            server = create_mcp(CoreClient(url, "private-core-token"))
            for name, arguments in (
                ("oai_register_endpoint", {"registration_key": "receiver-main", "label": "Hermes"}),
                ("oai_endpoints", {}),
                ("oai_receive_deliveries", {"endpoint_id": "endpoint_test"}),
                ("oai_ack_delivery", {"endpoint_id": "endpoint_test", "delivery_id": "delivery_test", "event_id": "event_test"}),
                ("oai_unregister_endpoint", {"endpoint_id": "endpoint_test"}),
            ):
                await server.call_tool(name, arguments)

        asyncio.run(scenario())
        assert received[:5] == received[5:]


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


@contextmanager
def account_http(mode="off"):
    received = []
    state = {"mode": mode, "refreshes": {}, "drop_refresh": False, "drop_invoke": False,
             "drop_revoke": False}
    bundle = {"token": "access-new", "accessToken": "access-new", "expiresAt": "2099-01-01T00:00:00Z",
              "refreshCredential": "refresh-new", "clientId": "client-1", "clientProof": "proof-1",
              "loginSessionId": "session-1", "accountId": "account-1"}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            return

        def handle_request(self):
            size = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(size)) if size else None
            path = self.path.removeprefix("/open-android-intelligence/core/v1")
            received.append((self.command, path, dict(self.headers), body))
            status, result = 200, {}
            if path == "/tokens":
                client_id = body.get("clientId", "client-1")
                response_bundle = {**bundle, "clientId": client_id}
                if state["mode"] != "off":
                    status = 401
                    code = "MFA_REQUIRED" if state["mode"] == "required" else "MFA_ENROLLMENT_REQUIRED"
                    result = {"error": {"code": code, "details": {"challengeId": "challenge-1",
                        "clientId": client_id, "methods": ["totp", "recovery-code"],
                        "expiresAt": "2099-01-01T00:00:00Z"}}}
                else:
                    result = {"data": response_bundle}
            elif path == "/tokens/complete":
                result = {"data": {**bundle, "clientId": body["clientId"]}}
            elif path == "/mfa/enroll":
                result = {"data": {"enrollmentId": "enroll-1", "provisioningUri": "otpauth://totp/Test",
                                   "secret": "setup-secret"}}
            elif path == "/mfa/confirm":
                result = {"data": {"recoveryCodes": ["recovery-secret-1", "recovery-secret-2"]}}
            elif path == "/tokens/refresh":
                key = (body["refreshCredential"], body["operationId"])
                state["refreshes"].setdefault(key, dict(bundle))
                result = {"data": state["refreshes"][key]}
                if state["drop_refresh"]:
                    state["drop_refresh"] = False
                    self.close_connection = True
                    return
            elif path == "/tokens/revoke":
                if state["drop_revoke"]:
                    self.close_connection = True
                    return
                result = {"data": {"revoked": True}}
            elif path == "/device-requests":
                if state["drop_invoke"]:
                    state["drop_invoke"] = False
                    self.close_connection = True
                    return
                result = {"data": {"requestId": "request-1", "state": "pending"}}
            elif path == "/devices":
                result = {"data": {"devices": [], "defaultDeviceId": None}}
            else:
                result = {"data": {"ok": True}}
            raw = json.dumps(result).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        do_GET = handle_request
        do_POST = handle_request
        do_DELETE = handle_request
        do_PUT = handle_request

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", received, state
    finally:
        server.shutdown()
        server.server_close()
        worker.join()


def _account_config(url, **changes):
    return {"url": url, "username": "alice", "token": "access-old", "expiresAt": "2001-01-01T00:00:00Z",
            "refreshCredential": "refresh-old", "clientId": "client-1", "clientProof": "proof-1",
            "loginSessionId": "session-1", "accountId": "account-1", **changes}


def test_password_login_without_device_or_mfa_and_same_user_scope_relogin(tmp_path, monkeypatch, capsys):
    path = tmp_path / "client.json"
    prompts = []
    monkeypatch.setattr("oai_core.cli.sys.stdin.isatty", lambda: True)
    monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda prompt: prompts.append(prompt) or "password")
    with account_http() as (url, received, _):
        arguments = ["--client-config", str(path), "login", "--url", url, "--username", "alice"]
        assert main(arguments) == 0
        first_login = received[0][3]
        assert first_login["username"] == "alice" and first_login["password"] == "password"
        assert isinstance(first_login["clientId"], str) and first_login["clientId"]
        assert "clientProof" not in first_login and "deviceId" not in first_login
        assert prompts == ["OAI password: "]
        output = capsys.readouterr().out
        assert all(secret not in output for secret in ("access-new", "refresh-new", "proof-1", "password"))
        saved = load_client_config(path)
        assert saved["clientProof"] == "proof-1"
        assert saved["clientId"] == first_login["clientId"]
        assert path.stat().st_mode & 0o077 == 0
        assert main(arguments) == 0
        assert received[1][3] == {"username": "alice", "password": "password", "clientId": saved["clientId"], "clientProof": "proof-1"}
        assert prompts == ["OAI password: ", "OAI password: "]


@pytest.mark.parametrize("choice, expected", [("1", {"otp": "factor"}), ("2", {"recoveryCode": "factor"})])
def test_login_answers_only_server_mfa_challenge(tmp_path, monkeypatch, capsys, choice, expected):
    path = tmp_path / "client.json"
    monkeypatch.setattr("oai_core.cli.sys.stdin.isatty", lambda: True)
    monkeypatch.setattr("builtins.input", lambda _: choice)
    values = iter(["password", "factor"])
    monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: next(values))
    with account_http("required") as (url, received, _):
        assert main(["--client-config", str(path), "login", "--url", url, "--username", "alice"]) == 0
        assert [entry[1] for entry in received] == ["/tokens", "/tokens/complete"]
        assert received[1][3] == {"challengeId": "challenge-1", "clientId": received[0][3]["clientId"], **expected}
        assert "factor" not in capsys.readouterr().out
        assert load_client_config(path)["clientProof"] == "proof-1"


def test_mfa_relogin_reuses_proof_but_does_not_expose_it(tmp_path, monkeypatch, capsys):
    path = tmp_path / "client.json"
    monkeypatch.setattr("oai_core.cli.sys.stdin.isatty", lambda: True)
    monkeypatch.setattr("builtins.input", lambda _: "1")
    values = iter(["password", "factor"])
    monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: next(values))
    with account_http("required") as (url, received, _):
        save_client_config(path, _account_config(url))
        assert main(["--client-config", str(path), "login", "--url", url, "--username", "alice"]) == 0
        assert received[0][3]["clientProof"] == "proof-1"
        assert received[1][3] == {"challengeId": "challenge-1", "clientId": "client-1",
                                  "clientProof": "proof-1", "otp": "factor"}
        assert "proof-1" not in capsys.readouterr().out


def test_mfa_enrollment_requires_terminal_and_confirmation(tmp_path, monkeypatch, capsys):
    path = tmp_path / "client.json"
    monkeypatch.setattr("oai_core.cli.sys.stdin.isatty", lambda: True)
    monkeypatch.setattr("oai_core.cli.sys.stdout.isatty", lambda: True)
    values = iter(["password", "123456"])
    monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: next(values))
    monkeypatch.setattr("builtins.input", lambda _: "saved")
    with account_http("enroll") as (url, received, _):
        assert main(["--client-config", str(path), "login", "--url", url, "--username", "alice"]) == 0
        assert [entry[1] for entry in received] == ["/tokens", "/mfa/enroll", "/mfa/confirm", "/tokens/complete"]
        assert received[2][3] == {"challengeId": "challenge-1", "enrollmentId": "enroll-1", "otp": "123456"}
        assert received[3][3] == {"challengeId": "challenge-1", "clientId": received[0][3]["clientId"]}
        assert "recovery-secret-1" in capsys.readouterr().out
        assert "recovery-secret" not in path.read_text()


def test_mfa_enrollment_refuses_redirected_output_before_request(tmp_path, monkeypatch, capsys):
    path = tmp_path / "client.json"
    monkeypatch.setattr("oai_core.cli.sys.stdin.isatty", lambda: True)
    monkeypatch.setattr("oai_core.cli.sys.stdout.isatty", lambda: False)
    monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: "password")
    with account_http("enroll") as (url, received, _):
        assert main(["--client-config", str(path), "login", "--url", url, "--username", "alice"]) == 1
        out, err = capsys.readouterr()
        assert "TERMINAL_REQUIRED" in err
        assert "setup-secret" not in out and "recovery-secret" not in out
        assert [entry[1] for entry in received] == ["/tokens"]
        assert not path.exists()


def test_refresh_response_loss_uses_same_operation_after_restart(tmp_path):
    path = tmp_path / "client.json"
    with account_http() as (url, received, state):
        save_client_config(path, _account_config(url))
        state["drop_refresh"] = True
        with pytest.raises(CoreClientError, match="CORE_UNREACHABLE"):
            CoreClient.from_config(path).devices()
        saved = load_client_config(path)
        assert saved["refreshCredential"] == "refresh-old"
        operation = saved["refreshOperationId"]
        assert CoreClient.from_config(path).devices()["devices"] == []
        refreshes = [entry for entry in received if entry[1] == "/tokens/refresh"]
        assert len(refreshes) == 2
        assert refreshes[0][3] == refreshes[1][3] == {
            "refreshCredential": "refresh-old", "clientId": "client-1", "operationId": operation}
        assert len(state["refreshes"]) == 1
        saved = load_client_config(path)
        assert saved["refreshCredential"] == "refresh-new"
        assert saved["clientProof"] == "proof-1"
        assert "refreshOperationId" not in saved
        assert received[-1][2]["Authorization"] == "Bearer access-new"


def test_cross_process_refresh_is_serialized(tmp_path):
    path = tmp_path / "client.json"
    code = ("from pathlib import Path; from oai_core.client import CoreClient; "
            "import sys; CoreClient.from_config(Path(sys.argv[1])).devices()")
    with account_http() as (url, received, _):
        save_client_config(path, _account_config(url))
        processes = [subprocess.Popen([sys.executable, "-c", code, str(path)],
                     stdout=subprocess.PIPE, stderr=subprocess.PIPE) for _ in range(4)]
        for process in processes:
            out, err = process.communicate(timeout=20)
            assert process.returncode == 0, (out, err)
        assert len([entry for entry in received if entry[1] == "/tokens/refresh"]) == 1
        assert len([entry for entry in received if entry[1] == "/devices"]) == 4
        assert load_client_config(path)["refreshCredential"] == "refresh-new"


def test_pending_invoke_retry_preserves_key_and_target(tmp_path):
    path = tmp_path / "client.json"
    with account_http() as (url, received, state):
        save_client_config(path, _account_config(url, expiresAt="2099-01-01T00:00:00Z"))
        state["drop_invoke"] = True
        kwargs = {"agent_name": "test", "purpose": "test", "device_id": "phone-1"}
        with pytest.raises(CoreClientError, match="CORE_UNREACHABLE") as failure:
            CoreClient.from_config(path).invoke("screen.read", "1", {}, **kwargs)
        key = failure.value.details["idempotencyKey"]
        assert key in load_client_config(path)["pendingInvocations"].values()
        assert CoreClient.from_config(path).invoke("screen.read", "1", {}, **kwargs)["requestId"] == "request-1"
        calls = [entry for entry in received if entry[1] == "/device-requests"]
        assert len(calls) == 2 and calls[0][2]["Idempotency-Key"] == calls[1][2]["Idempotency-Key"] == key
        assert calls[0][3] == calls[1][3] and calls[1][3]["deviceId"] == "phone-1"
        assert not load_client_config(path)["pendingInvocations"]


def test_offline_logout_blocks_existing_and_new_clients_until_revocation(tmp_path):
    path = tmp_path / "client.json"
    with account_http() as (url, received, state):
        save_client_config(path, _account_config(url, refreshOperationId="unfinished-refresh"))
        client = CoreClient.from_config(path)
        state["drop_revoke"] = True
        assert client.logout() == {"authenticated": False, "remoteRevocationPending": True, "reason": "CORE_UNREACHABLE"}
        assert load_client_config(path)["loggedOut"] is True
        with pytest.raises(CoreClientError, match="LOCAL_LOGGED_OUT"):
            client.devices()
        with pytest.raises(CoreClientError, match="LOCAL_LOGGED_OUT"):
            CoreClient.from_config(path)
        assert [entry[1] for entry in received] == ["/tokens/revoke"]
        assert received[0][3] == {"refreshCredential": "refresh-old", "clientId": "client-1"}
        state["drop_revoke"] = False
        assert CoreClient.from_config(path, allow_logged_out=True).logout() == {
            "authenticated": False, "remoteRevocationPending": False}
        assert not any(key in load_client_config(path) for key in ("token", "refreshCredential", "refreshOperationId"))
        assert [entry[1] for entry in received] == ["/tokens/revoke", "/tokens/revoke"]


def test_stale_client_cannot_logout_new_login(tmp_path):
    path = tmp_path / "client.json"
    with account_http() as (url, received, _):
        save_client_config(path, _account_config(url))
        old_client = CoreClient.from_config(path)
        save_client_config(path, _account_config(url, loginSessionId="new-session", token="new-session-token"))
        with pytest.raises(CoreClientError, match="SESSION_CHANGED"):
            old_client.logout()
        assert load_client_config(path).get("loggedOut") is not True
        assert received == []


def test_login_discovers_instance_and_mcp_does_not_prompt(tmp_path, monkeypatch):
    import types
    module = types.ModuleType("oai_core.instance")
    calls = []
    monkeypatch.setattr("oai_core.cli.sys.stdin.isatty", lambda: True)
    monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: "password")
    with account_http() as (url, received, _):
        module.discover_instance = lambda config=None: calls.append(config) or {"url": url}
        monkeypatch.setitem(sys.modules, "oai_core.instance", module)
        path = tmp_path / "client.json"
        assert main(["--client-config", str(path), "login", "--username", "alice"]) == 0
        assert calls == [None] and received[0][1] == "/tokens"

        def no_secret(*args, **kwargs):
            raise AssertionError("MCP must never ask for credentials")
        monkeypatch.setattr("oai_core.cli.getpass.getpass", no_secret)
        monkeypatch.setattr("builtins.input", no_secret)
        save_client_config(path, _account_config(url, expiresAt="2099-01-01T00:00:00Z"))
        client = CoreClient.from_config(path)
        client.logout()
        async def scenario():
            server = create_mcp(client)
            with pytest.raises(Exception, match="LOCAL_LOGGED_OUT"):
                await server.call_tool("oai_devices", {})
        asyncio.run(scenario())


def test_admin_cli_uses_username_identity_store_and_real_management_contract(tmp_path, monkeypatch, capsys):
    import types
    import oai_core
    config = tmp_path / "core.json"
    config.write_text(json.dumps({"storageRoot": "runtime/accounts", "masterKeyFile": "runtime/key"}))
    database = tmp_path / "runtime" / "security" / "identity.sqlite"
    instance = {"configPath": str(config), "url": "http://127.0.0.1:11451",
                "storageRoot": str(tmp_path / "runtime" / "accounts"), "identityDatabase": str(database)}
    events = []

    class Challenge(Exception):
        code = "MFA_REQUIRED"
        details = {"challengeId": "manage-challenge", "methods": ["totp", "recovery-code"]}

    class Store:
        def __init__(self, path, *, key_store=None, create=False):
            assert path == database and create is False and key_store is not None
            self.calls = 0
        def begin_management(self, username, password, **kwargs):
            events.append(("auth", username, password, kwargs))
            if state["mfa"] and not kwargs:
                raise Challenge()
            return "management-handle"
        def create_account(self, handle, username, password):
            events.append(("create", handle, username, password))
            return {"accountId": "opaque-id", "created": True}
        def list_accounts(self, handle):
            events.append(("list", handle))
            return {"accounts": [{"accountId": "opaque-id", "username": "new-user"}]}
        def revoke_management(self, handle):
            events.append(("revoke", handle))
        def close(self):
            events.append(("close",))

    identity = types.ModuleType("oai_core.identity")
    identity.IdentityStore = Store
    discover = types.ModuleType("oai_core.instance")
    discover.discover_instance = lambda value=None: instance
    configuration = types.ModuleType("oai_core.configuration")
    configuration.run_config = lambda found, store, handle: events.append(("config", found, handle)) or {"changed": True}
    configuration.run_setup = lambda found, store, handle: events.append(("setup", found, handle)) or {"completed": True}
    monkeypatch.setitem(sys.modules, "oai_core.identity", identity)
    monkeypatch.setitem(sys.modules, "oai_core.instance", discover)
    monkeypatch.setitem(sys.modules, "oai_core.configuration", configuration)
    monkeypatch.setattr(oai_core, "identity", identity, raising=False)
    monkeypatch.setattr(oai_core, "instance", discover, raising=False)
    monkeypatch.setattr(oai_core, "configuration", configuration, raising=False)
    monkeypatch.setattr("oai_core.cli.sys.stdin.isatty", lambda: True)
    state = {"mfa": False}
    answers = iter(["administrator"])
    monkeypatch.setattr("builtins.input", lambda _: next(answers))
    secrets = iter(["admin-password", "new-password", "new-password"])
    monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: next(secrets))
    assert main(["account", "create", "new-user", "--config", str(config)]) == 0
    assert ("create", "management-handle", "new-user", "new-password") in events
    assert events[-2:] == [("revoke", "management-handle"), ("close",)]
    assert "password" not in capsys.readouterr().out

    state["mfa"] = True
    answers = iter(["administrator", "2"])
    secrets = iter(["admin-password", "recovery-factor"])
    assert main(["account", "list", "--config", str(config)]) == 0
    assert ("auth", "administrator", "admin-password", {"challenge_id": "manage-challenge",
            "otp": None, "recovery_code": "recovery-factor"}) in events
    assert ("list", "management-handle") in events
    capsys.readouterr()

    state["mfa"] = False
    for command in ("config", "setup"):
        answers = iter(["administrator"])
        secrets = iter(["admin-password"])
        assert main([command, "--config", str(config)]) == 0
        assert (command, instance, "management-handle") in events
        capsys.readouterr()


def test_real_identity_store_with_cli_admin_and_optional_mfa(tmp_path, monkeypatch, capsys):
    # Main integration dependencies live in an isolated overlay in this
    # worktree; this test uses the real identity/configuration implementations.
    from oai_core.identity import IdentityStore, issue_bootstrap_proof
    from oai_core.gateway.local_keys import LocalMasterKeyStore, create_master_key_file
    from oai_core.management import LocalRecovery
    from oai_core.instance import discover_instance
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.twofactor.totp import TOTP
    import base64
    import time

    runtime = tmp_path / "runtime"
    runtime.mkdir()
    database, storage, key = runtime / "security" / "identity.sqlite", runtime / "accounts", runtime / "key"
    create_master_key_file(key)
    read_fd, write_fd = os.pipe()
    os.write(write_fd, b"bootstrap\n")
    os.close(write_fd)
    try:
        proof = issue_bootstrap_proof(database, storage, confirmation_fd=read_fd)
    finally:
        os.close(read_fd)
    store = IdentityStore(database, key_store=LocalMasterKeyStore(key), create=True, bootstrap_proof=proof)
    admin = store.bootstrap("admin", "admin-password", confirmed=True)
    config = tmp_path / "core.json"
    config.write_text(json.dumps({"storageRoot": "runtime/accounts", "masterKeyFile": "runtime/key",
                                  "listen": {"host": "127.0.0.1", "port": 11451}, "agents": []}))
    monkeypatch.setenv("HOME", str(tmp_path))
    assert discover_instance(config)["identityDatabase"] == str(database)
    monkeypatch.setattr("oai_core.cli.sys.stdin.isatty", lambda: True)
    answers = iter(["admin"])
    secrets = iter(["admin-password", "alice-password", "alice-password"])
    monkeypatch.setattr("builtins.input", lambda _: next(answers))
    monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: next(secrets))
    try:
        assert main(["account", "create", "alice", "--config", str(config)]) == 0
        user = store.authenticate("alice", "alice-password")
        assert user["username"] == "alice" and user["accountId"] != "alice"
        assert not user["mfaVerified"]
        assert "password" not in capsys.readouterr().out
        # Configure and setup exit without changing the saved config.
        before = config.read_bytes()
        for command, inputs in (("config", ["admin", "", ""]), ("setup", ["admin", "5"])):
            answers = iter(inputs)
            secrets = iter(["admin-password"])
            assert main([command, "--config", str(config)]) == 0
            assert config.read_bytes() == before
            capsys.readouterr()
        enrollment = store.authenticate("admin", "admin-password", purpose="enroll")
        challenge = enrollment["enrollmentProof"]
        pending = store.begin_mfa_enrollment(admin["accountId"], challenge)
        raw = base64.b32decode(pending["secret"] + "=" * (-len(pending["secret"]) % 8))
        otp = TOTP(raw, 6, hashes.SHA1(), 30).generate(time.time()).decode()
        codes = store.confirm_mfa_enrollment(admin["accountId"], challenge, otp,
                    enrollment_id=pending["enrollmentId"])["recoveryCodes"]
        store.complete_authentication(challenge, purpose="enroll")
        handle = store.begin_management("admin", "admin-password")
        LocalRecovery(store).register(handle, confirmed=True)
        store.set_mfa_required(handle, True)
        answers = iter(["admin", "2"])
        secrets = iter(["admin-password", codes[0]])
        assert main(["account", "list", "--config", str(config)]) == 0
        assert any(account["username"] == "alice" for account in json.loads(capsys.readouterr().out)["accounts"])
    finally:
        store.close()


@pytest.mark.asyncio
async def test_cli_against_real_core_http_with_optional_mfa(tmp_path, monkeypatch, capsys):
    from aiohttp.test_utils import TestServer
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.twofactor.totp import TOTP
    from oai_core.gateway.local_keys import LocalMasterKeyStore, create_master_key_file
    from oai_core.identity import IdentityStore, issue_bootstrap_proof
    from oai_core.management import LocalRecovery
    from oai_core.server import create_app, create_core
    from oai_core.sessions import AgentRegistry
    import base64
    import time

    runtime = tmp_path / "runtime"
    runtime.mkdir()
    database, storage, key = runtime / "security" / "identity.sqlite", runtime / "accounts", runtime / "key"
    create_master_key_file(key)
    read_fd, write_fd = os.pipe()
    os.write(write_fd, b"bootstrap\n")
    os.close(write_fd)
    try:
        proof = issue_bootstrap_proof(database, storage, confirmation_fd=read_fd)
    finally:
        os.close(read_fd)
    store = IdentityStore(database, key_store=LocalMasterKeyStore(key), create=True, bootstrap_proof=proof)
    admin = store.bootstrap("admin", "admin-password", confirmed=True)
    settings = {"storageRoot": str(storage), "masterKeyFile": str(key),
                "contractRoot": str(Path(__file__).parents[2] / "gateway-contract")}
    core = create_core(settings)
    server = TestServer(create_app(settings, registry=AgentRegistry(), core=core))
    await server.start_server()
    url = str(server.make_url("/")).rstrip("/")
    client_path = tmp_path / "client.json"
    monkeypatch.setattr("oai_core.cli.sys.stdin.isatty", lambda: True)
    monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: "admin-password")
    try:
        args = ["--client-config", str(client_path), "login", "--url", url, "--username", "admin"]
        assert await asyncio.to_thread(main, args) == 0
        capsys.readouterr()
        client = CoreClient.from_config(client_path)
        devices = await asyncio.to_thread(client.devices)
        assert devices["devices"] == []
        saved = load_client_config(client_path)
        assert saved["accountId"] == admin["accountId"] and saved["clientProof"]
        assert await asyncio.to_thread(client.logout) == {"authenticated": False, "remoteRevocationPending": False}

        enrollment = store.authenticate("admin", "admin-password", purpose="enroll")
        challenge = enrollment["enrollmentProof"]
        pending = store.begin_mfa_enrollment(admin["accountId"], challenge)
        raw = base64.b32decode(pending["secret"] + "=" * (-len(pending["secret"]) % 8))
        otp = TOTP(raw, 6, hashes.SHA1(), 30).generate(time.time()).decode()
        codes = store.confirm_mfa_enrollment(admin["accountId"], challenge, otp,
                    enrollment_id=pending["enrollmentId"])["recoveryCodes"]
        store.complete_authentication(challenge, purpose="enroll")
        handle = store.begin_management("admin", "admin-password")
        LocalRecovery(store).register(handle, confirmed=True)
        store.set_mfa_required(handle, True)
        monkeypatch.setattr("builtins.input", lambda _: "2")
        secrets = iter(["admin-password", codes[0]])
        monkeypatch.setattr("oai_core.cli.getpass.getpass", lambda _: next(secrets))
        assert await asyncio.to_thread(main, args) == 0
        assert await asyncio.to_thread(CoreClient.from_config(client_path).devices) == {"devices": [], "defaultDeviceId": None}
        assert "admin-password" not in capsys.readouterr().out
    finally:
        await server.close()
        store.close()
        core.identity_store.close()


def test_ambiguous_instance_is_reported_without_prompt(tmp_path, monkeypatch, capsys):
    import types
    from oai_core.gateway.core import GatewayError
    discover = types.ModuleType("oai_core.instance")
    def ambiguous(config=None):
        raise GatewayError("INSTANCE_REQUIRED", {"configPaths": ["first/core.json", "second/core.json"]})
    discover.discover_instance = ambiguous
    monkeypatch.setitem(sys.modules, "oai_core.instance", discover)
    monkeypatch.setattr("oai_core.cli._secret_input", lambda _: pytest.fail("must not ask for password"))
    assert main(["--client-config", str(tmp_path / "client.json"), "login", "--username", "alice"]) == 1
    failure = json.loads(capsys.readouterr().err)["error"]
    assert failure["code"] == "INSTANCE_REQUIRED"
    assert failure["details"]["configPaths"] == ["first/core.json", "second/core.json"]


def test_legacy_policy_commands_remain_retired(capsys):
    assert main(["policy", "set", "--config", "core.json", "--account", "bob",
                 "--capability", "org.openandroidintelligence.sms.query", "--disabled"]) == 1
    assert "DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID" in capsys.readouterr().err
