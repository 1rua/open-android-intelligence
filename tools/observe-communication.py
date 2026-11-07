#!/usr/bin/env python3
"""24 小时 Core/Agent 只读通信观察器。
每次采样先调用现有 CoreClient.devices()，让客户端按标准配置完成凭据续签；
随后用客户端当前 token GET Agent 列表。工具不发送消息、不创建会话、不读取
密码，不记录响应中的会话正文。后台启停由调用方负责。
"""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import HTTPRedirectHandler, Request, build_opener
import uuid
ROOT = Path(__file__).resolve().parents[1]
AGENT_API_PREFIX = "/open-android-intelligence/v2/agents"
DEFAULT_DURATION = 24 * 60 * 60
DEFAULT_INTERVAL = 10.0
DEFAULT_TIMEOUT = 5.0
MIN_PASS_SECONDS = 24 * 60 * 60
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
CAPABILITY_KEYS = (
    "list", "history", "create", "send", "steer", "rename", "archive", "delete",
    "cancel", "stream", "commands", "approvals", "attachments", "generationRecovery",
    "eventSync", "operationRecovery",
)
SAFE_CODE_RE = re.compile(r"[A-Za-z0-9_.:-]{1,160}")
AGENT_ID_RE = re.compile(r"[A-Za-z0-9_-]{1,80}")
SHA256_RE = re.compile(r"[0-9a-fA-F]{64}")
def _load_core_client():
    try:
        from oai_core.client import CoreClient, CoreClientError
    except ModuleNotFoundError as error:
        if error.name != "oai_core":
            raise
        source = ROOT / "oai-core" / "src"
        if not source.is_dir():
            raise
        sys.path.insert(0, str(source))
        from oai_core.client import CoreClient, CoreClientError
    return CoreClient, CoreClientError
CoreClient, CoreClientError = _load_core_client()
class ToolError(RuntimeError):
    def __init__(self, code: str, status: int | None = None):
        self.code = _safe_code(code, "OBSERVER_ERROR")
        self.status = status
        super().__init__(self.code)
class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None
OPENER = build_opener(_NoRedirect())
def _safe_code(value, fallback: str) -> str:
    return value if isinstance(value, str) and SAFE_CODE_RE.fullmatch(value) else fallback
def _hop_error_codes(row: dict) -> dict:
    value = row.get("hopErrors")
    return value if isinstance(value, dict) else {}
def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
def _write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True) + "\n", encoding="utf-8")
def _append_jsonl(path: Path, value: dict) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True) + "\n")
        stream.flush()
def load_manifest(path: Path) -> list[tuple[str, str]]:
    try:
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError) as error:
        raise ToolError("MANIFEST_JSON_INVALID") from error
    if isinstance(raw, dict) and "files" in raw:
        items = raw["files"]
    elif isinstance(raw, list):
        items = raw
    else:
        raise ToolError("MANIFEST_SCHEMA_INVALID")
    if not isinstance(items, list) or not items:
        raise ToolError("MANIFEST_SCHEMA_INVALID")
    result, seen = [], set()
    for item in items:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str) or not isinstance(item.get("sha256"), str):
            raise ToolError("MANIFEST_ENTRY_INVALID")
        relative, digest = item["path"], item["sha256"]
        if (not relative or relative.startswith(("/", "\\")) or "\\" in relative
                or re.match(r"^[A-Za-z]:", relative)
                or any(part in {"", ".", ".."} for part in relative.split("/"))):
            raise ToolError("MANIFEST_PATH_INVALID")
        if SHA256_RE.fullmatch(digest) is None or relative in seen:
            raise ToolError("MANIFEST_DIGEST_INVALID")
        seen.add(relative)
        result.append((relative, digest.lower()))
    return result
def check_manifest(entries: list[tuple[str, str]], *, root: Path = ROOT) -> dict:
    root = Path(root).absolute().resolve()
    changed = []
    for relative, expected in entries:
        target = (root / relative).resolve()
        if not target.is_relative_to(root):
            changed.append(relative)
            continue
        try:
            actual = hashlib.sha256(target.read_bytes()).hexdigest()
        except OSError:
            actual = None
        if actual != expected:
            changed.append(relative)
    return {"status": "ok" if not changed else "mismatch", "changedPaths": changed, "checkedAt": now()}
