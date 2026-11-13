"""Real signed HTTP/WebSocket probes and cancellation ownership regressions."""
from __future__ import annotations

import asyncio

import pytest
from aiohttp import WSMsgType, web

from test_core_transport import PREFIX, gateway, phone_login, publish, request_signed, signed, token

PROBE = PREFIX + "/events/ws/probe"
EVENTS_WS = PREFIX + "/events/ws"


def current_transport(app):
    from oai_core.gateway.transport import GatewayTransport

    return next(
        handler for route in app.router.routes()
        if isinstance(handler := getattr(route.handler, "__self__", None), GatewayTransport)
    )


@pytest.mark.asyncio
async def test_probe_matches_client_ping_without_business_reads_or_presence(gateway, monkeypatch):
    from oai_core.gateway.core import EventStore
    from oai_core.gateway.http import GatewayHttpRoute

    client, core, app = gateway
    bundle, key = await phone_login(client, core)
    transport = current_transport(app)

    def business_forbidden(*args, **kwargs):
        pytest.fail("a probe must not enter event replay, subscription or online presence")

    monkeypatch.setattr(core, "handle", business_forbidden)
    monkeypatch.setattr(core, "set_device_online", business_forbidden)
    monkeypatch.setattr(GatewayHttpRoute, "event_backlog", business_forbidden)
    monkeypatch.setattr(EventStore, "read_after", business_forbidden)
    _, headers = signed(bundle, key, "GET", PROBE)
    async with client.ws_connect(PROBE, headers=headers, autoping=False) as stream:
        await stream.ping(b"probe-payload-\x00\xff")
        frame = await stream.receive(timeout=2)
        assert frame.type == WSMsgType.PONG
        assert frame.data == b"probe-payload-\x00\xff"
        assert transport._event_subscribers == {}
        assert not core.is_device_online(bundle["accountId"], bundle["deviceId"], bundle["pairingGeneration"])


@pytest.fixture
def websocket_observer(monkeypatch):
    connections = []
    receivers = {}
    prepare = web.WebSocketResponse.prepare
    receive = web.WebSocketResponse.receive

    async def observe_prepare(ws, request):
        if ws not in [entry[0] for entry in connections]:
            connections.append((ws, asyncio.current_task()))
        return await prepare(ws, request)

    async def observe_receive(ws, *args, **kwargs):
        receivers.setdefault(ws, set()).add(asyncio.current_task())
        return await receive(ws, *args, **kwargs)

    monkeypatch.setattr(web.WebSocketResponse, "prepare", observe_prepare)
    monkeypatch.setattr(web.WebSocketResponse, "receive", observe_receive)
    from oai_core.gateway.transport import GatewayTransport

    watch = GatewayTransport._watch_ws_session

    async def observe_watch(transport, ws, context):
        receivers.setdefault(ws, set()).add(asyncio.current_task())
        return await watch(transport, ws, context)

    monkeypatch.setattr(GatewayTransport, "_watch_ws_session", observe_watch)
    return connections, receivers


def append_notice(core, marker):
    account = core.open_gateway_account("alice")
    try:
        return account.events.append("gateway.notice", "cor_" + marker, {"noticeCode": marker})
    finally:
        account.close()


@pytest.mark.asyncio
async def test_ws_receives_ping_while_backlog_write_is_blocked(gateway, monkeypatch):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    append_notice(core, "blocked_replay")
    blocked, release = asyncio.Event(), asyncio.Event()
    send = web.WebSocketResponse.send_str

    async def pause_replay(ws, data, *args, **kwargs):
        if "blocked_replay" in data:
            blocked.set()
            await release.wait()
        return await send(ws, data, *args, **kwargs)

    monkeypatch.setattr(web.WebSocketResponse, "send_str", pause_replay)
    _, headers = signed(bundle, key, "GET", EVENTS_WS)
    stream = await client.ws_connect(EVENTS_WS, headers=headers, autoping=False)
    try:
        await asyncio.wait_for(blocked.wait(), 2)
        await stream.ping(b"during-backlog")
        async with asyncio.timeout(1):
            while True:
                frame = await stream.receive()
                if frame.type == WSMsgType.PONG:
                    assert frame.data == b"during-backlog"
                    break
    finally:
        release.set()
        await stream.close()


