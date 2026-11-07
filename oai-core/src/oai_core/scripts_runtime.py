"""Authenticated installer, setup and uninstaller orchestration."""
from __future__ import annotations

import argparse
import builtins
from collections.abc import Sequence
from contextlib import contextmanager
import importlib
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


_DEFAULT_INPUT = _input


def _select_option(
    prompt: str,
    options: Sequence[tuple[str, str]],
    *,
    default_index: int = 0,
) -> str:
    """支持 ↑/↓ 方向键切换选项并回车确认，同时兼容直接输入编号或字母。"""
    if (
        _input is not _DEFAULT_INPUT
        or type(builtins.input).__name__ != "builtin_function_or_method"
        or not sys.stdin.isatty()
        or not sys.stdout.isatty()
    ):
        for _, label in options:
            print(f"  {label}")
        return _input(prompt)
    try:
        import select
        import termios

        fd = sys.stdin.fileno()
        old_attrs = termios.tcgetattr(fd)
    except Exception:
        for _, label in options:
            print(f"  {label}")
        return _input(prompt)

    selected = max(0, min(default_index, len(options) - 1))

    def _render(*, initial: bool = False, typed: str = "") -> None:
        if not initial:
            sys.stdout.write(f"\r\033[{len(options)}A")
        for idx, (_, label) in enumerate(options):
            sys.stdout.write("\r\033[2K")
            if idx == selected:
                marker = _style("❯", "36;1")
                styled_label = _style(label, "36;1")
                sys.stdout.write(f"  {marker} {styled_label}\n")
            else:
                sys.stdout.write(f"    {label}\n")
        sys.stdout.write(f"\r\033[2K{prompt}{typed}")
        sys.stdout.flush()

    typed_chars: list[str] = []
    new_attrs = list(old_attrs)
    new_attrs[3] &= ~(termios.ICANON | termios.ECHO)
    new_attrs[6][termios.VMIN] = 1
    new_attrs[6][termios.VTIME] = 0
    try:
        termios.tcsetattr(fd, termios.TCSANOW, new_attrs)
        _render(initial=True)
        while True:
            raw = os.read(fd, 1)
            if not raw:
                sys.stdout.write("\n")
                sys.stdout.flush()
                raise EOFError
            ch = raw.decode("utf-8", errors="ignore")
            if ch in ("\r", "\n"):
                sys.stdout.write("\n")
                sys.stdout.flush()
                if typed_chars:
                    return "".join(typed_chars).strip()
                return options[selected][0]
            if ch == "\x03":
                sys.stdout.write("\n")
                sys.stdout.flush()
                raise KeyboardInterrupt
            if ch == "\x04":
                sys.stdout.write("\n")
                sys.stdout.flush()
                raise EOFError
            if ch in ("\x7f", "\b"):
                if typed_chars:
                    typed_chars.pop()
                    _render(typed="".join(typed_chars))
                continue
            if ch == "\x1b":
                seq = ""
                if select.select([fd], [], [], 0.05)[0]:
                    b1 = os.read(fd, 1).decode("utf-8", errors="ignore")
                    seq += b1
                    if b1 in ("[", "O") and select.select([fd], [], [], 0.05)[0]:
                        seq += os.read(fd, 1).decode("utf-8", errors="ignore")
                if seq in ("[A", "OA", "[D", "OD"):
                    selected = (selected - 1) % len(options)
                    typed_chars.clear()
                    _render()
                elif seq in ("[B", "OB", "[C", "OC"):
                    selected = (selected + 1) % len(options)
                    typed_chars.clear()
                    _render()
                continue
            if ch.isprintable():
                typed_chars.append(ch)
                current = "".join(typed_chars).strip().lower()
                for idx, (val, _) in enumerate(options):
                    if current == val.lower():
                        selected = idx
                        break
                _render(typed="".join(typed_chars))
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_attrs)


