"""Signed pending-ID recovery stays device-scoped and never opens content/history."""
from __future__ import annotations

from datetime import timedelta
import hashlib
import json
import secrets

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from oai_core.gateway.core import ConversationPort, DeviceRequestStore, _jcs, _now, iso_millis
from oai_core.server import DEVICE_API
from test_core_transport import CONTRACT, PREFIX, b64, gateway, phone_login, publish, request_signed, signed, token

PENDING = PREFIX + "/device-requests/pending"


def seed_requests(core, phone, entries):
    """State fixtures deliberately contain undecodable parameters to catch reads."""
    account = core.open_gateway_account(phone["accountId"])
    try:
        generation, revision = account.store.database.execute(
            "SELECT pairing_generation,grant_revision FROM device_keys WHERE device_id=?", (phone["deviceId"],)
        ).fetchone()
        now = _now()
        with account.store.transaction():
            for entry in entries:
                account.store.database.execute(
                    "INSERT INTO device_requests(request_id,device_id,pairing_generation,grant_revision,risk,state,"
                    "capability_json,provider_json,parameters_json,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    (entry["id"], entry.get("deviceId", phone["deviceId"]), entry.get("generation", generation),
                     entry.get("revision", revision), "read", entry.get("state", "pending"), "{}", "{}",
                     "sensitive-parameters-must-not-be-decoded", entry.get("createdAt", iso_millis(now)),
                     entry.get("expiresAt", iso_millis(now + timedelta(hours=1)))),
                )
        return generation, revision
    finally:
        account.close()


async def another_phone(client, core):
    """A second independently signed device under the same legal account."""
    key = Ed25519PrivateKey.generate()
    negotiation = json.loads((CONTRACT / "vectors/protocol-negotiation.json").read_text())["cases"][0]["input"]["value"]
    negotiation["negotiationId"] = "neg_pending_" + secrets.token_hex(6)
    negotiation["client"]["installationId"] = "install_pending_" + secrets.token_hex(6)
    negotiation["schemaHashes"]["core"] = core.contracts.core_schema_hash
    response = await client.post(PREFIX + "/negotiate", json=negotiation)
    assert response.status == 200, await response.text()
    response = await client.post(PREFIX + "/sessions/password", json={
        "negotiationId": negotiation["negotiationId"], "username": "alice", "password": "test-only-password",
        "installation": {"installationId": negotiation["client"]["installationId"], "displayName": "second device",
                         "devicePublicKey": b64(key.public_key().public_bytes_raw())},
    })
    assert response.status == 200, await response.text()
    return (await response.json())["data"], key


