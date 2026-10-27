"""Gateway core message dispatch status machine and SSE event contracts."""
from datetime import datetime, timezone

import pytest

from oai_core.gateway.core import GatewayError, create_gateway_core
from .support import make_secret_store

NOW = datetime(2026, 10, 2, tzinfo=timezone.utc)


def _setup_conversation_and_message(account, client_message_id="client_msg_1", text="hello"):
    """创建测试用的 conversation 与消息，返回 (conversation, message)。"""
    conversation = account.conversations.create("client_conv_1", None, "cor_conv", NOW)
    message = account.conversations.accept_message(
        conversation_id=conversation["conversationId"],
        client_message_id=client_message_id,
        text=text,
        attachment_ids=[],
        device_id="dev_1",
        request_id="req_msg_1",
        correlation_id="cor_msg_1",
        now=NOW,
    )
    return conversation, message


def test_accept_message_dispatches_queued_status_and_emits_event(tmp_path):
    """accept_message 之后状态为 queued，且事件流中有一条 status=queued、revision=0。"""
    core = create_gateway_core(storage_root=tmp_path, secret_store=make_secret_store())
    account = core.open_gateway_account("alice")
    try:
        conversation, message = _setup_conversation_and_message(account, "client_msg_queued")

        # 1. 查询 dispatch 状态为 queued
        dispatch = account.conversations.dispatch_message("client_msg_queued")
        assert dispatch is not None
        assert dispatch["status"] == "queued"
        assert dispatch["revision"] == 0
        assert dispatch["messageId"] == message["messageId"]

        # 2. 事件流包含一条 conversation.message.status (status=queued, revision=0)
        events = account.events.read_after(None, NOW)
        status_events = [e for e in events if e["eventType"] == "conversation.message.status"]
        assert len(status_events) == 1
        payload = status_events[0]["payload"]
        assert payload["messageId"] == message["messageId"]
        assert payload["clientMessageId"] == "client_msg_queued"
        assert payload["status"] == "queued"
        assert payload["revision"] == 0
        assert payload["errorCode"] is None
    finally:
        account.close()


def test_claim_and_update_dispatch_status_to_delivered(tmp_path):
    """claim_dispatch 拿到 claimToken 后 update_dispatch_status(..., 'delivered') 成功，事件流追加 revision=1。"""
    core = create_gateway_core(storage_root=tmp_path, secret_store=make_secret_store())
    account = core.open_gateway_account("alice")
    try:
        conversation, message = _setup_conversation_and_message(account, "client_msg_claim")
        message_id = message["messageId"]

        # 认领并获取 claimToken
        claimed = account.conversations.claim_dispatch("client_msg_claim")
        assert claimed is not None
        claim_token = claimed["claimToken"]
        assert bool(claim_token)

        # 带 claim_token 上报 delivered
        result = account.conversations.update_dispatch_status(
            message_id, "delivered", None, "corr_delivered", NOW,
            claim_token=claim_token,
        )
        assert result["status"] == "delivered"
        assert result["revision"] == 1
        assert result["errorCode"] is None

        # 再次查询状态为 delivered
        current = account.conversations.dispatch_message("client_msg_claim")
        assert current["status"] == "delivered"
        assert current["revision"] == 1

        # 事件流新增一条 status=delivered、revision=1
        events = account.events.read_after(None, NOW)
        status_events = [e for e in events if e["eventType"] == "conversation.message.status"]
        assert len(status_events) == 2
        delivered_event = status_events[1]["payload"]
        assert delivered_event["messageId"] == message_id
        assert delivered_event["status"] == "delivered"
        assert delivered_event["revision"] == 1
        assert delivered_event["errorCode"] is None
    finally:
        account.close()


