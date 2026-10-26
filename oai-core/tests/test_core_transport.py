"""Real HTTP, Ed25519, account isolation and shared native device state tests."""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import secrets
from datetime import datetime, timezone
from pathlib import Path

import pytest
from aiohttp.test_utils import TestClient, TestServer
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from open_android_intelligence_gateway.capability_policy import DeviceCapabilityPolicy
from open_android_intelligence_gateway.core import _jcs

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


async def publish(client, core, bundle, key, risk="read"):
    schema = {"type": "object", "additionalProperties": False, "properties": {"limit": {"type": "integer"}}, "required": ["limit"]}
    binding = {"pluginId": "org.example.fixture", "authorKeyId": "sha256:" + "a" * 64, "capabilityId": "org.example.fixture.read", "capabilityVersion": "1.0.0",
               "schemaSha256": "sha256:" + hashlib.sha256(_jcs(schema).encode()).hexdigest(), "schema": schema, "risk": risk}
    account = core.open_gateway_account(bundle["accountId"])
    try:
        DeviceCapabilityPolicy(account.store).set(binding["capabilityId"], True)
    finally:
        account.close()
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
