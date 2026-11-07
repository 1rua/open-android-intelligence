"""Hermes installation stays local, authorized and recoverable, without a live host."""
from __future__ import annotations

import ast
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys
from types import SimpleNamespace

import pytest
import yaml

from oai_core.identity import IdentityStore, issue_bootstrap_proof


SDK = Path(os.environ["HERMES_SDK_ROOT"]) if os.environ.get("HERMES_SDK_ROOT") else (
    Path(__file__).parents[2] / "../../.toolchains/hermes-sdk-75b083e"
)
PLUGIN = "oai-hermes-session-adapter"


@pytest.fixture
def tmp_path():
    # Keep fixtures in this worktree for inspection; never let pytest prune them.
    parent = Path(__file__).parents[2] / ".worktrees" / "hermes-installation-tests"
    parent.mkdir(parents=True, exist_ok=True)
    path = parent / secrets.token_hex(12)
    path.mkdir(mode=0o700)
    return path


@pytest.fixture
def installation(tmp_path):
    root = tmp_path / "core"
    root.mkdir(mode=0o700)
    database = root / "runtime" / "security" / "identity.sqlite"
    read_fd, write_fd = os.pipe()
    os.write(write_fd, b"bootstrap\n")
    os.close(write_fd)
    try:
        proof = issue_bootstrap_proof(database, root / "runtime" / "accounts", confirmation_fd=read_fd)
    finally:
        os.close(read_fd)
    store = IdentityStore(database, create=True, bootstrap_proof=proof)
    store.bootstrap("admin", "test-password", confirmed=True)
    handle = store.begin_management("admin", "test-password", scopes=["agents.manage"])
    home = tmp_path / "hermes"
    home.mkdir(mode=0o700)
    python = home / "hermes-agent" / "venv" / "bin" / "python"
    python.parent.mkdir(parents=True)
    python.write_text("#!/usr/bin/env python3\n# inert host interpreter fixture\n")
    python.chmod(0o700)
    (python.parent.parent / "pyvenv.cfg").write_text("include-system-site-packages = false\n")
    for relative, package, name in (
        ("oai-core", "oai_core", "open-android-intelligence-core"),
        ("integrations/hermes-session-adapter", "oai_hermes_adapter", PLUGIN),
    ):
        source = root / relative / "src" / package
        source.mkdir(parents=True)
        (source / "__init__.py").write_text("# trusted package fixture\n")
        (root / relative / "pyproject.toml").write_text(
            '[build-system]\nrequires = ["setuptools>=68"]\nbuild-backend = "setuptools.build_meta"\n'
            f'[project]\nname = "{name}"\nversion = "0.2.0"\n'
        )
    manifest = root / "integrations/hermes-session-adapter/plugin.yaml"
    manifest.write_text(f"manifest_version: 2\nname: {PLUGIN}\nversion: 0.2.0\nkind: platform\n")
    yield SimpleNamespace(root=root, store=store, handle=handle, home=home, python=python, manifest=manifest)
    store.close()


def test_fixed_sdk_treats_relative_source_as_github_not_local_plugin():
    # Execute the actual fixed SDK resolver without importing the host or its home.
    source = SDK / "hermes_cli/plugins_cmd.py"
    if not source.is_file():
        if os.environ.get("HERMES_SDK_ROOT"):
            pytest.fail("HERMES_SDK_ROOT does not contain the Hermes SDK")
        pytest.skip("fixed Hermes SDK not present")
    tree = ast.parse(source.read_text())
    names = {"_URL_SCHEMES", "_GITHUB_BROWSER_SEGMENTS"}
    nodes = [node for node in tree.body if (
        isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id in names for t in node.targets)
    ) or isinstance(node, ast.FunctionDef) and node.name == "_resolve_git_url"]
    scope = {"Optional": __import__("typing").Optional}
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(source), "exec"), scope)
    assert scope["_resolve_git_url"]("./integrations/hermes-session-adapter") == (
        "https://github.com/./integrations.git", "hermes-session-adapter"
    )


def test_unauthorized_install_has_no_side_effects(installation, monkeypatch, capsys):
    from oai_core.hermes_installation import HermesAdapterInstallError, install_hermes_adapter
    import oai_core.hermes_installation as installer

    monkeypatch.setattr(installer.subprocess, "Popen", lambda *a, **k: pytest.fail("unauthorized process"))
    before = sorted(p.relative_to(installation.home) for p in installation.home.rglob("*"))
    with pytest.raises(HermesAdapterInstallError) as caught:
        install_hermes_adapter(installation.root, installation.store, "invalid-secret-handle", installation.home)
    assert caught.value.code == "HERMES_PERMISSION_DENIED"
    assert "invalid-secret-handle" not in str(caught.value)
    assert before == sorted(p.relative_to(installation.home) for p in installation.home.rglob("*"))
    assert capsys.readouterr() == ("", "")


