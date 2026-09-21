"""One reply is one message.

The Agent host can publish the same reply more than once in a turn (a finalize
plus a final send, a retried delivery). Those publishes describe one message, so
they must land under one message id: minting a fresh id per call put the reply in
the timeline two or three times, and no reader could collapse the copies because
the ids genuinely differed.
"""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from open_android_intelligence_gateway.adapter import OpenAndroidPlatformAdapter  # noqa: E402
from open_android_intelligence_gateway.admin import (  # noqa: E402
    HostApiCompatibility,
    create_admin_service,
)
from open_android_intelligence_gateway.core import create_gateway_core  # noqa: E402
from open_android_intelligence_gateway.http import create_gateway_exposure  # noqa: E402
from open_android_intelligence_gateway.plugin import GatewayServices  # noqa: E402
from test_support import make_secret_store, trust_core  # noqa: E402

TEST_HOST_API = HostApiCompatibility(
    "1.0.0", "1.0.0", "0123456789abcdef0123456789abcdef01234567"
)
ACCOUNT_ID = "acct_message_identity"
CONVERSATIONS = "/open-android-intelligence/v2/conversations"
REPLY = "现在是晚上 21:20 啦 (｡•̀ᴗ-)✧"

_real_create_gateway_core = create_gateway_core


def create_gateway_core(storage_root=None, **options):
    options.setdefault("secret_store", make_secret_store())
    return trust_core(_real_create_gateway_core(storage_root=storage_root, **options))


class _Config:
    def __init__(self, account_id: str = ACCOUNT_ID):
        self.extra = {"host": "127.0.0.1", "port": 0, "account_id": account_id}


def _adapter(core) -> OpenAndroidPlatformAdapter:
    exposure = create_gateway_exposure(
        "host-route", core=core, host_version="1.0.0", host_api=TEST_HOST_API,
        verify_request=None,
    )
    admin = create_admin_service(core=core, host_version="1.0.0", host_api=TEST_HOST_API)
    return OpenAndroidPlatformAdapter(_Config(), GatewayServices(core, admin, exposure))


def _seed_conversation(core, client_id: str) -> str:
    account = core.open_gateway_account(ACCOUNT_ID)
    try:
        return account.conversations.create(client_id, "会话", f"cor_{client_id}")["conversationId"]
    finally:
        account.close()


def _rows(core, conversation_id: str) -> list[dict]:
    account = core.open_gateway_account(ACCOUNT_ID)
    try:
        return account.conversations.list_messages(conversation_id)["messages"]
    finally:
        account.close()


def _completed_message_ids(core) -> list[str]:
    account = core.open_gateway_account(ACCOUNT_ID)
    try:
        events = account.events.read_after(None)
    finally:
        account.close()
    return [
        event["payload"]["messageId"]
        for event in events
        if event["eventType"] == "conversation.message.completed"
    ]


def _user_message_body(text: str) -> bytes:
    return json.dumps(
        {"clientMessageId": "msg_user_1", "text": text, "attachments": []}
    ).encode("utf-8")


def test_a_reply_published_twice_lands_under_one_message_id(tmp_path):
    async def scenario():
        core = create_gateway_core(storage_root=tmp_path)
        conversation = _seed_conversation(core, "cconv_repeat")
        adapter = _adapter(core)

        await adapter.send(conversation, REPLY)
        await adapter.send(conversation, REPLY)

        rows = _rows(core, conversation)
        assert [row["messageId"] for row in rows] == [rows[0]["messageId"]]
        assert rows[0]["text"] == REPLY
        assert len({entry for entry in _completed_message_ids(core)}) == 1
    asyncio.run(scenario())


def test_a_finalize_then_a_send_of_the_same_reply_lands_under_one_id(tmp_path):
    """The mixed path: Hermes finalizes what it streamed, then sends the same text."""
    async def scenario():
        core = create_gateway_core(storage_root=tmp_path)
        conversation = _seed_conversation(core, "cconv_mixed")
        adapter = _adapter(core)

        assert await adapter.edit_message(conversation, "msg_streamed", REPLY, finalize=True) is True
        await adapter.send(conversation, REPLY)

        assert [row["messageId"] for row in _rows(core, conversation)] == ["msg_streamed"]
    asyncio.run(scenario())


def test_send_prefers_the_message_id_the_host_named(tmp_path):
    async def scenario():
        core = create_gateway_core(storage_root=tmp_path)
        conversation = _seed_conversation(core, "cconv_named")
        adapter = _adapter(core)

        result = await adapter.send(conversation, "好的", reply_to="msg_named_by_host")

        assert result.success is True
        assert result.message_id == "msg_named_by_host"
        assert [row["messageId"] for row in _rows(core, conversation)] == ["msg_named_by_host"]
    asyncio.run(scenario())


def test_the_same_reply_after_a_new_user_message_is_a_new_message(tmp_path):
    """A user turn boundary ends the identity: the next identical text is new."""
    async def scenario():
        core = create_gateway_core(storage_root=tmp_path)
        conversation = _seed_conversation(core, "cconv_turn_boundary")
        adapter = _adapter(core)

        await adapter.send(conversation, "好的")
        await adapter._notify_agent_inbound(
            f"{CONVERSATIONS}/{conversation}/messages",
            _user_message_body("再确认一次"),
            {},
            ACCOUNT_ID,
        )
        await adapter.send(conversation, "好的")

        rows = _rows(core, conversation)
        assistant_ids = [row["messageId"] for row in rows if row["sender"] == "assistant"]
        assert len(assistant_ids) == 2
        assert len(set(assistant_ids)) == 2
    asyncio.run(scenario())


def test_a_different_reply_inside_the_same_turn_is_a_new_message(tmp_path):
    """Only an identical text is the same reply; a tool round answers differently."""
    async def scenario():
        core = create_gateway_core(storage_root=tmp_path)
        conversation = _seed_conversation(core, "cconv_two_replies")
        adapter = _adapter(core)

        await adapter.send(conversation, "先看一下终端")
        await adapter.send(conversation, "看完了，磁盘还剩 11G")

        rows = _rows(core, conversation)
        assert len(rows) == 2
        assert {row["text"] for row in rows} == {"先看一下终端", "看完了，磁盘还剩 11G"}
    asyncio.run(scenario())
