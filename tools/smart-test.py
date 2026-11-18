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
    """本地与 CI 使用同一影响图，未知改动扩大范围且不再启动旧服务。"""
    import importlib.util

    spec = importlib.util.spec_from_file_location("ci_impact", REPO_ROOT / "tools/ci/impact.py")
    classifier = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(classifier)
    plan = classifier.classify([("M", path) for path in changed_files])
    lanes = plan["lanes"]
    full = bool(plan["full_required"])
    return {
        "is_full_required": full,
        "affects_security_or_contract": full,
        "affects_configuration": bool(lanes["fast"]),
        "affects_remote": bool(lanes["remote"]),
        "affects_phone_mcp": bool(lanes["remote"]),
        "affects_plugins": any(path.startswith(("plugins/", "plugin-tooling/")) for path in changed_files) or full,
        "affects_android_control": bool(lanes["android-device"]),
        "affects_android_kernel": bool(lanes["android-platform"]),
        "affects_android_conversation": bool(lanes["android-conversation"]),
        "affects_android_gateway": bool(lanes["android-gateway"]),
        "affects_android_notifications": bool(lanes["android-notifications"]),
        "affects_android_app": bool(lanes["android-app"]),
        "affects_android_settings": "settings" in plan["app_sections"],
    }

def run_command(cmd: list[str], cwd: Path | None = None, env: dict[str, str] | None = None) -> int:
    cmd_str = " ".join(cmd)
    print(f"\n[EXEC] {cmd_str} (cwd: {cwd or REPO_ROOT})")
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    res = subprocess.run(cmd, cwd=cwd or REPO_ROOT, env=merged_env)
    return res.returncode