class Child:
    pid = 987654321

    def __init__(self, returncode=0):
        self.returncode = returncode

    def wait(self, timeout):
        assert 0 < timeout <= 300
        return self.returncode


def fake_host(monkeypatch, installer, installation, *, uv=True, pip=True, failure=None, before=None):
    """Only fake the external process boundary; exercise real authority and files."""
    tool = installation.home.parent / "uv"
    tool.write_text("# trusted uv executable fixture\n")
    tool.chmod(0o700)
    monkeypatch.setattr(installer.shutil, "which", lambda name: str(tool) if name == "uv" and uv else None)
    calls = []
    def popen(arguments, **kwargs):
        assert kwargs["cwd"] == installation.root
        assert kwargs["stdin"] == kwargs["stdout"] == kwargs["stderr"] == subprocess.DEVNULL
        assert kwargs["start_new_session"] is True
        assert kwargs["env"]["HERMES_HOME"] == str(installation.home)
        assert kwargs["env"]["HERMES_SAFE_MODE"] == "1"
        assert "OAI_ADMIN_TOKEN" not in kwargs["env"]
        assert "PYTHONPATH" not in kwargs["env"]
        assert installation.handle not in str(arguments)
        calls.append(list(arguments))
        if before:
            before(arguments)
        if "--version" in arguments and "pip" in arguments:
            return Child(0 if pip else 1)
        if "install" in arguments:
            assert arguments[-2:] == ["./oai-core", "./integrations/hermes-session-adapter"]
            if uv:
                assert "--python" in arguments
                assert os.path.abspath(installation.root / arguments[arguments.index("--python") + 1]) == str(installation.python)
            else:
                assert "--no-input" in arguments
            if failure == "dependencies":
                return Child(1)
        if "plugins" in arguments:
            action, name = arguments[-2:]
            config = installation.home / "config.yaml"
            data = yaml.safe_load(config.read_text()) if config.exists() else {}
            plugins = data.setdefault("plugins", {})
            enabled = set(plugins.setdefault("enabled", []))
            disabled = set(plugins.setdefault("disabled", []))
            if action == "enable":
                assert name == PLUGIN
                target = installation.home / "plugins" / PLUGIN
                assert (target / "plugin.yaml").read_bytes() == installation.manifest.read_bytes()
                assert "from oai_hermes_adapter import register" in (target / "__init__.py").read_text()
                assert not (installation.root / ".git").exists()
                if failure == "enable":
                    return Child(1)
                enabled.add(name)
                disabled.discard(name)
            else:
                assert action == "disable" and name == "open-android-intelligence-gateway"
                enabled.discard(name)
                disabled.add(name)
            plugins.update(enabled=sorted(enabled), disabled=sorted(disabled))
            config.write_text(yaml.safe_dump(data))
        if "-c" in arguments and "sys.exit(10" in arguments[-1]:
            config = installation.home / "config.yaml"
            data = yaml.safe_load(config.read_text()) if config.exists() else {}
            return Child(10 if "open-android-intelligence-gateway" in data.get("plugins", {}).get("enabled", []) else 0)
        return Child()
    monkeypatch.setattr(installer.subprocess, "Popen", popen)
    return calls


