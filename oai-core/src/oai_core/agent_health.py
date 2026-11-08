"""Bounded, independently scheduled health checks for registered agents."""
from __future__ import annotations

import asyncio
import json
import logging
import ssl
from dataclasses import dataclass
from typing import Any

import aiohttp

logger = logging.getLogger(__name__)
PROBE_INTERVAL = 10.0
PROBE_TIMEOUT = 5.0
_PERMANENT_FAILURES = frozenset({"authentication", "security", "protocol"})
_KINDS = _PERMANENT_FAILURES | {"transient", "unavailable"}
_CODES = frozenset({"AGENT_UNAVAILABLE", "AGENT_OFFLINE", "AGENT_TIMEOUT", "HOST_INCOMPATIBLE",
                    "INVALID_AGENT_RESPONSE", "AUTHENTICATION_REQUIRED", "AGENT_ACCOUNT_NOT_CONNECTED"})
_REASONS = {
    "authentication_failed": "authentication", "permission_denied": "authentication",
    "account_not_connected": "authentication", "tls_rejected": "security",
}
_CODE_KINDS = {
    "AUTHENTICATION_REQUIRED": "authentication", "AGENT_ACCOUNT_NOT_CONNECTED": "authentication",
    "HOST_INCOMPATIBLE": "protocol", "INVALID_AGENT_RESPONSE": "protocol",
}


def _failure_fields(failure: Any) -> tuple[str, str]:
    if not isinstance(failure, dict):
        return "transient", "UNKNOWN"
    kind, code = failure.get("kind"), failure.get("code")
    return (kind if isinstance(kind, str) and kind in _KINDS else "transient",
            code if isinstance(code, str) and code in _CODES else "UNKNOWN")


def _exception_fields(exc: Exception) -> tuple[str, str]:
    # Import here because sessions owns AdapterError and imports this module.
    from .sessions import AdapterError

    if isinstance(exc, (ssl.SSLError, aiohttp.ClientSSLError, aiohttp.ServerFingerprintMismatch)):
        return "security", "TLS_REJECTED"
    if isinstance(exc, (aiohttp.ContentTypeError, json.JSONDecodeError)):
        return "protocol", "INVALID_AGENT_RESPONSE"
    if isinstance(exc, aiohttp.ClientResponseError) and exc.status in (401, 403):
        return "authentication", "AUTHENTICATION_REQUIRED"
    if isinstance(exc, AdapterError):
        code = exc.code if isinstance(exc.code, str) and exc.code in _CODES else "UNKNOWN"
        reason = exc.details.get("reason")
        kind = _REASONS.get(reason) if isinstance(reason, str) else None
        kind = kind or _CODE_KINDS.get(code)
        if kind is None and exc.status in (401, 403):
            kind = "authentication"
        return kind or "transient", code
    return "transient", "UNKNOWN"


@dataclass
class _State:
    adapter: Any
    online: bool = False
    succeeded: bool = False
    failures: int = 0
    last_probe: float | None = None
    last_success: float | None = None
    last_duration: float | None = None
    phase: str = "unknown"
    probe: asyncio.Task | None = None
    runner: asyncio.Task | None = None


class AgentHealth:
    def __init__(self) -> None:
        self._states: dict[str, _State] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._closed = False

    async def online(self, agent_id: str, adapter: Any) -> bool:
        async with self._locks.setdefault(agent_id, asyncio.Lock()):
            if self._closed:
                return False
            state = self._states.get(agent_id)
            if state is not None and state.adapter is not adapter:
                await self._stop(state)
                if self._closed:
                    return False
                state = None
            if state is None:
                state = self._states[agent_id] = _State(adapter)
                if PROBE_INTERVAL > 0:
                    state.runner = asyncio.create_task(self._periodic(agent_id, state))
            now = asyncio.get_running_loop().time()
            if state.probe is not None and not state.probe.done():
                probe = state.probe
            elif state.last_probe is None or now - state.last_probe >= PROBE_INTERVAL:
                probe = self._start_probe(agent_id, state)
            else:
                probe = None
        if probe is not None:
            await asyncio.shield(probe)
        return state.online

    def _start_probe(self, agent_id: str, state: _State) -> asyncio.Task:
        if state.probe is None or state.probe.done():
            state.probe = asyncio.create_task(self._probe(agent_id, state))
        return state.probe

    async def _periodic(self, agent_id: str, state: _State) -> None:
        while True:
            if state.probe is not None and not state.probe.done():
                await asyncio.shield(state.probe)
            if state.last_probe is None:
                await asyncio.sleep(max(PROBE_INTERVAL, 0.001))
                continue
            delay = state.last_probe + PROBE_INTERVAL - asyncio.get_running_loop().time()
            if delay > 0:
                await asyncio.sleep(delay)
                continue
            await asyncio.shield(self._start_probe(agent_id, state))

    async def _probe(self, agent_id: str, state: _State) -> None:
        loop = asyncio.get_running_loop()
        started = loop.time()
        kind, code = "none", "OK"
        try:
            success = bool(await asyncio.wait_for(state.adapter.status(), PROBE_TIMEOUT))
            if not success:
                kind, code = _failure_fields(getattr(state.adapter, "health_failure", None))
        except Exception as exc:
            success = False
            kind, code = _exception_fields(exc)
        now = loop.time()
        previous_phase = state.phase
        previous_succeeded = state.succeeded
        if success:
            state.succeeded = True
            state.failures = 0
            state.online = True
            state.last_success = now
            state.phase = "healthy"
        else:
            state.failures += 1
            state.online = state.succeeded and state.failures < 3 and kind not in _PERMANENT_FAILURES and state.online
            state.phase = ("offline" if kind in _PERMANENT_FAILURES or state.failures >= 3
                           or previous_phase == "offline" or not state.succeeded else "degraded")
        state.last_probe = now
        state.last_duration = now - started
        transition = ("recovered" if success and previous_succeeded and previous_phase != "healthy" else state.phase)
        if state.phase != previous_phase:
            try:
                logger.info("Agent health agent=%s state=%s kind=%s code=%s duration_ms=%.1f failures=%d",
                            agent_id, transition, kind, code, state.last_duration * 1000, state.failures)
            except Exception:
                pass
        else:
            try:
                logger.debug("Agent probe agent=%s state=%s kind=%s code=%s duration_ms=%.1f failures=%d",
                             agent_id, state.phase, kind, code, state.last_duration * 1000, state.failures)
            except Exception:
                pass

    @staticmethod
    async def _stop(state: _State) -> None:
        tasks = [task for task in (state.runner, state.probe) if task is not None and not task.done()]
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    async def close(self) -> None:
        self._closed = True
        await asyncio.gather(*(self._stop(state) for state in tuple(self._states.values())))
