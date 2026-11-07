"""DSH setup keeps old bridges unregistered until the upgraded host proves identity."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
from threading import Thread
from types import SimpleNamespace

import pytest

from oai_core import configuration
from oai_core.agent_registration import AgentRegistration, bridge_proof
from oai_core.identity import IdentityStore, issue_bootstrap_proof

TOKEN = "synthetic-dsh-setup-token-12345678901234567890"


@pytest.fixture
def deployment(tmp_path, monkeypatch):
    root = tmp_path / "core"
    root.mkdir()
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("DSH_HOME", str(home / "no-dsh"))
    monkeypatch.setenv("HERMES_HOME", str(home / "no-hermes"))
    database = root / "runtime/security/identity.sqlite"
    storage = root / "runtime/accounts"
    read_fd, write_fd = os.pipe()
    try:
        os.write(write_fd, b"bootstrap\n")
        os.close(write_fd)
        write_fd = -1
        proof = issue_bootstrap_proof(database, storage, confirmation_fd=read_fd)
    finally:
        os.close(read_fd)
        if write_fd >= 0:
            os.close(write_fd)
    store = IdentityStore(database, create=True, bootstrap_proof=proof)
    try:
        store.bootstrap("alice", "synthetic-admin-password", confirmed=True)
        handle = store.begin_management("alice", "synthetic-admin-password")
        (root / "core.json").write_text(json.dumps({"storageRoot": "runtime/accounts", "agents": []}))
        storage.mkdir(parents=True, exist_ok=True, mode=0o700)
        secret = storage / "bridge.secret"
        secret.write_text(TOKEN)
        secret.chmod(0o600)
        operator = store.require_management(handle, "agents.manage")
        yield root, store, handle, operator
    finally:
        store.close()


@contextmanager
def bridge():
    state = SimpleNamespace(current=False, handshakes=0)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            state.handshakes += 1
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.headers.get("Authorization") != "Bearer " + TOKEN:
                status, result = 401, {"code": "AUTHENTICATION_REQUIRED"}
            elif not state.current:
                status, result = 404, {"code": "NOT_FOUND"}
            else:
                capabilities = {"list": True, "history": True}
                status, result = 200, {"instanceId": "dsh", "sessionScope": "deployment", "online": True,
                    "capabilities": capabilities, "proof": bridge_proof(TOKEN, body["challenge"], "dsh", "deployment", True, capabilities)}
            payload = json.dumps(result).encode()
            self.send_response(status)
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


def candidate(root, url):
    entry = {"id": "dsh", "type": "dsh", "instanceId": "dsh", "sessionScope": "deployment", "url": url, "secretFile": "bridge.secret"}
    directory = root / "runtime/agent-candidates"
    directory.mkdir(mode=0o700)
    (directory / "dsh.json").write_text(json.dumps(entry))
    return entry


def configure_fixture(root, monkeypatch, entry, answers):
    monkeypatch.setattr(configuration, "provision_host_candidates", lambda *_: [entry])
    monkeypatch.setattr(configuration, "_terminal_input", lambda _: next(answers))


def test_legacy_bridge_upgrade_registers_only_after_real_new_handshake(deployment, tmp_path, monkeypatch, capsys):
    from oai_core import dsh_installation
    root, store, handle, operator = deployment
    profiles = [dsh_installation.DshProfile(tmp_path / name, "web", tmp_path / name / "profiles/web") for name in ("first", "second")]
    monkeypatch.setattr(dsh_installation, "discover_dsh_profiles", lambda _: profiles)
    calls = []
    with bridge() as (host, url):
        entry = candidate(root, url)
        monkeypatch.setattr(configuration, "_is_local_dsh_bridge", lambda _: True)
        configure_fixture(root, monkeypatch, entry, iter(["1", "dsh", "2"]))
        def install(actual_root, actual_store, actual_handle, profile):
            assert (actual_root, actual_store, actual_handle) == (root, store, handle)
            assert host.handshakes == 1
            assert AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries() == []
            calls.append(profile)
            host.current = True
        monkeypatch.setattr(dsh_installation, "install_dsh_adapter", install)
        configuration._configure_agents(root, store, handle, operator)
        assert host.handshakes == 2
    assert calls == [profiles[1]]
    registered = AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries()
    assert [item["id"] for item in registered] == ["dsh"]
    output = capsys.readouterr().out
    assert "Agent 已通过真实握手并注册" in output
    assert TOKEN not in output


def test_cancel_upgrade_preserves_menu_and_does_not_register(deployment, tmp_path, monkeypatch, capsys):
    from oai_core import dsh_installation
    root, store, handle, operator = deployment
    profile = dsh_installation.DshProfile(tmp_path, "web", tmp_path / "profiles/web")
    monkeypatch.setattr(dsh_installation, "discover_dsh_profiles", lambda _: [profile])
    monkeypatch.setattr(dsh_installation, "install_dsh_adapter", lambda *_: pytest.fail("cancelled upgrade must not run"))
    with bridge() as (_, url):
        entry = candidate(root, url)
        monkeypatch.setattr(configuration, "_is_local_dsh_bridge", lambda _: True)
        configure_fixture(root, monkeypatch, entry, iter(["1", "dsh", ""]))
        configuration._configure_agents(root, store, handle, operator)
    assert AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries() == []
    output = capsys.readouterr().out
    assert "尚未注册" in output
    assert "Agent 已通过真实握手并注册" not in output


def test_installed_bundle_still_requires_reloaded_host_before_registration(deployment, tmp_path, monkeypatch, capsys):
    from oai_core import dsh_installation
    root, store, handle, operator = deployment
    profile = dsh_installation.DshProfile(tmp_path, "web", tmp_path / "profiles/web")
    monkeypatch.setattr(dsh_installation, "discover_dsh_profiles", lambda _: [profile])
    monkeypatch.setattr(dsh_installation, "install_dsh_adapter", lambda *_: None)
    with bridge() as (host, url):
        entry = candidate(root, url)
        monkeypatch.setattr(configuration, "_is_local_dsh_bridge", lambda _: True)
        configure_fixture(root, monkeypatch, entry, iter(["1", "dsh", "1"]))
        configuration._configure_agents(root, store, handle, operator)
        assert host.handshakes == 2
    assert AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries() == []
    output = capsys.readouterr().out
    assert "重新加载" in output and "尚未注册" in output
    assert "Agent 已通过真实握手并注册" not in output


def test_automatic_discovery_reports_old_bridge_without_mutating_profiles(deployment, monkeypatch, capsys):
    root, store, handle, operator = deployment
    with bridge() as (_, url):
        entry = candidate(root, url)
        monkeypatch.setattr(configuration, "detect_host_agents", lambda: {"dsh": [root / "host"]})
        monkeypatch.setattr(configuration, "provision_host_candidates", lambda *_: [entry])
        assert configuration.auto_connect_host_agents(root, store, handle, operator) == []
    output = capsys.readouterr().out
    assert "AGENT_BRIDGE_UPGRADE_REQUIRED" in output
    assert "setup" in output
    assert "待该 Agent 服务运行" not in output


@pytest.mark.parametrize("url,prefix,expected", [("http://127.0.0.1:8791", None, True), ("https://remote.invalid", None, False), ("http://127.0.0.1:9000", None, False), ("http://127.0.0.1:8791", "/custom", False)])
def test_local_upgrade_guard_rejects_unrelated_endpoints(url, prefix, expected):
    entry = {"id": "dsh", "type": "dsh", "instanceId": "dsh", "sessionScope": "deployment", "url": url}
    if prefix is not None:
        entry["prefix"] = prefix
    assert configuration._is_local_dsh_bridge(entry) is expected


def test_setup_returns_to_main_menu_after_legacy_upgrade_is_cancelled(deployment, tmp_path, monkeypatch, capsys):
    from oai_core import dsh_installation
    root, store, handle, operator = deployment
    profile = dsh_installation.DshProfile(tmp_path, "web", tmp_path / "profiles/web")
    monkeypatch.setattr(dsh_installation, "discover_dsh_profiles", lambda _: [profile])
    monkeypatch.setattr(dsh_installation, "install_dsh_adapter", lambda *_: pytest.fail("cancelled upgrade must not install"))
    with bridge() as (_, url):
        entry = candidate(root, url)
        monkeypatch.setattr(configuration, "_is_local_dsh_bridge", lambda _: True)
        configure_fixture(root, monkeypatch, entry, iter(["3", "1", "dsh", "", "5"]))
        configuration.run_setup({"configPath": str(root / "core.json")}, store, handle)
    output = capsys.readouterr().out
    assert output.count("3. Agent 接入") == 2
    assert "AGENT_BRIDGE_UPGRADE_REQUIRED" in output
    assert AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries() == []


def test_authentication_failure_never_installs_a_local_plugin(deployment, monkeypatch, capsys):
    from oai_core import dsh_installation
    root, store, handle, operator = deployment
    (root / "runtime/accounts/bridge.secret").write_text(TOKEN + "-wrong")
    monkeypatch.setattr(dsh_installation, "discover_dsh_profiles", lambda _: pytest.fail("authentication failure must not offer upgrade"))
    with bridge() as (_, url):
        entry = candidate(root, url)
        configure_fixture(root, monkeypatch, entry, iter(["1", "dsh"]))
        configuration._configure_agents(root, store, handle, operator)
    output = capsys.readouterr().out
    assert "AGENT_BRIDGE_AUTHENTICATION_FAILED" in output
    assert "Agent 已通过真实握手并注册" not in output
    assert AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries() == []


def test_non_default_endpoint_never_changes_local_profiles(deployment, monkeypatch, capsys):
    from oai_core import dsh_installation
    root, store, handle, operator = deployment
    monkeypatch.setattr(dsh_installation, "discover_dsh_profiles", lambda _: pytest.fail("unrelated endpoint must not offer local upgrade"))
    with bridge() as (_, url):
        entry = candidate(root, url)
        configure_fixture(root, monkeypatch, entry, iter(["1", "dsh"]))
        configuration._configure_agents(root, store, handle, operator)
    assert "不是默认本机 DSH" in capsys.readouterr().out
    assert AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries() == []


def test_upgrade_falls_back_to_uninstalled_profiles_when_enabled_profiles_are_empty(deployment, monkeypatch, tmp_path, capsys):
    from oai_core import dsh_installation
    root, store, handle, operator = deployment
    uninstalled_profile = dsh_installation.DshProfile(tmp_path, "default", tmp_path / "profiles/default")
    installed_calls = []

    def fake_discover(homes, include_uninstalled=False):
        if include_uninstalled:
            return [uninstalled_profile]
        return []

    def fake_install(root, store, handle, profile, include_uninstalled=False):
        installed_calls.append((profile.name, include_uninstalled))

    monkeypatch.setattr(dsh_installation, "discover_dsh_profiles", fake_discover)
    monkeypatch.setattr(dsh_installation, "install_dsh_adapter", fake_install)
    monkeypatch.setattr(configuration, "_is_local_dsh_bridge", lambda _: True)
    entry = candidate(root, "http://127.0.0.1:8791")
    monkeypatch.setattr(configuration, "_select_option", lambda prompt, options, default_index=0: "1")

    res = configuration._upgrade_dsh_bridge(root, store, handle, entry, allow_install=False)
    assert res is True
    assert len(installed_calls) == 1
    assert installed_calls[0] == ("default", True)
    assert "DSH 连接插件已更新" in capsys.readouterr().out


def test_upgrade_automatically_restarts_dsh_and_registers(deployment, monkeypatch, tmp_path, capsys):
    from oai_core import dsh_installation
    root, store, handle, operator = deployment
    profile = dsh_installation.DshProfile(tmp_path, "web", tmp_path / "profiles/web")
    monkeypatch.setattr(dsh_installation, "discover_dsh_profiles", lambda _: [profile])
    install_calls = []
    restart_calls = []

    def install(actual_root, actual_store, actual_handle, prof):
        install_calls.append(prof)

    with bridge() as (host, url):
        entry = candidate(root, url)
        monkeypatch.setattr(configuration, "_is_local_dsh_bridge", lambda _: True)
        monkeypatch.setattr(dsh_installation, "install_dsh_adapter", install)

        def restart(*_, **__):
            restart_calls.append(True)
            host.current = len(install_calls) == 1
            return host.current

        monkeypatch.setattr(configuration, "restart_dsh_service_if_active", restart)
        configure_fixture(root, monkeypatch, entry, iter(["1", "dsh", "1"]))
        configuration._configure_agents(root, store, handle, operator)

    assert len(install_calls) == 1
    assert len(restart_calls) >= 1
    entries = AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries()
    assert [e["id"] for e in entries] == ["dsh"]
    assert "Agent 已通过真实握手并注册" in capsys.readouterr().out


def test_restart_dsh_service_if_active_systemd(tmp_path, monkeypatch):
    monkeypatch.setattr("sys.platform", "linux")
    recorded = []

    def fake_run(cmd, *args, **kwargs):
        recorded.append(cmd)
        res = SimpleNamespace(returncode=0, stdout="active\n")
        return res

    monkeypatch.setattr("subprocess.run", fake_run)
    assert configuration.restart_dsh_service_if_active() is True
    assert any("restart" in cmd and "dsh.service" in cmd for cmd in recorded)


def test_restart_dsh_service_if_active_pid_file(tmp_path, monkeypatch):
    monkeypatch.setattr("sys.platform", "linux")
    remote_dir = tmp_path / ".dsh-app-remote"
    remote_dir.mkdir(parents=True)
    pid_file = remote_dir / "web.pid"
    cmd_file = remote_dir / "web.cmd"
    pid_file.write_text(f"{os.getpid()}\n")
    cmd_file.write_text("dsh web\n")

    killed = []
    monkeypatch.setattr(os, "kill", lambda pid, sig: killed.append((pid, sig)))
    monkeypatch.setattr("shutil.which", lambda name: f"/bin/{name}")
    spawned = []

    def fake_popen(cmd, *args, **kwargs):
        spawned.append(cmd)
        return SimpleNamespace(pid=99999, communicate=lambda timeout=None: ("99999\n", ""))

    monkeypatch.setattr("subprocess.Popen", fake_popen)
    monkeypatch.setattr(configuration, "detect_host_agents", lambda: {"dsh": [tmp_path / ".dsh"]})
    monkeypatch.setattr(Path, "home", lambda: tmp_path)

    assert configuration.restart_dsh_service_if_active(profile_home=tmp_path / ".dsh") is True
    assert len(killed) >= 1
    assert any("dsh" in str(cmd) for cmd in spawned)
    assert pid_file.read_text().strip() == "99999"

