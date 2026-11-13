"""An Adapter-only edit must actually reach its optional-host regressions."""
import ast
import importlib.util
import shlex
from pathlib import Path

import pytest
import yaml


def load_runner():
    spec = importlib.util.spec_from_file_location("smart_test", Path(__file__).with_name("smart-test.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def mock_hermes_sdk(runner, monkeypatch, available):
    sdk_root = Path(__file__).with_name("mock-hermes-sdk")
    original_is_dir = runner.Path.is_dir

    def is_dir(path):
        if path == sdk_root:
            return available
        if path.name == "hermes-sdk-75b083e":
            return False
        return original_is_dir(path)

    if available:
        monkeypatch.setenv("HERMES_SDK_ROOT", str(sdk_root))
    else:
        monkeypatch.delenv("HERMES_SDK_ROOT", raising=False)
    monkeypatch.setattr(runner.Path, "is_dir", is_dir)
    return sdk_root


def adapter_dependent_tests():
    root = Path(__file__).parents[1]
    return [
        path.relative_to(root).as_posix()
        for path in sorted((root / "oai-core/tests").glob("test_*.py"))
        if any(
            (
                isinstance(node, ast.ImportFrom)
                and (node.module or "").split(".")[0] == "oai_hermes_adapter"
            ) or (
                isinstance(node, ast.Import)
                and any(alias.name.split(".")[0] == "oai_hermes_adapter" for alias in node.names)
            )
            for node in ast.parse(path.read_text(encoding="utf-8")).body
        )
    ]


@pytest.mark.parametrize("test_file", adapter_dependent_tests())
def test_ci_routes_adapter_dependent_tests_to_native_job(test_file):
    workflow_path = Path(__file__).parents[1] / ".github/workflows/oai-core.yml"
    jobs = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))["jobs"]
    core_steps = "\n".join(step.get("run", "") for step in jobs["core"]["steps"])
    native_steps = "\n".join(step.get("run", "") for step in jobs["hermes-native"]["steps"])

    assert f"--ignore={test_file}" in shlex.split(core_steps)
    assert test_file in shlex.split(native_steps)


@pytest.mark.parametrize("level", ["fast", "pr", "full"])
@pytest.mark.parametrize("test_file", adapter_dependent_tests())
def test_runner_routes_adapter_dependent_tests_and_propagates_failure(test_file, level, monkeypatch):
    runner = load_runner()
    commands = []

    def execute(command, **kwargs):
        commands.append(command)
        return 19 if test_file in command else 0

    runner.run_command = execute
    # Command selection is mocked; use an existing directory without requiring an installed SDK.
    monkeypatch.setenv("HERMES_SDK_ROOT", str(Path(__file__).parent))
    impact = runner.analyze_impact([test_file])
    assert runner.execute_strategy(level, impact) == 19
    core = next(command for command in commands if "oai-core/tests" in command)
    assert f"--ignore={test_file}" in core
    assert test_file in commands[-1]


@pytest.mark.parametrize("sdk_available", [False, True], ids=["sdk-missing", "sdk-present"])
def test_adapter_only_edits_execute_native_suite_and_propagate_failure(sdk_available, monkeypatch):
    runner = load_runner()
    sdk_root = mock_hermes_sdk(runner, monkeypatch, sdk_available)
    commands = []
    command_envs = []
    sdk_conditional_tests = {
        "oai-core/tests/test_structured_tool_history.py",
        "oai-core/tests/test_native_tool_events.py",
        "oai-core/tests/test_native_event_sync.py",
    }
    failing_test = (
        "oai-core/tests/test_native_tool_events.py"
        if sdk_available
        else "oai-core/tests/test_hermes_native_origin.py"
    )

    def execute(command, **kwargs):
        commands.append(command)
        command_envs.append(kwargs.get("env"))
        return 7 if failing_test in command else 0

    runner.run_command = execute
    impact = runner.analyze_impact(["integrations/hermes-session-adapter/src/oai_hermes_adapter/tool_events.py"])
    assert runner.execute_strategy("fast", impact) == 7
    assert len(commands) == 2
    assert commands[0] == ["npm", "run", "mvp:lock:check"]
    selected_tests = {argument for argument in commands[-1] if argument.startswith("oai-core/tests/")}
    assert "oai-core/tests/test_hermes_native_origin.py" in selected_tests
    if sdk_available:
        assert sdk_conditional_tests <= selected_tests
        assert command_envs[-1]["HERMES_SDK_ROOT"] == str(sdk_root)
        assert str(sdk_root) in command_envs[-1]["PYTHONPATH"].split(":")
    else:
        assert sdk_conditional_tests.isdisjoint(selected_tests)
        assert "HERMES_SDK_ROOT" not in command_envs[-1]


