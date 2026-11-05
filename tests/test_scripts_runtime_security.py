"""Isolated regressions for installer administration and optional MFA."""
from __future__ import annotations

import base64
from contextlib import closing
import json
import os
from pathlib import Path
import secrets
import sqlite3
import subprocess
import sys

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.twofactor.totp import TOTP
import pytest

from oai_core import scripts_runtime as runtime
from oai_core.gateway.account_paths import sha256_hex
from oai_core.gateway.credentials import hash_password
from oai_core.gateway.local_keys import LocalMasterKeyStore, create_master_key_file
from oai_core.identity import IdentityStore, issue_bootstrap_proof
from oai_core.management import LocalRecovery

REPO = Path(__file__).resolve().parents[1]


@pytest.fixture
def tmp_path():
    parent = REPO / ".worktrees" / "scripts-runtime-tests"
    parent.mkdir(parents=True, exist_ok=True)
    path = parent / secrets.token_hex(12)
    path.mkdir(mode=0o700)
    return path


@pytest.fixture(autouse=True)
def isolated_home(tmp_path, monkeypatch):
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("HERMES_HOME", str(home / "absent-hermes"))
    monkeypatch.setenv("DSH_HOME", str(home / "absent-dsh"))
    monkeypatch.setattr(runtime.sys.stdin, "isatty", lambda: True)
    monkeypatch.setattr(runtime.sys.stdout, "isatty", lambda: True)
    return home


def bootstrap(root):
    storage, key, database = runtime._paths(root)
    read, write = os.pipe()
    try:
        os.write(write, b"bootstrap\n")
        os.close(write)
        write = -1
        proof = issue_bootstrap_proof(database, storage, confirmation_fd=read)
    finally:
        os.close(read)
        if write >= 0:
            os.close(write)
    store = IdentityStore(database, key_store=LocalMasterKeyStore(key), create=True, bootstrap_proof=proof)
    admin = store.bootstrap("admin", "synthetic-password", confirmed=True)
    return store, admin


def test_default_off_authentication_does_not_prompt_for_second_factor(tmp_path, monkeypatch):
    root = tmp_path / "installation"
    root.mkdir()
    store, admin = bootstrap(root)
    prompts = []
    monkeypatch.setattr(runtime, "_input", lambda prompt: "admin")
    monkeypatch.setattr(runtime, "secret_input", lambda prompt: prompts.append(prompt) or "synthetic-password")
    try:
        handle = runtime.authenticate(store, {"install"})
        assert store.require_management(handle, "install")["accountId"] == admin["accountId"]
        assert prompts == ["管理员密码: "]
    finally:
        store.close()


def test_enabled_policy_requires_real_challenge_and_factor(tmp_path, monkeypatch):
    root = tmp_path / "installation"
    root.mkdir()
    store, admin = bootstrap(root)
    _, key, _ = runtime._paths(root)
    key.parent.mkdir(parents=True, exist_ok=True)
    create_master_key_file(key)
    try:
        proof = store.authenticate("admin", "synthetic-password", purpose="enroll")["enrollmentProof"]
        pending = store.begin_mfa_enrollment(admin["accountId"], proof)
        secret = base64.b32decode(pending["secret"] + "=" * (-len(pending["secret"]) % 8))
        import time
        otp = TOTP(secret, 6, hashes.SHA1(), 30).generate(time.time()).decode()
        codes = store.confirm_mfa_enrollment(admin["accountId"], proof, otp,
                                             enrollment_id=pending["enrollmentId"])["recoveryCodes"]
        store.complete_authentication(proof, purpose="enroll")
        original = store.begin_management("admin", "synthetic-password")
        LocalRecovery(store).register(original, confirmed=True)
        store.set_mfa_required(original, True)
        answers = iter(["admin", "2"])
        prompts = []
        secret_answers = iter(["synthetic-password", codes[0]])
        monkeypatch.setattr(runtime, "_input", lambda prompt: next(answers))
        monkeypatch.setattr(runtime, "secret_input", lambda prompt: prompts.append(prompt) or next(secret_answers))
        handle = runtime.authenticate(store, {"install"})
        assert store.require_management(handle, "install")["accountId"] == admin["accountId"]
        assert prompts == ["管理员密码: ", "恢复码: "]
    finally:
        store.close()


