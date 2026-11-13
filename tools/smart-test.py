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
import site
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

# 旧 DSH 适配器及其真实安装/配置支持面，不以文件名包含 dsh 推断依赖。
DSH_ADAPTER_SUPPORT_PATHS = {
    "integrations/package.json", "integrations/package-lock.json", "integrations/tsconfig.json",
    "oai-core/src/oai_core/adapters/dsh.py", "oai-core/src/oai_core/dsh_installation.py",
    "oai-core/tests/test_dsh.py", "oai-core/tests/test_dsh_setup.py",
    "oai-core/tests/test_dsh_installation.py", "oai-core/tests/test_dsh_builtin_contract.py",
}


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
        "affects_configuration": False,
        "affects_measurements": False,
        "affects_observer": False,
        "affects_android_kernel": False,
        "affects_hermes": False,
        "affects_dsh": False,
        "affects_remote": False,
        "affects_plugins": False,
        "affects_android_control": False,
        "affects_android_conversation": False,
        "affects_android_gateway": False,
        "affects_android_app": False,
        "affects_android_settings": False,
        "affects_android_notifications": False,
    }

    if not changed_files:
        impact["is_full_required"] = True
        return impact

    for f in changed_files:
        # 文档中的宿主名称不代表修改了可选适配器实现。
        if f.startswith("docs/") or f.endswith(".md"):
            continue
        if f.startswith("tools/remote/"):
            impact["affects_remote"] = True
            continue
        if f in {"tools/observe-communication.py", "tools/test_observe_communication.py",
                 "tools/audit-communication.py", "tools/test_audit_communication.py"}:
            impact["affects_observer"] = True
        if f in {"tools/measure-communication.py", "tools/test_measure_communication.py"}:
            impact["affects_measurements"] = True
        for pattern in CORE_SECURITY_CONTRACT_PATTERNS:
            if pattern in f:
                impact["affects_security_or_contract"] = True
                impact["is_full_required"] = True
                break

        if f in {".github/workflows/oai-core.yml", "scripts/core_setup.py", "scripts/installer.py", "scripts/setup.sh", "scripts/install.sh", "scripts/uninstall.sh",
                 "tests/test_install_script.py", "tests/test_uninstall_script.py", "tests/test_core_setup_transaction.py",
                 "tools/smart-test.py", "tools/test_smart_test.py"}:
            impact["affects_configuration"] = True
        if f.startswith("apps/android/platform-kernel/"):
            impact["affects_android_kernel"] = True
        if f.startswith("oai-core/"):
            impact["affects_oai_core"] = True
        if "hermes" in f or f in {
            "oai-core/tests/test_interaction_pipeline.py",
            "oai-core/tests/test_native_event_sync.py",
            "oai-core/tests/test_native_tool_events.py",
            "oai-core/tests/test_structured_tool_history.py",
            "oai-core/tests/test_communication_process_recovery.py",
        }:
            impact["affects_hermes"] = True
        if f.startswith("integrations/dsh-session-adapter/") or f in DSH_ADAPTER_SUPPORT_PATHS:
            impact["affects_dsh"] = True
        if f.startswith("plugins/") or f.startswith("plugin-tooling/"):
            impact["affects_plugins"] = True
        
        if f.startswith("apps/android/"):
            if "/settings/" in f or Path(f).name.startswith(("Settings", "PlatformSettings")):
                impact["affects_android_settings"] = True
                impact["affects_android_app"] = True
            elif f.endswith("Screen.kt"):
                # 仅修改 Compose 页面（如 AppearanceSettingsScreen 等纯 UI），不标记为全局 settings/app 联动重试
                impact["affects_android_app"] = True
            if any(f.startswith(f"apps/android/{module}/") for module in ("notification-control", "notification-host", "notification-collector")):
                impact["affects_android_notifications"] = True
                impact["affects_android_app"] = True
            if f.startswith("apps/android/assistant-holder/"):
                impact["affects_android_app"] = True
            if f.startswith("apps/android/ui-resources/"):
                impact["affects_android_app"] = True
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
    ret = run_command(["npm", "run", "mvp:lock:check"])
    if ret != 0:
        return ret

    has_android_impact = any(k.startswith("affects_android_") for k, v in impact.items() if v) or (
        impact.get("is_full_required") and not any(impact.get(k) for k in ("affects_plugins", "affects_hermes", "affects_configuration"))
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

    # 2. Python Core 测试
    if (impact["is_full_required"] and level != "fast") or impact["affects_oai_core"] or level == "full":
        print("\n--- 阶段 2: OAI Core 单元测试 ---")
        pythonpath = f"{REPO_ROOT / 'oai-core/src'}:{REPO_ROOT / 'integrations/hermes-session-adapter/src'}:{site.getusersitepackages()}:{os.environ.get('PYTHONPATH', '')}"
        extra_env = {"PYTHONPATH": pythonpath}
        if "PYTHONUSERBASE" in os.environ:
            extra_env["PYTHONUSERBASE"] = os.environ["PYTHONUSERBASE"]
        ret = run_command([
            sys.executable, "-m", "pytest", "oai-core/tests", "tests/test_install_script.py",
            "tests/test_uninstall_script.py", "tests/test_core_setup_transaction.py", "tests/test_scripts_entrypoints.py", "tests/test_scripts_runtime.py", "tests/test_scripts_runtime_security.py", "tests/test_setup_service_entrypoint.py", "tools/test_smart_test.py", "tools/test_observe_communication.py", "tools/test_audit_communication.py", "-q",
            "--ignore=oai-core/tests/test_hermes.py",
            "--ignore=oai-core/tests/test_hermes_native.py",
            "--ignore=oai-core/tests/test_hermes_bridge_heartbeat.py",
            "--ignore=oai-core/tests/test_hermes_session_key_recovery.py",
            "--ignore=oai-core/tests/test_hermes_plugin_zero_config.py",
            "--ignore=oai-core/tests/test_hermes_native_origin.py",
            "--ignore=oai-core/tests/test_hermes_interactions.py",
            "--ignore=oai-core/tests/test_hermes_approval_prompts.py",
            "--ignore=oai-core/tests/test_interaction_pipeline.py",
            "--ignore=oai-core/tests/test_structured_tool_history.py",
            "--ignore=oai-core/tests/test_native_tool_events.py",
            "--ignore=oai-core/tests/test_native_event_sync.py",
            "--ignore=oai-core/tests/test_hermes_communication_reliability.py",
            "--ignore=oai-core/tests/test_communication_process_recovery.py",
        ], env=extra_env)
        if ret != 0:
            return ret

    elif impact["affects_configuration"]:
        print("\n--- Core 配置保存、安装卸载与测试入口回归 ---")
        pythonpath = f"{REPO_ROOT / 'oai-core/src'}:{REPO_ROOT / 'integrations/hermes-session-adapter/src'}:{site.getusersitepackages()}:{os.environ.get('PYTHONPATH', '')}"
        extra_env = {"PYTHONPATH": pythonpath}
        if "PYTHONUSERBASE" in os.environ:
            extra_env["PYTHONUSERBASE"] = os.environ["PYTHONUSERBASE"]
        ret = run_command([sys.executable, "-m", "pytest", "tests/test_install_script.py", "tests/test_uninstall_script.py",
                           "tests/test_core_setup_transaction.py", "oai-core/tests/test_core_config.py",
                           "tools/test_smart_test.py", "-q"], env=extra_env)
        if ret != 0:
            return ret

    # 可选 Hermes 适配器必须单独验证：独立 Core 环境不安装它，而
    # affects_hermes 不能只被识别却从不触发测试。SDK 用例需显式提供固定 SDK。
    if (impact["is_full_required"] and level != "fast") or impact["affects_hermes"] or level == "full":
        print("\n--- Hermes 原生会话与工具回归 ---")
        hermes_env = os.environ.copy()
        sdk_candidates = [
            os.environ.get("HERMES_SDK_ROOT"),
            str(REPO_ROOT / ".toolchains/hermes-sdk-75b083e"),
            str(REPO_ROOT.parents[1] / ".toolchains/hermes-sdk-75b083e"),
        ]
        sdk_root = next((c for c in sdk_candidates if c and Path(c).is_dir()), None)
        base_paths = [str(REPO_ROOT / 'oai-core/src'), str(REPO_ROOT / 'integrations/hermes-session-adapter/src')]
        if sdk_root:
            hermes_env["HERMES_SDK_ROOT"] = sdk_root
            base_paths.insert(0, sdk_root)
        if "PYTHONPATH" in hermes_env:
            base_paths.append(hermes_env["PYTHONPATH"])
        hermes_env["PYTHONPATH"] = ":".join(base_paths)
        hermes_cmd = [
            sys.executable, "-m", "pytest", "-q",
            "oai-core/tests/test_hermes.py",
            "oai-core/tests/test_hermes_native.py",
            "oai-core/tests/test_hermes_native_origin.py",
            "oai-core/tests/test_hermes_zero_config_native.py",
            "oai-core/tests/test_agent_registration.py",
            "oai-core/tests/test_hermes_interactions.py",
            "oai-core/tests/test_hermes_approval_prompts.py",
            "oai-core/tests/test_hermes_session_key_recovery.py",
            "oai-core/tests/test_hermes_plugin_zero_config.py",
            "oai-core/tests/test_hermes_dispatch_delivery.py",
            "oai-core/tests/test_hermes_communication_reliability.py",
            "oai-core/tests/test_hermes_bridge_heartbeat.py",
            "oai-core/tests/test_communication_process_recovery.py",
            "oai-core/tests/test_interaction_pipeline.py",
        ]
        if sdk_root:
            hermes_cmd.extend([
                "oai-core/tests/test_structured_tool_history.py",
                "oai-core/tests/test_native_tool_events.py",
                "oai-core/tests/test_native_event_sync.py",
            ])
        ret = run_command(hermes_cmd, env=hermes_env)
        if ret != 0:
            return ret

    # 固定 Remote 的公开接口探针独立于旧 Core 适配器，用同一命令接入本地与 CI。
    if impact.get("affects_remote") or level == "full":
        print("\n--- 固定 Remote 公开目标绑定探针 ---")
        ret = run_command([
            "node", "--experimental-vm-modules", "--test",
            "tools/remote/target-binding.test.mjs", "tools/remote/projection-scope.test.mjs",
        ])
        if ret != 0:
            return ret

    # 3. DSH 适配器
    if (impact["is_full_required"] and level != "fast") or impact["affects_dsh"] or level == "full":
        print("\n--- 阶段 3: DSH Session Adapter 测试 ---")
        ret = run_command(["node", "--check", "integrations/dsh-session-adapter/index.js"])
        if ret != 0:
            return ret
        ret = run_command(["npx", "--yes", "vitest", "run", "--config", "integrations/dsh-session-adapter/vitest.config.js"])
        if ret != 0:
            return ret

    if (impact["is_full_required"] and level != "fast") or impact["affects_measurements"] or level == "full":
        print("\n--- 通信测量器统计、来源与隐私回归 ---")
        ret = run_command([sys.executable, "-m", "pytest", "-q", "tools/test_measure_communication.py"])
        if ret != 0:
            return ret

    if impact["affects_observer"] and not (impact["is_full_required"] or impact["affects_oai_core"] or level == "full"):
        print("\n--- 通信只读观察器回归 ---")
        ret = run_command([sys.executable, "-m", "pytest", "-q", "tools/test_observe_communication.py", "tools/test_audit_communication.py"])
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
