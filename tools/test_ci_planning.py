"""CI planning helpers must never let a change skip its own tests.

These tests pin the two properties that matter more than any speed number:
  1. A diff that cannot be classified widens the matrix (never narrows it).
  2. A security/contract/build-config change runs everything.
They also pin the Gradle `--tests` binding that tools/ci/android_tasks.py exists
to protect: filters must follow their Test task, one task per invocation.
"""

import importlib.util
import re
import shlex
import subprocess
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parent
REPO_ROOT = TOOLS.parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / "ci" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def impact():
    return load("impact")


@pytest.fixture(scope="module")
def tasks():
    return load("android_tasks")


def active_lanes(result):
    return {lane for lane, enabled in result["lanes"].items() if enabled}


@pytest.fixture
def git_repository(tmp_path, monkeypatch, impact):
    """用真实 Git 输出验证路径分隔，避免把转义规则写进桩数据。"""
    root = tmp_path / "repository"
    root.mkdir()

    def git(*arguments):
        return subprocess.run(
            ["git", "-C", str(root), *arguments],
            check=True,
            capture_output=True,
            text=True,
        ).stdout

    git("init", "--quiet")
    git("config", "user.name", "CI 规划测试")
    git("config", "user.email", "ci-planning@example.invalid")
    git("commit", "--quiet", "--allow-empty", "-m", "测试: 建立路径解析基线")
    monkeypatch.setattr(impact, "REPO_ROOT", root)
    return root, git


# --- impact.classify -------------------------------------------------------


@pytest.mark.parametrize("path", [
    "docs/验证汇总.json",
    "docs/带 空格.json",
    "docs/带\t制表符.json",
    "docs/带\n换行.json",
    "docs/带\r回车.json",
    "docs/ 前后空白 .json",
])
def test_git_diff_preserves_document_paths(impact, git_repository, path):
    root, git = git_repository
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("{}\n", encoding="utf-8")
    git("add", "--", path)
    git("commit", "--quiet", "-m", "测试: 加入特殊名称文档")

    pairs = impact.changed_paths("HEAD^", "HEAD")

    assert pairs == [("A", path)]
    assert active_lanes(impact.classify(pairs)) == set()


@pytest.mark.parametrize("path", [
    "docs/未跟踪 验证.json",
    "docs/未跟踪\n验证.json",
    "docs/未跟踪\r验证.json",
    "docs/ 前后空白 .json",
])
def test_git_untracked_paths_are_preserved(impact, git_repository, path):
    root, _git = git_repository
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("{}\n", encoding="utf-8")

    assert impact.changed_paths(None, None) == [("A", path)]


def test_git_rename_preserves_both_path_owners(impact, git_repository):
    root, git = git_repository
    old_path = "apps/android/gateway-client/src/main/旧 客户端\n实现.kt"
    new_path = "docs/新 客户端\n实现.json"
    source = root / old_path
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text("class 客户端\n", encoding="utf-8")
    git("add", "--", old_path)
    git("commit", "--quiet", "-m", "测试: 建立重命名来源")
    target = root / new_path
    target.parent.mkdir(parents=True, exist_ok=True)
    source.rename(target)
    git("add", "--all")
    git("commit", "--quiet", "-m", "测试: 重命名特殊路径")

    pairs = impact.changed_paths("HEAD^", "HEAD")

    assert pairs == [("D", old_path), ("A", new_path)]
    assert "android-gateway" in active_lanes(impact.classify(pairs))


def test_docs_only_change_runs_nothing(impact):
    result = impact.classify([("M", "docs/reviews/whatever.zh-CN.md")])
    assert result["full_required"] is False
    assert active_lanes(result) == set()


def non_app_lanes(impact):
    return set(impact.LANES) - set(impact.APP_REGRESSION_LANES)


def test_unclassifiable_path_forces_full_matrix(impact):
    pr_result = impact.classify([("M", "some-new-top-level-dir/thing.bin")])
    assert pr_result["unknown"] is True
    assert pr_result["full_required"] is True
    assert pr_result["app_regressions_allowed"] is False
    assert pr_result["android_preflight"] is True
    assert active_lanes(pr_result) == non_app_lanes(impact)

    full_result = impact.classify(
        [("M", "some-new-top-level-dir/thing.bin")],
        allow_app_regressions=True,
    )
    assert full_result["unknown"] is True
    assert full_result["full_required"] is True
    assert full_result["app_regressions_allowed"] is True
    assert active_lanes(full_result) == set(impact.LANES)


def test_empty_diff_is_treated_as_full_not_as_no_op(impact):
    pr_result = impact.classify([])
    assert pr_result["full_required"] is True
    assert pr_result["unknown"] is True
    assert active_lanes(pr_result) == non_app_lanes(impact)

    full_result = impact.classify([], allow_app_regressions=True)
    assert full_result["full_required"] is True
    assert full_result["unknown"] is True
    assert active_lanes(full_result) == set(impact.LANES)


@pytest.mark.parametrize("path", [
    "apps/android/control-ports/src/main/kotlin/com/openandroidintelligence/control/remote/ControlRequest.kt",
    "apps/android/gradle.properties",
    "apps/android/build.gradle.kts",
    "apps/android/app/build.gradle.kts",
    "apps/android/gradle/mvp-forbidden-surfaces.gradle.kts",
    "apps/android/gradle/i18n-quality-gates.gradle.kts",
    "package-lock.json",
    "tools/smart-test.py",
    ".github/workflows/android-apk.yml",
    ".github/actions/android-lane/action.yml",
])
def test_security_and_build_config_paths_run_everything(impact, path):
    pr_result = impact.classify([("M", path)])
    assert pr_result["full_required"] is True
    assert pr_result["android_app_compile"] is True
    assert active_lanes(pr_result) == non_app_lanes(impact)

    full_result = impact.classify([("M", path)], allow_app_regressions=True)
    assert full_result["full_required"] is True
    assert full_result["android_app_compile"] is False
    assert active_lanes(full_result) == set(impact.LANES)


def test_core_security_paths_widen_the_matrix(impact):
    result = impact.classify([("M", "apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/remote/security/AuthGate.kt")])
    assert result["full_required"] is True