@pytest.mark.asyncio
async def test_empty_pending_response_precedes_request_id_route(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    generation, revision = seed_requests(core, phone, [])
    result = await request_signed(client, phone, key, "GET", PENDING)
    assert result == {"deviceId": phone["deviceId"], "pairingGeneration": generation,
                      "grantRevision": revision, "pendingDeviceRequests": []}


@pytest.mark.asyncio
async def test_pending_excludes_terminal_expired_and_other_pairing_without_content_access(gateway, monkeypatch):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    fixed_now = _now()
    entries = [
        {"id": "request_pending", "state": "pending"},
        {"id": "request_claimed", "state": "claimed"},
        {"id": "request_cancel_requested", "state": "cancel_requested"},
        {"id": "request_other_generation", "generation": 99},
        {"id": "request_old_publication", "revision": 0},
    ]
    entries += [{"id": "terminal_" + state, "state": state}
                for state in ("succeeded", "failed", "denied", "cancelled", "expired", "outcome_unknown")]
    entries += [{"id": "expired_" + state, "state": state, "expiresAt": iso_millis(fixed_now - timedelta(seconds=1))}
                for state in ("pending", "claimed", "cancel_requested")]
    entries.append({"id": "exact_deadline", "expiresAt": iso_millis(fixed_now)})
    generation, revision = seed_requests(core, phone, entries)

    def forbidden_read(*args, **kwargs):
        raise AssertionError("Pending-ID discovery must not read conversation or request content")

    monkeypatch.setattr(ConversationPort, "list", forbidden_read)
    monkeypatch.setattr(DeviceRequestStore, "_map", forbidden_read)
    monkeypatch.setattr(DeviceRequestStore, "_row", forbidden_read)
    monkeypatch.setattr("oai_core.gateway.core._request_now", lambda request: fixed_now)
    result = await request_signed(client, phone, key, "GET", PENDING)
    assert set(result) == {"deviceId", "pairingGeneration", "grantRevision", "pendingDeviceRequests"}
    assert result["pairingGeneration"] == generation and result["grantRevision"] == revision
    assert set(result["pendingDeviceRequests"]) == {
        "request_pending", "request_claimed", "request_cancel_requested", "request_old_publication",
    }
    assert "sensitive" not in json.dumps(result)


@pytest.mark.asyncio
async def test_pending_limit_is_128_with_deterministic_creation_and_id_order(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    created = iso_millis(_now() - timedelta(minutes=1))
    seed_requests(core, phone, [{"id": f"request_{index:03d}", "createdAt": created} for index in reversed(range(130))])
    first = await request_signed(client, phone, key, "GET", PENDING)
    second = await request_signed(client, phone, key, "GET", PENDING)
    assert first == second
    assert first["pendingDeviceRequests"] == [f"request_{index:03d}" for index in range(128)]


@pytest.mark.asyncio
async def test_pending_uses_signed_account_device_and_pairing_context(gateway):
    client, core, _ = gateway
    alice, alice_key = await phone_login(client, core)
    second, second_key = await another_phone(client, core)
    bob, bob_key = await phone_login(client, core, "bob")
    assert alice["deviceId"] != second["deviceId"]
    seed_requests(core, alice, [{"id": "alice_private"}, {"id": "old_pairing_private", "generation": 99}])
    seed_requests(core, second, [{"id": "second_device_private"}])
    seed_requests(core, bob, [{"id": "bob_private"}])
    for phone, key, expected in ((alice, alice_key, "alice_private"),
                                 (second, second_key, "second_device_private"), (bob, bob_key, "bob_private")):
        result = await request_signed(client, phone, key, "GET", PENDING)
        assert result["deviceId"] == phone["deviceId"]
        assert result["pendingDeviceRequests"] == [expected]


@pytest.mark.asyncio
async def test_pending_requires_phone_signature_and_rejects_identity_override(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    bearer = await token(client, phone)
    assert (await client.get(PENDING)).status == 401
    assert (await client.get(PENDING, headers=bearer)).status == 401
    _, headers = signed(phone, key, "GET", PENDING)
    headers["X-Open-Android-Intelligence-Device"] = "another-device"
    assert (await client.get(PENDING, headers=headers)).status == 401
    raw, headers = signed(phone, key, "GET", PENDING, {"deviceId": "another-device"})
    response = await client.get(PENDING, data=raw, headers=headers)
    assert response.status != 200
    assert (await response.json())["error"]["code"] == "IDENTITY_OVERRIDE_REJECTED"
    target = PENDING + "?deviceId=another-device"
    raw, headers = signed(phone, key, "GET", target)
    response = await client.get(target, data=raw, headers=headers)
    assert response.status == 400 and (await response.json())["error"]["code"] == "SCHEMA_INVALID"


@pytest.mark.asyncio
async def test_pending_is_id_discovery_only_and_driver_get_claim_keep_legacy_fence(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    binding, _ = await publish(client, core, phone, key)
    bearer = await token(client, phone)
    body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
            "caller": {"agentName": "pending-test", "purpose": "验证恢复请求仍检查旧撤销标记"}}
    response = await client.post(DEVICE_API + "/device-requests", json=body,
                                 headers={**bearer, "Idempotency-Key": "pending-fence"})
    assert response.status == 200, await response.text()
    request_id = (await response.json())["requestId"]
    account = core.open_gateway_account("alice")
    try:
        account.store.database.execute("INSERT INTO account_metadata VALUES (?,?)",
                                       ("device-request-policy-revoked:" + request_id, "1"))
    finally:
        account.close()
    assert (await request_signed(client, phone, key, "GET", PENDING))["pendingDeviceRequests"] == [request_id]
    for method, target, body in (("GET", PREFIX + f"/device-requests/{request_id}", None),
                                 ("POST", PREFIX + f"/device-requests/{request_id}/claim", {})):
        raw, headers = signed(phone, key, method, target, body)
        rejected = await client.request(method, target, data=raw, headers=headers)
        assert rejected.status != 200
        assert (await rejected.json())["error"]["details"]["reason"] == "REQUEST_REVOKED"


@pytest.mark.asyncio
@pytest.mark.parametrize("risk,expected", [("read", "cancelled"), ("write", "outcome_unknown")])
async def test_cancel_after_driver_get_discards_late_success_content_and_replay_cannot_restore(gateway, risk, expected):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    binding, revision = await publish(client, core, phone, key, risk=risk)
    bearer = await token(client, phone)
    invocation = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
                  "caller": {"agentName": "cancel-race-test", "purpose": "验证取消与结果上传竞态"}}
    response = await client.post(DEVICE_API + "/device-requests", json=invocation,
                                 headers={**bearer, "Idempotency-Key": "cancel-upload-race"})
    assert response.status == 200, await response.text()
    request_id = (await response.json())["requestId"]
    claim = await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
    # Android's pre-upload GET saw a legitimate claim; cancellation commits
    # before its success POST. The Core transaction must decide using that row.
    before_cancel = await request_signed(client, phone, key, "GET", PREFIX + f"/device-requests/{request_id}")
    assert before_cancel["request"]["state"] == "claimed"
    cancelled = await client.post(DEVICE_API + f"/device-requests/{request_id}/cancel", headers=bearer)
    assert (await cancelled.json())["state"] == "cancel_requested"
    result_path = PREFIX + f"/device-requests/{request_id}/result"
    success = {"claimId": claim["claimId"], "grantRevision": revision,
               "result": {"outcome": "succeeded", "data": {"secret": "sensitive-late-success-content"}}}

    async def repeat_result():
        raw, headers = signed(phone, key, "POST", result_path, success, request_id="same-late-result-key")
        returned = await client.post(result_path, data=raw, headers=headers)
        assert returned.status == 200, await returned.text()
        return (await returned.json())["data"]

    accepted = await repeat_result()
    assert accepted["deviceRequest"]["state"] == expected
    assert await repeat_result() == accepted
    status = await (await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=bearer)).json()
    assert status["state"] == expected and status["result"] == {"outcome": expected}
    assert status["resultDigest"] == "sha256:" + hashlib.sha256(_jcs({"outcome": expected}).encode()).hexdigest()
    account = core.open_gateway_account("alice")
    try:
        row = account.device_requests._row(request_id)
        assert row["parameters_json"] == ""
        assert account.device_requests.read_result(request_id, claim["claimId"]) == {"outcome": expected}
        replay = account.store.database.execute("SELECT outcome_json FROM idempotency_ledger WHERE device_id=? AND request_id=?",
                                                (phone["deviceId"], "same-late-result-key")).fetchone()
        replay_body = account.store.open_json(replay[0], f"idempotency:{phone['deviceId']}:same-late-result-key")
        assert "sensitive-late-success-content" not in json.dumps(replay_body)
    finally:
        account.close()
    assert (await client.post(DEVICE_API + f"/device-requests/{request_id}/ack", headers=bearer)).status == 200
    assert await repeat_result() == accepted
    assert "result" not in await (await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=bearer)).json()
    account = core.open_gateway_account("alice")
    try:
        assert account.device_requests._row(request_id)["result_json"] == ""
        account.store.database.execute("INSERT INTO account_metadata VALUES (?,?)",
                                       ("device-request-policy-revoked:" + request_id, "1"))
    finally:
        account.close()
    raw, headers = signed(phone, key, "POST", result_path, success, request_id="same-late-result-key")
    fenced_replay = await client.post(result_path, data=raw, headers=headers)
    assert fenced_replay.status != 200
    assert (await fenced_replay.json())["error"]["details"]["reason"] == "REQUEST_REVOKED"