def test_new_install_executes_real_identity_configuration_with_fake_commands(tmp_path, monkeypatch):
    root = tmp_path / "installation"
    root.mkdir()
    answers = iter(["admin", "bootstrap", "SAVED", "n"])
    monkeypatch.setattr(runtime, "_input", lambda prompt: next(answers))
    monkeypatch.setattr(runtime, "secret_input", lambda prompt: "synthetic-password")
    python = root / ".venv/bin/python"
    python.parent.mkdir(parents=True)
    python.write_text("synthetic python")
    commands = []
    monkeypatch.setattr(runtime.subprocess, "run", lambda args, **kwargs: commands.append(args))
    assert runtime.install(root) == 0
    config = json.loads((root / "core.json").read_text())
    assert config["listen"] == {"host": "0.0.0.0", "port": 11451}
    assert config["allowInsecureHttp"] is True
    assert json.loads((root / "runtime/installer-journal.json").read_text())["status"] == "complete"
    assert (root / "runtime/secrets/core-master-key").is_file()
    assert (root / "start-core.sh").is_file()
    assert (root / commands[0][-1]).resolve() == Path(runtime.__file__).resolve().parents[2]
    assert commands[1][-1] == "check"
    _, _, database = runtime._paths(root)
    with closing(IdentityStore(database)) as store:
        assert store.authenticate("admin", "synthetic-password")["role"] == "admin"


def test_existing_install_wrong_password_never_executes_commands(tmp_path, monkeypatch):
    root = tmp_path / "installation"
    root.mkdir()
    store, _ = bootstrap(root)
    store.close()
    monkeypatch.setattr(runtime, "_input", lambda prompt: "admin")
    monkeypatch.setattr(runtime, "secret_input", lambda prompt: "wrong-password")
    python = root / ".venv/bin/python"
    python.parent.mkdir(parents=True)
    python.write_text("synthetic python")
    before = sorted(str(path.relative_to(root)) for path in root.rglob("*") if path.is_file())
    commands = []
    monkeypatch.setattr(runtime.subprocess, "run", lambda args, **kwargs: commands.append(args))
    with pytest.raises(Exception):
        runtime.install(root)
    assert commands == []
    assert sorted(str(path.relative_to(root)) for path in root.rglob("*") if path.is_file()) == before
    assert not (root / "core.json").exists()


def test_existing_install_rechecks_scope_before_each_external_action(tmp_path, monkeypatch):
    root = tmp_path / "installation"
    root.mkdir()
    store, _ = bootstrap(root)
    store.close()
    (root / "core.json").write_text(json.dumps({"storageRoot": "runtime/accounts", "masterKeyFile": "runtime/key", "listen": {"host": "0.0.0.0", "port": 11451}, "agents": []}))
    (root / "start-core.sh").write_text("# existing start\n")
    monkeypatch.setattr(runtime, "_input", lambda prompt: "admin" if "账号" in prompt else "SAVED" if "SAVED" in prompt else "n")
    monkeypatch.setattr(runtime, "secret_input", lambda prompt: "synthetic-password")
    python = root / ".venv/bin/python"
    python.parent.mkdir(parents=True)
    python.write_text("synthetic python")
    commands = []
    monkeypatch.setattr(runtime.subprocess, "run", lambda args, **kwargs: commands.append(args))
    assert runtime.install(root) == 0
    assert len(commands) == 2
    assert all("synthetic-password" not in str(command) for command in commands)


