"""Hermes native lifecycle and dispatch only; no device listener or scheduler."""
from __future__ import annotations

import asyncio
from contextlib import suppress
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import logging
import mimetypes
import os
from pathlib import Path
import re
from types import SimpleNamespace
from typing import Any, Dict, Mapping, Optional
import uuid

from aiohttp import web
from gateway.config import Platform
from gateway.platforms.base import BasePlatformAdapter, MessageEvent, MessageType, SendResult

from oai_core.gateway.core import AgentSessionBindings, GatewayError, NEW_CONVERSATION_COMMAND, iso_millis, _epoch_millis
from .platform_identity import GATEWAY_PLATFORM_ID, GATEWAY_PLATFORM_IDS
from .context import trusted_turn
from .bridge import UnsupportedApprovalMixin, create_hermes_bridge_app
from .sessions import HermesSessionAdapter
from .backend import SessionBackend
from .host_history import HermesHistoryPort
from .history_media import HistoryMedia

logger = logging.getLogger(__name__)
MAX_REMEMBERED_TURNS = 256
NATIVE_HERMES_AVAILABLE = True


def _conversation_id_of(path):
    parts = [part for part in path.split("/") if part]
    if "conversations" not in parts:
        return None
    index = parts.index("conversations") + 1
    return parts[index] if index < len(parts) else None


