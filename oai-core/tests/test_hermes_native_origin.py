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
    (temporary / "contract.stdout.log").write_text(result.stdout)
    (temporary / "contract.stderr.log").write_text(result.stderr)
    assert result.returncode == 0, result.stdout + result.stderr
    return json.loads(result.stdout.splitlines()[-1])["passed"]


@pytest.mark.parametrize("scenario", [
    "busy_ack_is_delivery_status_not_assistant_history",
    "busy_ack_does_not_consume_concurrent_identical_model_reply",
    "shared_busy_reply_is_not_classified_as_ack",
    "real_busy_slash_reply_remains_assistant_history",
    "busy_ack_cannot_consume_other_conversation_or_adapter_output",
    "busy_ack_requires_exact_core_account_conversation_client_session_generation_receipt",
    "unknown_host_ack_entry_keeps_output_without_classification",
    "redirect_ack_is_status_and_original_input_survives",
    "ack_retry_has_no_message_id_and_never_rewrites_existing_history",
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
