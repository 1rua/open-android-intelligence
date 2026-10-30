#!/usr/bin/env python3
"""隔离进程中的 loopback HTTP/SSE 合成负载测量；不代表生产性能。

所有源码从 --source-root 固定导入，证据只写入本 worktree 的 measurements。
不连接真实账号、设备、模型或远端服务；不输出正文、凭据或账号标识。
"""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter
from contextlib import AsyncExitStack, closing, suppress
from dataclasses import dataclass, field
import gzip
import hashlib
import importlib
import importlib.abc
import importlib.machinery
import importlib.metadata
import inspect
import importlib.util
import json
import logging
import math
import os
from pathlib import Path
import platform
import re
import resource
import secrets
import socket
import sqlite3
import subprocess
import sys
import time
from types import SimpleNamespace
import uuid

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "docs/reviews/evidence/2026-10-30-communication-reliability/measurements"
LOADED_SOURCE_DIGESTS = {}
SOURCE_DIRS = {"oai_core": Path("oai-core/src"),
               "oai_hermes_adapter": Path("integrations/hermes-session-adapter/src")}
FORMAT = "oai-isolated-loopback-communication-v4"
SCENARIOS = ("create", "send", "create_disconnect", "send_disconnect", "bridge_disconnect",
             "concurrent_send", "slow_adapter_isolation")
MISSING = {
    "android_click_ms": "No Android device or input instrumentation.",
    "android_request_start_ms": "Loopback submission is not Android T1.",
    "android_sse_read_ms": "Loopback SSE read is not Android T6.",
    "android_event_emitted_ms": "No Android repository T7 instrumentation.",
    "android_event_applied_ms": "No Android reducer T8 instrumentation.",
    "android_reconciled_ms": "No Android recovery T11 instrumentation.",
    "android_render_ms": "No Android device or frame/render instrumentation.",
    "cross_machine_network_ms": "Only loopback in one process; no synchronized cross-machine tracing.",
    "db_queue_wait_ms": "No comparable database admission/queue boundary in both sources; ledger mode is recorded separately.",
    "db_queue_depth": "No comparable database queue-depth instrumentation.",
    "agent_started_ms": "Synthetic handler entry is not real Agent execution.",
    "agent_compute_ms": "Injected synthetic delay is not model compute time.",
}


def percentile(values, fraction):
    """仅忽略 null，使用 nearest rank，拒绝无效值。"""
    if not math.isfinite(fraction) or not 0 < fraction <= 1:
        raise ValueError("INVALID_PERCENTILE")
    ordered = sorted(v for v in values if v is not None)
    if any(type(v) not in (int, float) or not math.isfinite(v) or v < 0 for v in ordered):
        raise ValueError("INVALID_METRIC")
    return ordered[math.ceil(len(ordered) * fraction) - 1] if ordered else None


def quantiles(values):
    values = list(values)
    n = sum(v is not None for v in values)
    return {"n": n, "p50": percentile(values, .5), "p95": percentile(values, .95),
            "p99": percentile(values, .99) if n >= 1000 else None,
            "max": percentile(values, 1),
            "p99_unavailable_reason": "Fewer than 1000 observations; maximum is not a production P99." if n < 1000 else None}


def summarize(samples):
    groups = {}
    for row in samples:
        groups.setdefault(row["mode"] + "/" + row["scenario"], []).append(row)
    result = {}
    for key, rows in sorted(groups.items()):
        successful = [r for r in rows if r["success"]]
        n = len(rows)
        entry = {"attempts": n, "successes": len(successful), "failures": n - len(successful),
                 "success_rate": len(successful) / n, "failure_rate": (n - len(successful)) / n,
                 "completion_rate": sum(r["host_completed"] for r in rows) / n,
                 "consistency_rate": sum(r["consistent"] for r in rows) / n,
                 "injected_disconnects": sum(r["fault_injected"] for r in rows),
                 "failure_classes": dict(sorted(Counter(r["failure"] for r in rows if not r["success"]).items()))}
        for label, selected in (("all_attempts", rows), ("successful_attempts", successful)):
            entry["latency_" + label + "_ms"] = {
                name: quantiles(r["latency_ms"][name] for r in selected) for name in rows[0]["latency_ms"]}
        roles = sorted(set(r["role"] for r in rows))
        if roles != ["standard"]:
            entry["roles"] = {role: {"attempts": sum(r["role"] == role for r in rows),
                "failures": sum(r["role"] == role and not r["success"] for r in rows),
                "submit_to_finished_ms": quantiles(r["latency_ms"]["submit_to_finished"] for r in rows if r["role"] == role)}
                for role in roles}
            fast = [r for r in rows if r["role"] == "fast"]
            entry["fast_finished_before_slow"] = sum(r.get("finished_before_slow", False) for r in fast)
            entry["fast_attempts"] = len(fast)
        result[key] = entry
    return result


def validate_comparable(baseline, candidate):
    for key in ("format", "parameters", "environment", "measurement_script_sha256"):
        if baseline[key] != candidate[key]:
            raise ValueError("INCOMPARABLE_" + key.upper())


def compare_reports(baseline, candidate):
    validate_comparable(baseline, candidate)
    if set(baseline["summary"]) != set(candidate["summary"]):
        raise ValueError("INCOMPARABLE_SCENARIOS")
    result = {}
    for key, current in candidate["summary"].items():
        previous = baseline["summary"][key]
        entry = {"baseline_attempts": previous["attempts"], "candidate_attempts": current["attempts"],
                 "baseline_failure_rate": previous["failure_rate"], "candidate_failure_rate": current["failure_rate"],
                 "failure_rate_delta": current["failure_rate"] - previous["failure_rate"],
                 "baseline_failure_classes": previous["failure_classes"], "candidate_failure_classes": current["failure_classes"]}
        for population in ("all_attempts", "successful_attempts"):
            metric = "latency_" + population + "_ms"
            entry[metric] = {}
            for name, value in current[metric].items():
                old = previous[metric][name]
                entry[metric][name] = {"baseline_n": old["n"], "candidate_n": value["n"],
                    **{q + "_delta": value[q] - old[q] if value[q] is not None and old[q] is not None else None
                       for q in ("p50", "p95", "p99", "max")}}
        result[key] = entry
    return result


