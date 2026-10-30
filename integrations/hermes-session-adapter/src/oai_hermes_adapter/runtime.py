"""Hermes native lifecycle and dispatch only; no device listener or scheduler."""
from __future__ import annotations

import asyncio
from concurrent.futures import Future
from contextlib import closing, contextmanager, suppress
from copy import deepcopy
from contextvars import ContextVar
from functools import wraps
from datetime import datetime, timedelta, timezone
import hashlib
import json
import logging
import mimetypes
import os
from pathlib import Path
import re
import threading
from types import SimpleNamespace
from typing import Any, Dict, Mapping, Optional
import uuid

from aiohttp import web
from gateway.config import Platform
from gateway.platforms.base import BasePlatformAdapter, MessageEvent, MessageType, SendResult

from oai_core.gateway.core import AgentSessionBindings, GatewayError, NEW_CONVERSATION_COMMAND, iso_millis, _epoch_millis
from oai_core.sessions import AdapterError
from oai_core.native_diagnostics import current_trace_headers, trace_context, trace_native_stage
from .platform_identity import GATEWAY_PLATFORM_ID, GATEWAY_PLATFORM_IDS
from .context import native_input_receipt, trusted_turn
from .bridge import NativeApprovalMixin, create_hermes_bridge_app
from .sessions import HermesSessionAdapter
from .backend import SessionBackend
from .host_history import HermesHistoryPort, NATIVE_INPUT_KEY
from .history_media import HistoryMedia
from .tool_events import NativeToolObserver

logger = logging.getLogger(__name__)
NATIVE_HERMES_AVAILABLE = True


def _conversation_id_of(path):
    parts = [part for part in path.split("/") if part]
    if "conversations" not in parts:
        return None
    index = parts.index("conversations") + 1
    return parts[index] if index < len(parts) else None


async def report_dispatch_delivered(
    account: Any, record: Mapping[str, Any] | None, claim_token: str | None,
) -> bool:
    """把 queued 消息推进到 delivered：Agent 已经接下这一轮，而不是等整轮跑完。

    状态上报是旁路事实，任何失败都不能冒泡回事件分发栈——那会被当成 Agent
    不可用，把一条正在处理的消息标记成 failed。
    """
    return await asyncio.to_thread(_report_dispatch_delivered, account, record, claim_token)


def _report_dispatch_delivered(account: Any, record: Mapping[str, Any] | None, claim_token: str | None) -> bool:
    if record is None or str(record.get("status") or "") != "queued":
        return False
    message_id = str(record.get("messageId") or "")
    if not message_id:
        return False
    try:
        account.conversations.update_dispatch_status(
            message_id, "delivered", None, f"agent-started-{message_id}",
            claim_token=claim_token,
        )
        return True
    except Exception as exc:
        logger.warning(
            "[open_android] Agent delivery status failed messageId=%s errorCode=%s",
            message_id, str(getattr(exc, "code", "INTERNAL_ERROR")),
        )
        return False


_native_input_origin = ContextVar("oai_hermes_native_input_origin", default=None)


# The fixed SDK has lifecycle/tool hooks, but no structured mid-turn input hook.
# Pin the small producer/persistence contract; a different implementation stays
# unavailable until its native feedback tests prove the same row semantics.
def _native_input_contract() -> bool:
    import inspect
    try:
        from agent.interrupt_control import InterruptControlMixin
        from agent.session_persistence import SessionPersistenceMixin
        from agent.prompt_builder import steer_user_row
        from agent.agent_runtime_helpers import _requeue_pending_steer, apply_pending_steer_to_tool_results
        from agent.turn_iteration_prep import _inject_steer_after_newest_tool_result
        from agent.conversation_loop import _apply_active_turn_redirect
        checks = (
            (InterruptControlMixin, "2c19f9fbe6ad20d0c45d23be06b22ea311deccd62e7636b337b7cec9d47d309f"),
            (SessionPersistenceMixin, "418a8356d10e64c36b589bf8c8864fa7e6375ea40ccd541a8dd22d7cb09af734"),
            (steer_user_row, "b404a50fabf9dae2339f39b6886eb2f16d184da6d58f9d3d8f2cb46e1d3b7861"),
            (_requeue_pending_steer, "a3028faa109c0f3217aa9341691296516e8a118489324611102a9d7cf653b3f4"),
            (apply_pending_steer_to_tool_results, "082070fe6d4056f3d1d69becfec796fbd4aeaf309769cfa32592ed7cc4457f29"),
            (_inject_steer_after_newest_tool_result, "ec91f8a3d459cdc3812ac589c00b71659f9baefcb3bad72053388ca6c763eaa4"),
            (_apply_active_turn_redirect, "5ca8b6eed8e6a1f29987589a71689fa44e5ee28a5110ffbe3958293ba5dd801c"),
        )
        return all(hashlib.sha256(inspect.getsource(method).encode()).hexdigest() == expected
                   for method, expected in checks)
    except (ImportError, OSError, TypeError):
        return False


class _NativeInputText(str):
    """Native pending-text carrier; string operations preserve receipt objects."""
    def __new__(cls, text, inputs, tokens=None):
        value = super().__new__(cls, text)
        value.inputs = tuple(inputs)
        value.tokens = tuple(tokens) if tokens is not None else (object(),)
        return value

    def strip(self, chars=None):
        return type(self)(super().strip(chars), self.inputs, self.tokens)

    def __deepcopy__(self, memo):
        # Receipts/tokens are process-local immutable provenance, not transcript
        # content to clone during native API preparation or compression.
        return self

    def __add__(self, other):
        return type(self)(super().__add__(other), self.inputs + getattr(other, "inputs", ()),
                          self.tokens + getattr(other, "tokens", ()))

    def __radd__(self, other):
        return type(self)(str.__add__(other, self), getattr(other, "inputs", ()) + self.inputs,
                          getattr(other, "tokens", ()) + self.tokens)