def test_local_packages_install_with_uv_without_pip_and_switch_legacy_id(installation, monkeypatch, capsys):
    import oai_core.hermes_installation as installer

    config = installation.home / "config.yaml"
    original_config = b"plugins:\n  enabled: [open-android-intelligence-gateway, other]\nmodel: keep-me\n"
    config.write_bytes(original_config)
    old = installation.home / "plugins" / PLUGIN
    old.mkdir(parents=True)
    (old / "custom.py").write_bytes(b"previous adapter configuration\n")
    legacy = installation.home / "plugins" / "open-android-intelligence-gateway"
    legacy.mkdir()
    (legacy / "plugin.yaml").write_text("name: open-android-intelligence-gateway\n")
    protected = {}
    for relative in ("state.db", "sessions/history.jsonl", "auth.json", ".env", "accounts/master.key"):
        path = installation.home / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"existing private data - preserve exactly\n")
        protected[path] = path.read_bytes()
    monkeypatch.setenv("OAI_ADMIN_TOKEN", "must-not-leak")
    monkeypatch.setenv("PYTHONPATH", "untrusted-python-modules")
    calls = fake_host(monkeypatch, installer, installation, uv=True, pip=False)

    assert installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home) is None

    assert len([args for args in calls if "install" in args]) == 1
    assert not any("pip" in args and "--version" in args for args in calls)
    assert [args[-2:] for args in calls if "plugins" in args] == [
        ["disable", "open-android-intelligence-gateway"], ["enable", PLUGIN]
    ]
    data = yaml.safe_load(config.read_text())
    assert data["model"] == "keep-me"
    assert data["plugins"]["enabled"] == [PLUGIN, "other"]
    assert "open-android-intelligence-gateway" in data["plugins"]["disabled"]
    backups = list(installation.home.glob(".oai-hermes-install-*"))
    assert len(backups) == 1
    assert (backups[0] / "config.yaml").read_bytes() == original_config
    assert (backups[0] / "previous-plugin/custom.py").read_bytes() == b"previous adapter configuration\n"
    assert stat.S_IMODE(backups[0].stat().st_mode) == 0o700
    assert stat.S_IMODE((backups[0] / "config.yaml").stat().st_mode) == 0o600
    assert legacy.is_dir()
    assert all(path.read_bytes() == content for path, content in protected.items())
    assert capsys.readouterr() == ("", "")


@pytest.mark.parametrize("failure,code", [("dependencies", "HERMES_DEPENDENCY_FAILED"), ("enable", "HERMES_ENABLE_FAILED")])
def test_failures_retain_recovery_files_and_restore_old_entry(installation, monkeypatch, capsys, failure, code):
    import oai_core.hermes_installation as installer

    config = installation.home / "config.yaml"
    original = b"plugins:\n  enabled: [other]\nmodel: keep\n"
    config.write_bytes(original)
    old = installation.home / "plugins" / PLUGIN
    old.mkdir(parents=True)
    (old / "custom.py").write_bytes(b"keep old plugin")
    calls = fake_host(monkeypatch, installer, installation, failure=failure)
    with pytest.raises(installer.HermesAdapterInstallError) as caught:
        installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert caught.value.code == code
    assert config.read_bytes() == original
    assert (old / "custom.py").read_bytes() == b"keep old plugin"
    backup, = installation.home.glob(".oai-hermes-install-*")
    assert (backup / "config.yaml").read_bytes() == original
    if failure == "enable":
        assert not (backup / "failed-config.yaml").exists()
        assert (backup / "failed-plugin/plugin.yaml").is_file()
    else:
        assert not any("plugins" in args for args in calls)
    assert capsys.readouterr() == ("", "")


@pytest.mark.parametrize("pip,code", [(True, None), (False, "HERMES_PACKAGE_MANAGER_UNAVAILABLE")])
def test_missing_uv_uses_only_probed_host_pip(installation, monkeypatch, pip, code):
    import oai_core.hermes_installation as installer

    calls = fake_host(monkeypatch, installer, installation, uv=False, pip=pip)
    if code:
        with pytest.raises(installer.HermesAdapterInstallError) as caught:
            installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
        assert caught.value.code == code
        assert not list(installation.home.glob(".oai-hermes-install-*"))
    else:
        installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert any(args[-3:] == ["-m", "pip", "--version"] for args in calls)
    assert bool([args for args in calls if "install" in args]) == pip


def test_another_core_installation_cannot_reuse_management_handle(installation, monkeypatch):
    import oai_core.hermes_installation as installer

    other = installation.root.parent / "other-core"
    other.mkdir()
    monkeypatch.setattr(installer.subprocess, "Popen", lambda *a, **k: pytest.fail("wrong-install child"))
    with pytest.raises(installer.HermesAdapterInstallError) as caught:
        installer.install_hermes_adapter(other, installation.store, installation.handle, installation.home)
    assert caught.value.code == "HERMES_PERMISSION_DENIED"
    assert not list(installation.home.glob(".oai-hermes-install-*"))


def test_revoke_during_dependency_install_stops_plugin_and_config_writes(installation, monkeypatch):
    import oai_core.hermes_installation as installer

    config = installation.home / "config.yaml"
    config.write_bytes(b"model: existing\n")
    def revoke(arguments):
        if "install" in arguments:
            installation.store.revoke_management(installation.handle)
    calls = fake_host(monkeypatch, installer, installation, before=revoke)
    with pytest.raises(installer.HermesAdapterInstallError) as caught:
        installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert caught.value.code == "HERMES_PERMISSION_DENIED"
    assert not any("plugins" in args for args in calls)
    assert config.read_bytes() == b"model: existing\n"
    assert not (installation.home / "plugins").exists()


