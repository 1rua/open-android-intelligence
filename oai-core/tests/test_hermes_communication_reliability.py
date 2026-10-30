"""Hermes admission, durable queue and storage boundaries with the pinned SDK."""
from __future__ import annotations

import asyncio
from contextlib import closing, contextmanager, suppress
import hashlib
import json
import os
from pathlib import Path
import threading

import pytest
import pytest_asyncio

pytestmark = pytest.mark.skipif(not os.environ.get("HERMES_SDK_ROOT"), reason="requires the pinned Hermes SDK")


@pytest_asyncio.fixture
async def host(tmp_path, monkeypatch):
    sdk = Path(os.environ["HERMES_SDK_ROOT"]).resolve()
    assert (sdk / "gateway/session.py").is_file(), "pinned Hermes SDK is required"
    monkeypatch.syspath_prepend(str(sdk))
    monkeypatch.setenv("HERMES_HOME", str(tmp_path / "native"))
    monkeypatch.setenv("HERMES_TEST_ISOLATION", str(tmp_path / "native"))
    from gateway.config import GatewayConfig, PlatformConfig
    from gateway.platform_registry import PlatformEntry, platform_registry
    from gateway.session import SessionStore
    from oai_core.gateway.core import AgentSessionBindings
    from oai_core.server import create_core
    from oai_hermes_adapter.backend import SessionBackend
    from oai_hermes_adapter.host_history import HermesHistoryPort
    from oai_hermes_adapter.platform_identity import GATEWAY_PLATFORM_IDS
    from oai_hermes_adapter.runtime import SessionOnlyAdapter
    from oai_hermes_adapter.sessions import HermesSessionAdapter

    core = create_core({"storageRoot": str(tmp_path / "accounts"), "masterKeyFile": str(tmp_path / "master"),
                        "contractRoot": str(Path(__file__).resolve().parents[2] / "gateway-contract")})
    conversations = []
    for owner in ("alice", "bob"):
        with closing(core.open_gateway_account(owner)) as account:
            for index in range(2):
                conversations.append((owner, account.conversations.create(
                    f"create-{index}", "Synthetic", f"create-{index}")["conversationId"]))
    backend = SessionBackend(core.storage_root, ["alice", "bob"], secret_store=core.secret_store)
    backend.acquire_accounts()
    registrations = []
    for platform_id in GATEWAY_PLATFORM_IDS:
        previous = platform_registry.snapshot_registration(platform_id)
        platform_registry.register(PlatformEntry(platform_id, "Synthetic Hermes", lambda config: None, lambda: True))
        registrations.append((platform_id, platform_registry.snapshot_registration(platform_id), previous))
    runtime = SessionOnlyAdapter(PlatformConfig(enabled=True), backend,
        "synthetic-session-bridge-token-1234567890", ["alice", "bob"], 0)
    store = SessionStore(tmp_path / "native" / "sessions", GatewayConfig())
    runtime.set_session_store(store)
    runtime._loop = asyncio.get_running_loop()
    for owner, conversation in conversations:
        await runtime._ensure_agent_session(conversation, owner, force_new=False,
            created_via=AgentSessionBindings.CREATED_VIA_INBOUND_MESSAGE)
    history = HermesHistoryPort(backend, store)
    backend.history_reader = history.read
    backend.conversation_manager = history.manage
    try:
        yield runtime, HermesSessionAdapter(backend, runtime), conversations, history
    finally:
        tasks = set(runtime._background_tasks) | set(runtime._session_tasks.values())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        store.close_all_db_handles()
        backend.close()
        for platform_id, current, previous in reversed(registrations):
            platform_registry.restore_registration(platform_id, current, previous)


def accepted(host, client_id, *, conversation=None):
    runtime, _, conversations, _ = host
    owner, conversation = conversation or conversations[0]
    with closing(runtime.services.core.open_gateway_account(owner)) as account:
        return {**account.conversations.accept_message(conversation, client_id, "synthetic input", [], "phone", client_id, client_id),
                "clientMessageId": client_id}


def generation(host, receipt):
    with closing(host[0].services.core.open_gateway_account("alice")) as account:
        return account.conversations.workflow.for_message(receipt["messageId"])


