"""Persist native approval handles and resolve only the selected host wait."""
import asyncio
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import logging
import math
from weakref import WeakValueDictionary

from oai_core.gateway.core import (APPROVAL_CHOICES, APPROVAL_DEFAULT_TIMEOUT_SECONDS,
    APPROVAL_EVENT_RESOLVED, GatewayCore, _epoch_millis, _public_approval)
from oai_core.sessions import AdapterError

logger=logging.getLogger(__name__)


def host_approval_available() -> bool:
    try:
        from tools.approval import resolve_gateway_approval
        return callable(resolve_gateway_approval)
    except ImportError:
        return False

def pending_host_request_id(session_key: str, command: str = "") -> str | None:
    """从宿主待审批队列恢复缺失的 ``request_id``。

    新版 Hermes 的 metadata 可能不再携带 ``request_id``。只有命令唯一匹配，
    或整个队列只有一条时才能安全恢复；出现多个候选就必须降级为文本审批，
    绝不能按“最早一条”猜，否则用户可能批准另一条命令。
    """
    if not session_key:
        return None
    try:
        from tools.approval import list_gateway_approvals
    except Exception:
        return None
    try:
        entries = list_gateway_approvals(session_key) or []
    except Exception as exc:  # pragma: no cover - host runtime detail
        logger.warning("[open_android] Host approval lookup failed: %s", exc)
        return None
    rows = [row for row in entries if isinstance(row, dict) and row.get("request_id")]
    if not rows:
        return None
    matches = [
        row for row in rows
        if command and str(row.get("command") or "") == command
    ]
    if len(matches) == 1:
        return str(matches[0]["request_id"])
    if len(rows) == 1:
        return str(rows[0]["request_id"])
    logger.warning(
        "[open_android] Host approval request id is ambiguous (%d pending, %d command matches)",
        len(rows),
        len(matches),
    )
    return None


def resolve_host_approval(session_key: str, choice: str, request_id: str | None = None) -> int | None:
    """Unblock the Agent thread waiting on one approval.

    Returns the number of host waits this call released, or `None` when this
    process cannot reach the host approval runtime at all. `None` is a fact the
    caller must surface: it is never the same as "resolved", and a Gateway that
    cannot reach the runtime must not advertise approval cards.
    """
    try:
        from tools.approval import resolve_gateway_approval
    except Exception:
        return None
    try:
        return int(resolve_gateway_approval(session_key, choice, request_id=request_id))
    except Exception as exc:  # pragma: no cover - host runtime detail
        logger.warning("[open_android] Host approval resolution failed: %s", exc)
        return None