def _agent_get(client, suffix: str, timeout: float) -> tuple[dict, int, float]:
    token = getattr(client, "token", None)
    if not isinstance(token, str) or not token:
        raise ToolError("AUTH_TOKEN_MISSING")
    request = Request(client.url + AGENT_API_PREFIX + suffix, headers={
        "Accept": "application/json", "Authorization": "Bearer " + token,
    }, method="GET")
    started = time.monotonic()
    try:
        with (getattr(client, "_opener", None) or OPENER).open(request, timeout=timeout) as response:
            status, payload = int(getattr(response, "status", 200)), response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as error:
        try:
            payload = error.read(MAX_RESPONSE_BYTES + 1)
        except OSError:
            payload = b""
        try:
            body = json.loads(payload)
            failure = body.get("error") if isinstance(body, dict) else None
            code = failure.get("code") if isinstance(failure, dict) else body.get("code") if isinstance(body, dict) else None
        except (UnicodeError, ValueError):
            code = None
        raise ToolError(_safe_code(code, "AGENT_HTTP_ERROR"), error.code) from None
    except (URLError, TimeoutError, OSError, http.client.HTTPException) as error:
        raise ToolError("AGENT_UNREACHABLE") from error
    if len(payload) > MAX_RESPONSE_BYTES:
        raise ToolError("AGENT_RESPONSE_TOO_LARGE", status)
    try:
        value = json.loads(payload)
    except (UnicodeError, ValueError) as error:
        raise ToolError("AGENT_RESPONSE_INVALID", status) from error
    if not isinstance(value, dict):
        raise ToolError("AGENT_RESPONSE_INVALID", status)
    return value, status, round((time.monotonic() - started) * 1000, 3)
def _agents(payload: dict) -> dict[str, dict]:
    if isinstance(payload.get("data"), dict):
        payload = payload["data"]
    agents = payload.get("agents")
    if not isinstance(agents, list):
        raise ToolError("AGENT_RESPONSE_INVALID", 200)
    result = {}
    for item in agents:
        if (not isinstance(item, dict) or not isinstance(item.get("id"), str)
                or AGENT_ID_RE.fullmatch(item["id"]) is None or type(item.get("online")) is not bool
                or item["id"] in result):
            raise ToolError("AGENT_RESPONSE_INVALID", 200)
        capabilities = item.get("capabilities", {})
        if not isinstance(capabilities, dict) or any(type(value) is not bool for value in capabilities.values()):
            raise ToolError("AGENT_RESPONSE_INVALID", 200)
        result[item["id"]] = {"online": item["online"],
                              "capabilities": {key: capabilities[key] for key in CAPABILITY_KEYS if key in capabilities}}
    return result
def observe_once(client, *, agent_id: str | None, session_id: str | None,
                 read_path: str, timeout: float) -> dict:
    """一次只读采样，返回严格限定字段的 JSONL 记录。"""
    request_id, total_started = "obs_" + uuid.uuid4().hex, time.monotonic()
    devices_status = agents_status = session_status = None
    devices_latency = agents_latency = session_latency = None
    core_error = agent_error = session_error = None
    agents = {}
    started = time.monotonic()
    try:
        client.devices()
        devices_status = 200
    except CoreClientError as error:
        devices_status, core_error = error.status, _safe_code(error.code, "CORE_REQUEST_FAILED")
    except Exception:
        core_error = "CORE_REQUEST_FAILED"
    devices_latency = round((time.monotonic() - started) * 1000, 3)
    if core_error is None:
        started = time.monotonic()
        try:
            payload, agents_status, _ = _agent_get(client, "", timeout)
            agents = _agents(payload)
        except ToolError as error:
            agents_status, agent_error = error.status, error.code
        agents_latency = round((time.monotonic() - started) * 1000, 3)
    missing = agent_id is not None and agent_id not in agents
    offline = (agent_id in agents and agents[agent_id]["online"] is not True
               if agent_id is not None else any(value["online"] is not True for value in agents.values()))
    if session_id is not None and agent_id is not None and not missing and not offline:
        suffix = "/" + quote(agent_id, safe="") + "/sessions/" + quote(session_id, safe="") + "/" + read_path
        started = time.monotonic()
        try:
            payload, session_status, _ = _agent_get(client, suffix, timeout)
            if isinstance(payload.get("data"), dict):
                payload = payload["data"]
            valid = isinstance(payload.get("messages"), list) if read_path == "history" else (
                "generation" in payload and (payload["generation"] is None or isinstance(payload["generation"], dict)))
            if not valid:
                raise ToolError("AGENT_RESPONSE_INVALID", session_status)
        except ToolError as error:
            session_status, session_error = error.status, error.code
        session_latency = round((time.monotonic() - started) * 1000, 3)
    error_code = core_error or agent_error or ("AGENT_NOT_FOUND" if missing else "AGENT_OFFLINE" if offline
                                               else session_error or ("NO_AGENTS" if agent_id is None and not agents else None))
    return {
        "time": now(),
        "httpStatus": {"devices": devices_status, "agents": agents_status, "session": session_status},
        "agentOnline": {key: value["online"] for key, value in agents.items()},
        "capabilities": {key: value["capabilities"] for key, value in agents.items()},
        "requestId": request_id,
        "latencyMs": {"devices": devices_latency, "agents": agents_latency, "session": session_latency,
                      "total": round((time.monotonic() - total_started) * 1000, 3)},
        "hopErrors": {"devices": core_error, "agents": agent_error, "session": session_error},
        "errorCode": error_code,
    }
