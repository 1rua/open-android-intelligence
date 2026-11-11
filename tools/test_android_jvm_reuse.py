"""验证共享进程的调度边界，不能通过误过滤减少实际测试。"""

import importlib.util
from pathlib import Path

import pytest


def planner():
    path = Path(__file__).parent / "ci/android_tasks.py"
    spec = importlib.util.spec_from_file_location("android_tasks_reuse", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_normal_plan_keeps_process_isolation():
    tasks = planner()
    arguments = tasks.pr_args_for_sections(None)
    assert not any("ReusableAppTestGroup" in argument or "testReuseClasses" in argument for argument in arguments)
    assert len(arguments) - 1 == len(tasks.read_regression_manifest())
    assert all(argument[8:][0].isupper() for argument in arguments[1:])
    assert all("*" not in argument for argument in arguments[1:])


def test_reuse_plan_keeps_every_original_test_filter():
    tasks = planner()
    baseline = tasks.pr_args_for_sections(None)
    reused = tasks.pr_args_for_sections(None, jvm_reuse=True, seed=135)
    assert reused[:len(baseline)] == baseline
    classes = tasks.read_jvm_reuse_manifest()
    assert f"-Poai.testReuseClasses={','.join(classes)}" in reused
    groups = {tasks.jvm_reuse_group(name) for name in classes}
    assert {argument for argument in reused if "ReusableAppTestGroup" in argument} == {
        f"--tests={tasks.JVM_REUSE_GROUP_PREFIX}{group}" for group in groups
    }


def test_section_plan_reuses_only_selected_tests():
    tasks = planner()
    baseline = tasks.pr_args_for_sections(["core"])
    reused = tasks.pr_args_for_sections(["core"], jvm_reuse=True)
    property_value = next(argument.split("=", 1)[1] for argument in reused if argument.startswith("-Poai.testReuseClasses="))
    for name in property_value.split(","):
        assert f"--tests={name.rsplit('.', 1)[-1]}" in baseline


@pytest.mark.parametrize("content", ["", "java.lang.String\n", "com.openandroidintelligence.mobile.MissingTest\n"])
def test_invalid_allowlist_fails_closed(tmp_path, content):
    tasks = planner()
    tasks.JVM_REUSE_MANIFEST = tmp_path / "reuse.txt"
    tasks.JVM_REUSE_MANIFEST.write_text(content, encoding="utf-8")
    with pytest.raises(SystemExit):
        tasks.pr_args_for_sections(None, jvm_reuse=True)


def test_duplicate_allowlist_fails_closed(tmp_path):
    tasks = planner()
    name = tasks.read_jvm_reuse_manifest()[0]
    tasks.JVM_REUSE_MANIFEST = tmp_path / "reuse.txt"
    tasks.JVM_REUSE_MANIFEST.write_text(f"{name}\n{name}\n", encoding="utf-8")
    with pytest.raises(SystemExit):
        tasks.read_jvm_reuse_manifest()


def test_reuse_configuration_changes_run_full_safety_matrix():
    path = Path(__file__).parent / "ci/impact.py"
    spec = importlib.util.spec_from_file_location("impact_reuse", path)
    impact = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(impact)
    for name in [
        "apps/android/ci/app-jvm-reuse.txt",
        "tools/ci/android_app_performance.py",
        "tools/test_android_jvm_reuse.py",
    ]:
        result = impact.classify([("M", name)])
        assert result["full_required"] is True, name
        assert all(result["lanes"].values()), name
