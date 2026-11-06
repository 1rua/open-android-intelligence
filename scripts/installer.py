#!/usr/bin/env python3
"""Locate an installation and start its administrative process.

This entry point deliberately uses only the Python standard library.  An
existing installation is never updated or repaired before the authenticated
runtime has examined it.
"""
from __future__ import annotations

import argparse
import builtins
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import tomllib


def _confirm_install_venv(prompt: str) -> str:
    options = (("y", "1. 是，开始初始化环境 (Yes)"), ("n", "2. 否，取消并退出 (No)"))
    if type(builtins.input).__name__ != "builtin_function_or_method" or not (sys.stdin.isatty() and sys.stdout.isatty()):
        return input(prompt).strip().lower()
    try:
        import select
        import termios
        fd = sys.stdin.fileno()
        old_attrs = termios.tcgetattr(fd)
    except Exception:
        return input(prompt).strip().lower()
    use_color = not os.environ.get("NO_COLOR")
    idx = 0
    typed = ""
    def _fmt(active: int) -> str:
        lines = []
        for i, (_, label) in enumerate(options):
            if i == active:
                row = f"\033[36;1m  ❯ {label}\033[0m" if use_color else f"  ❯ {label}"
            else:
                row = f"    {label}"
            lines.append(row + "\n")
        lines.append(prompt)
        return "".join(lines)
    new_attrs = termios.tcgetattr(fd)
    new_attrs[3] &= ~(termios.ICANON | termios.ECHO)
    try:
        termios.tcsetattr(fd, termios.TCSANOW, new_attrs)
        sys.stdout.write(_fmt(idx))
        sys.stdout.flush()
        while True:
            ch = os.read(fd, 1)
            if not ch or ch == b"\x04":
                raise EOFError
            if ch == b"\x03":
                raise KeyboardInterrupt
            if ch in (b"\r", b"\n"):
                sys.stdout.write("\n")
                sys.stdout.flush()
                return typed.strip().lower() if typed.strip() else options[idx][0]
            if ch == b"\x1b":
                seq = b""
                if select.select([fd], [], [], 0.05)[0]:
                    b1 = os.read(fd, 1)
                    seq += b1
                    if b1 in (b"[", b"O") and select.select([fd], [], [], 0.05)[0]:
                        seq += os.read(fd, 1)
                if seq in (b"[A", b"OA", b"[D", b"OD", b"[B", b"OB", b"[C", b"OC"):
                    idx = 1 - idx
                    typed = ""
                    sys.stdout.write(f"\r\033[{len(options)}A")
                    for i, (_, label) in enumerate(options):
                        if i == idx:
                            row = f"\033[36;1m  ❯ {label}\033[0m" if use_color else f"  ❯ {label}"
                        else:
                            row = f"    {label}"
                        sys.stdout.write(f"\r\033[2K{row}\n")
                    sys.stdout.write(f"\r\033[2K{prompt}")
                    sys.stdout.flush()
                continue
            if ch in (b"\x7f", b"\x08"):
                if typed:
                    typed = typed[:-1]
                    sys.stdout.write("\b \b")
                    sys.stdout.flush()
                continue
            text = ch.decode("utf-8", errors="ignore")
            if text and text.isprintable():
                typed += text
                sys.stdout.write(text)
                sys.stdout.flush()
    finally:
        termios.tcsetattr(fd, termios.TCSANOW, old_attrs)


def _prepare_recovery_environment(source: Path, root: Path) -> int:
    """只在部署目录之外准备认证所需依赖，不改动原虚拟环境。"""
    metadata = tomllib.loads((source / "oai-core/pyproject.toml").read_text(encoding="utf-8"))
    dependencies = metadata.get("project", {}).get("dependencies", [])
    if not isinstance(dependencies, list) or not dependencies or not all(isinstance(value, str) and value for value in dependencies):
        raise ValueError("安装包缺少必要的运行依赖声明")
    environment = Path(tempfile.mkdtemp(prefix=root.name + ".recovery.", dir=root.parent))
    python = environment / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    env = os.environ.copy()
    env.pop("OAI_INIT_ADMIN_PASSWORD", None)
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env["OAI_INSTALL_RECOVERY_ENV"] = "1"
    print("▸ 检测到原运行环境不完整，正在临时目录准备恢复环境（原有数据不会受影响）...", flush=True)
    subprocess.run([sys.executable, "-m", "venv", "--copies", str(environment)], check=True, env=env)
    subprocess.run([str(python), "-m", "pip", "install", *dependencies], check=True, env=env)
    os.execve(str(python), [str(python), "-I", str(source / "scripts/installer.py"),
                           "install", "--dir", str(root)], env)
    return 1