def summarize(observations: list[dict], *, started_at: str, ended_at: str, actual_duration: float,
              requested_duration: float, interval: float, stop_reason: str, manifest_status: str,
              changed_paths: list[str], target_agent_id: str | None, fatal_code: str | None = None) -> dict:
    """统计真实失败；Core 请求失败和单个 Agent offline 分开记录。"""
    statuses = [row.get("httpStatus", {}) for row in observations]
    core_failures = sum(status.get("devices") != 200 or _hop_error_codes(row).get("devices") is not None
                        for row, status in zip(observations, statuses))
    agent_attempts = sum(status.get("agents") is not None or _hop_error_codes(row).get("agents") is not None
                         for row, status in zip(observations, statuses))
    agent_failures = sum(
        (status.get("agents") is not None or _hop_error_codes(row).get("agents") is not None)
        and (status.get("agents") != 200 or _hop_error_codes(row).get("agents") is not None)
        for row, status in zip(observations, statuses)
    )
    session_attempts = sum(status.get("session") is not None or _hop_error_codes(row).get("session") is not None
                           for row, status in zip(observations, statuses))
    session_failures = sum(
        (status.get("session") is not None or _hop_error_codes(row).get("session") is not None)
        and (status.get("session") != 200 or _hop_error_codes(row).get("session") is not None)
        for row, status in zip(observations, statuses)
    )
    empty_agents = sum(status.get("agents") == 200 and not row.get("agentOnline")
                       for row, status in zip(observations, statuses))
    offline = Counter(agent for row in observations for agent, value in row.get("agentOnline", {}).items() if value is False)
    missing_target = sum(target_agent_id is not None and status.get("agents") == 200
                         and target_agent_id not in row.get("agentOnline", {})
                         for row, status in zip(observations, statuses))
    errors = [row.get("errorCode") for row in observations if isinstance(row.get("errorCode"), str)]
    target_offline = offline.get(target_agent_id, 0) if target_agent_id is not None else sum(offline.values())
    no_agents = target_agent_id is None and empty_agents > 0
    duration_met = actual_duration + 1e-6 >= requested_duration
    completed_duration = stop_reason == "completed" and duration_met
    minimum_24h = actual_duration + 1e-6 >= MIN_PASS_SECONDS and requested_duration + 1e-6 >= MIN_PASS_SECONDS
    version_unchanged = manifest_status == "ok"
    hard_failure = bool(core_failures or agent_failures or session_failures or target_offline or missing_target
                        or no_agents or errors or not observations)
    categories = sorted(filter(None, [
        "core_request_failed" if core_failures else "",
        "agent_api_failed" if agent_failures else "",
        "agent_offline" if target_offline else "",
        "agent_missing" if missing_target else "",
        "no_agents" if no_agents else "",
        "session_read_failed" if session_failures else "",
        "request_error" if errors else "",
        "manifest_invalid" if manifest_status not in {"ok", "not_provided"} else "",
        "version_not_verified" if manifest_status == "not_provided" else "",
        "source_changed" if stop_reason == "source_changed" else "",
        "stopped" if stop_reason in {"stopped", "interrupted"} else "",
        "fatal_error" if stop_reason in {"failed", "fatal_error"} else "",
        "duration_not_met" if not duration_met else "",
        "duration_below_24h" if not minimum_24h else "",
        "no_observations" if not observations else "",
    ]))
    if stop_reason in {"source_changed", "manifest_invalid"} or manifest_status not in {"ok", "not_provided"}:
        verdict = "invalid"
    elif hard_failure or stop_reason in {"failed", "fatal_error"}:
        verdict = "failed"
    elif stop_reason in {"stopped", "interrupted"}:
        verdict = "incomplete"
    elif not completed_duration or not minimum_24h or not version_unchanged:
        verdict = "incomplete"
    else:
        verdict = "passed"
    core_status = "unknown" if not observations else "failed" if core_failures else "healthy"
    agent_status = ("failed" if agent_failures or missing_target or no_agents else
                    "degraded" if sum(offline.values()) else "healthy" if agent_attempts else "unknown")
    session_status = "not_requested" if not session_attempts else "failed" if session_failures else "healthy"
    summary = {
        "startedAt": started_at, "endedAt": ended_at,
        "actualDurationSeconds": round(actual_duration, 3), "requestedDurationSeconds": round(requested_duration, 3),
        "intervalSeconds": round(interval, 3), "stopReason": stop_reason, "verdict": verdict,
        "passed": verdict == "passed", "completed_duration": completed_duration,
        "version_unchanged": version_unchanged, "sample_count": len(observations),
        "core": {"attempts": len(observations), "failures": core_failures, "status": core_status},
        "agents": {"attempts": agent_attempts, "apiFailures": agent_failures,
                   "offlineObservations": sum(offline.values()), "targetOfflineObservations": target_offline,
                   "offlineByAgent": dict(sorted(offline.items())), "status": agent_status},
        "sessionReads": {"attempts": session_attempts, "failures": session_failures, "status": session_status},
        "manifest": {"status": manifest_status, "changedPaths": changed_paths},
        "failureCategories": categories,
    }
    if fatal_code:
        summary["fatalErrorCode"] = _safe_code(fatal_code, "OBSERVER_INTERNAL_ERROR")
    return summary