def _confirm(prompt: str, value: str) -> None:
    answer = _select_option(
        prompt,
        (
            ("y", "1. 是，确认继续 (Yes)"),
            ("n", "2. 否，取消退出 (No)"),
        ),
        default_index=0,
    ).strip()
    if answer != value and answer.lower() not in {"", "y", "yes", "1", value.lower()}:
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
                answer = _select_option(
                    "二次验证方式（↑/↓ 选择或输入 1 验证器，2 恢复码）: ",
                    (
                        ("1", "1. 手机验证器动态码 (TOTP)"),
                        ("2", "2. 应急恢复码 (Recovery Code)"),
                    ),
                    default_index=0,
                )
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


def _run(store: IdentityStore, handle: str, scope: str, arguments: list[str], *,
         cwd: Path, env: dict[str, str] | None = None) -> None:
    store.require_management(handle, scope)
    kwargs: dict[str, object] = {"cwd": cwd, "check": True}
    if env is not None:
        kwargs["env"] = env
    subprocess.run(arguments, **kwargs)


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
    choice = _select_option(
        "服务：1 状态  2 启动  3 停止  4 重启  5 配置自启动  0 返回: ",
        (
            ("1", "1. 查看服务状态"),
            ("2", "2. 启动后台服务"),
            ("3", "3. 停止后台服务"),
            ("4", "4. 重启后台服务"),
            ("5", "5. 配置开机自启动"),
            ("0", "0. 返回"),
        ),
        default_index=0,
    )
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


_EXCLUDED_UPGRADE_DIRS = {
    ".git", "__pycache__", ".pytest_cache", "node_modules", "build", "dist", ".gradle", ".venv",
}


def _list_source_entries(source: Path) -> list[Path]:
    if (source / ".git").exists() and not (source / ".git").is_symlink() and shutil.which("git"):
        result = subprocess.run(
            ["git", "-C", str(source), "ls-files", "-z"],
            check=True,
            capture_output=True,
            text=True,
        )
        return [Path(name) for name in result.stdout.split("\0") if name]
    found: list[Path] = []
    for top in ("oai-core", "scripts", "integrations", "gateway-contract"):
        top_path = source / top
        if not top_path.is_dir() or top_path.is_symlink():
            continue
        for dirpath, dirnames, filenames in os.walk(top_path, followlinks=False):
            dirnames[:] = [
                d for d in dirnames
                if d not in _EXCLUDED_UPGRADE_DIRS and not d.endswith(".egg-info")
                and not (Path(dirpath) / d).is_symlink()
            ]
            for fname in sorted(filenames):
                if fname.endswith((".pyc", ".pyo")):
                    continue
                candidate = Path(dirpath) / fname
                if candidate.is_file() and not candidate.is_symlink():
                    found.append(candidate.relative_to(source))
    return found


def _sync_install_source(root: Path, source: Path, store: IdentityStore, handle: str) -> int:
    """管理员认证后，将外部新版安装包中的源码同步更新至安装目录，保留原有配置与账号数据。"""
    store.require_management(handle, "install")
    source = source.resolve()
    if source == root.resolve():
        return 0
    relative_paths = _list_source_entries(source)
    storage, key, database = _paths(root)
    protected = (
        root / "runtime",
        root / ".venv",
        root / ".git",
        root / "core.json",
        root / "start-core.sh",
        storage,
        key,
        database,
    )
    marker = Path("oai-core/pyproject.toml")
    if not any(rel == marker for rel in relative_paths):
        raise ValueError("升级安装包缺少 OAI Core 安装元数据")
    to_update: list[tuple[Path, Path, Path]] = []
    for relative in relative_paths:
        if relative.is_absolute() or ".." in relative.parts or relative.parts[0] == ".git":
            raise ValueError("升级源码包含无效路径")
        destination = root / relative
        if any(destination == path or path in destination.parents for path in protected):
            continue
        original = source / relative
        if not original.is_file() or original.is_symlink() or destination.resolve() != destination.absolute():
            raise ValueError("升级源码或目标路径不安全")
        if destination.exists():
            if not destination.is_file() or destination.stat().st_uid != os.geteuid():
                raise ValueError("拒绝覆盖已有的非本用户程序文件")
            same_mode = (stat.S_IMODE(destination.stat().st_mode) & 0o111) == (stat.S_IMODE(original.stat().st_mode) & 0o111)
            if destination.read_bytes() == original.read_bytes() and same_mode:
                continue
        to_update.append((relative, original, destination))
    to_update.sort(key=lambda item: item[0] == marker)
    for _, original, destination in to_update:
        store.require_management(handle, "install")
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(destination.name + ".upgrade." + secrets.token_hex(8) + ".tmp")
        shutil.copy2(original, temporary)
        store.require_management(handle, "install")
        os.replace(temporary, destination)
    return len(to_update)