def git_output(source, *args):
    return subprocess.check_output(["git", "-C", str(source), *args], stderr=subprocess.DEVNULL)


def digest_bytes(value):
    return hashlib.sha256(value).hexdigest()


def source_identity(source):
    """记录已跟踪源码、非忽略新源码、dirty 内容摘要；不记录文件内容。"""
    source = source.resolve()
    paths = [str(p) for p in SOURCE_DIRS.values()]
    names = git_output(source, "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *paths)
    files = {}
    for name in sorted(set(os.fsdecode(n) for n in names.split(b"\0") if n)):
        path = source / name
        if path.is_symlink() or not path.resolve().is_relative_to(source):
            raise ValueError("SOURCE_SYMLINK_REJECTED")
        files[name] = digest_bytes(path.read_bytes()) if path.is_file() else None
    dirty = git_output(source, "diff", "HEAD", "--name-only", "-z").split(b"\0")
    new = git_output(source, "ls-files", "-z", "--others", "--exclude-standard").split(b"\0")
    dirty_hashes = {}
    # Evidence only contains hashes, never file contents or diff text.
    for name in sorted(set(os.fsdecode(n) for n in dirty + new if n)):
        if name.startswith("docs/reviews/evidence/2026-10-30-communication-reliability/measurements/"):
            continue
        path = source / name
        if path.is_symlink():
            dirty_hashes[name] = digest_bytes(os.fsencode(os.readlink(path)))
        else:
            dirty_hashes[name] = digest_bytes(path.read_bytes()) if path.is_file() else None
    return {"head": git_output(source, "rev-parse", "HEAD").decode().strip(),
            "source_root_relative_to_meter": os.path.relpath(source, ROOT),
            "source_sha256": digest_bytes(json.dumps(files, sort_keys=True).encode()),
            "files_sha256": files, "dirty_files_sha256": dirty_hashes,
            "dirty_source_files": sorted(n for n in dirty_hashes if any(n.startswith(p + "/") for p in paths)),
            "dirty_hash_exclusions": ["docs/reviews/evidence/2026-10-30-communication-reliability/measurements/**"]}


class SelectedSourceFinder(importlib.abc.MetaPathFinder):
    """对所选包绕过可能陈旧的 pyc，始终编译固定 root 的源码字节。"""
    def __init__(self, source, identity):
        self.source, self.identity = source, identity

    def find_spec(self, fullname, path=None, target=None):
        namespace = fullname.split(".")[0]
        if namespace not in SOURCE_DIRS:
            return None
        base = self.source / SOURCE_DIRS[namespace]
        module = base.joinpath(*fullname.split("."))
        filename = module / "__init__.py" if module.is_dir() else module.with_suffix(".py")
        if module.is_dir() and not filename.is_file():
            spec = importlib.machinery.ModuleSpec(fullname, loader=None, is_package=True)
            spec.submodule_search_locations = [str(module)]
            return spec
        if not filename.is_file():
            raise ImportError("SELECTED_SOURCE_MODULE_MISSING")
        if filename.is_symlink() or not filename.resolve().is_relative_to(base.resolve()):
            raise ImportError("SELECTED_SOURCE_OUTSIDE_ROOT")
        relative = filename.relative_to(self.source).as_posix()
        expected_digest = self.identity["files_sha256"].get(relative)
        class FreshLoader(importlib.machinery.SourceFileLoader):
            def get_code(self, name):
                data = self.get_data(self.path)
                digest = digest_bytes(data)
                if digest != expected_digest:
                    raise ImportError("SOURCE_CHANGED_BEFORE_IMPORT")
                LOADED_SOURCE_DIGESTS[name] = digest
                return self.source_to_code(data, self.path)
        return importlib.util.spec_from_file_location(fullname, filename,
            loader=FreshLoader(fullname, str(filename)),
            submodule_search_locations=[str(module)] if module.is_dir() else None)


def load_source_api(source, identity):
    if any(name.split(".")[0] in SOURCE_DIRS for name in sys.modules):
        raise ValueError("SOURCE_ALREADY_IMPORTED_USE_FRESH_PROCESS")
    logging.disable(logging.CRITICAL)
    sys.meta_path.insert(0, SelectedSourceFinder(source, identity))
    api = SimpleNamespace(**{name: importlib.import_module(module) for name, module in {
        "auth": "oai_core.auth", "sessions": "oai_core.sessions", "remote": "oai_core.adapters.remote",
        "bridge": "oai_hermes_adapter.bridge"}.items()})
    verify_imports(source, identity)
    return api


def verify_imports(source, identity):
    manifest = {}
    for name, module in list(sys.modules.items()):
        namespace = name.split(".")[0]
        if namespace not in SOURCE_DIRS or module is None:
            continue
        filename = getattr(module, "__file__", None)
        if filename is None:
            paths = list(getattr(module, "__path__", ()))
            if len(paths) != 1 or not Path(paths[0]).resolve().is_relative_to(source / SOURCE_DIRS[namespace]):
                raise ValueError("SOURCE_IMPORT_HAS_NO_FILE")
            manifest[name] = {"source_path": Path(paths[0]).resolve().relative_to(source).as_posix(),
                              "sha256": None, "namespace_package": True}
            continue
        path = Path(filename).resolve()
        expected = source / SOURCE_DIRS[namespace]
        if not path.is_relative_to(expected):
            raise ValueError("WRONG_SOURCE_IMPORTED")
        relative = path.relative_to(source).as_posix()
        digest = digest_bytes(path.read_bytes())
        if identity["files_sha256"].get(relative) != digest or LOADED_SOURCE_DIGESTS.get(name) != digest:
            raise ValueError("SOURCE_IMPORT_HASH_CHANGED")
        manifest[name] = {"source_path": relative, "sha256": digest}
    return dict(sorted(manifest.items()))


def safe_native_record(data, known_attempts):
    """枚举与数值白名单；即使 logger 上游失守，也不复制任意标识或字符串。"""
    if not isinstance(data, dict):
        return None
    stage = data.get("stage")
    allowed = {"T2", "T3", "T4", "T5", "core.operation.storage", "core.operation.admission",
               "core.adapter.status", "core.agent.discover", "adapter.dispatch", "adapter.first_event"}
    if not isinstance(stage, str) or stage not in allowed:
        return None
    safe = {"stage": stage}
    for key in ("monotonicNs", "wallTimeNs", "elapsedMs", "revision", "queueDepth"):
        value = data.get(key)
        if type(value) in (int, float) and math.isfinite(value) and value >= 0:
            safe[key] = value
    for key, choices in {"state": {"validated", "running", "completed", "failed", "unknown"},
                         "eventType": {"request", "generation", "delta", "done", "error"},
                         "operation": {"begin", "complete", "lookup", "count"}}.items():
        if isinstance(data.get(key), str) and data[key] in choices:
            safe[key] = data[key]
    for key in ("clientMessageId", "requestId", "generationId"):
        value = data.get(key)
        if isinstance(value, str) and value in known_attempts:
            safe["attempt_id"] = value
            break
    return safe


def install_network_guard():
    """审计钩子在 socket 系统调用前阻止非数值 loopback 地址。"""
    def guard(event, args):
        if event not in {"socket.connect", "socket.bind", "socket.getaddrinfo"}:
            return
        if event == "socket.getaddrinfo":
            host = args[0]
        else:
            sock, address = args[:2]
            if sock.family == socket.AF_UNIX:
                return  # asyncio 的进程内唤醒 socket，不对外连接。
            host = address[0] if isinstance(address, tuple) else None
        if host not in {"127.0.0.1", "::1"}:
            raise PermissionError("NON_LOOPBACK_NETWORK_BLOCKED")
    sys.addaudithook(guard)


@dataclass
class Observation:
    scenario: str
    mode: str
    index: int
    delay: float
    role: str = "standard"
    times: dict = field(default_factory=dict)
    host_completed: bool = False
    interrupted: bool = False
    fault_injected: bool = False
    calls: int = 0
    history: list = field(default_factory=list)
    created: list = field(default_factory=list)
    entered: asyncio.Event = field(default_factory=asyncio.Event)
    release: asyncio.Event = field(default_factory=asyncio.Event)
    closed: asyncio.Event = field(default_factory=asyncio.Event)
    handler_exited: asyncio.Event = field(default_factory=asyncio.Event)
    producer: asyncio.Task | None = None
    bridge_transport: object = None
    scheduled_ns: int | None = None

    @property
    def identity(self):
        return f"probe_{self.mode}_{self.scenario}_{self.index}"

    @property
    def session_id(self):
        return "s_" + self.identity

    def mark(self, stage):
        self.times.setdefault(stage, time.monotonic_ns())

    def duration(self, end, start="submit"):
        if end not in self.times or start not in self.times:
            return None
        return round((self.times[end] - self.times[start]) / 1_000_000, 6)


class SyntheticAgent:
    """仅在内存持有合成会话；direct 工作由生成器拥有，bridge 工作由宿主持有。"""
    def __init__(self, observations, agent_id="probe"):
        self.observations, self.agent_id = observations, agent_id

    def descriptor(self):
        return {"id": self.agent_id, "name": "Synthetic measurement Agent", "kind": "probe",
                "sessionScope": "account", "capabilities": dict.fromkeys(
                    ("list", "history", "create", "send", "stream", "generationRecovery"), True)}

    async def status(self):
        return True

    async def list_sessions(self, principal):
        return []

    async def history(self, principal, session_id):
        return list(self.observations[session_id.removeprefix("s_")].history)

    async def create(self, principal, title):
        o = self.observations[title]
        o.calls += 1
        o.mark("synthetic_entered")
        o.entered.set()
        try:
            await o.release.wait()
            await asyncio.sleep(o.delay)
            value = {"id": o.session_id, "title": "synthetic session"}
            o.created.append(value)
            o.host_completed = True
            o.mark("host_completed")
            return value
        except asyncio.CancelledError:
            o.interrupted = True
            raise
        finally:
            o.closed.set()

    async def complete(self, o):
        try:
            await o.release.wait()
            await asyncio.sleep(o.delay)
            o.history.append({"id": "reply_" + o.identity, "role": "assistant", "text": "synthetic response"})
            o.host_completed = True
            o.mark("host_completed")
        except asyncio.CancelledError:
            o.interrupted = True
            raise
        finally:
            o.closed.set()

    async def send(self, principal, session_id, text, *, client_message_id=None, attachment_ids=None):
        o = self.observations[session_id.removeprefix("s_")]
        o.calls += 1
        o.mark("synthetic_entered")
        o.entered.set()
        if o.mode == "bridge":
            o.producer = asyncio.create_task(self.complete(o))
        try:
            yield {"type": "generation", "generationId": o.identity, "clientMessageId": client_message_id}
            if o.producer is None:
                await self.complete(o)
            else:
                await asyncio.shield(o.producer)
            yield {"type": "delta", "text": "synthetic response", "messageId": "reply_" + o.identity}
            yield {"type": "done", "generationId": o.identity, "messageId": "reply_" + o.identity}
        finally:
            if o.producer is None and not o.host_completed:
                o.interrupted = True
                o.closed.set()

    async def generation(self, principal, session_id, **kwargs):
        o = self.observations[session_id.removeprefix("s_")]
        return {"generationId": o.identity, "clientMessageId": o.identity, "conversationId": session_id,
                "revision": int(o.host_completed),
                "state": "completed" if o.host_completed else "unknown" if o.interrupted else "running"}


class NativeTraceHandler(logging.Handler):
    def __init__(self, observations):
        super().__init__()
        self.observations = observations
        self.records = []

    def emit(self, record):
        try:
            message = record.getMessage()
            if not message.startswith("native_session "):
                return
            safe = safe_native_record(json.loads(message[len("native_session "):]), self.observations)
            if safe is not None:
                self.records.append(safe)
        except (ValueError, TypeError):
            return


async def serve(stack, app):
    from aiohttp import web
    runner = web.AppRunner(app, handler_cancellation=True, access_log=None, shutdown_timeout=1)
    await runner.setup()
    stack.push_async_callback(runner.cleanup)
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    return f"http://127.0.0.1:{runner.addresses[0][1]}"


async def close_maybe_async(value):
    result = value.close()
    if inspect.isawaitable(result):
        await result


class Harness:
    def __init__(self, api, mode, timeout, *, ledger_path=None, diagnostics=True):
        self.api, self.mode, self.timeout = api, mode, timeout
        self.ledger_path, self.diagnostics = ledger_path, diagnostics
        self.observations = {}
        self.trace = NativeTraceHandler(self.observations)
        self.stack = AsyncExitStack()
        self.active = self.max_active = 0

    async def __aenter__(self):
        from aiohttp import ClientSession, ClientTimeout, TCPConnector, web
        await self.stack.__aenter__()
        try:
            adapters = []
            for agent_id in ("probe", "slow_probe"):
                host = SyntheticAgent(self.observations, agent_id)
                if self.mode == "bridge":
                    token = secrets.token_urlsafe(32)
                    app = self.api.bridge.create_hermes_bridge_app(host, token, ["synthetic-account"])
                    @web.middleware
                    async def remember_transport(request, handler):
                        if request.method == "POST" and request.path.endswith("/messages"):
                            identity = request.match_info.get("session_id", "").removeprefix("s_")
                            o = self.observations.get(identity)
                            if o is not None:
                                o.bridge_transport = request.transport
                        return await handler(request)
                    app.middlewares.insert(0, remember_transport)
                    url = await serve(self.stack, app)
                    adapter = self.api.remote.RemoteSessionAdapter(url, token, agent_id=agent_id, name="probe",
                        kind="probe", session_scope="account", capabilities=host.descriptor()["capabilities"],
                        headers_builder=lambda _: {"X-OAI-Account": "synthetic-account"},
                        forward_client_message_ids=True, timeout_seconds=self.timeout,
                        stream_timeout_seconds=self.timeout)
                else:
                    adapter = host
                adapters.append(adapter)
            registry = self.api.sessions.AgentRegistry(adapters, operations_path=self.ledger_path)
            self.stack.push_async_callback(close_maybe_async, registry)
            async def authenticate(request):
                o = self.observations.get(request.headers.get("Idempotency-Key"))
                if o is not None and request.method == "POST":
                    asyncio.current_task().add_done_callback(lambda _: o.handler_exited.set())
                return self.api.auth.Principal("synthetic-account")
            app = web.Application()
            app.add_routes(self.api.sessions.create_agent_routes(registry, authenticate, lambda _: True))
            self.url = await serve(self.stack, app)
            self.client = await self.stack.enter_async_context(ClientSession(
                timeout=ClientTimeout(total=self.timeout), connector=TCPConnector(limit=0)))
            logger = logging.getLogger("oai_core.native_sessions")
            logger.handlers = [self.trace]
            logger.propagate = False
            logger.setLevel(logging.DEBUG if self.diagnostics else logging.WARNING)
            logging.disable(logging.NOTSET)
            # 其余 logger 即使报告异常也不能输出 request/body 或 traceback。
            logging.getLogger().handlers = [logging.NullHandler()]
            logging.getLogger().setLevel(logging.CRITICAL + 1)
            return self
        except BaseException:
            await self.stack.__aexit__(*sys.exc_info())
            raise

    async def __aexit__(self, *exc):
        for o in self.observations.values():
            o.release.set()
        tasks = [o.producer for o in self.observations.values() if o.producer is not None and not o.producer.done()]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        await self.stack.__aexit__(*exc)
        logging.getLogger("oai_core.native_sessions").handlers = [logging.NullHandler()]


async def next_event(response):
    while line := await response.content.readline():
        if line.startswith(b"data:"):
            value = json.loads(line[5:])
            if not isinstance(value, dict):
                raise ProbeFailure("INVALID_PROTOCOL_EVENT")
            return value
    raise EOFError


class ProbeFailure(Exception):
    def __init__(self, code):
        self.code = code


def safe_failure(exc):
    from aiohttp import ClientConnectionError, ClientError, ClientPayloadError
    if isinstance(exc, ProbeFailure):
        return exc.code
    if isinstance(exc, TimeoutError):
        return "TIMEOUT"
    if isinstance(exc, EOFError):
        return "STREAM_EOF"
    if isinstance(exc, ClientPayloadError):
        return "STREAM_PAYLOAD_ERROR"
    if isinstance(exc, (ClientConnectionError, ConnectionError)):
        return "TRANSPORT_ERROR"
    if isinstance(exc, ClientError):
        return "HTTP_CLIENT_ERROR"
    if isinstance(exc, (ValueError, UnicodeError)):
        return "INVALID_PROTOCOL_EVENT"
    return "PROBE_INTERNAL_ERROR"


async def measure_one(o, harness, slow_peer=None):
    h = harness
    h.observations[o.identity] = o
    agent_id = "slow_probe" if o.role == "slow" else "probe"
    base = h.url + "/open-android-intelligence/v2/agents/" + agent_id + "/sessions"
    create = o.scenario.startswith("create")
    path = base if create else base + "/" + o.session_id + "/messages"
    headers = {"Idempotency-Key": o.identity}
    body = {"title": o.identity} if create else {"text": "synthetic request", "clientMessageId": o.identity}
    pending = response = None
    success = consistent = False
    failure = None
    http_status = None
    terminal = None
    h.active += 1
    h.max_active = max(h.max_active, h.active)
    o.mark("submit")
    if o.scheduled_ns is not None:
        o.times["scheduled"] = o.scheduled_ns
    try:
        async with asyncio.timeout(h.timeout):
            pending = asyncio.create_task(h.client.post(path, headers=headers, json=body))
            # 若请求在到达 host 前失败，不能只等 entered 掩盖 HTTP 失败为 timeout。
            entered = asyncio.create_task(o.entered.wait())
            try:
                done, _ = await asyncio.wait((pending, entered), return_when=asyncio.FIRST_COMPLETED)
                if pending in done:
                    response = await pending
                    http_status = response.status
                    o.mark("http_headers")
                    if response.status != 200:
                        raise ProbeFailure("HTTP_" + str(response.status))
                await entered
            finally:
                if not entered.done():
                    entered.cancel()
                await asyncio.gather(entered, return_exceptions=True)
            if create:
                if o.scenario == "create_disconnect":
                    o.mark("fault")
                    pending.cancel()
                    await asyncio.gather(pending, return_exceptions=True)
                    o.fault_injected = True
                    await o.handler_exited.wait()
                o.release.set()
                if o.scenario == "create":
                    response = await pending
                    http_status = response.status
                    o.mark("http_headers")
                    value = await response.json()
                    o.mark("observed_response")
                await o.closed.wait()
                if o.scenario == "create_disconnect":
                    if response is not None:
                        response.close()
                    response = await h.client.post(path, json=body, headers=headers)
                    http_status = response.status
                    value = await response.json()
                    o.mark("reconnected")
                consistent = (response.status == 200 and value.get("session", {}).get("id") == o.session_id
                              and len(o.created) == 1 and o.calls == 1)
            else:
                response = await pending
                http_status = response.status
                o.mark("http_headers")
                if response.status != 200:
                    raise ProbeFailure("HTTP_" + str(response.status))
                event = await next_event(response)
                o.mark("first_event")
                if o.scenario.endswith("disconnect"):
                    o.mark("fault")
                    if o.scenario == "bridge_disconnect":
                        if o.bridge_transport is None:
                            raise ProbeFailure("FAULT_INJECTION_UNAVAILABLE")
                        o.bridge_transport.abort()
                        o.fault_injected = True
                        while event.get("type") not in {"done", "error"}:
                            event = await next_event(response)
                        terminal = event.get("type")
                    else:
                        transport = response.connection.transport if response.connection else None
                        if transport is None:
                            raise ProbeFailure("FAULT_INJECTION_UNAVAILABLE")
                        transport.abort()
                        response.close()
                        o.fault_injected = True
                    await o.handler_exited.wait()
                o.release.set()
                if not o.scenario.endswith("disconnect"):
                    while event.get("type") not in {"done", "error"}:
                        event = await next_event(response)
                        if event.get("type") == "delta":
                            o.mark("first_content")
                    terminal = event.get("type")
                    if terminal == "done":
                        o.mark("observed_response")
                await o.closed.wait()
                if o.scenario.endswith("disconnect"):
                    async with h.client.get(base + "/" + o.session_id + "/generation",
                                            params={"clientMessageId": o.identity}) as generation:
                        receipt = (await generation.json()).get("generation", {})
                        generation_status = generation.status
                    async with h.client.get(base + "/" + o.session_id + "/history") as history:
                        messages = (await history.json()).get("messages", [])
                        history_status = history.status
                    o.mark("reconnected")
                    consistent = (generation_status == history_status == 200 and receipt.get("state") == "completed"
                        and receipt.get("generationId") == o.identity
                        and [m.get("id") for m in messages] == ["reply_" + o.identity]
                        and messages[0].get("text") == "synthetic response" and o.calls == 1)
                else:
                    consistent = terminal == "done" and o.host_completed and len(o.history) == 1 and o.calls == 1
            success = o.host_completed and consistent
            if success:
                o.mark("final")
            else:
                failure = "HOST_INTERRUPTED" if o.interrupted else "INCONSISTENT_OUTCOME"
    except Exception as exc:
        failure = safe_failure(exc)
    finally:
        o.release.set()
        if response is not None:
            response.close()
        if pending is not None and not pending.done():
            pending.cancel()
            await asyncio.gather(pending, return_exceptions=True)
        if o.producer is not None and not o.producer.done():
            o.producer.cancel()
            await asyncio.gather(o.producer, return_exceptions=True)
        o.mark("finished")
        h.active -= 1
    latency = {"submit_to_http_headers": o.duration("http_headers"), "submit_to_first_event": o.duration("first_event"),
               "submit_to_first_content": o.duration("first_content"), "submit_to_final": o.duration("final"),
               "submit_to_finished": o.duration("finished"), "submit_to_synthetic_completion": o.duration("host_completed"),
               "disconnect_to_completion": o.duration("host_completed", "fault"),
               "disconnect_to_reconnected": o.duration("reconnected", "fault"),
               "arrival_to_submit": o.duration("submit", "scheduled"),
               "arrival_to_finished": o.duration("finished", "scheduled"), **dict.fromkeys(MISSING)}
    missing = {name: MISSING.get(name, "Boundary not observed for this scenario/outcome.")
               for name, value in latency.items() if value is None}
    row = {"attempt_id": o.identity, "mode": o.mode, "scenario": o.scenario, "role": o.role,
           "success": success, "consistent": consistent, "host_completed": o.host_completed,
           "host_interrupted": o.interrupted, "host_calls": o.calls, "fault_injected": o.fault_injected,
           "failure": failure, "http_status": http_status, "terminal_event": terminal,
           "latency_ms": latency, "missing_latency_reasons": missing,
           "boundaries_monotonic_ns": o.times}
    if slow_peer is not None:
        row["finished_before_slow"] = not slow_peer.closed.is_set()
    return row


def process_snapshot():
    result = {"cpu_seconds": time.process_time(), "rss_bytes": None, "fd_count": None,
              "max_rss_bytes": int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * (1024 if platform.system() == "Linux" else 1))}
    reasons = {}
    proc = Path(os.sep) / "proc" / "self"
    try:
        status = (proc / "status").read_text()
        match = re.search(r"^VmRSS:\s+(\d+)\s+kB", status, re.MULTILINE)
        result["rss_bytes"] = int(match[1]) * 1024 if match else None
        if match is None:
            reasons["rss_bytes"] = "Process RSS unavailable in proc status."
    except OSError:
        reasons["rss_bytes"] = "Process RSS unavailable on this platform."
    try:
        result["fd_count"] = len(list((proc / "fd").iterdir()))
    except OSError:
        reasons["fd_count"] = "Process file descriptors unavailable on this platform."
    result["missing_reasons"] = reasons
    return result