def test_venv_interpreter_symlink_is_invoked_without_resolving_away_venv(installation, monkeypatch):
    import oai_core.hermes_installation as installer

    # This is an actual venv-style link on native Linux. Some restricted
    # filesystems refuse symlink creation; report that environment explicitly.
    original = installation.python.with_name("python.original")
    installation.python.rename(original)
    try:
        installation.python.symlink_to(original.name)
    except OSError as error:
        installation.python.write_bytes(original.read_bytes())
        installation.python.chmod(0o700)
        # The restricted filesystem reports ENOENT even with existing parents.
        # Emulate only lstat/resolve, the OS boundaries; do not bypass validation.
        assert error.errno in (1, 2, 13, 38, 95)
        lstat, resolve = Path.lstat, Path.resolve
        def linked_stat(path, *args, **kwargs):
            info = lstat(path, *args, **kwargs)
            if path == installation.python:
                fields = list(info)
                fields[0] = stat.S_IFLNK | 0o777
                return os.stat_result(fields)
            return info
        def linked_resolve(path, *args, **kwargs):
            return resolve(original, *args, **kwargs) if path == installation.python else resolve(path, *args, **kwargs)
        monkeypatch.setattr(Path, "lstat", linked_stat)
        monkeypatch.setattr(Path, "resolve", linked_resolve)
    calls = fake_host(monkeypatch, installer, installation)
    installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    python_calls = [args for args in calls if "-I" in args]
    assert python_calls
    assert all(args[0] == os.path.relpath(installation.python, installation.root) for args in python_calls)


def test_failed_cli_with_unattributable_config_write_is_not_rolled_back(installation, monkeypatch):
    import oai_core.hermes_installation as installer

    config = installation.home / "config.yaml"
    original = b"model: original\n"
    config.write_bytes(original)
    calls = fake_host(monkeypatch, installer, installation)
    popen = installer.subprocess.Popen
    def concurrent_write(arguments, **kwargs):
        if arguments[-2:] == ["enable", PLUGIN]:
            calls.append(list(arguments))
            config.write_bytes(b"model: concurrent-or-partial-cli-write\n")
            return Child(1)
        return popen(arguments, **kwargs)
    monkeypatch.setattr(installer.subprocess, "Popen", concurrent_write)
    with pytest.raises(installer.HermesAdapterInstallError) as caught:
        installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert caught.value.code == "HERMES_RECOVERY_REQUIRED"
    assert config.read_bytes() == b"model: concurrent-or-partial-cli-write\n"
    backup, = installation.home.glob(".oai-hermes-install-*")
    assert (backup / "config.yaml").read_bytes() == original
    assert (installation.home / "plugins" / PLUGIN / "plugin.yaml").exists()


def test_uv_executable_symlink_is_validated_and_supported(installation, monkeypatch):
    import oai_core.hermes_installation as installer

    calls = fake_host(monkeypatch, installer, installation)
    uv = installation.home.parent / "uv"
    target = uv.with_name("uv.original")
    target.write_bytes(uv.read_bytes())
    target.chmod(0o700)
    lstat, resolve = Path.lstat, Path.resolve
    def linked_stat(path, *args, **kwargs):
        info = lstat(path, *args, **kwargs)
        if path == uv:
            fields = list(info)
            fields[0] = stat.S_IFLNK | 0o777
            return os.stat_result(fields)
        return info
    def linked_resolve(path, *args, **kwargs):
        return resolve(target, *args, **kwargs) if path == uv else resolve(path, *args, **kwargs)
    monkeypatch.setattr(Path, "lstat", linked_stat)
    monkeypatch.setattr(Path, "resolve", linked_resolve)
    installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert any("install" in args for args in calls)


