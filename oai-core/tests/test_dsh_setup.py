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
    # These setup fixtures own only their synthetic bridge. Never restart a
    # developer's real host or inherit whether that machine has a DSH service.
    monkeypatch.setattr(configuration, "restart_dsh_service_if_active", lambda *_: False)
    monkeypatch.setattr(configuration, "restart_hermes_service_if_active", lambda: False)
    monkeypatch.setattr(configuration, "_dsh_profile_entry", lambda home, name, entry: dict(entry))
    monkeypatch.setattr(configuration, "_dsh_target_owns_endpoint", lambda _: True)
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
    state = SimpleNamespace(current=False, handshakes=0, instance_id="dsh")

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
                status, result = 200, {"instanceId": state.instance_id, "sessionScope": "deployment", "online": True,
                    "capabilities": capabilities, "proof": bridge_proof(TOKEN, body["challenge"], state.instance_id, "deployment", True, capabilities)}
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
    assert res.home == uninstalled_profile.home
    assert res.name == "default"
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


def test_restart_rejects_unrelated_active_service(tmp_path, monkeypatch):
    monkeypatch.setattr("sys.platform", "linux")
    recorded = []

    def fake_run(cmd, *args, **kwargs):
        recorded.append(cmd)
        res = SimpleNamespace(returncode=0, stdout="active\n")
        return res

    monkeypatch.setattr("subprocess.run", fake_run)
    home = tmp_path / "target-home"
    home.mkdir()
    monkeypatch.setattr(configuration, "detect_host_agents", lambda: {"dsh": [tmp_path / "other-home"]})
    assert configuration.restart_dsh_service_if_active(home, "worker") is False
    assert not any("restart" in cmd for cmd in recorded)


def test_restart_rejects_pid_file_without_process_identity(tmp_path, monkeypatch):
    monkeypatch.setattr("sys.platform", "linux")
    monkeypatch.setattr("subprocess.run", lambda *_args, **_kwargs: SimpleNamespace(returncode=1, stdout=""))
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

    assert configuration.restart_dsh_service_if_active(profile_home=tmp_path / ".dsh", profile_name="worker") is False
    assert not any(sig != 0 for _, sig in killed)
    assert spawned == []


def test_restart_requires_explicit_target_without_scanning_processes(tmp_path, monkeypatch):
    monkeypatch.setattr("sys.platform", "linux")
    monkeypatch.setattr(configuration, "detect_host_agents", lambda: {"dsh": [tmp_path / "one", tmp_path / "two"]})
    monkeypatch.setattr("subprocess.run", lambda *_args, **_kwargs: pytest.fail("没有目标时不能选择服务"))
    monkeypatch.setattr(os, "kill", lambda *_: pytest.fail("没有目标时不能停止进程"))
    assert configuration.restart_dsh_service_if_active() is False


def test_selected_upgrade_target_reaches_restart_and_registration(deployment, monkeypatch, tmp_path):
    from oai_core import dsh_installation
    root, store, handle, operator = deployment
    profiles = [dsh_installation.DshProfile(tmp_path / name, "worker", tmp_path / name / "profiles/worker")
                for name in ("other-home", "target-home")]
    monkeypatch.setattr(dsh_installation, "discover_dsh_profiles", lambda _: profiles)
    monkeypatch.setattr(dsh_installation, "install_dsh_adapter", lambda *_: None)
    restart_calls = []
    with bridge() as (host, url):
        entry = candidate(root, url)
        monkeypatch.setattr(configuration, "_is_local_dsh_bridge", lambda _: True)
        configure_fixture(root, monkeypatch, entry, iter(["1", "dsh", "2"]))

        def restart(home=None, name=None):
            restart_calls.append((home, name))
            if home == profiles[1].home and name == profiles[1].name:
                host.current = True
                return True
            return False

        monkeypatch.setattr(configuration, "restart_dsh_service_if_active", restart)
        configuration._configure_agents(root, store, handle, operator)
    assert (profiles[1].home, "worker") in restart_calls
    assert all(home is not None and name is not None for home, name in restart_calls)
    assert [item["id"] for item in AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries()] == ["dsh"]