def test_queued_to_delivered_without_claim_token_fails(tmp_path):
    """不带 claim_token 的 queued -> delivered 必须抛 GatewayError('INVALID_STATE_TRANSITION')。"""
    core = create_gateway_core(storage_root=tmp_path, secret_store=make_secret_store())
    account = core.open_gateway_account("alice")
    try:
        conversation, message = _setup_conversation_and_message(account, "client_msg_no_token")
        message_id = message["messageId"]

        # 即使被 claim 过，调用时不传 claim_token 或 token 不匹配也必须拒绝
        account.conversations.claim_dispatch("client_msg_no_token")

        with pytest.raises(GatewayError) as exc_info:
            account.conversations.update_dispatch_status(
                message_id, "delivered", None, "corr_fail", NOW,
                claim_token=None,
            )
        assert exc_info.value.code == "INVALID_STATE_TRANSITION"

        # 未被 claim 时直接传 claim_token 同样校验不匹配抛出 INVALID_STATE_TRANSITION
        _setup_conversation_and_message(account, "client_msg_unclaimed")
        unclaimed_msg = account.conversations.dispatch_message("client_msg_unclaimed")
        with pytest.raises(GatewayError) as exc_info:
            account.conversations.update_dispatch_status(
                unclaimed_msg["messageId"], "delivered", None, "corr_fail2", NOW,
                claim_token="invalid_token",
            )
        assert exc_info.value.code == "INVALID_STATE_TRANSITION"
    finally:
        account.close()


def test_delivered_to_completed_and_completed_to_delivered_fails(tmp_path):
    """delivered -> completed 成功（revision=2、errorCode 为 None），且 completed -> delivered 抛 INVALID_STATE_TRANSITION。"""
    core = create_gateway_core(storage_root=tmp_path, secret_store=make_secret_store())
    account = core.open_gateway_account("alice")
    try:
        conversation, message = _setup_conversation_and_message(account, "client_msg_complete")
        message_id = message["messageId"]

        claimed = account.conversations.claim_dispatch("client_msg_complete")
        account.conversations.update_dispatch_status(
            message_id, "delivered", None, "corr_deliv", NOW,
            claim_token=claimed["claimToken"],
        )

        # delivered -> completed
        comp_result = account.conversations.update_dispatch_status(
            message_id, "completed", None, "corr_comp", NOW,
        )
        assert comp_result["status"] == "completed"
        assert comp_result["revision"] == 2
        assert comp_result["errorCode"] is None

        # 终态 completed -> delivered 不允许转换
        with pytest.raises(GatewayError) as exc_info:
            account.conversations.update_dispatch_status(
                message_id, "delivered", None, "corr_invalid", NOW,
            )
        assert exc_info.value.code == "INVALID_STATE_TRANSITION"
    finally:
        account.close()


def test_update_dispatch_status_is_idempotent_when_already_in_target_status(tmp_path):
    """update_dispatch_status(..., 'delivered') 在状态已是 delivered 时幂等：不抛异常、不新增事件、revision 不变。"""
    core = create_gateway_core(storage_root=tmp_path, secret_store=make_secret_store())
    account = core.open_gateway_account("alice")
    try:
        conversation, message = _setup_conversation_and_message(account, "client_msg_idempotent")
        message_id = message["messageId"]

        claimed = account.conversations.claim_dispatch("client_msg_idempotent")
        res1 = account.conversations.update_dispatch_status(
            message_id, "delivered", None, "corr_deliv", NOW,
            claim_token=claimed["claimToken"],
        )
        assert res1["status"] == "delivered"
        assert res1["revision"] == 1

        events_before = account.events.read_after(None, NOW)
        count_before = len(events_before)

        # 再次调用 delivered
        res2 = account.conversations.update_dispatch_status(
            message_id, "delivered", None, "corr_deliv_again", NOW,
            claim_token=claimed["claimToken"],
        )
        assert res2["status"] == "delivered"
        assert res2["revision"] == 1

        # 事件未新增
        events_after = account.events.read_after(None, NOW)
        assert len(events_after) == count_before
    finally:
        account.close()
