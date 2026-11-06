"""旧部署仅保留运行数据时的源码恢复安全回归测试。"""
import builtins
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest

from oai_core import scripts_runtime as runtime


REPOSITORY = Path(__file__).resolve().parents[1]


def write_checkout(source: Path, files: dict[str, str]) -> None:
    for name, content in files.items():
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


def runtime_only(root: Path) -> dict[str, bytes]:
    files = {
        "runtime/accounts/alice/gateway.sqlite": b"synthetic-account",
        "runtime/secrets/core-master-key": b"synthetic-master-key",
        "runtime/security/identity.sqlite": b"synthetic-identity",
    }
    for name, content in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return files


def snapshot(root: Path) -> dict[str, bytes]:
    return {path.relative_to(root).as_posix(): path.read_bytes()
            for path in root.rglob("*") if path.is_file()}


def test_shell_stdin_recovers_runtime_only_target_through_staging(tmp_path):
    root = tmp_path / "deployment"
    root.mkdir()
    before = runtime_only(root)
    trusted = tmp_path / "trusted-checkout"
    write_checkout(trusted, {
        "scripts/installer.py": "# synthetic trusted installer\n",
        "oai-core/pyproject.toml": "[project]\nname = 'synthetic-core'\n",
        "oai-core/src/oai_core/scripts_runtime.py": "# synthetic runtime\n",
    })
    commands = tmp_path / "commands"
    commands.mkdir()
    python_log = tmp_path / "python-arguments.jsonl"
    git_log = tmp_path / "git-arguments.jsonl"
    fake_python = commands / "python3"
    fake_python.write_text(
        f"#!{Path(sys.executable).resolve()}\n"
        "import json, os, sys\n"
        "args = sys.argv[1:]\n"
        "with open(os.environ['FAKE_PYTHON_LOG'], 'a', encoding='utf-8') as out:\n"
        "    out.write(json.dumps(args) + '\\n')\n"
        "if args[:2] == ['-I', '-c']:\n"
        "    raise SystemExit(0)\n",
        encoding="utf-8",
    )
    fake_python.chmod(0o700)
    fake_git = commands / "git"
    fake_git.write_text(
        f"#!{Path(sys.executable).resolve()}\n"
        "import json, os, shutil, sys\n"
        "args = sys.argv[1:]\n"
        "with open(os.environ['FAKE_GIT_LOG'], 'a', encoding='utf-8') as out:\n"
        "    out.write(json.dumps(args) + '\\n')\n"
        "if args and args[0] == 'clone':\n"
        "    shutil.copytree(os.environ['FAKE_TRUSTED_SOURCE'], args[-1], dirs_exist_ok=True)\n",
        encoding="utf-8",
    )
    fake_git.chmod(0o700)
    env = os.environ.copy()
    env["PATH"] = str(commands) + os.pathsep + env.get("PATH", "")
    env["FAKE_PYTHON_LOG"] = str(python_log)
    env["FAKE_GIT_LOG"] = str(git_log)
    env["FAKE_TRUSTED_SOURCE"] = str(trusted)

    result = subprocess.run(
        ["bash", "-s", "--", "--dir", str(root)],
        input=(REPOSITORY / "scripts/install.sh").read_text(encoding="utf-8"),
        capture_output=True,
        text=True,
        env=env,
        timeout=10,
    )

    assert result.returncode == 0, result.stderr
    assert json.loads(git_log.read_text(encoding="utf-8").splitlines()[0])[0] == "clone"
    python_calls = [json.loads(line) for line in python_log.read_text(encoding="utf-8").splitlines()]
    expected_stages = list(tmp_path.glob("deployment.download.*"))
    assert len(expected_stages) == 1
    stage = expected_stages[0]
    assert stage.parent == root.parent
    assert ["-I", str(stage / "scripts/installer.py"), "install", "--dir", str(root)] in python_calls
    assert snapshot(root) == before