def _archive_temp_download_source(root: Path, source: Path) -> None:
    if os.environ.get("OAI_TEMP_DOWNLOAD_SOURCE") != "1":
        return
    try:
        if (not source.is_dir() or source.is_symlink()
                or source.parent != root.parent
                or not source.name.startswith(root.name + ".download.")
                or source.stat().st_uid != os.geteuid()):
            return
        archive_parent = root / "runtime" / "upgrade-downloads"
        configuration.private_directory(archive_parent)
        os.replace(source, archive_parent / (source.name + "-" + secrets.token_hex(4)))
    except OSError:
        return


def _upgrade_git_checkout(root: Path, store: IdentityStore, handle: str) -> bool:
    """管理员认证后，在安全的 Git 部署目录拉取远端最新提交并快进合并。"""
    git_dir = root / ".git"
    if not git_dir.exists() or git_dir.is_symlink() or not shutil.which("git"):
        return False
    try:
        if git_dir.stat().st_uid != os.geteuid():
            return False
    except OSError:
        return False
    store.require_management(handle, "install")
    env = {**os.environ, "GIT_TERMINAL_PROMPT": "0"}
    remote_res = subprocess.run(
        ["git", "-C", str(root), "remote", "get-url", "origin"],
        capture_output=True, text=True, timeout=10, check=False, env=env,
    )
    if remote_res.returncode != 0 or not remote_res.stdout.strip():
        return False
    status_res = subprocess.run(
        ["git", "-C", str(root), "status", "--porcelain", "--untracked-files=no"],
        capture_output=True, text=True, timeout=15, check=False, env=env,
    )
    if status_res.returncode != 0:
        _warn("无法检查当前目录的 Git 状态，已跳过自动拉取远端源码。")
        return False
    if status_res.stdout.strip():
        _warn("检测到安装目录存在未提交的本地代码改动，已跳过自动拉取远端更新，将使用当前本地代码更新组件。")
        return False
    branch = os.environ.get("OAI_BRANCH", "").strip()
    if not branch:
        branch_res = subprocess.run(
            ["git", "-C", str(root), "symbolic-ref", "--short", "-q", "HEAD"],
            capture_output=True, text=True, timeout=10, check=False, env=env,
        )
        branch = branch_res.stdout.strip() if branch_res.returncode == 0 and branch_res.stdout.strip() else "dev"
    head_res = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "HEAD"],
        capture_output=True, text=True, timeout=10, check=False, env=env,
    )
    before_head = head_res.stdout.strip() if head_res.returncode == 0 else ""
    _step(f"正在从远端仓库检查最新版本（分支: {branch}）...")
    try:
        fetch_res = subprocess.run(
            ["git", "-C", str(root), "fetch", "--quiet", "origin", branch],
            capture_output=True, text=True, timeout=60, check=False, env=env,
        )
    except subprocess.TimeoutExpired:
        _warn("连接远端仓库超时，将继续使用本地现有源码完成检查与更新。")
        return False
    if fetch_res.returncode != 0:
        _warn("未能拉取远端仓库更新（可能处于离线或网络受限环境），将继续使用本地现有源码更新组件。")
        return False
    fetch_head_res = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "FETCH_HEAD"],
        capture_output=True, text=True, timeout=10, check=False, env=env,
    )
    after_head = fetch_head_res.stdout.strip() if fetch_head_res.returncode == 0 else ""
    if not after_head or after_head == before_head:
        _ok(f"当前源码已是最新版本（{before_head[:8] or '最新'}）。")
        return False
    store.require_management(handle, "install")
    merge_res = subprocess.run(
        ["git", "-C", str(root), "merge", "--ff-only", "FETCH_HEAD"],
        capture_output=True, text=True, timeout=30, check=False, env=env,
    )
    if merge_res.returncode != 0:
        _warn("本地分支与远端历史存在分叉，未强行覆盖；如需同步最新代码请手动执行 git pull 后重试。")
        return False
    _ok(f"源码已更新至最新版本：{before_head[:8]} -> {after_head[:8]}。")
    return True