@pytest.mark.parametrize("level", ["fast", "pr", "full"])
def test_standalone_core_suite_does_not_collect_optional_adapter_tests(level):
    runner = load_runner()
    commands = []

    def execute(command, **kwargs):
        commands.append(command)
        return 9 if "oai-core/tests" in command else 0

    runner.run_command = execute
    impact = runner.analyze_impact(["oai-core/src/oai_core/sessions.py"])
    assert runner.execute_strategy(level, impact) == 9
    command = commands[-1]
    assert "tests/test_uninstall_script.py" in command
    assert "tools/test_smart_test.py" in command
    assert "--ignore=oai-core/tests/test_structured_tool_history.py" in command
    assert "--ignore=oai-core/tests/test_native_tool_events.py" in command
    assert "--ignore=oai-core/tests/test_native_event_sync.py" in command
    assert "--ignore=oai-core/tests/test_hermes_native_origin.py" in command


@pytest.mark.parametrize("level", ["fast", "pr"])
@pytest.mark.parametrize("changed_file", [
    "scripts/core_setup.py",
    "scripts/setup.sh",
    "scripts/install.sh",
    "scripts/uninstall.sh",
    "tests/test_install_script.py",
    "tests/test_uninstall_script.py",
    "tests/test_core_setup_transaction.py",
    "tools/smart-test.py",
    "tools/test_smart_test.py",
    ".github/workflows/oai-core.yml",
])
def test_configuration_only_edits_run_configuration_fault_regressions(changed_file, level):
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 0
    impact = runner.analyze_impact([changed_file])
    assert impact["affects_configuration"]
    assert not impact["is_full_required"]
    assert runner.execute_strategy(level, impact) == 0
    command = next(command for command in commands if "tests/test_core_setup_transaction.py" in command)
    assert "tests/test_install_script.py" in command
    assert "tests/test_uninstall_script.py" in command
    assert "oai-core/tests/test_core_config.py" in command
    assert "tools/test_smart_test.py" in command
    assert not any("oai-core/tests" in command for command in commands)


@pytest.mark.parametrize("changed_file", [
    "scripts/uninstall.sh",
    "tests/test_uninstall_script.py",
    "tools/smart-test.py",
    "tools/test_smart_test.py",
])
def test_uninstall_and_runner_edits_propagate_regression_failures(changed_file):
    runner = load_runner()
    commands = []

    def execute(command, **kwargs):
        commands.append(command)
        return 11 if "tests/test_uninstall_script.py" in command else 0

    runner.run_command = execute
    impact = runner.analyze_impact([changed_file])
    assert runner.execute_strategy("fast", impact) == 11
    assert len(commands) == 2
    assert "tools/test_smart_test.py" in commands[-1]


@pytest.mark.parametrize("changed_file", [
    "apps/android/app/src/main/kotlin/example/settings/advanced/BuildDetailsScreen.kt",
    "apps/android/app/src/main/kotlin/example/SettingsScreen.kt",
])
def test_settings_renames_keep_ui_and_pr_indirect_dependencies(changed_file):
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 0
    impact = runner.analyze_impact([changed_file])
    assert impact["affects_android_settings"]
    assert runner.execute_strategy("full", impact) == 0
    # Full regression is split into a module invocation and an app-variant
    # invocation (Gradle binds --tests to a single Test task per invocation),
    # so the tasks are checked across all Gradle calls.
    gradle_commands = [command for command in commands if "./gradlew" in command]
    assert gradle_commands
    android = [
        argument
        for command in gradle_commands
        for argument in command
        if argument != "./gradlew"
    ]
    for task in (":app:testFullDebugUnitTest", ":app:testPlayDebugUnitTest",
                 ":platform-kernel:testDebugUnitTest", ":notification-host:testDebugUnitTest", ":sms-collector:testDebugUnitTest"):
        assert task in android
    assert len(android) == len(set(android))