def _finish(output_dir: Path, observations: list[dict], *, started_at: str, started_monotonic: float,
            duration: float, interval: float, stop_reason: str, manifest_status: str,
            changed_paths: list[str], target_agent_id: str | None, fatal_code: str | None = None) -> dict:
    ended_at = now()
    summary = summarize(observations, started_at=started_at, ended_at=ended_at,
                        actual_duration=max(0.0, time.monotonic() - started_monotonic),
                        requested_duration=duration, interval=interval, stop_reason=stop_reason,
                        manifest_status=manifest_status, changed_paths=changed_paths,
                        target_agent_id=target_agent_id, fatal_code=fatal_code)
    _write_json(output_dir / "end.json", {"startedAt": started_at, "endedAt": ended_at, "actualDurationSeconds": summary["actualDurationSeconds"], "stopReason": stop_reason, "observationCount": len(observations), "verdict": summary["verdict"], "passed": summary["passed"], "completed_duration": summary["completed_duration"], "version_unchanged": summary["version_unchanged"]})
    _write_json(output_dir / "summary.json", summary)
    return summary
def run_observer(*, config_path: Path, output_dir: Path, duration: float, interval: float, timeout: float,
                 agent_id: str | None, session_id: str | None, read_path: str,
                 manifest_path: Path | None = None) -> dict:
    if any(not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0
           for value in (duration, interval, timeout)):
        raise ToolError("DURATION_INTERVAL_TIMEOUT_INVALID")
    if session_id is not None and agent_id is None:
        raise ToolError("AGENT_ID_REQUIRED_FOR_SESSION")
    if read_path not in {"history", "generation"}:
        raise ToolError("SESSION_READ_PATH_INVALID")
    output_dir = Path(output_dir).expanduser()
    output_dir.mkdir(parents=True, exist_ok=True)
    if (output_dir / "summary.json").exists() or (output_dir / "observations.jsonl").exists():
        raise ToolError("OUTPUT_DIRECTORY_NOT_EMPTY")
    entries, manifest_status, changed_paths = [], "not_provided", []
    if manifest_path is not None:
        entries = load_manifest(Path(manifest_path))
        state = check_manifest(entries)
        manifest_status, changed_paths = state["status"], state["changedPaths"]
    started_at, started_monotonic = now(), time.monotonic()
    _write_json(output_dir / "start.json", {"startedAt": started_at, "pid": os.getpid(), "requestedDurationSeconds": round(duration, 3), "intervalSeconds": round(interval, 3), "requestTimeoutSeconds": round(timeout, 3), "target": {"agentId": agent_id, "sessionId": session_id, "readPath": read_path}, "manifestPath": str(manifest_path) if manifest_path else None})
    if manifest_path is not None and manifest_status != "ok":
        return _finish(output_dir, [], started_at=started_at, started_monotonic=started_monotonic,
                       duration=duration, interval=interval, stop_reason="manifest_invalid",
                       manifest_status=manifest_status, changed_paths=changed_paths, target_agent_id=agent_id)
    try:
        client = CoreClient.from_config(Path(config_path))
        client.timeout = timeout
    except CoreClientError as error:
        return _finish(output_dir, [], started_at=started_at, started_monotonic=started_monotonic,
                       duration=duration, interval=interval, stop_reason="failed", manifest_status=manifest_status,
                       changed_paths=changed_paths, target_agent_id=agent_id,
                       fatal_code=_safe_code(error.code, "CLIENT_CONFIG_UNAVAILABLE"))
    except Exception:
        return _finish(output_dir, [], started_at=started_at, started_monotonic=started_monotonic,
                       duration=duration, interval=interval, stop_reason="failed", manifest_status=manifest_status,
                       changed_paths=changed_paths, target_agent_id=agent_id, fatal_code="CLIENT_CONFIG_UNAVAILABLE")
    observations, stop_reason, fatal_code = [], "completed", None
    deadline = started_monotonic + duration
    try:
        while True:
            # 退出前先核对源版本，覆盖最后一次采样后的等待窗口。
            if entries:
                state = check_manifest(entries)
                manifest_status, changed_paths = state["status"], state["changedPaths"]
                if manifest_status != "ok":
                    stop_reason = "source_changed"
                    break
            # 设置耗时超过短 duration 时仍采集一轮，但短运行永远不会因此 passed。
            if observations and time.monotonic() >= deadline:
                break
            row = observe_once(client, agent_id=agent_id, session_id=session_id, read_path=read_path, timeout=timeout)
            observations.append(row)
            _append_jsonl(output_dir / "observations.jsonl", row)
            if entries:
                state = check_manifest(entries)
                manifest_status, changed_paths = state["status"], state["changedPaths"]
                if manifest_status != "ok":
                    stop_reason = "source_changed"
                    break
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(interval, remaining))
    except KeyboardInterrupt:
        stop_reason = "interrupted"
    except ToolError as error:
        stop_reason, fatal_code = "failed", error.code
    except Exception:
        stop_reason, fatal_code = "failed", "OBSERVER_INTERNAL_ERROR"
    return _finish(output_dir, observations, started_at=started_at, started_monotonic=started_monotonic,
                   duration=duration, interval=interval, stop_reason=stop_reason, manifest_status=manifest_status,
                   changed_paths=changed_paths, target_agent_id=agent_id, fatal_code=fatal_code)
