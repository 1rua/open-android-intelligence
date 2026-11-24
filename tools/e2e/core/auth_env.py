"""鉴权环境隔离管理器：支持 AVD 黄金快照模式与本地固定服务 + 仿真主机模式。"""

from __future__ import annotations

import json
import os
import queue
import shutil
import subprocess
import threading
import time
import urllib.parse
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .device_driver import (
    E2EDeviceError,
    resolve_repo_root,
    resolve_toolchains_root,
)

# 与 tools/remote-server/fixed-server.mjs 对齐的固定本地测试凭据
DEFAULT_SIM_HOST_NAME = "E2E Sim Host"
DEFAULT_SNAPSHOT_NAME = "oai_authenticated"


class E2EAuthEnvironmentError(RuntimeError):
    """隔离鉴权环境启动、快照加载或端口反向映射异常。"""


@dataclass(frozen=True)
class IsolatedAuthEndpoint:
    """隔离认证端点配置（快照直连模式或本地仿真模式）。"""

    mode: str  # "snapshot" | "local-sim"
    origin: str
    fixture_account: str
    fixture_token: str
    host_name: str = DEFAULT_SIM_HOST_NAME
    host_id: str = ""


def _resolve_node_binary(repo_root: Path) -> str:
    """优先使用仓库固定的 Node 24.18.0，其次回退到系统 PATH 中的 node。"""
    toolchains = resolve_toolchains_root(repo_root)
    fixed_node = toolchains / "node-v24.18.0-linux-x64" / "bin" / "node"
    if fixed_node.is_file() and os.access(fixed_node, os.X_OK):
        return str(fixed_node)
    path_node = shutil.which("node")
    if path_node:
        return path_node
    raise E2EAuthEnvironmentError("未找到可用的 Node.js 运行时（需 Node 24+）")


def _discover_fallback_node_modules(repo_root: Path) -> list[Path]:
    """按相对结构与运行时已安装工具链发现备选 `node_modules` 目录（零硬编码绝对路径）。"""
    candidates: list[Path] = [
        repo_root / "tools/remote-server/node_modules",
        repo_root / "tools/remote-interop/node_modules",
        repo_root / "integrations/oai-android-mcp/node_modules",
        repo_root / "node_modules",
    ]
    # 若当前位于 git worktree，同时检查主仓库根目录对应的 node_modules
    try:
        proc = subprocess.run(
            ["git", "-C", str(repo_root), "rev-parse", "--git-common-dir"],
            capture_output=True,
            text=True,
            timeout=3.0,
            check=True,
        )
        common_raw = proc.stdout.strip()
        if common_raw:
            common_path = Path(common_raw)
            if not common_path.is_absolute():
                common_path = (repo_root / common_path).resolve()
            main_root = common_path.resolve().parent
            candidates.extend(
                [
                    main_root / "tools/remote-server/node_modules",
                    main_root / "tools/remote-interop/node_modules",
                    main_root / "integrations/oai-android-mcp/node_modules",
                    main_root / "node_modules",
                ]
            )
    except (OSError, subprocess.SubprocessError):
        pass

    # 若子目录尚未跑 npm ci，探测全局 npm root 或已安装 CLI 伴随的依赖目录（不写入任何锁定目录）
    npm_bin = shutil.which("npm")
    if npm_bin:
        try:
            res = subprocess.run(
                [npm_bin, "root", "-g"],
                capture_output=True,
                text=True,
                timeout=3.0,
                check=False,
            )
            global_root = Path(res.stdout.strip())
            if res.returncode == 0 and global_root.is_dir():
                candidates.append(global_root)
                dsh_nm = global_root / "@deepseek-ai/dsh/node_modules"
                if dsh_nm.is_dir():
                    candidates.append(dsh_nm)
        except (OSError, subprocess.SubprocessError):
            pass

    dsh_bin = shutil.which("dsh")
    if dsh_bin:
        dsh_real = Path(dsh_bin).resolve()
        for parent in dsh_real.parents:
            nm = parent / "node_modules"
            if nm.is_dir():
                candidates.append(nm)
                break

    seen: set[Path] = set()
    existing: list[Path] = []
    for path in candidates:
        resolved = path.resolve()
        if resolved.is_dir() and resolved not in seen:
            seen.add(resolved)
            existing.append(resolved)
    return existing