def test_single_android_module_defers_app_and_play_on_daily_pr_and_enables_on_full_gate(impact):
    path = ("M", "apps/android/platform-kernel/src/main/PairingGrant.kt")

    pr_result = impact.classify([path])
    pr_lanes = active_lanes(pr_result)
    assert "android-platform" in pr_lanes
    assert "fast" not in pr_lanes
    assert pr_result["android_preflight"] is True
    assert pr_result["android_app_compile"] is True
    assert pr_result["app_impacted"] is True
    assert "android-app" not in pr_lanes
    assert "android-play" not in pr_lanes
    assert "android-gateway" not in pr_lanes

    full_result = impact.classify([path], allow_app_regressions=True)
    full_lanes = active_lanes(full_result)
    assert "android-platform" in full_lanes
    assert full_result["android_app_compile"] is False
    # 发版/主干场景保留完整 App 与 Play 变体回归，确保模块编入 APK 的集成行为仍被覆盖。
    assert {"android-app", "android-play"} <= full_lanes
    assert "android-gateway" not in full_lanes


def test_deleted_test_file_still_runs_its_lane(impact):
    pair = ("D", "apps/android/app/src/test/kotlin/com/x/GatewaySessionRefreshTest.kt")
    pr_result = impact.classify([pair])
    assert "fast" in active_lanes(pr_result)
    assert pr_result["android_preflight"] is True
    assert "android-app" not in active_lanes(pr_result)
    assert "android-play" not in active_lanes(pr_result)

    full_result = impact.classify([pair], allow_app_regressions=True)
    assert {"android-app", "android-play"} <= active_lanes(full_result)


def test_rename_counts_both_sides(impact):
    result = impact.classify([
        ("D", "plugins/sms/src/lib.rs"),
        ("A", "plugins/sms-v2/src/lib.rs"),
    ])
    lanes = active_lanes(result)
    assert "fast" in lanes
    assert "android-plugins" in lanes
    assert "android-app" not in lanes
    assert "android-play" not in lanes


def test_app_sections_narrow_to_the_touched_domain(impact):
    result = impact.classify([
        ("M", "apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt"),
    ])
    assert result["app_sections"] == {"core", "settings"}
    assert result["full_required"] is False


@pytest.mark.parametrize("source, sections, regression_names, module_lanes", [
    (
        "onboarding/AssistantRole.kt",
        {"core", "settings"},
        ["AssistantRoleTest"],
        {"android-device"},
    ),
    (
        "onboarding/OnboardingState.kt",
        {"core", "settings"},
        ["OnboardingStateTest"],
        {"android-device"},
    ),
    (
        "onboarding/PermissionCatalog.kt",
        {"core", "settings"},
        ["PermissionCatalogTest"],
        {"android-device"},
    ),
    (
        "onboarding/data/RemoteTutorials.kt",
        {"core", "settings"},
        ["RemoteTutorialsTest"],
        {"android-device"},
    ),
    (
        "onboarding/motion/ButtonRevealTransition.kt",
        {"core", "settings"},
        ["ButtonRevealGeometryTest"],
        {"android-device"},
    ),
    (
        "updates/AppUpdateScreen.kt",
        {"core", "settings"},
        ["AppUpdateScreenTest"],
        {"android-device"},
    ),
    (
        "updates/AppUpdateViewModel.kt",
        {"core", "settings"},
        ["AppUpdateViewModelTest"],
        set(),
    ),
    (
        "updates/AppUpdateChecker.kt",
        {"core", "settings"},
        ["AppUpdateCheckerTest", "AppUpdateViewModelTest"],
        set(),
    ),
    (
        "screen/ScreenCapabilityController.kt",
        {"core", "push-notifications"},
        ["ScreenCapabilityPresentationTest"],
        {"android-device"},
    ),
    (
        "screen/ScreenCaptureSystemConsent.kt",
        {"core", "push-notifications"},
        ["ScreenCaptureSystemConsentTest"],
        {"android-device"},
    ),
    (
        "screen/DeviceAuthorizationCopy.kt",
        {"core", "push-notifications"},
        ["DeviceAuthorizationCopyTest"],
        {"android-device"},
    ),
    (
        "screen/AccessibilityServiceState.kt",
        {"core", "push-notifications", "settings"},
        ["AccessibilityServiceWiringTest", "NotificationScopeOwnershipTest"],
        {"android-device"},
    ),
    (
        "screen/TemporaryApprovalCopy.kt",
        {"core", "push-notifications", "plugins"},
        ["TemporaryApprovalCopyTest", "ProductionPluginHostTest", "ClientCapabilityAuthorizationTest"],
        {"android-device"},
    ),
    (
        "plugins/DeviceCapabilityPermissions.kt",
        {"core", "plugins"},
        ["ScreenCaptureGrantRoutingTest", "DeviceCapabilityPermissionsTest"],
        {"android-device"},
    ),
])
def test_production_source_selects_its_real_app_regressions(
    impact, tasks, source, sections, regression_names, module_lanes
):
    """用真实源码和测试清单核对跨目录引用，同时保留旧提示选中的模块任务。"""
    path = f"apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/{source}"
    assert (REPO_ROOT / path).is_file(), path

    pr_result = impact.classify([("M", path)])
    arguments = tasks.pr_args_for_sections(sorted(pr_result["app_sections"]))

    assert pr_result["full_required"] is False
    assert pr_result["app_sections"] == sections
    assert pr_result["android_preflight"] is True
    assert pr_result["android_app_compile"] is True
    assert module_lanes <= active_lanes(pr_result)
    assert "fast" not in active_lanes(pr_result)
    assert "android-app" not in active_lanes(pr_result)
    assert "android-play" not in active_lanes(pr_result)

    full_result = impact.classify([("M", path)], allow_app_regressions=True)
    assert full_result["android_app_compile"] is False
    assert {"android-app", "android-play"} | module_lanes <= active_lanes(full_result)
    for name in regression_names:
        assert f"--tests={name}" in arguments, (path, name)


@pytest.mark.parametrize("status", ["A", "M", "D"])
def test_each_changed_app_test_selects_its_manifest_section(impact, tasks, status):
    """登记进清单还不够：单独修改任何测试文件都必须实际选中它。"""
    owners = {
        name: section
        for section, names in tasks.read_regression_sections().items()
        for name in names
    }
    failures = []
    test_root = REPO_ROOT / "apps/android/app/src/test/kotlin"
    for path in test_root.rglob("*.kt"):
        names = re.findall(
            r"^\s*(?:(?:internal|abstract|open)\s+)*class\s+(\w+Test)\b",
            path.read_text(encoding="utf-8"),
            re.MULTILINE,
        )
        if not names:
            continue
        relative = path.relative_to(REPO_ROOT).as_posix()
        result = impact.classify([(status, relative)])
        assert result["lanes"]["fast"] is True, relative
        for name in names:
            if owners[name] not in result["app_sections"]:
                failures.append(f"{relative}: {name} 未选中 {owners[name]}")
    assert not failures, "\n".join(failures)