@pytest.mark.asyncio
async def test_http_pending_scan_routes_high_privilege_without_event_stream(gateway, monkeypatch):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    binding, revision = await publish(client, core, phone, key, risk="high-privilege-ephemeral")
    bearer = await token(client, phone)
    clock = [1000.0]
    monkeypatch.setattr("oai_core.gateway.core.monotonic", lambda: clock[0])
    assert not core.is_device_online("alice", phone["deviceId"], 1)
    scan = await request_signed(client, phone, key, "GET", PENDING)
    assert scan["pendingDeviceRequests"] == []
    assert core._online_devices == {}
    assert core.is_device_online("alice", phone["deviceId"], 1)
    assert not core.is_device_online("bob", phone["deviceId"], 1)
    assert not core.is_device_online("alice", "another-device", 1)
    assert not core.is_device_online("alice", phone["deviceId"], 2)
    body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
            "caller": {"agentName": "http-poll-test", "purpose": "验证无事件流设备的短期在线租约"}}
    response = await client.post(DEVICE_API + "/device-requests", json=body,
                                 headers={**bearer, "Idempotency-Key": "http-high-privilege"})
    assert response.status == 200, await response.text()
    record = await response.json()
    request_id = record["requestId"]
    assert record["state"] == "pending"
    assert (_now(record["expiresAt"]) - _now(record["createdAt"])).total_seconds() == 360
    clock[0] += 10
    assert (await request_signed(client, phone, key, "GET", PENDING))["pendingDeviceRequests"] == [request_id]
    claim = await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
    await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/authorization", {"state": "awaiting_confirmation"})
    # Presence expiry fences only still-pending ephemeral work. An already
    # claimed request retains its bounded confirmation/execution lifecycle.
    clock[0] += 31
    assert not core.is_device_online("alice", phone["deviceId"], 1)
    status = await request_signed(client, phone, key, "GET", PREFIX + f"/device-requests/{request_id}")
    assert status["request"]["state"] == "claimed"
    assert status["request"]["localAuthorizationState"] == "awaiting_confirmation"
    await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/result",
        {"claimId": claim["claimId"], "grantRevision": revision, "result": {"outcome": "denied"}})