@pytest.mark.asyncio
async def test_ws_handler_cancellation_joins_live_tasks_and_preserves_other_stream(
    gateway, monkeypatch, websocket_observer,
):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    blocked, release = asyncio.Event(), asyncio.Event()
    senders = []
    send = web.WebSocketResponse.send_str

    async def pause_live(ws, data, *args, **kwargs):
        if "blocked_live" in data:
            senders.append(asyncio.current_task())
            blocked.set()
            await release.wait()
        return await send(ws, data, *args, **kwargs)

    monkeypatch.setattr(web.WebSocketResponse, "send_str", pause_live)
    _, headers = signed(bundle, key, "GET", EVENTS_WS)
    stream = await client.ws_connect(EVENTS_WS, headers=headers)
    # Another real stream retains the same session's online reference.
    _, sibling_headers = signed(bundle, key, "GET", PREFIX + "/events")
    sibling = await client.get(PREFIX + "/events", headers={**sibling_headers, "Accept": "text/event-stream"})
    connections, receivers = websocket_observer
    ws, handler = connections[0]

    async def read_close():
        while (await stream.receive()).type not in {WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED}:
            pass

    reader = asyncio.create_task(read_close())
    try:
        append_notice(core, "blocked_live")
        await asyncio.wait_for(blocked.wait(), 2)
        handler.cancel()
        await asyncio.wait_for(asyncio.shield(handler), 2)
        await asyncio.wait_for(reader, 2)
        assert senders and all(task.done() for task in senders), "handler left its live writer running"
        assert receivers[ws] and all(task.done() for task in receivers[ws]), "handler left its reader running"
        assert ws.closed, "cancelled handler did not close its WebSocket"
        assert core.is_device_online(bundle["accountId"], bundle["deviceId"], bundle["pairingGeneration"])
    finally:
        release.set()
        reader.cancel()
        await asyncio.gather(reader, return_exceptions=True)
        await stream.close()
        sibling.close()


SECURITY_HEADERS = (
    "Authorization",
    "X-Open-Android-Intelligence-Protocol",
    "X-Open-Android-Intelligence-Account",
    "X-Open-Android-Intelligence-Device",
    "X-Open-Android-Intelligence-Session",
    "X-Open-Android-Intelligence-Request-Id",
    "X-Open-Android-Intelligence-Timestamp",
    "X-Open-Android-Intelligence-Nonce",
    "X-Open-Android-Intelligence-Signature",
)


@pytest.mark.asyncio
@pytest.mark.parametrize("header", SECURITY_HEADERS)
@pytest.mark.parametrize("change", ["missing", "duplicate"])
async def test_probe_requires_all_singleton_security_headers(gateway, header, change):
    from aiohttp import WSServerHandshakeError

    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    _, headers = signed(bundle, key, "GET", PROBE)
    if change == "missing":
        headers.pop(header)
    else:
        headers = [*headers.items(), (header, headers[header])]
    with pytest.raises(WSServerHandshakeError) as error:
        await client.ws_connect(PROBE, headers=headers)
    assert error.value.status == 401
    assert not core.is_device_online(bundle["accountId"], bundle["deviceId"], bundle["pairingGeneration"])


@pytest.mark.asyncio
@pytest.mark.parametrize("target", [PREFIX + "/events", EVENTS_WS])
async def test_probe_verifies_original_target_and_invalid_signature_does_not_burn_nonce(gateway, target):
    from aiohttp import WSServerHandshakeError

    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    _, headers = signed(bundle, key, "GET", target)
    with pytest.raises(WSServerHandshakeError) as error:
        await client.ws_connect(PROBE, headers=headers)
    assert error.value.status == 401
    # The very same signed request must still work at its actual target.
    async with client.ws_connect(target, headers=headers):
        pass
    with pytest.raises(WSServerHandshakeError) as replay:
        await client.ws_connect(target, headers=headers)
    assert replay.value.status == 401


@pytest.mark.asyncio
async def test_probe_consumes_nonce_in_the_shared_deployment_ledger(gateway):
    from aiohttp import WSServerHandshakeError

    from test_core_transport import b64

    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    _, headers = signed(bundle, key, "GET", PROBE)
    async with client.ws_connect(PROBE, headers=headers):
        pass
    with pytest.raises(WSServerHandshakeError) as replay:
        await client.ws_connect(PROBE, headers=headers)
    assert replay.value.status == 401
    # A newly valid signature for another endpoint cannot reuse the nonce.
    fields = [
        "OPEN-ANDROID-INTELLIGENCE-REQUEST-V2", "GET", EVENTS_WS,
        bundle["accountId"], bundle["deviceId"], bundle["sessionId"],
        headers["X-Open-Android-Intelligence-Request-Id"],
        headers["X-Open-Android-Intelligence-Timestamp"],
        headers["X-Open-Android-Intelligence-Nonce"],
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    ]
    headers["X-Open-Android-Intelligence-Signature"] = b64(key.sign("\n".join(fields).encode("ascii")))
    with pytest.raises(WSServerHandshakeError) as across_route:
        await client.ws_connect(EVENTS_WS, headers=headers)
    assert across_route.value.status == 401


