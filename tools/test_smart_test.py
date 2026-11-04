"""An Adapter-only edit must actually reach its optional-host regressions."""
import importlib.util
from pathlib import Path

import pytest


def load_runner():
    spec = importlib.util.spec_from_file_location("smart_test", Path(__file__).with_name("smart-test.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_adapter_only_edits_execute_native_suite_and_propagate_failure():
    runner = load_runner()
    commands = []

    def execute(command, **kwargs):
        commands.append(command)
        return 7 if "oai-core/tests/test_native_tool_events.py" in command else 0

    runner.run_command = execute
    impact = runner.analyze_impact(["integrations/hermes-session-adapter/src/oai_hermes_adapter/tool_events.py"])
    assert runner.execute_strategy("fast", impact) == 7
    assert len(commands) == 2
    assert "oai-core/tests/test_structured_tool_history.py" in commands[-1]
    assert "oai-core/tests/test_native_event_sync.py" in commands[-1]
    assert "oai-core/tests/test_hermes_native_origin.py" in commands[-1]
    assert not any("oai-core/tests" in command for command in commands)


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