def test_setup_exit_is_read_only_and_has_five_categories(tmp_path, monkeypatch, capsys):
    root = tmp_path / "installation"
    root.mkdir()
    store, _ = bootstrap(root)
    (root / "core.json").write_text(json.dumps({"storageRoot": "runtime/accounts", "masterKeyFile": "runtime/key", "agents": [], "listen": {"host": "0.0.0.0", "port": 11451}}))
    handle = store.begin_management("admin", "synthetic-password")
    monkeypatch.setattr(runtime.configuration, "_terminal_input", lambda prompt: "5")
    try:
        runtime._run_setup(root, store, handle)
        output = capsys.readouterr().out
        assert all(item in output for item in ("1. 网络设置", "2. 账号管理", "3. Agent 接入", "4. 服务管理", "5. 退出"))
        assert not (root / "runtime/configuration-journal.json").exists()
    finally:
        store.close()


def test_legacy_migration_authenticates_before_writes_and_installs_barrier(tmp_path, monkeypatch):
    root = tmp_path / "installation"
    root.mkdir()
    runtime_dir = root / "runtime"
    runtime_dir.mkdir(mode=0o700)
    storage = runtime_dir / "accounts"
    from oai_core.gateway.core import GatewayCore
    from oai_core.gateway.local_keys import create_master_key_file
    key = runtime_dir / "secrets/core-master-key"
    key.parent.mkdir(mode=0o700)
    create_master_key_file(key)
    core = GatewayCore(storage_root=storage, secret_store=LocalMasterKeyStore(key))
    for name in ("alice", "bob"):
        with closing(core.open_gateway_account(name)) as account:
            account.credentials.create_password(name + "-password")
            account.store.database.execute("INSERT INTO account_metadata VALUES ('history','preserved')")
    (root / "core.json").write_text(json.dumps({"storageRoot": "runtime/accounts", "masterKeyFile": "runtime/secrets/core-master-key", "listen": {"host": "0.0.0.0", "port": 11451}, "agents": []}))
    (runtime_dir / "configuration-journal.json").write_text(json.dumps({"version": 1, "status": "committed", "entries": []}))
    (runtime_dir / "configuration-journal.json").chmod(0o600)
    python = root / ".venv/bin/python"
    python.parent.mkdir(parents=True)
    python.write_text("synthetic python")
    commands = []
    monkeypatch.setattr(runtime.subprocess, "run", lambda args, **kwargs: commands.append(args))
    monkeypatch.setattr(runtime, "_input", lambda prompt: "alice")
    monkeypatch.setattr(runtime, "secret_input", lambda prompt: "wrong")
    with pytest.raises(PermissionError):
        runtime.install(root)
    assert commands == []
    assert not (runtime_dir / "security/identity.sqlite").exists()
    answers = iter(["alice", "STOP", "SAVED", "n"])
    monkeypatch.setattr(runtime, "_input", lambda prompt: next(answers))
    monkeypatch.setattr(runtime, "secret_input", lambda prompt: "alice-password")
    monkeypatch.setattr(runtime, "_old_writers_stopped", lambda storage: True)
    assert runtime.install(root) == 0
    identity = IdentityStore(runtime_dir / "security/identity.sqlite")
    try:
        assert identity.authenticate("alice", "alice-password")["role"] == "admin"
        assert identity.authenticate("bob", "bob-password")["role"] == "user"
    finally:
        identity.close()
    database = storage / sha256_hex("alice") / "gateway.sqlite"
    with closing(sqlite3.connect(database)) as old:
        assert old.execute("SELECT value FROM account_metadata WHERE key='history'").fetchone()[0] == "preserved"
        with pytest.raises(sqlite3.Error):
            old.execute("UPDATE account_credentials SET password_hash='old-writer'")
    assert list((runtime_dir / "identity-migrations").glob("*/*.sqlite"))
    assert len(commands) == 2


