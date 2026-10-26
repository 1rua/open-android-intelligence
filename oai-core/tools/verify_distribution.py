"""Verify an installed Core distribution through its real ``oai serve`` entrypoint.

Run with a clean virtualenv's Python after installing the wheel and its declared
dependencies. No editable checkout, optional adapter or Hermes SDK is permitted.
"""
from __future__ import annotations

from importlib import metadata, util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def main():
    import oai_core
    from oai_core.gateway.core import ContractRegistry

    for name in ("open_android_intelligence_gateway", "oai_hermes_adapter", "gateway", "hermes_cli"):
        assert util.find_spec(name) is None, f"unexpected optional runtime installed: {name}"
    requirements = metadata.requires("open-android-intelligence-core")
    assert requirements and all("hermes" not in value.lower() and "git+" not in value for value in requirements)
    contracts = ContractRegistry()
    assert contracts.root.is_relative_to(Path(oai_core.__file__).resolve().parent), "contracts must ship inside the wheel"
    expected_hash = contracts.core_schema_hash
    negotiation = json.loads((contracts.root / "vectors/protocol-negotiation.json").read_text())["cases"][0]["input"]["value"]
    negotiation["schemaHashes"]["core"] = expected_hash
    environment = {name: value for name, value in os.environ.items()
                   if name != "PYTHONPATH" and not name.startswith(("HERMES_", "OAI_HERMES_", "OPEN_ANDROID_"))}

    with tempfile.TemporaryDirectory(prefix="oai-core-wheel-") as temporary:
        root = Path(temporary)
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        config = {"storageRoot": "accounts", "masterKeyFile": "secrets/master-key",
                  "listen": {"host": "127.0.0.1", "port": port}, "agents": []}
        config_path = root / "core.json"
        config_path.write_text(json.dumps(config))
        process = subprocess.Popen([sys.executable, "-m", "oai_core.cli", "serve", "--config", str(config_path)],
                                   cwd=root, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 20
            while True:
                try:
                    request = Request(f"http://127.0.0.1:{port}/open-android-intelligence/v2/negotiate",
                                      data=json.dumps(negotiation).encode(), headers={"Content-Type": "application/json"})
                    with urlopen(request, timeout=2) as response:
                        result = json.load(response)
                        assert response.status == 200 and response.headers["X-OAI-Agent-Sessions"] == "1"
                    assert result["protocol"] == "2.1"
                    break
                except URLError:
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise AssertionError("installed oai serve failed to become ready")
                    time.sleep(0.1)
            negotiation["schemaHashes"]["core"] = "sha256:" + "0" * 64
            request = Request(f"http://127.0.0.1:{port}/open-android-intelligence/v2/negotiate",
                              data=json.dumps(negotiation).encode(), headers={"Content-Type": "application/json"})
            try:
                urlopen(request, timeout=2)
            except HTTPError as error:
                assert json.load(error)["error"]["code"] == "PROTOCOL_INCOMPATIBLE"
            else:
                raise AssertionError("Core accepted the wrong Schema hash")
            assert (root / "secrets/master-key").stat().st_mode & 0o777 == 0o600
        finally:
            process.terminate()
            try:
                stdout, stderr = process.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                stdout, stderr = process.communicate()
            assert process.returncode == 0, stdout + stderr
    print(json.dumps({"distribution": metadata.version("open-android-intelligence-core"),
                      "hermes_installed": False, "serve": "passed", "core_schema_hash": expected_hash}))


if __name__ == "__main__":
    main()
