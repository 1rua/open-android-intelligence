"""Bounded, opt-in boundary timing; never transcripts or authorization data."""
from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
import json
import logging
import math
import re
import secrets
import time
from typing import Any, Iterator, Mapping

logger = logging.getLogger("oai_core.native_sessions")
_BOOT_ID = secrets.token_hex(8)
_REQUEST_ID = re.compile(r"[A-Za-z0-9._~-]{1,128}\Z")
_TRACEPARENT = re.compile(r"00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})\Z")


@dataclass(frozen=True)
class TraceContext:
    trace_id: str
    span_id: str
    request_id: str | None = None
    flags: str = "00"


_current: ContextVar[TraceContext | None] = ContextVar("native_trace", default=None)


@contextmanager
def trace_context(headers: Mapping[str, Any] | None = None, *, request_id: str | None = None) -> Iterator[TraceContext]:
    """Tracing is diagnostic context, never identity or permission authority."""
    value = next((str(v) for k, v in (headers or {}).items() if str(k).lower() == "traceparent"), "")
    match = _TRACEPARENT.fullmatch(value)
    valid = match is not None and int(match[1], 16) != 0 and int(match[2], 16) != 0
    safe_request = request_id if isinstance(request_id, str) and _REQUEST_ID.fullmatch(request_id) else None
    context = TraceContext(match[1] if valid else secrets.token_hex(16), secrets.token_hex(8),
                           safe_request, f"{int(match[3], 16) & 1:02x}" if valid else "00")
    token = _current.set(context)
    try:
        yield context
    finally:
        _current.reset(token)


def bind_request_id(request_id: str) -> None:
    context = _current.get()
    if context is not None and _REQUEST_ID.fullmatch(request_id):
        _current.set(TraceContext(context.trace_id, context.span_id, request_id, context.flags))


def current_trace_headers() -> dict[str, str]:
    context = _current.get()
    if context is None:
        return {}
    headers = {"traceparent": f"00-{context.trace_id}-{context.span_id}-{context.flags}"}
    if context.request_id is not None:
        headers["X-OAI-Request-Id"] = context.request_id
    return headers


def trace_native_stage(
    stage: str, *, client_message_id: str | None = None,
    generation_id: str | None = None, message_id: str | None = None,
    event_id: str | None = None, revision: int | None = None,
    state: str | None = None, event_type: str | None = None,
    request_id: str | None = None, started_ns: int | None = None,
    trace_id: str | None = None, agent_id: str | None = None,
    conversation_id: str | None = None, operation: str | None = None,
    queue_depth: int | None = None, pending_operations: int | None = None,
    queue_wait_ms: float | None = None,
) -> None:
    if not logger.isEnabledFor(logging.DEBUG):
        return
    now = time.monotonic_ns()
    context = _current.get()
    metadata = {
        "stage": stage, "bootId": _BOOT_ID, "monotonicNs": now, "wallTimeNs": time.time_ns(),
        "traceId": trace_id or (context.trace_id if context else None),
        "spanId": context.span_id if context else None,
        "agentId": agent_id, "conversationId": conversation_id, "operation": operation,
        "clientMessageId": client_message_id, "generationId": generation_id,
        "messageId": message_id, "eventId": event_id, "revision": revision,
        "state": state, "eventType": event_type,
        "queueDepth": queue_depth, "pendingOperations": pending_operations, "queueWaitMs": queue_wait_ms,
        "requestId": request_id or (context.request_id if context else None),
        "elapsedMs": round((now - started_ns) / 1_000_000, 3) if started_ns is not None else None,
    }
    public = {key: value for key, value in metadata.items()
              if (isinstance(value, str) and len(value) <= 1024 and re.fullmatch(r"[^\x00-\x1f\x7f]*", value))
              or (type(value) is int and -(2 ** 63) <= value < 2 ** 63)
              or (type(value) is float and math.isfinite(value))}
    if "requestId" in public and not _REQUEST_ID.fullmatch(str(public["requestId"])):
        public.pop("requestId")
    try:
        logger.debug("native_session %s", json.dumps(public, separators=(",", ":")))
    except Exception:
        # A custom diagnostic sink must never change an accepted operation.
        pass
