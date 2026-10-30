#!/usr/bin/env bash
# OAI Core 卸载：默认保留配置，--purge 清空安装目录；文件均移至临时回收目录。
# curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/uninstall.sh | bash
set -euo pipefail
if ! command -v python3 >/dev/null 2>&1; then
    printf '%s\n' '[错误] 卸载脚本需要 Python 3.11+，请先安装 Python。' >&2
    exit 1
fi
exec python3 - "$@" <<'PYTHON'
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import time
import uuid

if sys.version_info < (3, 11):
    raise SystemExit("[错误] 卸载脚本需要 Python 3.11+。")
if os.name != "posix":
    raise SystemExit("[错误] 当前卸载脚本支持 Linux/macOS；Windows 原生 Python 环境请手动卸载。")


class UninstallError(Exception):
    pass


def fail(message):
    raise UninstallError(message)


def exists(path):
    return path.exists() or path.is_symlink()


def identity(path):
    metadata = path.lstat()
    fingerprint = None
    if stat.S_ISREG(metadata.st_mode):
        fingerprint = hashlib.sha256(path.read_bytes()).hexdigest()
    elif stat.S_ISLNK(metadata.st_mode):
        fingerprint = os.readlink(path)
    return metadata.st_dev, metadata.st_ino, metadata.st_mode, fingerprint


def owned_text(path):
    if path.is_symlink():
        return None
    metadata = path.stat()
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid():
        fail(f"文件不属于当前用户或不是普通文件，已中止: {path}")
    return path.read_text(encoding="utf-8")


def validate_root(raw, home, purge):
    if not raw.strip():
        fail("安装目录不能为空。")
    root = Path(os.path.abspath(os.path.expanduser(raw)))
    protected = {Path(value) for value in (
        "/", "/usr", "/bin", "/sbin", "/etc", "/var", "/tmp", "/opt", "/srv",
        "/home", "/Users", "/Applications", "/Library",
    )}
    if root in protected or root == home or root in home.parents:
        fail(f"拒绝卸载系统目录或用户主目录: {root}")
    if root.is_symlink() or root.resolve() != root:
        fail("安装目录及其父目录不能包含符号链接；请使用真实安装路径。")
    if not root.exists():
        return root
    if not root.is_dir() or root.stat().st_uid != os.getuid():
        fail("安装目录必须是当前用户拥有的目录。")
    project = root / "oai-core" / "pyproject.toml"
    installer = root / "scripts" / "install.sh"
    if not project.is_file() or not installer.is_file():
        fail("目标目录不是受支持的 OAI Core 源码安装（缺少安装标记）。")
    import tomllib
    if tomllib.loads(owned_text(project) or "").get("project", {}).get("name") != "open-android-intelligence-core":
        fail("目标目录的 Python 项目不是 OAI Core。")
    if purge:
        if (root / ".git").is_file() or (root / ".git").is_symlink():
            fail("拒绝彻底卸载 Git worktree；请先用 Git 管理独立工作区。")
        worktrees = root / ".git" / "worktrees"
        if worktrees.exists() and any(worktrees.iterdir()):
            fail("该仓库仍有其他 Git worktree，不能移动共享 Git 数据。")
    return root


