"""Account-scoped access to the existing Hermes Gateway session bindings.

There is deliberately no scan of Hermes' global session directory. The
Gateway's account binding and native HistoryPort remain the authority.
"""
from __future__ import annotations

import asyncio
import json
import uuid
from contextlib import contextmanager
from typing import Any, AsyncIterator
from urllib.parse import quote

from ..sessions import AdapterError
from .dsh import DshSessionAdapter


class HermesRemoteSessionAdapter(DshSessionAdapter):
    """Only configured Core accounts can select their mapped Hermes account."""
    def __init__(self, base_url: str, token: str, accounts: dict[str, str], *, agent_id="hermes", name="Hermes"):
        if (not isinstance(accounts, dict) or not accounts
                or any(not isinstance(key, str) or not key or not isinstance(value, str) or not value for key, value in accounts.items())
                or len(set(accounts.values())) != len(accounts)):
            raise ValueError("Hermes requires an explicit one-to-one Core account mapping")
        super().__init__(base_url, token, agent_id=agent_id, name=name)
        self.accounts = dict(accounts)

    def descriptor(self) -> dict[str, Any]:
        return {**super().descriptor(), "kind": "hermes", "sessionScope": "account"}

    def _headers(self, principal: Any) -> dict[str, str]:
        account_id = self.accounts.get(getattr(principal, "account_id", None))
        if account_id is None:
            raise AdapterError("AGENT_ACCOUNT_NOT_CONNECTED", 403)
        result = {"X-OAI-Account": account_id}
        device_id = getattr(principal, "device_id", None)
        if device_id:
            result["X-OAI-Device"] = device_id
        for name, attribute in (("X-OAI-Pairing-Generation", "pairing_generation"), ("X-OAI-Grant-Revision", "grant_revision")):
            value = getattr(principal, attribute, None)
            if value is not None:
                result[name] = str(value)
        return result

    async def list_sessions(self, principal):
        return (await self._request("GET", "/sessions", headers=self._headers(principal)))["sessions"]

    async def history(self, principal, session_id):
        return (await self._request("GET", f"/sessions/{quote(session_id, safe='')}/history", headers=self._headers(principal)))["messages"]

    async def create(self, principal, title):
        return (await self._request("POST", "/sessions", {"title": title}, headers=self._headers(principal)))["session"]

    async def send(self, principal, session_id, text):
        async for event in self._send(session_id, text, headers=self._headers(principal)):
            yield event

    async def manage(self, principal, session_id, action, value=None):
        if action not in {"rename", "archive", "delete", "cancel"}:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)
        return await self._request("POST", f"/sessions/{quote(session_id, safe='')}/{action}", {"value": value}, headers=self._headers(principal))


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
        manager = callable(getattr(self.core, "conversation_manager", None)) and native
        return {"id": self.agent_id, "name": self.name, "kind": "hermes", "sessionScope": "account",
                "capabilities": {"list": True, "history": native, "create": writable,
                                 "send": send, "stream": send, "rename": native_titles,
                                 "archive": manager, "delete": manager,
                                 "cancel": send and callable(getattr(self.core, "generation_canceller", None))}}

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
                return [{"id": row["messageId"], "role": row.get("sender", "assistant"),
                         "text": row.get("text", ""), "createdAt": row.get("createdAt", row.get("timestamp"))}
                        for row in self.core.history_reader(principal.account_id, session_id)]
        return await asyncio.to_thread(read)

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

    async def send(self, principal: Any, session_id: str, text: str) -> AsyncIterator[dict[str, Any]]:
        if not self.descriptor()["capabilities"]["send"]:
            raise AdapterError("UNSUPPORTED_CAPABILITY", 409)
        device_id = getattr(principal, "device_id", None) or getattr(getattr(principal, "context", None), "device_id", None)
        if not device_id:
            # The existing Hermes dispatch establishes device origin from a
            # verified pairing. Never fabricate one for a CLI service token.
            raise AdapterError("DEVICE_CONTEXT_REQUIRED", 403)
        client_id, loop, queue = "core_" + uuid.uuid4().hex, asyncio.get_running_loop(), asyncio.Queue(maxsize=1024)
        def accept():
            with self._account(principal) as account:
                self._summary(account, session_id)
                accepted = account.conversations.accept_message(session_id, client_id, text, [], device_id, client_id, client_id)
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
                return accepted
        accepted = await asyncio.to_thread(accept)
        generation_id = accepted.get("generationId")
        def enqueue(event):
            if queue.full():
                queue.get_nowait()
            queue.put_nowait(event)
        def sink(account_id, event):
            payload = event.get("payload", {})
            if account_id == principal.account_id and payload.get("conversationId") == session_id:
                if payload.get("generationId") not in {None, generation_id}:
                    return
                loop.call_soon_threadsafe(enqueue, dict(event))
        self.core.register_event_sink(sink)
        body = {"clientMessageId": client_id, "text": text, "attachmentIds": []}
        task = asyncio.create_task(self.runtime._notify_agent_inbound(
            f"/open-android-intelligence/v2/conversations/{session_id}/messages",
            json.dumps(body).encode(), {"data": accepted}, principal.account_id))
        self.runtime._track_background_task(task, "core-session-message")
        previous = ""
        try:
            async with asyncio.timeout(1800):
                while True:
                    try:
                        event = await asyncio.wait_for(queue.get(), .2)
                    except TimeoutError:
                        if task.done():
                            await task
                            def state():
                                with self._account(principal) as account:
                                    return account.conversations.workflow.for_message(accepted["messageId"])
                            generation = await asyncio.to_thread(state)
                            status = (generation or {}).get("state")
                            if status == "completed":
                                yield {"type": "done", "text": previous}
                            else:
                                yield {"type": "error", "code": "CANCELLED" if status == "cancelled" else "AGENT_FAILED"}
                            return
                        continue
                    if event.get("eventType") == "core.approval.unsupported":
                        yield {"type": "error", "code": "UNSUPPORTED_APPROVAL"}
                        return
                    if event.get("eventType") in {"conversation.message.delta", "conversation.message.completed"}:
                        payload = event["payload"]
                        if payload.get("sender") not in {None, "assistant"}:
                            continue
                        current = payload.get("text", "")
                        if current.startswith(previous):
                            if current[len(previous):]:
                                yield {"type": "delta", "text": current[len(previous):]}
                        previous = current
        except TimeoutError:
            yield {"type": "error", "code": "AGENT_TIMEOUT"}
        finally:
            self.core.unregister_event_sink(sink)

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
                    current = account.conversations.workflow.current(session_id)
                    if current is None:
                        return {"id": session_id, "outcome": "ALREADY_COMPLETED"}
                    context = {"deviceId": getattr(principal, "device_id", None),
                               "pairingGeneration": getattr(principal, "pairing_generation", None),
                               "correlationId": operation_id}
                    cancel = lambda cid, gid: self.core.generation_canceller(principal.account_id, cid, gid)
                    outcome = account.conversations.workflow.prepare_cancel(session_id, current["generationId"], context, cancel)
                    return account.conversations.workflow.finish_cancel(session_id, current["generationId"], outcome, context, None)
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