def _upgrade_installed_host_adapters(root: Path, store: IdentityStore, handle: str) -> bool:
    """升级本机已经安装过 OAI 连接插件的 Hermes / DSH 环境。"""
    hermes_restarted = False
    detected = configuration.detect_host_agents()
    if "hermes" in detected and (root / "integrations/hermes-session-adapter/plugin.yaml").is_file():
        hermes_home = detected["hermes"][0]
        if ((hermes_home / "plugins" / "oai-hermes-session-adapter").is_dir()
                or (hermes_home / "plugins" / "open-android-intelligence-gateway").is_dir()):
            from .hermes_installation import HermesAdapterInstallError, install_hermes_adapter
            _step("检测到已安装的 Hermes 连接插件，正在同步更新...")
            try:
                install_hermes_adapter(root, store, handle, hermes_home)
                hermes_restarted = configuration.restart_hermes_service_if_active()
                _ok("已同步更新本机 Hermes 连接插件" + ("并重启 hermes-gateway 服务。" if hermes_restarted else "。"))
            except HermesAdapterInstallError as err:
                _warn(f"Hermes 连接插件未能自动更新：{err}（可稍后在 oai setup 中处理）。")
    if "dsh" in detected and (root / "integrations/dsh-session-adapter/install.mjs").is_file():
        from .dsh_installation import DshAdapterInstallError, discover_dsh_profiles, install_dsh_adapter
        try:
            installed_profiles = discover_dsh_profiles(detected["dsh"], include_uninstalled=False)
        except DshAdapterInstallError:
            installed_profiles = []
        for profile in installed_profiles:
            _step(f"检测到已安装的 DSH 连接插件（配置: {profile.name}），正在同步更新...")
            try:
                install_dsh_adapter(root, store, handle, profile, include_uninstalled=False)
                restarted = configuration.restart_dsh_service_if_active(profile.home, profile.name)
                _ok(f"已同步更新 DSH 连接插件（配置: {profile.name}）" + ("并重启 DSH 服务。" if restarted else "。"))
            except DshAdapterInstallError as err:
                _warn(f"DSH 配置 {profile.name} 的连接插件未能自动更新：{err}（可稍后在 oai setup 中处理）。")
    return hermes_restarted


def _unit_matches_root(unit: Path, root: Path) -> bool:
    if not unit.is_file() or unit.is_symlink():
        return False
    try:
        entries = dict(line.split("=", 1) for line in unit.read_text(encoding="utf-8", errors="replace").splitlines() if "=" in line)
        return (shlex.split(entries.get("WorkingDirectory", "")) == [str(root)]
                and shlex.split(entries.get("ExecStart", "")) == [str(root / "start-core.sh")])
    except (OSError, ValueError):
        return False