@pytest.fixture
def simulated_dsh_processes(tmp_path, monkeypatch):
    proc_root = tmp_path / "proc"
    proc_root.mkdir()
    monkeypatch.setattr(configuration, "_DSH_PROC_ROOT", proc_root)
    monkeypatch.setattr("sys.platform", "linux")
    monkeypatch.setattr(os, "geteuid", lambda: os.getuid() or 1000)
    directories = {}
    original_readlink = os.readlink
    monkeypatch.setattr(os, "readlink", lambda path, **kwargs: directories[Path(path)]
                        if Path(path) in directories else original_readlink(path, **kwargs))

    def add(pid, home, name, *, arguments=None, environment=None, directory=None):
        home.mkdir(exist_ok=True)
        process = proc_root / str(pid)
        process.mkdir()
        args = arguments or ["/usr/bin/dsh", "--profile", name, "--port", "8123", "--no-open"]
        env = {"HOME": str(tmp_path), "DSH_HOME": str(home), "LANG": "zh_CN.UTF-8", **(environment or {})}
        (process / "cmdline").write_bytes(b"\0".join(os.fsencode(value) for value in args) + b"\0")
        (process / "environ").write_bytes(b"\0".join(os.fsencode(f"{key}={value}") for key, value in env.items()) + b"\0")
        (process / "stat").write_text(f"{pid} (dsh) " + " ".join(["S"] + ["0"] * 18 + [str(pid * 10)]))
        (process / "cgroup").write_text("0::/user.slice/session-2.scope\n")
        directories[process / "cwd"] = str(directory or tmp_path)
        return process, args, env

    return add


def simulated_systemd(monkeypatch, units):
    calls = []

    def run(command, **kwargs):
        calls.append(command)
        if "list-units" in command:
            output = "\n".join(f"{name} loaded active running 测试服务" for name in units)
        elif "show" in command:
            unit = command[command.index("show") + 1]
            output = units.get(unit, "ActiveState=inactive\nMainPID=0\n")
            properties = dict(line.split("=", 1) for line in output.splitlines() if "=" in line)
            process = configuration._read_dsh_process(int(properties.get("MainPID", "0")))
            if process is not None:
                import shlex
                output += f"WorkingDirectory={process.directory}\nExecStart={{ argv[]={shlex.join(process.arguments)} ; }}\n"
        else:
            assert "restart" in command
            output = ""
        return SimpleNamespace(returncode=0, stdout=output)

    monkeypatch.setattr("subprocess.run", run)
    return calls


def test_restart_only_matching_custom_systemd_unit(tmp_path, monkeypatch, simulated_dsh_processes):
    other_home, target_home = tmp_path / "other", tmp_path / "target"
    simulated_dsh_processes(41001, other_home, "web")
    simulated_dsh_processes(41002, target_home, "worker")
    calls = simulated_systemd(monkeypatch, {
        "dsh.service": "ActiveState=active\nMainPID=41001\n",
        "custom-worker.service": "ActiveState=active\nMainPID=41002\n",
    })
    monkeypatch.setattr(os, "kill", lambda *_: pytest.fail("systemd 管理路径不能直接停止进程"))
    assert configuration.restart_dsh_service_if_active(target_home, "worker") is True
    assert [command[-1] for command in calls if "restart" in command] == ["custom-worker.service"]


@pytest.mark.parametrize("wrong_home,wrong_profile", [(True, False), (False, True)])
def test_restart_requires_both_home_and_profile(tmp_path, monkeypatch, simulated_dsh_processes, wrong_home, wrong_profile):
    target_home = tmp_path / "target"
    target_home.mkdir()
    simulated_dsh_processes(41001, tmp_path / "other" if wrong_home else target_home,
                            "web" if wrong_profile else "worker")
    calls = simulated_systemd(monkeypatch, {"dsh.service": "ActiveState=active\nMainPID=41001\n"})
    assert configuration.restart_dsh_service_if_active(target_home, "worker") is False
    assert not any("restart" in command for command in calls)