def test_installer_main_passes_trusted_source_for_data_only_target(tmp_path, monkeypatch):
    root = tmp_path / "deployment"
    root.mkdir()
    runtime_only(root)
    source = tmp_path / "trusted-source"
    write_checkout(source, {
        "oai-core/pyproject.toml": "[project]\nname = 'synthetic-core'\n",
        "oai-core/src/oai_core/scripts_runtime.py": "# trusted runtime marker\n",
    })
    installer_path = source / "scripts/installer.py"
    installer_path.parent.mkdir(parents=True)
    shutil.copy2(REPOSITORY / "scripts/installer.py", installer_path)
    spec = importlib.util.spec_from_file_location("recovery_installer", installer_path)
    installer = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(installer)
    calls = []

    def install(actual_root, *, restore_source=None):
        calls.append((actual_root, restore_source))
        return 23

    class Terminal:
        @staticmethod
        def isatty():
            return True

    monkeypatch.setattr(installer.sys, "argv", [str(installer_path), "install", "--dir", str(root)])
    monkeypatch.setattr(installer.sys, "stdin", Terminal())
    monkeypatch.setattr(installer.sys, "path", list(installer.sys.path))
    monkeypatch.setattr(runtime, "install", install)

    assert installer.main() == 23
    assert calls == [(root, source)]


def test_restore_copies_code_preserves_data_and_publishes_marker_last(tmp_path, monkeypatch):
    root = tmp_path / "deployment"
    root.mkdir()
    config = {
        "storageRoot": "custom/accounts",
        "masterKeyFile": "custom/master.key",
        "identityDatabase": "custom/identity.sqlite",
    }
    runtime.configuration.private_write(root / "core.json", json.dumps(config))
    preserved = {
        "start-core.sh": b"existing launcher",
        "runtime/accounts/alice/gateway.sqlite": b"runtime account",
        "custom/accounts/alice.sqlite": b"custom account",
        "custom/master.key": b"custom key",
        "custom/identity.sqlite": b"custom identity",
    }
    for name, content in preserved.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    before = snapshot(root)
    source = tmp_path / "trusted-source"
    tracked = [
        "oai-core/pyproject.toml",
        "core.json",
        "start-core.sh",
        "runtime/accounts/alice/gateway.sqlite",
        "custom/accounts/alice.sqlite",
        "custom/master.key",
        "custom/identity.sqlite",
        "scripts/installer.py",
        "oai-core/src/oai_core/recovered.py",
    ]
    write_checkout(source, {name: f"trusted:{name}\n" for name in tracked})
    marker = root / "oai-core/pyproject.toml"
    recovered = root / "oai-core/src/oai_core/recovered.py"
    installer = root / "scripts/installer.py"
    authorization_checks = []

    class Store:
        def require_management(self, handle, scope):
            assert handle == "test-handle"
            assert scope == "install"
            authorization_checks.append(marker.exists())

    def mock_git(args, **kwargs):
        assert args == ["git", "-C", str(source.resolve()), "ls-files", "-z"]
        assert kwargs["check"] is True and kwargs["capture_output"] is True
        return subprocess.CompletedProcess(args, 0, "\0".join(tracked) + "\0", "")

    replaces = []
    real_replace = os.replace

    def record_replace(original, destination):
        relative = Path(destination).relative_to(root).as_posix()
        if relative == "oai-core/pyproject.toml":
            assert recovered.is_file()
            assert installer.is_file()
        replaces.append(relative)
        return real_replace(original, destination)

    monkeypatch.setattr(runtime.subprocess, "run", mock_git)
    monkeypatch.setattr(runtime.os, "replace", record_replace)
    runtime._restore_install_source(root, source, Store(), "test-handle")

    assert marker.read_text(encoding="utf-8") == "trusted:oai-core/pyproject.toml\n"
    assert recovered.read_text(encoding="utf-8") == "trusted:oai-core/src/oai_core/recovered.py\n"
    assert installer.read_text(encoding="utf-8") == "trusted:scripts/installer.py\n"
    for name, content in before.items():
        assert (root / name).read_bytes() == content
    assert authorization_checks and not any(authorization_checks)
    assert replaces[-1] == "oai-core/pyproject.toml"
    assert not list(root.rglob("*.restore.*.tmp"))