async def notify(host, receipt):
    await host[0]._notify_agent_inbound(
        f"/open-android-intelligence/v2/conversations/{receipt['conversationId']}/messages",
        json.dumps({"clientMessageId": receipt["clientMessageId"]}).encode(), {"data": receipt}, "alice")


@pytest.mark.asyncio
async def test_cancel_during_threaded_accept_still_registers_exactly_one_native_dispatch(host, monkeypatch):
    from oai_core.auth import Principal
    runtime, adapter, conversations, _ = host
    owner, conversation = conversations[0]
    backend = runtime.services.core
    entered, release = threading.Event(), threading.Event()
    delivered, calls = asyncio.Event(), []
    with closing(backend.open_gateway_account(owner)) as account:
        store = account.store
        original = store.transaction
        @contextmanager
        def held_commit(*args, **kwargs):
            with original(*args, **kwargs) as database:
                yield database
            if store._transaction_depth == 0 and not entered.is_set():
                entered.set()
                assert release.wait(2)
        monkeypatch.setattr(store, "transaction", held_commit)
    async def handler(event):
        calls.append(event.message_id)
        delivered.set()
    runtime.set_message_handler(handler)
    stream = adapter.send(Principal(owner, device_id="phone"), conversation, "synthetic input", client_message_id="client-cancel")
    observation = asyncio.create_task(anext(stream))
    try:
        assert await asyncio.to_thread(entered.wait, 2)
        observation.cancel()
        with suppress(asyncio.CancelledError):
            await observation
    finally:
        release.set()
    await asyncio.wait_for(delivered.wait(), 1)
    await asyncio.gather(*tuple(runtime._background_tasks))
    with closing(backend.open_gateway_account(owner)) as account:
        assert account.store.database.execute("SELECT COUNT(*) FROM messages WHERE client_message_id=?", ("client-cancel",)).fetchone()[0] == 1
    assert calls == ["client-cancel"]
    assert backend._sinks == []


@pytest.mark.asyncio
async def test_reversed_notifications_drain_the_durable_queue_once_in_acceptance_order(host):
    runtime = host[0]
    first, second = accepted(host, "client-first"), accepted(host, "client-second")
    calls, started = [], asyncio.Event()
    async def handler(event):
        calls.append(event.message_id)
        if len(calls) == 2:
            started.set()
    runtime.set_message_handler(handler)
    # Only the later notifier arrives first. It must also advance the older
    # durable queued turn, including when that earlier observer is already gone.
    later = asyncio.create_task(notify(host, second))
    try:
        await asyncio.wait_for(started.wait(), 1)
        await asyncio.wait_for(later, 1)
        await notify(host, first)
        await notify(host, second)
    finally:
        later.cancel()
        await asyncio.gather(later, return_exceptions=True)
    assert calls == ["client-first", "client-second"]
    assert generation(host, first)["state"] == generation(host, second)["state"] == "completed"


@pytest.mark.asyncio
@pytest.mark.parametrize("state", ["running", "unknown"])
async def test_running_or_unknown_turn_fences_later_queued_work_without_redelivery(host, state):
    runtime = host[0]
    first, second = accepted(host, "client-fence"), accepted(host, "client-waiting")
    with closing(runtime.services.core.open_gateway_account("alice")) as account:
        assert account.conversations.claim_dispatch(first["clientMessageId"])
        if state == "unknown":
            account.conversations.workflow.settle(first["messageId"], "unknown")
    calls = []
    async def handler(event):
        calls.append(event.message_id)
    runtime.set_message_handler(handler)
    pending = asyncio.create_task(notify(host, second))
    try:
        await asyncio.sleep(0.04)
        assert not calls
        assert generation(host, second)["state"] == "queued"
        with closing(runtime.services.core.open_gateway_account("alice")) as account:
            with account.store.transaction():
                account.conversations.workflow.settle(first["messageId"], "completed")
                fact = account.conversations.workflow.for_message(first["messageId"])
                account.events.append("conversation.message.status", first["messageId"], {
                    "conversationId": first["conversationId"], "messageId": first["messageId"],
                    "clientMessageId": first["clientMessageId"], "generationId": fact["generationId"],
                    "status": "completed", "revision": fact["revision"], "errorCode": None})
        await asyncio.wait_for(pending, 1)
    finally:
        pending.cancel()
        await asyncio.gather(pending, return_exceptions=True)
    assert calls == ["client-waiting"]


