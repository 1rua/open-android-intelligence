#!/usr/bin/env python3
"""Verify source snapshot provenance without starting or stopping services."""

import hashlib
import importlib.util
import json
from pathlib import Path
import secrets
import unittest


PLAN = Path(__file__).resolve().parent
RUNTIME = PLAN / "runtime"
SNAPSHOTS = RUNTIME / "source-snapshots"
spec = importlib.util.spec_from_file_location("start_cores", PLAN / "start-isolated-cores.py")
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class CoreSnapshotTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not SNAPSHOTS.is_dir():
            raise unittest.SkipTest("no prepared source snapshot")
        candidates = sorted(path for path in SNAPSHOTS.iterdir()
                            if (path / "source-manifest.json").is_file() and (path / "processes.json").is_file())
        if not candidates:
            raise unittest.SkipTest("no prepared source snapshot")
        cls.snapshot = candidates[-1]

    def test_snapshot_matches_its_manifest_and_digest(self):
        manifest = json.loads((self.snapshot / "source-manifest.json").read_text())
        files = manifest["files"]
        digest = hashlib.sha256(json.dumps(files, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        self.assertEqual(digest, manifest["sourceDigestSha256"])
        for name, expected in files.items():
            self.assertEqual(hashlib.sha256((self.snapshot / name).read_bytes()).hexdigest(), expected, name)

    def test_both_configs_use_same_snapshot_and_separate_accounts(self):
        metadata = json.loads((self.snapshot / "processes.json").read_text())
        roots = []
        for instance in ("core-a", "core-b"):
            config_path = launcher.ROOT / metadata[instance]["config"]
            config = json.loads(config_path.read_text())
            self.assertEqual((config_path.parent / config["contractRoot"]).resolve(), self.snapshot / "gateway-contract")
            self.assertEqual(config["listen"]["host"], "127.0.0.1")
            roots.append((config_path.parent / config["storageRoot"]).resolve())
        self.assertNotEqual(*roots)

    def test_existing_snapshot_file_cannot_be_replaced(self):
        directory = RUNTIME / "test-runs" / ("snapshot-" + secrets.token_hex(8))
        directory.mkdir(mode=0o700, parents=True)
        destination = directory / "existing"
        launcher.write_new(destination, b"preserved")
        with self.assertRaises(FileExistsError):
            launcher.write_new(destination, b"changed")
        self.assertEqual(destination.read_bytes(), b"preserved")


if __name__ == "__main__":
    unittest.main()
