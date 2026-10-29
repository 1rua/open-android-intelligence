"""Native replay uses the existing account EventStore and durable observer ACKs."""
import asyncio
from contextlib import closing
from urllib.parse import urlencode

import pytest

from oai_core.auth import Principal
from oai_core.sessions import AdapterError
from oai_hermes_adapter.sessions import HermesSessionAdapter
from test_hermes import core
from test_interaction_pipeline import pipeline, request, AGENT, next_event

CONSUMER = "a" * 64
ALICE = Principal("alice", device_id="phone", pairing_generation=1)


async def session(adapter):
    return (await adapter.list_sessions(ALICE))[0]["id"]


def append(core, conversation, identity, owner="alice"):
    with closing(core.open_gateway_account(owner)) as account:
        return account.events.append("conversation.message.completed", identity, {
            "conversationId": conversation, "messageId": identity, "sender": "assistant",
            "text": identity, "parts": [{"type": "text", "text": identity}],
            "timestamp": 1000, "revision": 1,
        })


@pytest.mark.asyncio
async def test_pages_replay_every_committed_id_and_ack_survives_adapter_recreation(core):
    adapter = HermesSessionAdapter(core)
    conversation = await session(adapter)
    initial = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, snapshot=True)
    assert initial["events"] == [] and initial["approvals"] == []
    with closing(core.open_gateway_account("alice")) as account:
        core.request_command_approval(account, "approval-burst", conversation, "synthetic command", "burst test",
            [{"choice": "deny", "label": "拒绝"}], 60)
        requested = account.events.read_after(None)[-1]
        account.approvals.settle("approval-burst", "deny")
        settled = account.approvals.lookup("approval-burst")
        resolved = account.events.append("conversation.approval.resolved", "approval-burst",
            {key: settled[key] for key in ("approvalId", "conversationId", "decision", "decidedAt")})
    expected = [requested["eventId"], resolved["eventId"]]
    expected.extend(append(core, conversation, f"reply-{index}")["eventId"] for index in range(140))
    first = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, cursor=initial["cursor"], limit=17)
    replay = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, cursor=initial["cursor"], limit=17)
    assert [event["eventId"] for event in first["events"]] == [event["eventId"] for event in replay["events"]]
    ids, page = [], first
    while True:
        ids.extend(event["eventId"] for event in page["events"])
        await adapter.ack_events(ALICE, conversation, consumer_id=CONSUMER, cursor=page["cursor"])
        if not page["hasMore"]:
            break
        page = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, limit=17)
    assert ids == expected
    await adapter.ack_events(ALICE, conversation, consumer_id=CONSUMER, cursor=first["cursor"])
    recreated = HermesSessionAdapter(core)
    assert (await recreated.event_page(ALICE, conversation, consumer_id=CONSUMER))["events"] == []


@pytest.mark.asyncio
async def test_cursor_cannot_cross_observer_account_or_session(core):
    adapter = HermesSessionAdapter(core)
    conversation = await session(adapter)
    page = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, snapshot=True)
    for consumer in ("b" * 64,):
        with pytest.raises(AdapterError, match="INVALID_EVENT_CURSOR"):
            await adapter.event_page(ALICE, conversation, consumer_id=consumer, cursor=page["cursor"])
    with pytest.raises(AdapterError):
        await adapter.event_page(Principal("bob", device_id="phone"), conversation, consumer_id=CONSUMER, cursor=page["cursor"])
    with closing(core.open_gateway_account("alice")) as account:
        other = account.conversations.create("other-native", "other", "create-other")["conversationId"]
        account.agent_sessions.record(other, "conversation-create")
        account.agent_sessions.attach(other, "other-host", "other-key")
    with pytest.raises(AdapterError, match="INVALID_EVENT_CURSOR"):
        await adapter.event_page(ALICE, other, consumer_id=CONSUMER, cursor=page["cursor"])


@pytest.mark.asyncio
async def test_expired_cursor_requires_snapshot_and_does_not_discard_new_events(core):
    adapter = HermesSessionAdapter(core)
    conversation = await session(adapter)
    append(core, conversation, "before-expiry")
    page = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, snapshot=True)
    with closing(core.open_gateway_account("alice")) as account:
        account.store.database.execute("UPDATE events SET expires_at='1970-01-01T00:00:00.000Z'")
    append(core, conversation, "after-expiry")
    with pytest.raises(AdapterError, match="CURSOR_EXPIRED"):
        await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, cursor=page["cursor"])
    snapshot = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, snapshot=True)
    event = append(core, conversation, "during-history-refresh")
    page = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, cursor=snapshot["cursor"])
    assert [item["eventId"] for item in page["events"]] == [event["eventId"]]