def test_restore_authorization_failure_does_not_run_git_or_copy(tmp_path, monkeypatch):
    root = tmp_path / "deployment"
    root.mkdir()
    before = runtime_only(root)
    source = tmp_path / "trusted-source"
    source.mkdir()
    git_calls = []

    class DeniedStore:
        def require_management(self, handle, scope):
            raise PermissionError("授权已撤销")

    def forbidden_git(*args, **kwargs):
        git_calls.append(args)
        raise AssertionError("授权拒绝后不应调用 git")

    monkeypatch.setattr(runtime.subprocess, "run", forbidden_git)
    with pytest.raises(PermissionError, match="授权已撤销"):
        runtime._restore_install_source(root, source, DeniedStore(), "revoked-handle")

    assert git_calls == []
    assert snapshot(root) == before


def test_install_authentication_failure_never_restores_source_or_runs_pip(tmp_path, monkeypatch):
    root = tmp_path / "deployment"
    root.mkdir()
    identity = root / "runtime/security/identity.sqlite"
    identity.parent.mkdir(parents=True)
    identity.write_bytes(b"synthetic-existing-identity")
    key = root / "runtime/secrets/core-master-key"
    key.parent.mkdir(parents=True)
    key.write_bytes(b"synthetic-existing-key")
    storage = root / "runtime/accounts"
    storage.mkdir(parents=True)
    (storage / "account.marker").write_bytes(b"preserve")
    before = snapshot(root)
    source = tmp_path / "trusted-source"
    source.mkdir()
    restore_calls = []
    process_calls = []

    class Store:
        closed = False

        def close(self):
            self.closed = True

    store = Store()
    monkeypatch.setattr(runtime, "_paths", lambda actual_root: (storage, key, identity))
    monkeypatch.setattr(runtime, "_verify_journal", lambda actual_root, *, has_identity: None)
    monkeypatch.setattr(runtime, "IdentityStore", lambda database, *, key_store: store)
    monkeypatch.setattr(runtime, "LocalMasterKeyStore", lambda path: object())

    def reject_authentication(actual_store, scopes):
        raise PermissionError("密码错误")

    monkeypatch.setattr(runtime, "authenticate", reject_authentication)
    monkeypatch.setattr(runtime, "_restore_install_source",
                        lambda *args: restore_calls.append(args))
    monkeypatch.setattr(runtime.subprocess, "run",
                        lambda *args, **kwargs: process_calls.append(args))

    with pytest.raises(PermissionError, match="密码错误"):
        runtime.install(root, restore_source=source)

    assert store.closed
    assert restore_calls == []
    assert process_calls == []
    assert snapshot(root) == before


