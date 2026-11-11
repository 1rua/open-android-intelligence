#!/usr/bin/env python3
"""
Turn a CI impact classification into Gradle arguments.

One place decides which Android tasks a lane runs, so a local `npm run test:pr`
and a CI job cannot drift apart.

    python3 tools/ci/android_tasks.py --group modules --impact-impact impact.json
    python3 tools/ci/android_tasks.py --group app-shard --shard shard-a
    python3 tools/ci/android_tasks.py --group play
    python3 tools/ci/android_tasks.py --group lint

Output is one shell-quoted argument per line; callers use `mapfile`/`xargs`.

Important Gradle detail this script exists to respect: a `--tests` filter binds
to the *last* Test task named before it on the command line, not to every Test
task in the invocation. Emitting one test task followed by its filters keeps
that binding explicit and prevents "the filter silently did nothing and the
whole module suite ran" (slow) or "the filter hit the wrong task" (wrong tests).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REGRESSION_MANIFEST = REPO_ROOT / "apps/android/ci/app-regression.txt"
JVM_REUSE_MANIFEST = REPO_ROOT / "apps/android/ci/app-jvm-reuse.txt"
JVM_REUSE_GROUP_PREFIX = "com.openandroidintelligence.mobile.ci.ReusableAppTestGroup"

# Module tests grouped by lane. Keep in sync with impact.ANDROID_MODULE_LANES.
MODULE_TASKS = {
    "android-device": [
        ":control-ports:testDebugUnitTest",
        ":capability-ports:testDebugUnitTest",
        ":screen-control:testDebugUnitTest",
        ":device-primitives:testDebugUnitTest",
    ],
    "android-notifications": [
        ":notification-control:testDebugUnitTest",
        ":notification-host:testDebugUnitTest",
        ":notification-collector:testDebugUnitTest",
        ":policy-engine:testDebugUnitTest",
        ":encrypted-store:testDebugUnitTest",
        ":sms-collector:testDebugUnitTest",
        ":call-log-collector:testDebugUnitTest",
    ],
    "android-conversation": [
        ":conversation-domain:testDebugUnitTest",
        ":conversation-ui:testDebugUnitTest",
        ":conversation-data:testDebugUnitTest",
    ],
    "android-gateway": [":gateway-client:testDebugUnitTest"],
    # Modules with their own tests that previously had no lane at all: their
    # suites never ran in CI. `plugins` groups the plugin payload/runtime/UI
    # modules; `android-platform` keeps the shared kernel helpers.
    "android-plugins": [
        ":plugin-package:testDebugUnitTest",
        ":plugin-runtime-wasm:testDebugUnitTest",
        ":plugin-ui:testDebugUnitTest",
    ],
    "android-platform": [
        ":platform-kernel:testDebugUnitTest",
        ":core-model:testDebugUnitTest",
        ":capability-sync-runtime:testDebugUnitTest",
        ":artifact-ports:testDebugUnitTest",
        ":assistant-holder:testDebugUnitTest",
        ":ui-resources:testDebugUnitTest",
    ],
}

# These tests must always run for any Android change: they assert the
# distribution policy and the forbidden-surface gate that every variant shares.
ALWAYS_TASKS = ["noVpnSurfaceCheck", ":app:compileFullDebugKotlin"]

# Tests that assert Play-variant policy and cross-variant UI behaviour.
PLAY_TESTS = [
    ":app:testPlayDebugUnitTest",
    "--tests=SettingsResponsiveHostRegressionTest",
    "--tests=ScreenExecutionErrorsTest",
    "--tests=ScreenCapabilityPresentationTest",
    "--tests=ScreenCaptureSystemConsentTest",
    "--tests=ScreenControlConfirmationDialogTest",
    "--tests=NotificationAccessSettingsTest",
    "--tests=PluginCapabilityRiskTest",
    # Play-only: asserts the channel exposes no built-in capabilities and
    # reports BUILT_IN_CHANNEL_UNSUPPORTED. The class skips under the Full
    # variant, so listing it in the Full manifest does not cover this.
    "--tests=BuiltInCapabilitiesTest",
    "--tests=CapabilityPublisherTest",
    "--tests=ApplicationCompositionTest",
    "--tests=AndroidApiCompatibilityTest",
    "--tests=CoreAgentConversationRepositoryTest",
    "--tests=ColdStartConnectionTest",
    "--tests=AccountSwitchPersistenceFailureTest",
    "--tests=IndependentConnectionDiscoveryTest",
    "--tests=AdditionalDeviceConnectionsTest",
    "--tests=SettingsViewModelTest",
    "--tests=NativeEventDeletionPersistenceTest",
]


def read_regression_sections() -> dict[str, list[str]]:
    """The regression manifest as {section: [class, ...]}, in file order."""
    sections: dict[str, list[str]] = {}
    current = ""
    for raw in REGRESSION_MANIFEST.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("[") and line.endswith("]"):
            current = line[1:-1]
            sections.setdefault(current, [])
            continue
        if not current:
            raise SystemExit(f"manifest entry outside a section: {line!r}")
        sections[current].append(line)
    if not sections or not sections.get("core"):
        raise SystemExit(f"manifest is missing its [core] section: {REGRESSION_MANIFEST}")
    return sections


def read_regression_manifest() -> list[str]:
    """Every class in the PR app regression set, in file order."""
    entries: list[str] = []
    for section in read_regression_sections().values():
        entries.extend(section)
    if not entries:
        raise SystemExit(f"empty regression manifest: {REGRESSION_MANIFEST}")
    return entries


def modules(impact: dict[str, object]) -> list[str]:
    lanes = impact.get("lanes") or {}
    assert isinstance(lanes, dict)
    tasks: list[str] = []
    if lanes.get("full_required") or impact.get("full_required"):
        for lane_tasks in MODULE_TASKS.values():
            for task in lane_tasks:
                if task not in tasks:
                    tasks.append(task)
    else:
        for lane, lane_tasks in MODULE_TASKS.items():
            if lanes.get(lane):
                for task in lane_tasks:
                    if task not in tasks:
                        tasks.append(task)
    return tasks


def read_shard_args(name: str) -> list[str]:
    """Gradle arguments for one app shard: the Test task, then its filters."""
    return [":app:testFullDebugUnitTest"] + [f"--tests={entry}" for entry in read_shard(name)]


def module_lane_args(lane: str) -> list[str]:
    if lane not in MODULE_TASKS:
        raise SystemExit(f"unknown module lane: {lane!r} (have: {sorted(MODULE_TASKS)})")
    return list(MODULE_TASKS[lane])


def full_module_tasks() -> list[str]:
    """Every module test task, deduplicated, in a stable order."""
    tasks: list[str] = []
    for lane_tasks in MODULE_TASKS.values():
        for task in lane_tasks:
            if task not in tasks:
                tasks.append(task)
    return tasks


def read_jvm_reuse_manifest() -> list[str]:
    """读取显式审计清单；错误配置必须失败，不能静默扩大复用范围。"""
    names = [line.split("#", 1)[0].strip() for line in JVM_REUSE_MANIFEST.read_text(encoding="utf-8").splitlines()]
    names = [name for name in names if name]
    if not names or len(names) != len(set(names)):
        raise SystemExit("JVM 复用清单为空或存在重复类")
    known = set(read_regression_manifest())
    short_names: set[str] = set()
    for name in names:
        short_name = name.rsplit(".", 1)[-1]
        if not re.fullmatch(r"com\.openandroidintelligence\.mobile\.(?:[A-Za-z_][\w]*\.)*[A-Za-z_][\w]*Test", name):
            raise SystemExit(f"无效的 JVM 复用类名: {name!r}")
        if short_name not in known or short_name in short_names:
            raise SystemExit(f"JVM 复用类未登记或短类名冲突: {name}")
        short_names.add(short_name)
    return names


def jvm_reuse_group(name: str) -> int:
    """与 Java String.hashCode/floorMod 完全一致，保证清单只分配一次。"""
    value = 0
    for character in name:
        value = (31 * value + ord(character)) & 0xFFFFFFFF
    if value >= 0x80000000:
        value -= 0x100000000
    return value % 4


def pr_args_for_sections(sections: list[str] | None, *, jvm_reuse: bool = False, seed: int = 0) -> list[str]:
    """One Test task plus filters for [core] + the requested sections.

    `sections=None` means "everything" (no narrowing).
    """
    available = read_regression_sections()
    if not sections:
        names = read_regression_manifest()
    else:
        unknown = sorted(set(sections) - set(available))
        if unknown:
            raise SystemExit(f"unknown app-test sections: {unknown} (have: {sorted(available)})")
        names = []
        for section in ("core", *sorted(set(sections) - {"core"})):
            names.extend(available[section])
    ordered = list(dict.fromkeys(names))
    arguments = [":app:testFullDebugUnitTest"] + [f"--tests={name}" for name in ordered]
    if jvm_reuse:
        selected = [name for name in read_jvm_reuse_manifest() if name.rsplit(".", 1)[-1] in ordered]
        if selected:
            groups = sorted({jvm_reuse_group(name) for name in selected})
            arguments.extend(f"--tests={JVM_REUSE_GROUP_PREFIX}{group}" for group in groups)
            arguments.extend([
                f"-Poai.testReuseClasses={','.join(selected)}",
                f"-Poai.testReuseSeed={seed}",
            ])
    return arguments


def pr_app_args() -> list[str]:
    """同一任务覆盖完整 PR 清单，只编译一次；按实际阶段测量决定后续调度。

    使用大写开头的简单类名，让 Gradle 在测试发现前排除未选中的类。
    前导通配符会使未选中的类也启动 JVM，定向两个类曾实际启动 166 个进程。
    """
    ordered = read_regression_manifest()
    return [":app:testFullDebugUnitTest"] + [f"--tests={name}" for name in ordered]


def pr_app_shard_names() -> list[str]:
    """Every class in the PR app regression set."""
    return read_regression_manifest()


def app_variant_tasks() -> list[str]:
    """Both app variants, unfiltered: the complete app regression."""
    return [":app:testFullDebugUnitTest", ":app:testPlayDebugUnitTest"]


def play_args() -> list[str]:
    return list(PLAY_TESTS)


def emit(arguments: list[str]) -> None:
    # One argument per line, unquoted. Consumers read this with `mapfile` and
    # pass the array to Gradle without shell parsing, so shell quoting would be
    # delivered literally. Arguments here never contain newlines.
    for argument in arguments:
        if "\n" in argument:
            raise SystemExit(f"argument contains a newline: {argument!r}")
        print(argument)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Emit Gradle arguments for one CI lane.")
    parser.add_argument("--group", required=True,
                        choices=["modules", "app-shard", "app-pr", "play", "lint", "full"])
    parser.add_argument("--shard", default=None, help="shard name for --group app-shard")
    parser.add_argument("--jvm-reuse", action="store_true", help="仅让显式审计清单中的 App 测试复用 JVM")
    parser.add_argument("--seed", type=int, default=0, help="JVM 复用组内测试乱序的固定种子")
    parser.add_argument(
        "--sections",
        default=None,
        help=(
            "Comma-separated app-test sections for --group app-pr; empty means "
            "all sections. 'core' is always included."
        ),
    )
    parser.add_argument("--lane", default=None,
                        help="single lane name for --group modules (CI job granularity)")
    parser.add_argument("--impact", default=None, help="impact JSON path for --group modules")
    args = parser.parse_args(argv)

    if args.group == "modules":
        if args.lane:
            if args.lane not in MODULE_TASKS:
                raise SystemExit(f"unknown module lane: {args.lane!r} (have: {sorted(MODULE_TASKS)})")
            # CI already proved the safety gate in the preflight job; repeating it
            # here would only burn the same minute again.
            arguments = list(MODULE_TASKS[args.lane])
        else:
            impact: dict[str, object] = {"lanes": {}, "full_required": False}
            if args.impact:
                impact = json.loads(Path(args.impact).read_text(encoding="utf-8"))
            arguments = ALWAYS_TASKS + modules(impact)
    elif args.group == "app-shard":
        if not args.shard:
            raise SystemExit("--group app-shard requires --shard")
        arguments = read_shard_args(args.shard)
    elif args.group == "app-pr":
        sections = None
        if args.sections:
            sections = [part.strip() for part in args.sections.split(",") if part.strip()]
        arguments = pr_args_for_sections(sections, jvm_reuse=args.jvm_reuse, seed=args.seed)
    elif args.group == "play":
        arguments = play_args()
    elif args.group == "lint":
        arguments = ["noVpnSurfaceCheck", ":app:lintFullDebug"]
    else:  # full
        arguments = [
            "noVpnSurfaceCheck",
            ":app:testFullDebugUnitTest",
            ":app:testPlayDebugUnitTest",
        ]

    emit(arguments)
    return 0


if __name__ == "__main__":
    sys.exit(main())
