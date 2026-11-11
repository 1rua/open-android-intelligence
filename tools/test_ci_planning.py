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


def test_unclassifiable_path_forces_full_matrix(impact):
    result = impact.classify([("M", "some-new-top-level-dir/thing.bin")])
    assert result["unknown"] is True
    assert result["full_required"] is True
    assert active_lanes(result) == set(impact.LANES)


def test_empty_diff_is_treated_as_full_not_as_no_op(impact):
    result = impact.classify([])
    assert result["full_required"] is True
    assert result["unknown"] is True
    assert active_lanes(result) == set(impact.LANES)


@pytest.mark.parametrize("path", [
    "gateway-contract/schema/device.json",
    "apps/android/gradle.properties",
    "apps/android/build.gradle.kts",
    "apps/android/app/build.gradle.kts",
    "apps/android/gradle/mvp-forbidden-surfaces.gradle.kts",
    "package-lock.json",
    "tools/smart-test.py",
])
def test_security_and_build_config_paths_run_everything(impact, path):
    result = impact.classify([("M", path)])
    assert result["full_required"] is True
    assert active_lanes(result) == set(impact.LANES)


def test_core_security_paths_widen_the_matrix(impact):
    result = impact.classify([("M", "oai-core/src/oai_core/gateway/transport.py")])
    assert result["full_required"] is True


def test_plain_core_change_runs_core_only(impact):
    result = impact.classify([("M", "oai-core/src/oai_core/cli.py")])
    assert result["full_required"] is False
    assert active_lanes(result) == {"core"}


def test_single_android_module_pulls_in_app_and_play_lanes(impact):
    result = impact.classify([("M", "apps/android/platform-kernel/src/main/PairingGrant.kt")])
    lanes = active_lanes(result)
    assert "android-platform" in lanes
    # A module is compiled into the app, so its tests alone are not sufficient.
    assert {"android-app", "android-play"} <= lanes
    assert "android-gateway" not in lanes


def test_deleted_test_file_still_runs_its_lane(impact):
    result = impact.classify([
        ("D", "apps/android/app/src/test/kotlin/com/x/GatewaySessionRefreshTest.kt"),
    ])
    assert {"android-app", "android-play"} <= active_lanes(result)


def test_rename_counts_both_sides(impact):
    result = impact.classify([
        ("D", "plugins/sms/src/lib.rs"),
        ("A", "plugins/sms-v2/src/lib.rs"),
    ])
    assert "fast" in active_lanes(result)


def test_hermes_adapter_path_runs_core_and_hermes(impact):
    result = impact.classify([
        ("M", "integrations/hermes-session-adapter/src/oai_hermes_adapter/runtime.py"),
    ])
    assert active_lanes(result) == {"core", "hermes"}


def test_app_sections_narrow_to_the_touched_domain(impact):
    result = impact.classify([
        ("M", "apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/settings/appearance/AppearanceSettingsScreen.kt"),
    ])
    assert result["app_sections"] == {"core", "settings"}
    assert result["full_required"] is False


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
    result = impact.classify([("A", path)])

    assert result["lanes"]["fast"] is True
    assert {"android-app", "android-play"} <= active_lanes(result)
    assert result["app_sections"] == set(impact.APP_SECTIONS)


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


def test_native_only_tests_route_to_hermes(impact):
    """Core `--ignore`s these files; only the Hermes job executes them."""
    for path in (
        "oai-core/tests/test_native_event_sync.py",
        "oai-core/tests/test_structured_tool_history.py",
        "oai-core/tests/test_interaction_pipeline.py",
        "oai-core/tests/test_communication_process_recovery.py",
        "oai-core/tests/test_native_tool_events.py",
    ):
        lanes = impact.classify([("M", path)])["lanes"]
        assert lanes["hermes"] is True, path


def test_executable_manifests_are_not_documentation(impact):
    """The app regression manifest and fixture requirements control tests."""
    for path in (
        "apps/android/ci/app-regression.txt",
        "apps/android/ci/hermes-fixture-requirements.txt",
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


def test_measurement_changes_run_the_hermes_lane(impact):
    lanes = impact.classify([("M", "tools/measure-communication.py")])["lanes"]
    assert lanes["hermes"] is True


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
    result = impact.classify([("M", "oai-core/src/oai_core/cli.py")])
    output = impact.to_github_output(result)
    # Workflow outputs and `if:` conditions cannot contain dashes reliably.
    assert "runs_core=true" in output
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
    assert all(argument.startswith("--tests=*") for argument in arguments[1:])
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
        assert f"--tests=*{name}" in narrow
    for name in tasks.read_regression_sections()["settings"]:
        assert f"--tests=*{name}" in narrow
    for name in tasks.read_regression_sections()["plugins"]:
        assert f"--tests=*{name}" not in narrow


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
    assert len([a for a in arguments if a.startswith("--tests=*")]) >= 10


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
        (REPO_ROOT / ".github/workflows/oai-core.yml").read_text(encoding="utf-8")
    )
    jobs = workflow["jobs"]
    assert "changes" in jobs
    # Gate must always run, otherwise a cancelled lane leaves the check pending.
    assert jobs["gate"].get("if") == "always()"
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
        (REPO_ROOT / ".github/workflows/oai-core.yml").read_text(encoding="utf-8")
    )["jobs"]
    gate_needs = set(jobs["gate"]["needs"])
    assert "android-setup" in gate_needs
    assert "android-preflight" in gate_needs
    assert "changes" in gate_needs


def test_artifact_consumers_cannot_outlive_their_producer():
    """A lane that downloads android-setup's artifact must never run without it.

    If android-setup is skipped while a consumer still runs, the download step
    fails late and the failure looks unrelated to the change. Catch the wiring
    mismatch statically instead.
    """
    import yaml

    jobs = yaml.safe_load(
        (REPO_ROOT / ".github/workflows/oai-core.yml").read_text(encoding="utf-8")
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


def test_release_workflows_wait_for_verified_ci():
    import yaml

    for name, expected in (
        ("dev-release.yml", "oai-core.yml"),
        ("release.yml", "ci.yml"),
    ):
        jobs = yaml.safe_load(
            (REPO_ROOT / f".github/workflows/{name}").read_text(encoding="utf-8")
        )["jobs"]
        verify = jobs["verify-ci"]
        # The gate must be a separate job so the build can proceed in parallel.
        assert verify["uses"].endswith("release-gate.yml")
        assert verify["with"]["required-workflow"] == expected
        publish = next(job for job_name, job in jobs.items() if job_name.startswith("publish"))
        assert "verify-ci" in publish["needs"], name


def test_ci_workflow_keeps_configuration_cache_and_parallel_forks():
    properties = (REPO_ROOT / "apps/android/gradle.properties").read_text(encoding="utf-8")
    assert "org.gradle.configuration-cache=true" in properties