@pytest.mark.asyncio
async def test_http_presence_renews_for_30_monotonic_seconds_and_stream_presence_is_independent(gateway, monkeypatch):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    clock = [1000.0]
    monkeypatch.setattr("oai_core.gateway.core.monotonic", lambda: clock[0])
    await request_signed(client, phone, key, "GET", PENDING)
    context, deadline = next(iter(core._http_device_activity.values()))
    assert deadline == 1030.0
    clock[0] = 1029.0
    assert core.is_device_online("alice", phone["deviceId"], 1)
    await request_signed(client, phone, key, "GET", PENDING)
    assert next(iter(core._http_device_activity.values()))[1] == 1059.0
    clock[0] = 1059.0
    assert not core.is_device_online("alice", phone["deviceId"], 1)
    assert core._http_device_activity == {}
    # Stream close cannot erase an independent current HTTP lease, nor can an
    # expired HTTP lease hide a still-active authenticated stream.
    await request_signed(client, phone, key, "GET", PENDING)
    core.set_device_online(context, True)
    core.set_device_online(context, False)
    assert core.is_device_online("alice", phone["deviceId"], 1)
    core.set_device_online(context, True)
    clock[0] += 31
    assert core.is_device_online("alice", phone["deviceId"], 1)
    core.set_device_online(context, False)
    assert not core.is_device_online("alice", phone["deviceId"], 1)


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["logout", "session_revoke", "pairing_revoke", "session_expire"])
async def test_http_presence_never_outlives_verified_session_or_pairing(gateway, action):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    await request_signed(client, phone, key, "GET", PENDING)
    assert core.is_device_online("alice", phone["deviceId"], 1)
    if action == "logout":
        await request_signed(client, phone, key, "DELETE", PREFIX + "/sessions/current")
    else:
        account = core.open_gateway_account("alice")
        try:
            if action == "session_revoke":
                account.sessions.revoke_session(phone["sessionId"], "presence-revoke")
            elif action == "pairing_revoke":
                account.revoke_pairing(phone["deviceId"], "presence-unpair")
            else:
                account.store.database.execute("UPDATE access_sessions SET expires_at='2000-01-01T00:00:00.000Z' WHERE session_id=?", (phone["sessionId"],))
        finally:
            account.close()
    assert not core.is_device_online("alice", phone["deviceId"], 1)
    raw, headers = signed(phone, key, "GET", PENDING)
    response = await client.get(PENDING, data=raw, headers=headers)
    assert response.status == 401
    assert not core.is_device_online("alice", phone["deviceId"], 1)


