#!/usr/bin/env python3
"""Check recovered plugin verification without changing the original artifacts."""

from contextlib import redirect_stdout
import importlib.util
import io
from pathlib import Path
import secrets
import shutil
import unittest
import zipfile

from cryptography.exceptions import InvalidSignature


PLAN = Path(__file__).resolve().parent
ARTIFACTS = PLAN / "runtime/official-signed-plugins-41219517875"
spec = importlib.util.spec_from_file_location("recovered_plugins", PLAN / "verify-recovered-plugins.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class RecoveredPluginTests(unittest.TestCase):
    def setUp(self):
        self.directory = PLAN / "runtime/test-runs" / ("recovered-" + secrets.token_hex(8))
        self.directory.mkdir(mode=0o700, parents=True)

    def copy_packages(self, name: str, transform):
        for source in ARTIFACTS.glob("*.alp"):
            target = self.directory / source.name
            if source.name != name:
                shutil.copyfile(source, target)
                continue
            with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, "x") as changed:
                for entry in original.infolist():
                    changed.writestr(entry, transform(entry.filename, original.read(entry)))

    def check(self, directory):
        with redirect_stdout(io.StringIO()):
            verifier.verify(directory)

    def test_official_artifacts_pass(self):
        self.check(ARTIFACTS)

    def test_missing_packages_rejected(self):
        with self.assertRaisesRegex(ValueError, "six official"):
            self.check(self.directory)

    def test_changed_payload_rejected(self):
        self.copy_packages("notifications.alp", lambda name, data: data + b"x" if name.endswith(".wasm") else data)
        with self.assertRaisesRegex(ValueError, "payload digest"):
            self.check(self.directory)

    def test_changed_signature_rejected(self):
        self.copy_packages("notifications.alp", lambda name, data: b"A" * 86 + b"\n" if name == "signature.ed25519" else data)
        with self.assertRaises(InvalidSignature):
            self.check(self.directory)

    def test_relative_gradle_plugin_directory(self):
        worktree = PLAN.parents[2]
        gradle_value = "../../../docs/plans/2026-11-01-global-device-authorization/runtime/official-signed-plugins-41219517875"
        self.assertEqual((worktree / "apps/android/app" / gradle_value).resolve(), ARTIFACTS.resolve())


if __name__ == "__main__":
    unittest.main()