def test_fixed_sdk_cli_switches_standard_plugin_directories_without_loading_them(installation, monkeypatch):
    import oai_core.hermes_installation as installer

    if not (SDK / "hermes_cli/main.py").is_file():
        if os.environ.get("HERMES_SDK_ROOT"):
            pytest.fail("HERMES_SDK_ROOT does not contain the Hermes SDK")
        pytest.skip("fixed Hermes SDK not present")
    legacy = installation.home / "plugins" / "open-android-intelligence-gateway"
    legacy.mkdir(parents=True)
    (legacy / "plugin.yaml").write_text("name: open-android-intelligence-gateway\nversion: 0.1.0\n")
    (legacy / "__init__.py").write_text("raise AssertionError('installation must not load plugin runtime')\n")
    config = installation.home / "config.yaml"
    config.write_text("plugins:\n  enabled: [open-android-intelligence-gateway, other]\nmodel: keep-me\n")
    protected = {}
    for relative in ("state.db", "sessions/history.jsonl", "auth.json", "accounts/master.key", ".env"):
        path = installation.home / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        content = b"TEST_FIXTURE_VALUE=preserved\n" if relative == ".env" else b"existing private fixture data\n"
        path.write_bytes(content)
        protected[path] = content
    real_popen = subprocess.Popen
    fake_host(monkeypatch, installer, installation)
    stub = installer.subprocess.Popen
    cli_calls = []
    # Use the real SDK CLI and our test runner's dependencies in an isolated
    # child. Package installation/probes remain separate process-boundary tests.
    dependencies = [p for p in sys.path if p and "site-packages" in p]
    preamble = f"import sys; sys.path[:0] = {json.dumps([str(SDK.resolve()), *dependencies])}; "
    def child(arguments, **kwargs):
        if "plugins" not in arguments:
            return stub(arguments, **kwargs)
        cli_calls.append(arguments[-2:])
        code = preamble + f"sys.argv = {json.dumps(['hermes', *arguments[5:]])}; import runpy; runpy.run_module('hermes_cli.main', run_name='__main__')"
        with (installation.home / "fixture-cli.log").open("ab") as log:
            kwargs.update(stdout=log, stderr=log)
            return real_popen([sys.executable, "-I", "-B", "-c", code], **kwargs)
    monkeypatch.setattr(installer.subprocess, "Popen", child)
    installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert cli_calls == [["disable", "open-android-intelligence-gateway"], ["enable", PLUGIN]]
    data = yaml.safe_load(config.read_text())
    assert set(data["plugins"]["enabled"]) == {PLUGIN, "other"}
    assert "open-android-intelligence-gateway" in data["plugins"]["disabled"]
    assert data["model"] == "keep-me"
    assert all(path.read_bytes() == content for path, content in protected.items())


@pytest.mark.parametrize("where", ["home", "config", "plugin", "source"])
def test_non_executable_symlinks_are_refused_before_any_child(installation, monkeypatch, where):
    import oai_core.hermes_installation as installer

    path = {
        "home": installation.home,
        "config": installation.home / "config.yaml",
        "plugin": installation.home / "plugins" / PLUGIN,
        "source": installation.root / "integrations/hermes-session-adapter/src/oai_hermes_adapter/__init__.py",
    }[where]
    if where == "config":
        path.write_text("model: original\n")
    if where == "plugin":
        path.mkdir(parents=True)
    lstat = Path.lstat
    def linked_stat(candidate, *args, **kwargs):
        info = lstat(candidate, *args, **kwargs)
        if candidate == path:
            fields = list(info)
            fields[0] = stat.S_IFLNK | 0o777
            return os.stat_result(fields)
        return info
    monkeypatch.setattr(Path, "lstat", linked_stat)
    monkeypatch.setattr(installer.subprocess, "Popen", lambda *a, **k: pytest.fail("unsafe-path child"))
    with pytest.raises(installer.HermesAdapterInstallError) as caught:
        installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert caught.value.code == "HERMES_PATH_UNSAFE"
    assert not list(installation.home.glob(".oai-hermes-install-*"))


def test_foreign_owned_host_is_explicitly_unsupported_without_changes(installation, monkeypatch):
    import oai_core.hermes_installation as installer

    real_stat = Path.stat
    def foreign_stat(path, *args, **kwargs):
        info = real_stat(path, *args, **kwargs)
        if path == installation.home:
            fields = list(info)
            fields[4] = os.geteuid() + 1
            return os.stat_result(fields)
        return info
    monkeypatch.setattr(Path, "stat", foreign_stat)
    monkeypatch.setattr(installer.subprocess, "Popen", lambda *a, **k: pytest.fail("foreign-user child"))
    with pytest.raises(installer.HermesAdapterInstallError) as caught:
        installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert caught.value.code == "HERMES_HOST_OWNER_UNSUPPORTED"
    assert not list(installation.home.glob(".oai-hermes-install-*"))