@pytest.mark.asyncio
async def test_long_wait_is_woken_by_commit_and_unregisters_on_observer_cancel(core, monkeypatch):
    adapter = HermesSessionAdapter(core)
    conversation = await session(adapter)
    initial = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, snapshot=True)
    subscribed = asyncio.Event()
    original = core.register_event_sink
    def register(sink):
        original(sink)
        subscribed.set()
    monkeypatch.setattr(core, "register_event_sink", register)
    pending = asyncio.create_task(adapter.event_page(ALICE, conversation, consumer_id=CONSUMER,
        cursor=initial["cursor"], wait_ms=25000))
    await asyncio.wait_for(subscribed.wait(), 1)
    event = append(core, conversation, "wake")
    page = await asyncio.wait_for(pending, 2)
    assert page["events"][0]["eventId"] == event["eventId"]
    subscribed.clear()
    pending = asyncio.create_task(adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, cursor=page["cursor"], wait_ms=25000))
    await asyncio.wait_for(subscribed.wait(), 1)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    assert core._event_sinks == []


@pytest.mark.asyncio
async def test_signed_core_bridge_replays_offline_turn_and_approval_without_resubmission(pipeline):
    p = pipeline
    path = AGENT + "/sessions/" + p.conversation
    response = await request(p.client, p.alice, "GET", path + "/events?snapshot=true")
    assert response.status == 200, await response.text()
    initial = await response.json()
    stream = await request(p.client, p.alice, "POST", path + "/messages", {"text": "approval", "clientMessageId": "replay-client"})
    generation = await next_event(stream)
    approval = None
    while approval is None:
        frame = await next_event(stream)
        if frame["type"] == "approval_requested":
            approval = frame["approval"]
    stream.close()
    response = await request(p.client, p.alice, "GET", path + "/events?" + urlencode({"cursor": initial["cursor"]}))
    assert response.status == 200, await response.text()
    page = await response.json()
    assert any(event["eventType"] == "conversation.approval.requested" and event["payload"]["approvalId"] == approval["approvalId"] for event in page["events"])
    snapshot = await request(p.client, p.alice, "GET", path + "/events?snapshot=true")
    approvals = (await snapshot.json())["approvals"]
    assert any(item["approval"]["approvalId"] == approval["approvalId"] for item in approvals)
    assert "hostRequestId" not in str(approvals) and "sessionKey" not in str(approvals)
    response = await request(p.client, p.alice, "POST", path + "/events/ack", {"cursor": page["cursor"]})
    assert response.status == 200, await response.text()
    response = await request(p.client, p.alice, "GET", path + "/events")
    assert (await response.json())["events"] == []
    assert len(p.runtime.turns) == 1 and p.runtime.interrupted == []
    assert not p.runtime.turns[("native-alice", p.conversation, generation["generationId"])].done()


@pytest.mark.asyncio
async def test_core_restart_retains_events_and_ack_without_a_second_message_database(core, tmp_path):
    from oai_core.server import create_core
    adapter = HermesSessionAdapter(core)
    conversation = await session(adapter)
    initial = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, snapshot=True)
    first = append(core, conversation, "before-core-restart")
    page = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, cursor=initial["cursor"])
    await adapter.ack_events(ALICE, conversation, consumer_id=CONSUMER, cursor=page["cursor"])
    second = append(core, conversation, "after-ack-before-restart")
    from test_hermes import CONTRACT
    reopened = create_core({"storageRoot": str(tmp_path / "accounts"),
        "masterKeyFile": str(tmp_path / "master"), "contractRoot": str(CONTRACT)})
    # Every operation above closed its account connection. A fresh Core object
    # must recover both facts solely from the same on-disk store and key.
    recovered = await HermesSessionAdapter(reopened).event_page(ALICE, conversation, consumer_id=CONSUMER)
    assert [event["eventId"] for event in recovered["events"]] == [second["eventId"]]
    assert first["eventId"] not in [event["eventId"] for event in recovered["events"]]


@pytest.mark.asyncio
async def test_signed_cursor_cannot_cross_agent_alias_and_query_is_bounded(pipeline):
    from oai_core.adapters.hermes import HermesRemoteSessionAdapter
    from oai_core.server import REGISTRY_KEY
    from test_interaction_pipeline import TOKEN
    p = pipeline
    alias = HermesRemoteSessionAdapter(str(p.host.make_url("/")).rstrip("/"), TOKEN,
        {"alice": "native-alice", "bob": "native-bob"}, agent_id="hermes-alias")
    p.client.app[REGISTRY_KEY].register(alias)
    assert await alias.status()
    path = AGENT + "/sessions/" + p.conversation + "/events"
    page = await (await request(p.client, p.alice, "GET", path + "?snapshot=true")).json()
    other = path.replace("/agents/hermes/", "/agents/hermes-alias/")
    response = await request(p.client, p.alice, "GET", other + "?" + urlencode({"cursor": page["cursor"]}))
    assert response.status == 400 and (await response.json())["error"]["code"] == "INVALID_EVENT_CURSOR"
    for query in ("limit=129", "waitMs=25001", "limit=1&limit=2", "snapshot=false", "consumerId=forged"):
        response = await request(p.client, p.alice, "GET", path + "?" + query)
        assert response.status == 400, await response.text()


