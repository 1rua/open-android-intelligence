#!/usr/bin/env python3
"""Fail closed unless the OpenClaw checkout matches the repository pin."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PIN_PATH = ROOT / "openclaw-plugin-pin.json"
PLUGIN_ENV = "OPENCLAW_PLUGIN_ROOT"
EXPECTED_REPOSITORY = "1rua/openclaw-gateway-plugin"


def fail(message: str) -> int:
    print(f"OpenClaw 插件 pin 校验失败：{message}", file=sys.stderr)
    return 1


def read_json(path: Path) -> dict[str, object] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return value if isinstance(value, dict) else None


def main() -> int:
    pin = read_json(PIN_PATH)
    if pin is None:
        return fail(f"无法读取 {PIN_PATH}")
    repository = str(pin.get("repository", "")).strip()
    version = str(pin.get("version", "")).strip()
    ref = str(pin.get("ref", "")).strip()
    revision = str(pin.get("revision", "")).strip()
    if repository != EXPECTED_REPOSITORY:
        return fail(f"repository 必须为 {EXPECTED_REPOSITORY}")
    if not version or not ref or not re.fullmatch(r"[0-9a-f]{40}", revision):
        return fail("version、固定 ref 或 40 位 revision 缺失/无效")

    configured_root = os.environ.get(PLUGIN_ENV, "").strip()
    plugin_root = Path(configured_root).expanduser() if configured_root else ROOT / ".openclaw-gateway-plugin"
    if not plugin_root.is_dir():
        return fail(f"未检出插件仓：{plugin_root}（设置 {PLUGIN_ENV} 可指定目录）")

    checkout = subprocess.run(
        ["git", "-C", str(plugin_root), "rev-parse", "HEAD"],
        check=False,
        capture_output=True,
        text=True,
    )
    if checkout.returncode != 0:
        return fail(f"无法读取插件仓 HEAD：{plugin_root}")
    actual_revision = checkout.stdout.strip()
    if actual_revision != revision:
        return fail(f"检出提交为 {actual_revision}，pin 要求 {revision}")

    package = read_json(plugin_root / "package.json")
    manifest = read_json(plugin_root / "openclaw.plugin.json")
    if package is None or str(package.get("version", "")) != version:
        return fail(f"package.json 版本与 pin {version} 不一致")
    if manifest is None or manifest.get("id") != "open-android-intelligence-gateway":
        return fail("OpenClaw 原生插件清单缺失或 ID 不一致")
    runtime = plugin_root / "runtime" / "adapter.js"
    if not runtime.is_file():
        return fail(f"固定安装入口缺失：{runtime}")

    print(f"OpenClaw 插件 pin 通过：{version} {revision}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
