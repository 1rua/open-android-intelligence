#!/usr/bin/env python3
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "e2e" / "mvp" / "run-smoke.sh"
READINESS = ROOT / "e2e" / "mvp" / "run-readiness.sh"


class MvpSmokeGateTest(unittest.TestCase):
    def test_sdk_free_smoke_runs_contract_and_static_gates(self):
        result = subprocess.run(
            [str(SCRIPT), "--sdk-free"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("SDK_FREE_PASS", result.stdout)
        self.assertIn("LOCK_GATE_PENDING", result.stdout)

    def test_release_mode_is_a_real_gate(self):
        result = subprocess.run(
            [str(SCRIPT), "--release"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("RELEASE_GATE_BLOCKED", result.stdout + result.stderr)

    def test_readiness_report_audits_all_packets_without_promoting_sdk_free(self):
        result = subprocess.run(
            [str(READINESS), "--sdk-free"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        for packet in (f"WP-{index:02d}" for index in range(11)):
            self.assertIn(packet, result.stdout)
        self.assertIn("SDK_FREE_READINESS_PASS", result.stdout)
        self.assertIn("production gate not claimed", result.stdout)
        self.assertIn("BRIDGE-RUNTIME-PRODUCTION", result.stdout)
        self.assertNotIn("TASK7-DECISIONS", result.stdout)
        self.assertIn(
            "TASK9-REVIEW: Task 9 product literals are accepted, but only a reference contract is present; vectors, production durability and pre-replay integration remain pending",
            result.stdout,
        )

    def test_readiness_report_keeps_release_fail_closed(self):
        result = subprocess.run(
            [str(READINESS), "--release"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("RELEASE_READINESS_BLOCKED", result.stdout + result.stderr)
        self.assertIn("RELEASE_GATE_BLOCKED", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