def test_settings_pr_indirect_failure_propagates():
    runner = load_runner()
    runner.run_command = lambda command, **kwargs: 13 if ":notification-host:testDebugUnitTest" in command else 0
    impact = runner.analyze_impact(["apps/android/app/src/main/kotlin/example/settings/SettingsNavHost.kt"])
    assert runner.execute_strategy("full", impact) == 13


def test_kernel_edits_include_kernel_regressions():
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 0
    impact = runner.analyze_impact(["apps/android/platform-kernel/src/main/PairingGrantState.kt"])
    assert runner.execute_strategy("pr", impact) == 0
    assert any(":platform-kernel:testDebugUnitTest" in command for command in commands)


def test_communication_repairs_reach_admission_and_optional_host_regressions():
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 0
    impact = runner.analyze_impact(["oai-core/src/oai_core/sessions.py", "integrations/hermes-session-adapter/src/oai_hermes_adapter/runtime.py", "integrations/dsh-session-adapter/index.js"])
    assert runner.execute_strategy("pr", impact) == 0
    host = next(command for command in commands if "oai-core/tests/test_hermes.py" in command)
    assert "oai-core/tests/test_hermes_communication_reliability.py" in host
    assert "oai-core/tests/test_communication_process_recovery.py" in host
    assert any("integrations/dsh-session-adapter/vitest.config.js" in command for command in commands)


def test_measurement_only_edit_runs_its_portable_tests_without_android_or_native_suite():
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 0
    impact = runner.analyze_impact(["tools/measure-communication.py"])
    assert runner.execute_strategy("fast", impact) == 0
    assert any("tools/test_measure_communication.py" in command for command in commands)
    assert not any("oai-core/tests" in command or "oai-core/tests/test_hermes.py" in command for command in commands)


@pytest.mark.parametrize("level", ["fast", "pr"])
@pytest.mark.parametrize("changed_file", [
    "plugins/sms/src/lib.rs",
    "plugins/sms/manifest.json",
    "plugins/schemas/sms.json",
    "plugin-tooling/src/manifest.ts",
    "plugin-tooling/src/reference-build-policy.ts",
    "plugin-tooling/src/bundled-plugins.ts",
    "plugin-tooling/src/build-references.ts",
])
def test_plugin_edits_run_plugin_tooling_tests_after_the_root_lock_gate(changed_file, level):
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 0

    impact = runner.analyze_impact([changed_file])
    assert impact["affects_plugins"]
    assert runner.execute_strategy(level, impact) == 0

    assert commands[0] == ["npm", "run", "mvp:lock:check"]
    assert ["npm", "test", "--prefix", "plugin-tooling"] in commands


@pytest.mark.parametrize("level", ["fast", "pr"])
def test_plugin_tooling_test_failure_is_returned_to_the_caller(level):
    runner = load_runner()
    commands = []

    def execute(command, **kwargs):
        commands.append(command)
        return 17 if command == ["npm", "test", "--prefix", "plugin-tooling"] else 0

    runner.run_command = execute
    impact = runner.analyze_impact(["plugin-tooling/src/bundled-plugins.ts"])

    assert runner.execute_strategy(level, impact) == 17
    assert commands == [
        ["npm", "run", "mvp:lock:check"],
        ["npm", "test", "--prefix", "plugin-tooling"],
    ]


def test_combined_plugin_and_android_edits_run_both_relevant_suites():
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 0
    impact = runner.analyze_impact([
        "plugins/screen-control/manifest.json",
        "apps/android/platform-kernel/src/main/PairingGrantState.kt",
    ])

    assert runner.execute_strategy("pr", impact) == 0
    assert ["npm", "test", "--prefix", "plugin-tooling"] in commands
    assert any(":platform-kernel:testDebugUnitTest" in command for command in commands)


def test_unrelated_android_edit_does_not_run_plugin_tests():
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 0
    impact = runner.analyze_impact(["apps/android/app/src/main/SettingsViewModel.kt"])

    assert runner.execute_strategy("pr", impact) == 0
    assert any(":app:testFullDebugUnitTest" in command for command in commands)
    assert ["npm", "test", "--prefix", "plugin-tooling"] not in commands