class ResourceSampler:
    def __init__(self, interval_ms):
        self.interval = interval_ms / 1000
        self.records = []
        self.stopping = asyncio.Event()

    async def __aenter__(self):
        self.start_ns = time.monotonic_ns()
        self.before = process_snapshot()
        self.task = asyncio.create_task(self.observe())
        await asyncio.sleep(0)
        return self

    async def observe(self):
        loop = asyncio.get_running_loop()
        deadline = loop.time() + self.interval
        while not self.stopping.is_set():
            try:
                await asyncio.wait_for(self.stopping.wait(), timeout=max(0, deadline - loop.time()))
                break
            except TimeoutError:
                now = loop.time()
                snapshot = process_snapshot()
                snapshot.update({"monotonic_ns": time.monotonic_ns(),
                                 "event_loop_lag_ms": max(0, (now - deadline) * 1000)})
                self.records.append(snapshot)
                # Do not hide a long stall or create a busy catch-up loop.
                deadline = now + self.interval

    async def __aexit__(self, *exc):
        self.stopping.set()
        await self.task
        self.after = process_snapshot()
        self.wall_seconds = (time.monotonic_ns() - self.start_ns) / 1e9

    def summary(self):
        cpu = self.after["cpu_seconds"] - self.before["cpu_seconds"]
        return {"sample_interval_ms": self.interval * 1000, "sample_count": len(self.records),
                "wall_seconds": self.wall_seconds, "process_cpu_seconds": cpu,
                "process_cpu_percent_one_core": cpu / self.wall_seconds * 100 if self.wall_seconds else None,
                "event_loop_lag_ms": quantiles(r["event_loop_lag_ms"] for r in self.records),
                "rss_bytes": quantiles([self.before["rss_bytes"], *(r["rss_bytes"] for r in self.records), self.after["rss_bytes"]]),
                "fd_count": quantiles([self.before["fd_count"], *(r["fd_count"] for r in self.records), self.after["fd_count"]]),
                "before": self.before, "after": self.after,
                "scope": "Whole worker: Core, loopback client, synthetic adapters, tracing and sampler share one process; CPU/RSS are not Core-only.",
                "peak_rss_scope": "OS high-water mark since worker start, not a scenario-local peak."}


