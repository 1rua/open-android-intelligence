"""Opt-in native-session boundary timing without transcript or credentials."""
from __future__ import annotations

import json
import logging
import time

logger = logging.getLogger("oai_core.native_sessions")


def trace_native_stage(
    stage: str, *, client_message_id: str | None = None,
    generation_id: str | None = None, message_id: str | None = None,
    event_id: str | None = None, revision: int | None = None,
    state: str | None = None, event_type: str | None = None,
    request_id: str | None = None, started_ns: int | None = None,
) -> None:
    """Only explicitly selected public metadata can enter these DEBUG records.

    Monotonic timestamps can be compared within one service process. Hosts on
    different machines need synchronized tracing before measuring a network hop.
    """
    if not logger.isEnabledFor(logging.DEBUG):
        return
    now = time.monotonic_ns()
    metadata = {
        "stage": stage, "monotonicNs": now,
        "clientMessageId": client_message_id, "generationId": generation_id,
        "messageId": message_id, "eventId": event_id, "revision": revision,
        "state": state, "eventType": event_type, "requestId": request_id,
        "elapsedMs": round((now - started_ns) / 1_000_000, 3) if started_ns is not None else None,
    }
    logger.debug("native_session %s", json.dumps(
        {key: value for key, value in metadata.items() if value is not None}, separators=(",", ":")))