def test_dependency_timeout_stops_owned_process_group_and_preserves_original_config(installation, monkeypatch):
    import oai_core.hermes_installation as installer

    config = installation.home / "config.yaml"
    config.write_text("model: keep\n")
    calls = fake_host(monkeypatch, installer, installation)
    popen = installer.subprocess.Popen
    signals = []
    class TimedOut(Child):
        def __init__(self):
            self.waits = 0
        def wait(self, timeout):
            self.waits += 1
            if self.waits == 1:
                raise subprocess.TimeoutExpired("private-child-output-must-not-leak", timeout)
            return 0
    def timed_popen(arguments, **kwargs):
        if "install" in arguments:
            calls.append(list(arguments))
            return TimedOut()
        return popen(arguments, **kwargs)
    monkeypatch.setattr(installer.subprocess, "Popen", timed_popen)
    monkeypatch.setattr(installer.os, "killpg", lambda pid, sig: signals.append((pid, sig)))
    with pytest.raises(installer.HermesAdapterInstallError) as caught:
        installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert caught.value.code == "HERMES_INSTALL_TIMEOUT"
    assert "private-child-output" not in str(caught.value)
    assert signals == [(Child.pid, installer.signal.SIGTERM)]
    assert config.read_text() == "model: keep\n"
    assert not any("plugins" in args for args in calls)


def test_config_changed_during_resolve_is_preserved_without_enabling(installation, monkeypatch):
    import oai_core.hermes_installation as installer

    config = installation.home / "config.yaml"
    config.write_text("model: original\n")
    def change(arguments):
        if "install" in arguments:
            config.write_text("model: external-writer\n")
    calls = fake_host(monkeypatch, installer, installation, before=change)
    with pytest.raises(installer.HermesAdapterInstallError) as caught:
        installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert caught.value.code == "HERMES_ENABLE_FAILED"
    assert config.read_text() == "model: external-writer\n"
    assert not any("plugins" in args for args in calls)


def test_successful_legacy_disable_is_preserved_for_manual_recovery_if_enable_fails(installation, monkeypatch):
    import oai_core.hermes_installation as installer

    config = installation.home / "config.yaml"
    original = b"plugins:\n  enabled: [open-android-intelligence-gateway]\nmodel: original\n"
    config.write_bytes(original)
    fake_host(monkeypatch, installer, installation, failure="enable")
    with pytest.raises(installer.HermesAdapterInstallError) as caught:
        installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert caught.value.code == "HERMES_RECOVERY_REQUIRED"
    assert config.read_bytes() != original
    current = yaml.safe_load(config.read_bytes())
    assert "open-android-intelligence-gateway" in current["plugins"]["disabled"]
    assert "open-android-intelligence-gateway" not in current["plugins"]["enabled"]
    assert current["model"] == "original"
    backup, = installation.home.glob(".oai-hermes-install-*")
    assert (backup / "config.yaml").read_bytes() == original
    assert (installation.home / "plugins" / PLUGIN / "plugin.yaml").is_file()
    assert not (backup / "failed-config.yaml").exists()
    assert not (backup / "restored-config.yaml").exists()
    assert not (backup / "failed-plugin").exists()


def test_external_disable_after_successful_enable_is_preserved_on_verify_failure(installation, monkeypatch):
    import oai_core.hermes_installation as installer

    config = installation.home / "config.yaml"
    original = b"plugins:\n  enabled: [other]\nmodel: original\n"
    external = b"plugins:\n  enabled: [other]\nmodel: concurrent-model\n"
    config.write_bytes(original)
    previous = installation.home / "plugins" / PLUGIN
    previous.mkdir(parents=True)
    (previous / "custom.py").write_bytes(b"original plugin entry\n")
    fake_host(monkeypatch, installer, installation)
    popen = installer.subprocess.Popen
    enabled = False

    class ChangedAfterEnable(Child):
        def wait(self, timeout):
            result = super().wait(timeout)
            # Another writer cancels enablement after the CLI succeeds and
            # before the installer could read a post-CLI configuration snapshot.
            config.write_bytes(external)
            return result

    def child(arguments, **kwargs):
        nonlocal enabled
        if arguments[-2:] == ["enable", PLUGIN]:
            popen(arguments, **kwargs)
            enabled = True
            return ChangedAfterEnable()
        if enabled and "-c" in arguments:
            assert PLUGIN not in yaml.safe_load(config.read_bytes())["plugins"]["enabled"]
            return Child(1)
        return popen(arguments, **kwargs)

    monkeypatch.setattr(installer.subprocess, "Popen", child)
    with pytest.raises(installer.HermesAdapterInstallError) as caught:
        installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert caught.value.code == "HERMES_RECOVERY_REQUIRED"
    assert config.read_bytes() == external
    backup, = installation.home.glob(".oai-hermes-install-*")
    assert (backup / "config.yaml").read_bytes() == original
    assert (backup / "previous-plugin/custom.py").read_bytes() == b"original plugin entry\n"
    assert (previous / "plugin.yaml").is_file()
    assert not (backup / "failed-config.yaml").exists()
    assert not (backup / "failed-plugin").exists()


