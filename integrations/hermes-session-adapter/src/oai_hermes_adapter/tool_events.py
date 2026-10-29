"""Native tool observations, scoped to an explicitly accepted dispatch.

The host's public pre/post hooks are observers here: they return no directive
and never modify tool arguments, results, approval policy, or execution.
"""
from __future__ import annotations

from contextlib import closing
import hashlib
import json
import logging
from typing import Any, Mapping

from .context import trusted_turn

NOTICE_CODE = "oai.native.execution.updated"
_NAMESPACE = "oai_native_execution_v1:"
logger = logging.getLogger(__name__)


def execution_key(storage_owner: str, account_id: str, conversation_id: str, generation_id: str) -> str:
    identity = json.dumps([storage_owner, account_id, conversation_id, generation_id], separators=(",", ":"))
    return _NAMESPACE + hashlib.sha256(identity.encode()).hexdigest()


def public_execution(snapshot: Mapping[str, Any]) -> dict[str, Any]:
    names = {"stepId", "eventId", "toolName", "arguments", "output", "status", "revision"}
    return {"executionRevision": snapshot.get("revision", 0), "executionSteps": [
        {key: value for key, value in step.items() if key in names}
        for step in snapshot.get("steps", [])]}


def read_execution(database: Any, storage_owner: str, account_id: str,
                   conversation_id: str, generation_id: str) -> dict[str, Any] | None:
    value = database.get_meta(execution_key(storage_owner, account_id, conversation_id, generation_id))
    if value is None:
        return None
    result = json.loads(value)
    if (result.get("storageOwner") != storage_owner or result.get("accountId") != account_id
            or result.get("conversationId") != conversation_id or result.get("generationId") != generation_id):
        return None
    return result


def merge_history_execution(database: Any, storage_owner: str, account_id: str, conversation_id: str,
                            projected: dict[int, dict[str, Any]]) -> None:
    """Use one durable revision domain for each native step in live/history views.

    Native transcript rows can prove a result even when a post hook was missed.
    An old call row cannot reopen a result that a hook already confirmed.
    """
    groups: dict[str, list[tuple[int, int, dict[str, Any]]]] = {}
    for source_id, entry in projected.items():
        groups.setdefault(entry["generationId"], []).extend(
            (source_id, index, step) for index, step in enumerate(entry.get("executionSteps", [])))
    for generation_id, native_steps in groups.items():
        key = execution_key(storage_owner, account_id, conversation_id, generation_id)
        saved = read_execution(database, storage_owner, account_id, conversation_id, generation_id)
        snapshot = saved or {"storageOwner": storage_owner, "accountId": account_id,
            "conversationId": conversation_id, "generationId": generation_id, "revision": 0, "steps": []}
        known = {step["stepId"]: step for step in snapshot["steps"]}
        changed = False
        for source_id, source_index, native in native_steps:
            old = known.get(native["stepId"])
            if old is None or (old["status"] in {"unknown", "running"}
                               and native["status"] in {"completed", "failed"}):
                snapshot["revision"] += 1
                known[native["stepId"]] = {**native, "revision": snapshot["revision"],
                    "sourceMessageId": source_id, "sourceIndex": source_index}
                changed = True
            elif old.get("sourceMessageId") is None:
                old.update(sourceMessageId=source_id, sourceIndex=source_index)
                changed = True
            native.update({name: value for name, value in known[native["stepId"]].items()
                           if name in {"stepId", "eventId", "toolName", "arguments", "output", "status", "revision"}})
        if changed:
            snapshot["steps"] = sorted(known.values(), key=lambda item: (item["sourceMessageId"], item["sourceIndex"]))
            database.set_meta(key, json.dumps(snapshot, separators=(",", ":")))


