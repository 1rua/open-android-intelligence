"""Install trusted local packages and a standard, same-user Hermes user plugin.

The caller selects and confirms HERMES_HOME. No account/key/history migration or
host restart occurs. CLI enablement applies on the host's next normal startup.
All recovery copies remain on disk; even failed replacements are renamed aside.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import tomllib

from .configuration import require_configuration_access


PLUGIN = "oai-hermes-session-adapter"
LEGACY_PLUGIN = "open-android-intelligence-gateway"
_TIMEOUT = 300
_EXCLUDED = {".git", "__pycache__", "node_modules", "build", "dist", ".gradle", ".venv", ".pytest_cache"}
_MESSAGES = {
    "HERMES_PERMISSION_DENIED": "当前账号没有管理 AI 助手的权限，已停止安装。",
    "HERMES_PATH_UNSAFE": "Hermes 安装目录包含符号链接（快捷方式）或文件权限异常，已停止安装。",
    "HERMES_HOST_NOT_FOUND": "在所选 Hermes 目录中未找到可用的 Python 运行环境。",
    "HERMES_HOST_OWNER_UNSUPPORTED": "当前 Hermes 目录属于其他系统用户，请切换到该用户后再运行安装。",
    "HERMES_HOST_INCOMPATIBLE": "当前 Hermes 版本或配置文件格式不兼容，已停止安装。",
    "HERMES_SOURCE_INVALID": "当前安装目录缺少 OAI Core 或 Hermes 连接插件源码，请重新运行 install.sh 修复。",
    "HERMES_PACKAGE_MANAGER_UNAVAILABLE": "未找到包管理工具 uv，且 Hermes 环境缺少 pip，无法安装插件。",
    "HERMES_BACKUP_FAILED": "无法备份原有的 Hermes 插件或配置文件，为安全起见已停止安装。",
    "HERMES_DEPENDENCY_FAILED": "向 Hermes 环境安装连接组件失败，原有的插件和配置未受影响。",
    "HERMES_INSTALL_TIMEOUT": "Hermes 连接插件安装超时，备份文件已保留在 runtime/hermes-adapter-backups，请检查后重试。",
    "HERMES_ENABLE_FAILED": "Hermes 插件启用或自检未通过，备份文件已保留在 runtime/hermes-adapter-backups。",
    "HERMES_RECOVERY_REQUIRED": "Hermes 插件安装失败且自动回滚未完成，原文件备份保存在 runtime/hermes-adapter-backups，请人工检查。",
    "HERMES_INSTALL_FAILED": "Hermes 连接插件安装失败，原有文件备份保存在 runtime/hermes-adapter-backups。",
}
# The probe reads manifests/config only. A distinct exit status carries the one
# nonsecret fact needed by the caller, avoiding capture of any host output.
_PROBE = '''import importlib.util, os, sys
from pathlib import Path
import yaml
from hermes_constants import get_hermes_home
assert sys.version_info >= (3, 11) and sys.prefix != sys.base_prefix
assert Path(os.path.abspath(sys.prefix)) == Path(os.path.abspath(sys.executable)).parent.parent
assert get_hermes_home() == Path(os.environ["HERMES_HOME"])
assert importlib.util.find_spec("hermes_cli.main") is not None
manifest = yaml.safe_load(Path("integrations/hermes-session-adapter/plugin.yaml").read_bytes())
assert isinstance(manifest, dict) and manifest.get("name") == "oai-hermes-session-adapter"
assert not any(manifest.get(k) for k in ("python_dependencies", "pip_dependencies", "capabilities"))
config = get_hermes_home() / "config.yaml"
data = yaml.safe_load(config.read_bytes()) if config.exists() else {}
if data is None: data = {}
assert isinstance(data, dict)
plugins = data.get("plugins", {})
assert isinstance(plugins, dict)
for key in ("enabled", "disabled"):
    assert isinstance(plugins.get(key, []), list)
    assert all(isinstance(v, str) and v for v in plugins.get(key, []))
sys.exit(10 if "open-android-intelligence-gateway" in plugins.get("enabled", []) else 0)
'''
_VERIFY = '''import importlib.util, os
from importlib.metadata import version
from pathlib import Path
import tomllib, yaml
for source, name in (("oai-core", "open-android-intelligence-core"),
                     ("integrations/hermes-session-adapter", "oai-hermes-session-adapter")):
    expected = tomllib.loads((Path(source) / "pyproject.toml").read_text())["project"]["version"]
    assert version(name) == expected
assert importlib.util.find_spec("oai_core") is not None
assert importlib.util.find_spec("oai_hermes_adapter") is not None
config = yaml.safe_load((Path(os.environ["HERMES_HOME"]) / "config.yaml").read_bytes())
plugins = config["plugins"]
assert "oai-hermes-session-adapter" in plugins.get("enabled", [])
assert "oai-hermes-session-adapter" not in plugins.get("disabled", [])
assert "open-android-intelligence-gateway" not in plugins.get("enabled", [])
'''


class HermesAdapterInstallError(ValueError):
    """Only stable codes and fixed safe messages cross the installer boundary."""

    def __init__(self, code: str):
        self.code = code if code in _MESSAGES else "HERMES_INSTALL_FAILED"
        super().__init__(_MESSAGES[self.code])


def _authorize(root: Path, store, handle: str) -> None:
    try:
        require_configuration_access(root, store, handle, "agents.manage")
    except Exception:
        raise HermesAdapterInstallError("HERMES_PERMISSION_DENIED") from None


def _safe(path: Path, *, directory: bool, missing: bool = False, owned: bool = True,
          owner_uid: int | None = None) -> Path:
    raw = os.fspath(path)
    if (not raw or raw.strip() != raw or ".." in Path(raw).parts or "\\" in raw
            or "~" in Path(raw).parts or any(ord(c) < 32 or ord(c) == 127 for c in raw)):
        raise HermesAdapterInstallError("HERMES_PATH_UNSAFE")
    path = Path(os.path.abspath(raw))
    for part in (*reversed(path.parents), path):
        try:
            info = part.lstat()
        except FileNotFoundError:
            if missing and part == path:
                return path
            raise
        if stat.S_ISLNK(info.st_mode) or part != path and not stat.S_ISDIR(info.st_mode):
            raise HermesAdapterInstallError("HERMES_PATH_UNSAFE")
    if owned:
        if os.geteuid() != 0:
            if info.st_uid != os.geteuid():
                raise HermesAdapterInstallError("HERMES_PATH_UNSAFE")
        elif owner_uid is not None:
            if info.st_uid not in {0, owner_uid}:
                raise HermesAdapterInstallError("HERMES_PATH_UNSAFE")
    if ((directory and not stat.S_ISDIR(info.st_mode))
            or (not directory and (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1))
            or info.st_mode & 0o022):
        raise HermesAdapterInstallError("HERMES_PATH_UNSAFE")
    return path


def _executable(path: Path, *, venv: bool = False, owner_uid: int | None = None) -> Path:
    # venv/uv intentionally link executables. Keep the venv path when invoking
    # Python, but execute the validated destination for ordinary tool links.
    _safe(path.parent, directory=True, owned=venv, owner_uid=owner_uid)
    info = path.lstat()
    resolved = path
    allowed = {0, os.geteuid()} if owner_uid is None else {0, os.geteuid(), owner_uid}
    if stat.S_ISLNK(info.st_mode):
        if info.st_uid not in allowed:
            raise HermesAdapterInstallError("HERMES_PATH_UNSAFE")
        resolved = _safe(path.resolve(strict=True), directory=False, owned=False, owner_uid=owner_uid)
        if resolved.stat().st_uid not in allowed:
            raise HermesAdapterInstallError("HERMES_PATH_UNSAFE")
    else:
        _safe(path, directory=False, owned=venv, owner_uid=owner_uid)
    if not os.access(resolved, os.X_OK):
        raise HermesAdapterInstallError("HERMES_PATH_UNSAFE")
    return path if venv else resolved


def _bytes(path: Path, owner_uid: int | None = None) -> bytes:
    _safe(path, directory=False, owner_uid=owner_uid)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_mode & 0o022:
            raise HermesAdapterInstallError("HERMES_PATH_UNSAFE")
        if os.geteuid() != 0:
            if info.st_uid != os.geteuid():
                raise HermesAdapterInstallError("HERMES_PATH_UNSAFE")
        elif owner_uid is not None:
            if info.st_uid not in {0, owner_uid}:
                raise HermesAdapterInstallError("HERMES_PATH_UNSAFE")
        return source.read()


def _tree(path: Path, owner_uid: int | None = None) -> None:
    _safe(path, directory=True, owner_uid=owner_uid)
    for directory, dirs, files in os.walk(path, followlinks=False):
        for name in dirs:
            _safe(Path(directory) / name, directory=True, owner_uid=owner_uid)
        dirs[:] = [d for d in dirs if d not in _EXCLUDED and not d.endswith(".egg-info")]
        for name in files:
            if not name.endswith((".pyc", ".pyo")):
                _safe(Path(directory) / name, directory=False, owner_uid=owner_uid)


def _environment(home: Path, python: Path, uv: Path | None) -> dict[str, str]:
    return {
        "HOME": str(home), "HERMES_HOME": str(home), "HERMES_SAFE_MODE": "1",
        "HERMES_ENABLE_PROJECT_PLUGINS": "0", "PYTHONDONTWRITEBYTECODE": "1",
        "PATH": os.pathsep.join([str(python.parent), *([str(uv.parent)] if uv else []), os.defpath]),
        "PIP_CONFIG_FILE": os.devnull, "PIP_NO_INPUT": "1", "PIP_DISABLE_PIP_VERSION_CHECK": "1",
        "PIP_KEYRING_PROVIDER": "disabled", "PIP_NO_CACHE_DIR": "1", "UV_NO_CONFIG": "1",
        "UV_NO_PROGRESS": "1", "UV_PYTHON_DOWNLOADS": "never", "GIT_TERMINAL_PROMPT": "0",
    }


def _resolve_or_create_hermes_python(home: Path, *, owner_uid: int | None = None,
                                     owner_gid: int | None = None) -> Path:
    candidates: list[Path] = [
        home / "hermes-agent" / "venv" / "bin" / "python",
        home / "hermes-agent" / ".venv" / "bin" / "python",
        home / "venv" / "bin" / "python",
        home / ".venv" / "bin" / "python",
        Path("/usr/local/lib/hermes-agent/venv/bin/python"),
        Path("/opt/hermes-agent/venv/bin/python"),
        home / "hermes-agent" / "env" / "bin" / "python",
        home / "env" / "bin" / "python",
    ]
    if "VIRTUAL_ENV" in os.environ:
        candidates.append(Path(os.environ["VIRTUAL_ENV"]) / "bin" / "python")

    pid_file = home / "gateway.pid"
    if pid_file.is_file():
        try:
            raw = pid_file.read_text(encoding="utf-8", errors="replace")
            info = json.loads(raw) if raw else {}
            pid = info.get("pid")
            if isinstance(pid, int) and pid > 1:
                proc_exe = Path(f"/proc/{pid}/exe")
                if proc_exe.is_file():
                    resolved_exe = proc_exe.resolve()
                    if resolved_exe.is_file() and os.access(resolved_exe, os.X_OK):
                        candidates.append(resolved_exe)
        except Exception:
            pass

    hermes_bins = [
        home / "bin" / "hermes",
        home / "hermes-agent" / "hermes",
        Path("/usr/local/bin/hermes"),
    ]
    which_hermes = shutil.which("hermes")
    if which_hermes:
        hermes_bins.append(Path(which_hermes))
    for h_bin in hermes_bins:
        if h_bin.is_file() and os.access(h_bin, os.X_OK):
            try:
                content = h_bin.read_text(encoding="utf-8", errors="replace")
                first_line = content.splitlines()[0] if content else ""
                if first_line.startswith("#!"):
                    shebang = shlex.split(first_line[2:].strip())
                    if shebang and "python" in shebang[0]:
                        candidates.append(Path(shebang[0]))
                for m in re.finditer(r'["\']?(/[\w\.-]+(?:/venv|/env)?/bin/python[\d\.]*)["\']?', content):
                    candidates.append(Path(m.group(1)))
            except Exception:
                pass

    venvs_dir = home / "venvs"
    if venvs_dir.is_dir() and not venvs_dir.is_symlink():
        try:
            for item in sorted(venvs_dir.iterdir()):
                if item.is_dir() and not item.is_symlink():
                    candidates.append(item / "bin" / "python")
        except OSError:
            pass

    def _has_hermes(cand: Path) -> bool:
        try:
            prefix = cand.parent.parent
            if (prefix.parent / "hermes_cli").is_dir():
                return True
            if (prefix / "hermes_cli").is_dir():
                return True
            for sp in prefix.glob("lib*/python*/site-packages"):
                if (sp / "hermes_cli").is_dir() or (sp / "hermes_cli.py").is_file():
                    return True
                if any(sp.glob("hermes*.dist-info")):
                    return True
            return False
        except Exception:
            return False

    valid_candidates: list[Path] = []
    seen: set[Path] = set()
    for cand in candidates:
        if cand in seen:
            continue
        seen.add(cand)
        try:
            if (cand.is_file() or cand.is_symlink()) and os.access(cand, os.X_OK):
                _executable(cand, venv=True, owner_uid=owner_uid)
                valid_candidates.append(cand)
        except Exception:
            continue

    for cand in valid_candidates:
        if _has_hermes(cand):
            return cand

    if valid_candidates:
        return valid_candidates[0]

    target_venv = (home / "hermes-agent" / "venv") if (home / "hermes-agent").is_dir() else (home / "venv")
    py_exe = sys.executable or shutil.which("python3")
    if py_exe:
        try:
            target_venv.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            create_res = subprocess.run(
                [py_exe, "-m", "venv", str(target_venv)],
                capture_output=True, text=True, timeout=60, check=False,
            )
            if create_res.returncode == 0:
                if os.geteuid() == 0 and owner_uid is not None and owner_uid != 0:
                    gid = owner_gid if owner_gid is not None else 0
                    for dirpath, dirs, files in os.walk(target_venv, followlinks=False):
                        for fname in files:
                            try:
                                os.chown(os.path.join(dirpath, fname), owner_uid, gid)
                            except OSError:
                                pass
                        for dname in dirs:
                            try:
                                os.chown(os.path.join(dirpath, dname), owner_uid, gid)
                            except OSError:
                                pass
                new_python = target_venv / "bin" / "python"
                if new_python.is_file() and os.access(new_python, os.X_OK):
                    return new_python
        except Exception:
            pass

    raise HermesAdapterInstallError("HERMES_HOST_NOT_FOUND")


def _run(root, store, handle, args, env, *, code, accepted=(0,), owner_uid: int | None = None,
         python: Path | None = None) -> int:
    # Recheck host paths, then authority immediately before every child.
    home = _safe(Path(env["HERMES_HOME"]), directory=True, owner_uid=owner_uid)
    _safe(home / "config.yaml", directory=False, missing=True, owner_uid=owner_uid)
    _safe(home / "plugins", directory=True, missing=True, owner_uid=owner_uid)
    check_python = python or (home / "hermes-agent/venv/bin/python")
    _executable(check_python, venv=True, owner_uid=owner_uid)
    _authorize(root, store, handle)
    try:
        child = subprocess.Popen(args, cwd=root, env=env, stdin=subprocess.DEVNULL,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        try:
            result = child.wait(timeout=_TIMEOUT)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            # Stop the entire installer/build process group, not just its Python parent.
            for sig in (signal.SIGTERM, signal.SIGKILL):
                try:
                    os.killpg(child.pid, sig)
                except ProcessLookupError:
                    pass
                try:
                    child.wait(timeout=5)
                    break
                except subprocess.TimeoutExpired:
                    continue
            else:
                raise HermesAdapterInstallError("HERMES_RECOVERY_REQUIRED") from None
            raise HermesAdapterInstallError("HERMES_INSTALL_TIMEOUT") from None
    except OSError:
        raise HermesAdapterInstallError(code) from None
    if result not in accepted:
        raise HermesAdapterInstallError(code)
    return result


def install_hermes_adapter(root: Path, store, handle: str, hermes_home: Path) -> None:
    """Install both local distributions, then switch official user-plugin IDs.

    Requires this Core installation's agents.manage authorization. Only a real,
    same-user ``hermes-agent/venv/bin/python`` is used. Its standard executable
    symlink is validated; source, plugin and configuration links are refused.
    uv is preferred (no pip required in the host); otherwise pip is probed first.
    Package-manager changes to site-packages cannot be rolled back here. Config
    backups are retained for manual recovery; active config is never restored.
    Only an unchanged original config permits plugin entry restoration.
    Revocation stops further writes. No services are started/restarted.
    """
    _authorize(root, store, handle)
    backup = None
    previous_moved = new_moved = False
    original = None
    try:
        if os.getuid() != os.geteuid():
            raise HermesAdapterInstallError("HERMES_HOST_OWNER_UNSUPPORTED")
        # Diagnose the existing cross-user detection case explicitly, before writes.
        home = _safe(hermes_home, directory=True, owned=False)
        owner_uid = home.stat().st_uid
        owner_gid = home.stat().st_gid
        if os.geteuid() != 0 and owner_uid != os.geteuid():
            raise HermesAdapterInstallError("HERMES_HOST_OWNER_UNSUPPORTED")
        root = _safe(root, directory=True, owner_uid=owner_uid)

        def _chown(path: Path) -> None:
            if os.geteuid() == 0 and owner_uid != 0:
                try:
                    os.chown(path, owner_uid, owner_gid)
                except OSError:
                    pass

        def _chown_tree(path: Path) -> None:
            if os.geteuid() == 0 and owner_uid != 0:
                for dirpath, dirs, files in os.walk(path, followlinks=False):
                    for fname in files:
                        fp = Path(dirpath) / fname
                        try:
                            if fp.lstat().st_uid == 0:
                                os.chown(fp, owner_uid, owner_gid)
                        except OSError:
                            pass
                    for dname in dirs:
                        dp = Path(dirpath) / dname
                        try:
                            if dp.lstat().st_uid == 0:
                                os.chown(dp, owner_uid, owner_gid)
                        except OSError:
                            pass

        python = _resolve_or_create_hermes_python(home, owner_uid=owner_uid, owner_gid=owner_gid)
        _executable(python, venv=True, owner_uid=owner_uid)
        pyvenv_cfg = python.parent.parent / "pyvenv.cfg"
        if not pyvenv_cfg.is_file() and (python.parent.parent).is_dir():
            try:
                pyvenv_cfg.write_text("include-system-site-packages = false\n", encoding="utf-8")
                _chown(pyvenv_cfg)
            except OSError:
                pass
        if pyvenv_cfg.is_file():
            _safe(pyvenv_cfg, directory=False, owner_uid=owner_uid)
        if not os.access(python, os.X_OK):
            raise HermesAdapterInstallError("HERMES_HOST_NOT_FOUND")
        for relative, name, package in (
            ("oai-core", "open-android-intelligence-core", "oai_core"),
            ("integrations/hermes-session-adapter", PLUGIN, "oai_hermes_adapter"),
        ):
            source = root / relative
            _tree(source, owner_uid=owner_uid)
            project = tomllib.loads(_bytes(source / "pyproject.toml", owner_uid=owner_uid).decode())["project"]
            if project.get("name") != name or not isinstance(project.get("version"), str):
                raise HermesAdapterInstallError("HERMES_SOURCE_INVALID")
            _safe(source / "src" / package / "__init__.py", directory=False, owner_uid=owner_uid)
        # Core's standard build reads these repository assets when present.
        for asset in (root / "gateway-contract", root / "integrations/skills"):
            if asset.exists() or asset.is_symlink():
                _tree(asset, owner_uid=owner_uid)
        manifest = _bytes(root / "integrations/hermes-session-adapter/plugin.yaml", owner_uid=owner_uid)
        plugins = _safe(home / "plugins", directory=True, missing=True, owner_uid=owner_uid)
        target = plugins / PLUGIN
        if plugins.exists() and (target.exists() or target.is_symlink()):
            _tree(target, owner_uid=owner_uid)
        config = _safe(home / "config.yaml", directory=False, missing=True, owner_uid=owner_uid)
        original = _bytes(config, owner_uid=owner_uid) if config.exists() else None
        uv_path = shutil.which("uv")
        uv = _executable(Path(uv_path)) if uv_path else None
        env = _environment(home, python, uv)
        python_args = [os.path.relpath(python, root), "-I", "-B"]
        legacy_enabled = _run(root, store, handle, [*python_args, "-c", _PROBE], env,
                              code="HERMES_HOST_INCOMPATIBLE", accepted=(0, 10), owner_uid=owner_uid,
                              python=python) == 10
        if uv is None:
            _run(root, store, handle, [*python_args, "-m", "pip", "--version"], env,
                 code="HERMES_PACKAGE_MANAGER_UNAVAILABLE", owner_uid=owner_uid, python=python)
        # Retain the exact original config before any installation side effect.
        backup = home / (".oai-hermes-install-" + secrets.token_hex(12))
        def authorize():
            _authorize(root, store, handle)
            _safe(home, directory=True, owner_uid=owner_uid)
            _safe(backup, directory=True, owner_uid=owner_uid)
        _authorize(root, store, handle)
        backup.mkdir(mode=0o700)
        _chown(backup)
        def write_new(path, content):
            authorize()
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(content)
                output.flush()
                os.fsync(output.fileno())
            _chown(path)
        if original is not None:
            write_new(backup / "config.yaml", original)
        stage = backup / "new-plugin"
        authorize()
        stage.mkdir(mode=0o700)
        _chown(stage)
        write_new(stage / "plugin.yaml", manifest)
        write_new(stage / "__init__.py", b"from oai_hermes_adapter import register\n")
        if uv is not None:
            install = [os.path.relpath(uv, root), "pip", "install", "--no-config", "--no-progress",
                       "--no-python-downloads", "--python", os.path.relpath(python, root)]
            offline_flags = ["--offline", "--no-build-isolation", "--no-deps"]
        else:
            install = [*python_args, "-m", "pip", "install", "--no-input", "--disable-pip-version-check"]
            offline_flags = ["--no-build-isolation", "--no-deps"]
        try:
            _run(root, store, handle, [*install, "./oai-core", "./integrations/hermes-session-adapter"],
                 env, code="HERMES_DEPENDENCY_FAILED", owner_uid=owner_uid, python=python)
        except HermesAdapterInstallError as install_err:
            if install_err.code != "HERMES_DEPENDENCY_FAILED":
                raise
            _run(root, store, handle, [*install, *offline_flags, "./oai-core", "./integrations/hermes-session-adapter"],
                 env, code="HERMES_DEPENDENCY_FAILED", owner_uid=owner_uid, python=python)
        _chown_tree(python.parent.parent)
        # Refuse to overwrite a configuration changed while the resolver ran.
        if (_bytes(config, owner_uid=owner_uid) if config.exists() or config.is_symlink() else None) != original:
            raise HermesAdapterInstallError("HERMES_ENABLE_FAILED")
        authorize()
        if not plugins.exists():
            plugins.mkdir(mode=0o700)
            _chown(plugins)
        _safe(plugins, directory=True, owner_uid=owner_uid)
        if target.exists() or target.is_symlink():
            _tree(target, owner_uid=owner_uid)
            authorize()
            target.rename(backup / "previous-plugin")
            previous_moved = True
        authorize()
        stage.rename(target)
        _chown_tree(target)
        new_moved = True
        cli = [*python_args, "-m", "hermes_cli.main", "plugins"]
        if legacy_enabled:
            _run(root, store, handle, [*cli, "disable", LEGACY_PLUGIN], env, code="HERMES_ENABLE_FAILED",
                 owner_uid=owner_uid, python=python)
        _run(root, store, handle, [*cli, "enable", PLUGIN], env, code="HERMES_ENABLE_FAILED",
             owner_uid=owner_uid, python=python)
        _safe(config, directory=False, owner_uid=owner_uid)
        _run(root, store, handle, [*python_args, "-c", _VERIFY], env, code="HERMES_ENABLE_FAILED",
             owner_uid=owner_uid, python=python)
        _chown(config)
        if (home / "backups").is_dir():
            _chown_tree(home / "backups")
        if (home / "open-android-intelligence-gateway").is_dir():
            _chown_tree(home / "open-android-intelligence-gateway")
        return None
    except Exception as error:
        if isinstance(error, HermesAdapterInstallError):
            failure = error
        elif isinstance(error, (FileNotFoundError, KeyError, tomllib.TOMLDecodeError, UnicodeError)):
            failure = HermesAdapterInstallError("HERMES_SOURCE_INVALID")
        else:
            failure = HermesAdapterInstallError("HERMES_INSTALL_FAILED")
        if backup is not None and (new_moved or previous_moved):
            try:
                # Revocation forbids further writes; leave all recovery material intact.
                authorize()
                current = _bytes(config, owner_uid=owner_uid) if config.exists() or config.is_symlink() else None
                if current != original:
                    # Even a successful CLI cannot prove who last wrote the
                    # configuration. Never rename/write active config during
                    # recovery; leave it and every recovery copy for review.
                    raise HermesAdapterInstallError("HERMES_RECOVERY_REQUIRED")
                if new_moved:
                    _safe(plugins, directory=True, owner_uid=owner_uid)
                    _tree(target, owner_uid=owner_uid)
                    authorize()
                    target.rename(backup / "failed-plugin")
                if previous_moved:
                    authorize()
                    (backup / "previous-plugin").rename(target)
            except Exception:
                if failure.code != "HERMES_PERMISSION_DENIED":
                    failure = HermesAdapterInstallError("HERMES_RECOVERY_REQUIRED")
        raise failure from None