def test_full_level_always_runs_plugin_tests_without_plugin_changes():
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 0
    impact = runner.analyze_impact(["README.md"])

    assert runner.execute_strategy("full", impact) == 0
    assert ["npm", "test", "--prefix", "plugin-tooling"] in commands


def test_android_ci_covers_connection_account_and_settings_regressions():
    """The PR Android gate must still select these behaviour regressions.

    The workflow no longer has one monolithic `android` job, so this asserts the
    property that mattered rather than a specific step name: every regression
    that guards connection recovery, account switching and settings semantics is
    still selected by some shard, and the shards carry full-variant filters only.
    """
    workflow_path = Path(__file__).parents[1] / ".github/workflows/oai-core.yml"
    workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
    jobs = workflow["jobs"]

    # One app job compiles the variant once; splitting it would duplicate the
    # dominant compile cost (measured on CI: ~3 min of tests inside ~9-15 min of
    # wall time per shard job).
    assert "android-app" in jobs
    assert "android-app-a" not in jobs and "android-app-b" not in jobs

    manifest = (
        Path(__file__).parents[1] / "apps/android/ci/app-regression.txt"
    ).read_text(encoding="utf-8")
    selected = {
        line.strip()
        for line in manifest.splitlines()
        if line.strip() and not line.strip().startswith(("#", "["))
    }
    required = {
        "ColdStartConnectionTest",
        "AccountSwitchPersistenceFailureTest",
        "IndependentConnectionDiscoveryTest",
        "AdditionalDeviceConnectionsTest",
        "SettingsViewModelTest",
        "NativeEventDeletionPersistenceTest",
    }
    assert required <= selected

    # The Play variant keeps its own lane with its own variant-specific filters.
    planner = importlib.util.spec_from_file_location(
        "android_tasks", Path(__file__).parents[1] / "tools/ci/android_tasks.py"
    )
    android_tasks = importlib.util.module_from_spec(planner)
    planner.loader.exec_module(android_tasks)
    play_arguments = android_tasks.play_args()
    assert play_arguments[0] == ":app:testPlayDebugUnitTest"
    assert any("ColdStartConnectionTest" in argument for argument in play_arguments)


def test_observer_only_edits_run_the_readonly_tool_regression(monkeypatch):
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 0
    impact = runner.analyze_impact(["tools/observe-communication.py"])
    assert runner.execute_strategy("fast", impact) == 0
    assert any("tools/test_observe_communication.py" in command for command in commands)


@pytest.mark.parametrize("changed", ["tools/audit-communication.py", "tools/test_audit_communication.py"])
def test_observation_audit_edits_run_the_offline_regression(monkeypatch, changed):
    runner = load_runner()
    commands = []
    monkeypatch.setattr(runner, "run_command", lambda command, **_kwargs: commands.append(command) or 0)
    impact = runner.analyze_impact([changed])
    assert impact["affects_observer"] is True
    assert runner.execute_strategy("fast", impact) == 0
    assert any("tools/test_audit_communication.py" in command for command in commands)



@pytest.mark.parametrize("path", ["docs/plans/dsh-remote.md", "docs/reviews/hermes-remote.md"])
def test_remote_documents_do_not_trigger_legacy_adapters(path):
    runner = load_runner()
    impact = runner.analyze_impact([path])
    assert not impact["affects_dsh"]
    assert not impact["affects_hermes"]
    assert not impact["affects_remote"]


@pytest.mark.parametrize("level", ["fast", "pr", "full"])
def test_remote_probe_runs_and_propagates_failure(level):
    runner = load_runner()
    commands = []

    def execute(command, **kwargs):
        commands.append(command)
        return 23 if "tools/remote/target-binding.test.mjs" in command else 0

    runner.run_command = execute
    impact = runner.analyze_impact(["tools/remote/target-binding.test.mjs"])
    assert runner.execute_strategy(level, impact) == 23
    assert commands[-1] == ["node", "--experimental-vm-modules", "--test", "tools/remote/target-binding.test.mjs"]
    assert not any("integrations/dsh-session-adapter/vitest.config.js" in command for command in commands)