def test_real_standard_venv_and_executable_links(tmp_path):
    """Verify original venv execution on filesystems that support real links."""
    import errno
    import venv
    from oai_core.hermes_installation import _executable

    target = tmp_path / "link-target"
    target.write_text("filesystem probe\n")
    try:
        (tmp_path / "link-probe").symlink_to("link-target")
    except OSError as error:
        if error.errno in {errno.ENOENT, errno.EOPNOTSUPP, errno.EPERM, errno.ENOSYS}:
            pytest.skip("当前文件系统无法创建真实符号链接；由原生 CI 验证")
        raise
    environment = tmp_path / "standard-venv"
    # Hosted Python belongs to a shared tool cache whose permissions are not
    # this test's contract. Keep its metadata untouched and link to an owned,
    # private executable copy while retaining a real pyvenv.cfg environment.
    venv.EnvBuilder(with_pip=False, symlinks=False).create(environment)
    interpreter = environment / "bin/python"
    private_executable = interpreter.with_name("private-python")
    interpreter.rename(private_executable)
    private_executable.chmod(0o700)
    interpreter.symlink_to(private_executable.name)
    assert interpreter.is_symlink()
    assert _executable(interpreter, venv=True) == interpreter
    result = subprocess.run([str(interpreter), "-I", "-B", "-c",
                             "import json,sys;print(json.dumps([sys.prefix,sys.base_prefix]))"],
                            check=True, text=True, capture_output=True, timeout=10)
    prefix, base = json.loads(result.stdout)
    assert Path(prefix) == environment and prefix != base
    alias = tmp_path / "executable-alias"
    alias.symlink_to(os.path.relpath(private_executable, tmp_path))
    assert _executable(alias) == private_executable


@pytest.mark.parametrize("mode", [0o720, 0o702, 0o777])
def test_group_or_world_writable_executable_is_still_rejected(tmp_path, mode):
    from oai_core.hermes_installation import HermesAdapterInstallError, _executable

    executable = tmp_path / "shared-python"
    executable.write_text("#!/bin/sh\nexit 0\n")
    executable.chmod(mode)
    with pytest.raises(HermesAdapterInstallError) as caught:
        _executable(executable)
    assert caught.value.code == "HERMES_PATH_UNSAFE"


def test_probe_succeeds_when_invoked_via_parent_relative_venv_python(tmp_path):
    """Real Hermes lives at ../hermes relative to Core root; .. in sys.executable must not trip _PROBE."""
    import venv
    from oai_core.hermes_installation import _PROBE

    root = tmp_path / "core"
    manifest = root / "integrations/hermes-session-adapter/plugin.yaml"
    manifest.parent.mkdir(parents=True, mode=0o700)
    manifest.write_text("manifest_version: 2\nname: oai-hermes-session-adapter\nversion: 0.2.0\nkind: platform\n")
    hermes_home = tmp_path / "hermes"
    hermes_home.mkdir(mode=0o700)
    (hermes_home / "config.yaml").write_text("plugins:\n  enabled:\n    - open-android-intelligence-gateway\n")
    environment = hermes_home / "hermes-agent/venv"
    venv.EnvBuilder(with_pip=False, symlinks=False).create(environment)
    site_dirs = list(environment.glob("lib*/python*/site-packages"))
    assert site_dirs
    site_dir = site_dirs[0]
    (site_dir / "hermes_constants.py").write_text(
        "import os\nfrom pathlib import Path\ndef get_hermes_home(): return Path(os.environ['HERMES_HOME'])\n"
    )
    cli_pkg = site_dir / "hermes_cli"
    cli_pkg.mkdir(exist_ok=True)
    (cli_pkg / "__init__.py").write_text("")
    (cli_pkg / "main.py").write_text("")
    # Make PyYAML available inside the isolated venv for _PROBE.
    yaml_spec = __import__("importlib.util").util.find_spec("yaml")
    assert yaml_spec and yaml_spec.submodule_search_locations
    __import__("shutil").copytree(Path(list(yaml_spec.submodule_search_locations)[0]), site_dir / "yaml", dirs_exist_ok=True)
    rel_python = os.path.relpath(environment / "bin/python", root)
    assert rel_python.startswith("..")
    res = subprocess.run(
        [rel_python, "-I", "-B", "-c", _PROBE],
        cwd=root,
        env={"HERMES_HOME": str(hermes_home), "PATH": os.environ.get("PATH", "")},
        capture_output=True,
        text=True,
        timeout=15,
    )
    assert res.returncode == 10, res.stderr