def environment_identity():
    packages = {}
    for name in ("aiohttp", "jsonschema", "cryptography", "yarl", "pytest", "pytest-asyncio"):
        try:
            packages[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            packages[name] = None
    return {"python": platform.python_version(), "implementation": platform.python_implementation(),
            "python_compiler": platform.python_compiler(), "interpreter_sha256": digest_bytes(Path(sys.executable).read_bytes()),
            "system": platform.system(), "release": platform.release(), "machine": platform.machine(),
            "host_fingerprint_sha256": digest_bytes(platform.node().encode()),
            "cpu_count": os.cpu_count(), "cpu_affinity": sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
            "packages": packages, "sqlite_runtime": sqlite3.sqlite_version, "event_loop_policy": type(asyncio.get_event_loop_policy()).__name__,
            "monotonic_resolution_seconds": time.get_clock_info("monotonic").resolution}


def load_snapshot():
    usage = resource.getrusage(resource.RUSAGE_SELF)
    return {"load_average_1_5_15": list(os.getloadavg()) if hasattr(os, "getloadavg") else None,
            "worker_context_switches": {"voluntary": usage.ru_nvcsw, "involuntary": usage.ru_nivcsw},
            "scope": "Host load is observed, not controlled; other jobs may affect tail latency."}


async def execute_scenario(harness, scenario, args, count, start_index=0):
    rows = []
    if scenario not in {"concurrent_send", "slow_adapter_isolation"}:
        for i in range(count):
            o = Observation(scenario, harness.mode, start_index + i, args.delay_ms / 1000)
            rows.append(await measure_one(o, harness))
        return rows, {"shape": "serial_closed_loop_fault_or_functional_probe", "max_in_flight": harness.max_active,
                      "requested_attempts": count}
    loop = asyncio.get_running_loop()
    start = loop.time()
    start_ns = time.monotonic_ns()
    tasks = []
    wave_count = math.ceil(count / args.concurrency)
    for wave in range(wave_count):
        deadline = start + wave * args.arrival_interval_ms / 1000
        await asyncio.sleep(max(0, deadline - loop.time()))
        size = min(args.concurrency, count - wave * args.concurrency)
        observations = []
        for offset in range(size):
            index = start_index + wave * args.concurrency + offset
            slow = scenario == "slow_adapter_isolation" and offset == 0
            role = ("slow" if slow else "fast") if scenario == "slow_adapter_isolation" else "standard"
            o = Observation(scenario, harness.mode, index, (args.slow_delay_ms if slow else args.delay_ms) / 1000, role)
            o.release.set()
            o.scheduled_ns = start_ns + round(wave * args.arrival_interval_ms * 1_000_000)
            observations.append(o)
        slow_peer = observations[0] if scenario == "slow_adapter_isolation" else None
        # 发出时间只取决于固定时间表，与前一请求是否完成无关。
        tasks.extend(asyncio.create_task(measure_one(o, harness, slow_peer if o.role == "fast" else None))
                     for o in observations)
    rows = await asyncio.gather(*tasks)
    return rows, {"shape": "fixed_schedule_concurrent_bursts_independent_of_completions",
        "requested_attempts": count, "burst_size": args.concurrency, "burst_count": wave_count,
        "burst_interval_ms": args.arrival_interval_ms,
        "nominal_offered_attempts_per_second": args.concurrency * 1000 / args.arrival_interval_ms,
        "max_in_flight": harness.max_active, "slow_adapter_count": 1 if scenario == "slow_adapter_isolation" else 0,
        "slow_attempts_per_burst": 1 if scenario == "slow_adapter_isolation" else 0,
        "coordinated_omission_note": "Fixed scheduling plus arrival_to_submit and arrival_to_finished; scheduler lateness is retained. No serial request-response pacing."}


def parameters(args):
    return {"attempts_per_mode_scenario": args.samples, "synthetic_delay_ms": args.delay_ms,
            "synthetic_slow_delay_ms": args.slow_delay_ms, "modes": args.modes, "scenarios": args.scenarios,
            "timeout_seconds": args.timeout, "concurrency_burst_size": args.concurrency,
            "arrival_interval_ms": args.arrival_interval_ms, "warmup_attempts_per_mode_scenario": args.warmup,
            "resource_sample_interval_ms": args.resource_interval_ms,
            "database": args.ledger, "diagnostic_trace": args.diagnostics}


def write_json(path, value):
    with path.open("x") as output:
        output.write(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def write_jsonl(output, value):
    output.write(json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n")


def measurement_path(value):
    path = value.resolve()
    if path == EVIDENCE.resolve() or not path.is_relative_to(EVIDENCE.resolve()):
        raise ValueError("OUTPUT_OUTSIDE_AUTHORIZED_MEASUREMENTS")
    if path.exists():
        raise ValueError("OUTPUT_ALREADY_EXISTS_NO_OVERWRITE")
    if path.parent.exists() and path.parent.is_symlink():
        raise ValueError("OUTPUT_SYMLINK_REJECTED")
    return path


async def worker(args):
    source = args.source_root.resolve()
    identity = source_identity(source)
    if args.expected_head and identity["head"] != args.expected_head:
        raise ValueError("UNEXPECTED_SOURCE_HEAD")
    if args.require_clean_source and identity["dirty_files_sha256"]:
        raise ValueError("BASELINE_SOURCE_IS_DIRTY")
    script_hash = digest_bytes(Path(__file__).read_bytes())
    report = {"format": FORMAT, "run_id": uuid.uuid4().hex, "label": args.label,
              "started_wall_time_ns": time.time_ns(), "source": identity,
              "measurement_script_sha256": script_hash, "test_script_sha256": digest_bytes(
                  Path(__file__).with_name("test_measure_communication.py").read_bytes()),
              "environment": environment_identity(), "parameters": parameters(args), "host_load_before": load_snapshot(),
              "reproduction": {"argv": args.invocation,
                  "cwd_relative_to_meter": ".", "isolation_flags": ["-I", "-B"],
                  "source_root_relative_to_meter": os.path.relpath(source, ROOT),
                  "pairing_rule": "Same interpreter, script, environment and load arguments; change source-root/output/label only; after also supplies compare.",
                  "environment_policy": "Child inherits only PATH, locale and TZ; no HOME, PYTHONPATH, OAI/Hermes credentials or configuration variables."},
              "limitations": {"experiment": "Isolated loopback + real Core/bridge routing + synthetic host; not production performance.",
                  "authentication": "Synthetic local principal; random in-memory bridge token, never persisted.",
                  "persistence": "Synthetic host facts stay in memory. Core operation ledger is selected as memory or an isolated on-disk SQLite file; no native host process-restart claim.",
                  "percentiles": "Empirical nearest rank; P99 null below 1000 observations; no sample size establishes production P99 here.",
                  "denominator": "Every attempted request stays in success/failure denominator; fault injection is separate from semantic success.",
                  "timeout": "Failures retain observed submit_to_finished; unobserved final latency is null, never zero or a successful latency.",
                  "resources": "Core, bridges, synthetic hosts and load client share worker process; resource usage is combined.",
                  "clock": "One process monotonic clock only; wall time is provenance, not cross-machine latency.",
                  "unsupported_metrics": MISSING}}
    baseline = json.loads(args.compare.read_text()) if args.compare else None
    if baseline is not None:
        validate_comparable(baseline, report)
    install_network_guard()
    api = load_source_api(source, identity)
    output = measurement_path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / "manifest.json", report)
    all_rows = []
    report["workloads"] = {}
    report["resources"] = {}
    report["warmup"] = {}
    with gzip.open(output / "samples.jsonl.gz", "xt", encoding="utf-8") as samples_file, \
            gzip.open(output / "trace.jsonl.gz", "xt", encoding="utf-8") as trace_file, \
            gzip.open(output / "resources.jsonl.gz", "xt", encoding="utf-8") as resource_file:
        for mode in args.modes:
            for scenario in args.scenarios:
                if scenario == "bridge_disconnect" and mode != "bridge":
                    continue
                key = mode + "/" + scenario
                ledger_path = (ROOT / "tmp/communication-measurement-state" / report["run_id"] / mode / (scenario + ".sqlite")) if args.ledger == "disk" else None
                async with Harness(api, mode, args.timeout, ledger_path=ledger_path,
                                   diagnostics=args.diagnostics == "enabled") as h:
                    warm, _ = await execute_scenario(h, scenario, args, args.warmup, start_index=-args.warmup)
                    report["warmup"][key] = {"attempts": len(warm), "failures": sum(not r["success"] for r in warm),
                        "excluded_from_measured_population": True,
                        "failure_classes": dict(Counter(r["failure"] for r in warm if not r["success"]))}
                    h.trace.records.clear()
                    h.max_active = 0
                    async with ResourceSampler(args.resource_interval_ms) as sampler:
                        rows, workload = await execute_scenario(h, scenario, args, args.samples)
                    report["workloads"][key] = workload
                    report["resources"][key] = sampler.summary()
                    report["workloads"][key]["achieved_attempts_per_second"] = len(rows) / sampler.wall_seconds
                    for row in rows:
                        row["run_id"] = report["run_id"]
                        write_jsonl(samples_file, row)
                    for record in h.trace.records:
                        write_jsonl(trace_file, {"run_id": report["run_id"], "workload": key, **record})
                    for record in sampler.records:
                        write_jsonl(resource_file, {"run_id": report["run_id"], "workload": key, **record})
                    all_rows.extend(rows)
                    samples_file.flush()
                    print(json.dumps({"workload_finished": key, "attempts": len(rows),
                                      "failures": sum(not r["success"] for r in rows)}, separators=(",", ":")), flush=True)
                if ledger_path is not None:
                    with closing(sqlite3.connect(ledger_path.resolve().as_uri() + "?mode=ro", uri=True)) as ledger:
                        report["workloads"][key]["core_ledger_journal_mode"] = ledger.execute("PRAGMA journal_mode").fetchone()[0]
                else:
                    report["workloads"][key]["core_ledger_journal_mode"] = "memory"
    report["imports"] = verify_imports(source, identity)
    final_identity = source_identity(source)
    if final_identity != identity or script_hash != digest_bytes(Path(__file__).read_bytes()):
        write_json(output / "invalid.json", {"reason": "SOURCE_OR_METER_CHANGED_DURING_MEASUREMENT", "run_id": report["run_id"]})
        raise ValueError("SOURCE_OR_METER_CHANGED_DURING_MEASUREMENT")
    report["source_verified_unchanged"] = True
    report["finished_wall_time_ns"] = time.time_ns()
    report["host_load_after"] = load_snapshot()
    report["summary"] = summarize(all_rows)
    if any(v["attempts"] != args.samples for v in report["summary"].values()):
        raise ValueError("ATTEMPT_DENOMINATOR_MISMATCH")
    if baseline is not None:
        report["comparison"] = {"baseline_run_id": baseline["run_id"], "scenarios": compare_reports(baseline, report)}
    write_json(output / "summary.json", report)
    print(json.dumps({"output": os.path.relpath(output, ROOT), "source_head": identity["head"],
                      "source_sha256": identity["source_sha256"],
                      "attempts": len(all_rows), "failures": sum(not r["success"] for r in all_rows)}, separators=(",", ":")))
    return 1 if args.require_success and any(not row["success"] for row in all_rows) else 0


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--source-root", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--label", default="baseline")
    p.add_argument("--samples", type=int, default=1000)
    p.add_argument("--delay-ms", type=float, default=5)
    p.add_argument("--slow-delay-ms", type=float, default=80)
    p.add_argument("--timeout", type=float, default=3)
    p.add_argument("--modes", nargs="+", choices=("direct", "bridge"), default=["direct", "bridge"])
    p.add_argument("--scenarios", nargs="+", choices=SCENARIOS, default=list(SCENARIOS))
    p.add_argument("--concurrency", type=int, default=8)
    p.add_argument("--arrival-interval-ms", type=float, default=25)
    p.add_argument("--resource-interval-ms", type=float, default=5)
    p.add_argument("--warmup", type=int, default=10)
    p.add_argument("--ledger", choices=["memory", "disk"], default="memory",
                   help="Core operation ledger only; disk uses a retained isolated SQLite file in worktree tmp.")
    p.add_argument("--diagnostics", choices=["enabled", "disabled"], default="enabled",
                   help="Keep both sources identical; disabled measures the production default logger level.")
    p.add_argument("--compare", type=Path)
    p.add_argument("--expected-head")
    p.add_argument("--require-clean-source", action="store_true")
    p.add_argument("--require-success", action="store_true")
    p.add_argument("--allow-small-validation-run", action="store_true",
                   help="Only tool validation; formal evidence requires >=1000 per mode/scenario.")
    p.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    return p


def main(argv=None):
    invocation = list(sys.argv[1:] if argv is None else argv)
    p = parser()
    args = p.parse_args(invocation)
    args.invocation = [a for a in invocation if a != "--worker"]
    if (args.samples < 1 or args.warmup < 0 or args.concurrency < 2
            or any(not math.isfinite(v) or v <= 0 for v in (args.timeout, args.arrival_interval_ms,
                                                           args.resource_interval_ms, args.slow_delay_ms))
            or not math.isfinite(args.delay_ms) or args.delay_ms < 0
            or args.slow_delay_ms <= args.delay_ms or args.slow_delay_ms >= args.timeout * 1000):
        p.error("invalid count, delay, concurrency or timeout")
    if args.samples < 1000 and not args.allow_small_validation_run:
        p.error("formal evidence requires at least 1000 attempts per applicable mode/scenario")
    if args.samples < 1000 and args.label != "validation":
        p.error("small runs must use --label validation")
    if len(set(args.modes)) != len(args.modes) or len(set(args.scenarios)) != len(args.scenarios):
        p.error("duplicate modes/scenarios")
    if args.modes == ["direct"] and args.scenarios == ["bridge_disconnect"]:
        p.error("no applicable scenarios")
    try:
        measurement_path(args.output)
        if args.worker:
            if not sys.flags.isolated:
                raise ValueError("WORKER_REQUIRES_ISOLATED_INTERPRETER")
            return asyncio.run(worker(args))
        env = {k: v for k, v in os.environ.items() if k in {"PATH", "LANG", "LC_ALL", "TZ"}}
        command = [sys.executable, "-I", "-B", str(Path(__file__).resolve()), *invocation, "--worker"]
        # stdout 是本工具的固定数值记录；stderr 不回显，避免第三方异常泄漏环境。
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if result.stderr:
            print(json.dumps({"worker_stderr_bytes": len(result.stderr.encode()),
                              "worker_stderr_sha256": digest_bytes(result.stderr.encode()),
                              "reason": "Suppressed untrusted diagnostics; inspect tool tests, not raw logs."}))
        for line in result.stdout.splitlines():
            try:
                record = json.loads(line)
            except ValueError:
                continue
            if isinstance(record, dict):
                allowed = {k: v for k, v in record.items() if k in {
                    "workload_finished", "attempts", "failures", "output", "source_head", "source_sha256", "error_code"}}
                print(json.dumps(allowed, ensure_ascii=False), flush=True)
        return result.returncode
    except Exception as exc:
        # 不输出异常文本、stack、路径或外部响应正文。
        code = str(exc) if type(exc) is ValueError and re.fullmatch(r"[A-Z_]+", str(exc)) else "MEASUREMENT_ABORTED"
        print(json.dumps({"error_code": code}), flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
