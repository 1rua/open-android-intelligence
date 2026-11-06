"""Authenticated installer, setup and uninstaller orchestration."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import sqlite3
import stat
import subprocess
import sys

from . import configuration
from .configuration import configure, configuration_lock, owned_bytes, private_write
from .gateway.credentials import verify_password
from .gateway.local_keys import LocalMasterKeyStore
from .identity import IdentityStore, issue_bootstrap_proof
from .instance import discover_instance, register_instance
from .management import LocalRecovery, secret_input


def _use_color() -> bool:
    return sys.stdout.isatty() and not os.environ.get("NO_COLOR")


def _style(text: str, code: str) -> str:
    return f"\033[{code}m{text}\033[0m" if _use_color() else text


def _box(title: str, lines: tuple[str, ...] | list[str] = ()) -> None:
    border = _style("╭──────────────────────────────────────────────────────────╮", "36;1")
    bottom = _style("╰──────────────────────────────────────────────────────────╯", "36;1")
    bar = _style("│", "36;1")
    print(f"\n{border}")
    print(f"{bar}  {_style(title, '1')}")
    for line in lines:
        print(f"{bar}  {line}")
    print(bottom)


def _step(message: str) -> None:
    print(f"{_style('▸', '36;1')} {message}")


def _ok(message: str) -> None:
    print(f"{_style('✓', '32;1')} {message}")


def _warn(message: str) -> None:
    print(f"{_style('!', '33;1')} {message}")


def _input(prompt: str) -> str:
    if not sys.stdin.isatty():
        raise PermissionError("需要交互式终端")
    return input(prompt).strip()


def _confirm(prompt: str, value: str) -> None:
    answer = _input(prompt).strip()
    if answer != value and answer.lower() not in {"", "y", "yes", value.lower()}:
        raise PermissionError("操作已取消")


def _paths(root: Path) -> tuple[Path, Path, Path]:
    config_path = root / "core.json"
    content = owned_bytes(config_path)
    config = json.loads(content) if content is not None else {
        "storageRoot": "runtime/accounts", "masterKeyFile": "runtime/secrets/core-master-key"
    }
    if not isinstance(config, dict):
        raise ValueError("配置格式无效")
    storage, key, identity = config.get("storageRoot"), config.get("masterKeyFile"), config.get("identityDatabase")
    if not isinstance(storage, str) or not storage or not isinstance(key, str) or not key:
        raise ValueError("配置缺少数据或主密钥路径")
    if identity is not None and (not isinstance(identity, str) or not identity):
        raise ValueError("身份数据库路径无效")
    storage_path = (root / storage).absolute()
    identity_path = (root / identity).absolute() if identity else storage_path.parent / "security" / "identity.sqlite"
    for path in (storage_path, identity_path, (root / key).absolute()):
        if path.resolve() != path:
            raise ValueError("实例路径不能包含符号链接")
    return storage_path, (root / key).absolute(), identity_path


def authenticate(store: IdentityStore, scopes: set[str] | None) -> str:
    """Only ask for a second factor after the server policy challenges us."""
    username = _input("管理员账号: ")
    password = secret_input("管理员密码: ")
    try:
        try:
            return store.begin_management(username, password, scopes=scopes)
        except Exception as error:
            if getattr(error, "code", None) != "MFA_REQUIRED":
                raise
            details = getattr(error, "details", None)
            if not isinstance(details, dict):
                raise
            challenge, methods = details.get("challengeId"), details.get("methods")
            if not isinstance(challenge, str) or not isinstance(methods, list):
                raise
            methods = [item for item in ("totp", "recovery-code") if item in methods]
            if not methods:
                raise
            if len(methods) == 1:
                method = methods[0]
            else:
                answer = _input("二次验证方式（1 验证器，2 恢复码）: ")
                if answer not in {"1", "2"}:
                    raise PermissionError("已取消二次验证")
                method = methods[int(answer) - 1]
            factor = secret_input("验证码: " if method == "totp" else "恢复码: ")
            try:
                return store.begin_management(username, password, scopes=scopes, challenge_id=challenge,
                                              otp=factor if method == "totp" else None,
                                              recovery_code=factor if method == "recovery-code" else None)
            finally:
                factor = ""
    finally:
        password = ""


@contextmanager
def management(root: Path, scopes: set[str]):
    _, key, identity = _paths(root)
    store = IdentityStore(identity, key_store=LocalMasterKeyStore(key))
    handle = None
    try:
        handle = authenticate(store, scopes)
        yield store, handle
    finally:
        try:
            if handle is not None and store.database is not None:
                store.revoke_management(handle)
        finally:
            store.close()


def _journal(root: Path, status: str) -> None:
    private_write(root / "runtime" / "installer-journal.json", json.dumps({"version": 1, "status": status}) + "\n")


def _new_install(root: Path, storage: Path, key: Path, database: Path):
    _box("步骤 2/4 · 创建首个管理员", (
        "请设置管理员用户名与登录密码。",
        "后续手机 App 配对登录以及后台设置均使用此账号。",
    ))
    username = _input("初始管理员账号: ")
    password = secret_input("设置管理员密码: ")
    if password != secret_input("再次输入管理员密码: "):
        raise ValueError("两次密码不一致")
    _confirm("确认创建首个管理员账号？(直接回车确认，或输入 y / bootstrap) [y]: ", "bootstrap")
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
    # Consume the proof under the lock before writing the journal: a competing
    # initializer or a failed proof must not overwrite another installation's
    # evidence. A crash in this small interval is recoverable by authenticating
    # the initialized identity database on the next run.
    with configuration_lock(root):
        store = IdentityStore(database, key_store=LocalMasterKeyStore(key), create=True, bootstrap_proof=proof)
        try:
            store.bootstrap(username, password, confirmed=True)
            handle = store.begin_management(username, password)
            store.require_management(handle, "install")
            _journal(root, "identity")
        except BaseException:
            store.close()
            raise
        finally:
            password = ""
    _ok(f"管理员账号「{username}」创建成功。")
    return store, handle


def _legacy_authenticate(storage: Path) -> tuple[str, str]:
    # This path only opens existing account DBs in SQLite read-only mode.
    _box("升级旧版账号数据", (
        "检测到旧版账号数据库，请输入一个现有账号及密码。",
        "该账号将升级为管理员，其余账号与历史记录完整保留。",
    ))
    accounts = IdentityStore._legacy_accounts(storage)
    username = _input("指定要迁移为管理员的现有账号: ")
    password = secret_input("现有账号密码: ")
    selected = next((row for row in accounts if row[1] == username), None)
    if selected is None or not verify_password(password, selected[2]):
        raise PermissionError("账号或密码不正确")
    return username, password


def _old_writers_stopped(storage: Path) -> bool:
    """Conservatively refuse visible database users and busy SQLite writers."""
    if not sys.platform.startswith("linux"):
        raise RuntimeError("当前平台无法可靠检查旧服务，请使用 Linux 完成迁移")
    databases = sorted(storage.glob("*/gateway.sqlite"))
    if not databases:
        raise RuntimeError("未找到旧账号数据库")
    identities = set()
    for database in databases:
        info = database.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
            raise PermissionError("旧账号数据库所有者或权限不安全")
        identities.add((info.st_dev, info.st_ino))
    if os.geteuid() == 0:
        raise PermissionError("不能用 root 代替部署用户检查旧写入者")
    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit() or int(proc.name) == os.getpid():
            continue
        try:
            if proc.stat().st_uid != os.geteuid():
                continue
            args = (proc / "cmdline").read_bytes().split(b"\0")
            if any(b"oai_core" in arg or b"hermes" in arg.lower() or b"dsh" in arg.lower()
                   for arg in args):
                return False
            for entry in (proc / "fd").iterdir():
                try:
                    info = entry.stat()
                    if (info.st_dev, info.st_ino) in identities:
                        return False
                except FileNotFoundError:
                    continue
        except FileNotFoundError:
            continue
        except PermissionError:
            raise PermissionError("无法检查本用户的全部进程，请手动处理后重试") from None
    for database in databases:
        connection = sqlite3.connect(database.as_uri() + "?mode=rw", uri=True, timeout=0)
        try:
            connection.execute("BEGIN EXCLUSIVE")
            connection.rollback()
        except sqlite3.OperationalError:
            return False
        finally:
            connection.close()
    return True


def _migrate(root: Path, storage: Path, key: Path, database: Path):
    username, password = _legacy_authenticate(storage)
    try:
        _confirm("请先停止所有旧 Core、Adapter 和管理程序，确认已停止请输入 STOP（或直接回车确认）[y]: ", "STOP")
        if not _old_writers_stopped(storage):
            raise RuntimeError("旧数据库仍被进程使用或被锁定")
        from .identity_migration import migrate_legacy_installation
        store = migrate_legacy_installation(database, storage, username, password, confirmed=True,
                                            old_writers_stopped=lambda: _old_writers_stopped(storage))
        store.key_store = LocalMasterKeyStore(key)
        handle = store.begin_management(username, password)
        _ok("旧版账号数据已安全升级为新版格式。")
        return store, handle
    finally:
        password = ""


def _verify_journal(root: Path, *, has_identity: bool) -> str | None:
    raw = owned_bytes(root / "runtime" / "installer-journal.json", private=True)
    if raw is None:
        return None
    state = json.loads(raw)
    if not isinstance(state, dict) or state.get("version") != 1 or state.get("status") not in {"preparing", "identity", "configured", "complete"}:
        raise ValueError("安装恢复记录无效")
    if not has_identity:
        raise ValueError("安装中断且身份库缺失，请保留安装记录人工检查")
    return state["status"]


def _run(store: IdentityStore, handle: str, scope: str, arguments: list[str], *, cwd: Path) -> None:
    store.require_management(handle, scope)
    subprocess.run(arguments, cwd=cwd, check=True)


def _write_owned(store: IdentityStore, handle: str, scope: str, path: Path, content: str, mode: int) -> None:
    store.require_management(handle, scope)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError("拒绝覆盖符号链接")
    if path.exists():
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid():
            raise ValueError("拒绝覆盖非本用户文件")
        current = path.read_text()
        if current == content:
            return
        raise ValueError(f"已有文件不同，请人工检查: {path}")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), mode)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())


def _integrate_cli(root: Path, store: IdentityStore, handle: str) -> None:
    wrapper = Path.home() / ".local" / "bin" / "oai"
    relative = shlex.quote(os.path.relpath(root, wrapper.parent))
    content = f'#!/usr/bin/env bash\nset -euo pipefail\nBASE="$(cd "$(dirname "${{BASH_SOURCE[0]}}")" && pwd)"\nOAI_DIR="${{BASE}}"/{relative}\nif [ "$#" -eq 0 ]; then exec "$OAI_DIR/start-core.sh"; fi\nexec "$OAI_DIR/.venv/bin/oai" "$@"\n'
    if wrapper.is_file() and not wrapper.is_symlink() and wrapper.read_text() != content:
        previous = wrapper.read_text()
        marker = 'OAI_DIR="' + str(root) + '"'
        if ("# Open Android Intelligence Core Launcher & CLI" in previous and marker in previous
                and 'VENV_OAI="${OAI_DIR}/.venv/bin/oai"' in previous):
            store.require_management(handle, "install")
            info = wrapper.stat()
            if info.st_uid != os.geteuid() or info.st_mode & 0o022:
                raise PermissionError("旧 CLI 包装脚本权限不安全")
            retained = wrapper.with_name("oai.previous-" + secrets.token_hex(8))
            wrapper.rename(retained)
    _write_owned(store, handle, "install", wrapper, content, 0o755)


def _install_agents(root: Path, store: IdentityStore, handle: str) -> bool:
    """Install only explicitly selected host plugins after Core configuration."""
    _, _, selected, _ = configuration._network_settings(root)
    partial = False
    if "hermes" in selected:
        if not shutil.which("hermes"):
            _warn("Hermes 尚未连接：未在系统中找到 hermes 命令。")
            partial = True
        else:
            _run(store, handle, "agents.manage", ["hermes", "plugins", "install", "./integrations/hermes-session-adapter", "--enable"], cwd=root)
    if "dsh" in selected:
        _warn("DSH 插件需要在 setup 的「Agent 接入」菜单中选择对应的配置 (Profile) 完成安装与连接验证。")
        partial = True
    return not partial


def _service_files(root: Path) -> tuple[Path, Path]:
    home = Path.home()
    return home / ".config/systemd/user/oai-core.service", home / ".config/autostart/oai-core.desktop"


def _rotate_existing_service_file(store: IdentityStore, handle: str, path: Path, content: str) -> None:
    if not path.is_file() or path.is_symlink():
        return
    try:
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid():
            return
        current = path.read_text(encoding="utf-8", errors="replace")
        if current == content:
            return
        if any(marker in current for marker in (
            "Open Android Intelligence",
            "OAI Core",
            "com.openandroidintelligence.core",
            "start-core.sh",
        )):
            store.require_management(handle, "service")
            retained = path.with_name(path.name + ".previous-" + secrets.token_hex(8))
            path.rename(retained)
    except OSError:
        return


def _configure_service(root: Path, store: IdentityStore, handle: str) -> None:
    store.require_management(handle, "service")
    start = root / "start-core.sh"
    if not start.is_file():
        raise ValueError("缺少启动脚本")
    if sys.platform.startswith("linux"):
        if any(c in str(root) for c in ('\n', '\r', '\0')):
            raise ValueError("安装路径不能包含控制字符")
        unit, desktop = _service_files(root)
        quoted = '"' + str(start).replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%') + '"'
        workdir = str(root).replace('%', '%%')
        unit_text = ("[Unit]\nDescription=Open Android Intelligence Core Service\nAfter=network.target\n\n"
                     f"[Service]\nType=simple\nWorkingDirectory={workdir}\nExecStart={quoted}\n"
                     "Restart=always\nRestartSec=3\n\n[Install]\nWantedBy=default.target\n")
        desktop_text = ("[Desktop Entry]\nType=Application\nName=OAI Core\n"
                        f"Exec={quoted}\nTerminal=false\nHidden=false\nNoDisplay=false\n"
                        "X-GNOME-Autostart-enabled=true\nX-KDE-autostart-after=panel\n")
        _rotate_existing_service_file(store, handle, unit, unit_text)
        _rotate_existing_service_file(store, handle, desktop, desktop_text)
        _write_owned(store, handle, "service", unit, unit_text, 0o644)
        _write_owned(store, handle, "service", desktop, desktop_text, 0o644)
        if shutil.which("systemctl"):
            try:
                for args in (["daemon-reload"], ["enable", "oai-core.service"], ["restart", "oai-core.service"]):
                    _run(store, handle, "service", ["systemctl", "--user", *args], cwd=root)
            except subprocess.CalledProcessError:
                if os.geteuid() == 0 and Path("/etc/systemd/system").is_dir():
                    sys_unit = Path("/etc/systemd/system/oai-core.service")
                    _rotate_existing_service_file(store, handle, sys_unit, unit_text)
                    _write_owned(store, handle, "service", sys_unit, unit_text, 0o644)
                    try:
                        for args in (["daemon-reload"], ["enable", "oai-core.service"], ["restart", "oai-core.service"]):
                            _run(store, handle, "service", ["systemctl", *args], cwd=root)
                    except subprocess.CalledProcessError:
                        _warn("后台服务未能自动启动，稍后可手动运行 start-core.sh 启动。")
                else:
                    _warn("后台用户服务未能自动启动，稍后可手动运行 start-core.sh 启动。")
            else:
                _ok("已配置并启动 Linux 后台开机自启服务 (oai-core.service)。")
        else:
            _ok("未检测到 systemctl，已为您生成桌面登录自启项。")
    elif sys.platform == "darwin":
        import plistlib
        path = Path.home() / "Library/LaunchAgents/com.openandroidintelligence.core.plist"
        content = plistlib.dumps({"Label": "com.openandroidintelligence.core", "ProgramArguments": [str(start)],
                                  "WorkingDirectory": str(root), "RunAtLoad": True, "KeepAlive": True,
                                  "StandardOutPath": str(root / "runtime/core.log"),
                                  "StandardErrorPath": str(root / "runtime/core.log")}).decode()
        _rotate_existing_service_file(store, handle, path, content)
        _write_owned(store, handle, "service", path, content, 0o644)
        if shutil.which("launchctl"):
            _run(store, handle, "service", ["launchctl", "load", "-w", str(path)], cwd=root)
        _ok("已配置 macOS 登录自启服务 (LaunchAgent)。")
    elif sys.platform.startswith(("win", "cygwin", "msys")):
        appdata = os.environ.get("APPDATA")
        if not appdata:
            raise ValueError("未找到 Windows 启动目录")
        path = Path(appdata) / "Microsoft/Windows/Start Menu/Programs/Startup/oai-core.vbs"
        content = f'Set WshShell = CreateObject("WScript.Shell")\nWshShell.Run chr(34) & "{start}" & Chr(34), 0\nSet WshShell = Nothing\n'
        _rotate_existing_service_file(store, handle, path, content)
        _write_owned(store, handle, "service", path, content, 0o644)
        _ok("已添加 Windows 开机自启脚本。")
    else:
        raise ValueError("当前平台不支持自动服务配置")


def _service_menu(root: Path, store: IdentityStore, handle: str) -> None:
    store.require_management(handle, "service")
    choice = _input("服务：1 状态  2 启动  3 停止  4 重启  5 配置自启动  0 返回: ")
    if choice in {"", "0"}:
        return
    if choice == "5":
        _configure_service(root, store, handle)
        return
    actions = {"1": "status", "2": "start", "3": "stop", "4": "restart"}
    if choice not in actions or not sys.platform.startswith("linux"):
        raise ValueError("当前平台不支持此服务操作")
    unit, _ = _service_files(root)
    if unit.is_symlink() or not unit.is_file():
        raise ValueError("未找到属于本实例的服务")
    entries = dict(line.split("=", 1) for line in unit.read_text().splitlines() if "=" in line)
    if (shlex.split(entries.get("WorkingDirectory", "")) != [str(root)]
            or shlex.split(entries.get("ExecStart", "")) != [str(root / "start-core.sh")]):
        raise ValueError("服务文件属于其他实例")
    state = subprocess.run(["systemctl", "--user", "show", "oai-core.service", "--property=FragmentPath,WorkingDirectory"],
                           cwd=root, check=True, capture_output=True, text=True)
    loaded = dict(line.split("=", 1) for line in state.stdout.splitlines() if "=" in line)
    if loaded.get("FragmentPath") != str(unit) or loaded.get("WorkingDirectory") != str(root):
        raise ValueError("已加载的服务属于其他实例")
    _run(store, handle, "service", ["systemctl", "--user", actions[choice], "oai-core.service"], cwd=root)


def _run_setup(root: Path, store: IdentityStore, handle: str) -> None:
    # Shell setup and `oai setup` cross the same authenticated Interface.
    configuration.run_setup(discover_instance(root / "core.json"), store, handle)


def _restore_install_source(root: Path, source: Path, store: IdentityStore, handle: str) -> None:
    """管理员认证后补回受版本控制的源码，保留数据与现有配置。"""
    store.require_management(handle, "install")
    source = source.resolve()
    result = subprocess.run(["git", "-C", str(source), "ls-files", "-z"],
                            check=True, capture_output=True, text=True)
    storage, key, database = _paths(root)
    protected = (root / "runtime", root / "core.json", root / "start-core.sh", storage, key, database)
    marker = Path("oai-core/pyproject.toml")
    entries = []
    for name in result.stdout.split("\0"):
        if not name:
            continue
        relative = Path(name)
        if relative.is_absolute() or ".." in relative.parts or relative.parts[0] == ".git":
            raise ValueError("恢复源码包含无效路径")
        destination = root / relative
        if any(destination == path or path in destination.parents for path in protected):
            continue
        original = source / relative
        if not original.is_file() or original.is_symlink() or destination.resolve() != destination.absolute():
            raise ValueError("恢复源码或目标路径不安全")
        if destination.exists():
            if not destination.is_file() or destination.stat().st_uid != os.geteuid():
                raise ValueError("拒绝覆盖已有的非本用户程序文件")
            if destination.read_bytes() != original.read_bytes():
                raise ValueError(f"已有文件与恢复源码冲突，请保留现状人工检查: {relative}")
        entries.append((relative, original, destination))
    if not any(relative == marker for relative, _, _ in entries):
        raise ValueError("恢复源码缺少 OAI Core 安装元数据")
    # 最后原子发布就绪标记，中断后的再次安装仍会继续走恢复入口。
    entries.sort(key=lambda item: item[0] == marker)
    for relative, original, destination in entries:
        store.require_management(handle, "install")
        if destination.exists():
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(destination.name + ".restore." + secrets.token_hex(8) + ".tmp")
        shutil.copy2(original, temporary)
        store.require_management(handle, "install")
        os.replace(temporary, destination)


def install(root: Path, *, restore_source: Path | None = None) -> int:
    storage, key, database = _paths(root)
    has_identity = database.exists() or database.is_symlink()
    state = _verify_journal(root, has_identity=has_identity)
    if has_identity:
        _box("验证管理员身份", (
            "检测到当前目录已有安装记录，请先完成身份验证。",
            "验证通过后将自动更新程序组件，原有配置与数据保持不变。",
        ))
        store = IdentityStore(database, key_store=LocalMasterKeyStore(key))
        try:
            handle = authenticate(store, None)
        except BaseException:
            store.close()
            raise
        _ok("管理员身份验证通过。")
    elif (root / "core.json").exists() or (storage.exists() and any(storage.iterdir())):
        store, handle = _migrate(root, storage, key, database)
    else:
        store, handle = _new_install(root, storage, key, database)
    try:
        operator = store.require_management(handle, "install")
        if not store._local_recovery_ready(store._row(operator["accountId"])):
            # Bootstrap/legacy migration prepares the independent local recovery
            # route even when optional TOTP is left disabled.
            if sys.stdin.isatty() and sys.stdout.isatty():
                registered = LocalRecovery(store).register(handle, confirmed=True)
                _box("步骤 3/4 · 备份管理员应急恢复码（仅显示一次）", (
                    "当您忘记管理员密码时，可在服务器本机终端运行：",
                    f"  oai recover {operator['username']}",
                    "并输入下方任意一个恢复码来重置密码。请务必拍照或离线保存：",
                ))
                print("管理员本机恢复码只显示一次，请离线保存：")
                for code in registered["recoveryCodes"]:
                    print(f"  {code}")
                _confirm("请确认已保存恢复码（直接回车确认，或保存后请输入 SAVED）[y]: ", "SAVED")
                _ok("应急恢复码已完成登记。")
            else:
                raise PermissionError("首次初始化需要受保护终端以保存恢复码")
        if restore_source is not None:
            _step("正在恢复缺失的程序源码文件（原有配置与账号数据保持不变）...")
            with configuration_lock(root):
                _restore_install_source(root, restore_source, store, handle)
            _ok("程序源码文件已恢复。")
        python = root / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
        if not python.is_file():
            _step("正在创建 Python 虚拟环境 (.venv)...")
            _run(store, handle, "install", [sys.executable, "-m", "venv", "--copies", str(root / ".venv")], cwd=root)
        if (root / "runtime/configuration-journal.json").exists():
            store.require_management(handle, "config")
            with configuration_lock(root):
                configuration.ConfigurationTransaction.recover(root, store=store, handle=handle)
        if not (root / "core.json").is_file() or not (root / "start-core.sh").is_file():
            if (root / "core.json").is_file():
                host, port, selected, hermes = configuration._network_settings(root)
            else:
                host, port, selected, hermes = "0.0.0.0", 11451, [], None
            _step(f"正在生成初始服务配置（默认监听 {host}:{port}）...")
            configured = configure(root, host, port, operator["username"], selected, hermes,
                                   store=store, handle=handle)
            if not configured:
                _warn("配置已保存，但可选的 AI 助手技能（skill）暂未同步完成。")
            store.require_management(handle, "install")
            _journal(root, "configured")
        else:
            configured = True
        # Existing installs are authenticated before pip, CLI or registration writes.
        trusted_package = (root / "oai-core").resolve() if restore_source is not None else Path(__file__).resolve().parents[2]
        package_reference = "./oai-core" if trusted_package == (root / "oai-core").resolve() else os.path.relpath(trusted_package, root)
        _step("正在安装/更新 OAI Core 命令行与运行时组件...")
        _run(store, handle, "install", [str(python), "-m", "pip", "install", "-e", package_reference], cwd=root)
        _run(store, handle, "install", [str(python), "-m", "pip", "check"], cwd=root)
        store.require_management(handle, "install")
        register_instance(root / "core.json")
        _integrate_cli(root, store, handle)
        _ok("全局快捷命令 oai 已配置到 ~/.local/bin/oai。")
        if configuration.detect_host_agents():
            _step("正在扫描并尝试自动连接本机已安装的 AI 助手...")
            configuration.auto_connect_host_agents(root, store, handle, operator)
        _box("步骤 4/4 · 后台自启与服务设置", (
            "开启开机自启后，系统重启或登录时会自动在后台运行 OAI Core 服务。",
        ))
        if _input("是否配置开机自启服务？(y/n) [n]: ").lower() in {"y", "yes"}:
            _configure_service(root, store, handle)
        if state is not None or (root / "runtime" / "installer-journal.json").exists():
            store.require_management(handle, "install")
            _journal(root, "complete")
        final_host, final_port, _, _ = configuration._network_settings(root)
        _box("🎉 OAI Core 安装已完成", (
            f"• 安装目录: {root}",
            f"• 服务地址: {final_host}:{final_port}",
            "• 启动服务: 运行 oai 或 ./start-core.sh",
            "• 修改配置: 运行 oai setup 或 ./scripts/setup.sh",
            f"• 手机连接: 在手机 App 填入 http://<电脑局域网IP>:{final_port} 登录配对",
        ))
        print("Core 配置及 CLI 已安装；服务和 Agent 可在 setup 中管理。")
        return 0 if configured else 2
    finally:
        try:
            store.revoke_management(handle)
        finally:
            store.close()


def setup(root: Path) -> int:
    _box("验证管理员身份", (
        "请先完成身份验证以打开 OAI Core 设置面板。",
    ))
    with management(root, {"setup", "config", "accounts.read", "accounts.write", "service", "security", "agents.manage", "agent:manage"}) as (store, handle):
        _ok("登录成功，正在加载当前设置...")
        discover_instance(root / "core.json")
        _run_setup(root, store, handle)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("install", "setup"))
    parser.add_argument("--dir", type=Path, required=True)
    args = parser.parse_args()
    root = args.dir.expanduser().absolute()
    if root.is_symlink() or root.resolve() != root:
        raise ValueError("安装路径不能包含符号链接")
    return install(root) if args.operation == "install" else setup(root)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        code = getattr(error, "code", type(error).__name__)
        print(f"[错误] 操作未完成（原因：{code}）。原有配置与账号数据保持不变。", file=sys.stderr)
        raise SystemExit(1) from None