def _build_esm_fallback_import_arg() -> str:
    """构造内联 Node ESM 解析钩子 `--import` 参数，仅在子目录缺少 node_modules 时辅助查找。"""
    hook_src = (
        'import { pathToFileURL } from "node:url";'
        'const dirs = (process.env.OAI_NODE_FALLBACK_MODULES || "").split(":").filter(Boolean);'
        "export async function resolve(specifier, context, nextResolve) {"
        "  try { return await nextResolve(specifier, context); } catch (err) {"
        '    if (!specifier.startsWith(".") && !specifier.startsWith("/") && '
        '!specifier.startsWith("node:") && !specifier.startsWith("data:")) {'
        "      for (const dir of dirs) {"
        '        try { return await nextResolve(specifier, { ...context, parentURL: pathToFileURL(dir + "/__probe__.mjs").href }); } catch {}'
        "      }"
        "    }"
        "    throw err;"
        "  }"
        "}"
    )
    reg_src = (
        'import { register } from "node:module";'
        f'register("data:text/javascript," + encodeURIComponent({json.dumps(hook_src)}));'
    )
    return "data:text/javascript," + urllib.parse.quote(reg_src, safe="")


def _read_first_json_line(
    proc: subprocess.Popen[str], *, timeout: float, label: str
) -> dict[str, Any]:
    """在超时窗口内异步读取子进程输出的首条合法 JSON 行，避免阻塞卡死。"""
    q: queue.Queue[tuple[str, str | None]] = queue.Queue()

    def _reader() -> None:
        assert proc.stdout is not None
        try:
            for line in proc.stdout:
                stripped = line.strip()
                if stripped.startswith("{") and stripped.endswith("}"):
                    q.put(("line", stripped))
                    return
            q.put(("eof", None))
        except Exception as exc:
            q.put(("err", str(exc)))

    thread = threading.Thread(target=_reader, daemon=True)
    thread.start()

    try:
        kind, payload = q.get(timeout=timeout)
    except queue.Empty as exc:
        stderr_out = ""
        if proc.stderr is not None and proc.poll() is not None:
            stderr_out = proc.stderr.read()
        raise E2EAuthEnvironmentError(
            f"{label} 在 {timeout:.1f}s 内未就绪{f': {stderr_out.strip()}' if stderr_out else ''}"
        ) from exc

    if kind != "line" or not payload:
        stderr_out = proc.stderr.read() if proc.stderr is not None else ""
        raise E2EAuthEnvironmentError(
            f"{label} 提前退出 (code={proc.poll()}): {stderr_out.strip()}"
        )
    try:
        return json.loads(payload)
    except json.JSONDecodeError as exc:
        raise E2EAuthEnvironmentError(
            f"{label} 输出非合法 JSON ({payload}): {exc}"
        ) from exc


