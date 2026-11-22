#!/usr/bin/env python3
"""
CI change-impact analysis (GitHub Actions job gating).

This is the single source of truth for "which CI lanes must run for this diff".
`tools/smart-test.py` uses the same module for its local fast/pr/full strategy,
so a change classified here cannot be tested differently on a workstation and
on CI.

Usage (CI):
    python3 tools/ci/impact.py --base origin/dev --head HEAD --github-output

Outputs `runs_*` booleans plus `runs_android_preflight`, `runs_android_app_compile`,
`app_regressions_allowed`, `full_required`, `unknown_diff`, and `any_lane`. Unknown
or security-sensitive paths expand the matrix instead of narrowing it: a diff we
cannot classify must never silently skip a lane.

Tiered App/Play regression policy:
  * To keep daily feature/fix PR feedback within 5 minutes, heavy Robolectric
    `android-app` and `android-play` suites only run when `should_run_app_regressions`
    allows them (pushes to `main`/`dev`/`release*` or tags, release PRs targeting
    `main`/`release*` or from `release*`, explicit full-CI PR labels, or
    `workflow_dispatch`).
  * Even when `full_required=True` or `unknown=True` on a daily PR, all fast,
    remote, safety-preflight, and Android module lanes PLUS lightweight `:app`
    Kotlin production/test compilation (`runs_android_app_compile=true`) still run;
    only the heavy Robolectric App/Play suites are deferred until merge into `dev`
    (pre-canary verification) / `main` or explicit opt-in via PR label.

Design rules:
  * Deletions, renames and additions are classified by their old *and* new path
    (`git diff --name-status -M`), so deleting a security test still triggers the
    lane that owns it.
  * A path that no rule recognises sets `unknown=true`; callers then run the
    full eligible matrix. Conservative by construction.
  * Empty diff (shallow clone, missing base) also sets `unknown=true`.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# Contract, protocol, auth, build-config and safety-gate paths.
# A match here widens the matrix to everything: these changes can invalidate any
# lane's assumptions, so narrowing by "which file changed" would be unsound.
SECURITY_OR_CONTRACT_PREFIXES = (
    "apps/android/control-ports/src/main/kotlin/com/openandroidintelligence/control/remote/",
    "apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/remote/security/",
    "apps/android/gradle/mvp-forbidden-surfaces.gradle.kts",
    "apps/android/gradle/wrapper/",
    "apps/android/gradle.properties",
)
SECURITY_OR_CONTRACT_PATHS = {
    "apps/android/build.gradle.kts",
    "apps/android/settings.gradle.kts",
    "apps/android/gradle/libs.versions.toml",
    "apps/android/gradle/i18n-quality-gates.gradle.kts",
    "apps/android/app/build.gradle.kts",
    "package.json",
    "package-lock.json",
    "tools/check-remote-sources.mjs",
    "tools/remote-source-integrity.ts",
    "tools/check-remote-sources.test.mjs",
    "apps/android/gateway-client/src/main/assets/licenses/remote/sources.json",
    "tools/smart-test.py",
    "tools/ci/impact.py",
    "tools/ci/android_tasks.py",
    "tools/ci/android_app_performance.py",
    "tools/test_android_app_performance.py",
    "tools/test_android_jvm_reuse.py",
    "tools/test_ci_planning.py",
    "tools/test_smart_test.py",
    ".github/workflows/remote-client.yml",
    ".github/workflows/ci.yml",
    ".github/workflows/android-apk.yml",
    ".github/workflows/release-gate.yml",
    ".github/actions/android-lane/action.yml",
    "apps/android/ci/app-regression.txt",
    "apps/android/ci/app-jvm-reuse.txt",
}

# 耗时的 App 全量回归与 Play 变体通道，仅在推送/合并到 main、dev（金丝雀预览发布）、
# release* 分支或 Git Tag、发版 PR、显式打标签或手动调度时放行。
APP_REGRESSION_LANES = ("android-app", "android-play")
FULL_CI_LABELS = frozenset({
    "run-full-ci",
    "full-regression",
    "full-ci",
    "release",
})
RELEASE_BRANCH_PREFIXES = ("release/", "release-")

# Module lane -> app-test section. Modules without an entry are cross-cutting and
# widen the app suite to every section (they are compiled into every screen).
MODULE_LANE_TO_APP_SECTION = {
    "notifications": "settings",
    "conversation": "conversations",
    "plugins": "plugins",
}

# Reverse dependencies: changing a library must also run the lanes whose tests
# exercise it. Derived from `implementation(project(":x"))` in each module's
# build script (verified by tools/test_ci_planning.py against the real files).
MODULE_DEPENDENTS = {
    "gateway-client": ("device", "conversation", "plugins"),
    "control-ports": ("gateway", "conversation", "plugins", "notifications"),
    "capability-ports": ("plugins", "notifications"),
    "platform-kernel": ("conversation", "plugins", "notifications", "device"),
    "plugin-package": ("plugins",),
    "plugin-runtime-wasm": ("plugins",),
    "encrypted-store": ("notifications", "conversation"),
    "policy-engine": ("notifications", "plugins"),
    "device-primitives": ("device", "plugins"),
    "conversation-domain": ("conversation", "plugins"),
    "ui-resources": ("conversation", "device"),
}

ANDROID_MODULE_LANES = {
    "control-ports": "device",
    "capability-ports": "device",
    "screen-control": "device",
    "device-primitives": "device",
    "platform-kernel": "platform",
    "plugin-package": "plugins",
    "plugin-runtime-wasm": "plugins",
    "plugin-ui": "plugins",
    "core-model": "platform",
    "capability-sync-runtime": "platform",
    "artifact-ports": "platform",
    "assistant-holder": "platform",
    "ui-resources": "platform",
    "notification-control": "notifications",
    "notification-host": "notifications",
    "notification-collector": "notifications",
    "sms-collector": "notifications",
    "call-log-collector": "notifications",
    "policy-engine": "notifications",
    "encrypted-store": "notifications",
    "conversation-domain": "conversation",
    "conversation-data": "conversation",
    "conversation-ui": "conversation",
    "gateway-client": "gateway",
}

# Lanes are the unit of CI scheduling. Each maps to one or more GitHub jobs.
LANES = (
    "fast",          # protocol/JS/plugin-tooling static + unit gates
    "remote",        # 固定 ACP 投影关闭风险观察
    "android-device",       # device primitives, local authorization
    "android-platform",     # platform kernel recovery
    "android-notifications",  # settings/notification policy/encrypted state
    "android-conversation",   # conversation ordering/rendering/persistence
    "android-gateway",        # Remote 客户端传输与连接
    "android-plugins",        # plugin package/runtime/UI modules
    "android-app",            # full app unit tests (heaviest lane)
    "android-play",           # Play variant integration
)


def _run_git(args: list[str]) -> str:
    result = subprocess.run(
        ["git", "-C", str(REPO_ROOT), *args], capture_output=True
    )
    if result.returncode != 0:
        raise RuntimeError(os.fsdecode(result.stderr).strip() or "git failed")
    # 文本模式会把文件名内的回车转成换行，直接解码字节才能保留文件系统路径。
    return os.fsdecode(result.stdout)


def _normalize_branch_name(ref_or_branch: str | None) -> str:
    """去除 refs/heads/ 前缀并统一小写，保证分支/引用匹配对称。"""
    text = (ref_or_branch or "").strip()
    if text.lower().startswith("refs/heads/"):
        text = text[len("refs/heads/"):]
    return text.lower()


def should_run_app_regressions(
    *,
    event_name: str | None = None,
    ref: str | None = None,
    base_ref: str | None = None,
    head_ref: str | None = None,
    labels: list[str] | tuple[str, ...] | set[str] | None = None,
) -> bool:
    """判断当前触发上下文是否允许执行耗时的 Android App / Play 回归通道。

    仅在以下场景放行：
    1. 合并/推送到发布分支（main 稳定分支、dev 金丝雀预览发布分支、release/* / release-* 分支）或 Git Tag；
    2. 发版 PR（目标为 main 或 release/* / release-*，或源分支以 release/ / release- 开头）；
    3. PR 带有显式全量或发版标签（run-full-ci / full-regression / full-ci / release）；
    4. 手动触发（workflow_dispatch）。
    其余日常普通 feature/fix PR（含无上下文的默认调用）返回 False，仅跑受影响模块单测、轻量 :app 编译与 Fast Gate。
    """
    event = (event_name or "").strip().lower()
    raw_ref = (ref or "").strip().lower()
    ref_branch = _normalize_branch_name(ref)
    base_branch = _normalize_branch_name(base_ref)
    head_branch = _normalize_branch_name(head_ref)
    normalized_labels = {
        label.strip().lower()
        for label in (labels or ())
        if isinstance(label, str) and label.strip()
    }

    is_release_push_ref = (
        ref_branch in {"main", "dev"}
        or ref_branch.startswith(RELEASE_BRANCH_PREFIXES)
        or raw_ref.startswith("refs/tags/")
    )
    is_release_or_labeled_pr = (
        base_branch == "main"
        or base_branch.startswith(RELEASE_BRANCH_PREFIXES)
        or head_branch.startswith(RELEASE_BRANCH_PREFIXES)
        or bool(normalized_labels & FULL_CI_LABELS)
    )

    if event == "workflow_dispatch":
        return True
    if event == "push":
        return is_release_push_ref
    if event == "pull_request":
        return is_release_or_labeled_pr
    if not event:
        return is_release_push_ref or is_release_or_labeled_pr
    return False


def _parse_labels_json(raw: str | None) -> list[str]:
    text = (raw or "").strip()
    if not text or text == "null":
        return []
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"invalid --labels-json payload: {exc}") from exc
    if data is None:
        return []
    if not isinstance(data, list):
        raise SystemExit(
            f"invalid --labels-json payload: expected JSON array, got {type(data).__name__}"
        )
    return [item.strip() for item in data if isinstance(item, str) and item.strip()]


def _apply_app_regression_gate(
    result: dict[str, object], allow_app_regressions: bool
) -> dict[str, object]:
    lanes = result["lanes"]
    assert isinstance(lanes, dict)
    app_impacted = any(lanes[lane] for lane in APP_REGRESSION_LANES)
    result["app_impacted"] = app_impacted
    result["app_regressions_allowed"] = bool(allow_app_regressions)
    if not allow_app_regressions:
        for lane in APP_REGRESSION_LANES:
            lanes[lane] = False
    # 当日常 PR 跳过重型 android-app 回归但改动影响 :app 时，启用轻量编译兜底，
    # 防止带 Kotlin 语法/签名错误的改动绿灯合入 dev 并打断预览发布。
    result["android_app_compile"] = bool(app_impacted and not lanes["android-app"])
    android_lane_active = any(
        enabled for name, enabled in lanes.items() if name.startswith("android-")
    )
    # 只要涉及任意 Android 模块或 app 改动（即使日常 PR 关闭了 App 大回归），
    # 安全预检（硬编码中文、资源完整性、禁用表面）仍必须执行。
    result["android_preflight"] = bool(
        result.get("android_preflight") or android_lane_active or app_impacted
    )
    return result


def changed_paths(base: str | None, head: str | None, staged: bool = False) -> list[tuple[str, str]]:
    """Return (status, path) pairs, expanding renames to both sides."""
    if base == "0" * 40:
        return []
    args = ["diff", "--name-status", "-z", "-M", "--find-renames"]
    if staged:
        args.append("--cached")
    if base:
        args.append(f"{base}...{head or 'HEAD'}")
    elif head:
        args.append(head)
    else:
        # No base: compare worktree to HEAD so local runs stay honest.
        args.append("HEAD")

    pairs: list[tuple[str, str]] = []
    try:
        output = _run_git(args)
    except RuntimeError:
        return pairs

    # Git 的换行输出会转义中文、制表符和换行；以 NUL 分隔才能保留原始路径。
    fields = iter(output.split("\0"))
    for field in fields:
        if not field:
            continue
        status = field[:1]
        try:
            path = next(fields)
            if status in {"R", "C"}:
                pairs.append(("D", path))
                pairs.append(("A", next(fields)))
            else:
                pairs.append((status, path))
        except StopIteration:
            # 无法完整解析时交回空差异，让调用方按现有规则退到全量。
            return []

    # Untracked files are part of the change even before they are added.
    try:
        untracked = _run_git(["ls-files", "-z", "--others", "--exclude-standard"])
    except RuntimeError:
        untracked = ""
    for path in untracked.split("\0"):
        if path:
            pairs.append(("A", path))

    seen: set[tuple[str, str]] = set()
    unique: list[tuple[str, str]] = []
    for pair in pairs:
        if pair not in seen:
            seen.add(pair)
            unique.append(pair)
    return unique


def empty_lanes() -> dict[str, bool]:
    return {lane: False for lane in LANES}


def classify(
    pairs: list[tuple[str, str]],
    *,
    allow_app_regressions: bool = False,
) -> dict[str, object]:
    lanes = empty_lanes()
    # App-test sections this diff touches. "core" is always included; the CI app
    # job runs [core] plus these sections so a UI copy change does not drag the
    # whole app regression set into the critical path.
    sections: set[str] = {"core"}
    result: dict[str, object] = {
        "lanes": lanes,
        "unknown": False,
        "full_required": False,
        "app_impacted": False,
        "app_regressions_allowed": bool(allow_app_regressions),
        "android_preflight": False,
        "android_app_compile": False,
        "app_sections": sections,
        "paths": sorted({path for _, path in pairs}),
    }
    if not pairs:
        # No diff information at all: never claim "nothing to test".
        result["unknown"] = True
        result["full_required"] = True
    else:
        for _, path in pairs:
            if path in SECURITY_OR_CONTRACT_PATHS or path.startswith(SECURITY_OR_CONTRACT_PREFIXES):
                result["full_required"] = True

        if not result["full_required"]:
            unclassified: list[str] = []
            for status, path in pairs:
                if not classify_path(status, path, lanes, sections):
                    unclassified.append(path)
            if unclassified:
                # A single path we cannot place widens the matrix, even when a sibling
                # path (docs, a known module) was classified: otherwise one recognised
                # file would mask an unrecognised executable change.
                result["unknown"] = True
                result["full_required"] = True
                result["unclassified_paths"] = unclassified

    if result["full_required"]:
        for lane in LANES:
            lanes[lane] = True
        result["app_sections"] = set(ALL_APP_SECTIONS)
    return _apply_app_regression_gate(result, allow_app_regressions)


# Sections of apps/android/ci/app-regression.txt. "core" is always run.
APP_SECTIONS = ("core", "settings", "conversations", "plugins", "push-notifications")
ALL_APP_SECTIONS = set(APP_SECTIONS)


def classify_path(
    status: str, path: str, lanes: dict[str, bool], sections: set[str]
) -> bool:
    """Mark the lanes a single path affects. Returns True when it was classified."""
    # 许可和固定上游原文虽有 .txt/.md 扩展名，仍是必须逐字节验证的输入。
    if path.startswith("apps/android/gateway-client/src/main/assets/licenses/remote/"):
        lanes["fast"] = True
        return True
    # Documentation and review artefacts never gate code lanes.
    if path.startswith(("docs/", "tmp/")) or (
        path.endswith((".md", ".txt")) and not path.startswith("apps/android/app/src/test/")
    ):
        return True

    if path.startswith(("plugins/", "plugin-tooling/")):
        lanes["fast"] = True
        # 插件运行时模块单测直接验证真实编译后的 WASM 载荷，日常 PR 无需等整套 App 回归。
        lanes["android-plugins"] = True
        # Plugin payloads are installed and executed by the app tests.
        lanes["android-app"] = True
        lanes["android-play"] = True
        sections.add("plugins")
        return True

    if path.startswith("integrations/oai-android-mcp/"):
        lanes["remote"] = True
        return True

    if path.startswith("tools/remote-interop/"):
        lanes["remote"] = True
        lanes["android-gateway"] = True
        return True

    if path.startswith(("tools/remote/", "tools/remote-server/")):
        # 固定 ACP 投影观察独立于旧 DSH Session Adapter lane。
        lanes["remote"] = True
        return True

    if path.startswith("apps/android/"):
        # Kotlin 门面及请求类型也是原生 remote_* 探针输入，不能只按 Node 文件触发。
        if path.startswith(("apps/android/gateway-client/", "apps/android/control-ports/")):
            lanes["remote"] = True
        return classify_android(status, path, lanes, sections)

    # 根工程配置文件及非生产归档/端到端目录交由快速门禁验证，避免误判为 unknown 触发全量矩阵。
    if path in {
        ".gitignore",
        ".gitattributes",
        ".editorconfig",
        ".env.example",
        ".nvmrc",
        "Makefile",
        "pyproject.toml",
        "vitest.config.ts",
    } or path.startswith((".githooks/", "legacy/", "e2e/")):
        lanes["fast"] = True
        return True

    # 其余工具脚本（已排除前面处理的 tools/remote* 与安全/契约清单）触发快速门禁。
    if path.startswith("tools/") and path not in SECURITY_OR_CONTRACT_PATHS:
        lanes["fast"] = True
        return True

    return False


def classify_android(
    status: str, path: str, lanes: dict[str, bool], sections: set[str]
) -> bool:
    rest = path[len("apps/android/"):]
    module = rest.split("/", 1)[0]

    # Gradle wiring itself is already full-scope via SECURITY_OR_CONTRACT_PATHS.
    lane = ANDROID_MODULE_LANES.get(module)
    if lane:
        lanes[f"android-{lane}"] = True
        # Consumers' tests do not run in the producer's module task.
        for dependent in MODULE_DEPENDENTS.get(module, ()):
            lanes[f"android-{dependent}"] = True
        # Modules are compiled into the app; the app/play lanes are activated only
        # when the trigger context allows full app regressions (main/dev/release*
        # push, Git tag, release PR, explicit label, or workflow_dispatch).
        # Daily PRs still compile :app via `runs_android_app_compile`.
        lanes["android-app"] = True
        lanes["android-play"] = True
        section = MODULE_LANE_TO_APP_SECTION.get(lane)
        if section:
            sections.add(section)
        else:
            # device/platform/gateway modules are cross-cutting for the app suite.
            sections.update(ALL_APP_SECTIONS)
        return True

    if rest.startswith("app/"):
        # Daily PRs run Android Safety Gate, Fast Gate (wired in workflow), :app
        # Kotlin compilation (`runs_android_app_compile`), and any matched feature
        # module lane; heavy Robolectric app/play suites are gated by
        # `allow_app_regressions` without polluting `lanes["fast"]` for local
        # `smart-test.py` runs.
        lanes["android-app"] = True
        lanes["android-play"] = True
        if rest.startswith("app/src/test/"):
            # 新测试漏登清单必须被快速门禁发现；文件名提示不能代替真实所属组。
            lanes["fast"] = True
            sections.update(app_test_sections(path))
        if rest.startswith("app/src/main/"):
            # 这些源码的真实回归位于独立分组，额外补组而不替换原有任务选择。
            if "/onboarding/" in rest or "/updates/" in rest:
                sections.add("settings")
            if "/screen/" in rest:
                sections.add("push-notifications")
                if rest.endswith("/AccessibilityServiceState.kt"):
                    # 能力设置页面读取此状态，需覆盖该页面的授权恢复回归。
                    sections.add("settings")
                if rest.endswith("/TemporaryApprovalCopy.kt"):
                    # ProductionPluginHost.approveRequest 调用这里生成授权摘要。
                    sections.add("plugins")
        for section, lane, hints in APP_FEATURE_HINTS:
            if any(hint in rest for hint in hints):
                lanes[lane] = True
                sections.add(section)
                break
        return True

    if rest.startswith(("tools/", "keystore/")):
        # Release tooling is exercised by the release workflows, not the PR gate.
        return True

    return False


def app_test_sections(path: str) -> set[str]:
    """测试改动按共享清单归组；新类、辅助文件和无法确认的路径覆盖全部组。"""
    planner_path = Path(__file__).with_name("android_tasks.py")
    spec = importlib.util.spec_from_file_location("ci_android_tasks", planner_path)
    planner = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(planner)
    owners = {
        name: section
        for section, names in planner.read_regression_sections().items()
        for name in names
    }

    try:
        source = (REPO_ROOT / path).read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        # 删除后仅凭文件名无法还原文件内的其他类，必须覆盖所有可能的所属组。
        return set(ALL_APP_SECTIONS)
    else:
        # 一个 Kotlin 文件可以有多个测试类，必须合并它们各自的所属组。
        names = set(re.findall(
            r"^\s*(?:(?:internal|abstract|open)\s+)*class\s+(\w+Test)\b",
            source,
            re.MULTILINE,
        )) or {Path(path).stem}
    if names - owners.keys():
        return set(ALL_APP_SECTIONS)
    return {owners[name] for name in names}


# Package/directory hints inside apps/android/app. Order matters: the first
# match wins, so put the narrow capabilities before the broad ones.
#
# Each hint maps to both a module lane and an app-test section
# (apps/android/ci/app-regression.txt). The section is what lets a UI copy
# change run only its own app tests instead of the whole app regression set.
APP_FEATURE_HINTS = (
    # Narrower sections first: `/push/` and `/notifications/` also live under the
    # broad "settings" umbrella, and first match wins.
    ("push-notifications", "android-notifications", (
        "/push/", "/notifications/",
    )),
    ("settings", "android-notifications", (
        "/settings/", "SettingsScreen", "SettingsNavHost", "SettingsViewModel",
    )),
    ("conversations", "android-conversation", (
        "/conversations/", "/conversation/", "Conversation",
    )),
    ("plugins", "android-device", (
        "/plugins/", "Plugin",
    )),
    ("core", "android-gateway", (
        "/gateway/", "/connection/", "/auth/", "Gateway",
    )),
    ("core", "android-device", (
        "/screen/", "/onboarding/", "Screen",
    )),
)


def app_section(rest: str) -> str | None:
    """Which app-test section a file under apps/android/app belongs to."""
    for section, _lane, hints in APP_FEATURE_HINTS:
        if any(hint in rest for hint in hints):
            return section
    return None


def to_serializable(result: dict[str, object]) -> dict[str, object]:
    """JSON-safe copy: `app_sections` is an internal set, exported as a list."""
    copy = dict(result)
    sections = copy.get("app_sections")
    if isinstance(sections, set):
        copy["app_sections"] = sorted(sections)
    return copy


def to_github_output(result: dict[str, object]) -> str:
    lanes = result["lanes"]
    assert isinstance(lanes, dict)
    # GitHub job conditions cannot reference dashed output names reliably, so
    # lane ids are exported with underscores.
    lines = [
        f"runs_{lane.replace('-', '_')}={'true' if value else 'false'}"
        for lane, value in lanes.items()
    ]
    sections = result.get("app_sections") or set()
    assert isinstance(sections, set)
    lines.append("app_sections=" + ",".join(sorted(sections)))
    lines.append(
        f"runs_android_preflight={'true' if result.get('android_preflight') else 'false'}"
    )
    lines.append(
        f"runs_android_app_compile={'true' if result.get('android_app_compile') else 'false'}"
    )
    lines.append(
        f"app_regressions_allowed={'true' if result.get('app_regressions_allowed') else 'false'}"
    )
    lines.append(f"full_required={'true' if result['full_required'] else 'false'}")
    lines.append(f"unknown_diff={'true' if result['unknown'] else 'false'}")
    # Any lane, preflight, or compile check running means the aggregate gate has real work to wait for.
    any_lane = (
        any(lanes.values())
        or bool(result.get("android_preflight"))
        or bool(result.get("android_app_compile"))
    )
    lines.append(f"any_lane={'true' if any_lane else 'false'}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Classify a diff into CI test lanes.")
    parser.add_argument("--base", default=None, help="Base ref/SHA (merge base used)")
    parser.add_argument("--head", default="HEAD", help="Head ref/SHA")
    parser.add_argument(
        "--unknown-diff",
        action="store_true",
        help="Treat diff as unavailable and expand to full lane matrix for current trigger context",
    )
    parser.add_argument(
        "--event",
        default=None,
        help="CI trigger event name (e.g. pull_request, push, workflow_dispatch)",
    )
    parser.add_argument("--ref", default=None, help="Git ref (e.g. refs/heads/main)")
    parser.add_argument("--base-ref", default=None, help="PR base branch name (e.g. dev, main)")
    parser.add_argument(
        "--head-ref",
        default=None,
        help="PR head branch name (e.g. feature/foo, release/1.0)",
    )
    parser.add_argument(
        "--labels-json",
        default=None,
        help="JSON array of PR label names",
    )
    parser.add_argument("--github-output", action="store_true", help="Emit GITHUB_OUTPUT lines")
    parser.add_argument("--json", action="store_true", help="Emit the raw classification")
    parser.add_argument("--json-out", default=None, help="Also write the classification to this path")
    args = parser.parse_args(argv)

    labels = _parse_labels_json(args.labels_json)
    allow_app_regressions = should_run_app_regressions(
        event_name=args.event,
        ref=args.ref,
        base_ref=args.base_ref,
        head_ref=args.head_ref,
        labels=labels,
    )
    pairs = [] if args.unknown_diff else changed_paths(args.base, args.head)
    result = classify(pairs, allow_app_regressions=allow_app_regressions)

    if args.json_out:
        Path(args.json_out).write_text(
            json.dumps(to_serializable(result), indent=2, sort_keys=True), encoding="utf-8"
        )
    if args.json:
        print(json.dumps(to_serializable(result), indent=2, sort_keys=True))
    if args.github_output:
        print(to_github_output(result))

    if not args.json and not args.github_output:
        lanes = result["lanes"]
        assert isinstance(lanes, dict)
        print(f"changed files: {len(pairs)}")
        for path in result["paths"]:
            print(f"  {path}")
        print("lanes:")
        for lane, value in lanes.items():
            print(f"  {lane}: {'yes' if value else 'no'}")
        print(
            f"app/play regressions allowed: {'yes' if result['app_regressions_allowed'] else 'no'}"
        )
        if result["full_required"]:
            print("full matrix required: yes")
        if result["unknown"]:
            print("unclassified diff: yes (conservative full run)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
