"""Scoped, acknowledged replay over the account's existing durable EventStore."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import re
from typing import Any

from oai_core.sessions import AdapterError
from oai_core.gateway.core import iso_millis
from .tool_events import NOTICE_CODE


class NativeEventSync:
    def __init__(self, adapter: Any, principal: Any, session_id: str, consumer_id: str):
        if not isinstance(consumer_id, str) or re.fullmatch(r"[a-f0-9]{64}", consumer_id) is None:
            raise AdapterError("INVALID_EVENT_OBSERVER", 400)
        self.adapter, self.principal, self.session_id = adapter, principal, session_id
        self.purpose = "native-event-cursor:v1:" + hashlib.sha256(
            json.dumps([consumer_id, session_id], separators=(",", ":")).encode()).hexdigest()
        self.ack_key = self.purpose + ":ack"

    def _decode(self, store, cursor):
        try:
            if not isinstance(cursor, str) or not 1 <= len(cursor) <= 8192:
                raise ValueError("cursor")
            if re.fullmatch(r"[A-Za-z0-9_-]+", cursor) is None:
                raise ValueError("cursor")
            sealed = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)).decode("ascii")
            value = store.open_json(sealed, self.purpose)
            if (not isinstance(value, dict) or set(value) != {"v", "sequence", "eventId"}
                    or type(value["v"]) is not int or value["v"] != 1 or type(value["sequence"]) is not int or value["sequence"] < 0
                    or value["eventId"] is not None and not isinstance(value["eventId"], str)):
                raise ValueError("cursor")
            return value
        except Exception as exc:
            raise AdapterError("INVALID_EVENT_CURSOR", 400) from exc

    def _encode(self, store, position):
        sealed = store.seal_json(position, self.purpose)
        return base64.urlsafe_b64encode(sealed.encode("ascii")).rstrip(b"=").decode("ascii")

    def _saved_ack(self, store):
        row = store.database.execute("SELECT value FROM account_metadata WHERE key=?", (self.ack_key,)).fetchone()
        if row is None:
            return None
        try:
            value = json.loads(row[0])
            self._decode(store, value["cursor"])
            return value["cursor"]
        except Exception as exc:
            raise AdapterError("EVENT_ACK_UNAVAILABLE", 503) from exc

    @staticmethod
    def _tail(store):
        row = store.database.execute("SELECT event_id,sequence FROM events ORDER BY sequence DESC LIMIT 1").fetchone()
        if row is not None:
            return {"v": 1, "sequence": row["sequence"], "eventId": row["event_id"]}
        row = store.database.execute("SELECT seq FROM sqlite_sequence WHERE name='events'").fetchone()
        return {"v": 1, "sequence": row[0] if row else 0, "eventId": None}

    def _belongs(self, account, event):
        kind, payload = event["eventType"], event["payload"]
        if kind.startswith(("conversation.message.", "conversation.generation.", "conversation.approval.")):
            return payload.get("conversationId") == self.session_id
        if kind == "gateway.notice" and payload.get("noticeCode") == NOTICE_CODE:
            row = account.store.database.execute(
                "SELECT conversation_id FROM messages WHERE message_id=? AND sender='user'",
                (event["correlationId"],)).fetchone()
            return row is not None and row[0] == self.session_id
        return False

    def _approvals(self, account):
        result = []
        rows = account.store.database.execute(
            "SELECT * FROM approvals WHERE conversation_id=? ORDER BY requested_at,approval_id", (self.session_id,))
        for row in rows:
            saved = account.approvals._map(row)
            if saved["decision"] is None:
                payload = {key: saved[key] for key in ("approvalId", "conversationId", "command", "reason",
                    "options", "timeoutSeconds", "requestedAt", "expiresAt")}
                payload["options"] = [{key: option[key] for key in ("choice", "label", "style") if key in option}
                    for option in saved["options"] if isinstance(option, dict)]
                if saved.get("severity"):
                    payload["severity"] = saved["severity"]
                result.append({"type": "approval_requested", "approval": payload})
            else:
                payload = {key: saved[key] for key in ("approvalId", "conversationId", "decision", "decidedAt")}
                result.append({"type": "approval_resolved", "approval": payload})
        return result

    def _read(self, cursor, limit, snapshot):
        with self.adapter._account(self.principal) as account, account.store.transaction():
            self.adapter._summary(account, self.session_id)
            store = account.store
            account.events.purge_expired()
            if snapshot:
                # Capture the boundary BEFORE the client reloads native history.
                # A concurrent later commit belongs to the following replay page.
                position = self._tail(store)
                return {"events": [], "cursor": self._encode(store, position),
                        "hasMore": False, "approvals": self._approvals(account)}
            cursor = cursor if cursor is not None else self._saved_ack(store)
            position = self._decode(store, cursor) if cursor is not None else {"v": 1, "sequence": 0, "eventId": None}
            marker_key = "conversation-event-pruned:v1:" + hashlib.sha256(self.session_id.encode()).hexdigest()
            marker = store.database.execute("SELECT value FROM account_metadata WHERE key=?", (marker_key,)).fetchone()
            if marker is not None and int(marker[0]) > position["sequence"]:
                raise AdapterError("CURSOR_EXPIRED", 409)
            # A snapshot owns the boundary, not the lifetime of its previous
            # event. Include retained expired rows in this bounded scan so even
            # an expiry backlog beyond purge's 1000-row budget is observable.
            rows = store.database.execute(
                "SELECT * FROM events WHERE sequence>? ORDER BY sequence LIMIT ?",
                (position["sequence"], limit + 1),
            ).fetchall()
            scanned = rows[:limit]
            events, current = [], iso_millis()
            for row in scanned:
                event = account.events._map(row)
                if not self._belongs(account, event):
                    continue
                if row["expires_at"] <= current:
                    raise AdapterError("CURSOR_EXPIRED", 409)
                events.append(event)
            if scanned:
                position = {"v": 1, "sequence": scanned[-1]["sequence"], "eventId": scanned[-1]["event_id"]}
                cursor = self._encode(store, position)
            elif cursor is None:
                cursor = self._encode(store, position)
            return {"events": events, "cursor": cursor, "hasMore": len(rows) > limit}

    async def page(self, *, cursor=None, limit=128, wait_ms=0, snapshot=False):
        if (type(limit) is not int or not 1 <= limit <= 128 or type(wait_ms) is not int
                or not 0 <= wait_ms <= 25000 or type(snapshot) is not bool
                or snapshot and (cursor is not None or wait_ms)):
            raise AdapterError("INVALID_EVENT_QUERY", 400)
        if snapshot or not wait_ms:
            return await asyncio.to_thread(self._read, cursor, limit, snapshot)
        loop, changed = asyncio.get_running_loop(), asyncio.Event()
        def wake(account_id, _event):
            if account_id == self.principal.account_id:
                loop.call_soon_threadsafe(changed.set)
        self.adapter.core.register_event_sink(wake)
        deadline = loop.time() + wait_ms / 1000
        try:
            while True:
                changed.clear()
                page = await asyncio.to_thread(self._read, cursor, limit, False)
                if page["events"] or page["hasMore"] or loop.time() >= deadline:
                    return page
                cursor = page["cursor"]
                try:
                    await asyncio.wait_for(changed.wait(), max(0, deadline - loop.time()))
                except TimeoutError:
                    return await asyncio.to_thread(self._read, cursor, limit, False)
        finally:
            self.adapter.core.unregister_event_sink(wake)

    async def acknowledge(self, cursor):
        def write():
            with self.adapter._account(self.principal) as account, account.store.transaction():
                self.adapter._summary(account, self.session_id)
                position = self._decode(account.store, cursor)
                if position["sequence"] > self._tail(account.store)["sequence"]:
                    raise AdapterError("INVALID_EVENT_CURSOR", 400)
                previous = self._saved_ack(account.store)
                if previous is not None and self._decode(account.store, previous)["sequence"] > position["sequence"]:
                    return {"cursor": previous}
                account.store.database.execute(
                    "INSERT INTO account_metadata(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    (self.ack_key, json.dumps({"cursor": cursor}, separators=(",", ":"))))
                return {"cursor": cursor}
        return await asyncio.to_thread(write)