def load_android_planner():
    """Load the shared Android task planner used by CI."""
    import importlib.util

    path = REPO_ROOT / "tools/ci/android_tasks.py"
    spec = importlib.util.spec_from_file_location("ci_android_tasks", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# 旧的布尔影响标记到 CI lane 的映射。保留这层映射是为了不改变
# analyze_impact 的既有语义（以及据此写成的回归测试），同时让真正的
# 任务清单只存在一份。
IMPACT_TO_ANDROID_LANES = (
    ("affects_android_control", "android-device"),
    ("affects_android_kernel", "android-platform"),
    ("affects_android_notifications", "android-notifications"),
    ("affects_android_conversation", "android-conversation"),
    ("affects_android_gateway", "android-gateway"),
)


def android_app_sections(changed_files: list[str]) -> list[str] | None:
    """App-test sections the local PR run should cover.

    Returns None (meaning "every section") whenever the shared classifier says
    the change is full-scope or cannot be classified — the local runner must not
    be more optimistic than CI.
    """
    import importlib.util

    path = REPO_ROOT / "tools/ci/impact.py"
    spec = importlib.util.spec_from_file_location("ci_impact", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    result = module.classify([("M", changed) for changed in changed_files])
    if result["full_required"]:
        return None
    sections = result.get("app_sections") or set()
    return sorted(sections)


def android_test_invocations(
    level: str, impact: dict[str, bool], app_sections: list[str] | None = None
) -> list[list[str]]:
    """Gradle invocations to run, in order.

    Gradle binds `--tests` to the last Test task named before it, so a filtered
    task must be alone in its invocation. Module tasks (unfiltered) can share
    one invocation; the filtered app task cannot share with anything.

    The forbidden-surface gate is scheduled for every non-`full` level,
    independently of which lanes were selected: it is the repository's core
    safety check and must not disappear just because a change happened to be
    app-only or module-only.
    """
    planner = load_android_planner()
    invocations: list[list[str]] = []

    if level == "full":
        module_tasks = planner.full_module_tasks()
        invocations.append(["noVpnSurfaceCheck"] + module_tasks)
        invocations.append([":app:testFullDebugUnitTest", ":app:testPlayDebugUnitTest"])
        return invocations

    if level == "fast":
        # 本地快速反馈只跑与编译无关的越权面门禁。
        return [["noVpnSurfaceCheck"]]

    # `is_full_required` (build config, contracts, security) means "the affected
    # module set cannot be narrowed": run every module suite plus the app suite,
    # rather than falling through to a gate-only invocation.
    full_required = bool(impact.get("is_full_required"))

    module_tasks: list[str] = []
    if full_required:
        module_tasks.extend(planner.full_module_tasks())
    else:
        for flag, lane in IMPACT_TO_ANDROID_LANES:
            if impact.get(flag):
                module_tasks.extend(planner.module_lane_args(lane))
    module_tasks = list(dict.fromkeys(module_tasks))

    # Always keep the safety gate, even when no module or app task is selected.
    invocations.append(["noVpnSurfaceCheck"] + module_tasks)

    if full_required or impact.get("affects_android_app") or impact.get("affects_android_settings"):
        # Same section selection CI uses, so local PR admission and the PR gate
        # test the same classes. A full-required change widens to every section.
        invocations.append(planner.pr_args_for_sections(None if full_required else app_sections))

    return invocations


def execute_strategy(
    level: str, impact: dict[str, bool], app_sections: list[str] | None = None
) -> int:
    print(f"=== 智能测试验证计划 [Level: {level.upper()}] ===")
    print(f"影响范围评估: {impact}")

    # 1. 基础门禁 (任何代码修改必跑)
    print("\n--- 阶段 1: 静态安全与契约门禁 ---")
    ret = run_command(["npm", "run", "lock:check"])
    if ret != 0:
        return ret

    has_android_impact = impact.get("is_full_required") or any(
        value for key, value in impact.items() if key.startswith("affects_android_")
    )

    if has_android_impact or level == "full":
        # 1.1 源码级硬编码中文扫描 (fast / pr / full 均强制执行)
        print("\n--- 阶段 1.1: 源码级硬编码中文扫描 (check_hardcoded_chinese) ---")
        ret = run_command([sys.executable, "tools/check_hardcoded_chinese.py"])
        if ret != 0:
            return ret

        # 1.2 XML 资源完整性与命名冲突校验 (PR 准入与全量回归强制执行)
        if level in ("pr", "full"):
            print("\n--- 阶段 1.2: XML 资源完整性校验 (test_string_resources_integrity) ---")
            ret = run_command([sys.executable, "tools/test_string_resources_integrity.py"])
            if ret != 0:
                return ret

    # Plugin tooling tests cover plugin source, manifests, schemas, and packaging policy.
    if impact["affects_plugins"] or level == "full":
        print("\n--- 插件工具与清单回归 ---")
        ret = run_command(["npm", "test", "--prefix", "plugin-tooling"])
        if ret != 0:
            return ret

    if impact.get("affects_phone_mcp") or level == "full":
        print("\n--- 手机 MCP 官方 SDK 与桥接回归 ---")
        # 快速反馈不编译 Android；PR/Full 必须包含真实 Kotlin 门面互通。
        ret = run_command(["npm", "run", "test:phone-mcp:unit" if level == "fast" else "test:phone-mcp"])
        if ret != 0:
            return ret

    if impact.get("affects_configuration") or level == "full":
        print("\n--- CI 规划与门禁自身回归 ---")
        ret = run_command([sys.executable, "-m", "pytest", "-q", "-p", "no:cacheprovider",
            "tools/test_smart_test.py", "tools/test_ci_planning.py", "tools/test_android_jvm_reuse.py",
            "tools/test_android_app_performance.py", "tools/test_check_hardcoded_chinese.py",
            "tools/test_test_string_resources_integrity.py", "tools/test_verify_pr_source.py"])
        if ret != 0:
            return ret

    # 固定 ACP 投影观察独立于旧 Core，不作为后台委派放行门禁。
    if impact.get("affects_remote") or level == "full":
        print("\n--- 固定 ACP 投影关闭风险观察 ---")
        ret = run_command(["npm", "run", "test:remote"])
        if ret != 0:
            return ret
        ret = run_command(["npm", "test", "--prefix", "tools/remote-server"])
        if ret != 0:
            return ret

    # 4. Android 模块化精准测试
    #
    # 任务选择由 tools/ci/android_tasks.py 统一提供，CI 与本地取同一份分组，
    # 避免“本地跑一套、流水线跑另一套”造成的漏测。
    android_dir = REPO_ROOT / "apps/android"
    invocations = android_test_invocations(level, impact, app_sections)

    if (android_dir / "gradlew").exists():
        for android_tasks in invocations:
            print(f"\n--- 阶段 4: Android 增量测试 [{len(android_tasks)} 个参数] ---")
            # 让 Gradle 用满本机核数；--max-workers=2 是 CI 串行时代的遗留，
            # 在本地只会让独立模块的测试任务排队等待。
            ret = run_command(["./gradlew"] + android_tasks, cwd=android_dir)
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
    # PR 级与 CI 取同一份域选择：CI 收窄到什么范围，本地就复现什么范围。
    sections = android_app_sections(changed_files) if args.level == "pr" else None
    return execute_strategy(args.level, impact, sections)

if __name__ == "__main__":
    sys.exit(main())
