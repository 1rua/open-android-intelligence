"""Exercise plugin repair through the authenticated setup menu and real HTTP handshake."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import sys
from threading import Thread
from types import SimpleNamespace

import pytest

from oai_core import configuration
from oai_core.agent_registration import AgentRegistration, bridge_proof
from test_dsh_setup import TOKEN, configure_fixture, deployment


@contextmanager
def host_bridge(kind, *, status=503):
    scope = "account" if kind == "hermes" else "deployment"
    state = SimpleNamespace(current=False, handshakes=0)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            state.handshakes += 1
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if state.current:
                capabilities = {"list": True, "history": True}
                result = {"instanceId": kind, "sessionScope": scope, "online": True,
                          "capabilities": capabilities,
                          "proof": bridge_proof(TOKEN, body["challenge"], kind, scope, True, capabilities)}
                response_status = 200
            else:
                result, response_status = {"code": "NOT_READY"}, status
            payload = json.dumps(result).encode()
            self.send_response(response_status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield state, f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def arrange_candidate(root, kind, url, monkeypatch, answers):
    entry = {"id": kind, "type": kind, "instanceId": kind,
             "sessionScope": "account" if kind == "hermes" else "deployment",
             "url": url, "secretFile": "bridge.secret"}
    directory = root / "runtime/agent-candidates"
    directory.mkdir(mode=0o700)
    (directory / f"{kind}.json").write_text(json.dumps(entry))
    configure_fixture(root, monkeypatch, entry, iter(answers))
    monkeypatch.setattr(configuration, "restart_hermes_service_if_active", lambda: False)
    monkeypatch.setattr(configuration, "_confirm_operator_hermes_mapping", lambda *_: None)
    return entry


def arrange_installer(kind, root, monkeypatch, install):
    host = root / "host"
    host.mkdir()
    monkeypatch.setattr(configuration, "detect_host_agents", lambda: {kind: [host]})
    if kind == "hermes":
        class InstallError(ValueError):
            code = "HERMES_INSTALL_FAILED"
        monkeypatch.setitem(sys.modules, "oai_core.hermes_installation", SimpleNamespace(
            HermesAdapterInstallError=InstallError, install_hermes_adapter=install))
        monkeypatch.setattr(configuration, "_is_local_hermes_bridge", lambda _, **_kwargs: True, raising=False)
    else:
        from oai_core import dsh_installation
        profile = dsh_installation.DshProfile(host, "web", host / "profiles/web")
        def discover(homes, *, include_uninstalled=False):
            assert homes == [host]
            assert include_uninstalled
            return [profile]
        monkeypatch.setattr(dsh_installation, "discover_dsh_profiles", discover)
        monkeypatch.setattr(dsh_installation, "install_dsh_adapter", install)
        monkeypatch.setattr(configuration, "_is_local_dsh_bridge", lambda _: True)


@pytest.mark.parametrize("kind", ["hermes", "dsh"])
@pytest.mark.parametrize("ready_after_install", [True, False], ids=["reloaded", "reload-pending"])
def test_setup_installs_missing_plugin_then_requires_a_real_handshake(
        deployment, monkeypatch, capsys, kind, ready_after_install):
    root, store, handle, operator = deployment
    calls = []
    with host_bridge(kind) as (host, url):
        def install(actual_root, actual_store, actual_handle, target, **kwargs):
            assert (actual_root, actual_store, actual_handle) == (root, store, handle)
            assert AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries() == []
            calls.append(target)
            host.current = ready_after_install
        arrange_installer(kind, root, monkeypatch, install)
        answers = ["1", kind, "y" if kind == "hermes" else "1"]
        arrange_candidate(root, kind, url, monkeypatch, answers)
        configuration._configure_agents(root, store, handle, operator)
        assert len(calls) == 1, "setup 必须实际调用所选宿主的插件安装流程"
        assert host.handshakes == 2, "插件安装成功后必须重新验证真实运行中的会话桥"
    entries = AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries()
    assert [entry["id"] for entry in entries] == ([kind] if ready_after_install else [])
    output = capsys.readouterr().out
    assert ("Agent 已通过真实握手并注册" in output) is ready_after_install
    if not ready_after_install:
        assert "尚未注册" in output and "重新加载" in output


@pytest.mark.parametrize("kind", ["hermes", "dsh"])
def test_declining_plugin_installation_keeps_registration_pending(deployment, monkeypatch, capsys, kind):
    root, store, handle, operator = deployment
    with host_bridge(kind) as (host, url):
        arrange_installer(kind, root, monkeypatch,
                          lambda *_args, **_kwargs: pytest.fail("取消安装不得调用安装器"))
        arrange_candidate(root, kind, url, monkeypatch, ["1", kind, ""])
        configuration._configure_agents(root, store, handle, operator)
        assert host.handshakes == 1
    assert AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries() == []
    assert "已取消" in capsys.readouterr().out


@pytest.mark.parametrize("kind", ["hermes", "dsh"])
def test_remote_or_custom_bridge_failure_never_installs_local_plugins(deployment, monkeypatch, kind):
    root, store, handle, operator = deployment
    with host_bridge(kind) as (_, url):
        arrange_installer(kind, root, monkeypatch,
                          lambda *_args, **_kwargs: pytest.fail("自定义候选不得安装本机插件"))
        arrange_candidate(root, kind, url, monkeypatch, ["1", kind])
        monkeypatch.setattr(configuration, f"_is_local_{kind}_bridge", lambda _, **_kwargs: False, raising=False)
        configuration._configure_agents(root, store, handle, operator)
    assert AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries() == []


@pytest.mark.parametrize("kind", ["hermes", "dsh"])
@pytest.mark.parametrize("status", [401, 403, 422])
def test_invalid_credentials_or_response_do_not_offer_installation(deployment, monkeypatch, capsys, kind, status):
    root, store, handle, operator = deployment
    with host_bridge(kind, status=status) as (host, url):
        arrange_installer(kind, root, monkeypatch,
                          lambda *_args, **_kwargs: pytest.fail("认证失败或无效响应不得触发安装"))
        arrange_candidate(root, kind, url, monkeypatch, ["1", kind])
        configuration._configure_agents(root, store, handle, operator)
        assert host.handshakes == 1
    assert AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries() == []
    assert "Agent 已通过真实握手并注册" not in capsys.readouterr().out


@pytest.mark.parametrize("kind", ["hermes", "dsh"])
def test_healthy_bridge_registers_without_reinstalling(deployment, monkeypatch, kind):
    root, store, handle, operator = deployment
    with host_bridge(kind) as (host, url):
        host.current = True
        arrange_installer(kind, root, monkeypatch,
                          lambda *_args, **_kwargs: pytest.fail("健康插件不得重复安装"))
        arrange_candidate(root, kind, url, monkeypatch, ["1", kind])
        configuration._configure_agents(root, store, handle, operator)
        assert host.handshakes == 1
    entries = AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries()
    assert [entry["id"] for entry in entries] == [kind]


@pytest.mark.parametrize("url,instance,prefix,expected", [
    ("http://127.0.0.1:9012", "custom-hermes", None, True),
    ("http://[::1]:9012/", "custom-hermes", None, True),
    ("http://localhost:9012", "custom-hermes", None, True),
    ("https://remote.invalid:9012", "custom-hermes", None, False),
    ("http://127.0.0.1:8792", "custom-hermes", None, False),
    ("http://127.0.0.1:9012", "other-hermes", None, False),
    ("http://127.0.0.1:9012", "custom-hermes", "/custom", False),
    ("http://user@127.0.0.1:9012", "custom-hermes", None, False),
    ("http://127.0.0.1:9012?query=1", "custom-hermes", None, False),
])
def test_hermes_install_guard_matches_detected_host_configuration(tmp_path, monkeypatch, url, instance, prefix, expected):
    home = tmp_path / "hermes"
    home.mkdir()
    (home / ".env").write_text("OAI_HERMES_SESSION_PORT=9012\nOAI_HERMES_INSTANCE_ID=custom-hermes\n")
    monkeypatch.setattr(configuration, "detect_host_agents", lambda: {"hermes": [home]})
    entry = {"id": "hermes", "type": "hermes", "instanceId": instance, "sessionScope": "account", "url": url}
    if prefix is not None:
        entry["prefix"] = prefix
    assert configuration._is_local_hermes_bridge(entry) is expected


@pytest.mark.parametrize("kind", ["hermes", "dsh"])
def test_installation_failure_returns_to_main_menu_without_registering(deployment, monkeypatch, capsys, kind):
    root, store, handle, operator = deployment
    calls = []
    def install(*_args, **_kwargs):
        calls.append(kind)
        if kind == "hermes":
            raise sys.modules["oai_core.hermes_installation"].HermesAdapterInstallError("安装失败")
        from oai_core.dsh_installation import DshAdapterInstallError
        raise DshAdapterInstallError("DSH_INSTALL_FAILED")
    with host_bridge(kind) as (host, url):
        arrange_installer(kind, root, monkeypatch, install)
        arrange_candidate(root, kind, url, monkeypatch, ["3", "1", kind, "y" if kind == "hermes" else "1", "5"])
        configuration.run_setup({"configPath": str(root / "core.json")}, store, handle)
        assert calls == [kind]
        assert host.handshakes == 1
    assert AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries() == []
    output = capsys.readouterr().out
    assert output.count("3. Agent 接入") == 2
    assert "尚未注册" in output and "Agent 已通过真实握手并注册" not in output


@pytest.mark.parametrize("change", ["different-home", "changed-configuration"])
def test_hermes_installation_rechecks_the_confirmed_host(deployment, monkeypatch, change):
    root, store, handle, _ = deployment
    guard = configuration._is_local_hermes_bridge
    calls = []
    arrange_installer("hermes", root, monkeypatch, lambda *_args, **_kwargs: calls.append(_args[-1]))
    monkeypatch.setattr(configuration, "_is_local_hermes_bridge", guard)
    first, second = root / "host", root / "other-host"
    second.mkdir()
    (first / ".env").write_text("OAI_HERMES_INSTANCE_ID=hermes\nOAI_HERMES_SESSION_PORT=8792\n")
    (second / ".env").write_text("OAI_HERMES_INSTANCE_ID=other-hermes\nOAI_HERMES_SESSION_PORT=9876\n")
    discoveries = []
    def detect():
        discoveries.append(True)
        return {"hermes": [second if change == "different-home" and len(discoveries) > 1 else first]}
    monkeypatch.setattr(configuration, "detect_host_agents", detect)
    def confirm(_):
        if change == "changed-configuration":
            (first / ".env").write_text("OAI_HERMES_INSTANCE_ID=other-hermes\nOAI_HERMES_SESSION_PORT=9876\n")
        return "y"
    monkeypatch.setattr(configuration, "_terminal_input", confirm)
    entry = {"id": "hermes", "type": "hermes", "instanceId": "hermes", "sessionScope": "account",
             "url": "http://127.0.0.1:8792", "secretFile": "bridge.secret"}
    assert configuration._upgrade_hermes_bridge(root, store, handle, entry) is False
    assert calls == []