def test_app_test_with_multiple_classes_selects_every_owner(impact, tmp_path, monkeypatch):
    path = "apps/android/app/src/test/kotlin/MultiFeatureTest.kt"
    source = tmp_path / path
    source.parent.mkdir(parents=True)
    source.write_text(
        "class AppUpdateViewModelTest {}\nclass PluginPackageInputTest {}\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(impact, "REPO_ROOT", tmp_path)

    result = impact.classify([("M", path)])

    assert result["app_sections"] == {"core", "settings", "plugins"}


def test_missing_app_test_source_does_not_guess_its_only_class(impact, tmp_path, monkeypatch):
    monkeypatch.setattr(impact, "REPO_ROOT", tmp_path)
    path = "apps/android/app/src/test/kotlin/AppUpdateViewModelTest.kt"

    result = impact.classify([("D", path)])

    assert result["lanes"]["fast"] is True
    assert result["app_sections"] == set(impact.APP_SECTIONS)


@pytest.mark.parametrize("path", [
    "apps/android/app/src/test/kotlin/NewUnlistedRegressionTest.kt",
    "apps/android/app/src/test/kotlin/SharedTestFixture.kt",
    "apps/android/app/src/test/resources/fixture.txt",
])
def test_unknown_app_tests_require_all_sections_and_manifest_check(impact, path):
    pr_result = impact.classify([("A", path)])

    assert pr_result["lanes"]["fast"] is True
    assert pr_result["android_preflight"] is True
    assert pr_result["lanes"]["android-app"] is False
    assert pr_result["lanes"]["android-play"] is False
    assert pr_result["app_sections"] == set(impact.APP_SECTIONS)

    full_result = impact.classify([("A", path)], allow_app_regressions=True)
    assert {"android-app", "android-play"} <= active_lanes(full_result)
    assert full_result["app_sections"] == set(impact.APP_SECTIONS)


def test_unknown_scope_widens_app_sections(impact):
    result = impact.classify([("M", "some-new-top-level-dir/thing.bin")])
    assert result["app_sections"] == set(impact.APP_SECTIONS)


def test_unclassified_path_is_not_masked_by_a_classified_sibling(impact):
    """One recognised file must not hide an unrecognised executable change."""
    result = impact.classify([
        ("M", "docs/foo.md"),
        ("M", "weird-new-dir/thing.bin"),
    ])
    assert result["full_required"] is True
    assert result["unknown"] is True
    assert result["unclassified_paths"] == ["weird-new-dir/thing.bin"]


def test_executable_manifests_are_not_documentation(impact):
    """The app regression manifest and fixture requirements control tests."""
    for path in (
        "apps/android/ci/app-regression.txt",
        "tools/remote-source-integrity.ts",
    ):
        result = impact.classify([("M", path)])
        assert result["full_required"] is True, path


def test_gateway_change_runs_consumer_lanes(impact):
    """Consumer module tests do not run in the producer's own module task."""
    lanes = impact.classify([
        ("M", "apps/android/gateway-client/src/main/kotlin/x/Client.kt"),
    ])["lanes"]
    assert lanes["android-gateway"] is True
    assert lanes["android-conversation"] is True
    assert lanes["android-device"] is True


def test_push_paths_win_over_broad_settings_hint(impact):
    result = impact.classify([
        ("M", "apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt"),
    ])
    assert "push-notifications" in result["app_sections"]


def test_ui_resources_change_runs_consumer_lanes_and_all_app_sections(impact):
    """ui-resources changes must run platform, conversation, device, and app regressions when allowed."""
    pair = ("M", "apps/android/ui-resources/src/main/res/values/strings_common.xml")
    pr_result = impact.classify([pair])
    pr_lanes = pr_result["lanes"]
    assert pr_lanes["fast"] is False
    assert pr_lanes["android-platform"] is True
    assert pr_lanes["android-conversation"] is True
    assert pr_lanes["android-device"] is True
    assert pr_lanes["android-app"] is False
    assert pr_lanes["android-play"] is False
    assert pr_lanes["android-gateway"] is False
    assert pr_result["android_preflight"] is True
    assert pr_result["android_app_compile"] is True
    assert pr_result["full_required"] is False
    assert pr_result["unknown"] is False
    # ui-resources 对应用套件是横切的，必须覆盖所有 app sections
    assert pr_result["app_sections"] == set(impact.APP_SECTIONS)

    full_result = impact.classify([pair], allow_app_regressions=True)
    assert full_result["lanes"]["android-app"] is True
    assert full_result["lanes"]["android-play"] is True
    assert full_result["android_app_compile"] is False


def test_every_module_with_tests_has_a_lane(tasks):
    """A module test suite with no lane never runs in CI."""
    android_root = REPO_ROOT / "apps/android"
    covered = {
        task.split(":")[1]
        for lane_tasks in tasks.MODULE_TASKS.values()
        for task in lane_tasks
    }
    missing = []
    for module_dir in android_root.iterdir():
        if not module_dir.is_dir():
            continue
        # `app` is the application module; it is covered by the app lanes.
        if module_dir.name == "app":
            continue
        test_dir = module_dir / "src/test"
        if not test_dir.is_dir():
            continue
        if any(test_dir.rglob("*Test.kt")) and module_dir.name not in covered:
            missing.append(module_dir.name)
    assert not missing, f"modules with tests but no CI lane: {missing}"


def test_github_output_uses_underscore_names(impact):
    result = impact.classify([("M", "tools/remote/projection-scope.test.mjs")])
    output = impact.to_github_output(result)
    # Workflow outputs and `if:` conditions cannot contain dashes reliably.
    assert "runs_remote=true" in output
    assert "runs_fast=false" in output
    for line in output.splitlines():
        assert "-" not in line.split("=", 1)[0], line


# --- android_tasks ---------------------------------------------------------


def test_pr_app_invocation_puts_the_task_before_its_filters(tasks):
    # Gradle binds --tests to the Test task named before it. A filter emitted
    # before any task is rejected; a second unfiltered Test task would run its
    # whole suite.
    arguments = tasks.pr_app_args()
    assert arguments[0] == ":app:testFullDebugUnitTest"
    assert all(argument.startswith("--tests=") for argument in arguments[1:])
    assert len(arguments) > 1
    # Exactly one Test task: compiling the app dominates, so the set must not be
    # split across jobs or tasks.
    assert len([a for a in arguments if not a.startswith("--tests=")]) == 1


def test_regression_manifest_sections_are_complete_and_unique(tasks):
    sections = tasks.read_regression_sections()
    assert set(sections) == {"core", "settings", "conversations", "plugins", "push-notifications"}
    assert sections["core"], "the always-run core section must not be empty"
    entries = tasks.read_regression_manifest()
    assert len(entries) >= 60
    assert len(entries) == len(set(entries))


def test_section_filter_always_includes_core(tasks):
    all_args = tasks.pr_args_for_sections(None)
    narrow = tasks.pr_args_for_sections(["settings"])
    assert narrow[0] == ":app:testFullDebugUnitTest"
    assert len(narrow) < len(all_args)
    core_names = tasks.read_regression_sections()["core"]
    for name in core_names:
        assert f"--tests={name}" in narrow
    for name in tasks.read_regression_sections()["settings"]:
        assert f"--tests={name}" in narrow
    for name in tasks.read_regression_sections()["plugins"]:
        assert f"--tests={name}" not in narrow


def test_unknown_section_is_rejected(tasks):
    with pytest.raises(SystemExit):
        tasks.pr_args_for_sections(["nope"])


def test_regression_manifest_covers_every_app_test_class(tasks):
    """No app unit test may be silently excluded from the PR gate.

    The manifest is the whole PR-level app regression set. A newly added test
    that is not listed here would never run in CI, which is how 76 of 152 test
    classes had quietly dropped out of the gate.
    """
    test_root = REPO_ROOT / "apps/android/app/src/test/kotlin"
    discovered = set()
    for path in test_root.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        for line in text.splitlines():
            stripped = line.strip()
            for prefix in ("class ", "internal class ", "abstract class ", "open class "):
                if stripped.startswith(prefix):
                    name = stripped[len(prefix):].split("(")[0].split()[0].split("<")[0]
                    if name.endswith("Test"):
                        discovered.add(name)
    listed = set(tasks.read_regression_manifest())
    missing = sorted(discovered - listed)
    assert not missing, f"app test classes missing from the PR manifest: {missing}"


def test_every_regression_entry_exists_as_a_test_class(tasks):
    test_root = REPO_ROOT / "apps/android/app/src/test/kotlin"
    names = set()
    for path in test_root.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        for line in text.splitlines():
            stripped = line.strip()
            for prefix in ("class ", "internal class ", "abstract class ", "open class "):
                if stripped.startswith(prefix):
                    names.add(stripped[len(prefix):].split("(")[0].split()[0].split("<")[0])
    missing = sorted(set(tasks.read_regression_manifest()) - names)
    assert not missing, f"regression manifest references unknown classes: {missing}"


def test_module_lane_emits_only_its_own_tasks(tasks):
    arguments = tasks.module_lane_args("android-gateway")
    assert arguments == [":gateway-client:testDebugUnitTest"]


def test_lane_names_match_impact_module_lanes(tasks, impact):
    assert set(tasks.MODULE_TASKS) <= set(impact.LANES)


def test_unknown_lane_is_rejected(tasks, capsys):
    with pytest.raises(SystemExit):
        tasks.module_lane_args("android-nope")


def test_play_lane_enables_parallel_forks(tasks):
    arguments = tasks.play_args()
    assert arguments[0] == ":app:testPlayDebugUnitTest"
    assert len([a for a in arguments if a.startswith("--tests=")]) >= 10


def test_app_compile_lane_targets_full_debug_and_unit_test_kotlin(tasks):
    assert tasks.app_compile_args() == [
        ":app:compileFullDebugKotlin",
        ":app:compileFullDebugUnitTestKotlin",
    ]


# --- end-to-end wiring -----------------------------------------------------


def test_local_pr_sections_match_the_shared_classifier():
    """The local PR run must not be more optimistic than CI.

    `tools/smart-test.py` derives its app-test sections from the same classifier
    CI uses; a divergence here would let a workstation pass a PR the gate later
    rejects (or vice versa).
    """
    import importlib.util

    smart = Path(REPO_ROOT / "tools/smart-test.py")
    spec = importlib.util.spec_from_file_location("smart_test_runner", smart)
    runner = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(runner)

    settings_file = [
        "apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt"
    ]
    assert runner.android_app_sections(settings_file) == ["core", "settings"]

    # A full-scope change must fall back to "every section" (None), never to a
    # narrower set.
    assert runner.android_app_sections(["apps/android/gradle.properties"]) is None
    assert runner.android_app_sections([]) is None


def test_ci_workflow_gates_each_lane_on_its_own_output():
    import yaml

    workflow = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/remote-client.yml").read_text(encoding="utf-8")
    )
    jobs = workflow["jobs"]
    assert "changes" in jobs
    # Gate must always run on real runs (only skipping non-CI label noop events),
    # otherwise a cancelled lane leaves the check pending.
    gate_if = jobs["gate"].get("if") or ""
    assert gate_if.startswith("always()")
    for name, job in jobs.items():
        if not name.startswith("android-") or name in {"android-setup", "android-preflight"}:
            continue
        condition = job.get("if") or ""
        assert "needs.changes.outputs." in condition, name


