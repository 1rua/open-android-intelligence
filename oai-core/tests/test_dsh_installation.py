"""DSH upgrades operate only on explicit, owned local profile manifests."""
import json
import os
import stat
from uuid import uuid4
from types import SimpleNamespace
from pathlib import Path

import pytest

from oai_core.dsh_installation import (
    DshAdapterInstallError, DshProfile, discover_dsh_profiles,
)


OLD = "dsh-android-gateway"
NEW = "oai-dsh-session-adapter"


@pytest.fixture
def tmp_path():
    # Keep all fixtures for review; this fixture deliberately has no cleanup.
    directory = Path(__file__).resolve().parents[2] / ".worktrees" / "dsh-installation-tests" / uuid4().hex
    directory.mkdir(parents=True)
    return directory


def make_profile(home, name, bundles=None):
    directory = home / "profiles" / name
    directory.mkdir(parents=True)
    manifest = {"private": True, "dependencies": {OLD: "0.3.0", "other": "1.0.0"},
                "dsh": {"profile": {"bundles": bundles if bundles is not None else [OLD],
                                     "custom": {"port": 8791}}, "unrelated": True},
                "userMetadata": {"keep": "unchanged"}}
    (directory / "package.json").write_text(json.dumps(manifest))
    return DshProfile(home=home, name=name, directory=directory)


def test_discovery_is_read_only_direct_and_keeps_multiple_homes(tmp_path, monkeypatch):
    monkeypatch.delenv("DSH_PROFILE", raising=False)
    a = make_profile(tmp_path / "first", "web")
    b = make_profile(tmp_path / "second", "work", ["base", NEW])
    make_profile(a.home, "disabled", ["base"])
    make_profile(a.home, "dependency-only", [])
    for name in ("node_modules", "build", "dist", ".gradle"):
        make_profile(a.home, name)
    make_profile(a.home / "profiles" / "nested", "hidden")
    invalid = a.home / "profiles" / "invalid"
    invalid.mkdir()
    (invalid / "package.json").write_text("not json")
    before = {p: p.read_bytes() for p in tmp_path.rglob("package.json")}
    assert discover_dsh_profiles([a.home, b.home, a.home]) == [a, b]
    assert before == {p: p.read_bytes() for p in before}


@pytest.mark.parametrize("value", ["", " ", ".", "..", "../web", "a/b", "a\\b", "web\n", "node_modules", "build", "dist", ".gradle"])
def test_discovery_rejects_invalid_explicit_selector(tmp_path, monkeypatch, value):
    profile = make_profile(tmp_path / "dsh", "web")
    monkeypatch.setenv("DSH_PROFILE", value)
    with pytest.raises(DshAdapterInstallError) as caught:
        discover_dsh_profiles([profile.home])
    assert caught.value.code == "DSH_PROFILE_INVALID"


def test_selector_must_identify_one_target_across_homes(tmp_path, monkeypatch):
    a = make_profile(tmp_path / "one", "web")
    b = make_profile(tmp_path / "two", "worker")
    monkeypatch.setenv("DSH_PROFILE", "worker")
    assert discover_dsh_profiles([a.home, b.home]) == [b]
    monkeypatch.setenv("DSH_PROFILE", "missing")
    with pytest.raises(DshAdapterInstallError, match="未找到") as caught:
        discover_dsh_profiles([a.home, b.home])
    assert caught.value.code == "DSH_PROFILE_NOT_FOUND"
    make_profile(b.home, "web")
    monkeypatch.setenv("DSH_PROFILE", "web")
    with pytest.raises(DshAdapterInstallError) as caught:
        discover_dsh_profiles([a.home, b.home])
    assert caught.value.code == "DSH_PROFILE_AMBIGUOUS"


def test_discovery_rejects_symlinks_in_manifest_profile_home_or_parents(tmp_path, monkeypatch):
    monkeypatch.delenv("DSH_PROFILE", raising=False)
    safe = make_profile(tmp_path / "real", "web")
    rejected = [make_profile(tmp_path / name, "web") for name in ("manifest", "profile", "home", "parent")]
    # Emulate the filesystem lstat boundary: this workspace's filesystem refuses
    # symlink creation with ENOENT even when every parent already exists.
    symlinks = {rejected[0].directory / "package.json", rejected[1].directory,
                rejected[2].home, rejected[3].home.parent / "parent"}
    original = Path.lstat
    def lstat(path, *args, **kwargs):
        info = original(path, *args, **kwargs)
        if path in symlinks:
            return SimpleNamespace(st_mode=stat.S_IFLNK | 0o777, st_uid=info.st_uid)
        return info
    monkeypatch.setattr(Path, "lstat", lstat)
    assert discover_dsh_profiles([safe.home, *(p.home for p in rejected)]) == [safe]
    monkeypatch.setattr(os, "getuid", lambda: safe.directory.stat().st_uid + 1)
    assert discover_dsh_profiles([safe.home]) == []


