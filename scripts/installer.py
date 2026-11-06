#!/usr/bin/env python3
"""Locate an installation and start its administrative process.

This entry point deliberately uses only the Python standard library.  An
existing installation is never updated or repaired before the authenticated
runtime has examined it.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import tomllib



def _prepare_recovery_environment(source: Path, root: Path) -> int:
    """只在部署目录之外准备认证所需依赖，不改动原虚拟环境。"""
    metadata = tomllib.loads((source / "oai-core/pyproject.toml").read_text(encoding="utf-8"))
    dependencies = metadata.get("project", {}).get("dependencies", [])
    if not isinstance(dependencies, list) or not dependencies or not all(isinstance(value, str) and value for value in dependencies):
        raise ValueError("可信源码未声明恢复所需依赖")
    environment = Path(tempfile.mkdtemp(prefix=root.name + ".recovery.", dir=root.parent))
    python = environment / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    env = os.environ.copy()
    env.pop("OAI_INIT_ADMIN_PASSWORD", None)
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env["OAI_INSTALL_RECOVERY_ENV"] = "1"
    print("[INFO] 原运行环境缺少依赖，将在独立目录准备恢复环境；尚未修改原部署。", flush=True)
    subprocess.run([sys.executable, "-m", "venv", "--copies", str(environment)], check=True, env=env)
    subprocess.run([str(python), "-m", "pip", "install", *dependencies], check=True, env=env)
    os.execve(str(python), [str(python), "-I", str(source / "scripts/installer.py"),
                           "install", "--dir", str(root)], env)
    return 1


def main() -> int:
    sys.dont_write_bytecode = True
    parser = argparse.ArgumentParser(description="OAI Core 安装和管理入口")
    parser.add_argument("operation", choices=("install", "setup"))
    parser.add_argument("--dir", type=Path, default=None, help="安装目录")
    args = parser.parse_args()
    source = Path(__file__).resolve().parent.parent
    root = (args.dir or (Path(os.environ["OAI_DIR"]) if os.environ.get("OAI_DIR") else source)).expanduser().absolute()
    if root.is_symlink() or root.resolve() != root:
        parser.error("安装路径及其父目录不能是符号链接")
    if not (source / "oai-core" / "pyproject.toml").is_file():
        parser.error("请从受信任的 OAI Core 源码目录运行安装脚本")
    restore_source = source if not (root / "oai-core" / "pyproject.toml").is_file() else None
    if restore_source is not None and args.operation != "install":
        parser.error("已有部署缺少程序文件，请先使用安装入口恢复")
    runtime_source = source / "oai-core" / "src"
    if not (runtime_source / "oai_core" / "scripts_runtime.py").is_file():
        parser.error("可信源码缺少管理员安装入口，未修改部署")
    venv = root / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    evidence = (root / "core.json", root / "runtime", root / "start-core.sh")
    if restore_source is not None and not any(path.exists() or path.is_symlink() for path in evidence):
        parser.error("目标目录没有可恢复的 OAI 部署数据，未执行依赖安装")
    if not sys.stdin.isatty():
        try:
            tty = os.open("/dev/tty", os.O_RDONLY)
        except OSError:
            parser.error("请使用交互式终端进行管理员认证")
        os.dup2(tty, 0)
        os.close(tty)
    if (restore_source is not None or not venv.is_file()) and any(path.exists() or path.is_symlink() for path in evidence):
        if args.operation != "install":
            parser.error("已有部署缺少原虚拟环境，请先使用安装入口恢复")
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
            parser.error("未找到安装环境，请先执行安装")
        default_evidence = (root / "runtime" / "accounts", root / "runtime" / "security", root / "runtime" / "configuration-backups")
        if any(path.exists() or path.is_symlink() for path in default_evidence):
            parser.error("检测到旧账号或安全状态；未执行依赖安装")
        print("检测到全新安装，将创建虚拟环境并安装 Core 依赖。", flush=True)
        choice = input("确认继续？(y/n) [y]: ").strip().lower()
        if choice not in ("", "y", "yes"):
            parser.error("已取消安装")
        subprocess.run([sys.executable, "-m", "venv", "--copies", str(root / ".venv")], check=True)
        subprocess.run([str(venv), "-m", "pip", "install", "-e", "./oai-core"], cwd=root, check=True)
        subprocess.run([str(venv), "-m", "pip", "check"], cwd=root, check=True)
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
        print(f"[错误] 安装未完成（{getattr(exc, 'code', type(exc).__name__)}）；请保留恢复记录与现有数据。", file=sys.stderr)
        raise SystemExit(1) from None