def test_ci_gate_depends_on_every_android_prerequisite():
    """A skipped lane must not hide a failed fixture build.

    If android-setup fails, every android lane is skipped; without an explicit
    dependency the gate would see only success/skipped and pass.
    """
    import yaml

    jobs = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/remote-client.yml").read_text(encoding="utf-8")
    )["jobs"]
    gate_needs = set(jobs["gate"]["needs"])
    assert "android-setup" in gate_needs
    assert "android-preflight" in gate_needs
    assert "android-app-compile" in gate_needs
    assert "changes" in gate_needs


def test_android_preflight_executes_i18n_gates_and_fast_tests_the_checkers():
    """PR 实际执行的预检须调用文案门禁，快速通道同时验证门禁自身的回归。"""
    import yaml

    jobs = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/remote-client.yml").read_text(encoding="utf-8")
    )["jobs"]
    preflight_commands = [
        shlex.split(line)
        for step in jobs["android-preflight"]["steps"]
        for line in step.get("run", "").splitlines()
    ]
    for script in ("tools/check_hardcoded_chinese.py", "tools/test_string_resources_integrity.py"):
        assert ["python3", script] in preflight_commands

    fast_arguments = shlex.split("\n".join(step.get("run", "") for step in jobs["fast"]["steps"]))
    assert "tools/test_check_hardcoded_chinese.py" in fast_arguments
    assert "tools/test_test_string_resources_integrity.py" in fast_arguments


