"""Pinned Hermes loader and SessionStore checks without model or user data.

Set HERMES_SDK_ROOT to an explicitly checked-out native SDK. A fresh process
keeps standalone fallback imports and the host's module globals isolated.
"""
import os
from pathlib import Path
import subprocess
import sys

import pytest


@pytest.mark.skipif(not os.environ.get("HERMES_SDK_ROOT"), reason="requires an explicit pinned Hermes SDK checkout")
def test_native_plugin_loader_session_store_and_lifecycle(tmp_path):
    import oai_core
    import oai_hermes_adapter

    repository = Path(__file__).resolve().parents[2]
    sdk = Path(os.environ["HERMES_SDK_ROOT"]).resolve()
    assert (sdk / "gateway" / "session.py").is_file()
    home = tmp_path / "isolated-hermes"
    environment = dict(os.environ)
    environment.update({
        "PYTHONPATH": os.pathsep.join((str(Path(oai_core.__file__).resolve().parent.parent),
                                      str(Path(oai_hermes_adapter.__file__).resolve().parent.parent), str(sdk))),
        "HERMES_HOME": str(home), "HERMES_TEST_ISOLATION": str(home),
        "OPEN_ANDROID_GATEWAY_CONTRACT_ROOT": str(repository / "gateway-contract"),
    })
    process = subprocess.run([sys.executable, str(Path(__file__).with_name("native_hermes_contract.py")), str(repository), str(tmp_path)],
                             cwd=repository, env=environment, capture_output=True, text=True, timeout=45)
    assert process.returncode == 0, process.stdout + process.stderr
    assert "native Hermes contracts passed" in process.stdout