def invalidate_session(core, bundle, reason):
    account = core.open_gateway_account(bundle["accountId"])
    try:
        with account.store.transaction():
            if reason == "revoked":
                account.sessions.revoke_session(bundle["sessionId"], "cor_probe_revoked")
            elif reason == "expired":
                account.store.database.execute(
                    "UPDATE access_sessions SET expires_at=? WHERE session_id=?",
                    ("2000-01-01T00:00:00.000Z", bundle["sessionId"]),
                )
            else:
                account.store.database.execute(
                    "UPDATE device_keys SET pairing_generation=pairing_generation+1 WHERE device_id=?",
                    (bundle["deviceId"],),
                )
    finally:
        account.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("reason", ["revoked", "expired"])
async def test_probe_rejects_invalid_session_before_upgrade(gateway, reason):
    from aiohttp import WSServerHandshakeError

    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    _, headers = signed(bundle, key, "GET", PROBE)
    invalidate_session(core, bundle, reason)
    with pytest.raises(WSServerHandshakeError) as error:
        await client.ws_connect(PROBE, headers=headers)
    assert error.value.status == 401


@pytest.mark.asyncio
@pytest.mark.parametrize("target", [PROBE, EVENTS_WS])
@pytest.mark.parametrize("reason", ["revoked", "expired", "pairing_changed"])
async def test_stream_distinguishes_expired_credentials_from_revoked_identity(gateway, reason, target):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    _, headers = signed(bundle, key, "GET", target)
    async with client.ws_connect(target, headers=headers, autoping=False) as stream:
        await stream.ping(b"before-invalidation")
        assert (await stream.receive(timeout=2)).type == WSMsgType.PONG
        invalidate_session(core, bundle, reason)
        # No further input is needed to discover revocation or expiry.
        closed = await stream.receive(timeout=2)
        assert closed.type == WSMsgType.CLOSE
        assert closed.data == (4001 if reason == "expired" else 1008)


@pytest.mark.asyncio
@pytest.mark.parametrize("message", ["ping", '{"requestId":"business"}', b"business"])
async def test_probe_refuses_business_data_frames(gateway, message):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    _, headers = signed(bundle, key, "GET", PROBE)
    async with client.ws_connect(PROBE, headers=headers) as stream:
        if isinstance(message, bytes):
            await stream.send_bytes(message)
        else:
            await stream.send_str(message)
        closed = await stream.receive(timeout=2)
        assert closed.type == WSMsgType.CLOSE
        assert closed.data == 1008


@pytest.mark.asyncio
async def test_probe_rejects_a_pairing_revoked_through_the_signed_api(gateway):
    from aiohttp import WSServerHandshakeError

    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    await request_signed(client, bundle, key, "DELETE", PREFIX + "/pairings/current")
    _, headers = signed(bundle, key, "GET", PROBE)
    with pytest.raises(WSServerHandshakeError) as error:
        await client.ws_connect(PROBE, headers=headers)
    assert error.value.status == 401


