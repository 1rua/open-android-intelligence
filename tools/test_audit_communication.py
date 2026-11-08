"""通信证据离线审计工具的合成固定时间测试。"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

import pytest


SPEC = importlib.util.spec_from_file_location(
    "audit_communication", Path(__file__).with_name("audit-communication.py"))
probe = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = probe
SPEC.loader.exec_module(probe)


OBSERVER_START = datetime(2026, 11, 7, 22, 22, 23, 663000, tzinfo=timezone.utc)
OBSERVER_END = datetime(2026, 11, 8, 6, 22, 38, 716000, tzinfo=timezone.utc)
PERIODIC_START = datetime(2026, 11, 7, 22, 22, 34, 879000, tzinfo=timezone.utc)
PERIODIC_END = datetime(2026, 11, 8, 6, 22, 38, 717000, tzinfo=timezone.utc)
AUTHORIZATION_AT = "2026-11-08T06:22:38.716392+00:00"
OBSERVER_COUNT = 2860
OBSERVER_FIRST_THREE_HOURS = 1072
PERIODIC_ROUNDS = 16
BOUNDARY_CLIENT_ID = "periodic-20261108-f5001dc4d6164df3b75f6b1e87171764"
BOUNDARY_GENERATION_ID = "gen_e76ab237a824dee5119b24eebd2f8653ccf3d7a6"
OLD_PARTIAL_ID = "periodic-20261108-42f2ad2395954976b60e41302a290486"


def _iso(value: datetime) -> str:
    return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _write_jsonl(path: Path, values: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n" for value in values),
        encoding="utf-8",
    )


def _load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def _load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _observer_rows() -> list[dict]:
    last_offset = (OBSERVER_END - OBSERVER_START).total_seconds() - 3.336
    rows = []
    for index in range(OBSERVER_COUNT):
        offset = last_offset * index / (OBSERVER_COUNT - 1)
        capabilities = {
            "list": True, "history": True, "create": True, "send": True,
            "steer": index % 700 != 0, "rename": True, "archive": True,
            "delete": True, "cancel": True, "stream": True, "commands": True,
            "approvals": True, "attachments": True, "generationRecovery": True,
            "eventSync": True, "operationRecovery": True,
        }
        rows.append({
            "time": _iso(OBSERVER_START + timedelta(seconds=offset)),
            "httpStatus": {"devices": 200, "agents": 200, "session": 200},
            "agentOnline": {"hermes": True},
            "capabilities": {"hermes": capabilities},
            "requestId": f"obs_{index:032x}",
            "latencyMs": {"devices": 1.0, "agents": 1.0, "session": 1.0, "total": 3.0},
            "hopErrors": {"devices": None, "agents": None, "session": None},
            "errorCode": None,
        })
    return rows


def _periodic_rows(observation_start: datetime) -> tuple[list[dict], list[dict]]:
    rows, verification_rounds = [], []
    for index in range(PERIODIC_ROUNDS):
        round_number = index + 1
        started_at = observation_start + timedelta(milliseconds=8, seconds=1800 * index)
        ended_at = started_at + timedelta(seconds=6)
        client_id = f"periodic-20261108-{round_number:032x}"
        generation_id = f"gen_{round_number:040x}"
        message_id = f"msg_{round_number:032x}"
        rows.append({
            "clientMessageId": client_id,
            "endedAt": _iso(ended_at),
            "generationId": generation_id,
            "historyAssistantRows": 1,
            "historyUserRows": 1,
            "key": client_id,
            "latencySeconds": 6.0,
            "messageId": message_id,
            "postCount": 1,
            "round": round_number,
            "startedAt": _iso(started_at),
            "terminal": "completed",
        })
        verification_rounds.append({
            "assistantIdentityMatched": True,
            "assistantRows": 1,
            "assistantStatus": None,
            "clientMessageId": client_id,
            "generationId": generation_id,
            "historyIndexes": [index * 2 + 2, index * 2 + 3],
            "messageId": message_id,
            "passed": True,
            "round": round_number,
            "userRows": 1,
            "userStatus": None,
        })
    return rows, verification_rounds


def build_evidence(root: Path) -> dict:
    evidence = root / "evidence"
    observed = evidence / "observe-24h"
    periodic = evidence / "periodic-messages"
    partial = evidence / "periodic-messages-5b5bdf2d-partial"

    manifest_entries = []
    for index in range(3):
        relative = f"src/module_{index}.py"
        source = root / relative
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(f"VALUE = {index}\n", encoding="utf-8")
        manifest_entries.append({"path": relative, "sha256": _sha256(source)})
    manifest_path = root / ".toolchains" / "deployment" / "observer-manifest.json"
    _write_json(manifest_path, {"files": manifest_entries})
    manifest_sha = _sha256(manifest_path)
    manifest_reference = ".toolchains/deployment/observer-manifest.json"

    observer_start = {
        "intervalSeconds": 10.0,
        "manifestPath": manifest_reference,
        "pid": 2,
        "requestTimeoutSeconds": 5.0,
        "requestedDurationSeconds": 86400.0,
        "startedAt": _iso(OBSERVER_START),
        "target": {
            "agentId": "hermes",
            "readPath": "history",
            "sessionId": "conv-observer",
        },
    }
    observation_start = PERIODIC_START + timedelta(milliseconds=14)
    periodic_start = {
        "intervalSeconds": 1800,
        "manifestPath": manifest_reference,
        "manifestSha256": manifest_sha,
        "observationStartedAt": _iso(observation_start),
        "observationTimeoutSeconds": 90,
        "pid": 2,
        "requestedDurationSeconds": 86400,
        "sessionCreateKey": "periodic-create-communication-20261108-v1",
        "sessionCreatePosts": 0,
        "sessionId": "conv-periodic",
        "sessionTitle": "通信周期验收20261108",
        "startedAt": _iso(PERIODIC_START),
    }
    _write_json(observed / "start.json", observer_start)
    _write_json(periodic / "start.json", periodic_start)

    amendment = {
        "at": AUTHORIZATION_AT,
        "interpretation": "原始旧程序终态保留；按授权的新门槛审计同一批原始记录。",
        "keepOriginalStart": True,
        "originalRequiredSeconds": 86400,
        "plannedStopSignal": "SIGINT for samplers",
        "processes": [
            {
                "folder": "observe-24h",
                "pid": 2,
                "script": "observe-communication.py",
                "start": observer_start,
                "start_sha256": _sha256(observed / "start.json"),
            },
            {
                "folder": "periodic-messages",
                "pid": 3,
                "script": "periodic-message.py",
                "start": periodic_start,
                "start_sha256": _sha256(periodic / "start.json"),
            },
        ],
        "reason": "user_changed_acceptance_duration",
        "requiredSeconds": 10800,
        "status": "stop_requested",
    }
    _write_json(evidence / "acceptance-duration-amendment.json", amendment)

    observer_rows = _observer_rows()
    _write_jsonl(observed / "observations.jsonl", observer_rows)
    observer_end = {
        "actualDurationSeconds": 28815.054,
        "completed_duration": False,
        "endedAt": _iso(OBSERVER_END),
        "observationCount": OBSERVER_COUNT,
        "passed": False,
        "startedAt": _iso(OBSERVER_START),
        "stopReason": "interrupted",
        "verdict": "incomplete",
        "version_unchanged": True,
    }
    observer_summary = {
        "actualDurationSeconds": 28815.054,
        "agents": {"attempts": OBSERVER_COUNT, "apiFailures": 0, "targetOfflineObservations": 0},
        "completed_duration": False,
        "core": {"attempts": OBSERVER_COUNT, "failures": 0},
        "endedAt": _iso(OBSERVER_END),
        "failureCategories": ["duration_below_24h", "duration_not_met", "stopped"],
        "intervalSeconds": 10.0,
        "manifest": {"changedPaths": [], "status": "ok"},
        "passed": False,
        "requestedDurationSeconds": 86400.0,
        "sample_count": OBSERVER_COUNT,
        "sessionReads": {"attempts": OBSERVER_COUNT, "failures": 0},
        "startedAt": _iso(OBSERVER_START),
        "stopReason": "interrupted",
        "verdict": "incomplete",
        "version_unchanged": True,
    }
    _write_json(observed / "end.json", observer_end)
    _write_json(observed / "summary.json", observer_summary)

    periodic_rows, verification_rounds = _periodic_rows(observation_start)
    _write_jsonl(periodic / "observations.jsonl", periodic_rows)
    periodic_summary = {
        "actualDurationSeconds": 28803.824,
        "durationComplete": False,
        "endedAt": _iso(PERIODIC_END),
        "expectedRounds": 48,
        "intervalSeconds": 1800,
        "manifestChanges": [],
        "manifestPath": manifest_reference,
        "manifestSha256": manifest_sha,
        "manifestUnchanged": True,
        "messagePosts": 16,
        "observationTimeoutSeconds": 90,
        "passed": False,
        "requestedDurationSeconds": 86400,
        "roundsAttempted": 16,
        "roundsCompleted": 16,
        "roundsHistoryMatched": 16,
        "sessionCreatePosts": 0,
        "sessionId": "conv-periodic",
        "startedAt": _iso(PERIODIC_START),
        "stopReason": "interrupted",
        "totalPostCount": 16,
    }
    periodic_end = {
        "actualDurationSeconds": 28803.824,
        "endedAt": _iso(PERIODIC_END),
        "roundsAttempted": 16,
        "startedAt": _iso(PERIODIC_START),
        "stopReason": "interrupted",
        "totalPostCount": 16,
    }
    _write_json(periodic / "summary.json", periodic_summary)
    _write_json(periodic / "end.json", periodic_end)
    _write_json(periodic / "state.json", {
        "passed": False,
        "phase": "finished",
        "stopReason": "interrupted",
    })

    _write_jsonl(partial / "observations.jsonl", [{
        "clientMessageId": OLD_PARTIAL_ID,
        "endedAt": "2026-11-07T22:07:52.980Z",
        "generationId": "gen_f6e2d1464c22bc28ae823f30852dd4c882fce997",
        "historyAssistantRows": 1,
        "historyUserRows": 1,
        "key": OLD_PARTIAL_ID,
        "messageId": "msg_d1a95867a9f74668af84cc5b85d05a26",
        "postCount": 1,
        "round": 1,
        "startedAt": "2026-11-07T22:07:43.904Z",
        "terminal": "completed",
    }])
    _write_json(partial / "state.json", {"phase": "round_finished"})

    _write_json(evidence / "periodic-final-history-verification.json", {
        "at": "2026-11-08T06:27:52.955325+00:00",
        "containsMessageContent": False,
        "historyRowsReceived": 36,
        "httpStatus": 200,
        "messagePosts": 0,
        "operation": "GET history only",
        "orderedByOriginalInput": True,
        "passed": True,
        "rounds": verification_rounds,
        "roundsChecked": 16,
        "sessionId": "conv-periodic",
        "sourceObservationsSha256": _sha256(periodic / "observations.jsonl"),
    })
    _write_json(evidence / "periodic-stop-boundary-reconciliation.json", {
        "excludedPriorPartialRoundIds": 1,
        "messagePostsPerformedByReconciliation": 0,
        "observedAt": "2026-11-08T06:30:09.057053+00:00",
        "operation": "GET history/generation only",
        "recordedRoundIds": 16,
        "sessionId": "conv-periodic",
        "unmatched": [{
            "clientMessageId": BOUNDARY_CLIENT_ID,
            "generation": {
                "clientMessageId": BOUNDARY_CLIENT_ID,
                "createdAt": None,
                "generationId": BOUNDARY_GENERATION_ID,
                "messageId": None,
                "state": "completed",
                "updatedAt": None,
            },
            "historyRows": [
                {
                    "createdAt": 1794118954970,
                    "generationId": None,
                    "id": "msg_boundary_user",
                    "role": "user",
                    "status": None,
                    "timestamp": None,
                },
                {
                    "createdAt": 1794118966472,
                    "generationId": BOUNDARY_GENERATION_ID,
                    "id": "msg_boundary_assistant",
                    "role": "assistant",
                    "status": None,
                    "timestamp": None,
                },
            ],
            "httpStatus": 200,
        }],
    })
    return {"root": root, "evidence": evidence, "observer_rows": observer_rows,
            "periodic_rows": periodic_rows}


def _mutate_jsonl(path: Path, mutate) -> None:
    values = _load_jsonl(path)
    mutate(values)
    _write_jsonl(path, values)


def _mutate_json(path: Path, mutate) -> None:
    value = _load_json(path)
    mutate(value)
    _write_json(path, value)


def test_positive_audit_records_full_stats_and_separate_boundary_post(tmp_path):
    fixture = build_evidence(tmp_path)
    result = probe.audit_evidence(fixture["evidence"], repo_root=fixture["root"])

    assert result["passed"] is True, result["failureCategories"]
    assert result["failureCategories"] == []
    assert result["requiredSeconds"] == 10800
    assert result["observer"]["startedAt"] == _iso(OBSERVER_START)
    assert result["observer"]["endedAt"] == _iso(OBSERVER_END)
    assert result["observer"]["full"]["sampleCount"] == OBSERVER_COUNT
    assert result["observer"]["first3h"]["sampleCount"] == OBSERVER_FIRST_THREE_HOURS
    assert result["observer"]["full"]["maxGapSeconds"] <= 25.0
    assert result["periodic"]["full"]["roundCount"] == PERIODIC_ROUNDS
    assert result["periodic"]["first3h"]["roundCount"] == 6
    assert result["periodic"]["startedAt"] == _iso(PERIODIC_START)
    assert result["periodic"]["observationStartedAt"] == _iso(PERIODIC_START + timedelta(milliseconds=14))
    assert result["periodic"]["endedAt"] == _iso(PERIODIC_END)
    assert result["periodic"]["messagePostsRecorded"] == 16
    assert result["periodic"]["completedPostsObserved"] == 17
    assert result["periodic"]["actualMessagePostsAtLeast"] == 17
    assert result["periodic"]["stopBoundary"]["additionalCompletedPosts"] == 1
    assert result["periodic"]["stopBoundary"]["excludedFromFirst3h"] is True
    assert result["manifest"]["filesChecked"] == 3
    assert result["manifest"]["digestMatched"] is True
    assert result["files"]["observe-24h/observations.jsonl"]["sha256"] == _sha256(
        fixture["evidence"] / "observe-24h" / "observations.jsonl")
    assert not result["manifest"]["path"].startswith("/")
    assert all(not Path(path).is_absolute() for path in result["files"])


def test_cli_writes_new_file_and_refuses_overwrite(tmp_path, monkeypatch, capsys):
    fixture = build_evidence(tmp_path)
    output = tmp_path / "audit.json"
    monkeypatch.setattr(probe, "REPO_ROOT", fixture["root"])

    exit_code = probe.main([
        "--evidence-dir", str(fixture["evidence"]),
        "--output", str(output),
    ])
    assert exit_code == 0
    first_bytes = output.read_bytes()
    assert _load_json(output)["passed"] is True
    assert json.loads(capsys.readouterr().out)["passed"] is True

    second_exit = probe.main([
        "--evidence-dir", str(fixture["evidence"]),
        "--output", str(output),
    ])
    assert second_exit != 0
    assert output.read_bytes() == first_bytes
    assert json.loads(capsys.readouterr().out)["errorCode"] == "OUTPUT_EXISTS"


@pytest.mark.parametrize(
    ("case", "failure"),
    [
        ("empty_samples", "observer_sample_count_mismatch"),
        ("http_error", "observer_http_error"),
        ("hop_error", "observer_hop_error"),
        ("target_offline", "observer_target_offline"),
        ("capabilities_empty", "observer_capabilities_empty"),
        ("duplicate_request", "observer_duplicate_request_id"),
        ("truncated", "observer_sample_count_mismatch"),
        ("missing_timestamp", "observer_timestamp_invalid"),
    ],
)
def test_observer_invalid_cases_never_pass(tmp_path, case, failure):
    fixture = build_evidence(tmp_path)
    path = fixture["evidence"] / "observe-24h" / "observations.jsonl"

    def mutate(values):
        if case == "empty_samples":
            values.clear()
        elif case == "http_error":
            values[0]["httpStatus"]["agents"] = 503
        elif case == "hop_error":
            values[0]["hopErrors"]["agents"] = "AGENT_HTTP_ERROR"
        elif case == "target_offline":
            values[0]["agentOnline"]["hermes"] = False
        elif case == "capabilities_empty":
            values[0]["capabilities"]["hermes"] = {}
        elif case == "duplicate_request":
            values[0]["requestId"] = values[1]["requestId"]
        elif case == "truncated":
            values.pop()
        elif case == "missing_timestamp":
            values[0].pop("time")

    _mutate_jsonl(path, mutate)
    result = probe.audit_evidence(fixture["evidence"], repo_root=fixture["root"])
    assert result["passed"] is False
    assert failure in result["failureCategories"]


@pytest.mark.parametrize(("gap_seconds", "should_pass"), [(25.0, True), (25.001, False)])
def test_observer_gap_uses_interval_plus_three_request_timeouts(
        tmp_path, gap_seconds, should_pass):
    fixture = build_evidence(tmp_path)
    path = fixture["evidence"] / "observe-24h" / "observations.jsonl"

    def mutate(values):
        previous = datetime.fromisoformat(values[1499]["time"].replace("Z", "+00:00"))
        target = previous + timedelta(seconds=gap_seconds)
        values[1500]["time"] = _iso(target)
        values[1501]["time"] = _iso(target + timedelta(milliseconds=1))

    _mutate_jsonl(path, mutate)
    result = probe.audit_evidence(fixture["evidence"], repo_root=fixture["root"])
    assert result["passed"] is should_pass
    if not should_pass:
        assert "observer_gap_too_large" in result["failureCategories"]


@pytest.mark.parametrize(
    ("case", "failure"),
    [
        ("short_duration", "duration_below_required"),
        ("end_mismatch", "end_not_aligned_with_authorization"),
        ("unauthorized_stop", "unauthorized_interruption"),
        ("start_hash", "start_digest_mismatch"),
        ("start_identity", "start_identity_mismatch"),
    ],
)
def test_authorization_and_original_terminal_boundaries(tmp_path, case, failure):
    fixture = build_evidence(tmp_path)
    evidence = fixture["evidence"]
    if case in {"short_duration", "end_mismatch"}:
        def change_end(value):
            if case == "short_duration":
                value["actualDurationSeconds"] = 10799
            else:
                value["endedAt"] = "2026-11-08T06:22:44.000Z"
        _mutate_json(evidence / "observe-24h" / "summary.json", change_end)
        _mutate_json(evidence / "observe-24h" / "end.json", change_end)
    elif case == "unauthorized_stop":
        _mutate_json(
            evidence / "acceptance-duration-amendment.json",
            lambda value: value.update(reason="unexpected_stop", status="observed"),
        )
    elif case == "start_hash":
        _mutate_json(
            evidence / "acceptance-duration-amendment.json",
            lambda value: value["processes"][0].update(start_sha256="0" * 64),
        )
    elif case == "start_identity":
        _mutate_json(
            evidence / "observe-24h" / "start.json",
            lambda value: value["target"].update(agentId="other-agent"),
        )

    result = probe.audit_evidence(evidence, repo_root=fixture["root"])
    assert result["passed"] is False
    assert failure in result["failureCategories"]


@pytest.mark.parametrize(
    ("case", "failure"),
    [
        ("source_changed", "manifest_files_changed"),
        ("manifest_digest", "manifest_digest_mismatch"),
        ("missing_field", "input_invalid"),
    ],
)
def test_manifest_and_missing_field_fail_closed(tmp_path, case, failure):
    fixture = build_evidence(tmp_path)
    evidence = fixture["evidence"]
    if case == "source_changed":
        (fixture["root"] / "src" / "module_1.py").write_text("CHANGED = True\n", encoding="utf-8")
    elif case == "manifest_digest":
        _mutate_json(
            evidence / "periodic-messages" / "summary.json",
            lambda value: value.update(manifestSha256="0" * 64),
        )
    elif case == "missing_field":
        _mutate_json(
            evidence / "periodic-messages" / "end.json",
            lambda value: value.pop("endedAt"),
        )

    result = probe.audit_evidence(evidence, repo_root=fixture["root"])
    assert result["passed"] is False
    assert failure in result["failureCategories"]


@pytest.mark.parametrize(
    ("case", "failure"),
    [
        ("terminal", "periodic_terminal_invalid"),
        ("post_count", "periodic_post_count_invalid"),
        ("history_rows", "periodic_history_mismatch"),
        ("duplicate_identity", "periodic_identity_duplicate"),
        ("first3h_short", "periodic_first3h_insufficient"),
        ("interval_too_large", "periodic_interval_too_large"),
        ("truncated", "periodic_round_count_mismatch"),
    ],
)
def test_periodic_round_boundaries_never_pass(tmp_path, case, failure):
    fixture = build_evidence(tmp_path)
    path = fixture["evidence"] / "periodic-messages" / "observations.jsonl"

    def mutate(values):
        if case == "terminal":
            values[3]["terminal"] = "running"
        elif case == "post_count":
            values[3]["postCount"] = 2
        elif case == "history_rows":
            values[3]["historyAssistantRows"] = 0
        elif case == "duplicate_identity":
            values[3]["generationId"] = values[2]["generationId"]
        elif case == "first3h_short":
            for value in values[5:]:
                value["startedAt"] = _iso(
                    datetime.fromisoformat(value["startedAt"].replace("Z", "+00:00"))
                    + timedelta(seconds=1800))
                value["endedAt"] = _iso(
                    datetime.fromisoformat(value["endedAt"].replace("Z", "+00:00"))
                    + timedelta(seconds=1800))
        elif case == "interval_too_large":
            for value in values[9:]:
                value["startedAt"] = _iso(
                    datetime.fromisoformat(value["startedAt"].replace("Z", "+00:00"))
                    + timedelta(seconds=300))
                value["endedAt"] = _iso(
                    datetime.fromisoformat(value["endedAt"].replace("Z", "+00:00"))
                    + timedelta(seconds=300))
        elif case == "truncated":
            values.pop()

    _mutate_jsonl(path, mutate)
    result = probe.audit_evidence(fixture["evidence"], repo_root=fixture["root"])
    assert result["passed"] is False
    assert failure in result["failureCategories"]


def test_empty_manifest_is_invalid(tmp_path):
    fixture = build_evidence(tmp_path)
    manifest = fixture["root"] / ".toolchains" / "deployment" / "observer-manifest.json"
    _write_json(manifest, {"files": []})

    result = probe.audit_evidence(fixture["evidence"], repo_root=fixture["root"])
    assert result["passed"] is False
    assert "manifest_invalid" in result["failureCategories"]


@pytest.mark.parametrize(
    ("relative", "field", "value"),
    [
        ("observe-24h/summary.json", "actualDurationSeconds", float("nan")),
        ("observe-24h/summary.json", "intervalSeconds", float("inf")),
        ("periodic-messages/summary.json", "observationTimeoutSeconds", float("nan")),
    ],
)
def test_summary_numeric_values_must_be_finite(tmp_path, relative, field, value):
    fixture = build_evidence(tmp_path)
    _mutate_json(
        fixture["evidence"] / relative,
        lambda summary: summary.update({field: value}),
    )

    result = probe.audit_evidence(fixture["evidence"], repo_root=fixture["root"])
    assert result["passed"] is False
    assert "input_invalid" in result["failureCategories"]


@pytest.mark.parametrize(
    ("case", "failure"),
    [
        ("mixed_recorded", "periodic_boundary_mixed"),
        ("mixed_partial", "periodic_boundary_mixed"),
        ("incomplete", "periodic_boundary_incomplete"),
        ("history_rows", "periodic_boundary_history_mismatch"),
    ],
)
def test_additional_interrupted_boundary_is_counted_separately(tmp_path, case, failure):
    fixture = build_evidence(tmp_path)
    path = fixture["evidence"] / "periodic-stop-boundary-reconciliation.json"

    def mutate(value):
        unmatched = value["unmatched"][0]
        if case == "mixed_recorded":
            unmatched["clientMessageId"] = fixture["periodic_rows"][0]["clientMessageId"]
        elif case == "mixed_partial":
            unmatched["clientMessageId"] = OLD_PARTIAL_ID
        elif case == "incomplete":
            unmatched["generation"]["state"] = "running"
        elif case == "history_rows":
            unmatched["historyRows"].pop()

    _mutate_json(path, mutate)
    result = probe.audit_evidence(fixture["evidence"], repo_root=fixture["root"])
    assert result["passed"] is False
    assert failure in result["failureCategories"]



@pytest.mark.parametrize("case", ["flag_false", "index_reversed", "cross_round_reordered"])
def test_final_history_order_is_checked_independently(tmp_path, case):
    fixture = build_evidence(tmp_path)
    path = fixture["evidence"] / "periodic-final-history-verification.json"
    value = _load_json(path)
    if case == "flag_false":
        value["orderedByOriginalInput"] = False
    elif case == "index_reversed":
        value["rounds"][0]["historyIndexes"].reverse()
    else:
        value["rounds"][1]["historyIndexes"] = value["rounds"][0]["historyIndexes"]
    _write_json(path, value)
    result = probe.audit_evidence(fixture["evidence"], repo_root=fixture["root"])
    assert result["passed"] is False
    assert "periodic_history_verification_invalid" in result["failureCategories"]


def test_missing_hop_error_fields_are_not_success(tmp_path):
    fixture = build_evidence(tmp_path)
    path = fixture["evidence"] / "observe-24h" / "observations.jsonl"
    _mutate_jsonl(path, lambda rows: rows[0].update(hopErrors={}))
    result = probe.audit_evidence(fixture["evidence"], repo_root=fixture["root"])
    assert result["passed"] is False
    assert "observer_hop_error" in result["failureCategories"]


def test_valid_sampling_count_is_derived_not_fixed_to_one_snapshot(tmp_path):
    fixture = build_evidence(tmp_path)
    folder = fixture["evidence"] / "observe-24h"
    path = folder / "observations.jsonl"
    _mutate_jsonl(path, lambda rows: rows.pop(1500))
    summary = _load_json(folder / "summary.json")
    summary["sample_count"] -= 1
    for kind in ("core", "agents", "sessionReads"):
        summary[kind]["attempts"] -= 1
    _write_json(folder / "summary.json", summary)
    _mutate_json(folder / "end.json", lambda end: end.update(observationCount=OBSERVER_COUNT - 1))
    result = probe.audit_evidence(fixture["evidence"], repo_root=fixture["root"])
    assert result["passed"] is True, result["failureCategories"]
    assert result["observer"]["full"]["sampleCount"] == OBSERVER_COUNT - 1