@pytest.mark.asyncio
@pytest.mark.parametrize("handoff", ["claimed", "delivered", "unknown"])
async def test_restart_preserves_unknown_handoff_and_resumes_only_never_claimed_queue(host, handoff):
    runtime = host[0]
    uncertain = accepted(host, "client-prior-handoff")
    with closing(runtime.services.core.open_gateway_account("alice")) as account:
        claim = account.conversations.claim_dispatch(uncertain["clientMessageId"])
        assert claim
        if handoff == "delivered":
            account.conversations.update_dispatch_status(uncertain["messageId"], "delivered", None, "admitted", claim_token=claim["claimToken"])
        elif handoff == "unknown":
            account.conversations.workflow.settle(uncertain["messageId"], "unknown")
    queued = accepted(host, "client-never-claimed", conversation=host[2][1])
    calls, resumed = [], asyncio.Event()
    async def handler(event):
        calls.append(event.message_id)
        resumed.set()
    runtime.set_message_handler(handler)
    await runtime._recover_message_dispatches("alice")
    await asyncio.wait_for(resumed.wait(), 1)
    await asyncio.gather(*tuple(runtime._background_tasks))
    assert generation(host, uncertain)["state"] == "unknown"
    assert generation(host, queued)["state"] == "completed"
    assert calls == ["client-never-claimed"]
    with closing(runtime.services.core.open_gateway_account("alice")) as account:
        assert not any(event.get("payload", {}).get("status") == "failed" for event in account.events.read_after(None))


@pytest.mark.asyncio
async def test_slow_global_history_storage_does_not_block_other_session_or_loop(host, monkeypatch):
    from oai_core.auth import Principal
    runtime, _, conversations, history = host
    first = accepted(host, "client-slow")
    other_owner, other_conversation = conversations[-1]
    entered, release = threading.Event(), threading.Event()
    loop = asyncio.get_running_loop()
    original = history.capture_submission
    def held_capture(account, record):
        with history._migration_lock:
            entered.set()
            # A bounded wait lets the unfixed synchronous call fail quickly.
            release.wait(0.3)
            return original(account, record)
    monkeypatch.setattr(history, "capture_submission", held_capture)
    async def handler(event):
        return None
    runtime.set_message_handler(handler)
    task = asyncio.create_task(notify(host, first))
    before = loop.time()
    try:
        assert await asyncio.to_thread(entered.wait, 1)
        await asyncio.sleep(0.005)
        elapsed = loop.time() - before
        # A different account needs no history lock for its durable receipt.
        assert await host[1].current_generation(Principal(other_owner), other_conversation) is None
        assert elapsed < 0.15, f"event loop blocked by history storage for {elapsed:.3f}s"
    finally:
        release.set()
        await asyncio.wait_for(task, 2)


@pytest.mark.asyncio
async def test_bad_event_observer_does_not_hide_durable_status_from_other_observers(host, caplog):
    runtime = host[0]
    backend, seen = runtime.services.core, []
    def bad_sink(*args):
        raise RuntimeError("synthetic-private-content")
    def good_sink(owner, event):
        seen.append(event)
    backend.register_event_sink(bad_sink)
    backend.register_event_sink(good_sink)
    try:
        receipt = accepted(host, "client-sinks")
        with closing(backend.open_gateway_account("alice")) as account:
            assert account.conversations.dispatch_message("client-sinks")["messageId"] == receipt["messageId"]
        assert any(event["eventType"] == "conversation.message.status" for event in seen)
        assert "synthetic-private-content" not in caplog.text
        committed = list(seen)
        with closing(backend.open_gateway_account("alice")) as account:
            with pytest.raises(RuntimeError):
                with account.store.transaction():
                    account.conversations.accept_message(receipt["conversationId"], "rolled-back", "synthetic input",
                        [], "phone", "rolled-back", "rolled-back")
                    raise RuntimeError("rollback boundary")
            assert account.conversations.dispatch_message("rolled-back") is None
        assert seen == committed
    finally:
        backend.unregister_event_sink(bad_sink)
        backend.unregister_event_sink(good_sink)