def main() -> int:
    sys.dont_write_bytecode = True
    parser = argparse.ArgumentParser(description="OAI Core 安装与配置向导入口")
    parser.add_argument("operation", choices=("install", "setup"))
    parser.add_argument("--dir", type=Path, default=None, help="安装目录")
    args = parser.parse_args()
    source = Path(__file__).resolve().parent.parent
    root = (args.dir or (Path(os.environ["OAI_DIR"]) if os.environ.get("OAI_DIR") else source)).expanduser().absolute()
    if root.is_symlink() or root.resolve() != root:
        parser.error("安装路径及其上级目录不能是符号链接（快捷方式），请使用真实目录路径")
    if not (source / "oai-core" / "pyproject.toml").is_file():
        parser.error("未找到完整的 OAI Core 程序文件，请从项目源码目录运行安装脚本")
    restore_source = source if not (root / "oai-core" / "pyproject.toml").is_file() else None
    if restore_source is not None and args.operation != "install":
        parser.error("当前安装的程序文件不完整，请先运行 install.sh 修复安装")
    runtime_source = source / "oai-core" / "src"
    if not (runtime_source / "oai_core" / "scripts_runtime.py").is_file():
        parser.error("安装包缺少核心管理组件，未对现有环境做任何修改")
    venv = root / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    evidence = (root / "core.json", root / "runtime", root / "start-core.sh")
    if restore_source is not None and not any(path.exists() or path.is_symlink() for path in evidence):
        parser.error("目标目录中没有可恢复的 OAI 数据，未执行依赖安装")
    if not sys.stdin.isatty():
        try:
            tty = os.open("/dev/tty", os.O_RDONLY)
        except OSError:
            parser.error("请在可交互的终端窗口中运行，以便完成管理员身份验证")
        os.dup2(tty, 0)
        os.close(tty)
    if (restore_source is not None or not venv.is_file()) and any(path.exists() or path.is_symlink() for path in evidence):
        if args.operation != "install":
            parser.error("当前缺少 Python 运行环境，请先运行 install.sh 修复安装")
        # 系统缺依赖时只准备外部恢复环境；原部署的 venv、pip 和
        # 程序文件仍由认证后的运行时处理，不跳过管理员或二次验证。
        sys.path.insert(0, str(runtime_source))
        try:
            from oai_core.scripts_runtime import install
        except ModuleNotFoundError:
            if os.environ.get("OAI_INSTALL_RECOVERY_ENV") == "1":
                raise
            return _prepare_recovery_environment(source, root)
        return install(root, restore_source=restore_source) if restore_source is not None else install(root)
    if not venv.is_file():
        if args.operation != "install":
            parser.error("未找到已安装的运行环境，请先运行 install.sh 完成安装")
        default_evidence = (root / "runtime" / "accounts", root / "runtime" / "security", root / "runtime" / "configuration-backups")
        if any(path.exists() or path.is_symlink() for path in default_evidence):
            parser.error("检测到已有的账号或安全数据，为防数据丢失未直接重装，请先核对目录")
        print("\n╭─── 步骤 1/4 · 准备 Python 运行环境 ──────────────────────╮", flush=True)
        print("│ 检测到全新安装，即将创建独立虚拟环境并安装核心依赖组件。 │", flush=True)
        print("╰──────────────────────────────────────────────────────────╯", flush=True)
        choice = _confirm_install_venv("确认继续？(↑/↓ 切换，回车确认，或输入 y/n) [y]: ")
        if choice not in ("", "y", "yes", "1"):
            parser.error("已取消安装")
        print("▸ 正在创建 Python 虚拟环境 (.venv)...", flush=True)
        subprocess.run([sys.executable, "-m", "venv", "--copies", str(root / ".venv")], check=True)
        print("▸ 正在安装 OAI Core 核心组件（可能需要几十秒，请稍候）...", flush=True)
        subprocess.run([str(venv), "-m", "pip", "install", "-e", "./oai-core"], cwd=root, check=True)
        subprocess.run([str(venv), "-m", "pip", "check"], cwd=root, check=True)
        print("✓ 运行环境与依赖安装完成！\n", flush=True)
    env = os.environ.copy()
    # Old wizards used environment variables for passwords; never forward them.
    env.pop("OAI_INIT_ADMIN_PASSWORD", None)
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env["PYTHONPATH"] = str(runtime_source)
    os.execve(str(venv), [str(venv), "-m", "oai_core.scripts_runtime", args.operation, "--dir", str(root)], env)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"[错误] 安装未完成（原因：{getattr(exc, 'code', type(exc).__name__)}）。原有数据未受影响，请检查上方提示后重试。", file=sys.stderr)
        raise SystemExit(1) from None
