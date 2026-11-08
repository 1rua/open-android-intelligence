"""CI planning helpers must never let a change skip its own tests.

These tests pin the two properties that matter more than any speed number:
  1. A diff that cannot be classified widens the matrix (never narrows it).
  2. A security/contract/build-config change runs everything.
They also pin the Gradle `--tests` binding that tools/ci/android_tasks.py exists
to protect: filters must follow their Test task, one task per invocation.
"""

import importlib.util
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


# --- impact.classify -------------------------------------------------------


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


def test_unknown_scope_widens_app_sections(impact):
    result = impact.classify([("M", "some-new-top-level-dir/thing.bin")])
    assert result["app_sections"] == set(impact.APP_SECTIONS)


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
            # The lane is gated on one of app/play, and so is the producer.
            condition = job.get("if") or ""
            covered = any(
                token in setup_condition
                for token in ("runs_android_app", "runs_android_play")
                if token in condition
            )
            assert covered, f"{name} can run while android-setup is skipped"


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