@pytest.mark.asyncio
async def test_core_restart_does_not_restore_http_presence(gateway, tmp_path):
    from oai_core.server import create_core

    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    await request_signed(client, phone, key, "GET", PENDING)
    assert core.is_device_online("alice", phone["deviceId"], 1)
    reopened = create_core({"storageRoot": str(core.storage_root), "masterKeyFile": str(tmp_path / "secrets/master"), "contractRoot": str(CONTRACT)})
    assert reopened._http_device_activity == {}
    assert not reopened.is_device_online("alice", phone["deviceId"], 1)


@pytest.mark.asyncio
@pytest.mark.parametrize("recovery", ["poll", "get", "claim"])
async def test_expired_http_presence_cannot_revive_old_pending_high_privilege(gateway, monkeypatch, recovery):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    binding, _ = await publish(client, core, phone, key, risk="high-privilege-ephemeral")
    bearer = await token(client, phone)
    clock = [1000.0]
    monkeypatch.setattr("oai_core.gateway.core.monotonic", lambda: clock[0])
    await request_signed(client, phone, key, "GET", PENDING)
    body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
            "caller": {"agentName": "http-poll-test", "purpose": "验证在线租约断档不复活高权限请求"}}
    async def invoke(idempotency):
        return await client.post(DEVICE_API + "/device-requests", json=body, headers={**bearer, "Idempotency-Key": idempotency})

    accepted = await invoke("before-lease-gap")
    assert accepted.status == 200, await accepted.text()
    request_id = (await accepted.json())["requestId"]
    clock[0] += 31
    assert not core.is_device_online("alice", phone["deviceId"], 1)
    rejected = await invoke("during-lease-gap")
    assert rejected.status == 503 and (await rejected.json())["error"]["code"] == "DEVICE_OFFLINE"
    if recovery == "poll":
        result = await request_signed(client, phone, key, "GET", PENDING)
        assert result["pendingDeviceRequests"] == []
        assert core.is_device_online("alice", phone["deviceId"], 1)
    elif recovery == "get":
        result = await request_signed(client, phone, key, "GET", PREFIX + f"/device-requests/{request_id}")
        assert result["request"]["state"] == "expired" and result["request"]["parameters"] == {}
    target = PREFIX + f"/device-requests/{request_id}/claim"
    raw, headers = signed(phone, key, "POST", target, {})
    response = await client.post(target, data=raw, headers=headers)
    assert response.status != 200 and (await response.json())["error"]["code"] == "OUTCOME_UNKNOWN"
    old_replay = await invoke("before-lease-gap")
    assert old_replay.status == 200
    assert (await old_replay.json())["state"] == "expired"
    account = core.open_gateway_account("alice")
    try:
        row = account.device_requests._row(request_id)
        assert row["state"] == "expired" and row["parameters_json"] == "" and row["result_json"] == ""
    finally:
        account.close()
    if recovery == "poll":
        current = await invoke("after-new-poll")
        assert current.status == 200 and (await current.json())["state"] == "pending"