def test_missing_dependencies_prepare_environment_outside_retained_deployment(tmp_path, monkeypatch):
    """原环境依赖已丢失时，仅在外部恢复环境安装依赖并重新进入认证入口。"""
    root = tmp_path / "deployment"
    root.mkdir()
    before = runtime_only(root)
    source = tmp_path / "trusted-source"
    write_checkout(source, {
        "scripts/installer.py": (REPOSITORY / "scripts/installer.py").read_text(encoding="utf-8"),
        "oai-core/pyproject.toml": '[project]\nname="synthetic-core"\ndependencies=["jsonschema>=4.23,<5"]\n',
        "oai-core/src/oai_core/scripts_runtime.py": "# trusted runtime marker\n",
    })
    script = source / "scripts/installer.py"
    spec = importlib.util.spec_from_file_location("recovery_environment_installer", script)
    installer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(installer)
    monkeypatch.setattr(installer.sys, "argv", [str(script), "install", "--dir", str(root)])
    monkeypatch.setattr(installer.sys, "stdin", type("Terminal", (), {"isatty": lambda self: True})())
    monkeypatch.setattr(installer.sys, "path", list(installer.sys.path))
    monkeypatch.delenv("OAI_INSTALL_RECOVERY_ENV", raising=False)
    monkeypatch.setenv("OAI_INIT_ADMIN_PASSWORD", "synthetic-unused-legacy-password")
    real_import = builtins.__import__

    def missing_dependency(name, *args, **kwargs):
        if name == "oai_core.scripts_runtime":
            raise ModuleNotFoundError("模拟系统 Python 缺少 jsonschema", name="jsonschema")
        return real_import(name, *args, **kwargs)

    calls, launches = [], []
    monkeypatch.setattr(builtins, "__import__", missing_dependency)
    monkeypatch.setattr(installer.subprocess, "run", lambda args, **kwargs: calls.append((args, kwargs)))

    def launch(executable, arguments, env):
        launches.append((executable, arguments, env))
        raise SystemExit(42)

    monkeypatch.setattr(installer.os, "execve", launch)
    with pytest.raises(SystemExit) as result:
        installer.main()
    assert result.value.code == 42
    assert len(calls) == 2
    assert calls[0][0][:4] == [sys.executable, "-m", "venv", "--copies"]
    environment = Path(calls[0][0][-1])
    assert environment.parent == root.parent and root not in environment.parents
    python = environment / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    assert calls[1][0] == [str(python), "-m", "pip", "install", "jsonschema>=4.23,<5"]
    assert launches[0][1] == [str(python), "-I", str(script), "install", "--dir", str(root)]
    assert launches[0][2]["OAI_INSTALL_RECOVERY_ENV"] == "1"
    assert "OAI_INIT_ADMIN_PASSWORD" not in launches[0][2]
    assert snapshot(root) == before
    monkeypatch.setenv("OAI_INSTALL_RECOVERY_ENV", "1")
    with pytest.raises(ModuleNotFoundError):
        installer.main()
    assert len(calls) == 2 and len(launches) == 1


def test_shell_stdin_upgrades_existing_installation_through_staging(tmp_path):
    """已安装目录（含 pyproject.toml）通过 curl | bash 重跑时，应先克隆新源码到独立临时目录。"""
    root = tmp_path / "deployment"
    root.mkdir()
    runtime_only(root)
    write_checkout(root, {
        "core.json": '{"listen":{"host":"0.0.0.0","port":11451},"storageRoot":"runtime/accounts","masterKeyFile":"runtime/secrets/core-master-key","agents":[]}\n',
        "oai-core/pyproject.toml": "[project]\nname = 'old-core'\nversion = '0.1.0'\n",
        "scripts/installer.py": "# old local installer\n",
    })
    before = snapshot(root)
    trusted = tmp_path / "trusted-checkout"
    write_checkout(trusted, {
        "scripts/installer.py": "# new staged installer\n",
        "oai-core/pyproject.toml": "[project]\nname = 'new-core'\nversion = '0.2.0'\n",
        "oai-core/src/oai_core/scripts_runtime.py": "# new runtime\n",
    })
    commands = tmp_path / "commands"
    commands.mkdir()
    python_log = tmp_path / "python-arguments.jsonl"
    git_log = tmp_path / "git-arguments.jsonl"
    fake_python = commands / "python3"
    fake_python.write_text(
        f"#!{Path(sys.executable).resolve()}\n"
        "import json, os, sys\n"
        "args = sys.argv[1:]\n"
        "with open(os.environ['FAKE_PYTHON_LOG'], 'a', encoding='utf-8') as out:\n"
        "    out.write(json.dumps({'args': args, 'temp': os.environ.get('OAI_TEMP_DOWNLOAD_SOURCE')}) + '\\n')\n"
        "if args[:2] == ['-I', '-c']:\n"
        "    raise SystemExit(0)\n",
        encoding="utf-8",
    )
    fake_python.chmod(0o700)
    fake_git = commands / "git"
    fake_git.write_text(
        f"#!{Path(sys.executable).resolve()}\n"
        "import json, os, shutil, sys\n"
        "args = sys.argv[1:]\n"
        "with open(os.environ['FAKE_GIT_LOG'], 'a', encoding='utf-8') as out:\n"
        "    out.write(json.dumps(args) + '\\n')\n"
        "if args and args[0] == 'clone':\n"
        "    shutil.copytree(os.environ['FAKE_TRUSTED_SOURCE'], args[-1], dirs_exist_ok=True)\n",
        encoding="utf-8",
    )
    fake_git.chmod(0o700)
    env = os.environ.copy()
    env["PATH"] = str(commands) + os.pathsep + env.get("PATH", "")
    env["FAKE_PYTHON_LOG"] = str(python_log)
    env["FAKE_GIT_LOG"] = str(git_log)
    env["FAKE_TRUSTED_SOURCE"] = str(trusted)

    result = subprocess.run(
        ["bash", "-s", "--", "--dir", str(root)],
        input=(REPOSITORY / "scripts/install.sh").read_text(encoding="utf-8"),
        capture_output=True,
        text=True,
        env=env,
        timeout=10,
    )

    assert result.returncode == 0, result.stderr
    assert json.loads(git_log.read_text(encoding="utf-8").splitlines()[0])[0] == "clone"
    python_calls = [json.loads(line) for line in python_log.read_text(encoding="utf-8").splitlines()]
    expected_stages = list(tmp_path.glob("deployment.download.*"))
    assert len(expected_stages) == 1
    stage = expected_stages[0]
    assert {
        "args": ["-I", str(stage / "scripts/installer.py"), "install", "--dir", str(root)],
        "temp": "1",
    } in python_calls
    assert snapshot(root) == before