class _NativeInputCapture:
    """Instance-local bridge for the verified SDK's string-only queue.

    No content/prefix matching: redirect/fallback rows carry receipt objects;
    typed steer rows consume the native drain batches in producer order. A
    missing/extra batch aborts persistence instead of guessing an identity.
    """
    @staticmethod
    def supports(agent: Any) -> bool:
        from agent.interrupt_control import InterruptControlMixin
        from agent.session_persistence import SessionPersistenceMixin
        methods = {
            "steer": InterruptControlMixin.steer, "redirect": InterruptControlMixin.redirect,
            "clear_interrupt": InterruptControlMixin.clear_interrupt,
            "_drain_pending_steer": InterruptControlMixin._drain_pending_steer,
            "_drain_pending_redirect": InterruptControlMixin._drain_pending_redirect,
            "_flush_messages_to_session_db": SessionPersistenceMixin._flush_messages_to_session_db,
        }
        capture = getattr(agent, "_oai_native_input_capture", None)
        installed = capture.installed if isinstance(capture, _NativeInputCapture) else {}
        return (getattr(agent, "api_mode", None) != "codex_app_server"
                and all(getattr(type(agent), name, None) is method for name, method in methods.items())
                and all(getattr(agent, name, None) is installed[name] if name in installed else
                        getattr(getattr(agent, name, None), "__func__", None) is method
                        for name, method in methods.items()))


    def __init__(self, agent: Any, history: HermesHistoryPort, owner: tuple[str, str, str]):
        self.agent, self.history, self.owner = agent, history, owner
        # Reuse the native reentrant persistence lock; a second lock would
        # invert lock order when _persist_session() calls the wrapped flush.
        self.lock = getattr(agent, "_session_persist_lock", None) or threading.RLock()
        agent._session_persist_lock = self.lock
        self.drained = []
        self.admitted = set()
        # Keep object references, so pre-install rows cannot consume a new receipt
        # and Python id reuse cannot turn an old row into a new one.
        self.existing = list(getattr(agent, "_session_messages", None) or [])
        for kind in ("steer", "redirect"):
            setattr(agent, kind, self._admit(getattr(agent, kind), kind))
            name = "_drain_pending_" + kind
            setattr(agent, name, self._drain(getattr(agent, name), kind))
        agent.clear_interrupt = self._clear(agent.clear_interrupt)
        agent._flush_messages_to_session_db = self._flush(agent._flush_messages_to_session_db)
        self.installed = {name: getattr(agent, name) for name in (
            "steer", "redirect", "clear_interrupt", "_drain_pending_steer",
            "_drain_pending_redirect", "_flush_messages_to_session_db")}

    def _admit(self, method, kind):
        @wraps(method)
        def admit(text):
            with self.lock:
                staged = None
                if not isinstance(text, _NativeInputText):
                    origin = _native_input_origin.get()
                    if origin is not None:
                        if (origin["accountId"], origin["conversationId"], origin["sessionKey"]) != self.owner:
                            raise RuntimeError("HOST_HISTORY_BINDING_CONFLICT")
                        staged = self.history.stage_native_input(origin)
                        if staged["key"] in self.admitted or staged["record"].get("status") == "accepted":
                            return True
                    text = _NativeInputText(text, (staged,))
                pending = getattr(self.agent, "_pending_" + kind, None)
                if pending and not isinstance(pending, _NativeInputText):
                    # Mixing an earlier, uncaptured input would destroy its identity.
                    return False
                accepted = method(text)
                if accepted and staged is not None:
                    self.admitted.add(staged["key"])
                    try:
                        self.history.accept_native_input(staged)
                    except Exception as exc:
                        # Already accepted by the host: don't return False and make
                        # the busy dispatcher inject the same input a second time.
                        logger.warning("Native input accepted; presentation write pending errorType=%s", type(exc).__name__)
                return accepted
        return admit

    def _drain(self, method, kind):
        @wraps(method)
        def drain():
            with self.lock:
                text = method()
                if not text:
                    return text
                if not isinstance(text, _NativeInputText):
                    text = _NativeInputText(text, (None,))
                if kind == "steer":
                    # Requeue retains the carrier (including when new steer text is
                    # concatenated); supersede only an un-emitted batch of its tokens.
                    self.drained = [batch for batch in self.drained if batch["row"] is not None
                                    or not set(batch["text"].tokens).intersection(text.tokens)]
                    self.drained.append({"text": text, "row": None})
                return text
        return drain

    def _clear(self, method):
        @wraps(method)
        def clear(**kwargs):
            with self.lock:
                pending = getattr(self.agent, "_pending_steer", None)
                result = method(**kwargs)
                if kwargs.get("hard_cancel") and isinstance(pending, _NativeInputText):
                    self.drained = [batch for batch in self.drained if batch["row"] is not None
                                    or not set(batch["text"].tokens).intersection(pending.tokens)]
                return result
        return clear

    def _stamp(self, row, text):
        inputs = text.inputs
        if any(item is None for item in inputs):
            if any(item is not None for item in inputs):
                raise RuntimeError("HOST_HISTORY_BINDING_CONFLICT")
            return
        metadata = row.get("display_metadata") or {}
        row["display_metadata"] = {**metadata, NATIVE_INPUT_KEY: {
            "version": 1, "storageOwner": self.history.storage_owner,
            "accountId": self.owner[0], "conversationId": self.owner[1],
            "inputKeys": list(dict.fromkeys(item["key"] for item in inputs)),
        }}
        if len(inputs) == 1 and not row.get("platform_message_id"):
            row["platform_message_id"] = inputs[0]["record"]["clientMessageId"]

    def _flush(self, method):
        @wraps(method)
        def flush(messages, *args, **kwargs):
            from agent.context_compressor import _DB_PERSISTED_MARKER
            with self.lock:
                rows = [row for row in messages if isinstance(row, dict) and row.get("role") == "user"
                        and not row.get(_DB_PERSISTED_MARKER)
                        and not (row.get("display_metadata") or {}).get(NATIVE_INPUT_KEY)
                        and not any(row is previous for previous in self.existing)]
                for row in rows:
                    content = row.get("content")
                    if isinstance(content, _NativeInputText):
                        self._stamp(row, content)
                        for batch in self.drained:
                            if batch["row"] is None and set(batch["text"].tokens) == set(content.tokens):
                                batch["row"] = row
                typed = [row for row in rows if row.get("display_kind") == "steer"
                         and not isinstance(row.get("content"), _NativeInputText)]
                queued = getattr(self.agent, "_pending_steer", None)
                tokens = set(queued.tokens) if isinstance(queued, _NativeInputText) else set()
                batches = [batch for batch in self.drained if batch["row"] is None
                           and not tokens.intersection(batch["text"].tokens)]
                # This equality is only a structural batch count, never text identity.
                if len(typed) != len(batches):
                    raise RuntimeError("HOST_HISTORY_BINDING_CONFLICT")
                for row, batch in zip(typed, batches):
                    self._stamp(row, batch["text"])
                    batch["row"] = row
                result = method(messages, *args, **kwargs)
                if result:
                    self.drained = [batch for batch in self.drained
                                    if batch["row"] is None or not batch["row"].get(_DB_PERSISTED_MARKER)]
                return result
        return flush