@pytest.mark.asyncio
async def test_real_http_observer_disconnect_keeps_native_turn_and_trace_alive(host, caplog, monkeypatch):
    import logging
    from aiohttp.test_utils import TestClient, TestServer
    from oai_core.auth import Principal
    from oai_core.native_diagnostics import current_trace_headers
    from oai_hermes_adapter.bridge import PREFIX, create_hermes_bridge_app

    runtime, adapter, conversations, _ = host
    owner, conversation = conversations[0]
    backend = runtime.services.core
    release, started, unsubscribed = asyncio.Event(), asyncio.Event(), asyncio.Event()
    calls, handler_headers = [], []
    private = "synthetic-private-http-prompt"
    original_unregister = backend.unregister_event_sink
    def unregister(sink):
        original_unregister(sink)
        unsubscribed.set()
    monkeypatch.setattr(backend, "unregister_event_sink", unregister)
    async def handler(event):
        calls.append((event.source.user_id, event.message_id))
        handler_headers.append(current_trace_headers())
        started.set()
        await release.wait()
        await runtime.stream_delta(conversation, "native-http-reply", "synthetic partial")
        await runtime.complete_message(conversation, "native-http-reply", "synthetic final")
    runtime.set_message_handler(handler)
    headers = {"Authorization": "Bearer " + runtime._token, "X-OAI-Account": owner, "X-OAI-Device": "phone",
               "X-OAI-Request-Id": "bob", "traceparent": "00-" + "a" * 32 + "-" + "b" * 16 + "-01"}
    app = create_hermes_bridge_app(adapter, runtime._token, ["alice", "bob"])
    with caplog.at_level(logging.DEBUG, logger="oai_core.native_sessions"):
        async with TestClient(TestServer(app)) as client:
            denied = await client.post(f"{PREFIX}/sessions/{conversation}/messages",
                headers=headers | {"Authorization": "Bearer wrong"}, json={"text": private, "clientMessageId": "unauthenticated"})
            assert denied.status == 401
            response = await client.post(f"{PREFIX}/sessions/{conversation}/messages", headers=headers,
                json={"text": private, "clientMessageId": "client-http-disconnect"})
            try:
                assert response.status == 200
                first = json.loads(await asyncio.wait_for(response.content.readline(), 2))
                assert first["type"] == "generation" and first["state"] == "queued"
                await asyncio.wait_for(started.wait(), 2)
            finally:
                response.close()
                release.set()
            await asyncio.wait_for(asyncio.gather(*tuple(runtime._background_tasks)), 2)
            await asyncio.wait_for(unsubscribed.wait(), 2)
            duplicate = await client.post(f"{PREFIX}/sessions/{conversation}/messages", headers=headers,
                json={"text": private, "clientMessageId": "client-http-disconnect"})
            assert duplicate.status == 409
            assert (await duplicate.json())["code"] == "REQUEST_ALREADY_EXECUTED"
        recovered = await adapter.generation(Principal(owner), conversation, client_message_id="client-http-disconnect")
    assert recovered["generationId"] == first["generationId"] and recovered["state"] == "completed"
    assert calls == [("alice", "client-http-disconnect")]
    assert handler_headers[0]["X-OAI-Request-Id"] == "bob"  # diagnostic ID cannot choose the account
    traces = [json.loads(record.getMessage().removeprefix("native_session ")) for record in caplog.records
              if record.name == "oai_core.native_sessions"]
    assert {trace["stage"] for trace in traces} == {"T3", "T3_HANDLER", "T4", "T10"}
    assert all(trace["traceId"] == "a" * 32 and trace["requestId"] == "bob"
               and trace["agentId"] == "hermes" and trace["conversationId"] == conversation for trace in traces)
    assert private not in caplog.text and runtime._token not in caplog.text
    with closing(backend.open_gateway_account(owner)) as account:
        assert account.store.database.execute("SELECT COUNT(*) FROM messages WHERE client_message_id=?",
                                             ("client-http-disconnect",)).fetchone()[0] == 1
    assert backend._sinks == []