def command(arguments, *, input_text=None, acceptable=(0,)):
    try:
        result = subprocess.run(arguments, input=input_text, capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        fail(f"无法完成 {Path(arguments[0]).name} 操作；尚未移动的文件已保留。")
    if result.returncode not in acceptable:
        fail(f"{Path(arguments[0]).name} 操作失败（退出码 {result.returncode}）；请先手动停止对应服务后重试。")
    return result


def config_paths(root):
    path = root / "core.json"
    if not exists(path):
        return []
    text = owned_text(path)
    if text is None:
        fail("core.json 是符号链接，无法确认配置边界，请先核对真实配置。")
    try:
        config = json.loads(text)
        if not isinstance(config, dict):
            raise ValueError()
        values = [config.get(name) for name in ("storageRoot", "masterKeyFile", "contractRoot")]
        tls = config.get("tls", {})
        if isinstance(tls, dict):
            values.extend(tls.get(name) for name in ("certFile", "keyFile"))
        for agent in config.get("agents", []):
            if isinstance(agent, dict):
                values.extend(agent.get(name) for name in ("codexHome", "gatewayRoot"))
        paths = []
        for value in values:
            if isinstance(value, str) and value:
                lexical = Path(os.path.abspath(root / value))
                paths.extend((lexical, lexical.resolve()))
        return list(dict.fromkeys(paths))
    except (ValueError, TypeError, AttributeError):
        fail("无法解析 core.json；请先核对配置，卸载尚未执行。")


def same_command(value, executable):
    if value == str(executable):
        return True
    try:
        return shlex.split(value) == [str(executable)]
    except ValueError:
        return False


def startup_files(root, home):
    """Only installer-owned entries that point to this exact installation."""
    files = []
    service = None
    launch_agent = None
    start = root / "start-core.sh"
    wrapper = home / ".local" / "bin" / "oai"
    if wrapper.is_symlink():
        target = Path(os.path.abspath(wrapper.parent / os.readlink(wrapper)))
        if target in {start, root / ".venv" / "bin" / "oai"}:
            files.append(wrapper)
    elif wrapper.exists():
        for line in (owned_text(wrapper) or "").splitlines():
            if line.startswith("OAI_DIR="):
                try:
                    if shlex.split(line[len("OAI_DIR="):]) == [str(root)]:
                        files.append(wrapper)
                except ValueError:
                    pass
                break
    unit = home / ".config" / "systemd" / "user" / "oai-core.service"
    if unit.exists() and not unit.is_symlink():
        settings = dict(line.split("=", 1) for line in (owned_text(unit) or "").splitlines()
                        if "=" in line and not line.lstrip().startswith(("#", ";")))
        if settings.get("WorkingDirectory") == str(root) and same_command(settings.get("ExecStart", ""), start):
            service = unit
            files.append(unit)
            for link in sorted(unit.parent.glob("*.wants/oai-core.service")):
                if link.is_symlink() and link.resolve() == unit:
                    files.append(link)
    desktop = home / ".config" / "autostart" / "oai-core.desktop"
    if desktop.exists() and not desktop.is_symlink():
        if any(line.startswith("Exec=") and same_command(line[5:], start)
               for line in (owned_text(desktop) or "").splitlines()):
            files.append(desktop)
    plist = home / "Library" / "LaunchAgents" / "com.openandroidintelligence.core.plist"
    if plist.exists() and not plist.is_symlink():
        try:
            settings = plistlib.loads((owned_text(plist) or "").encode())
        except (ValueError, plistlib.InvalidFileException):
            fail(f"无法解析自启项: {plist}")
        if settings.get("Label") == "com.openandroidintelligence.core" and settings.get("ProgramArguments") == [str(start)]:
            launch_agent = plist
            files.append(plist)
    appdata = os.environ.get("APPDATA")
    if appdata:
        vbs = Path(appdata) / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup" / "oai-core.vbs"
        if vbs.exists() and not vbs.is_symlink():
            if f'WshShell.Run chr(34) & "{start}" & Chr(34), 0' in (owned_text(vbs) or "").splitlines():
                files.append(vbs)
    return files, service, launch_agent



def check_move_mounts(paths):
    directories = [path for path in paths if path.is_dir() and not path.is_symlink()]
    if not directories:
        return
    if sys.platform.startswith("linux"):
        entries = Path("/proc/self/mountinfo").read_text().splitlines()
        mounts = []
        for entry in entries:
            fields = entry.split()
            if len(fields) < 6:
                fail("无法解析挂载列表，已中止目录迁移。")
            mounts.append(Path(re.sub(r"\\([0-7]{3})", lambda match: chr(int(match[1], 8)), fields[4])))
    elif sys.platform == "darwin":
        mounts = []
        for line in command(["mount"]).stdout.splitlines():
            if " on " not in line or " (" not in line:
                fail("无法解析挂载列表，已中止目录迁移。")
            mounts.append(Path(line.rsplit(" on ", 1)[1].rsplit(" (", 1)[0]))
    else:
        fail("当前平台无法可靠核对挂载边界，请手动卸载。")
    if any(mount == directory or directory in mount.parents for directory in directories for mount in mounts):
        fail("待迁移目录本身或内部存在挂载点；为保护共享数据，已中止，请先核对并解除挂载。")


def check_retained_data(data, runtime_files):
    for path in data:
        for item in runtime_files:
            resolved = item.resolve()
            if (path == item or item in path.parents
                    or path.resolve() == resolved or resolved in path.resolve().parents):
                fail("配置数据依赖待移除的虚拟环境或启动脚本，无法保证保留；请先调整数据路径。")


def cron_plan(root):
    if not shutil.which("crontab"):
        return None
    result = command(["crontab", "-l"], acceptable=(0, 1))
    if result.returncode == 1:
        if result.stdout or (result.stderr and "no crontab" not in result.stderr.lower()):
            fail("无法读取当前用户的 crontab；请核对后重试。")
        return None
    expected = f"@reboot {root}/start-core.sh >/dev/null 2>&1 &"
    remaining = [line for line in result.stdout.splitlines(keepends=True) if line.rstrip("\r\n") != expected]
    updated = "".join(remaining)
    return (result.stdout, updated) if updated != result.stdout else None


def core_processes(root):
    """Match serve commands by their parsed config path, never by a substring."""
    result = command(["ps", "-axo", "pid=,uid=,command="])
    found = []
    for line in result.stdout.splitlines():
        fields = line.strip().split(None, 2)
        if len(fields) != 3 or not fields[0].isdigit() or fields[1] != str(os.getuid()):
            continue
        pid = int(fields[0])
        if pid == os.getpid() or "serve" not in fields[2] or "oai" not in fields[2]:
            continue
        proc = Path("/proc") / str(pid)
        try:
            if proc.is_dir():
                args = [part.decode(errors="surrogateescape") for part in (proc / "cmdline").read_bytes().split(b"\0") if part]
            else:
                if any(character.isspace() for character in str(root)) and str(root) in fields[2]:
                    fail("此平台无法可靠解析含空格路径的运行进程；请先手动停止当前 Core 后重试。")
                args = shlex.split(fields[2])
            cwd = None
            if "serve" not in args:
                continue
            serve = args.index("serve")
            launcher = args[:serve]
            if not launcher:
                continue
            executable = Path(launcher[0]).name
            is_oai = executable == "oai" or (
                executable.startswith("python") and (
                    (len(launcher) > 1 and Path(launcher[1]).name == "oai")
                    or launcher[1:3] == ["-m", "oai_core.cli"]
                )
            )
            if not is_oai:
                continue
            config = None
            for index, arg in enumerate(args[serve + 1:], serve + 1):
                if arg == "--config" and index + 1 < len(args):
                    config = args[index + 1]
                elif arg.startswith("--config="):
                    config = arg.split("=", 1)[1]
            if config is None:
                continue
            if not Path(config).is_absolute() and proc.is_dir():
                cwd = Path(os.readlink(proc / "cwd"))
            if not Path(config).is_absolute() and cwd is None:
                # macOS has no /proc; inspect only this candidate's working directory.
                if not shutil.which("lsof"):
                    fail("存在使用相对配置路径的 Core 进程，无法确认其工作目录；请先手动停止 Core。")
                listing = command(["lsof", "-a", "-p", str(pid), "-d", "cwd", "-Fn"])
                directories = [value[1:] for value in listing.stdout.splitlines() if value.startswith("n")]
                if len(directories) != 1:
                    fail("无法确认 Core 进程工作目录，请先手动停止 Core。")
                cwd = Path(directories[0])
            if ((cwd or root) / config).resolve() != root / "core.json":
                continue
            script = launcher[0] if executable == "oai" else (launcher[1] if len(launcher) > 1 and Path(launcher[1]).name == "oai" else None)
            if script is None:
                interpreter = Path(launcher[0])
                owned_launcher = interpreter.is_absolute() and interpreter.parent == root / ".venv" / "bin"
            else:
                if not Path(script).is_absolute() and cwd is None and proc.is_dir():
                    cwd = Path(os.readlink(proc / "cwd"))
                owned_launcher = (Path(script).is_absolute() or cwd is not None) and Path(os.path.abspath((cwd or root) / script)) == root / ".venv" / "bin" / "oai"
            if not owned_launcher:
                fail(f"进程 {pid} 使用此配置，但其程序不属于本次独立安装；请先手动停止，未向该进程发送信号。")
            found.append(pid)
        except (FileNotFoundError, ProcessLookupError):
            continue
        except (PermissionError, ValueError):
            fail("无法确认 Core 运行进程；请先手动停止 Core 后重试。")
    return found


def stop_processes(root):
    for pid in core_processes(root):
        # Recheck ownership/command immediately before sending a graceful stop.
        if pid in core_processes(root):
            try:
                os.kill(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
    deadline = time.monotonic() + 10
    while core_processes(root):
        if time.monotonic() >= deadline:
            fail("Core 在 10 秒内未停止；安装文件与数据尚未移动，请手动停止后重试。")
        time.sleep(0.2)


@contextmanager
def configuration_lock(root):
    runtime = root / "runtime"
    if runtime.is_symlink():
        fail("runtime 是符号链接，无法安全取得配置锁；请先核对路径。")
    runtime.mkdir(mode=0o700, exist_ok=True)
    descriptor = os.open(runtime / "configuration.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid() or metadata.st_mode & 0o077:
            fail("配置锁须归当前用户所有且权限为 0600。")
        import fcntl
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(descriptor)



def check_journal(root):
    path = root / "runtime" / "configuration-journal.json"
    if exists(path):
        try:
            journal = json.loads(owned_text(path) or "null")
            if not isinstance(journal, dict) or journal.get("version") != 1 or journal.get("status") not in {"committed", "rolled_back"}:
                raise ValueError()
        except (ValueError, TypeError):
            fail("配置恢复记录未完成或已损坏；请先核对 configuration-journal.json，未执行卸载。")


def stop_service(service, root):
    state = command(["systemctl", "--user", "show", "oai-core.service", "--property=LoadState,FragmentPath,WorkingDirectory"])
    properties = dict(line.split("=", 1) for line in state.stdout.splitlines() if "=" in line)
    if properties.get("LoadState") == "not-found":
        return
    if properties.get("FragmentPath") != str(service) or properties.get("WorkingDirectory") != str(root):
        fail("当前加载的 systemd 服务与此安装不匹配；为避免停止其他实例，已中止。")
    command(["systemctl", "--user", "stop", "oai-core.service"])



def stop_launch_agent(plist, root):
    if not shutil.which("launchctl"):
        fail("发现 macOS 自启项但 launchctl 不可用；请先手动停止服务。")
    label = "com.openandroidintelligence.core"
    jobs = command(["launchctl", "list"])
    if not any(line.split() and line.split()[-1] == label for line in jobs.stdout.splitlines()):
        return
    loaded = command(["launchctl", "list", "-x", label])
    try:
        settings = plistlib.loads(loaded.stdout.encode())
    except (ValueError, plistlib.InvalidFileException):
        fail("无法核对已加载的 macOS 服务配置，已中止；请先手动停止服务。")
    if settings.get("ProgramArguments") != [str(root / "start-core.sh")]:
        fail("已加载的 macOS 服务属于另一安装，已中止。")
    command(["launchctl", "unload", str(plist)])


def private_directory(path):
    if path.is_symlink():
        fail(f"回收路径不能是符号链接: {path}")
    path.mkdir(mode=0o700, exist_ok=True)
    metadata = path.stat()
    if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != os.getuid():
        fail(f"回收目录不属于当前用户: {path}")
    if stat.S_IMODE(metadata.st_mode) != 0o700:
        fail(f"回收目录权限必须为 0700: {path}")


def create_batch():
    base = Path("/tmp") / "open-android-intelligence-trash"
    private_directory(base)
    user = base / str(os.getuid())
    private_directory(user)
    batch = user / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-" + uuid.uuid4().hex[:8])
    batch.mkdir(mode=0o700)
    return batch


def write_private(path, text):
    # A failed replacement must leave the last complete recovery map readable.
    candidate = path.with_name(path.name + "." + uuid.uuid4().hex + ".next")
    descriptor = os.open(candidate, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(text)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(candidate, path)
    directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def confirm(purge):
    expected = "PURGE" if purge else "y"
    try:
        with open("/dev/tty", "r", encoding="utf-8") as terminal_input, open("/dev/tty", "w", encoding="utf-8") as terminal_output:
            terminal_output.write(f"确认卸载，请输入 {expected}，其他输入取消: ")
            terminal_output.flush()
            answer = terminal_input.readline().strip()
    except OSError:
        fail("非交互执行需要显式传入 --yes；可先使用 --dry-run 预览。")
    if answer != expected:
        fail("已取消，未执行卸载。")


def main():
    parser = argparse.ArgumentParser(prog="uninstall.sh", add_help=False, description="卸载 OAI Core；支持 Linux/macOS、Python 3.11+，所有移除文件均移入临时回收目录，供人工复核。")
    parser.add_argument("-h", "--help", action="help", help="显示帮助信息并退出")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--keep-config", action="store_true", help="默认：卸载独立虚拟环境和启动项，保留源码、配置、密钥、账号及历史，可原地重装")
    modes.add_argument("--purge", action="store_true", help="将安装目录整体移入回收目录；外置数据和 Agent 宿主环境保留")
    parser.add_argument("--dir", default=os.environ.get("OAI_DIR", str(Path.home() / ".oai-core")), metavar="PATH", help="安装目录（默认 OAI_DIR 或 ~/.oai-core；不根据当前仓库推断）")
    parser.add_argument("--yes", action="store_true", help="跳过交互确认（两种模式均可使用）")
    parser.add_argument("--dry-run", action="store_true", help="仅显示计划，不停止服务、不移动文件")
    args = parser.parse_args()
    home = Path.home().resolve()
    root = validate_root(args.dir, home, args.purge)
    if not root.exists():
        print(f"安装目录不存在，无需卸载: {root}")
        return
    configuration = [root / "core.json", root / "runtime" / "configuration-journal.json"]
    configuration_snapshot = {path: identity(path) if exists(path) else None for path in configuration}
    check_journal(root)
    data = config_paths(root)
    files, service, launch_agent = startup_files(root, home)
    runtime_files = [path for path in (root / ".venv", root / "start-core.sh") if exists(path)]
    if not args.purge:
        check_retained_data(data, runtime_files)
    moves = files + ([root] if args.purge else runtime_files)
    check_move_mounts(moves)
    cron = cron_plan(root)
    snapshots = {path: identity(path) for path in moves}
    print(f"卸载模式: {'彻底卸载（安装目录整体移入回收目录）' if args.purge else '保留配置（保留源码、配置、账号、密钥与历史）'}")
    print(f"安装目录: {root}")
    for path in moves:
        print(f"将移入回收目录: {path}")
    if service or launch_agent:
        print("将停止此安装对应的开机自启服务。")
    if cron:
        print("将备份 crontab 并移除完全匹配此安装的 @reboot 行。")
    for path in sorted(set(data)):
        if path != root and root not in path.parents:
            print(f"安装目录外的数据保持原位，请按需人工处理: {path}")
    print("Hermes、DSH、Codex 宿主、外置配置和共享 PATH 设置保持原位。")
    if args.dry_run:
        for pid in core_processes(root):
            print(f"将停止本安装的 Core 进程: {pid}")
        print("预览完成，未修改任何文件或服务。")
        return
    if not moves and not cron:
        print("未发现需要卸载的运行环境或启动项，保留的数据未改动。")
        return
    if not args.yes:
        confirm(args.purge)
    batch = None
    manifest = None
    try:
        with configuration_lock(root):
            if any((identity(path) if exists(path) else None) != snapshot for path, snapshot in configuration_snapshot.items()):
                fail("配置在确认期间发生变化，已中止；请重新预览。")
            check_journal(root)
            if service and shutil.which("systemctl"):
                stop_service(service, root)
            if launch_agent:
                stop_launch_agent(launch_agent, root)
            stop_processes(root)
            if any((identity(path) if exists(path) else None) != snapshot for path, snapshot in configuration_snapshot.items()):
                fail("停止服务期间配置发生变化，已中止；请重新预览。")
            if any(identity(path) != snapshot for path, snapshot in snapshots.items()):
                fail("安装文件或启动项在确认期间发生变化，已中止；请重新预览。")
            if cron and cron_plan(root) != cron:
                fail("crontab 在确认期间发生变化，已中止；请重新预览。")
            check_move_mounts(moves)
            batch = create_batch()
            print(f"回收目录: {batch}", flush=True)
            manifest = {"version": 1, "mode": "purge" if args.purge else "keep-config", "installation": str(root), "status": "in_progress", "moves": [
                {"source": str(path), "destination": str(batch / (f"{index:02d}-" + path.name)), "completed": False}
                for index, path in enumerate(moves)
            ]}
            def persist():
                write_private(batch / "manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
            persist()
            if cron:
                write_private(batch / "crontab.before", cron[0])
                command(["crontab", "-"], input_text=cron[1])
                manifest["crontab_backup"] = "crontab.before"
                persist()
            # The interpreter has already read this standalone script into memory.
            if args.purge:
                os.chdir(root.parent)
            for move in manifest["moves"]:
                source = Path(move["source"])
                if identity(source) != snapshots[source]:
                    fail(f"文件在移动前发生变化，已中止: {source}")
                shutil.move(str(source), move["destination"])
                move["completed"] = True
                persist()
            if service and shutil.which("systemctl"):
                command(["systemctl", "--user", "daemon-reload"])
            manifest["status"] = "completed"
            persist()
    except Exception:
        if batch and manifest:
            manifest["status"] = "failed"
            try:
                write_private(batch / "manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
            except OSError:
                print("无法更新清单，已保留上一份完整记录及候选文件，请按实际路径核对。", file=sys.stderr)
            print(f"部分操作未完成，请按 manifest.json 核对原路径与回收路径: {batch}", file=sys.stderr)
        raise
    print("OAI Core 卸载完成。回收目录可能包含密钥，请复核后自行清理；未进行安全擦除。")
    if not args.purge:
        print(f"配置与源码保留在 {root}；重新运行 scripts/install.sh 可原地恢复安装。")


if __name__ == "__main__":
    try:
        main()
    except (UninstallError, OSError, ValueError) as error:
        print(f"[错误] {error}", file=sys.stderr)
        raise SystemExit(1)
PYTHON