class NativeApprovalMixin:
    """Bridge native prompts to persisted cards without adding a device Core."""

    @property
    def native_approvals_available(self):
        return host_approval_available()

    @staticmethod
    def _approval_result(approval_id):
        from gateway.platforms.base import SendResult
        return SendResult(success=True, message_id=approval_id, retryable=False)

    @staticmethod
    def _unsupported_approval_result():
        return NativeApprovalMixin._approval_result("unsupported_approval")

    def _native_approval_lock(self, account_id, approval_id):
        locks = self.__dict__.get("_native_approval_locks")
        if locks is None:
            locks = self._native_approval_locks = WeakValueDictionary()
        return locks.setdefault((account_id, approval_id), asyncio.Lock())

    async def _send_exec_approval_prompt(self, prompt):
        metadata = getattr(prompt, "metadata", None) or {}
        conversation_id = str(getattr(prompt, "chat_id", "") or "")
        account_id = metadata.get("account_id") or self._conv_to_account.get(conversation_id)
        session_key = str(getattr(prompt, "session_key", "") or "")
        host_request_id = metadata.get("request_id")
        if not host_request_id:
            host_request_id = pending_host_request_id(session_key, str(getattr(prompt, "command", "") or ""))
        options = []
        for label, choice, style in getattr(prompt, "actions", ()):
            if choice in APPROVAL_CHOICES and choice not in {row["choice"] for row in options}:
                options.append({"choice": choice, **({"label": str(label)} if label else {}),
                                **({"style": style} if style in {"primary", "secondary", "danger", "neutral"} else {})})
        if not account_id or not session_key or not host_request_id or not options or not self.native_approvals_available:
            return await self._fallback_to_text_prompt(account_id, conversation_id, session_key, host_request_id)
        core = self.services.core
        with closing(core.open_gateway_account(account_id)) as account:
            binding = account.agent_sessions.lookup(conversation_id)
            generation = account.conversations.workflow.current(conversation_id)
            if not binding or binding.get("sessionKey") != session_key or generation is None:
                return await self._fallback_to_text_prompt(account_id, conversation_id, session_key, host_request_id)
            digest = hashlib.sha256((session_key + "\0" + str(host_request_id)).encode()).hexdigest()
            approval_id = "apr_" + digest[:40]
            prior = account.approvals.lookup(approval_id)
            if prior is not None:
                return self._approval_result(approval_id)
            timeout = metadata.get("timeout_seconds")
            if timeout is None:
                try:
                    from gateway.platforms.base_exec_approval import approval_timeout_seconds
                    timeout = approval_timeout_seconds()
                except ImportError:
                    timeout = APPROVAL_DEFAULT_TIMEOUT_SECONDS
            if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout < 1:
                timeout = APPROVAL_DEFAULT_TIMEOUT_SECONDS
            severity = metadata.get("severity")
            if severity not in {"info", "elevated", "critical"}:
                severity = "critical" if getattr(prompt, "smart_denied", False) else "elevated"
            with account.store.transaction():
                account.conversations.workflow._put("approval-generation:" + approval_id, generation["generationId"])
                approval = GatewayCore.request_command_approval(core, account, approval_id, conversation_id,
                    str(getattr(prompt, "command", "") or ""), str(getattr(prompt, "description", "") or ""),
                    options, int(timeout), session_key=session_key, host_request_id=str(host_request_id), severity=severity)
        task = asyncio.create_task(self._expire_native_approval(account_id, approval_id, approval["expiresAt"]))
        self.__dict__.setdefault("_native_approval_timers", {})[(account_id, approval_id)] = task
        self._track_background_task(task, "native-approval-timeout")
        return self._approval_result(approval_id)

    async def _fallback_to_text_prompt(self, account_id, conversation_id, session_key, request_id):
        """渲染不出原生卡片时，交回宿主的纯文本审批兜底。

        重构前的行为就是这样：Hermes 发一条带命令与 `/approve` 说明的普通消息，用户
        在会话里手动回复。绝不能在这里代替用户拒绝——那会把"没渲染出来"变成"用户拒绝"。
        返回 success=False 正是宿主的文本兜底开关（run_turn_runner 的 send 失败分支）。
        """
        logger.warning("[open_android] Native approval card unavailable (account=%s conversation=%s session=%s request=%s); falling back to host text prompt",
            bool(account_id), bool(conversation_id), bool(session_key), bool(request_id))
        return self._unrenderable_approval_result()

    @staticmethod
    def _unrenderable_approval_result():
        from gateway.platforms.base import SendResult
        return SendResult(success=False, error="NATIVE_APPROVAL_UNAVAILABLE", retryable=False)

    def _settle_native_approval(self, account, approval, decision, *, device_id=None, decided_at=None):
        with account.store.transaction():
            settled = account.approvals.settle(approval["approvalId"], decision, device_id=device_id, now=decided_at)
            terminal = account.approvals.lookup(approval["approvalId"])
            if settled:
                account.events.append(APPROVAL_EVENT_RESOLVED, approval["approvalId"], _public_approval(terminal), decided_at)
        timer = self.__dict__.get("_native_approval_timers", {}).pop((account.account_id, approval["approvalId"]), None)
        if timer is not None and timer is not asyncio.current_task():
            timer.cancel()
        return terminal

    async def _expire_native_approval(self, account_id, approval_id, expires_at):
        await asyncio.sleep(max(0, (expires_at - _epoch_millis(datetime.now(timezone.utc))) / 1000))
        async with self._native_approval_lock(account_id, approval_id):
            with closing(self.services.core.open_gateway_account(account_id)) as account:
                approval = account.approvals.lookup(approval_id)
                if approval is None or approval.get("decision") is not None:
                    return
                await asyncio.to_thread(resolve_host_approval, approval["sessionKey"], "deny", request_id=approval["hostRequestId"])
                deadline = datetime.fromtimestamp(approval["expiresAt"] / 1000, timezone.utc)
                self._settle_native_approval(account, approval, "timeout", decided_at=deadline)

    async def decide_native_approval(self, account_id, conversation_id, approval_id, decision, device_id):
        if not device_id:
            raise AdapterError("DEVICE_CONTEXT_REQUIRED", 403)
        async with self._native_approval_lock(account_id, approval_id):
            with closing(self.services.core.open_gateway_account(account_id)) as account:
                approval = account.approvals.lookup(approval_id)
                if approval is None or approval["conversationId"] != conversation_id:
                    raise AdapterError("APPROVAL_NOT_FOUND", 404)
                prior = approval.get("decision")
                if prior is not None:
                    if prior == decision:
                        return {"approval": _public_approval(approval)}
                    raise AdapterError("APPROVAL_ALREADY_RESOLVED", 409, details=_public_approval(approval))
                if decision not in {row["choice"] for row in approval["options"]}:
                    raise AdapterError("APPROVAL_DECISION_INVALID", 400)
                if approval["expiresAt"] <= _epoch_millis(datetime.now(timezone.utc)):
                    await asyncio.to_thread(resolve_host_approval, approval["sessionKey"], "deny", request_id=approval["hostRequestId"])
                    terminal = self._settle_native_approval(account, approval, "timeout",
                        decided_at=datetime.fromtimestamp(approval["expiresAt"] / 1000, timezone.utc))
                    raise AdapterError("APPROVAL_EXPIRED", 409, details=_public_approval(terminal))
                count = await asyncio.to_thread(resolve_host_approval, approval["sessionKey"], decision, request_id=approval["hostRequestId"])
                if count is None:
                    raise AdapterError("HOST_INCOMPATIBLE", 503)
                terminal = self._settle_native_approval(account, approval, decision if count > 0 else "withdrawn", device_id=device_id)
                if count <= 0:
                    raise AdapterError("APPROVAL_EXPIRED", 409, details=_public_approval(terminal))
                return {"approval": _public_approval(terminal)}

    async def withdraw_generation_approvals(self, account_id, conversation_id, generation_id):
        with closing(self.services.core.open_gateway_account(account_id)) as account:
            ids = [row[0] for row in account.store.database.execute(
                "SELECT approval_id FROM approvals WHERE conversation_id=? AND decision IS NULL", (conversation_id,))]
        for approval_id in ids:
            async with self._native_approval_lock(account_id, approval_id):
                with closing(self.services.core.open_gateway_account(account_id)) as account:
                    approval = account.approvals.lookup(approval_id)
                    if approval.get("decision") is not None or account.conversations.workflow._get("approval-generation:" + approval_id) != generation_id:
                        continue
                    await asyncio.to_thread(resolve_host_approval, approval["sessionKey"], "deny", request_id=approval["hostRequestId"])
                    self._settle_native_approval(account, approval, "withdrawn")
