"""Real Core/Adapter process loss without a model or a production connection.

Acceptance, authentication, durable generations and eventSync use the real APIs.
Only the synthetic host's release gate and observation counters are controlled.
"""
from __future__ import annotations

import asyncio
from contextlib import AsyncExitStack
import os
from types import SimpleNamespace
from urllib.parse import urlencode

import pytest

from aiohttp import ClientSession, ClientTimeout
from test_core_transport import PREFIX, phone_login, signed

AGENT = PREFIX + "/agents/hermes"


@pytest.fixture
def process_support():
    # Skip before importing shared helpers, so an independent Core environment
    # collects all three cases and exits successfully with three skipped tests.
    pytest.importorskip("oai_hermes_adapter.backend", reason="optional Hermes session Adapter is not installed")
    pytest.importorskip("oai_hermes_adapter.bridge", reason="optional Hermes session bridge is not installed")
    from communication_process_fixture import CommunicationProcess
    from test_interaction_pipeline import next_event

    async def start(stack, role, directory, **options):
        process = await CommunicationProcess(role, directory, **options).start()
        stack.push_async_callback(process.close)
        return process

    return start, next_event


async def _request(client, identity, method, path, body=None, request_id=None):
    raw, headers = signed(*identity, method, path, body, request_id=request_id)
    return await client.request(method, path, data=raw, headers=headers)


async def _json(client, identity, method, path, body=None, request_id=None):
    async with await _request(client, identity, method, path, body, request_id) as response:
        assert response.status == 200, await response.text()
        return await response.json()


async def _login(client, core):
    # phone_login needs only the contract hash, never the in-process Core object.
    metadata = SimpleNamespace(contracts=SimpleNamespace(core_schema_hash=core.ready["schemaHash"]))
    return await phone_login(client, metadata)


async def _receipt(client, identity, session, **query):
    return (await _json(client, identity, "GET", session + "/generation?" + urlencode(query)))["generation"]


async def _replay(client, identity, session, cursor):
    events = []
    async with asyncio.timeout(8):
        # Every iteration advances a real opaque cursor; this is finite replay,
        # not polling for a task or a remote build to finish.
        for _ in range(32):
            page = await _json(client, identity, "GET", session + "/events?" + urlencode({"cursor": cursor, "limit": 2}))
            events.extend(page["events"])
            cursor = page["cursor"]
            if not page["hasMore"]:
                return events, cursor
    raise AssertionError("fixture replay exceeded its bounded event budget")


def _assert_one_input(adapter, client_id, *, finished):
    journal = adapter.observations()
    posts = [row for row in journal if row["event"] == "http.message.post"]
    starts = [row for row in journal if row["event"] == "task.started"]
    assert len(posts) == len(starts) == 1, journal
    assert posts[0]["clientMessageId"] == starts[0]["clientMessageId"] == client_id
    assert not any(row["event"] == "task.cancelled" for row in journal), journal
    assert sum(row["event"] == "task.finished" for row in journal) == int(finished), journal


@pytest.mark.asyncio
@pytest.mark.parametrize("shutdown", ["graceful", "kill"])
async def test_core_restart_preserves_live_host_turn_and_recovers_original_identity_and_events(tmp_path, shutdown, process_support):
    start, next_event = process_support
    async with AsyncExitStack() as stack:
        adapter = await start(stack, "adapter", tmp_path / "adapter")
        core = await start(stack, "core", tmp_path / "core", host_url=adapter.url)
        assert len({os.getpid(), adapter.process.pid, core.process.pid}) == 3
        client = await stack.enter_async_context(ClientSession(base_url=core.url, timeout=ClientTimeout(total=10), trust_env=False))
        identity = await _login(client, core)
        session = AGENT + "/sessions/" + adapter.ready["conversationId"]
        baseline = await _json(client, identity, "GET", session + "/events?snapshot=true")
        await _json(client, identity, "POST", session + "/events/ack", {"cursor": baseline["cursor"]})
        client_id, request_id = "core-restart-input", "core-restart-request"
        stream = await _request(client, identity, "POST", session + "/messages",
                                {"text": "disconnect", "clientMessageId": client_id}, request_id)
        stack.callback(stream.close)
        assert stream.status == 200, await stream.text()
        generation = await next_event(stream)
        assert generation["type"] == "generation"
        generation_id = generation["generationId"]
        assert (await next_event(stream))["type"] == "delta"
        before = await _receipt(client, identity, session, clientMessageId=client_id, generationId=generation_id)
        assert before["state"] == "running"
        old_pid, port = core.process.pid, core.port
        await core.close(crash=shutdown == "kill")
        stream.close()
        marker = "开始：Core停机期间仍由原宿主任务写入"
        status = await adapter.command("emit", generationId=generation_id, text=marker)
        await adapter.command("detached")
        assert status == {"userCount": 1, "turnCount": 1, "liveTurns": 1, "interrupted": 0, "finished": False}
        restarted = await start(stack, "core", tmp_path / "core", host_url=adapter.url, port=port)
        assert restarted.process.pid != old_pid
        assert restarted.url == core.url
        # Reuse the original paired phone credentials and exact accepted IDs.
        running = await _receipt(client, identity, session, clientMessageId=client_id, generationId=generation_id)
        assert running == before
        operation = await _json(client, identity, "GET", AGENT + "/operations/" + request_id)
        assert operation == {"state": "unknown"}
        await adapter.command("release")
        by_client = await _receipt(client, identity, session, clientMessageId=client_id)
        by_generation = await _receipt(client, identity, session, generationId=generation_id)
        assert by_client == by_generation
        assert by_client["state"] == "completed"
        assert by_client["generationId"] == generation_id
        assert by_client["clientMessageId"] == client_id
        assert by_client["revision"] > before["revision"]
        events, cursor = await _replay(client, identity, session, baseline["cursor"])
        assert len({event["eventId"] for event in events}) == len(events)
        texts = [event["payload"].get("text") for event in events
                 if event["payload"].get("generationId") == generation_id]
        assert marker in texts
        assert "开始：观察断开后完成" in texts
        await _json(client, identity, "POST", session + "/events/ack", {"cursor": cursor})
        assert (await _json(client, identity, "GET", session + "/events"))["events"] == []
        status = await adapter.command("status")
        assert status == {"userCount": 1, "turnCount": 1, "liveTurns": 0, "interrupted": 0, "finished": True}
        _assert_one_input(adapter, client_id, finished=True)