class SessionOnlyAdapter(UnsupportedApprovalMixin, BasePlatformAdapter):
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
        self._turn_replies = {}
        self._generation_locks = {}
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
            self.services.core.acquire_accounts()
            self._refresh_generation_canceller()
            history = HermesHistoryPort(self.services.core, self._session_store)
            self.services.core.history_reader = history.read
            self.services.core.conversation_manager = history.manage
            self._cleanup_inbound_media_cache()
            self._app = create_hermes_bridge_app(HermesSessionAdapter(self.services.core, self), self._token, self._accounts)
            self._runner = web.AppRunner(self._app)
            try:
                await self._runner.setup()
                self._site = web.TCPSite(self._runner, "127.0.0.1", self._port)
                await self._site.start()
            except BaseException:
                await self._runner.cleanup()
                self._runner = self._site = self._app = None
                self.services.core.close()
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
            self.services.core.close()
            self._loop = None
            self._mark_disconnected()

    def set_message_handler(self, handler: Any) -> None:
        super().set_message_handler(handler)
        # Hosts may wire callbacks after connect or replace them on reconnect.
        self._refresh_generation_canceller()


    def _refresh_generation_canceller(self) -> None:
        self._generation_canceller = self._native_generation_canceller()
        self.services.core.generation_canceller = self._generation_canceller


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
            message_id = self._published_reply_id(chat_id, content, named)
            if isinstance(metadata, dict) and metadata.get("expect_edits"):
                await self.stream_delta(chat_id, message_id, content, occurred_at=now_iso, account_id=target_account)
            else:
                await self.complete_message(chat_id, message_id, content, occurred_at=now_iso, account_id=target_account)
            return SendResult(success=True, message_id=message_id)
        except Exception as exc:
            logger.error("[open_android] Failed to deliver message to %s: %s", chat_id, exc)
            return SendResult(success=False, error=str(exc), retryable=False)


    async def send_image_file(self,chat_id,image_path,caption=None,reply_to=None,metadata=None,**kwargs):
        return await self.send_document(chat_id,image_path,caption=caption,reply_to=reply_to,metadata=metadata)


    async def send_document(self,chat_id,file_path,caption=None,file_name=None,reply_to=None,metadata=None,**kwargs):
        from .history_media import HistoryMedia
        account_id=(metadata.get("account_id") if isinstance(metadata,dict) else None) or self._conv_to_account.get(chat_id) or self._account_id
        if not account_id: return SendResult(success=False,error="ACCOUNT_NOT_CONFIGURED",retryable=False)
        account=self.services.core.open_gateway_account(account_id)
        try:
            generation = self._reply_generation(account, chat_id)
            if generation and generation["state"] in {"completed", "failed", "cancelled"}:
                return SendResult(success=False, error="GENERATION_FINISHED", retryable=False)
            message_id=self._published_reply_id(chat_id,caption or "",None)
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
            account.events.append("conversation.message.completed",origin["messageId"] if origin else message_id,payload)
            return SendResult(success=True,message_id=message_id)
        except Exception:
            return SendResult(success=False,error="ATTACHMENT_READ_FAILED",retryable=False)
        finally: account.close()


    def _published_reply_id(self, chat_id: str, text: str, proposed: Optional[str]) -> str:
        """The message id one logical reply is published under.

        A host can publish the same reply more than once in a turn — a finalize
        plus a final send, a retried delivery. That is one message, not two:
        minting a second id would put the reply in the account's timeline twice,
        and nothing downstream could collapse the copies because the ids really
        do differ. The first id of a turn's reply is therefore kept, and a new
        user message is what starts a new turn.
        """
        remembered = self._turn_replies.get(chat_id)
        if remembered is not None and remembered[0] == text:
            if proposed is not None and proposed != remembered[1]:
                logger.info(
                    "[open_android] %s published the same reply again as %s; keeping %s",
                    chat_id, proposed, remembered[1],
                )
            return remembered[1]
        identity = proposed or f"msg_{uuid.uuid4().hex[:12]}"
        self._remember_turn_reply(chat_id, text, identity)
        return identity


    def _remember_turn_reply(self, chat_id: str, text: str, message_id: str) -> None:
        self._turn_replies.pop(chat_id, None)
        self._turn_replies[chat_id] = (text, message_id)
        while len(self._turn_replies) > MAX_REMEMBERED_TURNS:
            eldest = next(iter(self._turn_replies), None)
            if eldest is None:
                break
            self._turn_replies.pop(eldest, None)


    def _forget_turn_reply(self, chat_id: str) -> None:
        """A new user message starts a new turn: the same text is a new reply."""
        self._turn_replies.pop(chat_id, None)


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
            message_id = self._published_reply_id(chat_id, content, message_id)
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
                    logger.warning("[open_android] Failed to record completed message: %s", rec_err)
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
            account.events.append(event_type, correlation_id, payload, occurred_at)
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
            logger.warning("[open_android] Account %s is unavailable for binding: %s", account_id, exc)
            return
        try:
            with account.store.transaction():
                account.agent_sessions.record(conversation_id, created_via)
                if isinstance(agent_session_id, str) and agent_session_id:
                    account.agent_sessions.attach(conversation_id, agent_session_id, session_key)
        except Exception as exc:
            logger.warning(
                "[open_android] Agent session binding failed for %s: %s", conversation_id, exc
            )
        finally:
            account.close()


    async def _notify_agent_inbound(self,path: str,body_bytes: bytes,response_body: Any,account_id: str | None = None) -> None:
        source_account=account_id or self._account_id
        conversation_id=_conversation_id_of(path)
        if not source_account or not conversation_id: return
        lock=self._generation_locks.setdefault((source_account,conversation_id),asyncio.Lock())
        async with lock:
            await self._dispatch_agent_inbound(path,body_bytes,response_body,source_account)
            data=json.loads(body_bytes)
            client_id=data.get("clientMessageId") or (data.get("members") or [{}])[0].get("clientMessageId")
            if not client_id or not callable(getattr(self,"cancel_session_processing",None)): return
            while True:
                account=self.services.core.open_gateway_account(source_account)
                try:
                    row=account.store.database.execute("SELECT message_id FROM messages WHERE conversation_id=? AND client_message_id=?",(conversation_id,client_id)).fetchone()
                    generation=account.conversations.workflow.for_message(row[0]) if row else None
                    # Unknown outcome keeps this thread paused until native completion proves a terminal state.
                    waiting=generation and generation["state"] in {"running","unknown"}
                finally: account.close()
                if not waiting: break
                await asyncio.sleep(0.2)


    async def _dispatch_agent_inbound(
        self, path: str, body_bytes: bytes, response_body: Any, account_id: str | None = None,
    ) -> None:
        """Notify Hermes agent of a user message received from the Android device."""
        source_account = account_id or self._account_id
        if not source_account:
            logger.warning("[open_android] Inbound message carries no account identity; not dispatching")
            return
        client_message_id: str | None = None
        dispatch_record: dict[str, Any] | None = None
        dispatch_claim_token: str | None = None
        materialized_files: list[Path] = []
        account = None
        error_code = "AGENT_UNAVAILABLE"
        handed_to_host = False
        try:
            conv_id = _conversation_id_of(path) or "default"
            self._conv_to_account[conv_id] = source_account
            # A user turn boundary: whatever the Agent answered before belongs to
            # the previous turn, so the same text from now on is a new message.
            self._forget_turn_reply(conv_id)

            data = json.loads(body_bytes.decode("utf-8"))
            user_text = data.get("text") or data.get("content") or ""
            client_message_id = data.get("clientMessageId") or data.get("clientTurnId") or (data.get("members") or [{}])[0].get("clientMessageId")
            client_message_id = str(client_message_id) if client_message_id else str(uuid.uuid4())

            if user_text.strip() == NEW_CONVERSATION_COMMAND:
                # The Gateway's own command entry owns `/new` (contract §7.1): it
                # has already created the conversation and answered with its id.
                # Handing the same text to the Agent would make the host run its
                # own new-session command on the *source* conversation, wiping the
                # memory the user is still reading.
                logger.info("[open_android] Reserved /new stays a command entry; not dispatched to the agent")
                return

            account = self.services.core.open_gateway_account(source_account)
            dispatch_record = account.conversations.dispatch_message(client_message_id)
            if dispatch_record is not None:
                if dispatch_record["status"] != "queued":
                    logger.info(
                        "[open_android] Message %s is already %s; duplicate dispatch ignored",
                        client_message_id, dispatch_record["status"],
                    )
                    return
                dispatch_record = account.conversations.claim_dispatch(client_message_id)
                if dispatch_record is None:
                    logger.info(
                        "[open_android] Message dispatch already claimed messageId=%s",
                        client_message_id,
                    )
                    return
                dispatch_claim_token = str(dispatch_record["claimToken"])
                error_code = "ATTACHMENT_READ_FAILED"
                media_urls, media_types, materialized_files = self._materialize_message_attachments(
                    source_account, dispatch_record, account,
                )
                error_code = "AGENT_UNAVAILABLE"
                user_text = str(dispatch_record["text"])
                client_message_id = str(dispatch_record["clientMessageId"])
                conversation_id = str(dispatch_record["conversationId"])
                created_at = datetime.fromisoformat(str(dispatch_record["createdAt"]).replace("Z", "+00:00"))
            else:
                media_urls, media_types = [], []
                conversation_id = conv_id
                created_at = datetime.now(timezone.utc)

            await self._ensure_agent_session(
                conv_id, source_account, force_new=False,
                created_via=AgentSessionBindings.CREATED_VIA_INBOUND_MESSAGE,
            )
            history_port = getattr(getattr(self.services.core, "history_reader", None), "__self__", None)
            if dispatch_record is not None and callable(getattr(history_port, "capture_submission", None)):
                from .history_media import HistoryMedia
                HistoryMedia(account).adopt_inbound(conversation_id, dispatch_record["messageId"],
                    dispatch_record["attachments"], media_urls)
                history_port.capture_submission(account, dispatch_record)

            event = MessageEvent(
                text=user_text,
                source=self._agent_source(conversation_id, source_account),
                message_type=(
                    MessageType.TEXT if not media_types else
                    getattr(MessageType, "PHOTO", MessageType.DOCUMENT)
                    if all(media_type.casefold().startswith("image/") for media_type in media_types) else
                    getattr(MessageType, "AUDIO", MessageType.DOCUMENT)
                    if all(media_type.casefold().startswith("audio/") for media_type in media_types) else
                    getattr(MessageType, "DOCUMENT", MessageType.TEXT)
                ),
                message_id=client_message_id,
                media_urls=media_urls,
                media_types=media_types,
                timestamp=created_at,
            )
            event._gateway_claim_token=dispatch_claim_token
            if dispatch_record is not None:
                self._processing_media_files[(source_account, client_message_id)] = list(materialized_files)
            logger.info(
                "[open_android] Dispatching Android message messageId=%s attachmentCount=%d",
                dispatch_record["messageId"] if dispatch_record is not None else client_message_id,
                len(media_urls),
            )
            from .context import trusted_turn
            origin_row = account.store.database.execute("SELECT value FROM account_metadata WHERE key=?",(f"message-device:{client_message_id}",)).fetchone() if account is not None else None
            binding_row = account.agent_sessions.lookup(conversation_id) if account is not None else None
            origin = json.loads(origin_row[0]) if origin_row else {}
            token = trusted_turn.set({**origin,"core":self.services.core,"accountId":source_account,
                "sessionId":binding_row.get("agentSessionId") if binding_row else None})
            try:
                await self.handle_message(event)
            finally:
                trusted_turn.reset(token)
            if getattr(event, "_gateway_accepted", False) is not True:
                raise GatewayError("AGENT_UNAVAILABLE")
            handed_to_host = True
            materialized_files = []
            if dispatch_record is not None and account is not None:
                current = account.conversations.dispatch_message(client_message_id)
                if current is not None and current['status'] == 'queued':
                    account.conversations.update_dispatch_status(
                        dispatch_record["messageId"], "delivered", None,
                        f"dispatch-{dispatch_record['messageId']}",
                        claim_token=dispatch_claim_token,
                    )
                for attachment_id in dispatch_record["attachmentIds"]:
                    try:
                        account.attachments.mark_delivered(attachment_id)
                        account.attachments.acknowledge(
                            attachment_id, f"message-ack-{dispatch_record['messageId']}",
                        )
                    except Exception as exc:
                        logger.warning(
                            "[open_android] Attachment ACK failed messageId=%s errorCode=INTERNAL_ERROR",
                            dispatch_record["messageId"],
                        )
        except Exception as exc:
            failure_code = str(getattr(exc, "code", "AGENT_UNAVAILABLE"))
            if failure_code not in {
                "AGENT_UNAVAILABLE", "ATTACHMENT_READ_FAILED", "AGENT_MEDIA_REJECTED",
                "MODEL_REQUEST_REJECTED",
            }:
                failure_code = "AGENT_UNAVAILABLE"
            logger.warning(
                "[open_android] Inbound dispatch failed messageId=%s errorCode=%s",
                dispatch_record["messageId"] if dispatch_record is not None else client_message_id,
                failure_code,
            )
            if dispatch_record is not None and account is not None:
                # Any error while materializing verified encrypted content is a
                # read failure; once local paths exist, a host dispatch error is
                # classified as unavailable for a safe client retry.
                status_error_code = (
                    error_code if error_code == "ATTACHMENT_READ_FAILED" else failure_code
                )
                if status_error_code not in {
                    "AGENT_UNAVAILABLE", "ATTACHMENT_READ_FAILED", "AGENT_MEDIA_REJECTED",
                    "MODEL_REQUEST_REJECTED",
                }:
                    status_error_code = error_code
                with suppress(Exception):
                    account.conversations.update_dispatch_status(
                        dispatch_record["messageId"], "failed", status_error_code,
                        f"dispatch-failed-{dispatch_record['messageId']}",
                        claim_token=dispatch_claim_token,
                    )
        finally:
            if dispatch_claim_token is not None and not handed_to_host and client_message_id:
                self._processing_media_files.pop((source_account, client_message_id), None)
            for cached_file in materialized_files:
                with suppress(OSError):
                    HistoryMedia._retire_path(cached_file)
            if account is not None:
                account.close()


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
        """Fail interrupted host handoffs durably instead of replaying a turn."""
        account = None
        try:
            account = self.services.core.open_gateway_account(account_id)
            account.attachments.recover_interrupted_uploads()
            for dispatch in account.conversations.incomplete_dispatches():
                account.conversations.update_dispatch_status(
                    dispatch["messageId"], "failed", "AGENT_UNAVAILABLE",
                    f"startup-recovery-{dispatch['messageId']}",
                    force=True,
                )
        except Exception as exc:
            logger.warning(
                "[open_android] Message dispatch recovery failed accountId=%s errorCode=%s",
                account_id, str(getattr(exc, "code", "INTERNAL_ERROR")),
            )
        finally:
            if account is not None:
                account.close()


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
        task = asyncio.current_task()
        if not account_id or not client_id or task is None:
            return
        account = self.services.core.open_gateway_account(account_id)
        try:
            record = account.conversations.dispatch_message(client_id)
            generation = account.conversations.workflow.for_message(record["messageId"]) if record else None
            if not generation:
                return
            key = (account_id, record["conversationId"], generation["generationId"])
            session_key = next((key for key, owner in self._session_tasks.items() if owner is task), None)
            if session_key is None:
                return
            self._native_turns[key] = (session_key, task, source)
            def release(done: asyncio.Task) -> None:
                turn = self._native_turns.get(key)
                if turn is not None and turn[1] is done:
                    self._native_turns.pop(key, None)
            task.add_done_callback(release)
        finally:
            account.close()


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
        account = None
        try:
            account = self.services.core.open_gateway_account(account_id)
            record = account.conversations.dispatch_message(client_message_id)
            if record is not None:
                generation=account.conversations.workflow.for_message(record["messageId"])
                if generation:
                    await self._finish_streaming_replies(account_id, record["conversationId"], generation["generationId"])
                if generation and generation["state"]=="cancelled":
                    status, error_code = "failed", "AGENT_UNAVAILABLE"
                if status=="completed" and record["status"]=="queued":
                    account.conversations.update_dispatch_status(record["messageId"],"delivered",None,f"agent-adopted-{record['messageId']}",claim_token=getattr(event,"_gateway_claim_token",None))
                account.conversations.workflow.settle(record["messageId"],status)
                account.conversations.update_dispatch_status(
                    record["messageId"], status, error_code,
                    f"agent-complete-{record['messageId']}",
                )
        except Exception as exc:
            logger.warning(
                "[open_android] Agent completion status failed messageId=%s errorCode=%s",
                client_message_id, str(getattr(exc, "code", "INTERNAL_ERROR")),
            )
        finally:
            if account is not None:
                account.close()
            for media_path in self._processing_media_files.pop((account_id, client_message_id), []):
                with suppress(OSError):
                    media_HistoryMedia._retire_path(path)