def test_restart_does_not_choose_between_two_matching_services(tmp_path, monkeypatch, simulated_dsh_processes):
    home = tmp_path / "target"
    simulated_dsh_processes(41001, home, "worker")
    simulated_dsh_processes(41002, home, "worker")
    calls = simulated_systemd(monkeypatch, {
        "one.service": "ActiveState=active\nMainPID=41001\n",
        "two.service": "ActiveState=active\nMainPID=41002\n",
    })
    assert configuration.restart_dsh_service_if_active(home, "worker") is False
    assert not any("restart" in command for command in calls)


def test_pid_restart_preserves_command_environment_and_directory(tmp_path, monkeypatch, simulated_dsh_processes):
    import signal
    home = tmp_path / "target"
    process, args, env = simulated_dsh_processes(41001, home, "worker", arguments=[
        "/usr/bin/dsh", "--profile", "worker", "--patch", "custom patch.yml", "--port", "8123", "--no-open"],
        environment={"CUSTOM_OPTION": "保留参数"}, directory=tmp_path)
    pid_file = home / "dsh.pid"
    pid_file.write_text("41001\n")
    simulated_systemd(monkeypatch, {})
    signals, spawned, closed = [], [], []
    monkeypatch.setattr(os, "pidfd_open", lambda pid: 910, raising=False)
    original_close = os.close
    monkeypatch.setattr(os, "close", lambda fd: closed.append(fd) if fd == 910 else original_close(fd))

    def send_signal(fd, sig):
        signals.append((fd, sig))
        (process / "cmdline").write_bytes(b"")

    monkeypatch.setattr(signal, "pidfd_send_signal", send_signal, raising=False)
    monkeypatch.setattr("subprocess.Popen", lambda command, **kwargs:
                        (spawned.append((command, kwargs)) or SimpleNamespace(pid=41003)))
    assert configuration.restart_dsh_service_if_active(home, "worker") is True
    assert signals == [(910, signal.SIGTERM)]
    assert closed == [910]
    assert spawned[0][0] == args
    assert spawned[0][1]["cwd"] == tmp_path
    assert spawned[0][1]["env"] == env
    assert pid_file.read_text() == "41003\n"


def test_profile_entry_uses_runtime_patch_port_and_instance(tmp_path, monkeypatch, simulated_dsh_processes):
    home = tmp_path / "target"
    _, args, env = simulated_dsh_processes(41001, home, "worker", arguments=[
        "/usr/bin/dsh", "--profile", "worker", "--patch", "custom patch.yml", "--port", "8123"])
    calls = []

    def dump(command, **kwargs):
        calls.append((command, kwargs))
        return SimpleNamespace(returncode=0, stdout="- id: oai-session-adapter\n  name: oai-dsh-session-adapter\n  config:\n    port: 9127\n    instanceId: target-worker\n")

    monkeypatch.setattr("subprocess.run", dump)
    actual = configuration._dsh_profile_entry(home, "worker", {"id": "dsh", "secretFile": "bridge.secret"})
    assert actual["url"] == "http://127.0.0.1:9127"
    assert actual["instanceId"] == "target-worker"
    assert actual["secretFile"] == "bridge.secret"
    assert calls[0][0] == [args[0], "--profile", "worker", "--patch", "custom patch.yml", "--dump-config"]
    assert calls[0][1]["env"] == env