def test_artifact_consumers_cannot_outlive_their_producer():
    """A lane that downloads android-setup's artifact must never run without it.

    If android-setup is skipped while a consumer still runs, the download step
    fails late and the failure looks unrelated to the change. Catch the wiring
    mismatch statically instead.
    """
    import yaml

    jobs = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/remote-client.yml").read_text(encoding="utf-8")
    )["jobs"]
    setup_condition = jobs["android-setup"]["if"]
    for name, job in jobs.items():
        uses_wasm = any(
            "needs.android-setup.outputs.wasm" in str((step.get("with") or {}).get("wasm-artifact", ""))
            for step in (job.get("steps") or [])
        )
        if uses_wasm:
            assert "android-setup" in (job.get("needs") or []), name
            # Every output the lane is gated on must also gate the producer,
            # otherwise the lane can run while its artifact was never built.
            import re as _re

            lane_outputs = set(_re.findall(r"needs\.changes\.outputs\.([a-z_]+)", job.get("if") or ""))
            producer_outputs = set(_re.findall(r"needs\.changes\.outputs\.([a-z_]+)", setup_condition))
            assert lane_outputs, f"{name} has no lane condition"
            assert lane_outputs <= producer_outputs, (
                f"{name} can run while android-setup is skipped: {lane_outputs - producer_outputs}"
            )


def test_release_workflows_decouple_dev_preview_and_keep_stable_gate():
    import yaml

    dev_workflow = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/dev-release.yml").read_text(encoding="utf-8")
    )
    dev_jobs = dev_workflow["jobs"]
    assert "verify-ci" not in dev_jobs
    assert dev_workflow["concurrency"] == {
        "group": "dev-release",
        "cancel-in-progress": True,
    }
    assert "sign-plugin-packages" in dev_jobs
    assert "build-and-publish" in dev_jobs
    assert "verify-ci" not in (dev_jobs["build-and-publish"].get("needs") or [])

    stable_jobs = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
    )["jobs"]
    verify = stable_jobs["verify-ci"]
    # The gate must be a separate job so the build can proceed in parallel.
    assert verify["uses"].endswith("release-gate.yml")
    assert verify["with"]["required-workflow"] == "ci.yml"
    publish = next(job for job_name, job in stable_jobs.items() if job_name.startswith("publish"))
    assert "verify-ci" in publish["needs"]


def test_android_ci_caches_robolectric_runtime_jars():
    import yaml

    action = yaml.safe_load(
        (REPO_ROOT / ".github/actions/android-lane/action.yml").read_text(encoding="utf-8")
    )
    lane_cache_steps = [
        step for step in action["runs"]["steps"]
        if step.get("uses", "").startswith("actions/cache@")
        and "~/.m2/repository/org/robolectric" in ((step.get("with") or {}).get("path") or "")
    ]
    assert len(lane_cache_steps) == 1
    cache_with = lane_cache_steps[0]["with"]
    assert "apps/android/.gradle/robolectric-home/.m2/repository/org/robolectric" in cache_with["path"]
    assert "robolectric-${{ runner.os }}-${{ inputs.lane }}-" in cache_with["key"]
    assert "apps/android/**/build.gradle.kts" in cache_with["key"]

    ci_workflow = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    )
    ci_cache_steps = [
        step for step in ci_workflow["jobs"]["android"]["steps"]
        if step.get("uses", "").startswith("actions/cache@")
        and "~/.m2/repository/org/robolectric" in ((step.get("with") or {}).get("path") or "")
    ]
    assert len(ci_cache_steps) == 1
    assert "apps/android/.gradle/robolectric-home/.m2/repository/org/robolectric" in ci_cache_steps[0]["with"]["path"]


def test_ci_workflow_keeps_configuration_cache_and_parallel_forks():
    properties = (REPO_ROOT / "apps/android/gradle.properties").read_text(encoding="utf-8")
    assert "org.gradle.configuration-cache=true" in properties


@pytest.mark.parametrize("path", [
    "tools/remote/projection-scope.test.mjs",
    "tools/remote/fixtures/dsh-remote/acp/virtual-harness.ts",
    "tools/remote/fixtures/dsh-remote/source.json",
    "tools/remote/media-source.test.mjs",
    "tools/remote/fixtures/media-upstream/source.json",
    "tools/remote/fixtures/media-upstream/LICENSE",
    "tools/remote/fixtures/media-upstream/packages/plugin/src/workspace-bridge.ts",
    "tools/remote/phone-mcp-interop.ts",
    "tools/remote-interop/original-client.mjs",
    "tools/remote-interop/release-fixtures/source.json",
    "tools/remote-interop/harness-fixtures/source.json",
])
def test_acp_projection_observation_routes_to_remote_lane(impact, path):
    result = impact.classify([("M", path)])
    assert result["lanes"]["remote"]
    assert not result["unknown"]
    assert not result["full_required"]


def test_remote_android_fixture_routes_to_gateway_without_legacy_dsh(impact):
    result = impact.classify([("M", "apps/android/gateway-client/src/test/resources/dsh-remote/fixtures/crypto/v1/noise-ik.json")])
    assert result["lanes"]["android-gateway"]


def test_control_ports_remote_types_reach_gateway_and_ui_consumers(impact):
    pair = ("M", "apps/android/control-ports/src/main/kotlin/com/openandroidintelligence/control/remote/TaskScope.kt")
    pr_result = impact.classify([pair])
    pr_expected = {"fast", "remote", "android-device", "android-gateway", "android-conversation", "android-plugins", "android-notifications"}
    assert pr_expected <= active_lanes(pr_result)
    assert "android-app" not in active_lanes(pr_result)
    assert "android-play" not in active_lanes(pr_result)
    assert not pr_result["unknown"]

    full_result = impact.classify([pair], allow_app_regressions=True)
    assert pr_expected | {"android-app", "android-play"} <= active_lanes(full_result)
    assert not full_result["unknown"]


def test_phone_mcp_routes_to_remote_without_legacy_adapters(impact):
    result = impact.classify([("M", "integrations/oai-android-mcp/src/stdio.mjs")])
    assert result["lanes"]["remote"]
    assert not result["full_required"]


def test_retired_service_jobs_cannot_reenter_the_current_workflow():
    import yaml
    workflow = yaml.safe_load((REPO_ROOT / ".github/workflows/remote-client.yml").read_text())
    jobs = workflow["jobs"]
    assert not {"core", "hermes-native", "dsh"} & jobs.keys()
    assert "remote" in jobs
    assert jobs["gate"]["name"] == "CI Gate"
    assert "remote-sources" in jobs["gate"]["needs"]


def test_release_gate_defaults_to_current_remote_workflow():
    source = (REPO_ROOT / ".github/workflows/release-gate.yml").read_text()
    assert "default: remote-client.yml" in source
    assert "inputs.required-workflow || 'remote-client.yml'" in source
    assert "head_sha={sha}" in source
    assert 'endswith("/" + workflow_file)' in source