def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-config", type=Path, required=True, help="Core client config path")
    parser.add_argument("--output-dir", type=Path, required=True, help="Evidence directory")
    parser.add_argument("--duration", type=float, default=DEFAULT_DURATION, help="Seconds; default 86400")
    parser.add_argument("--interval", type=float, default=DEFAULT_INTERVAL, help="Seconds; default 10")
    parser.add_argument("--request-timeout", type=float, default=DEFAULT_TIMEOUT, help="Seconds; default 5")
    parser.add_argument("--agent-id", help="Existing Agent only; never created")
    parser.add_argument("--session-id", help="Existing session only; never created")
    parser.add_argument("--session-read", choices=("history", "generation"), default="history")
    parser.add_argument("--manifest", type=Path, help="JSON files[] manifest; required for a 24h pass")
    args = parser.parse_args(argv)
    try:
        summary = run_observer(config_path=args.client_config, output_dir=args.output_dir,
                               duration=args.duration, interval=args.interval, timeout=args.request_timeout,
                               agent_id=args.agent_id, session_id=args.session_id, read_path=args.session_read,
                               manifest_path=args.manifest)
    except ToolError as error:
        print(json.dumps({"errorCode": error.code}, ensure_ascii=False))
        return 1
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    return 0 if summary["passed"] else 2 if summary["verdict"] == "incomplete" else 1
if __name__ == "__main__":
    raise SystemExit(main())