@pytest.mark.asyncio
async def test_adapter_process_loss_never_fabricates_terminal_or_reposts_accepted_input(tmp_path, process_support):
    start, next_event = process_support
    async with AsyncExitStack() as stack:
        adapter = await start(stack, "adapter", tmp_path / "adapter")
        core = await start(stack, "core", tmp_path / "core", host_url=adapter.url)
        assert len({os.getpid(), adapter.process.pid, core.process.pid}) == 3
        client = await stack.enter_async_context(ClientSession(base_url=core.url, timeout=ClientTimeout(total=10), trust_env=False))
        identity = await _login(client, core)
        conversation = adapter.ready["conversationId"]
        session = AGENT + "/sessions/" + conversation
        baseline = await _json(client, identity, "GET", session + "/events?snapshot=true")
        client_id, request_id = "adapter-loss-input", "adapter-loss-request"
        stream = await _request(client, identity, "POST", session + "/messages",
                                {"text": "hold", "clientMessageId": client_id}, request_id)
        stack.callback(stream.close)
        assert stream.status == 200, await stream.text()
        generation = await next_event(stream)
        assert generation["type"] == "generation"
        generation_id = generation["generationId"]
        assert (await next_event(stream))["type"] == "delta"
        before = await _receipt(client, identity, session, clientMessageId=client_id, generationId=generation_id)
        assert before["state"] == "running"
        assert (await adapter.command("status"))["liveTurns"] == 1
        old_pid, port = adapter.process.pid, adapter.port
        await adapter.close(crash=True)
        # A transport error may end the observer, but is not a failed generation.
        interrupted = await next_event(stream)
        assert interrupted["type"] == "error", interrupted
        assert interrupted["code"] in {"AGENT_UNAVAILABLE", "AGENT_STREAM_INTERRUPTED"}
        async with asyncio.timeout(5):
            assert not (await stream.content.read()).strip()
        stream.close()
        assert core.process.is_alive()
        assert await _json(client, identity, "GET", AGENT + "/operations/" + request_id) == {"state": "unknown"}
        async with await _request(client, identity, "GET", session + "/generation?" + urlencode({"clientMessageId": client_id})) as response:
            assert response.status in {409, 502, 503}, await response.text()
        restarted = await start(stack, "adapter", tmp_path / "adapter", port=port, conversation=conversation)
        assert restarted.process.pid != old_pid
        assert restarted.url == adapter.url
        by_client = await _receipt(client, identity, session, clientMessageId=client_id)
        by_generation = await _receipt(client, identity, session, generationId=generation_id)
        assert by_client == by_generation
        assert by_client["state"] in {"running", "unknown"}
        assert by_client["generationId"] == generation_id
        assert by_client["clientMessageId"] == client_id
        assert by_client["userMessageId"] == before["userMessageId"]
        events, cursor = await _replay(client, identity, session, baseline["cursor"])
        assert any(event["payload"].get("generationId") == generation_id and
                   event["payload"].get("text") == "开始" for event in events)
        assert not any(event["payload"].get("state", event["payload"].get("status")) in
                       {"completed", "failed", "cancelled"} for event in events)
        await _json(client, identity, "POST", session + "/events/ack", {"cursor": cursor})
        assert await _json(client, identity, "GET", AGENT + "/operations/" + request_id) == {"state": "unknown"}
        status = await restarted.command("status")
        assert status == {"userCount": 1, "turnCount": 0, "liveTurns": 0, "interrupted": 0, "finished": False}
        _assert_one_input(restarted, client_id, finished=False)