@pytest.mark.parametrize("status", ["A", "M", "D"])
@pytest.mark.parametrize("filename", ["webrtc-native-and-third-party.txt", "webrtc-LICENSE.txt", "说明.txt"])
def test_remote_license_assets_always_execute_source_integrity_gate(impact, status, filename):
    path = "apps/android/gateway-client/src/main/assets/licenses/remote/" + filename
    result = impact.classify([(status, path)])
    assert result["lanes"]["fast"]
    import yaml
    jobs = yaml.safe_load((REPO_ROOT / ".github/workflows/remote-client.yml").read_text())["jobs"]
    assert "runs_fast" in jobs["remote-sources"]["if"]
    commands = "\n".join(step.get("run", "") for step in jobs["remote-sources"]["steps"])
    assert "npm run lock:check" in commands


def test_full_ci_preserves_android_release_tool_regressions():
    import yaml
    jobs = yaml.safe_load((REPO_ROOT / ".github/workflows/ci.yml").read_text())["jobs"]
    commands = "\n".join(step.get("run", "") for job in jobs.values() for step in job.get("steps", []))
    for filename in ("test_app_release_metadata.py", "test_extract_package_version.py",
                     "test_prepare_release_notes.py", "test_bundled_plugins.py",
                     "test_sync_release_tag.py"):
        assert f"unittest discover -s apps/android/tools -p '{filename}'" in commands


@pytest.mark.parametrize("path", [
    "apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/remote/facade/PhoneControlBus.kt",
    "apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/remote/facade/PhoneVirtualHostProbe.kt",
    "apps/android/control-ports/src/main/kotlin/com/openandroidintelligence/control/remote/ControlOperation.kt",
])
def test_phone_jvm_inputs_trigger_original_tools_interop(impact, path):
    for status in ("A", "M", "D"):
        result = impact.classify([(status, path)])
        assert result["lanes"]["remote"]


def test_remote_ci_prepares_real_jvm_in_the_same_job_before_original_probe():
    import json
    import yaml
    jobs = yaml.safe_load((REPO_ROOT / ".github/workflows/remote-client.yml").read_text())["jobs"]
    steps = jobs["remote"]["steps"]
    unit = next(i for i, step in enumerate(steps) if step.get("run") == "npm run test:phone-mcp:unit")
    media = next(i for i, step in enumerate(steps) if step.get("run") == "npm run test:remote")
    interop = next(i for i, step in enumerate(steps) if step.get("with", {}).get("lane") == "phone-mcp-interop")
    assert unit < interop and media < interop
    action = yaml.safe_load((REPO_ROOT / ".github/actions/android-lane/action.yml").read_text())
    action_steps = action["runs"]["steps"]
    setup = next(i for i, step in enumerate(action_steps) if step.get("uses", "").startswith("gradle/actions/setup-gradle@"))
    run = next(i for i, step in enumerate(action_steps) if "phone-mcp-interop.ts" in step.get("run", ""))
    assert setup < run
    assert any(step.get("with", {}).get("java-version") == "21" for step in action_steps[:run])
    assert any(step.get("uses", "").startswith("android-actions/setup-android@") for step in action_steps[:run])
    scripts = json.loads((REPO_ROOT / "package.json").read_text())["scripts"]
    assert scripts["test:phone-mcp"] == "npm run test:phone-mcp:unit && npm run test:phone-mcp:interop"
    assert "media-source.test.mjs" in scripts["test:remote"]
    assert "phone-mcp-interop.test.mjs" in scripts["test:remote"]


def test_full_ci_original_tools_runs_in_android_job_after_current_checkout_build():
    import yaml
    jobs = yaml.safe_load((REPO_ROOT / ".github/workflows/ci.yml").read_text())["jobs"]
    node_commands = "\n".join(step.get("run", "") for step in jobs["gateway-and-protocol"]["steps"])
    assert "npm run test:phone-mcp:unit" in node_commands
    assert "npm run test:phone-mcp:interop" not in node_commands
    assert "npm run test:remote" in node_commands
    steps = jobs["android"]["steps"]
    build = next(i for i, step in enumerate(steps) if "./gradlew check " in step.get("run", ""))
    interop = next(i for i, step in enumerate(steps) if "npm run test:phone-mcp:interop" in step.get("run", ""))
    assert build < interop


def test_history_media_regression_is_selected_for_conversation_changes(tasks):
    sections = tasks.read_regression_sections()
    assert "RemoteHistoryMediaCacheTest" in sections["conversations"]
    assert "--tests=RemoteHistoryMediaCacheTest" in tasks.pr_args_for_sections(["conversations"])


def test_typecheck_excludes_only_pinned_typescript_and_keeps_probe_source():
    import json
    config = json.loads((REPO_ROOT / "tsconfig.json").read_text())
    assert config["include"] == ["tools/**/*.ts"]
    assert set(config["exclude"]) == {
        "tools/remote/fixtures/dsh-remote/**/*.ts",
        "tools/remote/fixtures/media-upstream/**/*.ts",
        "tools/remote-server/fixtures/**/*.ts",
        "tools/remote-interop/fixtures/**/*.ts",
        "tools/remote-interop/release-fixtures/**/*.ts",
    }


def test_android_module_lanes_contains_no_phantom_modules(impact):
    assert "control-plane" not in impact.ANDROID_MODULE_LANES
    android_root = REPO_ROOT / "apps/android"
    missing = [
        module for module in impact.ANDROID_MODULE_LANES
        if not (android_root / module).is_dir()
    ]
    assert missing == []


@pytest.mark.parametrize("path", [
    ".gitignore",
    ".gitattributes",
    ".editorconfig",
    ".env.example",
    ".nvmrc",
    "Makefile",
    "pyproject.toml",
    "vitest.config.ts",
    ".githooks/pre-commit",
    "legacy/old_script.py",
    "e2e/smoke.spec.ts",
    "tools/check_hardcoded_chinese.py",
])
def test_repo_config_and_archived_paths_route_to_fast_lane(impact, path):
    result = impact.classify([("M", path)])
    assert result["unknown"] is False
    assert result["full_required"] is False
    assert active_lanes(result) == {"fast"}


def test_remote_client_enables_app_performance_only_on_workflow_dispatch():
    import yaml

    workflow = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/remote-client.yml").read_text(encoding="utf-8")
    )
    app_pr_step = next(
        step
        for step in workflow["jobs"]["android-app"]["steps"]
        if (step.get("with") or {}).get("lane") == "app-pr"
    )
    assert app_pr_step["with"]["app-performance"] == (
        "${{ github.event_name == 'workflow_dispatch' && 'true' || 'false' }}"
    )