def test_repeat_install_with_upgrade_source_updates_code_skills_and_restarts_running_service(tmp_path, monkeypatch):
    """重复安装传入 upgrade_source 时，应在认证后更新源码与技能、执行 pip 并自动重启运行中的本实例服务。"""
    from oai_core.gateway.local_keys import LocalMasterKeyStore, create_master_key_file
    from oai_core.identity import IdentityStore, issue_bootstrap_proof
    from oai_core.management import LocalRecovery

    home = tmp_path / "home"
    home.mkdir(mode=0o700)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("HERMES_HOME", str(home / "absent-hermes"))
    monkeypatch.setenv("DSH_HOME", str(home / "absent-dsh"))
    monkeypatch.setattr(runtime.sys.stdin, "isatty", lambda: True)
    monkeypatch.setattr(runtime.sys.stdout, "isatty", lambda: True)

    root = tmp_path / "deployment"
    root.mkdir(mode=0o700)
    write_checkout(root, {
        "oai-core/pyproject.toml": "[project]\nname = 'open-android-intelligence-core'\nversion = '0.1.0'\n",
        "oai-core/src/oai_core/marker.py": "VERSION = '0.1.0'\n",
        "scripts/install.sh": "# v1\n",
        "integrations/skills/oai-client/SKILL.md": "# Skill v1\n",
        "start-core.sh": "#!/usr/bin/env bash\nexit 0\n",
    })
    (root / "start-core.sh").chmod(0o700)
    python_bin = root / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    python_bin.parent.mkdir(parents=True, exist_ok=True)
    python_bin.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    python_bin.chmod(0o700)

    storage, key, database = runtime._paths(root)
    key.parent.mkdir(parents=True, mode=0o700)
    create_master_key_file(key)
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
    store = IdentityStore(database, key_store=LocalMasterKeyStore(key), create=True, bootstrap_proof=proof)
    store.bootstrap("admin", "correct-password", confirmed=True)
    handle = store.begin_management("admin", "correct-password")
    LocalRecovery(store).register(handle, confirmed=True)
    store.close()

    config_text = json.dumps({
        "storageRoot": "runtime/accounts",
        "masterKeyFile": "runtime/secrets/core-master-key",
        "listen": {"host": "127.0.0.1", "port": 18451},
        "allowInsecureHttp": True,
        "agents": [],
    }) + "\n"
    runtime.configuration.private_write(root / "core.json", config_text)
    key_before = key.read_bytes()

    staged = tmp_path / "deployment.download.abc12345"
    write_checkout(staged, {
        "oai-core/pyproject.toml": "[project]\nname = 'open-android-intelligence-core'\nversion = '0.2.0'\n",
        "oai-core/src/oai_core/marker.py": "VERSION = '0.2.0'\n",
        "scripts/install.sh": "# v2\n",
        "integrations/skills/oai-client/SKILL.md": "# Skill v2\n",
    })
    monkeypatch.setenv("OAI_TEMP_DOWNLOAD_SOURCE", "1")

    unit = home / ".config/systemd/user/oai-core.service"
    unit.parent.mkdir(parents=True, exist_ok=True)
    unit.write_text(
        f"[Service]\nWorkingDirectory={root}\nExecStart=\"{root / 'start-core.sh'}\"\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(runtime.sys, "platform", "linux")
    monkeypatch.setattr(runtime.shutil, "which", lambda name: f"/usr/bin/{name}" if name == "systemctl" else None)

    executed: list[list[str]] = []

    def fake_run(args, **kwargs):
        executed.append(list(args))
        if args[:3] == ["systemctl", "--user", "show"]:
            return subprocess.CompletedProcess(
                args,
                0,
                f"FragmentPath={unit}\nWorkingDirectory={root}\nActiveState=active\n",
                "",
            )
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(runtime.subprocess, "run", fake_run)

    # 1. 密码错误时绝不更新文件、不跑 pip、不重启服务
    monkeypatch.setattr(runtime, "_input", lambda prompt: "admin")
    monkeypatch.setattr(runtime, "secret_input", lambda prompt: "wrong-password")
    with pytest.raises(Exception):
        runtime.install(root, upgrade_source=staged)
    assert executed == []
    assert (root / "oai-core/src/oai_core/marker.py").read_text(encoding="utf-8") == "VERSION = '0.1.0'\n"
    assert staged.is_dir()

    # 2. 密码正确且自启选 n 时，自动同步新代码、更新技能、执行 pip，并重启运行中的 oai-core.service
    inputs = iter(["admin", "n"])
    monkeypatch.setattr(runtime, "_input", lambda prompt: next(inputs))
    monkeypatch.setattr(runtime, "secret_input", lambda prompt: "correct-password")
    assert runtime.install(root, upgrade_source=staged) == 0

    assert (root / "oai-core/src/oai_core/marker.py").read_text(encoding="utf-8") == "VERSION = '0.2.0'\n"
    assert (root / "scripts/install.sh").read_text(encoding="utf-8") == "# v2\n"
    assert (home / ".agents/skills/oai-client/SKILL.md").read_text(encoding="utf-8") == "# Skill v2\n"
    assert (root / "core.json").read_text(encoding="utf-8") == config_text
    assert key.read_bytes() == key_before
    assert not staged.exists()
    assert list((root / "runtime/upgrade-downloads").glob("deployment.download.abc12345-*"))
    assert [str(python_bin), "-m", "pip", "install", "-e", "./oai-core"] in executed
    assert ["systemctl", "--user", "daemon-reload"] in executed
    assert ["systemctl", "--user", "restart", "oai-core.service"] in executed


def test_repeat_install_git_checkout_pulls_fast_forward_and_skips_dirty_worktree(tmp_path, monkeypatch):
    """本地 Git 仓库部署重复运行 install 时，认证后自动快进拉取远端新提交；有未提交修改则保留现场。"""
    from oai_core.gateway.local_keys import LocalMasterKeyStore, create_master_key_file
    from oai_core.identity import IdentityStore, issue_bootstrap_proof
    from oai_core.management import LocalRecovery

    home = tmp_path / "home"
    home.mkdir(mode=0o700)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("HERMES_HOME", str(home / "absent-hermes"))
    monkeypatch.setenv("DSH_HOME", str(home / "absent-dsh"))
    monkeypatch.setattr(runtime.sys.stdin, "isatty", lambda: True)
    monkeypatch.setattr(runtime.sys.stdout, "isatty", lambda: True)

    git_env = {
        **os.environ,
        "GIT_AUTHOR_NAME": "Test",
        "GIT_AUTHOR_EMAIL": "test@example.com",
        "GIT_COMMITTER_NAME": "Test",
        "GIT_COMMITTER_EMAIL": "test@example.com",
    }
    upstream = tmp_path / "upstream"
    upstream.mkdir()
    subprocess.run(["git", "-C", str(upstream), "init", "-b", "dev"], check=True, env=git_env, capture_output=True)
    write_checkout(upstream, {
        "oai-core/pyproject.toml": "[project]\nname = 'open-android-intelligence-core'\nversion = '0.1.0'\n",
        "oai-core/src/oai_core/version_flag.py": "BUILD = 1\n",
    })
    subprocess.run(["git", "-C", str(upstream), "add", "."], check=True, env=git_env, capture_output=True)
    subprocess.run(["git", "-C", str(upstream), "commit", "-m", "v1"], check=True, env=git_env, capture_output=True)

    root = tmp_path / "deployment"
    subprocess.run(["git", "clone", "--branch", "dev", str(upstream), str(root)], check=True, env=git_env, capture_output=True)

    # 远端推进到 v2
    write_checkout(upstream, {
        "oai-core/pyproject.toml": "[project]\nname = 'open-android-intelligence-core'\nversion = '0.2.0'\n",
        "oai-core/src/oai_core/version_flag.py": "BUILD = 2\n",
    })
    subprocess.run(["git", "-C", str(upstream), "add", "."], check=True, env=git_env, capture_output=True)
    subprocess.run(["git", "-C", str(upstream), "commit", "-m", "v2"], check=True, env=git_env, capture_output=True)

    (root / "start-core.sh").write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    (root / "start-core.sh").chmod(0o700)
    python_bin = root / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    python_bin.parent.mkdir(parents=True, exist_ok=True)
    python_bin.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    python_bin.chmod(0o700)

    storage, key, database = runtime._paths(root)
    key.parent.mkdir(parents=True, mode=0o700)
    create_master_key_file(key)
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
    store = IdentityStore(database, key_store=LocalMasterKeyStore(key), create=True, bootstrap_proof=proof)
    store.bootstrap("admin", "correct-password", confirmed=True)
    handle = store.begin_management("admin", "correct-password")
    LocalRecovery(store).register(handle, confirmed=True)
    store.close()
    runtime.configuration.private_write(root / "core.json", json.dumps({
        "storageRoot": "runtime/accounts",
        "masterKeyFile": "runtime/secrets/core-master-key",
        "listen": {"host": "0.0.0.0", "port": 11451},
        "allowInsecureHttp": True,
        "agents": [],
    }) + "\n")

    real_run = subprocess.run

    def selective_run(args, **kwargs):
        if len(args) >= 3 and args[1:3] == ["-m", "pip"]:
            return subprocess.CompletedProcess(args, 0, "", "")
        return real_run(args, **kwargs)

    monkeypatch.setattr(runtime.subprocess, "run", selective_run)

    # 1. 本地存在未提交改动时，不覆盖本地改动
    (root / "oai-core/src/oai_core/version_flag.py").write_text("BUILD = 999\n", encoding="utf-8")
    inputs = iter(["admin", "n"])
    monkeypatch.setattr(runtime, "_input", lambda prompt: next(inputs))
    monkeypatch.setattr(runtime, "secret_input", lambda prompt: "correct-password")
    assert runtime.install(root) == 0
    assert (root / "oai-core/src/oai_core/version_flag.py").read_text(encoding="utf-8") == "BUILD = 999\n"

    # 2. 工作区干净时，自动快进更新到远端 v2
    real_run(["git", "-C", str(root), "checkout", "--", "oai-core/src/oai_core/version_flag.py"], check=True)
    inputs = iter(["admin", "n"])
    monkeypatch.setattr(runtime, "_input", lambda prompt: next(inputs))
    assert runtime.install(root) == 0
    assert (root / "oai-core/src/oai_core/version_flag.py").read_text(encoding="utf-8") == "BUILD = 2\n"