class AuthEnvironmentManager:
    """管理 E2E 隔离鉴权生命周期（AVD 快照 / 本地仿真服务 / 自动判定）。"""

    def __init__(
        self,
        mode: str = "auto",
        driver: Any = None,
        repo_root: Path | None = None,
        snapshot_name: str = DEFAULT_SNAPSHOT_NAME,
        avd_name: str | None = None,
        host_name: str = DEFAULT_SIM_HOST_NAME,
        startup_timeout: float = 20.0,
    ) -> None:
        if mode not in {"auto", "snapshot", "local-sim"}:
            raise ValueError(f"不支持的鉴权模式: {mode!r}（仅允许 auto / snapshot / local-sim）")
        self.mode = mode
        self.driver = driver
        self.repo_root = (repo_root or resolve_repo_root()).resolve()
        self.snapshot_name = snapshot_name
        self.avd_name = avd_name
        self.host_name = host_name
        self.startup_timeout = float(startup_timeout)

        self.endpoint: IsolatedAuthEndpoint | None = None
        self._fixed_server_proc: subprocess.Popen[str] | None = None
        self._sim_host_proc: subprocess.Popen[str] | None = None
        self._reversed_port: int | None = None
        self._temp_dirs: list[Path] = []

    def __enter__(self) -> IsolatedAuthEndpoint:
        return self.start()

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.close()

    def _has_avd_snapshot(self) -> bool:
        """探测当前在线模拟器是否具备目标黄金快照。"""
        if self.driver is None or not hasattr(self.driver, "emu_command"):
            return False
        try:
            listing = self.driver.emu_command("avd", "snapshot", "list", timeout=8.0)
        except Exception:
            return False
        for line in listing.splitlines():
            tokens = line.strip().split()
            if self.snapshot_name in tokens:
                return True
        return False

    def _load_avd_snapshot(self) -> None:
        """请求运行中模拟器回滚加载黄金快照，确保只读免密起始状态。"""
        if self.driver is None or not hasattr(self.driver, "emu_command"):
            raise E2EAuthEnvironmentError("snapshot 模式要求传入有效的设备驱动实例")
        if not self._has_avd_snapshot():
            raise E2EAuthEnvironmentError(
                f"当前模拟器不存在黄金快照 {self.snapshot_name!r}，请先登录建快照或改用 local-sim 模式"
            )
        try:
            self.driver.emu_command(
                "avd", "snapshot", "load", self.snapshot_name, timeout=max(self.startup_timeout, 25.0)
            )
        except E2EDeviceError as exc:
            raise E2EAuthEnvironmentError(f"加载黄金快照 {self.snapshot_name!r} 失败: {exc}") from exc

    def _start_local_sim(self) -> IsolatedAuthEndpoint:
        """拉起 fixed-server.mjs 与 sim_remote_host.mjs，并按需建立 adb reverse 本地回环。"""
        node_bin = _resolve_node_binary(self.repo_root)
        fixed_server_script = self.repo_root / "tools/remote-server/fixed-server.mjs"
        sim_host_script = self.repo_root / "tools/e2e/core/sim_remote_host.mjs"
        if not fixed_server_script.is_file():
            raise E2EAuthEnvironmentError(f"缺少 fixed-server.mjs: {fixed_server_script}")
        if not sim_host_script.is_file():
            raise E2EAuthEnvironmentError(f"缺少 sim_remote_host.mjs: {sim_host_script}")

        fallback_dirs = _discover_fallback_node_modules(self.repo_root)
        env = os.environ.copy()
        if fallback_dirs:
            env["OAI_NODE_FALLBACK_MODULES"] = ":".join(str(p) for p in fallback_dirs)

        server_nm = self.repo_root / "tools/remote-server/node_modules"
        server_cmd = [node_bin, "--experimental-vm-modules"]
        if not server_nm.is_dir() and fallback_dirs:
            server_cmd.extend(["--import", _build_esm_fallback_import_arg()])
        server_cmd.append(str(fixed_server_script))

        self._fixed_server_proc = subprocess.Popen(
            server_cmd,
            cwd=str(self.repo_root),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env,
        )
        server_info = _read_first_json_line(
            self._fixed_server_proc,
            timeout=self.startup_timeout,
            label="fixed-server.mjs",
        )
        origin = str(server_info.get("origin") or "").rstrip("/")
        account = str(server_info.get("account") or "")
        password = str(server_info.get("password") or "")
        if not origin or not account or not password:
            raise E2EAuthEnvironmentError(f"fixed-server.mjs 返回信息不完整: {server_info}")

        parsed = urllib.parse.urlparse(origin)
        if parsed.port is None:
            raise E2EAuthEnvironmentError(f"无法从 origin 解析端口: {origin}")

        # 若连接了真实设备/模拟器，映射同端口反向隧道使手机 http://127.0.0.1:<port> 直连宿主机
        if self.driver is not None and hasattr(self.driver, "reverse_tcp"):
            try:
                devices = self.driver.list_connected_devices()
                if devices:
                    self.driver.reverse_tcp(parsed.port, parsed.port)
                    self._reversed_port = parsed.port
            except Exception as exc:
                self.close()
                raise E2EAuthEnvironmentError(f"建立 adb reverse 隧道失败: {exc}") from exc

        host_cmd = [
            node_bin,
            "--experimental-vm-modules",
            str(sim_host_script),
            "--origin",
            origin,
            "--account",
            account,
            "--password",
            password,
            "--host-name",
            self.host_name,
        ]
        self._sim_host_proc = subprocess.Popen(
            host_cmd,
            cwd=str(self.repo_root),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env,
        )
        host_info = _read_first_json_line(
            self._sim_host_proc,
            timeout=self.startup_timeout,
            label="sim_remote_host.mjs",
        )
        if host_info.get("status") != "READY":
            raise E2EAuthEnvironmentError(f"sim_remote_host.mjs 状态异常: {host_info}")

        endpoint = IsolatedAuthEndpoint(
            mode="local-sim",
            origin=origin,
            fixture_account=account,
            fixture_token=password,
            host_name=str(host_info.get("hostName") or self.host_name),
            host_id=str(host_info.get("hostId") or ""),
        )
        self.endpoint = endpoint
        return endpoint

    def start(self) -> IsolatedAuthEndpoint:
        """按既定模式启动并返回隔离认证端点信息。"""
        if self.endpoint is not None:
            return self.endpoint

        if self.mode == "snapshot":
            self._load_avd_snapshot()
            self.endpoint = IsolatedAuthEndpoint(
                mode="snapshot",
                origin="",
                fixture_account="",
                fixture_token="",
                host_name=self.host_name,
            )
            return self.endpoint

        if self.mode == "auto":
            if self._has_avd_snapshot():
                try:
                    self._load_avd_snapshot()
                    self.endpoint = IsolatedAuthEndpoint(
                        mode="snapshot",
                        origin="",
                        fixture_account="",
                        fixture_token="",
                        host_name=self.host_name,
                    )
                    return self.endpoint
                except E2EAuthEnvironmentError:
                    pass

        try:
            return self._start_local_sim()
        except Exception:
            self.close()
            raise

    @staticmethod
    def _shutdown_process(proc: subprocess.Popen[str] | None, timeout: float = 5.0) -> None:
        """通过关闭 stdin 通知子进程安全退出，超时后再温和终止。"""
        if proc is None:
            return
        try:
            if proc.stdin is not None and not proc.stdin.closed:
                proc.stdin.close()
        except OSError:
            pass
        if proc.poll() is None:
            try:
                proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                proc.terminate()
                try:
                    proc.wait(timeout=2.0)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=2.0)
        for stream in (proc.stdout, proc.stderr):
            if stream is not None:
                try:
                    stream.close()
                except OSError:
                    pass

    def _move_to_trash(self, path: Path) -> None:
        """将临时目录安全移至仓库 `.worktrees/trash/`，严禁使用永久删除命令。"""
        if not path.exists():
            return
        trash_root = self.repo_root / ".worktrees" / "trash"
        trash_root.mkdir(parents=True, exist_ok=True)
        dest = trash_root / f"e2e-{path.name}-{int(time.time() * 1000)}"
        shutil.move(str(path), str(dest))

    def close(self) -> None:
        """安全关闭全部子进程、移除端口映射并转移临时目录至 `.worktrees/trash/`。"""
        if self._reversed_port is not None and self.driver is not None:
            try:
                self.driver.remove_reverse_tcp(self._reversed_port)
            except Exception:
                pass
            self._reversed_port = None

        self._shutdown_process(self._sim_host_proc)
        self._sim_host_proc = None

        self._shutdown_process(self._fixed_server_proc)
        self._fixed_server_proc = None

        for tmp_dir in self._temp_dirs:
            try:
                self._move_to_trash(tmp_dir)
            except Exception:
                pass
        self._temp_dirs.clear()
        self.endpoint = None