class Management:
    def __init__(self, allowed=True):
        self.allowed = allowed
        self.calls = []

    def require_management(self, handle, scope):
        self.calls.append((handle, scope))
        if not self.allowed:
            raise PermissionError("private administrator detail")
        return {"accountId": "unchanged"}


def make_install_root(tmp_path):
    root = tmp_path / "core"
    bundle = root / "integrations" / "dsh-session-adapter"
    bundle.mkdir(parents=True)
    (bundle / "package.json").write_text(json.dumps({"name": NEW}))
    (bundle / "install.mjs").write_text("// fixture entry, subprocess is mocked\n")
    launcher = tmp_path / "official" / "lib" / "bin.js"
    launcher.parent.mkdir(parents=True)
    launcher.write_text("// official launcher fixture\n")
    (launcher.parent.parent / "package.json").write_text(json.dumps({"name": "@deepseek-ai/dsh"}))
    return root, launcher


def test_unauthorized_upgrade_creates_no_backup_or_child(tmp_path, monkeypatch):
    from oai_core.dsh_installation import install_dsh_adapter
    import oai_core.dsh_installation as installer

    profile = make_profile(tmp_path / "dsh", "web")
    root, launcher = make_install_root(tmp_path)
    def forbidden(*args, **kwargs):
        pytest.fail("unauthorized installer started a process")
    monkeypatch.setattr(installer.subprocess, "Popen", forbidden)
    store = Management(allowed=False)
    with pytest.raises(DshAdapterInstallError) as caught:
        install_dsh_adapter(root, store, "administrator-secret", profile)
    assert caught.value.code == "DSH_PERMISSION_DENIED"
    assert "private administrator detail" not in str(caught.value)
    assert not (root / "runtime").exists()
    assert store.calls == [("administrator-secret", "agents.manage")]


class SuccessfulChild:
    def __init__(self, returncode=0):
        self.returncode = returncode
        self.pid = 12345678

    def wait(self, timeout):
        return self.returncode


def stub_tools(monkeypatch, installer, launcher):
    monkeypatch.setattr(installer.shutil, "which", lambda tool: str(launcher) if tool == "dsh" else "/usr/bin/node")


def test_upgrade_has_private_complete_backups_and_relative_secret_free_child(tmp_path, monkeypatch):
    import oai_core.dsh_installation as installer
    from oai_core import configuration

    flushed = []
    sync_directory = configuration.fsync_directory
    def remember_sync(directory):
        flushed.append(directory)
        sync_directory(directory)
    monkeypatch.setattr(configuration, "fsync_directory", remember_sync)

    profile = make_profile(tmp_path / "dsh", "web")
    root, launcher = make_install_root(tmp_path)
    original = (profile.directory / "package.json").read_bytes()
    lock = b"lockfileVersion: '9.0'\n"
    (profile.directory / "pnpm-lock.yaml").write_bytes(lock)
    protected = {}
    for name in ("account.json", "master.key", "history.json", "cordis.patch.yml"):
        p = profile.directory / name
        p.write_text("private old " + name)
        protected[p] = p.read_bytes()
    shared = profile.directory / "node_modules" / OLD
    shared.mkdir(parents=True)
    (shared / "index.js").write_text("shared repository must remain unchanged")
    protected[shared / "index.js"] = (shared / "index.js").read_bytes()
    store = Management()
    calls = []
    stub_tools(monkeypatch, installer, launcher)
    monkeypatch.setenv("OAI_ADMIN_TOKEN", "sensitive-token")
    monkeypatch.setenv("NODE_OPTIONS", "--require=profile-code.js")
    def popen(arguments, **kwargs):
        backups = list((root / "runtime" / "dsh-adapter-backups").iterdir())
        assert len(backups) == 1
        backup = backups[0]
        assert {backup, backup.parent, backup.parent.parent, root}.issubset(flushed)
        assert (backup / "package.json").read_bytes() == original
        assert (backup / "pnpm-lock.yaml").read_bytes() == lock
        metadata = json.loads((backup / "metadata.json").read_text())
        assert metadata["profile"] == "web"
        for key in ("home", "directory", "bundle", "launcher"):
            assert not Path(metadata[key]).is_absolute()
        assert stat.S_IMODE(backup.stat().st_mode) == 0o700
        for p in backup.iterdir():
            assert stat.S_IMODE(p.stat().st_mode) == 0o600
        assert kwargs["cwd"] == root
        assert not any(secret in str((arguments, kwargs)) for secret in ("sensitive-token", "administrator-secret", "profile-code"))
        assert kwargs["stdin"] == installer.subprocess.DEVNULL
        assert kwargs["stdout"] == installer.subprocess.DEVNULL
        assert kwargs["stderr"] == installer.subprocess.DEVNULL
        assert all(not Path(a).is_absolute() for a in arguments[1:])
        assert store.calls[-1] == ("administrator-secret", "agents.manage")
        calls.append((arguments, kwargs))
        return SuccessfulChild()
    monkeypatch.setattr(installer.subprocess, "Popen", popen)
    installer.install_dsh_adapter(root, store, "administrator-secret", profile)
    assert len(calls) == 1
    assert len(store.calls) >= 3  # entry, backup, immediate process boundary
    assert all(p.read_bytes() == content for p, content in protected.items())