@pytest.mark.asyncio
async def test_attachment_decrypt_and_fsync_run_off_loop_with_original_bytes_and_one_dispatch(host, monkeypatch):
    runtime = host[0]
    backend, conversation = runtime.services.core, host[2][0][1]
    content = b"synthetic-attachment" * 4096
    with closing(backend.open_gateway_account("alice")) as account:
        attachment = account.attachments.create(clientAttachmentId="native-file", filename="synthetic.bin",
            mediaType="application/octet-stream", sizeBytes=len(content), sha256=hashlib.sha256(content).hexdigest(),
            deviceId="phone", pairingGeneration=0, correlationId="native-file")
        attachment_id = attachment["attachmentId"]
        account.attachments.upload_content(attachment_id, content)
        account.attachments.commit(attachment_id)
        receipt = {**account.conversations.accept_message(conversation, "client-fsync", "synthetic input",
            [attachment_id], "phone", "client-fsync", "client-fsync"), "clientMessageId": "client-fsync"}
    entered, release = threading.Event(), threading.Event()
    thread_ids, files = [], []
    original_fsync = os.fsync
    loop_thread, loop = threading.get_ident(), asyncio.get_running_loop()
    def held_fsync(descriptor):
        thread_ids.append(threading.get_ident())
        if not entered.is_set():
            entered.set()
            release.wait(0.3)
        return original_fsync(descriptor)
    monkeypatch.setattr(os, "fsync", held_fsync)
    async def handler(event):
        files.extend((Path(path).read_bytes(), event.message_id) for path in event.media_urls)
    runtime.set_message_handler(handler)
    before = loop.time()
    task = asyncio.create_task(notify(host, receipt))
    try:
        assert await asyncio.to_thread(entered.wait, 1)
        await asyncio.sleep(0.005)
        assert loop.time() - before < 0.15
    finally:
        release.set()
        await asyncio.wait_for(task, 2)
    assert files == [(content, "client-fsync")]
    assert thread_ids and loop_thread not in thread_ids
    assert generation(host, receipt)["state"] == "completed"


@pytest.mark.asyncio
async def test_concurrent_event_commits_keep_order_without_blocking_another_session(host, monkeypatch):
    runtime, _, conversations, history = host
    owner, conversation = conversations[0]
    other_owner, other_conversation = conversations[-1]
    entered, release = threading.Event(), threading.Event()
    original = history.record_visible
    def slow_visible(account, payload, state="CONFIRMED"):
        if payload["conversationId"] == conversation and payload["text"] == "first":
            entered.set()
            release.wait(0.3)
        return original(account, payload, state)
    monkeypatch.setattr(history, "record_visible", slow_visible)
    loop = asyncio.get_running_loop()
    before = loop.time()
    first = asyncio.create_task(runtime.stream_delta(conversation, "ordered-reply", "first", account_id=owner))
    try:
        assert await asyncio.to_thread(entered.wait, 1)
        last = asyncio.create_task(runtime.complete_message(conversation, "ordered-reply", "last", account_id=owner))
        await runtime.stream_delta(other_conversation, "other-reply", "other", account_id=other_owner)
        assert loop.time() - before < 0.15
    finally:
        release.set()
        await asyncio.wait_for(first, 2)
    await asyncio.wait_for(last, 2)
    with closing(runtime.services.core.open_gateway_account(owner)) as account:
        events = [event for event in account.events.read_after(None) if event.get("payload", {}).get("messageId") == "ordered-reply"]
    assert [(event["eventType"], event["payload"]["text"]) for event in events] == [
        ("conversation.message.delta", "first"), ("conversation.message.completed", "last")]
    assert events[0]["eventId"] != events[1]["eventId"]