def test_authenticated_linux_service_setup_with_fake_systemctl(tmp_path, monkeypatch):
    root = tmp_path / "installation with spaces"
    root.mkdir()
    store, _ = bootstrap(root)
    (root / "start-core.sh").write_text("# synthetic\n")
    handle = store.begin_management("admin", "synthetic-password")
    calls = []
    monkeypatch.setattr(runtime.shutil, "which", lambda name: "/synthetic/systemctl")
    monkeypatch.setattr(runtime.subprocess, "run", lambda args, **kwargs: calls.append(args))
    try:
        runtime._configure_service(root, store, handle)
        unit = Path.home() / ".config/systemd/user/oai-core.service"
        assert f'ExecStart="{root}/start-core.sh"' in unit.read_text()
        assert [args[-1] for args in calls] == ["daemon-reload", "oai-core.service", "oai-core.service"]
        store.revoke_management(handle)
        with pytest.raises(Exception):
            runtime._configure_service(root, store, handle)
        assert len(calls) == 3
    finally:
        store.close()


def test_uninstall_real_shell_requires_admin_and_preserves_data(tmp_path, monkeypatch):
    import errno
    import pty
    import select
    import shutil
    import termios
    import time

    root = tmp_path / "installation"
    root.mkdir()
    (root / "oai-core").mkdir()
    (root / "oai-core/pyproject.toml").write_text('[project]\nname = "open-android-intelligence-core"\n')
    (root / "scripts").mkdir()
    (root / "scripts/install.sh").write_text("# installer marker\n")
    (root / ".venv").mkdir()
    (root / "start-core.sh").write_text("# start\n")
    store, _ = bootstrap(root)
    store.close()
    config = {"storageRoot": "runtime/accounts", "masterKeyFile": "runtime/secrets/core-master-key"}
    (root / "core.json").write_text(json.dumps(config))
    shutil.copytree(REPO / "oai-core/src", root / "oai-core/src", ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    interpreter = Path(sys.executable).parent
    env = dict(os.environ, PYTHONPATH=str(root / "oai-core/src"), PATH=str(interpreter) + os.pathsep + "/usr/bin:/bin")
    pid, fd = pty.fork()
    if pid == 0:
        os.execve("/usr/bin/bash", ["bash", str(REPO / "scripts/uninstall.sh"), "--yes", "--dir", str(root)], env)
    output = bytearray()
    username_sent = password_sent = False
    status = None
    deadline = time.monotonic() + 15
    try:
        while time.monotonic() < deadline:
            if select.select([fd], [], [], 0.05)[0]:
                try:
                    output.extend(os.read(fd, 8192))
                except OSError as error:
                    if error.errno != errno.EIO:
                        raise
            if not username_sent and "管理员账号".encode() in output:
                os.write(fd, b"admin\n")
                username_sent = True
            if username_sent and not password_sent and "管理员密码".encode() in output:
                if not termios.tcgetattr(fd)[3] & termios.ECHO:
                    os.write(fd, b"synthetic-password\n")
                    password_sent = True
            waited, status = os.waitpid(pid, os.WNOHANG)
            if waited:
                break
        else:
            raise AssertionError(output.decode(errors="replace"))
        assert os.waitstatus_to_exitcode(status) == 0, output.decode(errors="replace")
        assert username_sent and password_sent
        assert b"synthetic-password" not in output
        assert not (root / ".venv").exists()
        assert not (root / "start-core.sh").exists()
        assert (root / "runtime/security/identity.sqlite").is_file()
    finally:
        os.close(fd)


def test_scripts_syntax_and_uninstall_preview_without_authentication(tmp_path):
    for name in ("install.sh", "setup.sh", "uninstall.sh"):
        assert subprocess.run(["bash", "-n", str(REPO / "scripts" / name)], check=False).returncode == 0
    result = subprocess.run(["bash", str(REPO / "scripts/uninstall.sh"), "--dir", str(tmp_path / "missing"), "--dry-run"],
                            capture_output=True, text=True, start_new_session=True)
    assert result.returncode == 0
