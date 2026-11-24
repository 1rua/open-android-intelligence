"""Android ADB 设备驱动封装模块（所有子进程调用强制携带超时与相对路径推导）。"""

from __future__ import annotations

import os
import shlex
import shutil
import subprocess
from pathlib import Path
from typing import Any

from .layout_pruner import (
    SemanticNode,
    prune_android_cli_json,
    prune_layout_xml,
)

# Android 应用默认包名与主启动入口（见 AndroidManifest.xml）
DEFAULT_APP_PACKAGE = "com.openandroidintelligence.mobile"
DEFAULT_APP_ACTIVITY = ".MainActivityThemed"
DEFAULT_APK_REL_PATH = Path(
    "apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk"
)


class E2EDeviceError(RuntimeError):
    """设备连接、ADB 命令执行或超时错误。"""


def resolve_repo_root() -> Path:
    """从当前脚本路径相对向上推导出当前工作区仓库根目录。"""
    current = Path(__file__).resolve()
    for parent in current.parents:
        if (parent / ".git").exists() and (parent / "apps/android").is_dir():
            return parent
    # 默认 `tools/e2e/core/device_driver.py` 的上三级即为仓库根
    return current.parents[3]


def resolve_toolchains_root(repo_root: Path | None = None) -> Path:
    """按环境变量 -> 当前工作区 -> 主仓库共有目录顺序解析 `.toolchains` 根目录。"""
    root = (repo_root or resolve_repo_root()).resolve()

    env_val = os.environ.get("OAI_TOOLCHAINS_ROOT", "").strip()
    if env_val:
        candidate = Path(env_val)
        if not candidate.is_absolute():
            candidate = (root / candidate).resolve()
        return candidate

    local_tc = root / ".toolchains"
    if local_tc.is_dir():
        return local_tc

    try:
        proc = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "--git-common-dir"],
            capture_output=True,
            text=True,
            timeout=5.0,
            check=True,
        )
        common_raw = proc.stdout.strip()
        if common_raw:
            common_path = Path(common_raw)
            if not common_path.is_absolute():
                common_path = (root / common_path).resolve()
            main_tc = common_path.resolve().parent / ".toolchains"
            if main_tc.is_dir():
                return main_tc
    except (OSError, subprocess.SubprocessError):
        pass

    return local_tc


def _encode_adb_input_text(text: str) -> str:
    """将文本转换为 `adb shell input text` 安全的命令行参数字符串。"""
    # `adb shell input text` 将 `%s` 解析为空格；其他特殊字符通过 shlex.quote 保护
    encoded = text.replace(" ", "%s")
    return shlex.quote(encoded)


