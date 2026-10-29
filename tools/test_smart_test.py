"""An Adapter-only edit must actually reach its optional-host regressions."""
import importlib.util
from pathlib import Path


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
    assert not any("oai-core/tests" in command for command in commands)


def test_standalone_core_suite_does_not_collect_optional_adapter_tests():
    runner = load_runner()
    commands = []

    def execute(command, **kwargs):
        commands.append(command)
        return 9 if "oai-core/tests" in command else 0

    runner.run_command = execute
    impact = runner.analyze_impact(["oai-core/src/oai_core/sessions.py"])
    assert runner.execute_strategy("pr", impact) == 9
    command = commands[-1]
    assert "--ignore=oai-core/tests/test_structured_tool_history.py" in command
    assert "--ignore=oai-core/tests/test_native_tool_events.py" in command
    assert "--ignore=oai-core/tests/test_native_event_sync.py" in command