class NativeToolObserver:
    def __init__(self, core: Any, history: Any):
        self.core, self.history = core, history
        self._lock = history._migration_lock

    def record(self, context: Mapping[str, Any], *, tool_name: str, args: Any, session_id: str,
               tool_call_id: str, turn_id: str, finished: bool = False, result: Any = None,
               status: str = "") -> None:
        if (context.get("core") is not self.core or context.get("executionObserver") is not self
                or not all(isinstance(value, str) and value for value in
                           (tool_name, session_id, tool_call_id, turn_id))):
            return
        account_id, conversation_id = context.get("accountId"), context.get("conversationId")
        client_id, generation_id = context.get("clientMessageId"), context.get("generationId")
        if not all(isinstance(value, str) and value for value in
                   (account_id, conversation_id, client_id, generation_id)):
            return
        with self._lock, closing(self.core.open_gateway_account(account_id)) as account, account.store.transaction():
            generation = account.conversations.workflow._get("generation:" + generation_id)
            record = account.conversations.dispatch_message(client_id)
            if (not generation or not record or generation["conversationId"] != conversation_id
                    or record["conversationId"] != conversation_id or record["messageId"] not in generation["messageIds"]
                    or generation["state"] not in {"running", "unknown"}):
                return
            binding = account.agent_sessions.lookup(conversation_id)
            if not binding or binding.get("agentSessionId") != context.get("sessionId"):
                return
            database = self.history._database(binding["agentSessionId"], binding.get("sessionKey"))
            chain = self.history._session_chain(database, binding["agentSessionId"])
            if session_id not in chain:
                return
            # Hermes commits the assistant tool-call row before tool side effects.
            # Require that exact call inside the exact accepted native user turn.
            rows = self.history._history_rows(database, binding["agentSessionId"])
            anchors = [row for row in rows if row.get("role") == "user"
                       and not row.get("_compressed_summary") and row.get("platform_message_id") == client_id]
            if len(anchors) != 1:
                return
            anchor = anchors[0]["id"]
            end = min((row["id"] for row in rows if row.get("role") == "user" and row["id"] > anchor),
                      default=float("inf"))
            sources = [(row, index) for row in rows if row.get("role") == "assistant"
                and row.get("session_id") == session_id and anchor < row["id"] < end
                and isinstance(row.get("tool_calls"), list)
                for index, call in enumerate(row["tool_calls"])
                if isinstance(call, Mapping) and call.get("id") == tool_call_id
                and isinstance(call.get("function"), Mapping) and call["function"].get("name") == tool_name]
            if len(sources) != 1:
                return
            source, index = sources[0]
            key = execution_key(self.history.storage_owner, account_id, conversation_id, generation_id)
            snapshot = read_execution(database, self.history.storage_owner, account_id, conversation_id, generation_id) or {
                "storageOwner": self.history.storage_owner, "accountId": account_id,
                "conversationId": conversation_id, "generationId": generation_id, "revision": 0, "steps": []}
            known = {step["stepId"]: step for step in snapshot["steps"]}
            old = known.get(tool_call_id)
            if old and old.get("nativeTurnId") not in {None, turn_id}:
                return
            if finished and (old is None or old.get("nativeTurnId") != turn_id):
                return
            if not finished and old and old["status"] in {"completed", "failed"}:
                return
            arguments = json.dumps(args if isinstance(args, Mapping) else {}, ensure_ascii=False, separators=(",", ":"))
            output = (result if isinstance(result, str) else json.dumps(result, ensure_ascii=False, separators=(",", ":"))) if finished else None
            step = {"stepId": tool_call_id, "toolName": tool_name, "arguments": arguments,
                "output": output, "status": ({"ok": "completed", "error": "failed", "blocked": "failed"}.get(status, "unknown")
                                               if finished else "running"),
                "nativeTurnId": turn_id, "sourceMessageId": source["id"], "sourceIndex": index}
            if old and all(old.get(name) == value for name, value in step.items()):
                return
            snapshot["revision"] += 1
            event = account.events.append("gateway.notice", record["messageId"], {"noticeCode": NOTICE_CODE})
            known[tool_call_id] = {**step, "revision": snapshot["revision"], "eventId": event["eventId"]}
            snapshot["steps"] = sorted(known.values(), key=lambda item: (item.get("sourceMessageId", 0), item.get("sourceIndex", 0)))
            database.set_meta(key, json.dumps(snapshot, ensure_ascii=False, separators=(",", ":")))

    def snapshot(self, account_id: str, conversation_id: str, generation_id: str) -> dict[str, Any] | None:
        with self._lock, closing(self.core.open_gateway_account(account_id)) as account:
            generation = account.conversations.workflow._get("generation:" + generation_id)
            binding = account.agent_sessions.lookup(conversation_id)
            if not generation or generation["conversationId"] != conversation_id or not binding:
                return None
            database = self.history._database(binding["agentSessionId"], binding.get("sessionKey"))
            saved = read_execution(database, self.history.storage_owner, account_id, conversation_id, generation_id)
            return public_execution(saved) if saved else None

    def close_generation(self, account_id: str, conversation_id: str, generation_id: str, message_id: str) -> None:
        with self._lock, closing(self.core.open_gateway_account(account_id)) as account, account.store.transaction():
            binding = account.agent_sessions.lookup(conversation_id)
            if not binding:
                return
            database = self.history._database(binding["agentSessionId"], binding.get("sessionKey"))
            saved = read_execution(database, self.history.storage_owner, account_id, conversation_id, generation_id)
            if not saved or not any(step["status"] == "running" for step in saved["steps"]):
                return
            event = account.events.append("gateway.notice", message_id, {"noticeCode": NOTICE_CODE})
            for step in saved["steps"]:
                if step["status"] == "running":
                    saved["revision"] += 1
                    step.update(status="unknown", revision=saved["revision"], eventId=event["eventId"])
            database.set_meta(execution_key(self.history.storage_owner, account_id, conversation_id, generation_id),
                              json.dumps(saved, ensure_ascii=False, separators=(",", ":")))


def _observe(finished: bool, tool_name: str = "", args: Any = None, session_id: str = "",
             tool_call_id: str = "", turn_id: str = "", result: Any = None, status: str = "", **_kwargs: Any) -> None:
    context = trusted_turn.get()
    observer = context.get("executionObserver") if isinstance(context, Mapping) else None
    if not isinstance(observer, NativeToolObserver):
        return
    try:
        observer.record(context, tool_name=tool_name, args=args, session_id=session_id,
            tool_call_id=tool_call_id, turn_id=turn_id, finished=finished, result=result, status=status)
    except Exception as failure:
        # Observer failures do not become policy directives or expose tool data.
        logger.warning("Native tool observation unavailable errorType=%s", type(failure).__name__)


def pre_tool_call(**kwargs: Any) -> None:
    _observe(False, **kwargs)


def post_tool_call(**kwargs: Any) -> None:
    _observe(True, **kwargs)