@pytest.mark.asyncio
async def test_trace_uses_real_generation_after_native_callback_loses_context(host, caplog):
    from contextvars import Context
    import logging
    from gateway.platforms.base import MessageEvent
    from oai_core.native_diagnostics import trace_context

    runtime = host[0]
    receipt = accepted(host, "client-cross-thread")
    with trace_context({"traceparent": "00-" + "c" * 32 + "-" + "d" * 16 + "-01"}, request_id="cross-thread-request"):
        runtime.remember_generation_trace("alice", receipt["conversationId"], receipt["generationId"])
    with closing(runtime.services.core.open_gateway_account("alice")) as account:
        claim = account.conversations.claim_dispatch("client-cross-thread")
    event = MessageEvent(text="synthetic private callback", message_id="client-cross-thread",
                         source=runtime._agent_source(receipt["conversationId"], "alice"))
    event._gateway_claim_token = claim["claimToken"]
    async def handler(event):
        return None
    runtime.set_message_handler(handler)
    async def native_callback():
        await runtime.on_processing_start(event)
        assert not any('"stage":"T3_HANDLER"' in record.getMessage() for record in caplog.records)
        await runtime._message_handler(event)
        await runtime._publish_event("conversation.message.delta", receipt["conversationId"], "real-native-reply",
            "synthetic private output", account_id="alice", generation_id=receipt["generationId"])
        await runtime.on_processing_complete(event, "completed")
    loop = asyncio.get_running_loop()
    def callback_from_thread():
        # A fresh native thread has no ContextVar values from the Core request.
        future = Context().run(asyncio.run_coroutine_threadsafe, native_callback(), loop)
        future.result(2)
    with caplog.at_level(logging.DEBUG, logger="oai_core.native_sessions"):
        await asyncio.to_thread(callback_from_thread)
    traces = [json.loads(record.getMessage().removeprefix("native_session ")) for record in caplog.records
              if record.name == "oai_core.native_sessions"]
    assert [trace["stage"] for trace in traces] == ["T3", "T3_HANDLER", "T4", "T10"]
    assert all(trace["traceId"] == "c" * 32 and trace["requestId"] == "cross-thread-request"
               and trace["generationId"] == receipt["generationId"] for trace in traces)
    assert "synthetic private" not in caplog.text


@pytest.mark.asyncio
async def test_concurrent_admission_return_order_cannot_change_dispatch_or_trace_order(host, monkeypatch):
    from oai_core.auth import Principal
    from oai_core.native_diagnostics import current_trace_headers, trace_context

    runtime, adapter, conversations, _ = host
    owner, conversation = conversations[0]
    entered, release = threading.Event(), threading.Event()
    handled, seen = asyncio.Event(), []
    with closing(runtime.services.core.open_gateway_account(owner)) as account:
        store, original = account.store, account.store.transaction
        @contextmanager
        def delay_first_commit_return(*args, **kwargs):
            with original(*args, **kwargs) as database:
                yield database
            if store._transaction_depth == 0 and not entered.is_set():
                entered.set()
                assert release.wait(2)
        monkeypatch.setattr(store, "transaction", delay_first_commit_return)
    async def handler(event):
        seen.append((event.message_id, current_trace_headers()))
        if len(seen) == 2:
            handled.set()
    runtime.set_message_handler(handler)
    first_stream = adapter.send(Principal(owner, device_id="phone"), conversation,
        "synthetic first", client_message_id="concurrent-first")
    second_stream = adapter.send(Principal(owner, device_id="phone"), conversation,
        "synthetic second", client_message_id="concurrent-second")
    with trace_context({"traceparent": "00-" + "1" * 32 + "-" + "a" * 16 + "-01"}, request_id="request-first"):
        first_observation = asyncio.create_task(anext(first_stream))
    second_observation = None
    try:
        assert await asyncio.to_thread(entered.wait, 1)
        with trace_context({"traceparent": "00-" + "2" * 32 + "-" + "b" * 16 + "-01"}, request_id="request-second"):
            second_observation = asyncio.create_task(anext(second_stream))
        await asyncio.wait_for(second_observation, 1)
        await asyncio.wait_for(handled.wait(), 1)
    finally:
        release.set()
        await asyncio.wait_for(first_observation, 2)
        if second_observation is not None:
            await asyncio.gather(second_observation, return_exceptions=True)
        await first_stream.aclose()
        await second_stream.aclose()
    await asyncio.wait_for(asyncio.gather(*tuple(runtime._background_tasks)), 2)
    assert [client_id for client_id, _ in seen] == ["concurrent-first", "concurrent-second"]
    assert [headers["X-OAI-Request-Id"] for _, headers in seen] == ["request-first", "request-second"]
    assert [headers["traceparent"].split("-")[1] for _, headers in seen] == ["1" * 32, "2" * 32]
    with closing(runtime.services.core.open_gateway_account(owner)) as account:
        assert account.store.database.execute("SELECT COUNT(*) FROM messages WHERE sender='user'").fetchone()[0] == 2
        assert account.conversations.dispatch_message("concurrent-first")["status"] == "completed"
        assert account.conversations.dispatch_message("concurrent-second")["status"] == "completed"