@pytest.mark.asyncio
async def test_probe_leaves_durable_events_pending_requests_and_stream_cursor_untouched(gateway):
    client, core, app = gateway
    bundle, key = await phone_login(client, core)
    binding, _ = await publish(client, core, bundle, key)
    cli = await token(client, bundle)
    anchor = append_notice(core, "probe_anchor")
    _, headers = signed(bundle, key, "GET", PROBE)
    async with client.ws_connect(PROBE, headers=headers, autoping=False) as stream:
        notice = append_notice(core, "during_probe")
        response = await client.post(
            "/open-android-intelligence/core/v1/device-requests",
            json={"caller": {"agentName": "probe-test", "purpose": "验证探测无业务副作用"},
                  "capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1}},
            headers={**cli, "Idempotency-Key": "probe-keeps-pending"},
        )
        assert response.status == 200, await response.text()
        receipt = await response.json()
        assert receipt["state"] == "pending"
        await stream.ping(b"after-durable-business")
        frame = await stream.receive(timeout=2)
        assert frame.type == WSMsgType.PONG
        assert frame.data == b"after-durable-business"
        assert current_transport(app)._event_subscribers == {}
        assert not core.is_device_online(bundle["accountId"], bundle["deviceId"], bundle["pairingGeneration"])
        response = await client.get(
            "/open-android-intelligence/core/v1/device-requests/" + receipt["requestId"], headers=cli,
        )
        assert response.status == 200, await response.text()
        assert (await response.json())["state"] == "pending"
    # A later business stream still replays the original durable cursor boundary.
    target = EVENTS_WS + "?cursor=" + anchor["eventId"]
    _, headers = signed(bundle, key, "GET", target)
    async with client.ws_connect(target, headers=headers) as stream:
        first = await stream.receive_json(timeout=2)
        assert first["id"] == notice["eventId"]
        assert "during_probe" in first["data"]


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["cancel", "shutdown"])
@pytest.mark.parametrize("stage", ["probe_pong", "backlog", "live"])
async def test_cancellation_and_server_shutdown_join_all_socket_workers(
    gateway, monkeypatch, websocket_observer, action, stage,
):
    client, core, app = gateway
    bundle, key = await phone_login(client, core)
    blocked, release = asyncio.Event(), asyncio.Event()
    writers = []
    send = web.WebSocketResponse.send_str
    pong = web.WebSocketResponse.pong

    async def pause_send(ws, data, *args, **kwargs):
        if "blocked_owned" in data:
            writers.append(asyncio.current_task())
            blocked.set()
            await release.wait()
        return await send(ws, data, *args, **kwargs)

    async def pause_pong(ws, data=b""):
        writers.append(asyncio.current_task())
        blocked.set()
        await release.wait()
        return await pong(ws, data)

    monkeypatch.setattr(web.WebSocketResponse, "send_str", pause_send)
    if stage == "probe_pong":
        monkeypatch.setattr(web.WebSocketResponse, "pong", pause_pong)
    if stage == "backlog":
        append_notice(core, "blocked_owned")
    target = PROBE if stage == "probe_pong" else EVENTS_WS
    _, headers = signed(bundle, key, "GET", target)
    stream = await client.ws_connect(target, headers=headers, autoping=False)
    connections, workers = websocket_observer
    ws, handler = connections[0]
    if stage == "probe_pong":
        await stream.ping(b"cancel-in-flight")
    elif stage == "live":
        append_notice(core, "blocked_owned")

    async def read_close():
        while (await stream.receive()).type not in {WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED}:
            pass

    reader = asyncio.create_task(read_close())
    stopping = None
    try:
        await asyncio.wait_for(blocked.wait(), 2)
        if action == "cancel":
            handler.cancel()
            stopping = handler
        else:
            stopping = asyncio.create_task(app.shutdown())
        async with asyncio.timeout(2):
            await asyncio.shield(stopping)
            await reader
        assert ws.closed
        assert handler.done()
        assert writers and all(task.done() for task in writers)
        assert workers[ws] and all(task.done() for task in workers[ws])
        transport = current_transport(app)
        assert transport._websocket_handlers == set()
        assert transport._event_subscribers == {}
        assert not core.is_device_online(bundle["accountId"], bundle["deviceId"], bundle["pairingGeneration"])
    finally:
        release.set()
        reader.cancel()
        await asyncio.gather(reader, return_exceptions=True)
        await stream.close()
        if stopping is not None:
            await asyncio.gather(stopping, return_exceptions=True)


@pytest.mark.asyncio
async def test_repeated_handler_cancellation_still_closes_socket_during_worker_cleanup(
    gateway, monkeypatch, websocket_observer,
):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    receiving, cleaning, release = asyncio.Event(), asyncio.Event(), asyncio.Event()
    receive = web.WebSocketResponse.receive

    async def delay_cancel_cleanup(ws, *args, **kwargs):
        receiving.set()
        try:
            return await receive(ws, *args, **kwargs)
        except asyncio.CancelledError:
            cleaning.set()
            await release.wait()
            raise

    monkeypatch.setattr(web.WebSocketResponse, "receive", delay_cancel_cleanup)
    _, headers = signed(bundle, key, "GET", PROBE)
    stream = await client.ws_connect(PROBE, headers=headers)
    ws, handler = websocket_observer[0][0]

    async def read_close():
        return await stream.receive()

    reader = asyncio.create_task(read_close())
    try:
        await asyncio.wait_for(receiving.wait(), 2)
        handler.cancel()
        await asyncio.wait_for(cleaning.wait(), 2)
        handler.cancel()  # Server shutdown may overlap an already cancelled owner.
        await asyncio.wait_for(asyncio.gather(handler, return_exceptions=True), 2)
        assert ws.closed, "a second cancellation skipped socket.close()"
        assert (await asyncio.wait_for(reader, 2)).type in {WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED}
        assert all(task.done() for task in websocket_observer[1][ws])
    finally:
        release.set()
        reader.cancel()
        await asyncio.gather(reader, return_exceptions=True)
        await stream.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("stage", ["backlog", "live"])
