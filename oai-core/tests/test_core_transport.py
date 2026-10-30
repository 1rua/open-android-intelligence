"""Real HTTP, Ed25519, account isolation and shared native device state tests."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import secrets
import threading
from datetime import datetime, timezone
from pathlib import Path

import pytest
from aiohttp import WSMsgType
from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from oai_core.gateway.core import CredentialStore, _jcs

from oai_core.server import AUTH_KEY, CORE_KEY, DEVICE_API, create_app, create_core
from oai_core.sessions import AgentRegistry

PREFIX = "/open-android-intelligence/v2"
CONTRACT = Path(__file__).resolve().parents[2] / "gateway-contract"


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def signed(bundle, key, method, target, body=None, request_id=None):
    raw = json.dumps(body, separators=(",", ":")).encode() if body is not None else b""
    timestamp = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    nonce = b64(secrets.token_bytes(16))
    request_id = request_id or "req_" + secrets.token_hex(8)
    preimage = "\n".join(("OPEN-ANDROID-INTELLIGENCE-REQUEST-V2", method, target, bundle["accountId"], bundle["deviceId"], bundle["sessionId"], request_id, timestamp, nonce, hashlib.sha256(raw).hexdigest())).encode("ascii")
    headers = {"Authorization": "Bearer " + bundle["accessToken"], "Content-Type": "application/json",
               "X-Open-Android-Intelligence-Protocol": "2.1", "X-Open-Android-Intelligence-Account": bundle["accountId"],
               "X-Open-Android-Intelligence-Device": bundle["deviceId"], "X-Open-Android-Intelligence-Session": bundle["sessionId"],
               "X-Open-Android-Intelligence-Request-Id": request_id, "X-Open-Android-Intelligence-Timestamp": timestamp,
               "X-Open-Android-Intelligence-Nonce": nonce, "X-Open-Android-Intelligence-Signature": b64(key.sign(preimage))}
    if method != "GET":
        headers["Idempotency-Key"] = request_id
    return raw, headers


async def phone_login(client, core, account_id="alice"):
    key = Ed25519PrivateKey.generate()
    negotiation = json.loads((CONTRACT / "vectors/protocol-negotiation.json").read_text())["cases"][0]["input"]["value"]
    negotiation["negotiationId"] = "neg_" + secrets.token_hex(4)
    negotiation["schemaHashes"]["core"] = core.contracts.core_schema_hash
    installation = negotiation["client"]["installationId"]
    response = await client.post(PREFIX + "/negotiate", json=negotiation)
    assert response.status == 200, await response.text()
    response = await client.post(PREFIX + "/sessions/password", json={"negotiationId": negotiation["negotiationId"], "username": account_id, "password": "test-only-password",
        "installation": {"installationId": installation, "displayName": "test", "devicePublicKey": b64(key.public_key().public_bytes_raw())}})
    assert response.status == 200, await response.text()
    return (await response.json())["data"], key


async def request_signed(client, bundle, key, method, path, body=None):
    raw, headers = signed(bundle, key, method, path, body)
    response = await client.request(method, path, data=raw, headers=headers)
    assert response.status == 200, await response.text()
    return (await response.json())["data"]


@pytest.fixture
async def gateway(tmp_path):
    settings = {"storageRoot": str(tmp_path / "data"), "masterKeyFile": str(tmp_path / "secrets/master"), "contractRoot": str(CONTRACT)}
    core = create_core(settings)
    for account_id in ("alice", "bob"):
        account = core.open_gateway_account(account_id)
        account.credentials.create_password("test-only-password")
        account.close()
    app = create_app(settings, registry=AgentRegistry(), core=core)
    async with TestClient(TestServer(app)) as client:
        yield client, core, app


async def token(client, bundle):
    response = await client.post(DEVICE_API + "/tokens", json={"username": bundle["accountId"], "password": "test-only-password", "deviceId": bundle["deviceId"]})
    assert response.status == 200, await response.text()
    return {"Authorization": "Bearer " + (await response.json())["token"]}


def pause_password_checks(monkeypatch):
    """Pause real verification after reading its credential, without DB locks."""
    loop = asyncio.get_running_loop()
    started = asyncio.Queue()
    release = threading.Event()
    verify_password = CredentialStore.verify_password

    def verify(self, password):
        result = verify_password(self, password)
        loop.call_soon_threadsafe(started.put_nowait, threading.get_ident())
        assert release.wait(10), "password worker was not released"
        return result

    monkeypatch.setattr(CredentialStore, "verify_password", verify)
    return started, release


@pytest.mark.asyncio
async def test_core_does_not_advertise_unavailable_legacy_chat_features(gateway):
    client, core, _ = gateway
    negotiation = json.loads((CONTRACT / "vectors/protocol-negotiation.json").read_text())["cases"][0]["input"]["value"]
    negotiation["schemaHashes"]["core"] = core.contracts.core_schema_hash
    negotiation["features"]["conversationUi"] = ["agent-command-new-v1", "message-batches-v1", "newline-v1", "agent-command-catalog-v1"]
    response = await client.post(PREFIX + "/negotiate", json=negotiation)
    assert response.status == 200
    assert response.headers["X-OAI-Agent-Sessions"] == "1"
    features = (await response.json())["data"]["features"]
    assert features.get("conversationUi", []) == []
    assert features["deviceRequests"] == "risk-queue-v1"


@pytest.mark.asyncio
async def test_real_phone_signatures_nonce_replay_and_cli_tokens_are_account_device_bound(gateway):
    client, core, app = gateway
    alice, key = await phone_login(client, core)
    bob, _ = await phone_login(client, core, "bob")
    path = PREFIX + "/agents"
    raw, headers = signed(alice, key, "GET", path)
    response = await client.get(path, headers=headers)
    assert response.status == 200, await response.text()
    assert (await client.get(path, headers=headers)).status == 401
    _, tampered = signed(alice, key, "GET", path)
    tampered["X-Open-Android-Intelligence-Account"] = "bob"
    assert (await client.get(path, headers=tampered)).status == 401
    cli = await token(client, alice)
    response = await client.get(DEVICE_API + "/devices", headers=cli)
    assert {row["deviceId"] for row in (await response.json())["devices"]} == {alice["deviceId"]}
    response = await client.post(DEVICE_API + "/tokens", json={"username": "alice", "password": "test-only-password", "deviceId": bob["deviceId"]})
    assert response.status == 400
    response = await client.post(DEVICE_API + "/device-requests", json={"accountId": "bob", "capabilityId": "x", "capabilityVersion": "1.0.0", "parameters": {}}, headers={**cli, "Idempotency-Key": "test"})
    assert response.status == 400
    assert (await client.delete(DEVICE_API + "/tokens/current", headers=cli)).status == 200
    assert (await client.get(DEVICE_API + "/devices", headers=cli)).status == 401


@pytest.mark.asyncio
async def test_password_check_does_not_block_devices_or_live_events(gateway, monkeypatch):
    client, core, app = gateway
    bundle, key = await phone_login(client, core)
    binding, _ = await publish(client, core, bundle, key)
    cli = await token(client, bundle)
    _, headers = signed(bundle, key, "GET", PREFIX + "/events")
    stream = await client.ws_connect(PREFIX + "/events", headers=headers)
    started, release = pause_password_checks(monkeypatch)
    login = asyncio.create_task(client.post(DEVICE_API + "/tokens", json={
        "username": "alice", "password": "test-only-password", "deviceId": bundle["deviceId"]}))
    try:
        assert await asyncio.wait_for(started.get(), 2) != threading.get_ident()
        async with asyncio.timeout(2):
            response = await client.get(DEVICE_API + "/devices", headers=cli)
            assert response.status == 200, await response.text()
            response = await client.post(DEVICE_API + "/device-requests", json={
                "capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1}},
                headers={**cli, "Idempotency-Key": "during-password-check"})
            assert response.status == 200, await response.text()
            request_id = (await response.json())["requestId"]
            while True:
                frame = await stream.receive_json()
                if frame["event"] == "device.requested" and json.loads(frame["data"])["payload"]["requestId"] == request_id:
                    break
    finally:
        release.set()
        response = await login
        await stream.close()
    assert response.status == 200, await response.text()


@pytest.mark.asyncio
async def test_password_admission_stays_bounded_after_cancellation_and_keeps_token_identity(gateway, monkeypatch):
    client, core, app = gateway
    alice, _ = await phone_login(client, core)
    bob, _ = await phone_login(client, core, "bob")
    auth = app[AUTH_KEY]
    bodies = [{"username": bundle["accountId"], "password": "test-only-password", "deviceId": bundle["deviceId"]}
              for bundle in (alice, bob, alice, bob)]
    owner = threading.get_ident()
    database_threads = []
    auth.database.set_trace_callback(lambda _: database_threads.append(threading.get_ident()))
    started, release = pause_password_checks(monkeypatch)
    logins = [asyncio.create_task(auth.async_login(body)) for body in bodies]
    try:
        async with asyncio.timeout(2):
            workers = [await started.get() for _ in logins]
            assert all(worker != owner for worker in workers)
            response = await client.post(DEVICE_API + "/tokens", json=bodies[0])
            assert response.status == 429, await response.text()
            assert (await response.json())["error"]["code"] == "RATE_LIMITED"
            logins[0].cancel()
            with pytest.raises(asyncio.CancelledError):
                await logins[0]
            response = await client.post(DEVICE_API + "/tokens", json=bodies[0])
            assert response.status == 429, await response.text()
            assert started.empty()
    finally:
        release.set()
        results = await asyncio.gather(*logins, return_exceptions=True)
    for body, result in zip(bodies[1:], results[1:]):
        assert isinstance(result, dict), result
        response = await client.get(DEVICE_API + "/devices", headers={"Authorization": "Bearer " + result["token"]})
        assert response.status == 200, await response.text()
        assert {row["deviceId"] for row in (await response.json())["devices"]} == {body["deviceId"]}
    assert auth.database.execute("SELECT count(*) FROM core_tokens").fetchone()[0] == len(logins) - 1
    assert set(database_threads) == {owner}


@pytest.mark.asyncio
@pytest.mark.parametrize("change", ["password", "pairing", "grant"])
async def test_token_issuance_rechecks_credentials_and_pairing_after_worker(gateway, monkeypatch, change):
    client, core, app = gateway
    bundle, _ = await phone_login(client, core)
    started, release = pause_password_checks(monkeypatch)
    login = asyncio.create_task(client.post(DEVICE_API + "/tokens", json={
        "username": "alice", "password": "test-only-password", "deviceId": bundle["deviceId"]}))
    try:
        await asyncio.wait_for(started.get(), 2)
        account = core.open_gateway_account("alice")
        try:
            if change == "password":
                account.credentials.set_password("replacement-password")
            elif change == "pairing":
                account.sessions.revoke_device_keys(bundle["deviceId"], "during-password-check")
            else:
                account.bump_grant_revision(bundle["deviceId"], "during-password-check")
        finally:
            account.close()
    finally:
        release.set()
        response = await login
    if change == "grant":
        assert response.status == 200, await response.text()
        cli = {"Authorization": "Bearer " + (await response.json())["token"]}
        assert (await client.get(DEVICE_API + "/devices", headers=cli)).status == 200
        row = app[AUTH_KEY].database.execute("SELECT account_id, device_id, generation, revision FROM core_tokens").fetchone()
        assert tuple(row) == ("alice", bundle["deviceId"], 1, 2)
    else:
        assert response.status in {400, 401}, await response.text()
        expected = "AUTHENTICATION_FAILED" if change == "password" else "PAIRING_REQUIRED"
        assert (await response.json())["error"]["code"] == expected
        assert app[AUTH_KEY].database.execute("SELECT count(*) FROM core_tokens").fetchone()[0] == 0


async def publish(client, core, bundle, key, risk="read"):
    schema = {"type": "object", "additionalProperties": False, "properties": {"limit": {"type": "integer"}}, "required": ["limit"]}
    binding = {"pluginId": "org.example.fixture", "authorKeyId": "sha256:" + "a" * 64, "capabilityId": "org.example.fixture.read", "capabilityVersion": "1.0.0",
               "schemaSha256": "sha256:" + hashlib.sha256(_jcs(schema).encode()).hexdigest(), "schema": schema, "risk": risk}
    result = await request_signed(client, bundle, key, "POST", PREFIX + "/pairings/current/capabilities", {"bindings": [binding], "expectedGrantRevision": 1, "localGrantRevision": 1})
    return binding, result["grantRevision"]


@pytest.mark.asyncio
@pytest.mark.parametrize("channel", ["sse", "websocket"])
async def test_cli_and_signed_phone_share_one_native_queue_claim_result_and_ack(gateway, channel):
    client, core, app = gateway
    bundle, key = await phone_login(client, core)
    binding, revision = await publish(client, core, bundle, key)
    cli = await token(client, bundle)
    _, headers = signed(bundle, key, "GET", PREFIX + "/events")
    if channel == "sse":
        headers["Accept"] = "text/event-stream"
        stream = await client.get(PREFIX + "/events", headers=headers)
    else:
        stream = await client.ws_connect(PREFIX + "/events", headers=headers)
    try:
        body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1}}
        response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**cli, "Idempotency-Key": "invoke_once"})
        assert response.status == 200, await response.text()
        receipt = await response.json()
        assert receipt["state"] == "pending"
        request_id = receipt["requestId"]
        replay = await client.post(DEVICE_API + "/device-requests", json=body, headers={**cli, "Idempotency-Key": "invoke_once"})
        assert (await replay.json())["requestId"] == request_id
        conflict = await client.post(DEVICE_API + "/device-requests", json={**body, "parameters": {"limit": 2}}, headers={**cli, "Idempotency-Key": "invoke_once"})
        assert conflict.status == 409
        async with asyncio.timeout(3):
            while True:
                if channel == "sse":
                    frame = (await stream.content.readuntil(b"\n\n")).decode()
                    if "data: " not in frame:
                        continue
                    event = {**json.loads(frame.split("data: ", 1)[1]), "eventType": next(line[7:] for line in frame.splitlines() if line.startswith("event: "))}
                else:
                    frame = await stream.receive_json()
                    event = {**json.loads(frame["data"]), "eventType": frame["event"]}
                if event.get("eventType") == "device.requested" and event["payload"].get("requestId") == request_id:
                    break
        claim = await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
        await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{request_id}/result", {"claimId": claim["claimId"], "grantRevision": revision, "result": {"outcome": "succeeded", "data": {"records": ["phone-result"]}}})
        response = await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=cli)
        record = await response.json()
        assert record["state"] == "succeeded" and record["result"]["data"] == {"records": ["phone-result"]}
        await client.post(DEVICE_API + f"/device-requests/{request_id}/ack", headers=cli)
        response = await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=cli)
        assert "result" not in await response.json()
        account = core.open_gateway_account("alice")
        try:
            assert account.store.database.execute("SELECT count(*) FROM device_requests").fetchone()[0] == 1
        finally:
            account.close()
    finally:
        if channel == "sse":
            stream.close()
        else:
            await stream.close()


@pytest.mark.asyncio
async def test_cancellation_and_expiry_recover_native_uncertain_outcome(gateway):
    client, core, app = gateway
    bundle, key = await phone_login(client, core)
    binding, _ = await publish(client, core, bundle, key)
    cli = await token(client, bundle)
    body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1}}
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**cli, "Idempotency-Key": "cancel-before"})
    pending = (await response.json())["requestId"]
    response = await client.post(DEVICE_API + f"/device-requests/{pending}/cancel", headers=cli)
    assert (await response.json())["state"] == "cancelled"
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**cli, "Idempotency-Key": "cancel-after"})
    claimed = (await response.json())["requestId"]
    await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{claimed}/claim", {})
    response = await client.post(DEVICE_API + f"/device-requests/{claimed}/cancel", headers=cli)
    assert (await response.json())["state"] == "cancel_requested"
    account = core.open_gateway_account("alice")
    account.store.database.execute("UPDATE device_requests SET expires_at='2000-01-01T00:00:00.000Z' WHERE request_id=?", (claimed,))
    account.close()
    # Reopening account handles models restart; Core retains the authoritative
    # claimed/cancel state rather than re-enqueueing an uncertain side effect.
    response = await client.get(DEVICE_API + f"/device-requests/{claimed}", headers=cli)
    assert (await response.json())["state"] == "outcome_unknown"
    account = core.open_gateway_account("alice")
    account.bump_grant_revision(bundle["deviceId"], "test-revoke")
    account.close()
    assert (await client.get(DEVICE_API + "/devices", headers=cli)).status == 403


@pytest.mark.asyncio
async def test_device_rest_requires_scoped_core_token_and_rejects_v2_phone_token(gateway):
    client, core, app = gateway
    bundle, key = await phone_login(client, core)
    binding, _ = await publish(client, core, bundle, key)
    path = DEVICE_API + "/device-requests"
    body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1}}
    raw, headers = signed(bundle, key, "POST", path, body, "req_signed_once")
    response = await client.post(path, data=raw, headers=headers)
    assert response.status == 401
    # A phone access token cannot be mistaken for an API token, even if its
    # signature headers are stripped by a caller.
    response = await client.post(path, json=body, headers={"Authorization": "Bearer " + bundle["accessToken"], "Idempotency-Key": "test"})
    assert response.status == 401


@pytest.mark.asyncio
async def test_restart_preserves_queue_token_idempotency_and_nonce_replay_fence(tmp_path):
    config = {"storageRoot": str(tmp_path / "data"), "masterKeyFile": str(tmp_path / "secrets/master"), "contractRoot": str(CONTRACT)}
    core = create_core(config)
    account = core.open_gateway_account("alice")
    account.credentials.create_password("test-only-password")
    account.close()
    body = None
    async with TestClient(TestServer(create_app(config, registry=AgentRegistry(), core=core))) as client:
        bundle, key = await phone_login(client, core)
        binding, _ = await publish(client, core, bundle, key)
        cli = await token(client, bundle)
        body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1}}
        response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**cli, "Idempotency-Key": "restart_once"})
        request_id = (await response.json())["requestId"]
        await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
        raw, headers = signed(bundle, key, "GET", PREFIX + "/agents")
        assert (await client.get(PREFIX + "/agents", headers=headers)).status == 200
    # Entire app/Core/Authentication objects are recreated against existing disk.
    restarted = create_app(config, registry=AgentRegistry())
    async with TestClient(TestServer(restarted)) as client:
        assert (await client.get(PREFIX + "/agents", headers=headers)).status == 401
        response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**cli, "Idempotency-Key": "restart_once"})
        record = await response.json()
        assert record["requestId"] == request_id and record["state"] == "claimed"
        account = restarted[CORE_KEY].open_gateway_account("alice")
        account.store.database.execute("UPDATE device_requests SET expires_at='2000-01-01T00:00:00.000Z' WHERE request_id=?", (request_id,))
        account.close()
        response = await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=cli)
        assert (await response.json())["state"] == "outcome_unknown"


@pytest.mark.asyncio
async def test_signature_checks_exact_http_bytes_before_json_parsing_and_nonce_consumption(gateway):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    binding, revision = await publish(client, core, bundle, key)
    path = PREFIX + "/pairings/current/capabilities"
    body = {"bindings": [binding], "expectedGrantRevision": revision, "localGrantRevision": revision}
    raw, headers = signed(bundle, key, "POST", path, body)
    # This JSON parses to the same object; only its raw byte digest differs.
    altered = await client.post(path, data=raw + b" ", headers=headers)
    assert altered.status == 401, await altered.text()
    # A failed signature must not burn a valid request's nonce or idempotency key.
    original = await client.post(path, data=raw, headers=headers)
    assert original.status == 200, await original.text()


@pytest.mark.asyncio
@pytest.mark.parametrize("header", ["Authorization", "X-Open-Android-Intelligence-Account", "X-Open-Android-Intelligence-Nonce", "X-Open-Android-Intelligence-Signature"])
async def test_duplicate_security_headers_fail_closed_even_when_values_are_identical(gateway, header):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    path = PREFIX + "/agents"
    raw, headers = signed(bundle, key, "GET", path)
    duplicated = [*headers.items(), (header, headers[header])]
    response = await client.get(path, headers=duplicated)
    assert response.status == 401, await response.text()


@pytest.mark.asyncio
@pytest.mark.parametrize("channel", ["sse", "websocket"])
async def test_event_reconnect_replays_after_cursor_and_revocation_closes_live_stream(gateway, channel):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)

    async def open_stream(cursor=None):
        path = PREFIX + "/events" + ("?cursor=" + cursor if cursor else "")
        _, headers = signed(bundle, key, "GET", path)
        if cursor:
            headers["Last-Event-ID"] = cursor
        if channel == "sse":
            headers["Accept"] = "text/event-stream"
            response = await client.get(path, headers=headers)
            assert response.status == 200, await response.text()
            return response
        return await client.ws_connect(path, headers=headers)

    def append_notice(marker):
        account = core.open_gateway_account("alice")
        try:
            return account.events.append("gateway.notice", "cor_" + marker, {"noticeCode": marker})
        finally:
            account.close()

    async def read_until(stream, expected_id):
        seen = []
        async with asyncio.timeout(3):
            while True:
                if channel == "sse":
                    frame = (await stream.content.readuntil(b"\n\n")).decode()
                    if "data: " not in frame:
                        continue
                    event = {"eventId": next(line[4:] for line in frame.splitlines() if line.startswith("id: ")), **json.loads(frame.split("data: ", 1)[1])}
                else:
                    frame = await stream.receive_json()
                    event = {"eventId": frame["id"], **json.loads(frame["data"])}
                seen.append(event)
                if event["eventId"] == expected_id:
                    return seen

    stream = await open_stream()
    try:
        first = append_notice("before_disconnect")
        await read_until(stream, first["eventId"])
    finally:
        if channel == "sse":
            stream.close()
        else:
            await stream.close()
    second = append_notice("while_disconnected")
    resumed = await open_stream(first["eventId"])
    try:
        replayed = await read_until(resumed, second["eventId"])
        assert first["eventId"] not in {event["eventId"] for event in replayed}
        assert replayed[-1]["payload"]["noticeCode"] == "while_disconnected"
        account = core.open_gateway_account("alice")
        try:
            account.sessions.revoke_session(bundle["sessionId"], "cor_revoke_stream")
            account.events.append("gateway.notice", "cor_after_revoke", {"noticeCode": "private_after_revoke"})
        finally:
            account.close()
        async with asyncio.timeout(3):
            if channel == "sse":
                assert b"private_after_revoke" not in await resumed.content.read()
                assert resumed.content.at_eof()
            else:
                closed = await resumed.receive()
                assert closed.type in {WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED}
        assert not core.is_device_online("alice", bundle["deviceId"], bundle["pairingGeneration"])
    finally:
        if channel == "sse":
            resumed.close()
        else:
            await resumed.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("channel", ["sse", "ws"])
async def test_slow_subscriber_overflow_closes_before_a_newer_cursor_can_skip_events(gateway, monkeypatch, channel):
    """Exercise the real transport with one blocked write and a bounded queue."""
    from aiohttp import web
    from oai_core.gateway import transport

    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    monkeypatch.setattr(transport, "SSE_QUEUE_SIZE", 2)
    blocked = asyncio.Event()
    release = asyncio.Event()
    original = web.StreamResponse.write if channel == "sse" else web.WebSocketResponse.send_str

    async def slow_write(response, data, *args, **kwargs):
        text = data.decode() if isinstance(data, bytes) else data
        if "slow_first" in text:
            blocked.set()
            await release.wait()
        return await original(response, data, *args, **kwargs)

    monkeypatch.setattr(web.StreamResponse if channel == "sse" else web.WebSocketResponse,
                        "write" if channel == "sse" else "send_str", slow_write)

    async def open_stream(cursor=None):
        path = PREFIX + "/events" + ("?cursor=" + cursor if cursor else "")
        _, headers = signed(bundle, key, "GET", path)
        if channel == "sse":
            headers["Accept"] = "text/event-stream"
            return await client.get(path, headers=headers)
        return await client.ws_connect(path, headers=headers)

    def append(marker):
        account = core.open_gateway_account("alice")
        try:
            return account.events.append("gateway.notice", "cor_" + marker, {"noticeCode": marker})
        finally:
            account.close()

    async def read_event(stream):
        if channel == "sse":
            while True:
                frame = await stream.content.readuntil(b"\n\n")
                if b"data: " in frame:
                    return next(line[4:].decode() for line in frame.splitlines() if line.startswith(b"id: "))
        return (await stream.receive_json())["id"]

    stream = await open_stream()
    try:
        anchor = append("anchor")
        async with asyncio.timeout(2):
            while await read_event(stream) != anchor["eventId"]:
                pass
        events = [append("slow_first")]
        await asyncio.wait_for(blocked.wait(), 2)
        events += [append("burst_" + str(index)) for index in range(6)]
        # Flush the committed event callbacks while the first socket write is blocked.
        await asyncio.sleep(0)
        release.set()
        try:
            async with asyncio.timeout(1):
                if channel == "sse":
                    await stream.content.read()
                    assert stream.content.at_eof()
                else:
                    while (await stream.receive()).type not in {WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED}:
                        pass
        except TimeoutError:
            pytest.fail("overflow kept a broken subscription online instead of reconnecting from its durable cursor")
    finally:
        release.set()
        if channel == "sse":
            stream.close()
        else:
            await stream.close()

    resumed = await open_stream(anchor["eventId"])
    try:
        async with asyncio.timeout(2):
            replayed = [await read_event(resumed) for _ in events]
        assert replayed == [event["eventId"] for event in events]
        assert len(set(replayed)) == len(events)
    finally:
        if channel == "sse":
            resumed.close()
        else:
            await resumed.close()