@pytest.mark.parametrize(
    "kwargs, expected",
    [
        # 日常 feature/fix PR：关闭耗时 App/Play 大回归
        ({"event_name": "pull_request", "base_ref": "dev", "head_ref": "feature/ui-fix", "labels": []}, False),
        ({"event_name": "pull_request", "base_ref": "dev", "head_ref": "fix/crash", "labels": ["bug"]}, False),
        # dev 分支 push（合并后触发 dev-release.yml 金丝雀预览发布）：保留 App/Play 回归验证能力
        ({"event_name": "push", "ref": "refs/heads/dev"}, True),
        # 非发布分支 push（如临时分支）：关闭耗时大回归
        ({"event_name": "push", "ref": "refs/heads/feature/demo"}, False),
        # 无上下文默认调用（命令行模拟日常 PR）：默认关闭
        ({}, False),
        # 合并/推送到 main、release* 分支或 Git Tag：放行全量 App/Play 回归
        ({"event_name": "push", "ref": "refs/heads/main"}, True),
        ({"event_name": "push", "ref": "refs/heads/release/1.2.0"}, True),
        ({"event_name": "push", "ref": "refs/heads/Release-1.2"}, True),
        ({"event_name": "push", "ref": "refs/tags/v1.0.0"}, True),
        # 发版 PR（目标或源分支为 main / release/* / release-*，忽略 refs/heads/ 前缀与大小写）：放行
        ({"event_name": "pull_request", "base_ref": "main", "head_ref": "dev", "labels": []}, True),
        ({"event_name": "pull_request", "base_ref": "dev", "head_ref": "release/1.2.0", "labels": []}, True),
        ({"event_name": "pull_request", "base_ref": "dev", "head_ref": "refs/heads/Release/2.0", "labels": []}, True),
        ({"event_name": "pull_request", "base_ref": "dev", "head_ref": "release-hotfix", "labels": []}, True),
        ({"event_name": "pull_request", "base_ref": "release/1.0", "head_ref": "fix/hotfix", "labels": []}, True),
        ({"event_name": "pull_request", "base_ref": "refs/heads/Release-1.0", "head_ref": "fix/hotfix", "labels": []}, True),
        # PR 显式打标签：放行（大小写不敏感）
        ({"event_name": "pull_request", "base_ref": "dev", "head_ref": "feature/x", "labels": ["run-full-ci"]}, True),
        ({"event_name": "pull_request", "base_ref": "dev", "head_ref": "feature/x", "labels": ["Full-Regression"]}, True),
        ({"event_name": "pull_request", "base_ref": "dev", "head_ref": "feature/x", "labels": ["full-ci"]}, True),
        ({"event_name": "pull_request", "base_ref": "dev", "head_ref": "feature/x", "labels": ["release"]}, True),
        # 手动 workflow_dispatch：放行（供性能对照与手动全量验证）
        ({"event_name": "workflow_dispatch", "ref": "refs/heads/dev"}, True),
    ],
)
def test_app_regression_trigger_policy(impact, kwargs, expected):
    assert impact.should_run_app_regressions(**kwargs) is expected


def test_impact_cli_emits_false_for_daily_pr_and_true_for_main_dev_or_label(
    impact, git_repository, capsys
):
    root, git = git_repository
    target = root / "apps/android/gateway-client/src/main/kotlin/Client.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("class Client\n", encoding="utf-8")
    git("add", "--", "apps/android/gateway-client/src/main/kotlin/Client.kt")
    git("commit", "--quiet", "-m", "测试: 模拟常规模块改动")

    # 1. 日常 feature PR：runs_android_app/play 为 false，同时启用 runs_android_app_compile 兜底编译
    assert impact.main([
        "--base", "HEAD^",
        "--head", "HEAD",
        "--event", "pull_request",
        "--base-ref", "dev",
        "--head-ref", "feature/gateway-tweak",
        "--labels-json", "[]",
        "--github-output",
    ]) == 0
    daily_out = capsys.readouterr().out
    assert "runs_fast=false" in daily_out
    assert "runs_android_gateway=true" in daily_out
    assert "runs_android_preflight=true" in daily_out
    assert "runs_android_app_compile=true" in daily_out
    assert "runs_android_app=false" in daily_out
    assert "runs_android_play=false" in daily_out
    assert "app_regressions_allowed=false" in daily_out

    # 2. 显式打标签：放行 android-app 与 android-play，关闭冗余 app_compile
    assert impact.main([
        "--base", "HEAD^",
        "--head", "HEAD",
        "--event", "pull_request",
        "--base-ref", "dev",
        "--head-ref", "feature/gateway-tweak",
        "--labels-json", '["run-full-ci"]',
        "--github-output",
    ]) == 0
    labeled_out = capsys.readouterr().out
    assert "runs_android_app=true" in labeled_out
    assert "runs_android_play=true" in labeled_out
    assert "runs_android_app_compile=false" in labeled_out
    assert "app_regressions_allowed=true" in labeled_out

    # 3. 推送到 dev 分支（金丝雀预览发布验证）：放行 android-app 与 android-play
    assert impact.main([
        "--base", "HEAD^",
        "--head", "HEAD",
        "--event", "push",
        "--ref", "refs/heads/dev",
        "--labels-json", "null",
        "--github-output",
    ]) == 0
    dev_out = capsys.readouterr().out
    assert "runs_android_app=true" in dev_out
    assert "runs_android_play=true" in dev_out
    assert "runs_android_app_compile=false" in dev_out
    assert "app_regressions_allowed=true" in dev_out

    # 4. 合并到 main 分支：放行 android-app 与 android-play
    assert impact.main([
        "--base", "HEAD^",
        "--head", "HEAD",
        "--event", "push",
        "--ref", "refs/heads/main",
        "--labels-json", "null",
        "--github-output",
    ]) == 0
    main_out = capsys.readouterr().out
    assert "runs_android_app=true" in main_out
    assert "runs_android_play=true" in main_out
    assert "runs_android_app_compile=false" in main_out
    assert "app_regressions_allowed=true" in main_out

    # 5. --unknown-diff：日常 PR 展开全部非 App lane + app_compile；workflow_dispatch 展开全量
    assert impact.main([
        "--unknown-diff",
        "--event", "pull_request",
        "--base-ref", "dev",
        "--head-ref", "feature/no-base",
        "--labels-json", "[]",
        "--github-output",
    ]) == 0
    unknown_pr_out = capsys.readouterr().out
    assert "full_required=true" in unknown_pr_out
    assert "unknown_diff=true" in unknown_pr_out
    assert "runs_android_preflight=true" in unknown_pr_out
    assert "runs_android_app_compile=true" in unknown_pr_out
    assert "runs_android_app=false" in unknown_pr_out
    assert "runs_android_play=false" in unknown_pr_out

    assert impact.main([
        "--unknown-diff",
        "--event", "workflow_dispatch",
        "--ref", "refs/heads/dev",
        "--labels-json", "null",
        "--github-output",
    ]) == 0
    unknown_dispatch_out = capsys.readouterr().out
    assert "full_required=true" in unknown_dispatch_out
    assert "runs_android_app=true" in unknown_dispatch_out
    assert "runs_android_play=true" in unknown_dispatch_out
    assert "runs_android_app_compile=false" in unknown_dispatch_out
    assert "app_regressions_allowed=true" in unknown_dispatch_out