class SessionOnlyAdapter(NativeApprovalMixin, BasePlatformAdapter):
    supports_code_blocks = True
    supports_status_text = False
    supports_async_delivery = True
    interactive_resume = True
    splits_long_messages = True
    REQUIRES_EDIT_FINALIZE = True

    @property
    def authorization_is_upstream(self):
        return True

    def __init__(self, config, backend, token, accounts, port):
        super().__init__(config, Platform(GATEWAY_PLATFORM_ID))
        self.services = SimpleNamespace(core=backend)
        self._token, self._accounts, self._port = token, accounts, port
        self._account_id = None
        self._loop = None
        self._runner = self._site = self._app = None
        self._background_tasks = set()
        self._conv_to_account = {}
        self._processing_media_files = {}
        self._generation_locks = {}
        self._generation_traces = {}
        self._first_reply_events = set()
        self._generation_trace_lock = threading.RLock()
        self._event_locks = {}
        self._conversation_platforms = {}
        self._streaming_replies = {}
        self._native_turns = {}
        self._generation_canceller = None
        self._media_cache_dir = backend.storage_root / "inbound-media"
        self._lifecycle_lock = asyncio.Lock()

    async def connect(self, *, is_reconnect=False):
        async with self._lifecycle_lock:
            if self._runner is not None:
                return True
            self._loop = asyncio.get_running_loop()
            await asyncio.to_thread(self.services.core.acquire_accounts)
            self._refresh_generation_canceller()
            history = HermesHistoryPort(self.services.core, self._session_store)
            self.services.core.history_reader = history.read
            self.services.core.conversation_manager = history.manage
            self._tool_observer = NativeToolObserver(self.services.core, history)
            await asyncio.to_thread(self._cleanup_inbound_media_cache)
            self._app = create_hermes_bridge_app(HermesSessionAdapter(self.services.core, self), self._token, self._accounts)
            self._runner = web.AppRunner(self._app)
            try:
                await self._runner.setup()
                self._site = web.TCPSite(self._runner, "127.0.0.1", self._port)
                await self._site.start()
            except BaseException:
                await self._runner.cleanup()
                self._runner = self._site = self._app = None
                await asyncio.to_thread(self.services.core.close)
                self._loop = None
                raise
            for account in self._accounts:
                await self._recover_message_dispatches(account)
            self._mark_connected()
            return True

    async def disconnect(self):
        async with self._lifecycle_lock:
            if self._runner is not None:
                await self._runner.cleanup()
            for task in tuple(self._background_tasks):
                task.cancel()
            if self._background_tasks:
                await asyncio.gather(*self._background_tasks, return_exceptions=True)
            self._runner = self._site = self._app = None
            await asyncio.to_thread(self.services.core.close)
            self._loop = None
            self._mark_disconnected()

    def remember_generation_trace(self, account_id: str, conversation_id: str, generation_id: str) -> None:
        # Diagnostics only. This map neither authorizes work nor selects native
        # history, and is keyed by the real receipt produced by admission.
        headers = current_trace_headers()
        if not headers:
            with trace_context():
                headers = current_trace_headers()
        with self._generation_trace_lock:
            self._generation_traces[(account_id, conversation_id, generation_id)] = headers

    @contextmanager
    def _generation_trace(self, account_id: str, conversation_id: str, generation_id: str):
        lock = getattr(self, "_generation_trace_lock", None)
        if lock is None:
            headers = current_trace_headers()
        else:
            with lock:
                key = (account_id, conversation_id, generation_id)
                headers = self._generation_traces.get(key)
                if headers is None:
                    # Recovery may have no surviving Core request context. A
                    # fresh generation trace avoids borrowing a later request.
                    with trace_context():
                        headers = current_trace_headers()
                    self._generation_traces[key] = headers
        with trace_context(headers, request_id=headers.get("X-OAI-Request-Id")):
            yield

    def set_message_handler(self, handler: Any) -> None:
        if handler is None:
            super().set_message_handler(None)
        else:
            @wraps(handler)
            async def traced_handler(event):
                turn = getattr(event, "_oai_native_diagnostic_turn", None)
                if turn is None:
                    return await handler(event)
                with self._generation_trace(turn["accountId"], turn["conversationId"], turn["generationId"]):
                    # This boundary is the actual handler invocation. Neither
                    # claim_dispatch nor delivered can stand in for this stage.
                    trace_native_stage("T3_HANDLER", client_message_id=event.message_id,
                        generation_id=turn["generationId"], message_id=turn["messageId"],
                        agent_id="hermes", conversation_id=turn["conversationId"],
                        event_type="handler_started", operation="send")
                    return await handler(event)
            super().set_message_handler(traced_handler)
        self._refresh_generation_canceller()

    def set_busy_session_handler(self, handler: Any) -> None:
        if handler is None:
            super().set_busy_session_handler(None)
            return

        async def forward(event: Any, session_key: str) -> bool:
            try:
                origin = self._native_input_capture(event, session_key)
            except AdapterError:
                # BasePlatformAdapter falls back to interrupt/queue if a busy
                # callback raises or returns False. Consume this refusal without
                # accepting the event, so it cannot leak through that fallback.
                return True
            token = _native_input_origin.set(origin)
            try:
                accepted = await handler(event, session_key)
                if accepted:
                    event._gateway_accepted = True
                return accepted
            finally:
                _native_input_origin.reset(token)

        super().set_busy_session_handler(forward)

    async def handle_message(self, event: Any) -> None:
        # Public adapter intake also covers the runner's priority busy path,
        # which can bypass BasePlatformAdapter's busy callback entirely.
        session_key = self._event_session_key(event)
        runner = self._host_runner()
        agent = getattr(runner, "_running_agents", {}).get(session_key)
        origin = None
        if agent is not None:
            from gateway.run import _AGENT_PENDING_SENTINEL
            if agent is not _AGENT_PENDING_SENTINEL:
                origin = self._native_input_capture(event, session_key)
        token = _native_input_origin.set(origin)
        try:
            await super().handle_message(event)
        finally:
            _native_input_origin.reset(token)

    def _native_input_capture(self, event: Any, session_key: str) -> dict[str, Any] | None:
        runner = self._host_runner()
        agent = getattr(runner, "_running_agents", {}).get(session_key)
        history = getattr(getattr(self.services.core, "history_reader", None), "__self__", None)
        source = event.source
        command = event.get_command() if event.is_command() else None
        # Commands other than /steer and the host's queue mode retain normal
        # structured MessageEvent delivery and need no string-queue capture.
        if command and command != "steer":
            return None
        mode = getattr(runner, "_effective_busy_input_mode", None)
        if not command and callable(mode) and mode(source) == "queue":
            return None
        if (not _native_input_contract() or not _NativeInputCapture.supports(agent)
                or not callable(getattr(history, "stage_native_input", None))):
            raise AdapterError("STEER_UNAVAILABLE", 409)
        account_id, conversation_id = str(source.user_id or ""), str(source.chat_id or "")
        if not account_id or not conversation_id or not event.message_id:
            raise AdapterError("HOST_INCOMPATIBLE", 409)
        owner = (account_id, conversation_id, session_key)
        capture = getattr(agent, "_oai_native_input_capture", None)
        if capture is None:
            capture = agent._oai_native_input_capture = _NativeInputCapture(agent, history, owner)
        if capture.owner != owner:
            raise AdapterError("HOST_INCOMPATIBLE", 409)
        context = trusted_turn.get() or {}
        source_fields = {"platform": source.platform.value, **{name: getattr(source, name, None) for name in
            ("chat_id", "thread_id", "chat_type", "user_id", "scope_id", "profile", "message_id")}}
        return {"accountId": account_id, "conversationId": conversation_id, "sessionKey": session_key,
            "clientMessageId": str(event.message_id), "text": str(event.text or ""),
            "timestamp": int(event.timestamp.timestamp() * 1000),
            "generationId": context.get("generationId"), "source": source_fields}

    async def _dispatch_inline_reply(self, event: Any, **kwargs: Any) -> None:
        await super()._dispatch_inline_reply(event, **kwargs)
        event._gateway_accepted = True


    def _refresh_generation_canceller(self) -> None:
        self._generation_canceller = self._native_generation_canceller()
        self.services.core.generation_canceller = self._generation_canceller

    def native_steering_available(self) -> bool:
        runner = self._host_runner()
        return (callable(getattr(self, "_message_handler", None))
                and callable(getattr(self._busy_session_handler, "__call__", None))
                and callable(getattr(getattr(getattr(self.services.core, "history_reader", None), "__self__", None), "stage_native_input", None))
                and runner is not None and _native_input_contract()
                and all(_NativeInputCapture.supports(agent) for agent in
                        getattr(runner, "_running_agents", {}).values()))

    async def steer_native_turn(
        self,
        account_id: str,
        conversation_id: str,
        generation_id: str,
        text: str,
        *,
        client_message_id: str | None = None,
    ) -> dict[str, Any]:
        """Delegate input to the host without choosing its interrupt/steer policy."""
        if not text.strip():
            raise AdapterError("INVALID_REQUEST", 400)
        if not self.native_steering_available():
            raise AdapterError("STEER_UNAVAILABLE", 409)
        loop = self._loop
        if loop is None or loop.is_closed() or not loop.is_running():
            raise AdapterError("STEER_UNAVAILABLE", 409)

        async def deliver() -> dict[str, Any]:
            turn = self._native_turns.get((account_id, conversation_id, generation_id))
            if turn is None:
                raise AdapterError("STEER_UNAVAILABLE", 409)
            session_key, task, _source = turn
            if task.done() or self._session_tasks.get(session_key) is not task:
                raise AdapterError("STEER_UNAVAILABLE", 409)
            await self.dispatch_native_input(account_id, conversation_id, text,
                                             client_message_id or "native_" + uuid.uuid4().hex,
                                             generation_id=generation_id)
            return {"generationId": generation_id, "outcome": "accepted"}

        if asyncio.get_running_loop() is loop:
            return await deliver()
        return await asyncio.wrap_future(asyncio.run_coroutine_threadsafe(deliver(), loop))


    def _host_runner(self) -> Any:
        runner = getattr(self, "gateway_runner", None)
        if callable(getattr(runner, "_interrupt_and_clear_session", None)):
            return runner
        # Hermes wraps bound handlers in profile scopes. Walk only references
        # captured by that callback (never module globals or unrelated objects).
        pending = [getattr(self, "_message_handler", None)]
        seen: set[int] = set()
        while pending and len(seen) < 32:
            candidate = pending.pop()
            if candidate is None or id(candidate) in seen:
                continue
            seen.add(id(candidate))
            if callable(getattr(candidate, "_interrupt_and_clear_session", None)):
                return candidate
            pending.extend((getattr(candidate, "__self__", None),
                            getattr(candidate, "__wrapped__", None),
                            getattr(candidate, "func", None)))
            for cell in getattr(candidate, "__closure__", ()) or ():
                with suppress(ValueError):
                    pending.append(cell.cell_contents)
        return None


    def set_session_store(self, session_store: Any) -> None:
        super().set_session_store(session_store)
        core = self.services.core
        port = getattr(getattr(core, "history_reader", None), "__self__", None)
        if port is not None:
            port.session_store = session_store
        elif session_store is not None:
            core.history_reader = HermesHistoryPort(core, session_store).read
        port = getattr(getattr(core, "history_reader", None), "__self__", None)
        core.conversation_manager = port.manage if session_store is not None and hasattr(port, "manage") else None


    def _native_generation_canceller(self) -> Any:
        if (not getattr(self, "_message_handler", None)
                or not callable(getattr(self._host_runner(), "_interrupt_and_clear_session", None))
                or not callable(getattr(self, "cancel_session_processing", None))
                or self._loop is None or self._loop.is_closed()):
            return None

        def cancel(account_id: str, conversation_id: str, generation_id: str) -> str:
            loop = self._loop
            if loop is None or loop.is_closed() or not loop.is_running():
                return "OUTCOME_UNKNOWN"
            # This synchronous bridge is called by the HTTP worker. Waiting on
            # our own event loop would deadlock it, including native completion.
            with suppress(RuntimeError):
                if asyncio.get_running_loop() is loop:
                    return "OUTCOME_UNKNOWN"

            async def stop() -> str:
                interrupt = getattr(self._host_runner(), "_interrupt_and_clear_session", None)
                if not self._message_handler or not callable(interrupt):
                    return "UNSUPPORTED"
                account = self.services.core.open_gateway_account(account_id)
                try:
                    generation = account.conversations.workflow._get("generation:" + generation_id)
                    if not generation or generation["conversationId"] != conversation_id:
                        return "OUTCOME_UNKNOWN"
                    if generation["state"] in {"completed", "failed"}:
                        return "ALREADY_COMPLETED"
                finally:
                    account.close()
                turn = self._native_turns.get((account_id, conversation_id, generation_id))
                if turn is None:
                    return "OUTCOME_UNKNOWN"
                session_key, task, source = turn

                def settled_outcome() -> str:
                    if task.cancelled():
                        return "CANCELLED"
                    account = self.services.core.open_gateway_account(account_id)
                    try:
                        generation = account.conversations.workflow._get("generation:" + generation_id)
                        return "ALREADY_COMPLETED" if generation and generation["state"] in {"completed", "failed"} else "OUTCOME_UNKNOWN"
                    finally:
                        account.close()

                if task.done():
                    return settled_outcome()
                if self._session_tasks.get(session_key) is not task:
                    return "OUTCOME_UNKNOWN"
                # Use the same hard interrupt/run invalidation path as Hermes /stop.
                await interrupt(session_key, source, interrupt_reason="Gateway generation cancelled",
                    invalidation_reason="gateway_generation_cancel")
                # The native interrupt yields. A completed turn may already have
                # handed this session to a successor; never cancel that task.
                if not task.done() and self._session_tasks.get(session_key) is task:
                    await self.cancel_session_processing(session_key)
                # Stop the native task before releasing an approval wait; a
                # denied wait alone can resume another model or tool step.
                await self.withdraw_generation_approvals(account_id, conversation_id, generation_id)
                outcome = settled_outcome()
                if outcome != "CANCELLED":
                    return outcome
                # A host may defer its completion callback. Close the content
                # already shown to clients before Core publishes terminal
                # dispatch statuses, without waiting for that callback.
                await self._finish_streaming_replies(account_id, conversation_id, generation_id)
                return "CANCELLED"
            coroutine = stop()
            try:
                future = asyncio.run_coroutine_threadsafe(coroutine, loop)
            except RuntimeError:
                coroutine.close()
                return "OUTCOME_UNKNOWN"
            try: return future.result(timeout=12)
            except Exception:
                future.cancel()
                return "OUTCOME_UNKNOWN"
        return cancel


    async def send(
        self,
        chat_id: str,
        content: str,
        reply_to: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> SendResult:
        """Send message from Hermes AI agent back to the mobile client."""
        target_account = (
            (metadata.get("account_id") if isinstance(metadata, dict) else None)
            or self._conv_to_account.get(chat_id)
            or self._account_id
        )
        if not target_account:
            return SendResult(success=False, error="ACCOUNT_NOT_CONFIGURED", retryable=False)
        try:
            now_iso = iso_millis()
            # `reply_to` names the message being answered, not this one: using it
            # as our own id would overwrite the user's row. Only an id the host
            # explicitly hands over as *this* message's is honoured.
            named: Optional[str] = None
            if isinstance(metadata, dict):
                candidate = metadata.get("messageId") or metadata.get("message_id")
                if isinstance(candidate, str) and candidate:
                    named = candidate
            message_id = named or f"msg_{uuid.uuid4().hex}"
            if isinstance(metadata, dict) and metadata.get("expect_edits"):
                await self.stream_delta(chat_id, message_id, content, occurred_at=now_iso, account_id=target_account)
            else:
                await self.complete_message(chat_id, message_id, content, occurred_at=now_iso, account_id=target_account)
            receipt = native_input_receipt.get()
            if receipt is not None and receipt.get("open") and receipt["accountId"] == target_account and receipt["conversationId"] == chat_id:
                receipt["messages"].append({"messageId": message_id, "text": content})
            return SendResult(success=True, message_id=message_id)
        except Exception as exc:
            receipt = native_input_receipt.get()
            if receipt is not None and receipt.get("open"):
                receipt["deliveryFailed"] = True
            logger.error("Native reply delivery failed conversationId=%s errorType=%s", chat_id, type(exc).__name__)
            return SendResult(success=False, error=str(exc), retryable=False)


    async def send_image_file(self,chat_id,image_path,caption=None,reply_to=None,metadata=None,**kwargs):
        return await self.send_document(chat_id,image_path,caption=caption,reply_to=reply_to,metadata=metadata)


    async def send_document(self,chat_id,file_path,caption=None,file_name=None,reply_to=None,metadata=None,**kwargs):
        from .history_media import HistoryMedia
        account_id=(metadata.get("account_id") if isinstance(metadata,dict) else None) or self._conv_to_account.get(chat_id) or self._account_id
        if not account_id: return SendResult(success=False,error="ACCOUNT_NOT_CONFIGURED",retryable=False)
        return await self._store_reply(account_id, chat_id, self._persist_document, account_id, chat_id, file_path, caption)

    async def _store_reply(self, account_id, chat_id, persist, *args):
        locks = self.__dict__.setdefault("_event_locks", {})
        async with locks.setdefault((account_id, chat_id), asyncio.Lock()):
            worker = asyncio.create_task(asyncio.to_thread(persist, *args))
            cancelled = False
            while not worker.done():
                try:
                    await asyncio.shield(worker)
                except asyncio.CancelledError:
                    # A thread cannot be cancelled. Keep the session's event
                    # order locked until its in-flight commit really finishes.
                    cancelled = True
            result = worker.result()
            if cancelled:
                raise asyncio.CancelledError
            return result

    def _persist_document(self, account_id, chat_id, file_path, caption):
        account=self.services.core.open_gateway_account(account_id)
        try:
            generation = self._reply_generation(account, chat_id)
            if generation and generation["state"] in {"completed", "failed", "cancelled"}:
                return SendResult(success=False, error="GENERATION_FINISHED", retryable=False)
            message_id=f"msg_{uuid.uuid4().hex}"
            part=HistoryMedia(account).register(chat_id,message_id,file_path)
            key=f"history-media-reply:{message_id}:{part['attachmentId']}"
            account.store.database.execute("INSERT INTO account_metadata(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",(key,account.store.seal_json(part,key)))
            account.conversations.record_assistant_message(chat_id,message_id,caption or "")
            payload=self._message_payload(chat_id,message_id,caption or "")
            if generation:
                payload["generationId"] = generation["generationId"]
            payload["parts"].extend(HistoryMedia(account).reply_parts(message_id))
            origin=account.conversations.workflow._get("message-device:" + generation["messageIds"][0]) if generation else None
            visible_payload = dict(payload)
            if origin and origin.get("conversationId") == chat_id:
                visible_payload["anchorClientMessageId"] = origin.get("clientMessageId")
            history_port = getattr(getattr(self.services.core, "history_reader", None), "__self__", None)
            if callable(getattr(history_port, "record_visible", None)):
                saved = history_port.record_visible(account, visible_payload)
                if saved is not None:
                    payload["revision"] = saved["revision"]
            event = account.events.append("conversation.message.completed",origin["messageId"] if origin else message_id,payload)
            self._trace_first_reply(account_id, chat_id, generation, origin, event, payload, "conversation.message.completed")
            return SendResult(success=True,message_id=message_id)
        except Exception:
            return SendResult(success=False,error="ATTACHMENT_READ_FAILED",retryable=False)
        finally: account.close()


    async def edit_message(
        self,
        chat_id: str,
        message_id: str,
        content: str,
        *,
        finalize: bool = False,
        **kwargs: Any,
    ) -> SendResult:
        """按宿主 SendResult 契约确认增量更新与最终完成。"""
        target_account = (
            kwargs.get("account_id")
            or self._conv_to_account.get(chat_id)
            or self._account_id
        )
        if not target_account:
            logger.warning("[open_android] No account configured; cannot edit message %s", message_id)
            return SendResult(success=False, error="ACCOUNT_NOT_CONFIGURED", retryable=False)
        now_iso = iso_millis()
        if finalize:
            await self.complete_message(chat_id, message_id, content, occurred_at=now_iso, account_id=target_account)
        else:
            await self.stream_delta(chat_id, message_id, content, occurred_at=now_iso, account_id=target_account)
        return SendResult(success=True, message_id=message_id)


    async def stream_delta(
        self,
        chat_id: str,
        message_id: str,
        accumulated_text: str,
        occurred_at: Optional[str] = None,
        account_id: Optional[str] = None,
    ) -> None:
        """Publish one partial assistant message.

        The phone treats a timeline event as an upsert, not an append, so the
        payload carries everything generated so far rather than only the newest
        fragment. Hosts driving a token stream call this per chunk and then
        finish with [complete_message].
        """
        target_account = account_id or self._conv_to_account.get(chat_id) or self._account_id
        await self._publish_event(
            "conversation.message.delta", chat_id, message_id, accumulated_text, occurred_at, account_id=target_account,
        )


    async def complete_message(
        self,
        chat_id: str,
        message_id: str,
        content: str,
        occurred_at: Optional[str] = None,
        account_id: Optional[str] = None,
    ) -> None:
        """Publish the final assistant message for one turn."""
        target_account = account_id or self._conv_to_account.get(chat_id) or self._account_id
        await self._publish_event(
            "conversation.message.completed", chat_id, message_id, content, occurred_at, account_id=target_account,
        )


    async def _publish_event(
        self,
        event_type: str,
        chat_id: str,
        message_id: str,
        text: str,
        occurred_at: Optional[str] = None,
        account_id: Optional[str] = None,
        generation_id: Optional[str] = None,
    ) -> None:
        """Persist one assistant event; the committed event delivers itself.

        Persisting first is what makes the SSE `id:` a real cursor: after a
        disconnect the phone replays the event from the account store instead of
        depending on this process still holding it in memory. Delivery is not
        done here: the store hands every committed event to the registered sink,
        so an event only ever has one path to a live subscriber.
        """
        target_account = account_id or self._conv_to_account.get(chat_id) or self._account_id
        await self._store_reply(target_account, chat_id, self._persist_event, event_type, chat_id,
                                message_id, text, occurred_at, target_account, generation_id)

    def _persist_event(self, event_type, chat_id, message_id, text, occurred_at, account_id, generation_id):
        target_account = account_id or self._conv_to_account.get(chat_id) or self._account_id
        if not target_account:
            logger.warning("[open_android] No account configured; %s was not published", event_type)
            return
        payload = self._message_payload(chat_id, message_id, text, occurred_at)
        account = self.services.core.open_gateway_account(target_account)
        try:
            generation = self._reply_generation(account, chat_id, generation_id)
            tracked = self._streaming_replies.get((target_account, chat_id, generation_id), {}).get(message_id) if generation_id else None
            closing_tracked = event_type == "conversation.message.completed" and tracked is not None and tracked["text"] == text
            if generation:
                # A lifecycle callback may close an already published snapshot
                # after cancellation settles. It cannot introduce new output.
                if generation["state"] in {"completed", "failed", "cancelled"} and not closing_tracked:
                    return
                payload["generationId"] = generation["generationId"]
            from .history_media import HistoryMedia
            if closing_tracked:
                payload = deepcopy(tracked)
            else:
                payload["parts"].extend(HistoryMedia(account).reply_parts(message_id))
            if event_type == "conversation.message.completed":
                try:
                    account.conversations.record_assistant_message(chat_id, message_id, text, occurred_at)
                except Exception as rec_err:
                    logger.warning("[open_android] Failed to record completed message errorType=%s", type(rec_err).__name__)
            origin = account.conversations.workflow._get("message-device:" + generation["messageIds"][0]) if generation else None
            visible_payload = dict(payload)
            if origin and origin.get("conversationId") == chat_id:
                visible_payload["anchorClientMessageId"] = origin.get("clientMessageId")
            history_port = getattr(getattr(self.services.core, "history_reader", None), "__self__", None)
            if callable(getattr(history_port, "record_visible", None)):
                saved = history_port.record_visible(account, visible_payload,
                    "CONFIRMED" if event_type == "conversation.message.completed" else "STREAMING")
                if saved is not None:
                    payload["timestamp"] = saved["timestamp"]
                    payload["revision"] = saved["revision"]
            correlation_id = origin["messageId"] if origin and origin.get("conversationId") == chat_id else message_id
            saved_event = account.events.append(event_type, correlation_id, payload, occurred_at)
            self._trace_first_reply(target_account, chat_id, generation, origin, saved_event, payload, event_type)
            if generation:
                key = (target_account, chat_id, generation["generationId"])
                if event_type == "conversation.message.delta":
                    self._streaming_replies.setdefault(key, {})[message_id] = deepcopy(payload)
                else:
                    pending = self._streaming_replies.get(key)
                    if pending is not None:
                        pending.pop(message_id, None)
                        if not pending:
                            self._streaming_replies.pop(key, None)
        finally:
            account.close()


    def _trace_first_reply(self, account_id, chat_id, generation, origin, event, payload, event_type):
        if generation is None:
            return
        key = (account_id, chat_id, generation["generationId"])
        seen = self.__dict__.setdefault("_first_reply_events", set())
        if key in seen:
            return
        seen.add(key)
        with self._generation_trace(account_id, chat_id, generation["generationId"]):
            trace_native_stage("T4", client_message_id=origin.get("clientMessageId") if origin else None,
                generation_id=generation["generationId"], message_id=payload["messageId"],
                event_id=event["eventId"], revision=payload.get("revision"),
                event_type="first_delta" if event_type == "conversation.message.delta" else "first_completed",
                agent_id="hermes", conversation_id=chat_id, operation="send")

    def _reply_generation(self, account: Any, chat_id: str, generation_id: str | None = None) -> Any:
        from .context import trusted_turn
        workflow = account.conversations.workflow
        if generation_id:
            generation = workflow._get("generation:" + generation_id)
            if not generation or generation["conversationId"] != chat_id:
                raise GatewayError("SCHEMA_INVALID")
            return generation
        turn = trusted_turn.get()
        if (not turn or turn.get("core") is not self.services.core
                or turn.get("accountId") != account.account_id or turn.get("conversationId") != chat_id):
            return None
        generation = workflow.for_message(turn.get("messageId", ""))
        return generation if generation and generation["conversationId"] == chat_id else None


    async def _finish_streaming_replies(self, account_id: str, chat_id: str, generation_id: str) -> None:
        locks = self.__dict__.setdefault("_event_locks", {})
        async with locks.setdefault((account_id, chat_id), asyncio.Lock()):
            pending = dict(self._streaming_replies.get((account_id, chat_id, generation_id), {}))
        for message_id, snapshot in pending.items():
            await self._publish_event(
                "conversation.message.completed", chat_id, message_id, snapshot["text"],
                account_id=account_id, generation_id=generation_id,
            )


    def _message_payload(
        self,
        chat_id: str,
        message_id: str,
        text: str,
        occurred_at: Optional[str] = None,
    ) -> Dict[str, Any]:
        """The event payload the phone's decoder reads.

        `messageId` and the text parts sit at the top level: the decoder reads
        the payload directly and returns nothing at all for a payload that nests
        them, which is how a whole reply can be silently dropped.
        """
        # One formatter for the whole payload: the phone parses `occurredAt` with
        # an ISO instant parser that requires exactly three fractional digits.
        timestamp = iso_millis(occurred_at)
        return {
            "conversationId": chat_id,
            "messageId": message_id,
            "sender": "assistant",
            "parts": [{"type": "text", "text": text}],
            "text": text,
            "timestamp": _epoch_millis(timestamp),
            "revision": 0,
        }


    async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
        """Get information about an Android conversation chat/channel."""
        return {
            "name": f"Android Session ({chat_id})",
            "type": "dm",
            "chat_id": chat_id,
        }


    def _track_background_task(self, task: asyncio.Task, operation: str) -> None:
        """Keep accepted dispatch work alive and always observe task failures."""
        self._background_tasks.add(task)

        def _finished(done_task: asyncio.Task) -> None:
            self._background_tasks.discard(done_task)
            if done_task.cancelled():
                return
            try:
                error = done_task.exception()
            except asyncio.CancelledError:
                return
            if error is not None:
                logger.warning(
                    "[open_android] Background task failed operation=%s errorCode=%s",
                    operation, str(getattr(error, "code", "INTERNAL_ERROR")),
                )

        task.add_done_callback(_finished)


    def _agent_source(self, conversation_id: str, account_id: str, platform_id: str | None = None) -> Any:
        """The host source that names one Gateway conversation as one Agent chat.

        The Gateway conversation id *is* the chat id: the host keys its session
        from it, which is what makes two conversations two memories rather than
        one shared transcript.
        """
        source = self.build_source(
            chat_id=conversation_id,
            chat_name="Android Client",
            chat_type="dm",
            user_id=account_id,
            user_name=account_id,
        )
        platform_id = platform_id or self._conversation_platforms.get((account_id, conversation_id), GATEWAY_PLATFORM_ID)
        if platform_id != GATEWAY_PLATFORM_ID:
            source.platform = Platform(platform_id)
            # 使用宿主原生 profile 路由，保留 build_source 产生的接收实例引用。
            runner = getattr(self, "gateway_runner", None)
            if runner is not None:
                from gateway.profile_routing import ProfileRouteRejected
                owner = getattr(self, "_owner_profile", None)
                try:
                    source.profile = runner._profile_name_for_source(source, adapter_profile=owner) or owner
                    source.profile_route_rejected = False
                except ProfileRouteRejected:
                    source.profile_route_rejected = True
        return source


    def _lookup_or_create_agent_session(
        self, store: Any, conversation_id: str, account_id: str, force_new: bool,
    ) -> Any:
        """读取已绑定的历史来源，禁止平台更名偷偷创建另一段 Agent 记忆。"""
        lookup = getattr(store, "lookup_by_session_key", None)
        if not callable(lookup):
            return store.get_or_create_session(self._agent_source(conversation_id, account_id), force_new)

        account = self.services.core.open_gateway_account(account_id)
        try:
            account.conversations.management.require_visible(conversation_id)
            binding = account.agent_sessions.lookup(conversation_id)
        finally:
            account.close()

        bound_id = (binding or {}).get("agentSessionId")
        bound_key = (binding or {}).get("sessionKey")
        if bound_id:
            # 只认领账号自己的精确绑定。LOCAL 或未知来源保留可读，但需
            # 操作员核实，不能把其它本地历史冒充成手机会话。
            entry = lookup(bound_key) if bound_key else None
            self._remember_agent_source(store, entry, conversation_id, account_id, bound_id, bound_key)
            return entry

        candidates = []
        for platform_id in GATEWAY_PLATFORM_IDS:
            source = self._agent_source(conversation_id, account_id, platform_id)
            key = self._host_session_key(store, source)
            entry = lookup(key)
            if entry is not None:
                candidates.append(entry)
        if len(candidates) > 1:
            raise GatewayError("HOST_INCOMPATIBLE", {"reason": "AMBIGUOUS_AGENT_SESSION"})
        if candidates:
            self._remember_agent_source(store, candidates[0], conversation_id, account_id)
            return candidates[0]
        return store.get_or_create_session(self._agent_source(conversation_id, account_id), force_new)


    def _remember_agent_source(
        self, store: Any, entry: Any, conversation_id: str, account_id: str,
        bound_id: str | None = None, bound_key: str | None = None,
    ) -> None:
        origin = getattr(entry, "origin", None)
        platform_id = getattr(getattr(origin, "platform", None), "value", None)
        if (
            entry is None or origin is None or platform_id not in GATEWAY_PLATFORM_IDS
            or origin.chat_id != conversation_id or origin.user_id != account_id
            or (bound_id is not None and entry.session_id != bound_id)
            or (bound_key is not None and entry.session_key != bound_key)
        ):
            raise GatewayError("HOST_INCOMPATIBLE", {"reason": "UNRESOLVED_AGENT_SESSION_BINDING"})
        source = self._agent_source(conversation_id, account_id, platform_id)
        if self._host_session_key(store, source) != entry.session_key:
            raise GatewayError("HOST_INCOMPATIBLE", {"reason": "AGENT_SESSION_KEY_MISMATCH"})
        self._conversation_platforms[(account_id, conversation_id)] = platform_id


    @staticmethod
    def _host_session_key(store: Any, source: Any) -> str:
        from gateway.session import build_session_key
        resolver = getattr(store, "_resolve_profile_for_key", None)
        return build_session_key(
            source,
            group_sessions_per_user=getattr(store.config, "group_sessions_per_user", True),
            thread_sessions_per_user=getattr(store.config, "thread_sessions_per_user", False),
            profile=resolver(source) if callable(resolver) else None,
        )


    async def _ensure_agent_session(
        self, conversation_id: str, account_id: str, *, force_new: bool, created_via: str,
    ) -> Optional[str]:
        """Bind one conversation to the Agent session that owns its memory.

        `force_new` is only ever true for a conversation the `/new` command entry
        just created: that conversation owes the user an empty context, while
        every other conversation must land back on the session it already has.

        Returns the host's session id, or None when this host cannot name one —
        an absent binding is recorded as absent rather than filled with an
        invented id.
        """
        store = getattr(self, "_session_store", None)
        entry: Any = None
        if store is not None:
            try:
                entry = await asyncio.to_thread(
                    self._lookup_or_create_agent_session, store, conversation_id, account_id, force_new,
                )
            except GatewayError as exc:
                logger.warning("Agent 会话兼容失败 code=%s reason=%s", exc.code, exc.details.get("reason"))
                raise
            except Exception as exc:
                logger.warning(
                    "Agent 会话查询失败 stage=agent-session-lookup type=%s", type(exc).__name__
                )
                entry = None
        # The binding write touches SQLite, so it is offloaded like the lookup: a
        # per-message path must not block the loop that serves the event stream.
        await asyncio.to_thread(
            self._record_agent_session_binding, account_id, conversation_id, entry, created_via,
        )
        return getattr(entry, "session_id", None) if entry is not None else None


    def _record_agent_session_binding(
        self, account_id: str, conversation_id: str, entry: Any, created_via: str,
    ) -> None:
        """Persist the binding, including the honest case where there is none yet."""
        agent_session_id = getattr(entry, "session_id", None) if entry is not None else None
        session_key = getattr(entry, "session_key", None) if entry is not None else None
        try:
            account = self.services.core.open_gateway_account(account_id)
        except Exception as exc:
            logger.warning("Native account binding unavailable accountId=%s errorType=%s", account_id, type(exc).__name__)
            return
        try:
            with account.store.transaction():
                account.agent_sessions.record(conversation_id, created_via)
                if isinstance(agent_session_id, str) and agent_session_id:
                    account.agent_sessions.attach(conversation_id, agent_session_id, session_key)
        except Exception as exc:
            logger.warning(
                "Native session binding failed conversationId=%s errorType=%s", conversation_id, type(exc).__name__
            )
        finally:
            account.close()


    def _next_dispatch(self, account_id: str, conversation_id: str) -> tuple[bool, dict[str, Any] | None]:
        """Read durable acceptance order, including work whose observer is gone."""
        with closing(self.services.core.open_gateway_account(account_id)) as account, account.store.transaction():
            generations = [json.loads(row[0]) for row in account.store.database.execute(
                "SELECT value FROM account_metadata WHERE key LIKE 'generation:%'")]
            active = [item for item in generations if item["conversationId"] == conversation_id
                      and item["state"] in {"queued", "running", "unknown"}]
            if any(item["state"] in {"running", "unknown"} for item in active):
                return True, None
            if not active:
                return False, None
            generation = min(active, key=lambda item: item["order"])
            row = account.store.database.execute("SELECT client_message_id FROM messages WHERE message_id=?",
                (generation["messageIds"][0],)).fetchone()
            record = account.conversations.dispatch_message(row[0]) if row else None
            if record is None or record["status"] != "queued" or record.get("claimUntil"):
                # A missing/inconsistent dispatch is not evidence that it is
                # safe to run a later turn or replay an earlier handoff.
                return True, None
            record["generationId"] = generation["generationId"]
            return False, record

    async def _notify_agent_inbound(self, path: str, body_bytes: bytes, response_body: Any,
                                    account_id: str | None = None) -> None:
        source_account = account_id or self._account_id
        conversation_id = _conversation_id_of(path)
        if not source_account or not conversation_id:
            return
        lock = self._generation_locks.setdefault((source_account, conversation_id), asyncio.Lock())
        async with lock:
            loop = asyncio.get_running_loop()
            changed = asyncio.Event()
            def committed(owner, event):
                if owner != source_account or event.get("payload", {}).get("conversationId") != conversation_id:
                    return
                try:
                    loop.call_soon_threadsafe(changed.set)
                except RuntimeError:
                    pass
            self.services.core.register_event_sink(committed)
            try:
                while True:
                    # Subscribe before reading. A commit during this read either
                    # changes the durable result or leaves this wake-up set.
                    changed.clear()
                    blocked, record = await asyncio.to_thread(self._next_dispatch, source_account, conversation_id)
                    if blocked:
                        # Only the committed terminal receipt releases this
                        # fence. No polling sleep adds latency to the next turn.
                        await changed.wait()
                        continue
                    if record is None:
                        return
                    body = json.dumps({"clientMessageId": record["clientMessageId"]}).encode()
                    with self._generation_trace(source_account, conversation_id, record["generationId"]):
                        await self._dispatch_agent_inbound(path, body, {"data": record}, source_account)
            finally:
                self.services.core.unregister_event_sink(committed)


    async def dispatch_native_input(
        self,
        account_id: str,
        conversation_id: str,
        text: str,
        client_message_id: str,
        *,
        generation_id: str | None = None,
    ) -> dict[str, Any]:
        """Hand original input to the host's native command/busy dispatcher."""
        def read_binding():
            with closing(self.services.core.open_gateway_account(account_id)) as account:
                binding = account.agent_sessions.lookup(conversation_id)
                if not binding or not binding.get("agentSessionId") or not binding.get("sessionKey"):
                    raise AdapterError("SESSION_NOT_FOUND", 404)
                return binding
        binding = await asyncio.to_thread(read_binding)
        session_key = str(binding["sessionKey"])
        self._conv_to_account[conversation_id] = account_id
        event = MessageEvent(
            text=text,
            source=self._agent_source(conversation_id, account_id),
            message_type=MessageType.TEXT,
            message_id=client_message_id,
            metadata={"gateway_session_key": session_key},
            allow_gateway_control=True,
        )
        token = trusted_turn.set({
            "core": self.services.core,
            "accountId": account_id,
            "sessionId": binding.get("agentSessionId"),
            "conversationId": conversation_id,
            "clientMessageId": client_message_id,
            "generationId": generation_id,
        })
        receipt = {"accountId": account_id, "conversationId": conversation_id, "messages": [], "open": True}
        receipt_token = native_input_receipt.set(receipt)
        try:
            previous_task = self._session_tasks.get(session_key)
            was_busy = session_key in self._active_sessions
            if previous_task is not None and previous_task.done():
                was_busy = False
            await self.handle_message(event)
            started_task = self._session_tasks.get(session_key)
            if not was_busy and started_task is not None and started_task is not previous_task:
                await asyncio.shield(started_task)
        finally:
            receipt["open"] = False
            native_input_receipt.reset(receipt_token)
            trusted_turn.reset(token)
        if receipt.get("deliveryFailed"):
            raise AdapterError("OUTCOME_UNKNOWN", 409)
        if not getattr(event, "_gateway_accepted", False):
            raise AdapterError("HOST_INPUT_REJECTED", 409)
        logger.info(
            "[open_android] Native input dispatched account=%s conversation=%s client=%s",
            bool(account_id),
            bool(conversation_id),
            bool(client_message_id),
        )
        return {"outcome": "accepted", "messages": receipt["messages"],
                "text": "\n\n".join(message["text"] for message in receipt["messages"])}


    async def _dispatch_agent_inbound(
        self, path: str, body_bytes: bytes, response_body: Any, account_id: str | None = None,
    ) -> None:
        """Claim once, prepare storage on workers, and enter the native host loop."""
        source_account = account_id or self._account_id
        if not source_account:
            return
        data = json.loads(body_bytes)
        conv_id = _conversation_id_of(path) or "default"
        client_message_id = str(data.get("clientMessageId") or data.get("clientTurnId")
                                or (data.get("members") or [{}])[0].get("clientMessageId") or uuid.uuid4())
        user_text = str(data.get("text") or data.get("content") or "")
        if user_text.strip() == NEW_CONVERSATION_COMMAND and path.endswith("/commands"):
            return
        self._conv_to_account[conv_id] = source_account
        dispatch_record = None
        dispatch_claim_token = None
        materialized_files = []
        account = None
        error_code = "AGENT_UNAVAILABLE"
        handed_to_host = handoff_started = handoff_rejected = False

        def prepare():
            nonlocal account, dispatch_record, dispatch_claim_token, materialized_files, error_code
            account = self.services.core.open_gateway_account(source_account)
            dispatch_record = account.conversations.dispatch_message(client_message_id)
            if dispatch_record is None:
                return user_text, conv_id, datetime.now(timezone.utc), [], []
            if dispatch_record["status"] != "queued":
                return None
            dispatch_record = account.conversations.claim_dispatch(client_message_id)
            if dispatch_record is None:
                return None
            dispatch_claim_token = str(dispatch_record["claimToken"])
            error_code = "ATTACHMENT_READ_FAILED"
            media_urls, media_types, materialized_files = self._materialize_message_attachments(
                source_account, dispatch_record, account)
            error_code = "AGENT_UNAVAILABLE"
            return (str(dispatch_record["text"]), str(dispatch_record["conversationId"]),
                    datetime.fromisoformat(str(dispatch_record["createdAt"]).replace("Z", "+00:00")),
                    media_urls, media_types)

        def capture():
            history = getattr(getattr(self.services.core, "history_reader", None), "__self__", None)
            if dispatch_record is not None and callable(getattr(history, "capture_submission", None)):
                HistoryMedia(account).adopt_inbound(conversation_id, dispatch_record["messageId"],
                    dispatch_record["attachments"], media_urls)
                history.capture_submission(account, dispatch_record)
            origin_row = account.store.database.execute("SELECT value FROM account_metadata WHERE key=?",
                (f"message-device:{client_message_id}",)).fetchone()
            return (json.loads(origin_row[0]) if origin_row else {}, account.agent_sessions.lookup(conversation_id),
                    account.conversations.workflow.for_message(dispatch_record["messageId"]) if dispatch_record else None)

        def acknowledge():
            for attachment_id in dispatch_record["attachmentIds"]:
                try:
                    account.attachments.mark_delivered(attachment_id)
                    account.attachments.acknowledge(attachment_id, f"message-ack-{dispatch_record['messageId']}")
                except Exception as exc:
                    logger.warning("Native attachment ACK failed messageId=%s errorType=%s",
                        dispatch_record["messageId"], type(exc).__name__)

        def record_failure(failure_code):
            if dispatch_record is None or account is None or handed_to_host:
                return
            if handoff_started and not handoff_rejected:
                # An exception during intake cannot prove whether the host
                # adopted the turn. Preserve ambiguity and prohibit redelivery.
                account.conversations.workflow.settle(dispatch_record["messageId"], "unknown")
                return
            status_error = error_code if error_code == "ATTACHMENT_READ_FAILED" else failure_code
            account.conversations.update_dispatch_status(dispatch_record["messageId"], "failed", status_error,
                f"dispatch-failed-{dispatch_record['messageId']}", claim_token=dispatch_claim_token)

        try:
            prepared = await asyncio.to_thread(prepare)
            if prepared is None:
                return
            user_text, conversation_id, created_at, media_urls, media_types = prepared
            await self._ensure_agent_session(conv_id, source_account, force_new=False,
                created_via=AgentSessionBindings.CREATED_VIA_INBOUND_MESSAGE)
            origin, binding, generation = await asyncio.to_thread(capture)
            event = MessageEvent(text=user_text, source=self._agent_source(conversation_id, source_account),
                message_type=(MessageType.TEXT if not media_types else
                    getattr(MessageType, "PHOTO", MessageType.DOCUMENT)
                    if all(media_type.casefold().startswith("image/") for media_type in media_types) else
                    getattr(MessageType, "AUDIO", MessageType.DOCUMENT)
                    if all(media_type.casefold().startswith("audio/") for media_type in media_types) else
                    getattr(MessageType, "DOCUMENT", MessageType.TEXT)),
                message_id=client_message_id, media_urls=media_urls, media_types=media_types, timestamp=created_at)
            event._gateway_claim_token = dispatch_claim_token
            if dispatch_record is not None:
                self._processing_media_files[(source_account, client_message_id)] = list(materialized_files)
            token = trusted_turn.set({**origin, "core": self.services.core, "accountId": source_account,
                "sessionId": binding.get("agentSessionId") if binding else None,
                "conversationId": conversation_id, "clientMessageId": client_message_id,
                "generationId": generation.get("generationId") if generation else None,
                "executionObserver": getattr(self, "_tool_observer", None)})
            try:
                handoff_started = True
                await self.handle_message(event)
            finally:
                trusted_turn.reset(token)
            if getattr(event, "_gateway_accepted", False) is not True:
                handoff_rejected = True
                raise GatewayError("AGENT_UNAVAILABLE")
            handed_to_host = True
            materialized_files = []
            if dispatch_record is not None:
                await report_dispatch_delivered(account, dispatch_record, dispatch_claim_token)
                await asyncio.to_thread(acknowledge)
        except Exception as exc:
            failure_code = str(getattr(exc, "code", "AGENT_UNAVAILABLE"))
            if failure_code not in {"AGENT_UNAVAILABLE", "ATTACHMENT_READ_FAILED", "AGENT_MEDIA_REJECTED", "MODEL_REQUEST_REJECTED"}:
                failure_code = "AGENT_UNAVAILABLE"
            logger.warning("Native inbound dispatch failed clientMessageId=%s errorCode=%s", client_message_id, failure_code)
            with suppress(Exception):
                await asyncio.to_thread(record_failure, failure_code)
        finally:
            if dispatch_claim_token is not None and not handed_to_host:
                self._processing_media_files.pop((source_account, client_message_id), None)
            def release_storage():
                for cached_file in materialized_files:
                    with suppress(OSError):
                        HistoryMedia._retire_path(cached_file)
                if account is not None:
                    account.close()
            await asyncio.to_thread(release_storage)


    def _materialize_message_attachments(
        self, account_id: str, dispatch_record: Mapping[str, Any], account: Any,
    ) -> tuple[list[str], list[str], list[Path]]:
        """Copy verified AEAD records to a private Hermes media cache in order."""
        media_urls: list[str] = []
        media_types: list[str] = []
        files: list[Path] = []
        if not dispatch_record.get("attachments"):
            return media_urls, media_types, files
        cache_dir = (
            self._media_cache_dir
            / hashlib.sha256(account_id.encode("utf-8")).hexdigest()
            / uuid.uuid4().hex
        )
        cache_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        for private_dir in (self._media_cache_dir, cache_dir.parent, cache_dir):
            try:
                private_dir.chmod(0o700)
            except OSError:
                pass
        try:
            for attachment in dispatch_record["attachments"]:
                if attachment["state"] != "verified":
                    raise GatewayError("ATTACHMENT_READ_FAILED")
                media_type = str(attachment["mediaType"])
                suffix = Path(str(attachment["filename"])).suffix.lower()
                if not suffix or len(suffix) > 12 or any(not char.isalnum() and char != "." for char in suffix):
                    suffix = mimetypes.guess_extension(media_type, strict=False) or ".bin"
                attachment_id = str(attachment["attachmentId"])
                partial = cache_dir / f"{uuid.uuid4().hex}{suffix}.part"
                destination = cache_dir / partial.name[:-len(".part")]
                digest = hashlib.sha256()
                total = 0
                try:
                    with partial.open("xb") as output:
                        partial.chmod(0o600)
                        for chunk in account.attachments.open_verified_stream(attachment_id):
                            output.write(chunk)
                            digest.update(chunk)
                            total += len(chunk)
                        output.flush()
                        os.fsync(output.fileno())
                    if (
                        total != int(attachment["sizeBytes"])
                        or digest.hexdigest() != str(attachment["sha256"])
                    ):
                        raise GatewayError("ATTACHMENT_READ_FAILED")
                    os.replace(partial, destination)
                except BaseException:
                    with suppress(OSError):
                        HistoryMedia._retire_path(partial)
                    raise
                files.append(destination)
                media_urls.append(str(destination))
                media_types.append(media_type)
            return media_urls, media_types, files
        except BaseException:
            for cached_file in files:
                with suppress(OSError):
                    HistoryMedia._retire_path(cached_file)
            raise


    def _recovery_account_ids(self) -> list[str]:
        core = getattr(self.services, "core", None)
        list_ids = getattr(core, "list_gateway_account_ids", None)
        account_ids = set(list_ids() if callable(list_ids) else [])
        if self._account_id and callable(getattr(core, "account_exists", None)) and core.account_exists(self._account_id):
            account_ids.add(self._account_id)
        return sorted(account_ids)


    async def _recover_message_dispatches(self, account_id: str) -> None:
        """Resume only unclaimed work; interrupted handoffs retain unknown facts."""
        def recover():
            queued = set()
            with closing(self.services.core.open_gateway_account(account_id)) as account:
                account.attachments.recover_interrupted_uploads()
                with account.store.transaction():
                    for dispatch in account.conversations.incomplete_dispatches():
                        generation = account.conversations.workflow.for_message(dispatch["messageId"])
                        if generation is None or generation["state"] in {"completed", "failed", "cancelled"}:
                            continue
                        if (generation["state"] in {"running", "unknown"} or dispatch["status"] == "delivered"
                                or dispatch.get("claimUntil")):
                            account.conversations.workflow.settle(dispatch["messageId"], "unknown")
                        else:
                            queued.add(dispatch["conversationId"])
            return queued
        try:
            conversations = await asyncio.to_thread(recover)
            for conversation_id in conversations:
                task = asyncio.create_task(self._notify_agent_inbound(
                    f"/open-android-intelligence/v2/conversations/{conversation_id}/messages", b"{}", {}, account_id))
                self._track_background_task(task, "native-startup-recovery")
        except Exception as exc:
            logger.warning("Native dispatch recovery failed accountId=%s errorType=%s", account_id, type(exc).__name__)


    def _cleanup_inbound_media_cache(self) -> None:
        """Remove incomplete and expired private cache files after restart."""
        if not self._media_cache_dir.is_dir():
            return
        cutoff = datetime.now(timezone.utc).timestamp() - max(3600, self.services.core.attachment_policy.attachment_ttl_seconds)
        for path in self._media_cache_dir.rglob("*"):
            if not path.is_file():
                continue
            try:
                if path.name.endswith(".part") or path.stat().st_mtime < cutoff:
                    HistoryMedia._retire_path(path)
            except OSError as exc:
                logger.warning("[open_android] Cached media cleanup failed errorCode=INTERNAL_ERROR")


    async def on_processing_start(self, event: Any) -> None:
        """Capture native admission before the host callback starts running."""
        source = getattr(event, "source", None)
        account_id = str(getattr(source, "user_id", "") or "")
        client_id = str(getattr(event, "message_id", "") or "")
        if not account_id or not client_id:
            return
        def record_admission():
            with closing(self.services.core.open_gateway_account(account_id)) as account:
                record = account.conversations.dispatch_message(client_id)
                _report_dispatch_delivered(account, record, getattr(event, "_gateway_claim_token", None))
                generation = account.conversations.workflow.for_message(record["messageId"]) if record else None
                return record, generation
        record, generation = await asyncio.to_thread(record_admission)
        if not generation:
            return
        event._oai_native_diagnostic_turn = {"accountId": account_id, "conversationId": record["conversationId"],
            "generationId": generation["generationId"], "messageId": record["messageId"]}
        with self._generation_trace(account_id, record["conversationId"], generation["generationId"]):
            trace_native_stage("T3", client_message_id=client_id, generation_id=generation["generationId"],
                message_id=record["messageId"], revision=generation.get("revision"), state=generation["state"],
                event_type="host_admitted", agent_id="hermes", conversation_id=record["conversationId"], operation="send")
        key = (account_id, record["conversationId"], generation["generationId"])
        session_key = self._event_session_key(event)
        task = self._session_tasks.get(session_key)
        if task is None or task.done():
            return
        self._native_turns[key] = (session_key, task, source)
        def release(done: asyncio.Task) -> None:
            turn = self._native_turns.get(key)
            if turn is not None and turn[1] is done:
                self._native_turns.pop(key, None)
        task.add_done_callback(release)


    async def on_processing_complete(self, event: Any, outcome: Any) -> None:
        """按宿主异步完成回调契约结算 Gateway 投递状态。"""
        source = getattr(event, "source", None)
        account_id = str(getattr(source, "user_id", "") or "")
        client_message_id = str(getattr(event, "message_id", "") or "")
        if not account_id or not client_message_id:
            return
        name = str(getattr(outcome, "value", None) or getattr(outcome, "name", outcome)).lower()
        status = "completed" if name in {"success", "succeeded", "completed"} else "failed"
        error_code = None if status == "completed" else (
            "AGENT_UNAVAILABLE" if "cancel" in name else "MODEL_REQUEST_REJECTED"
        )
        def read_turn():
            with closing(self.services.core.open_gateway_account(account_id)) as account:
                record = account.conversations.dispatch_message(client_message_id)
                generation = account.conversations.workflow.for_message(record["messageId"]) if record else None
                return record, generation

        def settle_turn(record, generation):
            final_status, final_error = status, error_code
            with closing(self.services.core.open_gateway_account(account_id)) as account, account.store.transaction():
                current_generation = account.conversations.workflow.for_message(record["messageId"])
                if current_generation and current_generation["state"] == "cancelled":
                    final_status, final_error = "failed", "AGENT_UNAVAILABLE"
                if final_status == "completed" and record["status"] == "queued":
                    account.conversations.update_dispatch_status(record["messageId"], "delivered", None,
                        f"agent-adopted-{record['messageId']}", claim_token=getattr(event, "_gateway_claim_token", None))
                account.conversations.workflow.settle(record["messageId"], final_status)
                account.conversations.update_dispatch_status(record["messageId"], final_status, final_error,
                    f"agent-complete-{record['messageId']}")
                return account.conversations.workflow.for_message(record["messageId"])

        try:
            record, generation = await asyncio.to_thread(read_turn)
            if record is not None:
                if generation:
                    await self.withdraw_generation_approvals(account_id, record["conversationId"], generation["generationId"])
                    await self._finish_streaming_replies(account_id, record["conversationId"], generation["generationId"])
                    observer = getattr(self, "_tool_observer", None)
                    if observer is not None:
                        try:
                            await asyncio.to_thread(observer.close_generation, account_id, record["conversationId"],
                                generation["generationId"], record["messageId"])
                        except Exception as observation_failure:
                            logger.warning("Native tool terminal observation unavailable errorType=%s", type(observation_failure).__name__)
                settled = await asyncio.to_thread(settle_turn, record, generation)
                if settled:
                    with self._generation_trace(account_id, record["conversationId"], settled["generationId"]):
                        trace_native_stage("T10", client_message_id=client_message_id, generation_id=settled["generationId"],
                            message_id=record["messageId"], revision=settled.get("revision"), state=settled["state"],
                            event_type="terminal_committed", agent_id="hermes", conversation_id=record["conversationId"], operation="send")
                    if hasattr(self, "_generation_trace_lock"):
                        with self._generation_trace_lock:
                            key = (account_id, record["conversationId"], settled["generationId"])
                            self._generation_traces.pop(key, None)
                            self._first_reply_events.discard(key)
        except Exception as exc:
            logger.warning("Native completion status failed clientMessageId=%s errorType=%s", client_message_id, type(exc).__name__)
        finally:
            media_paths = self._processing_media_files.pop((account_id, client_message_id), [])
            def retire_media():
                for media_path in media_paths:
                    with suppress(OSError):
                        HistoryMedia._retire_path(media_path)
            await asyncio.to_thread(retire_media)
