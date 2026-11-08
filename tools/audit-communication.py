#!/usr/bin/env python3
"""离线审计通信稳定性原始证据，不访问网络、凭据或消息正文。"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import sys


REPO_ROOT = Path(__file__).resolve().parents[1]
REQUIRED_SECONDS = 3 * 60 * 60
ORIGINAL_REQUIRED_SECONDS = 24 * 60 * 60
MINIMUM_PERIODIC_FIRST_THREE_HOURS = 6
END_ALIGNMENT_TOLERANCE_SECONDS = 5.0
WALL_DURATION_TOLERANCE_SECONDS = 5.0
DURATION_EPSILON = 1e-6
SUCCESS_TERMINALS = {"completed", "done"}
SHA256_RE = re.compile(r"[0-9a-fA-F]{64}")


class AuditError(RuntimeError):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def _add_failure(failures: set[str], code: str) -> None:
    failures.add(code)


def _is_finite_number(value) -> bool:
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value))


def _get(value: dict, key: str, code: str = "input_invalid"):
    if not isinstance(value, dict) or key not in value:
        raise AuditError(code)
    return value[key]


def _load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise AuditError("input_missing") from error
    except (OSError, UnicodeError, ValueError) as error:
        raise AuditError("input_invalid") from error


def _load_jsonl(path: Path) -> list[dict]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError as error:
        raise AuditError("input_missing") from error
    except (OSError, UnicodeError) as error:
        raise AuditError("input_invalid") from error
    rows = []
    for line in lines:
        if not line.strip():
            raise AuditError("input_invalid")
        try:
            row = json.loads(line)
        except ValueError as error:
            raise AuditError("input_invalid") from error
        if not isinstance(row, dict):
            raise AuditError("input_invalid")
        rows.append(row)
    return rows


def _parse_time(value, code: str = "input_invalid") -> datetime:
    if not isinstance(value, str) or not value:
        raise AuditError(code)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise AuditError(code) from error
    if parsed.tzinfo is None:
        raise AuditError(code)
    return parsed.astimezone(timezone.utc)


def _safe_relative(raw_path, code: str = "input_invalid") -> str:
    if not isinstance(raw_path, str) or not raw_path or "\\" in raw_path:
        raise AuditError(code)
    path = Path(raw_path)
    if (path.is_absolute() or re.match(r"^[A-Za-z]:", raw_path)
            or any(part in {"", ".", ".."} for part in path.parts)):
        raise AuditError(code)
    return path.as_posix()


def _resolve_relative(root: Path, raw_path: str) -> Path:
    normalized = _safe_relative(raw_path)
    root = root.resolve()
    target = (root / normalized).resolve()
    if not target.is_relative_to(root):
        raise AuditError("input_invalid")
    return target


def _file_record(path: Path) -> dict:
    try:
        raw = path.read_bytes()
    except OSError:
        return {"present": False, "sha256": None, "sizeBytes": 0}
    return {"present": True, "sha256": hashlib.sha256(raw).hexdigest(), "sizeBytes": len(raw)}


def _recorded_files(evidence_dir: Path) -> dict[str, dict]:
    relative_paths = [
        "acceptance-duration-amendment.json",
        "observe-24h/start.json",
        "observe-24h/end.json",
        "observe-24h/summary.json",
        "observe-24h/observations.jsonl",
        "periodic-messages/start.json",
        "periodic-messages/end.json",
        "periodic-messages/summary.json",
        "periodic-messages/observations.jsonl",
        "periodic-messages/state.json",
        "periodic-final-history-verification.json",
        "periodic-stop-boundary-reconciliation.json",
    ]
    for partial in sorted(evidence_dir.glob("periodic-messages-*-partial/observations.jsonl")):
        relative_paths.append(partial.relative_to(evidence_dir).as_posix())
    return {relative: _file_record(evidence_dir / relative) for relative in relative_paths}


def _authorization_process(auth: dict, folder: str) -> dict:
    processes = _get(auth, "processes")
    if not isinstance(processes, list):
        raise AuditError("input_invalid")
    matches = [item for item in processes if isinstance(item, dict) and item.get("folder") == folder]
    if len(matches) != 1:
        raise AuditError("authorization_invalid")
    return matches[0]


def _audit_authorization(auth: dict, files: dict[str, dict], failures: set[str], started_at_map: dict) -> dict:
    required = _get(auth, "requiredSeconds")
    original = _get(auth, "originalRequiredSeconds")
    if required != REQUIRED_SECONDS or original != ORIGINAL_REQUIRED_SECONDS:
        _add_failure(failures, "authorization_invalid")
    if auth.get("keepOriginalStart") is not True:
        _add_failure(failures, "authorization_invalid")
    auth_at = _parse_time(_get(auth, "at"))
    start_hashes = {}
    for folder in ("observe-24h", "periodic-messages"):
        process = _authorization_process(auth, folder)
        declared = _get(process, "start_sha256", "authorization_invalid")
        actual = files[f"{folder}/start.json"]["sha256"]
        matched = isinstance(declared, str) and SHA256_RE.fullmatch(declared) is not None and declared.lower() == actual
        start_hashes[folder] = matched
        if not matched:
            _add_failure(failures, "start_digest_mismatch")
        started_at_map[folder] = process.get("start", {}).get("startedAt")
    if (auth.get("reason") != "user_changed_acceptance_duration"
            or auth.get("status") != "stop_requested"
            or not isinstance(auth.get("plannedStopSignal"), str)
            or "SIGINT" not in auth["plannedStopSignal"]):
        _add_failure(failures, "unauthorized_interruption")
    return {"at": auth_at, "startHashesMatched": start_hashes}


def _audit_manifest(evidence_dir: Path, repo_root: Path, auth: dict, starts: dict,
                    summaries: dict, files: dict[str, dict], failures: set[str]) -> dict:
    observer_path = _get(starts["observe-24h"], "manifestPath")
    periodic_path = _get(starts["periodic-messages"], "manifestPath")
    if observer_path != periodic_path:
        _add_failure(failures, "manifest_invalid")
    raw_path = _safe_relative(observer_path)
    manifest_path = _resolve_relative(repo_root, raw_path)
    try:
        manifest_raw = manifest_path.read_bytes()
    except OSError:
        _add_failure(failures, "manifest_invalid")
        return {
            "path": raw_path, "sha256": None, "declaredSha256": None,
            "digestMatched": False, "filesChecked": 0, "filesMatched": 0,
            "changedPaths": [],
        }
    manifest_sha = hashlib.sha256(manifest_raw).hexdigest()
    try:
        manifest = json.loads(manifest_raw)
    except (UnicodeError, ValueError):
        _add_failure(failures, "manifest_invalid")
        return {
            "path": raw_path, "sha256": manifest_sha, "declaredSha256": None,
            "digestMatched": False, "filesChecked": 0, "filesMatched": 0,
            "changedPaths": [],
        }
    entries = manifest.get("files") if isinstance(manifest, dict) else None
    if not isinstance(entries, list):
        _add_failure(failures, "manifest_invalid")
        entries = []
    if not entries:
        _add_failure(failures, "manifest_invalid")

    declared = []
    periodic_start_sha = starts["periodic-messages"].get("manifestSha256")
    if isinstance(periodic_start_sha, str):
        declared.append(periodic_start_sha.lower())
    summary_sha = summaries["periodic-messages"].get("manifestSha256")
    if isinstance(summary_sha, str):
        declared.append(summary_sha.lower())
    periodic_process = _authorization_process(auth, "periodic-messages")
    authorization_sha = periodic_process.get("start", {}).get("manifestSha256")
    if isinstance(authorization_sha, str):
        declared.append(authorization_sha.lower())
    if not declared or any(value != manifest_sha for value in declared):
        _add_failure(failures, "manifest_digest_mismatch")
    digest_matched = bool(declared) and all(value == manifest_sha for value in declared)

    if (summaries["observe-24h"].get("manifest", {}).get("status") != "ok"
            or summaries["observe-24h"].get("manifest", {}).get("changedPaths") != []
            or summaries["observe-24h"].get("version_unchanged") is not True):
        _add_failure(failures, "manifest_source_changed")
    if (summaries["periodic-messages"].get("manifestUnchanged") is not True
            or summaries["periodic-messages"].get("manifestChanges") != []):
        _add_failure(failures, "manifest_source_changed")

    changed_paths = []
    for entry in entries:
        try:
            relative = _safe_relative(_get(entry, "path", "manifest_invalid"), "manifest_invalid")
            expected = _get(entry, "sha256", "manifest_invalid")
            if not isinstance(expected, str) or SHA256_RE.fullmatch(expected) is None:
                raise AuditError("manifest_invalid")
        except AuditError:
            _add_failure(failures, "manifest_invalid")
            continue
        try:
            actual = hashlib.sha256(_resolve_relative(repo_root, relative).read_bytes()).hexdigest()
        except (AuditError, OSError):
            actual = None
        if actual != expected.lower():
            changed_paths.append(relative)
    if changed_paths:
        _add_failure(failures, "manifest_files_changed")
    return {
        "path": raw_path,
        "sha256": manifest_sha,
        "declaredSha256": declared[0] if declared else None,
        "digestMatched": digest_matched,
        "filesChecked": len(entries),
        "filesMatched": len(entries) - len(changed_paths),
        "changedPaths": changed_paths,
    }


def _audit_common_run(name: str, evidence_dir: Path, auth: dict, auth_at: datetime,
                      files: dict[str, dict], failures: set[str]) -> dict:
    start = _load_json(evidence_dir / name / "start.json")
    end = _load_json(evidence_dir / name / "end.json")
    summary = _load_json(evidence_dir / name / "summary.json")
    process = _authorization_process(auth, name)
    if start != process.get("start"):
        _add_failure(failures, "start_identity_mismatch")
    if files[f"{name}/start.json"]["sha256"] != _get(process, "start_sha256", "authorization_invalid").lower():
        _add_failure(failures, "start_digest_mismatch")

    started_at = _parse_time(_get(start, "startedAt"))
    ended_at = _parse_time(_get(end, "endedAt"))
    summary_started = _parse_time(_get(summary, "startedAt"))
    summary_ended = _parse_time(_get(summary, "endedAt"))
    end_started = _parse_time(_get(end, "startedAt"))
    if len({started_at, summary_started, end_started}) != 1 or summary_ended != ended_at:
        _add_failure(failures, "original_summary_not_preserved")

    requested = _get(summary, "requestedDurationSeconds")
    start_requested = start.get("requestedDurationSeconds")
    if (not _is_finite_number(requested) or not _is_finite_number(start_requested)
            or requested != ORIGINAL_REQUIRED_SECONDS
            or start_requested != ORIGINAL_REQUIRED_SECONDS):
        _add_failure(failures, "original_summary_not_preserved")
    if summary.get("stopReason") != "interrupted" or end.get("stopReason") != "interrupted":
        _add_failure(failures, "unauthorized_interruption")
    if summary.get("passed") is not False:
        _add_failure(failures, "original_summary_not_preserved")
    if name == "observe-24h":
        if (summary.get("verdict") != "incomplete" or end.get("verdict") != "incomplete"
                or summary.get("completed_duration") is not False):
            _add_failure(failures, "original_summary_not_preserved")
    else:
        if summary.get("durationComplete") is not False:
            _add_failure(failures, "original_summary_not_preserved")
        state = _load_json(evidence_dir / name / "state.json")
        if state.get("stopReason") != "interrupted" or state.get("phase") != "finished":
            _add_failure(failures, "unauthorized_interruption")

    actual = _get(summary, "actualDurationSeconds")
    end_actual = _get(end, "actualDurationSeconds")
    if not _is_finite_number(actual) or not _is_finite_number(end_actual):
        raise AuditError("input_invalid")
    if abs(actual - end_actual) > 0.001:
        _add_failure(failures, "original_summary_not_preserved")
    if actual + DURATION_EPSILON < REQUIRED_SECONDS:
        _add_failure(failures, "duration_below_required")
    wall_duration = (ended_at - started_at).total_seconds()
    if wall_duration < 0 or abs(actual - wall_duration) > WALL_DURATION_TOLERANCE_SECONDS:
        _add_failure(failures, "wall_duration_mismatch")
    if abs((ended_at - auth_at).total_seconds()) > END_ALIGNMENT_TOLERANCE_SECONDS:
        _add_failure(failures, "end_not_aligned_with_authorization")
    return {
        "start": start,
        "end": end,
        "summary": summary,
        "startedAt": start.get("startedAt"),
        "endedAt": end.get("endedAt"),
        "requestedDurationSeconds": requested,
        "actualDurationSeconds": actual,
        "wallDurationSeconds": round(wall_duration, 3),
        "originalTerminal": "interrupted/incomplete",
    }


def _percentile_free_gap(times: list[datetime]) -> float | None:
    if len(times) < 2:
        return None
    return round(max((right - left).total_seconds() for left, right in zip(times, times[1:])), 3)


def _audit_observer(run: dict, rows: list[dict], failures: set[str]) -> dict:
    start, summary, end = run["start"], run["summary"], run["end"]
    interval = _get(start, "intervalSeconds")
    timeout = _get(start, "requestTimeoutSeconds")
    summary_interval = summary.get("intervalSeconds")
    target_agent = _get(_get(start, "target"), "agentId")
    if not all(_is_finite_number(value) and value > 0
               for value in (interval, timeout, summary_interval)):
        raise AuditError("input_invalid")
    threshold = interval + 3 * timeout

    timestamps = []
    status_200 = hop_error_free = target_online = nonempty_capabilities = 0
    request_ids = []
    for row in rows:
        try:
            row_time = _parse_time(row.get("time"), "observer_timestamp_invalid")
            timestamps.append(row_time)
        except AuditError:
            _add_failure(failures, "observer_timestamp_invalid")
        status = row.get("httpStatus")
        if (isinstance(status, dict) and status.get("devices") == 200
                and status.get("agents") == 200 and status.get("session") == 200):
            status_200 += 1
        else:
            _add_failure(failures, "observer_http_error")
        hop_errors = row.get("hopErrors")
        if (isinstance(hop_errors, dict)
                and {"devices", "agents", "session"}.issubset(hop_errors)
                and all(value is None for value in hop_errors.values())):
            hop_error_free += 1
        else:
            _add_failure(failures, "observer_hop_error")
        online = row.get("agentOnline")
        if isinstance(online, dict) and online.get(target_agent) is True:
            target_online += 1
        else:
            _add_failure(failures, "observer_target_offline")
        capabilities = row.get("capabilities")
        target_capabilities = capabilities.get(target_agent) if isinstance(capabilities, dict) else None
        if (isinstance(target_capabilities, dict) and target_capabilities
                and all(type(value) is bool for value in target_capabilities.values())):
            nonempty_capabilities += 1
        else:
            _add_failure(failures, "observer_capabilities_empty")
        if row.get("errorCode") is not None:
            _add_failure(failures, "observer_request_error")
        request_id = row.get("requestId")
        if isinstance(request_id, str) and request_id:
            request_ids.append(request_id)
        else:
            _add_failure(failures, "observer_row_invalid")

    if not rows:
        _add_failure(failures, "observer_no_samples")
    recorded_samples = len(rows)
    reported_samples = summary.get("sample_count")
    if (not isinstance(reported_samples, int) or isinstance(reported_samples, bool)
            or reported_samples != recorded_samples
            or end.get("observationCount") != reported_samples
            or summary.get("core", {}).get("attempts") != reported_samples
            or summary.get("agents", {}).get("attempts") != reported_samples
            or summary.get("sessionReads", {}).get("attempts") != reported_samples):
        _add_failure(failures, "observer_sample_count_mismatch")
    if len(set(request_ids)) != len(request_ids):
        _add_failure(failures, "observer_duplicate_request_id")
    for previous, current in zip(timestamps, timestamps[1:]):
        if current <= previous:
            _add_failure(failures, "observer_timestamp_not_increasing")
            break

    first = timestamps[0] if timestamps else None
    last = timestamps[-1] if timestamps else None
    observed_start = _parse_time(_get(start, "startedAt"))
    observed_end = _parse_time(_get(end, "endedAt"))
    if first is not None and (first < observed_start
                              or (first - observed_start).total_seconds() > threshold):
        _add_failure(failures, "observer_coverage_missing")
    if last is not None and (last > observed_end
                             or (observed_end - last).total_seconds() > threshold):
        _add_failure(failures, "observer_coverage_missing")
    max_gap = _percentile_free_gap(timestamps)
    if max_gap is not None and max_gap > threshold + DURATION_EPSILON:
        _add_failure(failures, "observer_gap_too_large")

    cutoff = observed_start + timedelta(seconds=REQUIRED_SECONDS)
    first_three_hours = [value for value in timestamps if value <= cutoff]

    def stats(values: list[datetime]) -> dict:
        return {
            "sampleCount": len(values),
            "firstTimestamp": values[0].isoformat().replace("+00:00", "Z") if values else None,
            "lastTimestamp": values[-1].isoformat().replace("+00:00", "Z") if values else None,
            "spanSeconds": round((values[-1] - values[0]).total_seconds(), 3) if len(values) > 1 else 0.0,
            "maxGapSeconds": _percentile_free_gap(values),
        }

    return {
        **{key: run[key] for key in (
            "startedAt", "endedAt", "requestedDurationSeconds", "actualDurationSeconds",
            "wallDurationSeconds", "originalTerminal")},
        "intervalSeconds": interval,
        "requestTimeoutSeconds": timeout,
        "maxAllowedGapSeconds": round(threshold, 3),
        "full": {
            **stats(timestamps),
            "httpStatus200Samples": status_200,
            "hopErrorFreeSamples": hop_error_free,
            "targetOnlineSamples": target_online,
            "nonemptyCapabilitySamples": nonempty_capabilities,
            "uniqueRequestIds": len(set(request_ids)),
        },
        "first3h": stats(first_three_hours),
    }


def _periodic_partial_identities(evidence_dir: Path, files: dict[str, dict],
                                 failures: set[str]) -> tuple[set[str], int]:
    identities = set()
    keys = set()
    for relative in files:
        if not relative.startswith("periodic-messages-") or not relative.endswith("-partial/observations.jsonl"):
            continue
        try:
            rows = _load_jsonl(evidence_dir / relative)
        except AuditError:
            _add_failure(failures, "periodic_partial_mixed")
            continue
        for row in rows:
            for field in ("key", "clientMessageId", "generationId", "messageId"):
                value = row.get(field)
                if isinstance(value, str) and value:
                    identities.add(value)
            key = row.get("key")
            if isinstance(key, str) and key:
                keys.add(key)
    return identities, len(keys)


def _audit_periodic(run: dict, rows: list[dict], verification: dict, reconciliation: dict | None,
                    partial_identities: set[str], excluded_partial_keys: int,
                    observations_sha256: str, failures: set[str]) -> dict:
    start, summary, end = run["start"], run["summary"], run["end"]
    interval = _get(start, "intervalSeconds")
    timeout = _get(start, "observationTimeoutSeconds")
    if not all(_is_finite_number(value) and value > 0
               for value in (interval, timeout, summary.get("intervalSeconds"),
                             summary.get("observationTimeoutSeconds"))):
        raise AuditError("input_invalid")
    threshold = interval + 3 * timeout
    observation_start = _parse_time(_get(start, "observationStartedAt"))
    observation_end = _parse_time(_get(end, "endedAt"))

    recorded_rounds = len(rows)
    if recorded_rounds == 0:
        _add_failure(failures, "periodic_round_count_mismatch")
    if (summary.get("roundsAttempted") != recorded_rounds
            or summary.get("roundsCompleted") != recorded_rounds
            or summary.get("roundsHistoryMatched") != recorded_rounds
            or summary.get("messagePosts") != recorded_rounds
            or summary.get("totalPostCount") != recorded_rounds
            or end.get("roundsAttempted") != recorded_rounds
            or end.get("totalPostCount") != recorded_rounds):
        _add_failure(failures, "periodic_round_count_mismatch")

    original_ids: dict[str, set[str]] = {field: set() for field in (
        "key", "clientMessageId", "generationId", "messageId")}
    terminal_completed = post_count_one = history_matched = 0
    started_times, ended_times = [], []
    for index, row in enumerate(rows):
        round_number = row.get("round")
        if round_number != index + 1:
            _add_failure(failures, "periodic_order_invalid")
        if row.get("terminal") in SUCCESS_TERMINALS:
            terminal_completed += 1
        else:
            _add_failure(failures, "periodic_terminal_invalid")
        if type(row.get("postCount")) is int and row["postCount"] == 1:
            post_count_one += 1
        else:
            _add_failure(failures, "periodic_post_count_invalid")
        if row.get("historyUserRows") == 1 and row.get("historyAssistantRows") == 1:
            history_matched += 1
        else:
            _add_failure(failures, "periodic_history_mismatch")
        for field in original_ids:
            value = row.get(field)
            if isinstance(value, str) and value:
                original_ids[field].add(value)
            else:
                _add_failure(failures, "periodic_identity_invalid")
        if row.get("key") != row.get("clientMessageId"):
            _add_failure(failures, "periodic_identity_invalid")
        try:
            started = _parse_time(row.get("startedAt"), "periodic_timestamp_invalid")
            ended = _parse_time(row.get("endedAt"), "periodic_timestamp_invalid")
        except AuditError:
            _add_failure(failures, "periodic_timestamp_invalid")
            continue
        started_times.append(started)
        ended_times.append(ended)
        if (started < observation_start or ended > observation_end
                or ended <= started):
            _add_failure(failures, "periodic_order_invalid")

    for field, values in original_ids.items():
        if len(values) != len(rows):
            _add_failure(failures, "periodic_identity_duplicate")
    all_original_identities = set().union(*original_ids.values())
    if partial_identities & all_original_identities:
        _add_failure(failures, "periodic_partial_mixed")
    for previous, current in zip(started_times, started_times[1:]):
        if current <= previous:
            _add_failure(failures, "periodic_order_invalid")
            break
    max_gap = _percentile_free_gap(started_times)
    if max_gap is not None and max_gap > threshold + DURATION_EPSILON:
        _add_failure(failures, "periodic_interval_too_large")
    if (started_times and ((started_times[0] - observation_start).total_seconds() > threshold
                           or started_times[0] < observation_start)):
        _add_failure(failures, "periodic_coverage_missing")
    if (ended_times and ((observation_end - ended_times[-1]).total_seconds() > threshold
                         or ended_times[-1] > observation_end)):
        _add_failure(failures, "periodic_coverage_missing")

    cutoff = observation_start + timedelta(seconds=REQUIRED_SECONDS)
    first_three_hours = [value for value in started_times if value <= cutoff]
    if len(first_three_hours) < MINIMUM_PERIODIC_FIRST_THREE_HOURS:
        _add_failure(failures, "periodic_first3h_insufficient")

    verification_valid = isinstance(verification, dict) and verification.get("passed") is True
    verification_identities = {}
    if not isinstance(verification, dict):
        _add_failure(failures, "periodic_history_verification_invalid")
    else:
        if (verification.get("containsMessageContent") is not False
                or verification.get("httpStatus") != 200
                or verification.get("messagePosts") != 0
                or verification.get("orderedByOriginalInput") is not True
                or verification.get("roundsChecked") != recorded_rounds
                or verification.get("sourceObservationsSha256") != observations_sha256):
            verification_valid = False
        rounds = verification.get("rounds")
        if not isinstance(rounds, list) or len(rounds) != recorded_rounds:
            verification_valid = False
        else:
            for item in rounds:
                if not isinstance(item, dict):
                    verification_valid = False
                    continue
                verification_identities[item.get("round")] = item
        previous_history_index = -1
        for row in rows:
            item = verification_identities.get(row.get("round"))
            indexes = item.get("historyIndexes") if isinstance(item, dict) else None
            if (not isinstance(indexes, list) or len(indexes) != 2
                    or not all(type(index) is int for index in indexes)
                    or not previous_history_index < indexes[0] < indexes[1]):
                verification_valid = False
            else:
                previous_history_index = indexes[1]
            if (not isinstance(item, dict) or item.get("passed") is not True
                    or item.get("userRows") != 1 or item.get("assistantRows") != 1
                    or item.get("assistantIdentityMatched") is not True
                    or item.get("clientMessageId") != row.get("clientMessageId")
                    or item.get("generationId") != row.get("generationId")
                    or item.get("messageId") != row.get("messageId")):
                verification_valid = False
                break
    if not verification_valid:
        _add_failure(failures, "periodic_history_verification_invalid")

    boundary = {
        "present": reconciliation is not None,
        "additionalCompletedPosts": 0,
        "excludedFromFirst3h": True,
        "clientMessageId": None,
        "generationId": None,
    }
    if reconciliation is not None:
        if (not isinstance(reconciliation, dict)
                or reconciliation.get("recordedRoundIds") != recorded_rounds
                or reconciliation.get("excludedPriorPartialRoundIds") != excluded_partial_keys
                or reconciliation.get("messagePostsPerformedByReconciliation") != 0
                or reconciliation.get("sessionId") != start.get("sessionId")):
            _add_failure(failures, "periodic_boundary_invalid")
            unmatched = reconciliation.get("unmatched") if isinstance(reconciliation, dict) else None
        else:
            unmatched = reconciliation.get("unmatched")
        if not isinstance(unmatched, list) or len(unmatched) != 1:
            _add_failure(failures, "periodic_boundary_invalid")
            unmatched = []
        seen_boundary_ids = set()
        for extra in unmatched:
            if not isinstance(extra, dict):
                _add_failure(failures, "periodic_boundary_invalid")
                continue
            client_id = extra.get("clientMessageId")
            generation = extra.get("generation")
            generation_id = generation.get("generationId") if isinstance(generation, dict) else None
            history = extra.get("historyRows")
            if (not isinstance(client_id, str) or not client_id
                    or client_id in all_original_identities or client_id in partial_identities
                    or client_id in seen_boundary_ids):
                _add_failure(failures, "periodic_boundary_mixed")
            seen_boundary_ids.add(client_id)
            if (not isinstance(generation, dict)
                    or generation.get("clientMessageId") != client_id
                    or not isinstance(generation_id, str) or not generation_id
                    or generation_id in all_original_identities
                    or generation_id in partial_identities):
                _add_failure(failures, "periodic_boundary_mixed")
            if not isinstance(generation, dict) or generation.get("state") not in SUCCESS_TERMINALS:
                _add_failure(failures, "periodic_boundary_incomplete")
            if not isinstance(history, list) or len(history) != 2:
                _add_failure(failures, "periodic_boundary_history_mismatch")
                history = []
            user_rows = [row for row in history if isinstance(row, dict) and row.get("role") == "user"]
            assistant_rows = [row for row in history if isinstance(row, dict) and row.get("role") == "assistant"]
            assistant_id = assistant_rows[0].get("id") if len(assistant_rows) == 1 else None
            if (len(user_rows) != 1 or len(assistant_rows) != 1
                    or assistant_rows[0].get("generationId") != generation_id
                    or not isinstance(assistant_id, str) or not assistant_id
                    or assistant_id in all_original_identities
                    or assistant_id in partial_identities):
                _add_failure(failures, "periodic_boundary_history_mismatch")
            elif extra.get("httpStatus") != 200:
                _add_failure(failures, "periodic_boundary_history_mismatch")
            if not any(code.startswith("periodic_boundary_") for code in failures):
                boundary.update({
                    "additionalCompletedPosts": 1,
                    "clientMessageId": client_id,
                    "generationId": generation_id,
                    "terminal": generation.get("state"),
                    "historyUserRows": len(user_rows),
                    "historyAssistantRows": len(assistant_rows),
                })
    boundary["recordedMessagePosts"] = summary.get("messagePosts")
    boundary["actualMessagePostsAtLeast"] = len(rows) + boundary["additionalCompletedPosts"]
    if isinstance(verification, dict):
        expected_history_rows = (2 * recorded_rounds + 2 * excluded_partial_keys
                                 + 2 * boundary["additionalCompletedPosts"])
        if verification.get("historyRowsReceived") != expected_history_rows:
            verification_valid = False
            _add_failure(failures, "periodic_history_verification_invalid")

    def stats(values: list[datetime]) -> dict:
        return {
            "roundCount": len(values),
            "firstStartedAt": values[0].isoformat().replace("+00:00", "Z") if values else None,
            "lastStartedAt": values[-1].isoformat().replace("+00:00", "Z") if values else None,
            "spanSeconds": round((values[-1] - values[0]).total_seconds(), 3) if len(values) > 1 else 0.0,
            "maxGapSeconds": _percentile_free_gap(values),
        }

    return {
        **{key: run[key] for key in (
            "startedAt", "endedAt", "requestedDurationSeconds", "actualDurationSeconds",
            "wallDurationSeconds", "originalTerminal")},
        "observationStartedAt": start.get("observationStartedAt"),
        "intervalSeconds": interval,
        "observationTimeoutSeconds": timeout,
        "maxAllowedGapSeconds": round(threshold, 3),
        "messagePostsRecorded": summary.get("messagePosts"),
        "completedPostsObserved": terminal_completed + boundary["additionalCompletedPosts"],
        "actualMessagePostsAtLeast": boundary["actualMessagePostsAtLeast"],
        "summaryPostCounts": {
            "messagePosts": summary.get("messagePosts"),
            "roundsAttempted": summary.get("roundsAttempted"),
            "totalPostCount": summary.get("totalPostCount"),
        },
        "full": {
            **stats(started_times),
            "terminalCompletedCount": terminal_completed,
            "postCountOneCount": post_count_one,
            "historyMatchedCount": history_matched,
            "uniqueKeyCount": len(original_ids["key"]),
            "uniqueClientMessageIdCount": len(original_ids["clientMessageId"]),
            "uniqueGenerationIdCount": len(original_ids["generationId"]),
            "uniqueMessageIdCount": len(original_ids["messageId"]),
        },
        "first3h": stats(first_three_hours),
        "finalHistoryVerification": {
            "present": isinstance(verification, dict),
            "passed": verification_valid,
            "historyRowsReceived": verification.get("historyRowsReceived") if isinstance(verification, dict) else None,
            "roundsChecked": verification.get("roundsChecked") if isinstance(verification, dict) else None,
        },
        "stopBoundary": boundary,
    }

def audit_evidence(evidence_dir: Path | str, *, repo_root: Path | str | None = None) -> dict:
    evidence_dir = Path(evidence_dir).expanduser().resolve()
    repo_root = Path(repo_root).expanduser().resolve() if repo_root is not None else REPO_ROOT.resolve()
    files = _recorded_files(evidence_dir)
    failures: set[str] = set()
    result = {
        "schemaVersion": 1,
        "verdict": "invalid",
        "passed": False,
        "requiredSeconds": REQUIRED_SECONDS,
        "minimumDurationSeconds": REQUIRED_SECONDS,
        "originalRequiredSeconds": ORIGINAL_REQUIRED_SECONDS,
        "failureCategories": [],
        "files": files,
    }
    try:
        auth = _load_json(evidence_dir / "acceptance-duration-amendment.json")
        if not isinstance(auth, dict):
            raise AuditError("input_invalid")
        started_at_map: dict[str, str] = {}
        authorization = _audit_authorization(auth, files, failures, started_at_map)
        observe_run = _audit_common_run(
            "observe-24h", evidence_dir, auth, authorization["at"], files, failures)
        periodic_run = _audit_common_run(
            "periodic-messages", evidence_dir, auth, authorization["at"], files, failures)
        manifest = _audit_manifest(
            evidence_dir, repo_root, auth,
            {"observe-24h": observe_run["start"], "periodic-messages": periodic_run["start"]},
            {"observe-24h": observe_run["summary"], "periodic-messages": periodic_run["summary"]},
            files, failures,
        )

        observer_path = evidence_dir / "observe-24h" / "observations.jsonl"
        observer_rows = _load_jsonl(observer_path)
        observer = _audit_observer(observe_run, observer_rows, failures)

        periodic_path = evidence_dir / "periodic-messages" / "observations.jsonl"
        periodic_rows = _load_jsonl(periodic_path)
        partial_identities, excluded_partial_keys = _periodic_partial_identities(
            evidence_dir, files, failures)
        verification_path = evidence_dir / "periodic-final-history-verification.json"
        verification = _load_json(verification_path)
        reconciliation_path = evidence_dir / "periodic-stop-boundary-reconciliation.json"
        reconciliation = _load_json(reconciliation_path) if reconciliation_path.exists() else None
        periodic = _audit_periodic(
            periodic_run, periodic_rows, verification, reconciliation,
            partial_identities, excluded_partial_keys,
            files["periodic-messages/observations.jsonl"]["sha256"], failures,
        )
        result.update({
            "authorization": {
                "at": authorization["at"].isoformat(),
                "startHashesMatched": authorization["startHashesMatched"],
                "reason": auth.get("reason"),
                "status": auth.get("status"),
            },
            "manifest": manifest,
            "observer": observer,
            "periodic": periodic,
        })
    except AuditError as error:
        _add_failure(failures, error.code)

    result["failureCategories"] = sorted(failures)
    invalid_prefixes = ("input_", "authorization_invalid", "manifest_invalid")
    if failures:
        result["verdict"] = "invalid" if any(
            code.startswith(invalid_prefixes) for code in failures) else "failed"
    else:
        result["verdict"] = "passed"
        result["passed"] = True
    return result


def write_audit(output_path: Path | str, result: dict) -> None:
    output_path = Path(output_path).expanduser()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with output_path.open("x", encoding="utf-8") as stream:
            stream.write(json.dumps(result, ensure_ascii=False, allow_nan=False, sort_keys=True) + "\n")
    except FileExistsError as error:
        raise AuditError("OUTPUT_EXISTS") from error


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path, required=True,
                        help="Evidence root containing both original runs")
    parser.add_argument("--output", type=Path, required=True,
                        help="New JSON audit output; existing files are never overwritten")
    args = parser.parse_args(argv)
    result = audit_evidence(args.evidence_dir)
    try:
        write_audit(args.output, result)
    except AuditError as error:
        print(json.dumps({"errorCode": error.code}, ensure_ascii=False))
        return 1
    print(json.dumps({
        "verdict": result["verdict"],
        "passed": result["passed"],
        "failureCategories": result["failureCategories"],
    }, ensure_ascii=False, sort_keys=True))
    return 0 if result["passed"] else 2 if result["verdict"] == "failed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
