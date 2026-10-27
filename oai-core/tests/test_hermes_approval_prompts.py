"""Run real Hermes approval callbacks with isolated, synthetic host queues.

The subprocess never starts an Agent or executes a command. It uses the pinned
SDK's prompt, redactor, queue and resolver, rather than inventing their shapes.
"""
import os
from pathlib import Path
import subprocess
import sys

import pytest

adapter = pytest.importorskip("oai_hermes_adapter")


@pytest.mark.skipif(not os.environ.get("HERMES_SDK_ROOT"), reason="requires an explicit pinned Hermes SDK checkout")
@pytest.mark.parametrize(("scenario", "decision"), [
    ("redacted_parallel", "once"), ("redacted_parallel", "session"),
    ("redacted_parallel", "always"), ("redacted_parallel", "deny"),
    ("redacted_ambiguous", "once"), ("redacted_literal_collision", "once"),
    ("plain_parallel", "once"), ("text_fallback", "once"),
    ("host_options", "once"), ("host_options", "deny"),
    ("host_without_always", "session"), ("host_without_session", "once"),
    ("host_once_deny", "deny"),
])
def test_native_approval_prompt_and_selected_host_wait(tmp_path, scenario, decision):
    import oai_core

    repository = Path(__file__).resolve().parents[2]
    sdk = Path(os.environ["HERMES_SDK_ROOT"]).resolve()
    assert (sdk / "gateway" / "platforms" / "base.py").is_file()
    isolated = tmp_path / "isolated-hermes"
    environment = dict(os.environ)
    environment.update({
        "PYTHONPATH": os.pathsep.join((
            str(Path(oai_core.__file__).resolve().parent.parent),
            str(Path(adapter.__file__).resolve().parent.parent), str(sdk),
            *filter(None, os.environ.get("PYTHONPATH", "").split(os.pathsep)),
        )),
        "HERMES_HOME": str(isolated), "HERMES_TEST_ISOLATION": str(isolated),
    })
    process = subprocess.run([
        sys.executable, str(Path(__file__).with_name("native_hermes_approval_contract.py")),
        str(repository), str(tmp_path), scenario, decision,
    ], cwd=repository, env=environment, capture_output=True, text=True, timeout=20)
    assert process.returncode == 0, process.stdout + process.stderr
    assert "native approval prompt contracts passed" in process.stdout