def _restart_active_service_if_owned(root: Path, store: IdentityStore, handle: str) -> bool:
    """升级完成后，若属于当前实例的后台服务正在运行，则自动平滑重启使其加载新代码。"""
    if sys.platform.startswith("linux"):
        unit, _ = _service_files(root)
        candidates: list[tuple[Path, list[str]]] = [(unit, ["--user"])]
        if os.geteuid() == 0:
            candidates.append((Path("/etc/systemd/system/oai-core.service"), []))
        if not any(_unit_matches_root(candidate_unit, root) for candidate_unit, _ in candidates):
            return False
        if not shutil.which("systemctl"):
            return False
        for candidate_unit, flags in candidates:
            if not _unit_matches_root(candidate_unit, root):
                continue
            state = subprocess.run(
                ["systemctl", *flags, "show", "oai-core.service",
                 "--property=FragmentPath,WorkingDirectory,ActiveState"],
                cwd=root, check=False, capture_output=True, text=True, timeout=15,
            )
            if state.returncode != 0:
                continue
            loaded = dict(line.split("=", 1) for line in state.stdout.splitlines() if "=" in line)
            if (loaded.get("FragmentPath") == str(candidate_unit)
                    and loaded.get("WorkingDirectory") == str(root)
                    and loaded.get("ActiveState") in {"active", "activating", "reloading"}):
                _step("检测到后台服务 oai-core.service 正在运行，正在重启以加载新版本...")
                try:
                    _run(store, handle, "service", ["systemctl", *flags, "daemon-reload"], cwd=root)
                    _run(store, handle, "service", ["systemctl", *flags, "restart", "oai-core.service"], cwd=root)
                except subprocess.CalledProcessError:
                    _warn("后台服务自动重启失败，请手动执行 systemctl --user restart oai-core.service。")
                    return False
                _ok("后台服务 oai-core.service 已自动重启，新版本已生效。")
                return True
        return False
    if sys.platform == "darwin":
        import plistlib
        plist = Path.home() / "Library/LaunchAgents/com.openandroidintelligence.core.plist"
        if not plist.is_file() or plist.is_symlink() or not shutil.which("launchctl"):
            return False
        try:
            disk = plistlib.loads(owned_bytes(plist) or b"")
        except Exception:
            return False
        label = "com.openandroidintelligence.core"
        if (disk.get("Label") != label
                or disk.get("ProgramArguments") != [str(root / "start-core.sh")]
                or disk.get("WorkingDirectory") != str(root)):
            return False
        jobs = subprocess.run(["launchctl", "list"], cwd=root, check=False, capture_output=True, text=True, timeout=15)
        if jobs.returncode != 0:
            return False
        running = any(
            len(parts := line.split()) >= 3 and parts[-1] == label and parts[0] != "-"
            for line in jobs.stdout.splitlines()
        )
        if not running:
            return False
        _step("检测到 macOS 后台服务正在运行，正在重启以加载新版本...")
        try:
            _run(store, handle, "service", ["launchctl", "unload", str(plist)], cwd=root)
            _run(store, handle, "service", ["launchctl", "load", "-w", str(plist)], cwd=root)
        except subprocess.CalledProcessError:
            _warn("macOS 后台服务自动重启失败，请手动重新加载 LaunchAgent。")
            return False
        _ok("macOS 后台服务已自动重启，新版本已生效。")
        return True
    return False


