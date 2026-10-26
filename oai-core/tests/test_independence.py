"""Run the device service with every Hermes import rejected before Core loads.

The legacy protocol tests exercise the state machine directly. This separate
process checks its deployment boundary through real HTTP without the old
Gateway package, SDK, or native platform registry. It follows the actual
package loaded by its interpreter, including an installed wheel.
"""
from __future__ import annotations

import ast
import os
from pathlib import Path
import subprocess
import sys
import tomllib


REPOSITORY = Path(__file__).resolve().parents[2]


def test_core_declares_its_dependencies_without_a_gateway_repository():
    metadata = tomllib.loads((REPOSITORY / "oai-core/pyproject.toml").read_text())
    dependencies = metadata["project"]["dependencies"]
    names = {value.split(">", 1)[0].split("<", 1)[0].split("=", 1)[0].split("@", 1)[0].strip().lower() for value in dependencies}
    assert {"aiohttp", "mcp", "cryptography", "jsonschema"} <= names
    assert all("hermes" not in value.lower() and "git+" not in value.lower() for value in dependencies)

    # A lazy import is still a reverse dependency, so inspect function bodies as
    # well as module imports. Historical names in documentation are harmless.
    forbidden = "open_android_intelligence_gateway"
    for path in (REPOSITORY / "oai-core/src/oai_core").rglob("*.py"):
        tree = ast.parse(path.read_text(), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                assert all(not alias.name.startswith(forbidden) for alias in node.names), path
            if isinstance(node, ast.ImportFrom):
                assert not (node.module or "").startswith(forbidden), path


def test_device_service_works_while_hermes_and_legacy_gateway_are_unimportable(tmp_path):
    environment = {key: value for key, value in os.environ.items() if not key.startswith(("HERMES_", "OAI_HERMES_", "OPEN_ANDROID_GATEWAY_CONTRACT_"))}
    environment.pop("OPEN_ANDROID_INTELLIGENCE_GATEWAY_ROOT", None)
    environment.pop("OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE", None)
    # Follow the package actually loaded by this interpreter. Wheel jobs must
    # keep testing site-packages; source runs may use their editable src root.
    import oai_core
    import_root = Path(oai_core.__file__).resolve().parents[1]
    environment["PYTHONPATH"] = str(import_root)
    result = subprocess.run(
        [sys.executable, str(Path(__file__).with_name("independent_core_contract.py")), str(tmp_path), str(import_root)],
        cwd=tmp_path, env=environment, capture_output=True, text=True, timeout=40,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "independent Core HTTP contracts passed" in result.stdout
