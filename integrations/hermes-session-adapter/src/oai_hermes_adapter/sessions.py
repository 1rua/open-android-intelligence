"""Account-isolated native Hermes session operations."""
from __future__ import annotations
import asyncio
import json
import logging
import re
import uuid
from collections import deque
from contextlib import contextmanager
from typing import Any, AsyncIterator
from oai_core.gateway.core import APPROVAL_CHOICES
from oai_core.sessions import AdapterError

logger = logging.getLogger(__name__)


def is_host_command(text: str) -> bool:
    """Leave slash command recognition and handling to the native host."""
    return text.lstrip().startswith("/")


class HermesSessionAdapter:
    def __init__(self, core: Any, runtime: Any = None, *, agent_id: str = "hermes", name: str = "Hermes"):
        if runtime is not None and getattr(getattr(runtime, "services", None), "core", None) is not core:
            raise ValueError("Hermes runtime must use the same Core instance")
        self.core, self.runtime, self.agent_id, self.name = core, runtime, agent_id, name

    def _runtime_ready(self) -> bool:
        loop = getattr(self.runtime, "_loop", None)
        return (getattr(self.runtime, "_session_store", None) is not None
                and loop is not None and loop.is_running() and not loop.is_closed())

    def descriptor(self) -> dict[str, Any]:
        native = callable(getattr(self.core, "history_reader", None))
        history_port = getattr(getattr(self.core, "history_reader", None), "__self__", None)
        native_titles = callable(getattr(history_port, "_bound_database", None))
        writable = self._runtime_ready()
        send = writable and callable(getattr(self.runtime, "_message_handler", None))
        steer = send and callable(getattr(self.runtime, "native_steering_available", None)) \
            and bool(self.runtime.native_steering_available())
        manager = callable(getattr(self.core, "conversation_manager", None)) and native
        return {"id": self.agent_id, "name": self.name, "kind": "hermes", "sessionScope": "account",
                "capabilities": {"list": True, "history": native, "create": writable,
                                 "send": send, "steer": steer, "stream": send, "rename": native_titles,
                                 "archive": manager, "delete": manager,
                                 "cancel": send and callable(getattr(self.core, "generation_canceller", None)),
                                 "approvals": send and bool(getattr(self.runtime, "native_approvals_available", False)),
                                 "commands": True, "attachments": send, "generationRecovery": True}}

    def commands(self, language_code: str = "en") -> list[dict[str, Any]]:
        from .host_commands import command_catalog
        return command_catalog()

    async def status(self) -> bool:
        return self._runtime_ready() and callable(getattr(self.core, "history_reader", None))

    @contextmanager
    def _account(self, principal: Any):
        # Principal is minted by Core authentication. Never accept an account
        # id, session path, or native session id from a request body.
        account_id = getattr(principal, "account_id", None)
        if not isinstance(account_id, str) or not self.core.account_exists(account_id):
            raise AdapterError("AUTHENTICATION_REQUIRED", 401)
        account = self.core.open_gateway_account(account_id)
        try:
            yield account
        except Exception as exc:
            if hasattr(exc, "code") and not isinstance(exc, AdapterError):
                code = exc.code
                raise AdapterError(code, 404 if code in {"SCHEMA_INVALID", "CONVERSATION_NOT_FOUND"} else 409) from exc
            raise
        finally:
            account.close()

    @staticmethod
    def _native_database(account: Any, conversation_id: str) -> tuple[Any, str] | None:
        binding = account.agent_sessions.lookup(conversation_id)
        port = getattr(getattr(account.conversations, "history_reader", None), "__self__", None)
        resolve = getattr(port, "_bound_database", None)
        if not binding or not binding.get("agentSessionId") or not callable(resolve):
            return None
        database = resolve(binding)
        # A binding may retain a compression ancestor. Match native history and
        # lifecycle operations by addressing the current tip of its own chain.
        chain = port._session_chain(database, binding["agentSessionId"])
        if not chain:
            raise AdapterError("SESSION_NOT_FOUND", 404)
        return database, chain[-1]

    @staticmethod
    def _summary(account: Any, conversation_id: str) -> dict[str, Any]:
        # Unbound Gateway placeholders are not native sessions.
        binding = account.agent_sessions.lookup(conversation_id)
        if not binding or not binding.get("agentSessionId"):
            raise AdapterError("SESSION_NOT_FOUND", 404)
        value = account.conversations.management.record(conversation_id)
        native = HermesSessionAdapter._native_database(account, conversation_id)
        title = None
        if native is not None and callable(getattr(native[0], "get_session_title", None)):
            title = native[0].get_session_title(native[1])
        return {"id": conversation_id, "title": title or value.get("title") or "", "updatedAt": value["updatedAt"],
                "archived": bool(value.get("archived"))}

    async def list_sessions(self, principal: Any) -> list[dict[str, Any]]:
        def read():
            with self._account(principal) as account:
                result = []
                for binding in account.agent_sessions.list():
                    if not binding.get("agentSessionId"):
                        continue
                    try:
                        result.append(self._summary(account, binding["conversationId"]))
                    except Exception as exc:
                        if not (isinstance(exc, AdapterError) and exc.status == 404
                                or getattr(exc, "code", None) == "CONVERSATION_NOT_FOUND"):
                            raise
                return sorted(result, key=lambda row: row["updatedAt"], reverse=True)
        return await asyncio.to_thread(read)

    async def history(self, principal: Any, session_id: str) -> list[dict[str, Any]]:
        if not self.descriptor()["capabilities"]["history"]:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)
        def read():
            with self._account(principal) as account:
                self._summary(account, session_id)
                rows = list(self.core.history_reader(principal.account_id, session_id))
                final_replies = {row["anchorClientMessageId"]: row["messageId"] for row in rows
                    if row.get("sender") == "assistant" and row.get("anchorClientMessageId")}
                result = []
                for row in rows:
                    client_id = row.get("clientMessageId")
                    anchor = row.get("anchorClientMessageId")
                    if row.get("sender") == "assistant" and anchor:
                        client_id = anchor if final_replies.get(anchor) == row["messageId"] else None
                    result.append({"id": row["messageId"], "role": row.get("sender", "assistant"),
                        "text": row.get("text", ""), "createdAt": row.get("createdAt", row.get("timestamp")),
                        "clientMessageId": client_id,
                        **{field: row[field] for field in ("generationId", "contentRevision", "state", "executionSteps")
                           if field in row},
                        "attachments": [dict(part) for part in row.get("parts", []) if part.get("type") == "attachment"]})
                return result
        return await asyncio.to_thread(read)

    async def current_generation(self, principal: Any, session_id: str) -> dict[str, Any] | None:
        if not self.descriptor()["capabilities"]["send"]:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)

        def read() -> dict[str, Any] | None:
            with self._account(principal) as account:
                self._summary(account, session_id)
                generation = account.conversations.workflow.current(session_id)
                return None if generation is None else {
                    "generationId": generation["generationId"],
                    "conversationId": generation["conversationId"],
                    "state": generation["state"],
                }

        return await asyncio.to_thread(read)

    async def generation(
        self, principal: Any, session_id: str, *,
        client_message_id: str | None = None, generation_id: str | None = None,
        after_revision: int | None = None, wait_ms: int = 0,
    ) -> dict[str, Any]:
        """Read an accepted turn's durable outcome, including after host shutdown.

        The active-turn view deliberately forgets terminal generations. Recovery
        must address the original accepted identity and never infer success from
        the absence of active work or resubmit the prompt.
        """
        identities = (client_message_id, generation_id)
        if not any(value is not None for value in identities) or any(
                value is not None and (not isinstance(value, str)
                                       or not re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", value))
                for value in identities):
            raise AdapterError("SCHEMA_INVALID", 400)
        if (after_revision is not None and (type(after_revision) is not int or after_revision < 0)
                or type(wait_ms) is not int or not 0 <= wait_ms <= 25000):
            raise AdapterError("SCHEMA_INVALID", 400)

        def read() -> dict[str, Any]:
            with self._account(principal) as account, account.store.transaction():
                self._summary(account, session_id)
                row = account.store.database.execute(
                    "SELECT message_id,client_message_id FROM messages "
                    "WHERE conversation_id=? AND client_message_id=? AND sender='user'",
                    (session_id, client_message_id),
                ).fetchone() if client_message_id is not None else None
                generation = (account.conversations.workflow.for_message(row["message_id"]) if row else
                              account.conversations.workflow._get("generation:" + generation_id)
                              if client_message_id is None and generation_id is not None else None)
                if (generation is None or generation["conversationId"] != session_id
                        or generation_id is not None and generation["generationId"] != generation_id):
                    raise AdapterError("GENERATION_NOT_FOUND", 404)
                if row is None:
                    row = account.store.database.execute(
                        "SELECT message_id,client_message_id FROM messages "
                        "WHERE conversation_id=? AND message_id=? AND sender='user'",
                        (session_id, generation["messageIds"][0]),
                    ).fetchone()
                if row is None:
                    raise AdapterError("GENERATION_NOT_FOUND", 404)
                return {
                    "conversationId": session_id, "generationId": generation["generationId"],
                    "clientMessageId": row["client_message_id"], "userMessageId": row["message_id"],
                    "state": generation["state"], "revision": int(generation.get("revision", 0)),
                }

        if after_revision is None or wait_ms == 0:
            return await asyncio.to_thread(read)
        loop, changed = asyncio.get_running_loop(), asyncio.Event()
        def wake(account_id, event):
            if account_id == principal.account_id and event.get("payload", {}).get("conversationId") == session_id:
                loop.call_soon_threadsafe(changed.set)
        self.core.register_event_sink(wake)
        deadline = loop.time() + wait_ms / 1000
        try:
            while True:
                changed.clear()
                receipt = await asyncio.to_thread(read)
                if (receipt["state"] in {"completed", "failed", "cancelled"}
                        or receipt["revision"] > after_revision or loop.time() >= deadline):
                    return receipt
                try:
                    await asyncio.wait_for(changed.wait(), deadline - loop.time())
                except TimeoutError:
                    return await asyncio.to_thread(read)
        finally:
            self.core.unregister_event_sink(wake)

    async def create(self, principal: Any, title: str | None) -> dict[str, Any]:
        if not self.descriptor()["capabilities"]["create"]:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)
        client_id = "core_" + uuid.uuid4().hex
        def create():
            with self._account(principal) as account:
                return account.conversations.create(client_id, title, client_id)
        created = await asyncio.to_thread(create)
        native_id = await self.runtime._ensure_agent_session(created["conversationId"], principal.account_id,
                            force_new=False, created_via="conversation-create")
        if not native_id:
            raise AdapterError("HOST_INCOMPATIBLE", 503)
        def read():
            with self._account(principal) as account:
                if title:
                    self._rename_native(account, created["conversationId"], title)
                return self._summary(account, created["conversationId"])
        return await asyncio.to_thread(read)

    async def send(self, principal: Any, session_id: str, text: str, *, attachment_ids: list[str] | None = None,
                   client_message_id: str | None = None) -> AsyncIterator[dict[str, Any]]:
        if not self.descriptor()["capabilities"]["send"]:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)
        device_id = getattr(principal, "device_id", None) or getattr(getattr(principal, "context", None), "device_id", None)
        if not device_id:
            # The existing Hermes dispatch establishes device origin from a
            # verified pairing. Never fabricate one for a CLI service token.
            raise AdapterError("DEVICE_CONTEXT_REQUIRED", 403)
        client_id, loop = client_message_id or "core_" + uuid.uuid4().hex, asyncio.get_running_loop()
        attachment_ids = list(attachment_ids or [])
        if is_host_command(text) and not attachment_ids:
            def validate_control() -> None:
                with self._account(principal) as account:
                    self._summary(account, session_id)
            await asyncio.to_thread(validate_control)
            receipt = await self.runtime.dispatch_native_input(
                principal.account_id,
                session_id,
                text,
                client_id,
            )
            messages = receipt.get("messages", [])
            for message in messages:
                yield {"type": "delta", "text": message["text"], "messageId": message["messageId"],
                       "clientMessageId": client_id}
            yield {
                "type": "done",
                "outcome": "completed",
                "text": messages[-1]["text"] if messages else receipt.get("text", ""),
                "clientMessageId": client_id,
                **({"messageId": messages[-1]["messageId"]} if messages else {}),
            }
            return
        def accept():
            with self._account(principal) as account, account.store.transaction():
                self._summary(account, session_id)
                prior = account.store.database.execute(
                    "SELECT message_id FROM messages WHERE client_message_id=? AND sender='user' LIMIT 1", (client_id,)).fetchone()
                if prior is not None:
                    generation = account.conversations.workflow.for_message(prior["message_id"])
                    code = "REQUEST_ALREADY_EXECUTED" if (generation or {}).get("state") in {"completed", "cancelled", "failed"} else "OUTCOME_UNKNOWN"
                    raise AdapterError(code, 409)
                for attachment_id in attachment_ids:
                    row = account.store.database.execute(
                        "SELECT owner_device_id, owner_pairing_generation FROM attachments WHERE attachment_id=?", (attachment_id,)).fetchone()
                    if (row is None or row["owner_device_id"] != device_id
                            or row["owner_pairing_generation"] != (getattr(principal, "pairing_generation", None) or 0)):
                        raise AdapterError("ATTACHMENT_ACCESS_DENIED", 403)
                    account.attachments.require_verified_for_message(attachment_id)
                accepted = account.conversations.accept_message(session_id, client_id, text, attachment_ids, device_id, client_id, client_id)
                # The session host has a separate account store. Cancellation
                # must retain Core's authenticated pairing context, rather than
                # the legacy store's fallback generation for an unknown phone.
                with account.store.transaction():
                    for identity in (accepted["messageId"], client_id):
                        key = f"message-device:{identity}"
                        row = account.store.database.execute("SELECT value FROM account_metadata WHERE key=?", (key,)).fetchone()
                        origin = json.loads(row[0])
                        for field, attribute in (("pairingGeneration", "pairing_generation"), ("grantRevision", "grant_revision")):
                            value = getattr(principal, attribute, None)
                            if value is not None:
                                origin[field] = value
                        account.store.database.execute("UPDATE account_metadata SET value=? WHERE key=?", (json.dumps(origin), key))
                cursor = account.store.database.execute(
                    "SELECT event_id FROM events ORDER BY sequence DESC LIMIT 1").fetchone()
                generation = account.conversations.workflow.for_message(accepted["messageId"])
                if generation is None or generation["generationId"] != accepted.get("generationId"):
                    raise AdapterError("HOST_INCOMPATIBLE", 502)
                return accepted, cursor[0] if cursor else None, {
                    "state": generation["state"], "revision": int(generation.get("revision", 0)),
                }
        accepted, cursor, initial_generation = await asyncio.to_thread(accept)
        generation_id = accepted.get("generationId")
        changed = asyncio.Event()
        from .tool_events import NOTICE_CODE
        def is_execution_notice(event):
            return (event.get("eventType") == "gateway.notice"
                    and event.get("correlationId") == accepted["messageId"]
                    and event.get("payload", {}).get("noticeCode") == NOTICE_CODE)
        def sink(account_id, event):
            payload = event.get("payload", {})
            if account_id == principal.account_id and (payload.get("conversationId") == session_id or is_execution_notice(event)):
                if payload.get("generationId") not in {None, generation_id}:
                    return
                loop.call_soon_threadsafe(changed.set)
        def read_events():
            # Durable commit order is the buffer. A slow observer must never
            # evict another real message, an approval, or a terminal event.
            with self._account(principal) as account:
                return account.events.read_after(cursor, limit=128)
        def belongs(event):
            payload = event.get("payload", {})
            return is_execution_notice(event) or (payload.get("conversationId") == session_id
                    and payload.get("generationId") in {None, generation_id})
        self.core.register_event_sink(sink)
        body = {"clientMessageId": client_id, "text": text, "attachmentIds": attachment_ids}
        task = asyncio.create_task(self.runtime._notify_agent_inbound(
            f"/open-android-intelligence/v2/conversations/{session_id}/messages",
            json.dumps(body).encode(), {"data": accepted}, principal.account_id))
        self.runtime._track_background_task(task, "core-session-message")
        task.add_done_callback(lambda _task: changed.set())
        pending_events = deque()
        previous = ""
        assistant_message_id = None
        assistant_revision = None
        assistant_event_id = None
        identity = {"clientMessageId": client_id, "userMessageId": accepted["messageId"]}
        lifecycle_revision = initial_generation["revision"]
        def read_generation():
            with self._account(principal) as account, account.store.transaction():
                generation = account.conversations.workflow.for_message(accepted["messageId"])
                if generation is None or generation["generationId"] != generation_id:
                    raise AdapterError("HOST_INCOMPATIBLE", 502)
                return generation
        def message_identity():
            return {
                **({"messageId": assistant_message_id} if assistant_message_id else {}),
                **({"contentRevision": assistant_revision} if assistant_revision is not None else {}),
                **({"eventId": assistant_event_id} if assistant_event_id else {}),
            }
        async def execution_frame(event_id):
            observer = getattr(self.runtime, "_tool_observer", None)
            try:
                snapshot = (await asyncio.to_thread(observer.snapshot, principal.account_id, session_id, generation_id)
                            if observer is not None else None)
            except Exception as observation_failure:
                logger.warning("Native tool snapshot unavailable errorType=%s", type(observation_failure).__name__)
                return None
            return ({"type": "generation", "generationId": generation_id, **identity,
                     "eventId": event_id, **snapshot} if snapshot is not None else None)
        try:
            yield {"type": "generation", "generationId": generation_id, **identity, **initial_generation}
            async with asyncio.timeout(1800):
                while True:
                    if not pending_events:
                        # Clear before reading: a commit racing with the read
                        # either appears in this page or leaves the wake set.
                        changed.clear()
                        pending_events.extend(await asyncio.to_thread(read_events))
                    if not pending_events:
                        if task.done():
                            def state():
                                with self._account(principal) as account:
                                    return account.conversations.workflow.for_message(accepted["messageId"])
                            generation = await asyncio.to_thread(state)
                            status = (generation or {}).get("state")
                            if status not in {"completed", "cancelled", "failed"}:
                                # A host Task may finish before its completion
                                # callback/cancel handoff commits. Task ownership
                                # is not an authoritative execution outcome.
                                if not task.cancelled() and task.exception() is not None:
                                    yield {"type": "error", "code": "OUTCOME_UNKNOWN"}
                                    return
                                await changed.wait()
                                continue
                            withdraw = getattr(self.runtime, "withdraw_generation_approvals", None)
                            if callable(withdraw):
                                await withdraw(principal.account_id, session_id, generation_id)
                            # Approval withdrawal may commit more events. Apply
                            # every committed page before publishing the terminal.
                            pending_events.extend(await asyncio.to_thread(read_events))
                            if pending_events:
                                continue
                            if status in {"completed", "cancelled"}:
                                yield {"type": "done", "text": previous, "generationId": generation_id,
                                       "outcome": "cancelled" if status == "cancelled" else "completed", **identity,
                                       **message_identity()}
                            else:
                                yield {"type": "error", "code": "AGENT_FAILED"}
                            return
                        await changed.wait()
                        continue
                    event = pending_events.popleft()
                    cursor = event["eventId"]
                    if not belongs(event):
                        continue
                    if event.get("eventType") == "conversation.message.status":
                        payload = event["payload"]
                        if (payload.get("status") == "delivered" and payload.get("messageId") == accepted["messageId"]
                                and payload.get("clientMessageId") == client_id):
                            generation = await asyncio.to_thread(read_generation)
                            # A real native delivery receipt, rather than the
                            # existence of a background Task, proves admission.
                            # Suppress an old delivery event if it was replayed
                            # after this generation already reached a terminal.
                            revision = int(generation.get("revision", 0))
                            if generation["state"] == "running" and revision > lifecycle_revision:
                                lifecycle_revision = revision
                                yield {"type": "generation", "generationId": generation_id, **identity,
                                       "state": "running", "revision": revision, "eventId": event["eventId"]}
                        continue
                    if is_execution_notice(event):
                        frame = await execution_frame(event["eventId"])
                        if frame is not None:
                            yield frame
                        continue
                    if event.get("eventType") == "core.approval.unsupported":
                        yield {"type": "error", "code": "UNSUPPORTED_APPROVAL"}
                        return
                    if event.get("eventType") in {"conversation.approval.requested", "conversation.approval.resolved"}:
                        yield {"type": "approval_requested" if event["eventType"].endswith("requested") else "approval_resolved",
                               "approval": event["payload"], "generationId": generation_id}
                        continue
                    if event.get("eventType") == "conversation.generation.cancelled":
                        withdraw = getattr(self.runtime, "withdraw_generation_approvals", None)
                        if callable(withdraw):
                            await withdraw(principal.account_id, session_id, generation_id)
                        observer = getattr(self.runtime, "_tool_observer", None)
                        if observer is not None:
                            try:
                                await asyncio.to_thread(observer.close_generation, principal.account_id, session_id,
                                    generation_id, accepted["messageId"])
                            except Exception as observation_failure:
                                logger.warning("Native cancelled tool observation unavailable errorType=%s",
                                    type(observation_failure).__name__)
                        # Publish the authoritative withdrawn cards after the
                        # cancellation barrier; late text cannot revive it.
                        while tail_events := await asyncio.to_thread(read_events):
                            for tail in tail_events:
                                cursor = tail["eventId"]
                                if belongs(tail) and tail.get("eventType") == "conversation.approval.resolved":
                                    yield {"type": "approval_resolved", "approval": tail["payload"], "generationId": generation_id}
                                elif is_execution_notice(tail):
                                    frame = await execution_frame(tail["eventId"])
                                    if frame is not None:
                                        yield frame
                        yield {"type": "done", "text": previous, "generationId": generation_id, "outcome": "cancelled", **identity,
                               **message_identity()}
                        return
                    if event.get("eventType") in {"conversation.message.delta", "conversation.message.completed"}:
                        payload = event["payload"]
                        if payload.get("sender") not in {None, "assistant"}:
                            continue
                        current = payload.get("text", "")
                        message_id = payload.get("messageId")
                        if message_id != assistant_message_id:
                            previous = ""
                        assistant_message_id = message_id
                        revision = payload.get("revision")
                        assistant_revision = revision if type(revision) is int and revision >= 0 else None
                        assistant_event_id = event["eventId"]
                        if current.startswith(previous):
                            if current[len(previous):]:
                                yield {"type": "delta", "text": current[len(previous):], "generationId": generation_id,
                                       **message_identity(), **identity}
                        previous = current
        except TimeoutError:
            yield {"type": "error", "code": "AGENT_TIMEOUT"}
        finally:
            self.core.unregister_event_sink(sink)

    async def steer(
        self,
        principal: Any,
        session_id: str,
        text: str,
        generation_id: str | None,
        client_message_id: str | None = None,
    ) -> dict[str, Any]:
        if generation_id is not None and (not isinstance(generation_id, str) or not generation_id):
            raise AdapterError("INVALID_REQUEST", 400)

        def running_generation() -> str:
            with self._account(principal) as account:
                self._summary(account, session_id)
                binding = account.agent_sessions.lookup(session_id)
                generation = account.conversations.workflow.current(session_id)
                if (not binding or not binding.get("agentSessionId") or generation is None
                        or generation_id is not None and generation.get("generationId") != generation_id
                        or generation.get("state") != "running"):
                    raise AdapterError("STEER_UNAVAILABLE", 409)
                return generation["generationId"]

        generation_id = await asyncio.to_thread(running_generation)
        return await self.runtime.steer_native_turn(
            principal.account_id,
            session_id,
            generation_id,
            text,
            client_message_id=client_message_id,
        )

    async def decide_approval(self, principal: Any, session_id: str, approval_id: str, decision: str) -> dict[str, Any]:
        if not isinstance(decision, str) or decision not in APPROVAL_CHOICES:
            raise AdapterError("SCHEMA_INVALID", 400)
        if not self.descriptor()["capabilities"].get("approvals"):
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)
        with self._account(principal) as account:
            self._summary(account, session_id)
        return await self.runtime.decide_native_approval(principal.account_id, session_id, approval_id,
            decision, getattr(principal, "device_id", None))

    @staticmethod
    def _rename_native(account: Any, session_id: str, title: str) -> None:
        native = HermesSessionAdapter._native_database(account, session_id)
        setter = getattr(native[0], "set_session_title", None) if native is not None else None
        if not callable(setter):
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)
        try:
            renamed = setter(native[1], title)
        except ValueError as exc:
            # Native uniqueness errors can contain other sessions' identifiers.
            raise AdapterError("NATIVE_TITLE_CONFLICT", 409) from exc
        if not renamed:
            raise AdapterError("SESSION_NOT_FOUND", 404)

    async def manage(self, principal: Any, session_id: str, action: str, value: Any = None) -> dict[str, Any]:
        if not self.descriptor()["capabilities"].get(action):
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)
        operation_id = "core_" + uuid.uuid4().hex
        def manage():
            with self._account(principal) as account:
                self._summary(account, session_id)
                if action == "rename":
                    if not isinstance(value, str) or not value.strip():
                        raise AdapterError("SCHEMA_INVALID", 400)
                    self._rename_native(account, session_id, value)
                    account.conversations.update_title(session_id, value, operation_id)
                    return self._summary(account, session_id)
                if action == "cancel":
                    if value is None:
                        return {"id": session_id, "outcome": "outcome_unknown"}
                    if not isinstance(value, dict) or set(value) != {"generationId"} or not isinstance(value["generationId"], str) or not value["generationId"]:
                        raise AdapterError("SCHEMA_INVALID", 400)
                    generation_id = value["generationId"]
                    generation = account.conversations.workflow._get("generation:" + generation_id)
                    if generation is None or generation["conversationId"] != session_id:
                        raise AdapterError("GENERATION_NOT_FOUND", 404)
                    context = {"deviceId": getattr(principal, "device_id", None),
                               "pairingGeneration": getattr(principal, "pairing_generation", None),
                               "correlationId": operation_id}
                    cancel = lambda cid, gid: self.core.generation_canceller(principal.account_id, cid, gid)
                    outcome = account.conversations.workflow.prepare_cancel(session_id, generation_id, context, cancel)
                    with account.store.transaction():
                        result = account.conversations.workflow.finish_cancel(session_id, generation_id, outcome, context, None)
                    return {**result, "outcome": result["outcome"].lower()}
                if action == "archive" and not isinstance(value, bool):
                    raise AdapterError("SCHEMA_INVALID", 400)
                archive = value if action == "archive" else None
                account.conversations.management.begin(session_id, operation_id, action, archive)
                try:
                    self.core.conversation_manager(principal.account_id, session_id, action, archive)
                except Exception as exc:
                    if getattr(exc, "code", None) in {"CONVERSATION_BUSY", "HOST_INCOMPATIBLE"}:
                        account.conversations.management.abort(session_id, operation_id)
                    raise
                return account.conversations.management.commit(session_id, action, archive, operation_id)
        return await asyncio.to_thread(manage)