@pytest.mark.parametrize("tool,code", [("dsh", "DSH_CLI_NOT_FOUND"), ("node", "DSH_NODE_NOT_FOUND")])
def test_missing_cli_is_safe_and_does_not_create_backups(tmp_path, monkeypatch, tool, code):
    import oai_core.dsh_installation as installer
    profile = make_profile(tmp_path / "dsh", "web")
    root, launcher = make_install_root(tmp_path)
    monkeypatch.setattr(installer.shutil, "which", lambda name: None if name == tool else str(launcher))
    with pytest.raises(DshAdapterInstallError) as caught:
        installer.install_dsh_adapter(root, Management(), "admin", profile)
    assert caught.value.code == code
    assert not (root / "runtime").exists()


def test_authorization_is_rechecked_before_backup(tmp_path, monkeypatch):
    import oai_core.dsh_installation as installer
    profile = make_profile(tmp_path / "dsh", "web")
    root, launcher = make_install_root(tmp_path)
    stub_tools(monkeypatch, installer, launcher)
    store = Management()
    original = store.require_management
    def expired(handle, scope):
        original(handle, scope)
        if len(store.calls) >= 2:
            raise PermissionError("expired administrator-secret")
    store.require_management = expired
    with pytest.raises(DshAdapterInstallError) as caught:
        installer.install_dsh_adapter(root, store, "admin", profile)
    assert caught.value.code == "DSH_PERMISSION_DENIED"
    assert not (root / "runtime").exists()


def test_failed_child_retains_backups_and_hides_diagnostics(tmp_path, monkeypatch):
    import oai_core.dsh_installation as installer
    profile = make_profile(tmp_path / "dsh", "web")
    root, launcher = make_install_root(tmp_path)
    stub_tools(monkeypatch, installer, launcher)
    def failed(*args, **kwargs):
        raise OSError("stderr token=administrator-secret stdout=private-account")
    monkeypatch.setattr(installer.subprocess, "Popen", failed)
    with pytest.raises(DshAdapterInstallError) as caught:
        installer.install_dsh_adapter(root, Management(), "admin", profile)
    assert caught.value.code == "DSH_INSTALL_FAILED"
    assert "administrator-secret" not in str(caught.value)
    assert "private-account" not in str(caught.value)
    assert len(list((root / "runtime" / "dsh-adapter-backups").glob("*/package.json"))) == 1


@pytest.mark.parametrize("status,code", [(20, "DSH_PLUGIN_MANAGER_UNAVAILABLE"), (21, "DSH_PROFILE_UNSAFE"),
    (22, "DSH_PROFILE_CHANGED"), (23, "DSH_INSTALL_INCOMPATIBLE"), (24, "DSH_INSTALL_TIMEOUT"),
    (25, "DSH_BUNDLE_INVALID"), (26, "DSH_INSTALL_FAILED"), (27, "DSH_INSTALL_RECOVERY_REQUIRED"), (-15, "DSH_INSTALL_FAILED")])
