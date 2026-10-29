#!/usr/bin/env python3
"""
智能增量测试与多级验证运行器 (Smart Incremental Test & Multi-Level Verification Runner)

支持策略等级:
  --level fast: 快速自测阶段 (只跑变更相关模块和轻量单元测试，针对 Agent 快速反馈设计)
  --level pr:   PR 合并前验证 (基于影响图分析，包含协议、安全性、网关与受影响模块)
  --level full: 全量回归与发布验证 (跑所有测试集与完整构建检查)
  --since <ref>: 对比基线 (默认自动检测 origin/dev 或 HEAD~1)
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

# 契约、协议、核心安全性关键路径 (一旦变更必须升级验证范围)
CORE_SECURITY_CONTRACT_PATTERNS = [
    "gateway-contract/",
    "oai-core/src/oai_core/gateway/",
    "apps/android/gradle/mvp-forbidden-surfaces.gradle.kts",
    "apps/android/gradle.properties",
    "apps/android/build.gradle.kts",
    "apps/android/settings.gradle.kts",
    "package.json",
    "package-lock.json",
]

def get_git_diff_files(since_ref: str | None) -> list[str]:
    cmd = ["git", "-C", str(REPO_ROOT), "diff", "--name-only"]
    if since_ref:
        cmd.append(since_ref)
    else:
        # 尝试对比 origin/dev，如果不存在则对比 HEAD~1，最后对比工作区
        try:
            res = subprocess.run(["git", "-C", str(REPO_ROOT), "merge-base", "origin/dev", "HEAD"], capture_output=True, text=True, check=True)
            base = res.stdout.strip()
            cmd.append(f"{base}...HEAD")
        except Exception:
            try:
                subprocess.run(["git", "-C", str(REPO_ROOT), "rev-parse", "HEAD~1"], capture_output=True, check=True)
                cmd.append("HEAD~1")
            except Exception:
                pass
    
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, check=True)
        files = [line.strip() for line in res.stdout.splitlines() if line.strip()]
        # 同时检查未跟踪和当前暂存/未暂存的文件
        status_res = subprocess.run(["git", "-C", str(REPO_ROOT), "status", "--porcelain"], capture_output=True, text=True, check=True)
        for line in status_res.stdout.splitlines():
            if len(line) > 3:
                f = line[3:].strip()
                if " -> " in f:
                    f = f.split(" -> ")[1].strip()
                if f and f not in files:
                    files.append(f)
        return sorted(list(set(files)))
    except Exception as e:
        print(f"警告: 无法获取 git diff ({e})，将回退为全量范围。", file=sys.stderr)
        return []

def analyze_impact(changed_files: list[str]) -> dict[str, bool]:
    impact = {
        "is_full_required": False,
        "affects_security_or_contract": False,
        "affects_oai_core": False,
        "affects_hermes": False,
        "affects_dsh": False,
        "affects_plugins": False,
        "affects_android_control": False,
        "affects_android_conversation": False,
        "affects_android_gateway": False,
        "affects_android_app": False,
    }

    if not changed_files:
        impact["is_full_required"] = True
        return impact

    for f in changed_files:
        for pattern in CORE_SECURITY_CONTRACT_PATTERNS:
            if pattern in f:
                impact["affects_security_or_contract"] = True
                impact["is_full_required"] = True
                break

        if f.startswith("oai-core/"):
            impact["affects_oai_core"] = True
        if "hermes" in f:
            impact["affects_hermes"] = True
        if "dsh" in f:
            impact["affects_dsh"] = True
        if f.startswith("plugins/") or f.startswith("plugin-tooling/"):
            impact["affects_plugins"] = True
        
        if f.startswith("apps/android/"):
            if "screen" in f or "control" in f or "capability" in f or "device-primitives" in f:
                impact["affects_android_control"] = True
            if "conversation" in f:
                impact["affects_android_conversation"] = True
            if "gateway" in f:
                impact["affects_android_gateway"] = True
            if f.startswith("apps/android/app/"):
                impact["affects_android_app"] = True

    return impact

def run_command(cmd: list[str], cwd: Path | None = None, env: dict[str, str] | None = None) -> int:
    cmd_str = " ".join(cmd)
    print(f"\n[EXEC] {cmd_str} (cwd: {cwd or REPO_ROOT})")
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    res = subprocess.run(cmd, cwd=cwd or REPO_ROOT, env=merged_env)
    return res.returncode

def execute_strategy(level: str, impact: dict[str, bool]) -> int:
    print(f"=== 智能测试验证计划 [Level: {level.upper()}] ===")
    print(f"影响范围评估: {impact}")

    # 1. 基础门禁 (任何代码修改必跑)
    print("\n--- 阶段 1: 静态安全与契约门禁 ---")
    ret = run_command(["npm", "run", "mvp:lock:check"])
    if ret != 0:
        return ret

    # 2. Python Core 测试
    if impact["is_full_required"] or impact["affects_oai_core"] or level == "full":
        print("\n--- 阶段 2: OAI Core 单元测试 ---")
        ret = run_command([
            "python3", "-m", "pytest", "oai-core/tests", "tests/test_install_script.py", "-q",
            "--ignore=oai-core/tests/test_hermes.py",
            "--ignore=oai-core/tests/test_hermes_native.py",
            "--ignore=oai-core/tests/test_hermes_interactions.py",
            "--ignore=oai-core/tests/test_hermes_approval_prompts.py",
            "--ignore=oai-core/tests/test_interaction_pipeline.py",
            "--ignore=oai-core/tests/test_structured_tool_history.py",
            "--ignore=oai-core/tests/test_native_tool_events.py",
            "--ignore=oai-core/tests/test_native_event_sync.py",
        ])
        if ret != 0:
            return ret

    # 可选 Hermes 适配器必须单独验证：独立 Core 环境不安装它，而
    # affects_hermes 不能只被识别却从不触发测试。SDK 用例需显式提供固定 SDK。
    if impact["is_full_required"] or impact["affects_hermes"] or level == "full":
        print("\n--- Hermes 原生会话与工具回归 ---")
        ret = run_command([
            "python3", "-m", "pytest", "-q",
            "oai-core/tests/test_hermes.py",
            "oai-core/tests/test_hermes_native.py",
            "oai-core/tests/test_hermes_interactions.py",
            "oai-core/tests/test_hermes_approval_prompts.py",
            "oai-core/tests/test_hermes_dispatch_delivery.py",
            "oai-core/tests/test_interaction_pipeline.py",
            "oai-core/tests/test_structured_tool_history.py",
            "oai-core/tests/test_native_tool_events.py",
            "oai-core/tests/test_native_event_sync.py",
        ])
        if ret != 0:
            return ret

    # 3. DSH 适配器
    if impact["is_full_required"] or impact["affects_dsh"] or level == "full":
        print("\n--- 阶段 3: DSH Session Adapter 测试 ---")
        ret = run_command(["node", "--check", "integrations/dsh-session-adapter/index.js"])
        if ret != 0:
            return ret
        ret = run_command(["npx", "vitest", "run", "integrations/dsh-session-adapter/adapter.test.js"])
        if ret != 0:
            return ret

    # 4. Android 模块化精准测试
    android_dir = REPO_ROOT / "apps/android"
    android_tasks = []
    
    if impact["is_full_required"] or level == "full":
        android_tasks = [
            ":control-ports:testDebugUnitTest",
            ":capability-ports:testDebugUnitTest",
            ":screen-control:testDebugUnitTest",
            ":device-primitives:testDebugUnitTest",
            "noVpnSurfaceCheck",
            ":conversation-domain:testDebugUnitTest",
            ":conversation-ui:testDebugUnitTest",
            ":gateway-client:testDebugUnitTest",
            ":app:testFullDebugUnitTest",
        ]
    else:
        # 增量精简选择
        android_tasks.append("noVpnSurfaceCheck")
        if impact["affects_android_control"]:
            android_tasks.extend([
                ":control-ports:testDebugUnitTest",
                ":capability-ports:testDebugUnitTest",
                ":screen-control:testDebugUnitTest",
                ":device-primitives:testDebugUnitTest",
            ])
        if impact["affects_android_conversation"]:
            android_tasks.extend([
                ":conversation-domain:testDebugUnitTest",
                ":conversation-ui:testDebugUnitTest",
            ])
        if impact["affects_android_gateway"]:
            android_tasks.append(":gateway-client:testDebugUnitTest")
        if impact["affects_android_app"]:
            android_tasks.append(":app:testFullDebugUnitTest")

    if android_tasks and (android_dir / "gradlew").exists():
        print(f"\n--- 阶段 4: Android 增量测试任务 [{len(android_tasks)} 项] ---")
        gradle_cmd = ["./gradlew", "--max-workers=2"] + android_tasks
        ret = run_command(gradle_cmd, cwd=android_dir)
        if ret != 0:
            return ret

    print("\n✅ 所有阶段验证通过！")
    return 0

def main() -> int:
    parser = argparse.ArgumentParser(description="智能增量测试与多级验证运行器")
    parser.add_argument("--level", choices=["fast", "pr", "full"], default="pr", help="验证等级: fast(快速), pr(PR准入), full(全量)")
    parser.add_argument("--since", default=None, help="Git 对比基准 (默认自动检测)")
    args = parser.parse_args()

    changed_files = get_git_diff_files(args.since)
    print(f"检测到变更文件数量: {len(changed_files)}")
    for f in changed_files[:10]:
        print(f"  - {f}")
    if len(changed_files) > 10:
        print(f"  ... 及其余 {len(changed_files) - 10} 个文件")

    impact = analyze_impact(changed_files)
    return execute_strategy(args.level, impact)

if __name__ == "__main__":
    sys.exit(main())
