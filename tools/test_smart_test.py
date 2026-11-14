"""验证 Remote、手机和 Android 的测试选路与失败传播。"""
import importlib.util
from pathlib import Path

import pytest
import yaml


def load_runner():
    spec = importlib.util.spec_from_file_location("smart_test", Path(__file__).with_name("smart-test.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


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

    assert commands[0] == ["npm", "run", "lock:check"]
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
    assert commands[0] == ["npm", "run", "lock:check"]
    assert commands[-1] == ["npm", "test", "--prefix", "plugin-tooling"]


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
    workflow_path = Path(__file__).parents[1] / ".github/workflows/remote-client.yml"
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


@pytest.mark.parametrize("path", ["docs/plans/dsh-remote.md", "docs/reviews/hermes-remote.md"])
def test_remote_documents_do_not_trigger_legacy_adapters(path):
    runner = load_runner()
    impact = runner.analyze_impact([path])
    assert not impact["affects_remote"]


@pytest.mark.parametrize("level", ["fast", "pr", "full"])
def test_acp_projection_observation_runs_and_propagates_failure(level):
    runner = load_runner()
    commands = []

    def execute(command, **kwargs):
        commands.append(command)
        return 23 if "tools/remote/projection-scope.test.mjs" in command else 0

    runner.run_command = execute
    impact = runner.analyze_impact(["tools/remote/projection-scope.test.mjs"])
    assert runner.execute_strategy(level, impact) == 23
    assert commands[-1] == [
        "node", "--experimental-vm-modules", "--test",
        "tools/remote/projection-scope.test.mjs",
    ]
    assert not any("integrations/dsh-session-adapter/vitest.config.js" in command for command in commands)


def test_remote_android_fixture_does_not_trigger_legacy_dsh_adapter():
    runner = load_runner()
    impact = runner.analyze_impact([
        "apps/android/gateway-client/src/test/resources/dsh-remote/fixtures/crypto/v1/noise-ik.json",
    ])
    assert impact["affects_android_gateway"]


def test_full_runs_remote_and_phone_without_retired_services():
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 0
    impact = runner.analyze_impact(["tools/remote/projection-scope.test.mjs"])
    assert runner.execute_strategy("full", impact) == 0
    assert ["npm", "test", "--prefix", "tools/remote-server"] in commands
    assert ["npm", "run", "test:phone-mcp"] in commands
    assert not any("oai-core" in " ".join(command) or "session-adapter" in " ".join(command) for command in commands)


def test_control_ports_remote_change_executes_gateway_and_conversation_pr_tasks():
    runner = load_runner()
    impact = runner.analyze_impact([
        "apps/android/control-ports/src/main/kotlin/com/openandroidintelligence/control/remote/TaskScope.kt",
    ])
    tasks = [task for invocation in runner.android_test_invocations("pr", impact) for task in invocation]
    assert ":gateway-client:testDebugUnitTest" in tasks
    assert ":conversation-data:testDebugUnitTest" in tasks
    assert ":conversation-ui:testDebugUnitTest" in tasks
    assert ":app:testFullDebugUnitTest" in tasks


def test_phone_mcp_routes_without_legacy_adapter_and_propagates_failure():
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or (31 if "test:phone-mcp" in command else 0)
    impact = runner.analyze_impact(["integrations/oai-android-mcp/src/stdio.mjs"])
    assert impact["affects_phone_mcp"]
    assert runner.execute_strategy("fast", impact) == 31
    assert commands[-1] == ["npm", "run", "test:phone-mcp"]


@pytest.mark.parametrize("level", ["fast", "pr", "full"])
def test_remote_source_gate_failure_blocks_every_level(level):
    runner = load_runner()
    commands = []
    runner.run_command = lambda command, **kwargs: commands.append(command) or 29
    assert runner.execute_strategy(level, runner.analyze_impact(["README.md"])) == 29
    assert commands == [["npm", "run", "lock:check"]]


def test_unknown_path_expands_local_and_ci_scope():
    runner = load_runner()
    impact = runner.analyze_impact(["new-unknown-area/security.py"])
    assert impact["is_full_required"]
    assert impact["affects_remote"]
    assert impact["affects_android_gateway"]
    assert impact["affects_android_control"]