@pytest.mark.asyncio
async def test_cancelled_reply_writer_retains_order_until_thread_commit_finishes(host, monkeypatch):
    runtime, _, conversations, history = host
    owner, conversation = conversations[0]
    entered, release = threading.Event(), threading.Event()
    original = history.record_visible
    def hold_first(account, payload, state="CONFIRMED"):
        if payload["text"] == "inflight":
            entered.set()
            assert release.wait(2)
        return original(account, payload, state)
    monkeypatch.setattr(history, "record_visible", hold_first)
    writer = asyncio.create_task(runtime.stream_delta(conversation, "cancelled-writer", "inflight", account_id=owner))
    terminal = None
    try:
        assert await asyncio.to_thread(entered.wait, 1)
        writer.cancel()
        terminal = asyncio.create_task(runtime.complete_message(conversation, "cancelled-writer", "terminal", account_id=owner))
        await asyncio.sleep(0.02)
        assert not terminal.done(), "completion overtook the cancelled writer's still-running thread"
        writer.cancel()  # repeated host cancellation must retain the same fence
        await asyncio.sleep(0)
        assert not terminal.done()
    finally:
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await writer
        if terminal is not None:
            await asyncio.wait_for(terminal, 2)
    with closing(runtime.services.core.open_gateway_account(owner)) as account:
        events = [event for event in account.events.read_after(None) if event.get("payload", {}).get("messageId") == "cancelled-writer"]
    assert [(event["eventType"], event["payload"]["text"]) for event in events] == [
        ("conversation.message.delta", "inflight"), ("conversation.message.completed", "terminal")]


@pytest.mark.asyncio
async def test_storage_errors_cannot_echo_private_content_or_credentials_into_logs(host, monkeypatch, caplog):
    runtime, _, conversations, history = host
    owner, conversation = conversations[0]
    private = "synthetic-private-prompt-and-bridge-secret"
    def unavailable(*args, **kwargs):
        raise RuntimeError(private)
    monkeypatch.setattr(history, "record_visible", unavailable)
    result = await runtime.send(conversation, "synthetic reply", metadata={"messageId": "error-reply", "account_id": owner})
    assert not result.success
    assert private not in caplog.text


@pytest.mark.parametrize("reader", ["fetchone", "fetchall", "fetchmany", "iterate"])
def test_sqlite_cursor_materialization_stays_under_the_shared_account_lock(reader):
    import sqlite3
    from concurrent.futures import ThreadPoolExecutor
    from oai_hermes_adapter.backend import LockedDatabase
    lock = threading.RLock()
    connection = sqlite3.connect(":memory:", check_same_thread=False)
    def row_factory(_cursor, values):
        assert lock._is_owned(), "SQLite fetch escaped the shared connection lock"
        return tuple(values)
    connection.row_factory = row_factory
    database = LockedDatabase(connection, lock)
    def read(index):
        cursor = database.execute("SELECT ? UNION ALL SELECT ?", (index, index + 1))
        if reader == "fetchone":
            values = [cursor.fetchone(), cursor.fetchone()]
        elif reader == "fetchall":
            values = cursor.fetchall()
        elif reader == "fetchmany":
            values = cursor.fetchmany(2)
        else:
            values = list(cursor)
        cursor.close()
        assert values == [(index,), (index + 1,)]
    try:
        with ThreadPoolExecutor(max_workers=4) as executor:
            list(executor.map(read, range(100)))
    finally:
        database.close()