class AdbAndroidDriver:
    """面向 Compose / Android 真机与模拟器的 ADB 底层驱动。"""

    def __init__(
        self,
        serial: str | None = None,
        repo_root: Path | None = None,
        command_timeout: float = 15.0,
    ) -> None:
        self.repo_root = (repo_root or resolve_repo_root()).resolve()
        self.toolchains_root = resolve_toolchains_root(self.repo_root)
        self.command_timeout = float(command_timeout)
        self.serial: str | None = serial
        self._adb_bin = self._locate_adb()
        self._android_cli = shutil.which("android")

    def _locate_adb(self) -> str:
        """优先定位项目共享工具链中的 adb，再回退到系统 PATH。"""
        sdk_adb = self.toolchains_root / "android-sdk" / "platform-tools" / "adb"
        if sdk_adb.is_file() and os.access(sdk_adb, os.X_OK):
            return str(sdk_adb)
        path_adb = shutil.which("adb")
        if path_adb:
            return path_adb
        return "adb"

    def _adb_base_cmd(self, with_serial: bool = True) -> list[str]:
        """构造带可选 `-s <serial>` 的基础 adb 命令前缀。"""
        cmd = [self._adb_bin]
        if with_serial and self.serial:
            cmd.extend(["-s", self.serial])
        return cmd

    def _run(
        self,
        args: list[str],
        *,
        timeout: float | None = None,
        with_serial: bool = True,
        binary: bool = False,
        check: bool = True,
    ) -> subprocess.CompletedProcess[Any]:
        """执行带严格超时的 adb 子进程命令。"""
        effective_timeout = self.command_timeout if timeout is None else float(timeout)
        cmd = self._adb_base_cmd(with_serial=with_serial) + args
        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=not binary,
                timeout=effective_timeout,
                cwd=str(self.repo_root),
            )
        except subprocess.TimeoutExpired as exc:
            raise E2EDeviceError(
                f"ADB 命令执行超时 ({effective_timeout:.1f}s): {' '.join(cmd)}"
            ) from exc
        except OSError as exc:
            raise E2EDeviceError(f"无法启动 ADB 命令 {' '.join(cmd)}: {exc}") from exc

        if check and proc.returncode != 0:
            stderr = (
                proc.stderr.decode("utf-8", errors="replace")
                if binary and isinstance(proc.stderr, (bytes, bytearray))
                else str(proc.stderr or "")
            )
            stdout = (
                proc.stdout.decode("utf-8", errors="replace")
                if binary and isinstance(proc.stdout, (bytes, bytearray))
                else str(proc.stdout or "")
            )
            detail = (stderr or stdout).strip()
            raise E2EDeviceError(
                f"ADB 命令失败 (code={proc.returncode}): {' '.join(cmd)} -> {detail}"
            )
        return proc

    def list_connected_devices(self) -> list[str]:
        """列出当前处于在线 `device` 状态的全部 ADB 设备序列号。"""
        proc = self._run(["devices"], with_serial=False, timeout=self.command_timeout)
        serials: list[str] = []
        for raw_line in proc.stdout.splitlines():
            line = raw_line.strip()
            if not line or line.startswith("List of devices"):
                continue
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                serials.append(parts[0])
        return serials

    def ensure_device_connected(self) -> str:
        """确认目标设备在线；未显式指定 serial 时自动选择唯一在线设备。"""
        devices = self.list_connected_devices()
        if not devices:
            raise E2EDeviceError(
                "未发现可用在线 Android 设备或模拟器（请先连接设备或启动模拟器）"
            )
        if self.serial:
            if self.serial not in devices:
                raise E2EDeviceError(
                    f"目标设备 {self.serial} 不在线，当前在线设备: {devices}"
                )
            return self.serial
        if len(devices) > 1:
            raise E2EDeviceError(
                f"检测到多台在线设备 {devices}，请通过 --device 显式指定序列号"
            )
        self.serial = devices[0]
        return self.serial

    def is_package_installed(self, package: str = DEFAULT_APP_PACKAGE) -> bool:
        """检查目标设备上是否已安装指定包名。"""
        proc = self._run(
            ["shell", "pm", "path", package], check=False, timeout=self.command_timeout
        )
        return proc.returncode == 0 and "package:" in (proc.stdout or "")

    def install_apk_if_needed(
        self,
        apk_path: Path | str | None = None,
        package: str = DEFAULT_APP_PACKAGE,
        force: bool = False,
    ) -> bool:
        """按需将调试 APK 安装到设备；返回 True 表示实际执行了安装。"""
        self.ensure_device_connected()
        target_apk = (
            Path(apk_path) if apk_path is not None else self.repo_root / DEFAULT_APK_REL_PATH
        )
        if not target_apk.is_absolute():
            target_apk = (self.repo_root / target_apk).resolve()

        already_installed = self.is_package_installed(package)
        if already_installed and not force:
            return False

        if not target_apk.is_file():
            raise E2EDeviceError(f"未找到待安装 APK 文件: {target_apk}")

        install_timeout = max(self.command_timeout, 90.0)
        self._run(
            ["install", "-r", "-d", str(target_apk)], timeout=install_timeout
        )
        return True

    def dump_semantics(self) -> list[SemanticNode]:
        """采集并精简当前屏幕的无障碍语义树。

        优先使用 `adb exec-out uiautomator dump /dev/tty`，稳定获取 Compose
        `testTagsAsResourceId` 映射的 `resource-id`；异常时先后回退到
        `/sdcard/window_dump.xml` 与 `android layout` JSON。
        """
        self.ensure_device_connected()
        errors: list[str] = []

        # 策略 1：直接将 XML 流式导出至 stdout（无设备端落盘开销）
        try:
            proc = self._run(
                ["exec-out", "uiautomator", "dump", "/dev/tty"],
                check=False,
                timeout=self.command_timeout,
            )
            if "<hierarchy" in (proc.stdout or ""):
                nodes = prune_layout_xml(proc.stdout)
                if nodes:
                    return nodes
                errors.append("uiautomator /dev/tty 解析结果为空")
            else:
                errors.append(
                    f"uiautomator /dev/tty 未返回 hierarchy (code={proc.returncode})"
                )
        except E2EDeviceError as exc:
            errors.append(str(exc))

        # 策略 2：回退到设备固定路径文件覆盖写入后再读取
        dump_remote = "/sdcard/window_dump.xml"
        try:
            self._run(
                ["shell", "uiautomator", "dump", dump_remote],
                timeout=self.command_timeout,
            )
            cat_proc = self._run(
                ["exec-out", "cat", dump_remote], timeout=self.command_timeout
            )
            if "<hierarchy" in (cat_proc.stdout or ""):
                nodes = prune_layout_xml(cat_proc.stdout)
                if nodes:
                    return nodes
                errors.append("window_dump.xml 解析结果为空")
            else:
                errors.append("window_dump.xml 未包含 hierarchy 根节点")
        except E2EDeviceError as exc:
            errors.append(str(exc))

        # 策略 3：回退到 `android layout` CLI JSON
        if self._android_cli:
            cmd = [self._android_cli, "layout", "--full"]
            if self.serial:
                cmd.extend(["--device", self.serial])
            try:
                cli_proc = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=self.command_timeout,
                    cwd=str(self.repo_root),
                )
                if cli_proc.returncode == 0 and cli_proc.stdout.strip():
                    nodes = prune_android_cli_json(cli_proc.stdout)
                    if nodes:
                        return nodes
                errors.append(
                    f"android layout 失败 (code={cli_proc.returncode}): {cli_proc.stderr.strip()}"
                )
            except (OSError, subprocess.SubprocessError) as exc:
                errors.append(f"android layout 异常: {exc}")

        raise E2EDeviceError(f"无法采集界面语义树: {'; '.join(errors)}")

    def capture_screenshot_bytes(self) -> bytes:
        """抓取当前设备屏幕 PNG 原始字节。"""
        self.ensure_device_connected()
        proc = self._run(
            ["exec-out", "screencap", "-p"],
            binary=True,
            timeout=self.command_timeout,
        )
        raw = bytes(proc.stdout or b"")
        if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
            raise E2EDeviceError(
                f"设备截图返回非法 PNG 数据 (len={len(raw)})"
            )
        return raw

    def start_app(
        self,
        package: str = DEFAULT_APP_PACKAGE,
        activity: str = DEFAULT_APP_ACTIVITY,
        cold: bool = True,
        clear_data: bool = False,
    ) -> None:
        """启动目标 Android 应用（支持冷启动及清理应用本地数据）。"""
        self.ensure_device_connected()
        if clear_data:
            self._run(["shell", "pm", "clear", package], timeout=self.command_timeout)
        elif cold:
            self._run(["shell", "am", "force-stop", package], timeout=self.command_timeout)

        if activity:
            component = (
                f"{package}/{activity}" if "/" not in activity else activity
            )
            flags = ["-W", "-n", component]
            if cold:
                flags.insert(0, "-S")
            self._run(
                ["shell", "am", "start", *flags],
                timeout=max(self.command_timeout, 25.0),
            )
        else:
            self._run(
                [
                    "shell",
                    "monkey",
                    "-p",
                    package,
                    "-c",
                    "android.intent.category.LAUNCHER",
                    "1",
                ],
                timeout=max(self.command_timeout, 25.0),
            )

    def tap_point(self, x: int, y: int) -> None:
        """点击屏幕指定坐标像素点。"""
        self.ensure_device_connected()
        self._run(["shell", "input", "tap", str(int(x)), str(int(y))])

    def swipe_points(
        self,
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        duration_ms: int = 300,
    ) -> None:
        """从 (x1, y1) 滑动到 (x2, y2)。"""
        self.ensure_device_connected()
        self._run(
            [
                "shell",
                "input",
                "swipe",
                str(int(x1)),
                str(int(y1)),
                str(int(x2)),
                str(int(y2)),
                str(max(50, int(duration_ms))),
            ]
        )

    def clear_text(self, count: int = 64) -> None:
        """清空当前聚焦输入框中的文本内容。"""
        self.ensure_device_connected()
        # 先尝试 Android 12+ 原生 Ctrl+A 全选 + 退格，再移动光标到末尾补退格以兜底旧输入框
        self._run(["shell", "input", "keycombination", "113", "29"], check=False)
        self._run(["shell", "input", "keyevent", "67"], check=False)
        steps = max(0, min(int(count), 120))
        if steps > 0:
            self._run(["shell", "input", "keyevent", "123"], check=False)
            del_codes = ["67"] * steps
            self._run(["shell", "input", "keyevent", *del_codes], check=False)

    def input_text(self, text: str, *, clear_first: bool = False) -> None:
        """向当前聚焦输入框键入文本，自动处理空格 `%s` 与 Shell 安全转义。"""
        self.ensure_device_connected()
        if clear_first:
            self.clear_text()
        if not text:
            return
        safe_arg = _encode_adb_input_text(text)
        self._run(["shell", f"input text {safe_arg}"])

    def press_back(self) -> None:
        """发送系统返回键 (`KEYCODE_BACK`)。"""
        self.ensure_device_connected()
        self._run(["shell", "input", "keyevent", "4"])

    def press_enter(self) -> None:
        """发送回车确认键 (`KEYCODE_ENTER`)。"""
        self.ensure_device_connected()
        self._run(["shell", "input", "keyevent", "66"])

    def hide_keyboard(self) -> bool:
        """若当前软键盘处于弹出状态，发送返回键收起软键盘；未弹出则无操作。"""
        self.ensure_device_connected()
        proc = self._run(
            ["shell", "dumpsys", "input_method"], check=False, timeout=self.command_timeout
        )
        shown = False
        for line in (proc.stdout or "").splitlines():
            if "mInputShown=" in line:
                shown = "mInputShown=true" in line
                break
        if shown:
            self.press_back()
            return True
        return False

    def reverse_tcp(self, remote_port: int, local_port: int) -> None:
        """建立设备本地端口到宿主机端口的 ADB 反向隧道。"""
        self.ensure_device_connected()
        self._run(
            ["reverse", f"tcp:{int(remote_port)}", f"tcp:{int(local_port)}"]
        )

    def remove_reverse_tcp(self, remote_port: int | None = None) -> None:
        """移除指定或全部 ADB 反向端口转发规则。"""
        if not self.serial and not self.list_connected_devices():
            return
        if remote_port is None:
            self._run(["reverse", "--remove-all"], check=False)
        else:
            self._run(
                ["reverse", "--remove", f"tcp:{int(remote_port)}"], check=False
            )

    def emu_command(self, *args: str, timeout: float | None = None) -> str:
        """向模拟器控制台发送 `adb emu ...` 指令（如快照列举或加载）。"""
        self.ensure_device_connected()
        proc = self._run(
            ["emu", *args],
            check=False,
            timeout=timeout or self.command_timeout,
        )
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout or "").strip()
            raise E2EDeviceError(
                f"模拟器指令失败: adb emu {' '.join(args)} -> {detail}"
            )
        return (proc.stdout or "").strip()

    def recent_crash_log(
        self, package: str | None = None, lines: int = 80
    ) -> str:
        """拉取设备近期崩溃日志，优先读取 crash 缓冲区。"""
        try:
            self.ensure_device_connected()
            proc = self._run(
                ["logcat", "-d", "-b", "crash", "-t", str(max(1, int(lines)))],
                check=False,
                timeout=min(self.command_timeout, 8.0),
            )
            text = (proc.stdout or "").strip()
            if text and "--------- beginning of crash" != text:
                if package and package in text:
                    return text
                if not package:
                    return text
            main_proc = self._run(
                [
                    "logcat",
                    "-d",
                    "-t",
                    str(max(1, int(lines) * 2)),
                    "AndroidRuntime:E",
                    "*:S",
                ],
                check=False,
                timeout=min(self.command_timeout, 8.0),
            )
            main_text = (main_proc.stdout or "").strip()
            if package and main_text:
                filtered = [
                    line for line in main_text.splitlines() if package in line or "FATAL" in line or "Exception" in line
                ]
                return "\n".join(filtered)
            return main_text or text
        except E2EDeviceError:
            return ""
