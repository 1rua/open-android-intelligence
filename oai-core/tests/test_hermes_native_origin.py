"""Prove origin/original identity across the pinned host's real steering persistence."""
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest


@pytest.fixture(scope="module")
def native_origin_results(tmp_path_factory):
    sdk = os.environ.get("HERMES_SDK_ROOT")
    if not sdk:
        pytest.skip("requires an explicitly supplied pinned Hermes SDK")
    repo = Path(__file__).resolve().parents[2]
    temporary = tmp_path_factory.mktemp("native-origin")
    environment = {**os.environ, "HERMES_HOME": str(temporary / "hermes"),
                   "HERMES_TEST_ISOLATION": str(temporary), "PYTHONDONTWRITEBYTECODE": "1"}
    result = subprocess.run([sys.executable, "-B", str(Path(__file__).with_name("native_hermes_origin_contract.py")),
                             str(repo), str(temporary)], cwd=repo, env=environment,
                            capture_output=True, text=True, timeout=40)
    assert result.returncode == 0, result.stdout + result.stderr
    return json.loads(result.stdout.splitlines()[-1])["passed"]


@pytest.mark.parametrize("scenario", [
    "explicit_identity_original_retry_restart", "merged_two_inputs_compaction",
    "normal_busy_keeps_existing_identity", "native_redirect_keeps_original",
    "native_pre_api_requeue", "legal_history_systemnote_recovered_unchanged",
    "wrong_conversation_fails_closed", "conversation_delete_retires_originals",
    "flush_retry_multiple_batches", "hard_cancel_soft_clear",
    "late_steer_fallback_and_empty_flush", "unsupported_sdk_and_missing_capture_fail_closed",
    "priority_busy_public_intake",
])
def test_native_origin_contract(native_origin_results, scenario):
    assert scenario in native_origin_results