def test_app_only_daily_pr_still_runs_preflight_and_app_compile_without_polluting_fast(impact):
    """只改 app/ 主源码的日常 PR 关闭 App 大回归时，须跑安全预检与 :app 轻量编译，且不污染本地 fast 语义。"""
    result = impact.classify([
        ("M", "apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteRuntime.kt"),
    ])
    assert result["lanes"]["fast"] is False
    assert result["lanes"]["android-app"] is False
    assert result["lanes"]["android-play"] is False
    assert result["android_preflight"] is True
    assert result["android_app_compile"] is True
    output = impact.to_github_output(result)
    assert "runs_android_preflight=true" in output
    assert "runs_android_app_compile=true" in output
    assert "any_lane=true" in output


def test_remote_client_concurrency_and_app_gate_wiring():
    import yaml

    workflow = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/remote-client.yml").read_text(encoding="utf-8")
    )
    # 1. 并发策略：PR/全量标签变更立即取消旧运行，普通标签落入 label-noop 不打断进行中的 CI，push 不取消
    concurrency = workflow["concurrency"]
    assert "github.event.pull_request.number" in concurrency["group"]
    assert "label-noop-" in concurrency["group"]
    assert "github.run_id" in concurrency["group"]
    assert concurrency["cancel-in-progress"] == "${{ github.event_name == 'pull_request' }}"

    # 2. PR 事件需监听 labeled 与 unlabeled，且同时覆盖 release/* 分支
    triggers = workflow[True] if True in workflow else workflow["on"]
    pr_trigger = triggers["pull_request"]
    assert {"labeled", "unlabeled"} <= set(pr_trigger["types"])
    assert "release/*" in pr_trigger["branches"]
    assert "release/*" in triggers["push"]["branches"]

    # 3. changes 与 gate 屏蔽普通标签增删，避免浪费 runner 或干扰正在运行的 PR CI
    jobs = workflow["jobs"]
    assert "github.event.label.name" in (jobs["changes"].get("if") or "")
    assert "github.event.label.name" in (jobs["gate"].get("if") or "")
    assert (jobs["gate"].get("if") or "").startswith("always()")

    # 4. android-preflight 收敛为单一开关；fast 在 CI 侧并行覆盖 Android 变更
    assert jobs["android-preflight"]["if"].strip() == "needs.changes.outputs.runs_android_preflight == 'true'"
    assert "runs_android_preflight" in jobs["fast"]["if"]
    assert "runs_android_preflight" in jobs["remote-sources"]["if"]

    # 5. android-app-compile 兜底日常 PR 的 :app 编译且不依赖 android-setup（WASM）
    assert "runs_android_app_compile" in jobs["changes"]["outputs"]
    compile_job = jobs["android-app-compile"]
    assert compile_job["if"].strip() == "needs.changes.outputs.runs_android_app_compile == 'true'"
    assert compile_job["needs"] == "changes"
    assert any((step.get("with") or {}).get("lane") == "app-compile" for step in compile_job["steps"])

    # 6. android-app / android-play 须双重受控于 runs_android_* 与 app_regressions_allowed
    assert "app_regressions_allowed" in jobs["changes"]["outputs"]
    assert "runs_android_preflight" in jobs["changes"]["outputs"]
    for job_id, run_flag in (("android-app", "runs_android_app"), ("android-play", "runs_android_play")):
        cond = jobs[job_id]["if"]
        assert f"needs.changes.outputs.{run_flag} == 'true'" in cond
        assert "needs.changes.outputs.app_regressions_allowed == 'true'" in cond

    # 7. 汇总状态检查名保持不变，避免破坏分支保护规则
    assert jobs["gate"]["name"] == "CI Gate"


def test_android_apk_workflow_does_not_duplicate_pr_branch_compilations():
    """日常 PR 分支推送不得再触发 android-apk.yml 整包 APK 编译，仅保留 main 与手动触发。"""
    import yaml

    workflow = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/android-apk.yml").read_text(encoding="utf-8")
    )
    triggers = workflow[True] if True in workflow else workflow["on"]
    assert "pull_request" not in triggers
    assert triggers["push"] == {"branches": ["main"]}
    assert "workflow_dispatch" in triggers
    assert workflow["concurrency"]["cancel-in-progress"] == "${{ github.ref != 'refs/heads/main' }}"


def test_ci_skips_draft_prs_and_robolectric_cache_persists_on_failure():
    """Draft PR 跳过 main 级全量 ci.yml（转 Ready 时自动触发），且 Robolectric 缓存开启 save-always。"""
    import yaml

    ci_workflow = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    )
    triggers = ci_workflow[True] if True in ci_workflow else ci_workflow["on"]
    assert "ready_for_review" in set(triggers["pull_request"]["types"])
    for job_id in ("gateway-and-protocol", "android"):
        cond = ci_workflow["jobs"][job_id].get("if") or ""
        assert "github.event.pull_request.draft" in cond

    ci_cache_steps = [
        step for step in ci_workflow["jobs"]["android"]["steps"]
        if step.get("uses", "").startswith("actions/cache@")
        and "~/.m2/repository/org/robolectric" in ((step.get("with") or {}).get("path") or "")
    ]
    assert len(ci_cache_steps) == 1
    assert ci_cache_steps[0]["with"].get("save-always") is True

    action = yaml.safe_load(
        (REPO_ROOT / ".github/actions/android-lane/action.yml").read_text(encoding="utf-8")
    )
    lane_cache_steps = [
        step for step in action["runs"]["steps"]
        if step.get("uses", "").startswith("actions/cache@")
        and "~/.m2/repository/org/robolectric" in ((step.get("with") or {}).get("path") or "")
    ]
    assert len(lane_cache_steps) == 1
    assert lane_cache_steps[0]["with"].get("save-always") is True