def install(root: Path, *, restore_source: Path | None = None, upgrade_source: Path | None = None) -> int:
    storage, key, database = _paths(root)
    has_identity = database.exists() or database.is_symlink()
    state = _verify_journal(root, has_identity=has_identity)
    if has_identity:
        _box("验证管理员身份", (
            "检测到当前目录已有安装记录，请先完成身份验证。",
            "验证通过后将自动拉取/同步最新代码并升级组件，原有配置与数据保持不变。",
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
        source_updated = False
        if restore_source is not None:
            _step("正在恢复缺失的程序源码文件（原有配置与账号数据保持不变）...")
            with configuration_lock(root):
                _restore_install_source(root, restore_source, store, handle)
            _ok("程序源码文件已恢复。")
            source_updated = True
        elif upgrade_source is not None:
            _step("正在同步最新版本程序文件（原有配置与账号数据保持不变）...")
            with configuration_lock(root):
                updated_count = _sync_install_source(root, upgrade_source, store, handle)
            if updated_count > 0:
                _ok(f"程序文件已更新（共更新 {updated_count} 个文件）。")
                source_updated = True
            else:
                _ok("当前目录的程序文件已是最新版本。")
            _archive_temp_download_source(root, upgrade_source)
        elif has_identity:
            source_updated = _upgrade_git_checkout(root, store, handle)
        if source_updated and (root / "oai-core" / "src" / "oai_core" / "configuration.py").is_file():
            local_src = str((root / "oai-core" / "src").resolve())
            if not sys.path or sys.path[0] != local_src:
                sys.path.insert(0, local_src)
            importlib.reload(configuration)
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
            agents_home = Path.home() / ".agents"
            if ((root / "integrations" / "skills" / "oai-client" / "SKILL.md").is_file()
                    and (not agents_home.exists() or agents_home.is_dir())):
                configuration.deploy_skills_to_agents(root)
        # Existing installs are authenticated before pip, CLI or registration writes.
        local_package = (root / "oai-core").resolve()
        trusted_package = (
            local_package
            if (restore_source is not None or upgrade_source is not None or (local_package / "pyproject.toml").is_file())
            else Path(__file__).resolve().parents[2]
        )
        package_reference = "./oai-core" if trusted_package == local_package else os.path.relpath(trusted_package, root)
        _step("正在安装/更新 OAI Core 命令行与运行时组件...")
        pip_env = {**os.environ, "PIP_DISABLE_PIP_VERSION_CHECK": "1"}
        try:
            _run(store, handle, "install", [str(python), "-m", "pip", "install", "-e", package_reference],
                 cwd=root, env=pip_env)
        except subprocess.CalledProcessError:
            _run(store, handle, "install",
                 [str(python), "-m", "pip", "install", "--no-build-isolation", "--no-deps", "-e", package_reference],
                 cwd=root, env=pip_env)
        _run(store, handle, "install", [str(python), "-m", "pip", "check"], cwd=root, env=pip_env)
        store.require_management(handle, "install")
        register_instance(root / "core.json")
        _integrate_cli(root, store, handle)
        _ok("全局快捷命令 oai 已配置到 ~/.local/bin/oai。")
        if configuration.detect_host_agents():
            configuration.provision_host_candidates(root, store, handle, operator)
            hermes_restarted = bool(_upgrade_installed_host_adapters(root, store, handle))
            _step("正在扫描并尝试自动连接本机已安装的 AI 助手...")
            if hermes_restarted:
                configuration.auto_connect_host_agents(root, store, handle, operator, hermes_restarted=True)
            else:
                configuration.auto_connect_host_agents(root, store, handle, operator)
        _box("步骤 4/4 · 后台自启与服务设置", (
            "开启开机自启后，系统重启或登录时会自动在后台运行 OAI Core 服务。",
        ))
        autostart_choice = _select_option(
            "是否配置开机自启服务？(↑/↓ 选择或输入 y/n) [n]: ",
            (
                ("y", "1. 是，开启后台自启 (Yes)"),
                ("n", "2. 否，暂不开启 (No)"),
            ),
            default_index=1,
        )
        if autostart_choice.lower() in {"y", "yes", "1"}:
            _configure_service(root, store, handle)
        elif has_identity:
            _restart_active_service_if_owned(root, store, handle)
        if state is not None or (root / "runtime" / "installer-journal.json").exists():
            store.require_management(handle, "install")
            _journal(root, "complete")
        final_host, final_port, _, _ = configuration._network_settings(root)
        done_title = "🎉 OAI Core 更新已完成" if has_identity else "🎉 OAI Core 安装已完成"
        _box(done_title, (
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
    parser.add_argument("--upgrade-source", type=Path, default=None)
    args = parser.parse_args()
    root = args.dir.expanduser().absolute()
    if root.is_symlink() or root.resolve() != root:
        raise ValueError("安装路径不能包含符号链接")
    upgrade_source = args.upgrade_source.expanduser().absolute() if args.upgrade_source is not None else None
    return install(root, upgrade_source=upgrade_source) if args.operation == "install" else setup(root)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        code = getattr(error, "code", type(error).__name__)
        print(f"[错误] 操作未完成（原因：{code}）。原有配置与账号数据保持不变。", file=sys.stderr)
        raise SystemExit(1) from None
