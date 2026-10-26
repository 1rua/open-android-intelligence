"""Pagination over an explicitly supplied native history reader."""
from __future__ import annotations
import hashlib
from datetime import datetime
from typing import Any
from .core import GatewayError, _jcs

def host_history_page(port: Any, conversation_id: str, client_id: str | None, cursor: str | None, limit: int) -> dict[str, Any]:
    from .core import GatewayError, _jcs
    messages = port.history_reader(port.account_id, conversation_id)
    by_id = {row["messageId"]: row for row in messages}
    # Only unadopted transport messages remain in the Gateway body queue.
    pending = port.store.database.execute("SELECT * FROM messages WHERE conversation_id=? AND text!=''", (conversation_id,)).fetchall()
    for row in pending:
        if row["message_id"] not in by_id:
            text = port.store.open_json(row["text"], f"message:{row['message_id']}:text")
            by_id[row["message_id"]] = {"messageId": row["message_id"], "clientMessageId": row["client_message_id"],
                "conversationId": conversation_id, "sender": row["sender"], "text": text,
                "parts": [{"type": "text", "text": text}], "createdAt": row["created_at"],
                "timestamp": int(datetime.fromisoformat(row["created_at"].replace("Z", "+00:00")).timestamp()*1000), "state": row["state"]}
    if client_id and not any(row.get("clientMessageId") == client_id for row in by_id.values()):
        known = port.store.database.execute("SELECT * FROM messages WHERE conversation_id=? AND client_message_id=?",(conversation_id,client_id)).fetchone()
        if known:
            by_id[known["message_id"]] = {"messageId":known["message_id"],"clientMessageId":client_id,"conversationId":conversation_id,
                "sender":known["sender"],"text":"","parts":[],"timestamp":int(datetime.fromisoformat(known["created_at"].replace("Z","+00:00")).timestamp()*1000),"state":known["state"]}
    rows = sorted(by_id.values(), key=lambda row: (row["timestamp"], row["messageId"]))
    revision = int(hashlib.sha256(_jcs(rows).encode()).hexdigest()[:13], 16) + 1
    offset = 0
    if cursor:
        saved = port.store.open_json(cursor, f"host-timeline:{conversation_id}")
        if saved.get("revision") != revision:
            raise GatewayError("CURSOR_EXPIRED", {"recoverableResources": ["conversations"]})
        offset = saved.get("offset")
        if not isinstance(offset, int) or offset < 0 or offset > len(rows):
            raise GatewayError("SCHEMA_INVALID")
    if client_id:
        rows = [row for row in rows if row.get("clientMessageId") == client_id]
    size = max(1, min(int(limit), 100))
    end = offset + size
    next_cursor = port.store.seal_json({"offset": end, "revision": revision}, f"host-timeline:{conversation_id}") if end < len(rows) else None
    return {"messages": rows[offset:end], "nextCursor": next_cursor, "snapshotRevision": revision}