def test_child_status_uses_stable_safe_errors(tmp_path, monkeypatch, status, code):
    import oai_core.dsh_installation as installer
    profile = make_profile(tmp_path / "dsh", "web")
    root, launcher = make_install_root(tmp_path)
    stub_tools(monkeypatch, installer, launcher)
    monkeypatch.setattr(installer.subprocess, "Popen", lambda *args, **kwargs: SuccessfulChild(status))
    with pytest.raises(DshAdapterInstallError) as caught:
        installer.install_dsh_adapter(root, Management(), "admin", profile)
    assert caught.value.code == code
    backup = next((root / "runtime" / "dsh-adapter-backups").iterdir())
    assert (backup / "package.json").read_bytes() == (profile.directory / "package.json").read_bytes()
    assert not (backup / "pnpm-lock.yaml").exists()


def test_expired_permission_before_process_preserves_backup_without_running(tmp_path, monkeypatch):
    import oai_core.dsh_installation as installer
    profile = make_profile(tmp_path / "dsh", "web")
    root, launcher = make_install_root(tmp_path)
    stub_tools(monkeypatch, installer, launcher)
    store = Management()
    original = store.require_management
    def expired(handle, scope):
        original(handle, scope)
        if len(store.calls) >= 3:
            raise PermissionError("administrator-secret")
    store.require_management = expired
    calls = []
    monkeypatch.setattr(installer.subprocess, "Popen", lambda *args, **kwargs: calls.append(True))
    with pytest.raises(DshAdapterInstallError) as caught:
        installer.install_dsh_adapter(root, store, "admin", profile)
    assert caught.value.code == "DSH_PERMISSION_DENIED"
    assert len(list((root / "runtime" / "dsh-adapter-backups").glob("*/package.json"))) == 1
    assert calls == []


def test_subprocess_timeout_is_bounded_and_retains_backup(tmp_path, monkeypatch):
    import oai_core.dsh_installation as installer
    profile = make_profile(tmp_path / "dsh", "web")
    root, launcher = make_install_root(tmp_path)
    stub_tools(monkeypatch, installer, launcher)
    class TimeoutChild(SuccessfulChild):
        def wait(self, timeout):
            raise installer.subprocess.TimeoutExpired("secret-node-diagnostic", timeout)
    signals = []
    monkeypatch.setattr(installer.subprocess, "Popen", lambda *args, **kwargs: TimeoutChild())
    monkeypatch.setattr(installer.os, "killpg", lambda pid, sig: signals.append((pid, sig)))
    with pytest.raises(DshAdapterInstallError) as caught:
        installer.install_dsh_adapter(root, Management(), "admin", profile)
    assert caught.value.code == "DSH_INSTALL_TIMEOUT"
    assert "secret-node-diagnostic" not in str(caught.value)
    assert len(signals) == 2
    assert list((root / "runtime" / "dsh-adapter-backups").glob("*/package.json"))


def test_wait_failure_is_sanitized(tmp_path, monkeypatch):
    import oai_core.dsh_installation as installer
    profile = make_profile(tmp_path / "dsh", "web")
    root, launcher = make_install_root(tmp_path)
    stub_tools(monkeypatch, installer, launcher)
    class BrokenChild(SuccessfulChild):
        def wait(self, timeout):
            raise OSError("raw private diagnostic")
    monkeypatch.setattr(installer.subprocess, "Popen", lambda *args, **kwargs: BrokenChild())
    with pytest.raises(DshAdapterInstallError) as caught:
        installer.install_dsh_adapter(root, Management(), "admin", profile)
    assert caught.value.code == "DSH_INSTALL_FAILED"
    assert "raw private diagnostic" not in str(caught.value)


def test_discovery_does_not_normalize_away_unchecked_parents(tmp_path, monkeypatch):
    monkeypatch.delenv("DSH_PROFILE", raising=False)
    profile = make_profile(tmp_path / "dsh", "web")
    assert discover_dsh_profiles([profile.home / "unchecked" / ".."]) == []


def test_relative_node_path_is_anchored_before_running_in_core_directory(tmp_path, monkeypatch):
    import oai_core.dsh_installation as installer
    profile = make_profile(tmp_path / "dsh", "web")
    root, launcher = make_install_root(tmp_path)
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(installer.shutil, "which", lambda tool: str(launcher) if tool == "dsh" else "commands/node")
    calls = []
    def popen(arguments, **kwargs):
        assert arguments[0] == str(tmp_path / "commands/node")
        assert kwargs["cwd"] == root
        calls.append(arguments)
        return SuccessfulChild()
    monkeypatch.setattr(installer.subprocess, "Popen", popen)
    installer.install_dsh_adapter(root, Management(), "admin", profile)
    assert len(calls) == 1