def test_register_retries_only_selected_profile_endpoint(deployment, monkeypatch, tmp_path):
    root, store, handle, operator = deployment
    from oai_core.agent_registration import AgentHandshakeError
    entry = {"id": "dsh", "type": "dsh", "instanceId": "worker", "url": "http://127.0.0.1:9127"}
    target = configuration._DshBridgeUpgrade(tmp_path / "target", "worker", entry)
    monkeypatch.setattr(configuration, "_dsh_target_owns_endpoint", lambda _: False)

    class Registration:
        async def register(self, *_):
            pytest.fail("目标尚未持有端口时不能向任意在线实例注册")

    monkeypatch.setattr(configuration, "restart_dsh_service_if_active", lambda home, name: False)
    with pytest.raises(AgentHandshakeError) as error:
        configuration._register_with_host_retry(Registration(), handle, entry, root, dsh_target=target)
    assert error.value.code == "AGENT_BRIDGE_UNAVAILABLE"


@pytest.mark.parametrize("owns_socket", [True, False])
def test_target_endpoint_requires_selected_process_socket(tmp_path, monkeypatch, simulated_dsh_processes, owns_socket):
    home = tmp_path / "target"
    process, _, _ = simulated_dsh_processes(41001, home, "worker")
    (process / "fd").mkdir()
    descriptor = process / "fd/7"
    descriptor.touch()
    (process / "net").mkdir()
    (process / "net/tcp").write_text("标题\n 0: 0100007F:23A7 00000000:0000 0A 0:0 00:0 0 1000 0 5511\n")
    original_readlink = os.readlink
    monkeypatch.setattr(os, "readlink", lambda path, **kwargs: ("socket:[5511]" if owns_socket else "socket:[5512]")
                        if Path(path) == descriptor else original_readlink(path, **kwargs))
    target = configuration._DshBridgeUpgrade(home, "worker", {"url": "http://127.0.0.1:9127"})
    assert configuration._dsh_target_owns_endpoint(target) is owns_socket


def test_changed_unit_definition_does_not_fall_back_to_pid_kill(tmp_path, monkeypatch, simulated_dsh_processes):
    home = tmp_path / "target"
    simulated_dsh_processes(41001, home, "worker")
    (home / "dsh.pid").write_text("41001\n")
    calls = simulated_systemd(monkeypatch, {"dsh.service": "ActiveState=active\nMainPID=41001\nEnvironment=DSH_HOME=/different-home\n"})
    monkeypatch.setattr(os, "pidfd_open", lambda *_: pytest.fail("不能绕过服务配置核验而直接停止进程"))
    assert configuration.restart_dsh_service_if_active(home, "worker") is False
    assert not any("restart" in command for command in calls)


def test_upgraded_registration_uses_selected_instance_and_port(deployment, monkeypatch, tmp_path):
    from oai_core import dsh_installation
    root, store, handle, operator = deployment
    profile = dsh_installation.DshProfile(tmp_path / "target", "worker", tmp_path / "target/profiles/worker")
    monkeypatch.setattr(dsh_installation, "discover_dsh_profiles", lambda _: [profile])
    monkeypatch.setattr(dsh_installation, "install_dsh_adapter", lambda *_: None)
    with bridge() as (other, old_url), bridge() as (selected, selected_url):
        selected.instance_id = "selected-worker"
        entry = candidate(root, old_url)
        monkeypatch.setattr(configuration, "_is_local_dsh_bridge", lambda _: True)
        monkeypatch.setattr(configuration, "_dsh_profile_entry", lambda home, name, candidate:
                            {**candidate, "instanceId": "selected-worker", "url": selected_url})
        configure_fixture(root, monkeypatch, entry, iter(["1", "dsh", "1"]))

        def restart(home, name):
            assert (home, name) == (profile.home, "worker")
            selected.current = True
            return True

        monkeypatch.setattr(configuration, "restart_dsh_service_if_active", restart)
        configuration._configure_agents(root, store, handle, operator)
        assert other.handshakes == 1
        assert selected.handshakes == 1
    registered = AgentRegistration(operator["instanceId"], store, lambda _: False, lambda _: False).entries()
    assert registered[0]["instanceId"] == "selected-worker"
    assert registered[0]["url"] == selected_url