@pytest.mark.parametrize("use_uv", [True, False])
def test_offline_dependency_fallback_succeeds_when_online_index_is_unreachable(installation, monkeypatch, use_uv):
    from oai_core import hermes_installation as installer

    install_attempts = []

    def before(arguments):
        if "install" in arguments:
            install_attempts.append(list(arguments))

    calls = fake_host(monkeypatch, installer, installation, uv=use_uv, pip=not use_uv, before=before)
    real_popen = installer.subprocess.Popen

    def offline_fallback_popen(arguments, **kwargs):
        if "install" in arguments and "--no-build-isolation" not in arguments:
            calls.append(list(arguments))
            before(arguments)
            return Child(1)
        return real_popen(arguments, **kwargs)

    monkeypatch.setattr(installer.subprocess, "Popen", offline_fallback_popen)
    installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert len(install_attempts) == 2
    assert "--no-build-isolation" not in install_attempts[0]
    assert "--no-build-isolation" in install_attempts[1]
    assert "--no-deps" in install_attempts[1]
    if use_uv:
        assert "--offline" in install_attempts[1]


def test_root_user_can_install_adapter_for_foreign_owned_host(installation, monkeypatch):
    from oai_core import hermes_installation as installer

    owner_uid = 1000
    owner_gid = 1000
    real_stat = Path.stat
    def foreign_stat(path, *args, **kwargs):
        info = real_stat(path, *args, **kwargs)
        if str(path).startswith(str(installation.home)):
            fields = list(info)
            fields[4] = owner_uid
            fields[5] = owner_gid
            return os.stat_result(fields)
        return info

    monkeypatch.setattr(Path, "stat", foreign_stat)
    monkeypatch.setattr(os, "geteuid", lambda: 0)
    monkeypatch.setattr(os, "getuid", lambda: 0)

    chowned_paths = []
    def fake_chown(path, uid, gid, *args, **kwargs):
        chowned_paths.append((str(path), uid, gid))
    monkeypatch.setattr(os, "chown", fake_chown)

    calls = fake_host(monkeypatch, installer, installation)
    installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)

    assert any(uid == owner_uid for _, uid, _ in chowned_paths)
    target = installation.home / "plugins" / installer.PLUGIN
    assert target.is_dir()


def test_hermes_install_finds_root_venv_python(installation, monkeypatch):
    from oai_core import hermes_installation as installer

    # 移动虚拟环境到 home / "venv"
    old_venv = installation.home / "hermes-agent" / "venv"
    new_venv = installation.home / "venv"
    old_venv.rename(new_venv)
    installation.python = new_venv / "bin" / "python"

    fake_host(monkeypatch, installer, installation)
    installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    target = installation.home / "plugins" / installer.PLUGIN
    assert target.is_dir()


def test_hermes_install_finds_dot_venv_python(installation, monkeypatch):
    from oai_core import hermes_installation as installer

    # 移动虚拟环境到 home / "hermes-agent" / ".venv"
    old_venv = installation.home / "hermes-agent" / "venv"
    new_venv = installation.home / "hermes-agent" / ".venv"
    old_venv.rename(new_venv)
    installation.python = new_venv / "bin" / "python"

    fake_host(monkeypatch, installer, installation)
    installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    target = installation.home / "plugins" / installer.PLUGIN
    assert target.is_dir()


def test_hermes_install_auto_creates_venv_when_missing(installation, monkeypatch):
    from oai_core import hermes_installation as installer

    # 移开现有虚拟环境，模拟无 venv 环境
    old_venv = installation.home / "hermes-agent" / "venv"
    old_venv.rename(installation.home / "hermes-agent" / "venv.bak")

    created_venvs = []
    real_run = subprocess.run
    def fake_run(cmd, *args, **kwargs):
        if "-m" in cmd and "venv" in cmd:
            target = Path(cmd[-1])
            target.mkdir(parents=True, exist_ok=True)
            py = target / "bin" / "python"
            py.parent.mkdir(parents=True, exist_ok=True)
            py.write_text("#!/usr/bin/env python3\n")
            py.chmod(0o700)
            (target / "pyvenv.cfg").write_text("include-system-site-packages = false\n")
            created_venvs.append(target)
            return SimpleNamespace(returncode=0)
        return real_run(cmd, *args, **kwargs)

    monkeypatch.setattr(subprocess, "run", fake_run)
    installation.python = installation.home / "hermes-agent" / "venv" / "bin" / "python"
    fake_host(monkeypatch, installer, installation)
    installer.install_hermes_adapter(installation.root, installation.store, installation.handle, installation.home)
    assert len(created_venvs) == 1
    target = installation.home / "plugins" / installer.PLUGIN
    assert target.is_dir()