async def test_ws_revocation_closes_1008_even_when_business_writer_is_blocked(
    gateway, monkeypatch, websocket_observer, stage,
):
    client, core, app = gateway
    bundle, key = await phone_login(client, core)
    blocked, release = asyncio.Event(), asyncio.Event()
    writers = []
    send = web.WebSocketResponse.send_str

    async def pause_write(ws, data, *args, **kwargs):
        if "blocked_revoked" in data:
            writers.append(asyncio.current_task())
            blocked.set()
            await release.wait()
        return await send(ws, data, *args, **kwargs)

    monkeypatch.setattr(web.WebSocketResponse, "send_str", pause_write)
    if stage == "backlog":
        append_notice(core, "blocked_revoked")
    _, headers = signed(bundle, key, "GET", EVENTS_WS)
    stream = await client.ws_connect(EVENTS_WS, headers=headers)
    ws, handler = websocket_observer[0][0]
    if stage == "live":
        append_notice(core, "blocked_revoked")
    try:
        await asyncio.wait_for(blocked.wait(), 2)
        invalidate_session(core, bundle, "revoked")
        async with asyncio.timeout(2):
            while True:
                frame = await stream.receive()
                if frame.type == WSMsgType.CLOSE:
                    assert frame.data == 1008
                    break
                assert frame.type != WSMsgType.TEXT or "blocked_revoked" not in frame.data
            await asyncio.shield(handler)
        assert ws.closed
        assert writers and all(task.done() for task in writers)
        assert current_transport(app)._event_subscribers == {}
    finally:
        release.set()
        await stream.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("target", [PROBE, EVENTS_WS])
async def test_cancellation_after_upgrade_before_prepare_returns_closes_socket(
    gateway, monkeypatch, websocket_observer, target,
):
    client, core, app = gateway
    bundle, key = await phone_login(client, core)
    prepared = asyncio.Event()
    release = asyncio.Event()
    prepare = web.WebSocketResponse.prepare

    async def pause_prepare(ws, request):
        first_prepare = not ws.prepared
        result = await prepare(ws, request)
        if first_prepare:
            prepared.set()
            await release.wait()
        return result

    monkeypatch.setattr(web.WebSocketResponse, "prepare", pause_prepare)
    _, headers = signed(bundle, key, "GET", target)
    stream = await client.ws_connect(target, headers=headers)
    ws, handler = websocket_observer[0][0]

    async def read_close():
        return await stream.receive()

    reader = asyncio.create_task(read_close())
    try:
        await asyncio.wait_for(prepared.wait(), 2)
        handler.cancel()
        async with asyncio.timeout(2):
            await asyncio.shield(handler)
            assert (await reader).type in {WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED}
        assert ws.closed
        assert current_transport(app)._event_subscribers == {}
        assert current_transport(app)._websocket_handlers == set()
        assert not core.is_device_online(bundle["accountId"], bundle["deviceId"], bundle["pairingGeneration"])
    finally:
        release.set()
        reader.cancel()
        await asyncio.gather(reader, return_exceptions=True)
        await stream.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("query", ["cursor=evt_unknown", "marker=a", "marker=%2B"])
async def test_probe_refuses_even_correctly_signed_query_without_business_dispatch(gateway, monkeypatch, query):
    from aiohttp import WSServerHandshakeError

    client, core, _ = gateway
    bundle, key = await phone_login(client, core)

    def business_forbidden(*args, **kwargs):
        pytest.fail("query rejection must not dispatch into Core business events")

    monkeypatch.setattr(core, "handle", business_forbidden)
    target = PROBE + "?" + query
    _, headers = signed(bundle, key, "GET", target)
    with pytest.raises(WSServerHandshakeError) as error:
        await client.ws_connect(target, headers=headers)
    assert error.value.status == 400


@pytest.mark.asyncio
async def test_query_is_not_stripped_before_verifying_the_probe_target(gateway):
    from aiohttp import WSServerHandshakeError

    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    _, headers = signed(bundle, key, "GET", PROBE)
    with pytest.raises(WSServerHandshakeError) as error:
        await client.ws_connect(PROBE + "?marker=a", headers=headers)
    assert error.value.status == 401
    async with client.ws_connect(PROBE, headers=headers):
        pass
