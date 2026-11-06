"""Bounded, explicit local DSH profile installs through the official manager.

Discovery never chooses a default profile. The caller owns confirmation, local
endpoint eligibility, registration/handshake and any later host restart.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import stat
import shutil
import signal
import subprocess
from uuid import uuid4


OLD_BUNDLE = "dsh-android-gateway"
BUNDLE = "oai-dsh-session-adapter"
_EXCLUDED = {"node_modules", "build", "dist", ".gradle"}
_MESSAGES = {
    "DSH_PROFILE_INVALID": "所选的 DSH 配置名称 (profile) 格式无效。",
    "DSH_PROFILE_NOT_FOUND": "未找到指定的 DSH 运行配置 (profile)。",
    "DSH_PROFILE_AMBIGUOUS": "检测到多个 DSH 目录包含同名配置，无法唯一确定要更新的目标。",
    "DSH_PROFILE_UNSAFE": "DSH 配置目录或文件权限不安全（或包含符号链接），已停止操作。",
    "DSH_PERMISSION_DENIED": "当前账号没有管理 AI 助手的权限，已停止操作。",
    "DSH_CLI_NOT_FOUND": "未在系统中找到 DSH 命令，请先安装官方 DSH 客户端。",
    "DSH_NODE_NOT_FOUND": "未在系统中找到 Node.js，无法安装或更新 DSH 连接插件。",
    "DSH_BUNDLE_INVALID": "当前目录下的 DSH 连接插件安装包缺失或损坏。",
    "DSH_BACKUP_FAILED": "无法备份原有的 DSH 配置文件，为安全起见已停止操作。",
    "DSH_PLUGIN_MANAGER_UNAVAILABLE": "当前 DSH 版本过旧（缺少官方插件管理接口），请先升级 DSH 客户端。",
    "DSH_PROFILE_CHANGED": "DSH 配置文件在准备期间被其他程序修改，已停止操作，请重试。",
    "DSH_INSTALL_INCOMPATIBLE": "当前 DSH 版本与连接插件不兼容，原配置备份已保留在 runtime/dsh-adapter-backups。",
    "DSH_INSTALL_TIMEOUT": "DSH 连接插件安装超时，原配置备份已保留在 runtime/dsh-adapter-backups，请检查后重试。",
    "DSH_INSTALL_FAILED": "DSH 连接插件安装失败，原配置备份已保留在 runtime/dsh-adapter-backups，请检查 DSH 状态。",
    "DSH_INSTALL_RECOVERY_REQUIRED": "DSH 插件安装失败且自动还原未完成，原配置备份保存在 runtime/dsh-adapter-backups，请人工核对。",
}


class DshAdapterInstallError(ValueError):
    """Only stable codes and fixed safe messages cross the installer boundary."""

    def __init__(self, code: str):
        self.code = code if code in _MESSAGES else "DSH_INSTALL_FAILED"
        super().__init__(_MESSAGES[self.code])


@dataclass(frozen=True)
class DshProfile:
    home: Path
    name: str
    directory: Path


def _valid_name(name: str) -> bool:
    return (isinstance(name, str) and bool(name.strip()) and name == name.strip()
            and name not in {".", "..", *_EXCLUDED}
            and not any(c in name for c in "/\\")
            and not any(ord(c) < 32 or ord(c) == 127 for c in name))


def _safe_path(path: Path, *, directory: bool = False, owned: bool = True) -> Path:
    # Do not resolve symlinks before inspecting every component, including the
    # supplied home's parents. In particular a benign final lstat is not enough.
    if ".." in Path(path).parts:
        raise ValueError("unchecked parent traversal")
    path = Path(os.path.abspath(path))
    for part in [*reversed(path.parents), path]:
        info = part.lstat()
        if stat.S_ISLNK(info.st_mode) or (part != path and not stat.S_ISDIR(info.st_mode)):
            raise ValueError("unsafe path")
    info = path.lstat()
    if ((directory and not stat.S_ISDIR(info.st_mode))
            or (not directory and not stat.S_ISREG(info.st_mode))
            or (owned and info.st_uid != os.getuid())):
        raise ValueError("unsafe path")
    return path


def _owned_bytes(path: Path, *, limit: int = 8 * 1024 * 1024) -> bytes:
    path = _safe_path(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_size > limit:
            raise ValueError("unsafe file")
        content = source.read(limit + 1)
    if len(content) > limit:
        raise ValueError("file too large")
    return content


def _profile_manifest(profile: DshProfile, *, include_uninstalled: bool = False) -> tuple[dict, bytes]:
    if not _valid_name(profile.name):
        raise ValueError("invalid profile")
    home = _safe_path(profile.home, directory=True)
    parent = _safe_path(home / "profiles", directory=True)
    directory = _safe_path(profile.directory, directory=True)
    if directory != parent / profile.name:
        raise ValueError("profile outside home")
    content = _owned_bytes(directory / "package.json")
    manifest = json.loads(content)
    if not isinstance(manifest, dict):
        raise ValueError("invalid manifest")
    # Official profiles may omit dsh, profile or bundles entirely. Treat only
    # absent fields as empty; an explicitly malformed field is still unsafe.
    dsh = manifest.get("dsh", {})
    config = dsh.get("profile", {}) if isinstance(dsh, dict) else None
    bundles = config.get("bundles", []) if isinstance(config, dict) else None
    if not isinstance(bundles, list) or not all(isinstance(b, str) for b in bundles):
        raise ValueError("invalid profile bundles")
    if not include_uninstalled and not {OLD_BUNDLE, BUNDLE}.intersection(bundles):
        raise ValueError("not an upgrade target")
    return manifest, content


def discover_dsh_profiles(homes: list[Path], *, include_uninstalled: bool = False) -> list[DshProfile]:
    """List owned direct profiles, optionally including first-install targets.

    The default only returns profiles already enabling the old or new adapter.
    Discovery neither creates profiles nor selects one for installation.
    """
    selected = os.environ.get("DSH_PROFILE")
    if selected is not None and not _valid_name(selected):
        raise DshAdapterInstallError("DSH_PROFILE_INVALID")
    result = []
    seen = set()
    for candidate in homes:
        try:
            home = _safe_path(candidate, directory=True)
            parent = _safe_path(home / "profiles", directory=True)
            children = sorted(parent.iterdir(), key=lambda p: p.name)
        except (OSError, ValueError):
            continue
        for directory in children:
            if directory in seen or not _valid_name(directory.name):
                continue
            if selected is not None and directory.name != selected:
                continue
            profile = DshProfile(home, directory.name, directory)
            try:
                _profile_manifest(profile, include_uninstalled=include_uninstalled)
            except (OSError, ValueError):
                continue
            seen.add(directory)
            result.append(profile)
    if selected is not None:
        if not result:
            raise DshAdapterInstallError("DSH_PROFILE_NOT_FOUND")
        if len(result) != 1:
            raise DshAdapterInstallError("DSH_PROFILE_AMBIGUOUS")
    return result


def _authorize(store, handle: str) -> None:
    try:
        if not isinstance(handle, str) or not handle:
            raise ValueError("missing handle")
        store.require_management(handle, "agents.manage")
    except Exception:
        raise DshAdapterInstallError("DSH_PERMISSION_DENIED") from None


# The Node entry uses only these numeric statuses; subprocess output is never
# parsed or exposed to a caller (including exception text and diagnostics).
_CHILD_ERRORS = {
    20: "DSH_PLUGIN_MANAGER_UNAVAILABLE",
    21: "DSH_PROFILE_UNSAFE",
    22: "DSH_PROFILE_CHANGED",
    23: "DSH_INSTALL_INCOMPATIBLE",
    24: "DSH_INSTALL_TIMEOUT",
    25: "DSH_BUNDLE_INVALID",
    27: "DSH_INSTALL_RECOVERY_REQUIRED",
}


def _private_directory(path: Path) -> Path:
    try:
        path.mkdir(mode=0o700)
    except FileExistsError:
        pass
    _safe_path(path, directory=True)
    if stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise ValueError("private directory required")
    return path


def _backup(root: Path, profile: DshProfile, launcher: Path, content: bytes,
            lock: bytes | None, *, include_uninstalled: bool = False) -> Path:
    # Existing runtime directories need not be private, but their owner and
    # non-symlink ancestry must be valid. Every new backup directory is 0700.
    runtime = root / "runtime"
    try:
        runtime.mkdir(mode=0o700)
    except FileExistsError:
        pass
    _safe_path(runtime, directory=True)
    parent = _private_directory(runtime / "dsh-adapter-backups")
    destination = _private_directory(parent / uuid4().hex)
    metadata = {
        "version": 1,
        "includeUninstalled": include_uninstalled,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "profile": profile.name,
        "home": os.path.relpath(profile.home, root),
        "directory": os.path.relpath(profile.directory, root),
        "bundle": "integrations/dsh-session-adapter",
        "launcher": os.path.relpath(launcher, root),
        "files": {"package.json": hashlib.sha256(content).hexdigest(),
                  "pnpm-lock.yaml": None if lock is None else hashlib.sha256(lock).hexdigest()},
    }
    files = {"package.json": content,
             "metadata.json": (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode()}
    if lock is not None:
        files["pnpm-lock.yaml"] = lock
    for name, data in files.items():
        fd = os.open(destination / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as target:
            target.write(data)
            target.flush()
            os.fsync(target.fileno())
    from .configuration import fsync_directory
    for directory in (destination, parent, runtime, root):
        fsync_directory(directory)
    return destination


def _child_environment() -> dict[str, str]:
    # Never inherit administrator handles, NODE_OPTIONS/NODE_PATH injection or
    # account tokens. The official manager independently scrubs pnpm's service
    # environment. HOME/PATH are only the ordinary launcher execution context.
    return {key: os.environ[key] for key in ("PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "TMPDIR")
            if key in os.environ}


def _run_helper(arguments: list[str], root: Path) -> None:
    try:
        child = subprocess.Popen(arguments, cwd=root, env=_child_environment(),
                                 stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, start_new_session=True)
    except Exception:
        raise DshAdapterInstallError("DSH_INSTALL_FAILED") from None
    try:
        status = child.wait(timeout=120)
    except subprocess.TimeoutExpired:
        # Node has its own earlier abort deadline and lets the official manager
        # stop pnpm. This is the final bound for a broken manager/Node process.
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.killpg(child.pid, sig)
            except OSError:
                break
            try:
                child.wait(timeout=5)
                break
            except subprocess.TimeoutExpired:
                continue
            except OSError:
                break
        raise DshAdapterInstallError("DSH_INSTALL_TIMEOUT") from None
    except OSError:
        raise DshAdapterInstallError("DSH_INSTALL_FAILED") from None
    if status != 0:
        raise DshAdapterInstallError(_CHILD_ERRORS.get(status, "DSH_INSTALL_FAILED"))


def install_dsh_adapter(root: Path, store, handle: str, profile: DshProfile, *,
                        include_uninstalled: bool = False) -> None:
    """Install this repository's bundle into exactly one confirmed profile.

    First installation requires include_uninstalled=True, independently of
    discovery. Missing profile fields are left for the official host to write.
    Permission is checked at entry and immediately before both backup creation
    and process launch. Failures retain backups; no account, key, history,
    registration, default-profile selection or host restart is performed here.
    """
    _authorize(store, handle)
    try:
        _profile_manifest(profile, include_uninstalled=include_uninstalled)
    except (OSError, ValueError, TypeError):
        raise DshAdapterInstallError("DSH_PROFILE_UNSAFE") from None
    dsh = shutil.which("dsh")
    if dsh is None:
        raise DshAdapterInstallError("DSH_CLI_NOT_FOUND")
    node = shutil.which("node")
    if node is None:
        raise DshAdapterInstallError("DSH_NODE_NOT_FOUND")
    # Popen runs in the Core root; a relative PATH entry belongs to the
    # caller's directory and must be anchored before changing cwd.
    node = os.path.abspath(node)
    try:
        launcher = Path(dsh).resolve(strict=True)
        # The launcher and its package, not profile/node_modules, supply the
        # trusted module resolution anchor. Reject wrappers/unknown packages.
        _safe_path(launcher, owned=False)
        anchor = launcher.parent.parent / "package.json"
        if (launcher.name != "bin.js" or launcher.parent.name != "lib"
                or json.loads(anchor.read_bytes()).get("name") != "@deepseek-ai/dsh"):
            raise ValueError("unknown launcher")
    except (OSError, ValueError, AttributeError):
        raise DshAdapterInstallError("DSH_PLUGIN_MANAGER_UNAVAILABLE") from None
    try:
        root = _safe_path(root, directory=True)
        bundle = _safe_path(root / "integrations" / "dsh-session-adapter", directory=True)
        _safe_path(bundle / "install.mjs")
        if json.loads(_owned_bytes(bundle / "package.json")).get("name") != BUNDLE:
            raise ValueError("unexpected bundle")
    except (OSError, ValueError, AttributeError):
        raise DshAdapterInstallError("DSH_BUNDLE_INVALID") from None
    try:
        _, content = _profile_manifest(profile, include_uninstalled=include_uninstalled)
        lock_path = profile.directory / "pnpm-lock.yaml"
        try:
            lock = _owned_bytes(lock_path, limit=32 * 1024 * 1024)
        except FileNotFoundError:
            lock = None
    except (OSError, ValueError):
        raise DshAdapterInstallError("DSH_PROFILE_UNSAFE") from None
    _authorize(store, handle)
    try:
        backup = _backup(root, profile, launcher, content, lock,
                         include_uninstalled=include_uninstalled)
    except (OSError, ValueError):
        raise DshAdapterInstallError("DSH_BACKUP_FAILED") from None
    arguments = [node, "integrations/dsh-session-adapter/install.mjs",
                 "--launcher", os.path.relpath(launcher, root),
                 "--home", os.path.relpath(profile.home, root),
                 "--profile", profile.name,
                 "--backup", os.path.relpath(backup, root)]
    if include_uninstalled:
        arguments.append("--include-uninstalled")
    _authorize(store, handle)
    _run_helper(arguments, root)