@pytest.mark.asyncio
async def test_revocation_during_long_replay_wait_blocks_delivery(pipeline, monkeypatch):
    p = pipeline
    path = AGENT + "/sessions/" + p.conversation + "/events"
    initial = await (await request(p.client, p.alice, "GET", path + "?snapshot=true")).json()
    subscribed = asyncio.Event()
    original = p.backend.register_event_sink
    def register(sink):
        original(sink)
        subscribed.set()
    monkeypatch.setattr(p.backend, "register_event_sink", register)
    pending = asyncio.create_task(request(p.client, p.alice, "GET", path + "?" +
        urlencode({"cursor": initial["cursor"], "waitMs": 25000})))
    await asyncio.wait_for(subscribed.wait(), 2)
    with closing(p.core.open_gateway_account("alice")) as account:
        account.sessions.revoke_session(p.alice[0]["sessionId"], "revoke-during-replay-wait")
    append(p.backend, p.conversation, "private-after-revoke", owner="native-alice")
    response = await asyncio.wait_for(pending, 2)
    assert response.status == 401
    assert "private-after-revoke" not in await response.text()


@pytest.mark.asyncio
async def test_replay_filters_foreign_conversations_and_tool_notifications(core):
    from oai_hermes_adapter.tool_events import NOTICE_CODE
    adapter = HermesSessionAdapter(core)
    conversation = await session(adapter)
    initial = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, snapshot=True)
    with closing(core.open_gateway_account("alice")) as account:
        other = account.conversations.create("foreign", "foreign", "create-foreign")["conversationId"]
        accepted = account.conversations.accept_message(conversation, "local-client", "local", [], "phone", "local", "local")
        foreign = account.conversations.accept_message(other, "foreign-client", "foreign", [], "phone", "foreign", "foreign")
        own_notice = account.events.append("gateway.notice", accepted["messageId"], {"noticeCode": NOTICE_CODE})
        foreign_notice = account.events.append("gateway.notice", foreign["messageId"], {"noticeCode": NOTICE_CODE})
    append(core, other, "foreign-reply")
    page = await adapter.event_page(ALICE, conversation, consumer_id=CONSUMER, cursor=initial["cursor"])
    ids = [event["eventId"] for event in page["events"]]
    assert own_notice["eventId"] in ids and foreign_notice["eventId"] not in ids
    assert not any(event["payload"].get("conversationId") == other for event in page["events"])


@pytest.mark.asyncio
async def test_restarting_core_http_app_recovers_offline_host_turn_without_resubmitting(pipeline, tmp_path):
    from aiohttp.test_utils import TestClient, TestServer
    from oai_core.adapters.hermes import HermesRemoteSessionAdapter
    from oai_core.server import create_app, create_core
    from oai_core.sessions import AgentRegistry
    from test_interaction_pipeline import TOKEN, CONTRACT
    p = pipeline
    path = AGENT + "/sessions/" + p.conversation
    initial = await (await request(p.client, p.alice, "GET", path + "/events?snapshot=true")).json()
    stream = await request(p.client, p.alice, "POST", path + "/messages",
        {"text": "disconnect", "clientMessageId": "core-restart-client"})
    generation = await next_event(stream)
    assert (await next_event(stream))["type"] == "delta"
    task = p.runtime.turns[("native-alice", p.conversation, generation["generationId"])]
    # Closing the entire Core app cancels its HTTP observers. The separate
    # Adapter HTTP app and accepted host task stay alive.
    stream.close()
    await p.client.close()
    assert not task.done() and p.runtime.interrupted == []
    p.runtime.observer_release.set()
    await asyncio.wait_for(p.runtime.host_finished.wait(), 2)
    settings = {"storageRoot": str(tmp_path / "core-data"), "masterKeyFile": str(tmp_path / "core-master"),
        "contractRoot": str(CONTRACT)}
    core = create_core(settings)
    remote = HermesRemoteSessionAdapter(str(p.host.make_url("/")).rstrip("/"), TOKEN,
        {"alice": "native-alice", "bob": "native-bob"})
    registry = AgentRegistry([remote])
    assert (await registry.discover())[0]["capabilities"]["eventSync"]
    async with TestClient(TestServer(create_app(settings, registry=registry, core=core))) as restarted:
        response = await request(restarted, p.alice, "GET", path + "/generation?clientMessageId=core-restart-client")
        assert response.status == 200, await response.text()
        receipt = (await response.json())["generation"]
        assert receipt["state"] == "completed" and receipt["generationId"] == generation["generationId"]
        response = await request(restarted, p.alice, "GET", path + "/events?" + urlencode({"cursor": initial["cursor"]}))
        assert response.status == 200, await response.text()
        page = await response.json()
        assert any("观察断开后完成" in event["payload"].get("text", "") for event in page["events"])
        response = await request(restarted, p.alice, "POST", path + "/events/ack", {"cursor": page["cursor"]})
        assert response.status == 200, await response.text()
    assert len(p.runtime.turns) == 1 and p.runtime.interrupted == []
    with closing(p.backend.open_gateway_account("native-alice")) as account:
        assert account.store.database.execute("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND sender='user'",
            (p.conversation,)).fetchone()[0] == 1
