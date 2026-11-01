"""Real signed Android -> Core -> credential-owned endpoint -> durable ACK."""
from __future__ import annotations

from datetime import timedelta
import json
from pathlib import Path

import pytest
from aiohttp.test_utils import TestClient, TestServer

from oai_core.gateway.core import _now, iso_millis
from oai_core.server import DEVICE_API, ENDPOINT_KEY, create_app, create_core
from oai_core.sessions import AgentRegistry
from test_core_transport import CONTRACT, PREFIX, gateway, phone_login, publish, request_signed, signed, token

PUSH = PREFIX + "/push"


def event(route, event_id="event_notification_1", payload=None):
    return {"eventId": event_id, "route": route, "eventType": "notification",
            "occurredAt": iso_millis(), "expiresAt": iso_millis(_now() + timedelta(hours=72)),
            "payload": {"text": "sensitive-test-notification"} if payload is None else payload}


async def register(client, headers, registration_key="receiver-main", label="Hermes"):
    response = await client.post(DEVICE_API + "/endpoints", json={"registrationKey": registration_key, "label": label, "ttlSeconds": 3600}, headers=headers)
    assert response.status == 200, await response.text()
    return await response.json()


async def receipt(client, headers, route, delivery_id, event_id):
    return await client.post(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries/{delivery_id}/ack", json={"eventId": event_id}, headers=headers)


@pytest.mark.asyncio
async def test_signed_push_per_endpoint_ack_and_repeat_survive_restart_without_plaintext(gateway, tmp_path):
    client, core, app = gateway
    phone, key = await phone_login(client, core)
    headers = await token(client, phone)
    first = await register(client, headers)
    second = await register(client, headers, "receiver-two", "Same label")
    discovery = await request_signed(client, phone, key, "GET", PUSH + "/endpoints")
    assert {v["route"]["endpointId"] for v in discovery["endpoints"]} == {first["route"]["endpointId"], second["route"]["endpointId"]}
    deliveries = []
    bodies = []
    for descriptor in (first, second):
        body = event(descriptor["route"])
        bodies.append(body)
        delivery = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", body)
        deliveries.append(delivery)
        assert delivery["state"] == "pending" and delivery["acknowledgedAt"] is None
        assert _now(delivery["payloadExpiresAt"]) <= _now() + timedelta(seconds=60)
    route = first["route"]
    premature = await receipt(client, headers, route, deliveries[0]["deliveryId"], bodies[0]["eventId"])
    assert premature.status == 409 and (await premature.json())["error"]["code"] == "DELIVERY_NOT_RECEIVED"
    received = await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=headers)
    actual = (await received.json())["deliveries"]
    assert len(actual) == 1 and actual[0]["payload"] == bodies[0]["payload"]
    ack = await receipt(client, headers, route, deliveries[0]["deliveryId"], bodies[0]["eventId"])
    assert ack.status == 200, await ack.text()
    ack = await ack.json()
    assert ack["state"] == "acknowledged" and ack["acknowledgedAt"]
    assert await (await receipt(client, headers, route, deliveries[0]["deliveryId"], bodies[0]["eventId"])).json() == ack
    assert (await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + deliveries[1]["deliveryId"]))["state"] == "pending"
    service = app[ENDPOINT_KEY]
    row = service.database.execute("SELECT * FROM push_deliveries WHERE delivery_id=?", (ack["deliveryId"],)).fetchone()
    assert row["payload"] == "" and row["payload_bytes"] == 0
    # A separate listener reopened against the same persisted state must return
    # the same Core identity and ACK, without recreating a sensitive payload.
    settings = {"storageRoot": str(core.storage_root), "masterKeyFile": str(tmp_path / "secrets/master"), "contractRoot": str(CONTRACT)}
    reopened = create_app(settings, registry=AgentRegistry(), core=create_core(settings))
    async with TestClient(TestServer(reopened)) as other:
        assert reopened[ENDPOINT_KEY].instance_id == service.instance_id
        repeated = await request_signed(other, phone, key, "POST", PUSH + "/deliveries", bodies[0])
        assert repeated == ack
        conflict_raw, conflict_headers = signed(phone, key, "POST", PUSH + "/deliveries", {**bodies[0], "payload": {"text": "changed"}})
        conflict = await other.post(PUSH + "/deliveries", data=conflict_raw, headers=conflict_headers)
        assert conflict.status == 409
    for path in (Path(core.storage_root) / "push-endpoints.sqlite", Path(core.storage_root) / "push-endpoints.sqlite-wal"):
        if path.exists():
            assert b"sensitive-test-notification" not in path.read_bytes()


@pytest.mark.asyncio
async def test_buffer_expiry_refill_same_event_and_ack_after_payload_expired(gateway, monkeypatch):
    client, core, app = gateway
    phone, key = await phone_login(client, core)
    headers = await token(client, phone)
    route = (await register(client, headers))["route"]
    body = event(route)
    queued = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", body)
    service = app[ENDPOINT_KEY]
    real_now = _now
    instant = real_now() + timedelta(seconds=61)
    monkeypatch.setattr("oai_core.endpoints._now", lambda value=None: instant if value is None else real_now(value))
    status = await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + queued["deliveryId"])
    assert status["state"] == "payload_expired"
    assert service.database.execute("SELECT payload FROM push_deliveries").fetchone()[0] == ""
    refilled = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", body)
    assert refilled["state"] == "pending" and refilled["deliveryId"] == queued["deliveryId"]
    await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=headers)
    instant += timedelta(seconds=61)
    late_ack = await receipt(client, headers, route, queued["deliveryId"], body["eventId"])
    assert late_ack.status == 200, await late_ack.text()
    assert (await late_ack.json())["state"] == "acknowledged"


@pytest.mark.asyncio
async def test_caller_token_cannot_impersonate_endpoint_owner_or_other_account(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    owner = await token(client, phone)
    intruder = await token(client, phone)
    route = (await register(client, owner))["route"]
    response = await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=intruder)
    assert response.status == 403
    bob, bob_key = await phone_login(client, core, "bob")
    bob_token = await token(client, bob)
    assert (await (await client.get(DEVICE_API + "/endpoints", headers=bob_token)).json())["endpoints"] == []
    response = await client.delete(DEVICE_API + f"/endpoints/{route['endpointId']}", headers=bob_token)
    assert response.status == 403
    body = event(route)
    raw, signed_headers = signed(bob, bob_key, "POST", PUSH + "/deliveries", body)
    response = await client.post(PUSH + "/deliveries", data=raw, headers=signed_headers)
    assert response.status == 403
    # Self-reported display labels confer no receive right.
    impostor_route = (await register(client, intruder, "impostor", "Hermes"))["route"]
    assert impostor_route != route


@pytest.mark.asyncio
async def test_authentication_exact_bytes_nonce_and_route_binding(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    owner = await token(client, phone)
    route = (await register(client, owner))["route"]
    assert (await client.get(PUSH + "/endpoints", headers=owner)).status == 401
    assert (await client.get(DEVICE_API + "/endpoints")).status == 401
    body = event(route)
    raw, headers = signed(phone, key, "POST", PUSH + "/deliveries", body)
    invalid = await client.post(PUSH + "/deliveries", data=raw.replace(b"notification", b"sms"), headers=headers)
    assert invalid.status == 401
    accepted = await client.post(PUSH + "/deliveries", data=raw, headers=headers)
    assert accepted.status == 200, await accepted.text()
    assert (await client.post(PUSH + "/deliveries", data=raw, headers=headers)).status == 401
    for field, value in (("coreInstanceId", "core_" + "0" * 32), ("accountScope", "acct_" + "0" * 32), ("generation", 2)):
        altered = event({**route, field: value}, "event_wrong_" + field)
        raw, headers = signed(phone, key, "POST", PUSH + "/deliveries", altered)
        response = await client.post(PUSH + "/deliveries", data=raw, headers=headers)
        assert response.status == 403


@pytest.mark.asyncio
async def test_endpoint_renewal_replacement_and_android_revocation_release_buffers(gateway):
    client, core, app = gateway
    phone, key = await phone_login(client, core)
    owner = await token(client, phone)
    route = (await register(client, owner))["route"]
    assert (await register(client, owner, label="Renamed display"))["route"] == route
    body = event(route)
    queued = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", body)
    revocation = await request_signed(client, phone, key, "POST", PUSH + "/deliveries/revoke", {"route": route})
    assert revocation == {"route": route, "revoked": True}
    assert (await request_signed(client, phone, key, "POST", PUSH + "/deliveries", body))["state"] == "revoked"
    assert app[ENDPOINT_KEY].database.execute("SELECT payload FROM push_deliveries").fetchone()[0] == ""
    assert (await client.delete(DEVICE_API + f"/endpoints/{route['endpointId']}", headers=owner)).status == 200
    renewed = (await register(client, owner))["route"]
    assert renewed["endpointId"] == route["endpointId"] and renewed["generation"] == route["generation"] + 1
    replacement = (await register(client, await token(client, phone)))["route"]
    assert replacement["generation"] == renewed["generation"] + 1
    assert replacement["coreInstanceId"] == route["coreInstanceId"]
    assert (await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + queued["deliveryId"]))["state"] == "revoked"


@pytest.mark.asyncio
async def test_expired_event_and_bounded_payload_are_not_silently_accepted(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    route = (await register(client, await token(client, phone)))["route"]
    for body, status in ((event(route, payload={"text": "x" * 65536}), 413),
                         ({**event(route), "expiresAt": iso_millis(_now() - timedelta(seconds=1))}, 410)):
        raw, headers = signed(phone, key, "POST", PUSH + "/deliveries", body)
        response = await client.post(PUSH + "/deliveries", data=raw, headers=headers)
        assert response.status == status, await response.text()


@pytest.mark.asyncio
async def test_empty_discovery_has_stable_core_and_authenticated_account_scope(gateway):
    client, core, app = gateway
    alice, key = await phone_login(client, core)
    discovery = await request_signed(client, alice, key, "GET", PUSH + "/endpoints")
    assert discovery == {"coreInstanceId": app[ENDPOINT_KEY].instance_id,
                         "accountScope": app[ENDPOINT_KEY]._scope("alice"), "endpoints": []}
    assert discovery["accountScope"] != alice["accountId"]
    assert await (await client.get(DEVICE_API + "/endpoints", headers=await token(client, alice))).json() == discovery
    bob, bob_key = await phone_login(client, core, "bob")
    other = await request_signed(client, bob, bob_key, "GET", PUSH + "/endpoints")
    assert other["coreInstanceId"] == discovery["coreInstanceId"]
    assert other["accountScope"] != discovery["accountScope"]


@pytest.mark.asyncio
async def test_publication_revision_preserves_route_and_token_but_fences_existing_requests(gateway):
    client, core, app = gateway
    phone, key = await phone_login(client, core)
    binding, revision = await publish(client, core, phone, key)
    owner = await token(client, phone)
    route = (await register(client, owner))["route"]
    invocation = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
                  "caller": {"agentName": "receiver", "purpose": "验证发布版本与访问关系解耦"}}
    original = await (await client.post(DEVICE_API + "/device-requests", json=invocation,
                      headers={**owner, "Idempotency-Key": "before-publication-change"})).json()
    pending = event(route)
    queued = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", pending)
    publication = await request_signed(client, phone, key, "POST", PREFIX + "/pairings/current/capabilities",
        {"bindings": [binding], "expectedGrantRevision": revision, "localGrantRevision": 2})
    assert publication["grantRevision"] == revision + 1
    discovery = await request_signed(client, phone, key, "GET", PUSH + "/endpoints")
    assert discovery["endpoints"][0]["route"] == route
    assert (await register(client, owner))["route"] == route
    received = await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=owner)
    assert received.status == 200 and len((await received.json())["deliveries"]) == 1
    ack = await receipt(client, owner, route, queued["deliveryId"], pending["eventId"])
    assert ack.status == 200 and (await ack.json())["state"] == "acknowledged"
    current = await client.post(DEVICE_API + "/device-requests", json=invocation,
        headers={**owner, "Idempotency-Key": "after-publication-change"})
    assert current.status == 200, await current.text()
    assert (await current.json())["grantRevision"] == revision + 1
    stale = await client.get(DEVICE_API + "/device-requests/" + original["requestId"], headers=owner)
    assert stale.status == 403 and (await stale.json())["error"]["code"] == "GRANT_STALE"
    replay = await client.post(DEVICE_API + "/device-requests", json=invocation,
        headers={**owner, "Idempotency-Key": "before-publication-change"})
    assert replay.status == 403 and (await replay.json())["error"]["code"] == "GRANT_STALE"
    raw, headers = signed(phone, key, "POST", PREFIX + f"/device-requests/{original['requestId']}/claim", {})
    old_claim = await client.post(PREFIX + f"/device-requests/{original['requestId']}/claim", data=raw, headers=headers)
    assert old_claim.status != 200 and (await old_claim.json())["error"]["code"] == "GRANT_STALE"
    assert (await client.delete(DEVICE_API + "/tokens/current", headers=owner)).status == 200
    assert (await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=owner)).status == 401
    assert (await request_signed(client, phone, key, "GET", PUSH + "/endpoints"))["endpoints"] == []


@pytest.mark.asyncio
async def test_lease_and_pairing_revocation_disable_endpoint_and_clear_pending(gateway, monkeypatch):
    client, core, app = gateway
    phone, key = await phone_login(client, core)
    owner = await token(client, phone)
    route = (await register(client, owner))["route"]
    queued = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", event(route))
    service = app[ENDPOINT_KEY]
    service.database.execute("UPDATE push_endpoints SET lease_expires=0")
    assert (await request_signed(client, phone, key, "GET", PUSH + "/endpoints"))["endpoints"] == []
    assert (await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + queued["deliveryId"]))["state"] == "revoked"
    renewed = (await register(client, owner))["route"]
    assert renewed["generation"] == route["generation"] + 1
    queued = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", event(renewed, "new-event"))
    account = core.open_gateway_account("alice")
    try:
        account.revoke_pairing(phone["deviceId"], "test-revoke")
    finally:
        account.close()
    assert (await client.get(DEVICE_API + "/endpoints", headers=owner)).status == 403
    service.sweep()
    row = service.database.execute("SELECT state,payload FROM push_deliveries WHERE delivery_id=?", (queued["deliveryId"],)).fetchone()
    assert tuple(row) == ("revoked", "")


@pytest.mark.asyncio
async def test_full_buffer_returns_retryable_error_and_preserves_original_events(gateway, monkeypatch):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    owner = await token(client, phone)
    route = (await register(client, owner))["route"]
    monkeypatch.setattr("oai_core.endpoints.MAX_ACCOUNT_PENDING", 1)
    original = event(route, "event_first")
    queued = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", original)
    raw, headers = signed(phone, key, "POST", PUSH + "/deliveries", event(route, "event_second"))
    blocked = await client.post(PUSH + "/deliveries", data=raw, headers=headers)
    assert blocked.status == 429
    assert (await blocked.json())["error"]["retryable"] is True
    assert (await request_signed(client, phone, key, "POST", PUSH + "/deliveries", original))["deliveryId"] == queued["deliveryId"]
    received = await (await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=owner)).json()
    assert [d["eventId"] for d in received["deliveries"]] == ["event_first"]


@pytest.mark.asyncio
async def test_event_expiry_discards_body_and_ack_remains_terminal_after_expiry(gateway, monkeypatch):
    client, core, app = gateway
    phone, key = await phone_login(client, core)
    owner = await token(client, phone)
    route = (await register(client, owner))["route"]
    bodies = [event(route, "event_expire"), event(route, "event_ack")]
    for body in bodies:
        body["expiresAt"] = iso_millis(_now() + timedelta(seconds=30))
    queued = [await request_signed(client, phone, key, "POST", PUSH + "/deliveries", body) for body in bodies]
    await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=owner)
    ack = await (await receipt(client, owner, route, queued[1]["deliveryId"], bodies[1]["eventId"])).json()
    actual_now = _now
    instant = actual_now() + timedelta(seconds=31)
    monkeypatch.setattr("oai_core.endpoints._now", lambda value=None: instant if value is None else actual_now(value))
    expired = await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + queued[0]["deliveryId"])
    assert expired["state"] == "expired"
    assert (await request_signed(client, phone, key, "POST", PUSH + "/deliveries", bodies[0]))["state"] == "expired"
    assert await request_signed(client, phone, key, "POST", PUSH + "/deliveries", bodies[1]) == ack
    assert app[ENDPOINT_KEY].database.execute("SELECT count(*) FROM push_deliveries WHERE payload!=''").fetchone()[0] == 0


@pytest.mark.asyncio
async def test_expired_endpoint_credential_is_never_kept_alive_by_route_renewal(gateway):
    from oai_core.server import AUTH_KEY

    client, core, app = gateway
    phone, key = await phone_login(client, core)
    owner = await token(client, phone)
    route = (await register(client, owner))["route"]
    queued = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", event(route))
    app[AUTH_KEY].database.execute("UPDATE core_tokens SET expires=0")
    app[AUTH_KEY].database.commit()
    assert (await client.get(DEVICE_API + "/endpoints", headers=owner)).status == 401
    assert (await request_signed(client, phone, key, "GET", PUSH + "/endpoints"))["endpoints"] == []
    assert (await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + queued["deliveryId"]))["state"] == "revoked"


@pytest.mark.asyncio
async def test_selected_revocation_keeps_other_kinds_ranges_routes_and_ack_terminal(gateway):
    client, core, app = gateway
    phone, key = await phone_login(client, core)
    owner = await token(client, phone)
    route = (await register(client, owner))["route"]
    other_route = (await register(client, owner, "other-receiver"))["route"]
    events = {
        "sms_withdrawn": {**event(route, "sms_withdrawn"), "eventType": "sms.received"},
        "notification_kept": {**event(route, "notification_kept"), "eventType": "notification.posted"},
        "sms_range_kept": {**event(route, "sms_range_kept"), "eventType": "sms.received"},
        "ack_consumed": event(route, "ack_consumed"),
        "expired_buffer_withdrawn": event(route, "expired_buffer_withdrawn"),
    }
    queued = {event_id: await request_signed(client, phone, key, "POST", PUSH + "/deliveries", body)
              for event_id, body in events.items()}
    other = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", event(other_route, "sms_withdrawn"))
    await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=owner)
    ack = await (await receipt(client, owner, route, queued["ack_consumed"]["deliveryId"], "ack_consumed")).json()
    service = app[ENDPOINT_KEY]
    service.database.execute("UPDATE push_deliveries SET payload_expires=0 WHERE delivery_id=?", (queued["expired_buffer_withdrawn"]["deliveryId"],))
    expired_buffer = await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + queued["expired_buffer_withdrawn"]["deliveryId"])
    assert expired_buffer["state"] == "payload_expired"
    body = {"route": route, "eventIds": ["sms_withdrawn", "expired_buffer_withdrawn", "ack_consumed", "not_yet_delivered"]}
    rows_before = service.database.execute("SELECT count(*) FROM push_deliveries").fetchone()[0]
    for _ in range(2):
        result = await request_signed(client, phone, key, "POST", PUSH + "/deliveries/revoke", body)
        assert result == {"route": route, "revoked": True}
    assert service.database.execute("SELECT count(*) FROM push_deliveries").fetchone()[0] == rows_before
    for event_id in ("sms_withdrawn", "expired_buffer_withdrawn"):
        row = service.database.execute("SELECT state,payload,payload_bytes FROM push_deliveries WHERE delivery_id=?", (queued[event_id]["deliveryId"],)).fetchone()
        assert tuple(row) == ("revoked", "", 0)
        assert (await request_signed(client, phone, key, "POST", PUSH + "/deliveries", events[event_id]))["state"] == "revoked"
    for event_id in ("notification_kept", "sms_range_kept"):
        current = await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + queued[event_id]["deliveryId"])
        assert current["state"] == "pending"
        assert service.database.execute("SELECT payload FROM push_deliveries WHERE delivery_id=?", (current["deliveryId"],)).fetchone()[0]
    assert (await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + other["deliveryId"]))["state"] == "pending"
    assert await request_signed(client, phone, key, "POST", PUSH + "/deliveries", events["ack_consumed"]) == ack
    received = await (await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=owner)).json()
    assert {item["eventId"] for item in received["deliveries"]} == {"notification_kept", "sms_range_kept"}
    # Whole-route compatibility is deliberate and still leaves ACK terminal.
    await request_signed(client, phone, key, "POST", PUSH + "/deliveries/revoke", {"route": route})
    for event_id in ("notification_kept", "sms_range_kept"):
        assert (await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + queued[event_id]["deliveryId"]))["state"] == "revoked"
    assert await request_signed(client, phone, key, "POST", PUSH + "/deliveries", events["ack_consumed"]) == ack
    assert (await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + other["deliveryId"]))["state"] == "pending"


@pytest.mark.asyncio
async def test_selected_revocation_unknown_ids_and_route_scope_do_not_disclose_other_accounts(gateway):
    client, core, app = gateway
    alice, alice_key = await phone_login(client, core)
    alice_route = (await register(client, await token(client, alice)))["route"]
    bob, bob_key = await phone_login(client, core, "bob")
    bob_route = (await register(client, await token(client, bob)))["route"]
    queued = await request_signed(client, bob, bob_key, "POST", PUSH + "/deliveries", event(bob_route, "bob_private_event"))
    # Neither response shape nor a matched count reveals whether an ID exists
    # under any other account, endpoint or generation.
    for event_id in ("unknown_event", "bob_private_event"):
        response = await request_signed(client, alice, alice_key, "POST", PUSH + "/deliveries/revoke", {"route": alice_route, "eventIds": [event_id]})
        assert response == {"route": alice_route, "revoked": True}
    for invalid_route in (bob_route, {**alice_route, "accountScope": bob_route["accountScope"]},
                          {**alice_route, "coreInstanceId": "core_" + "0" * 32}):
        raw, headers = signed(alice, alice_key, "POST", PUSH + "/deliveries/revoke", {"route": invalid_route, "eventIds": ["bob_private_event"]})
        response = await client.post(PUSH + "/deliveries/revoke", data=raw, headers=headers)
        assert response.status == 403
    wrong_generation = {**bob_route, "generation": bob_route["generation"] + 1}
    await request_signed(client, bob, bob_key, "POST", PUSH + "/deliveries/revoke", {"route": wrong_generation, "eventIds": ["bob_private_event"]})
    assert (await request_signed(client, bob, bob_key, "GET", PUSH + "/deliveries/" + queued["deliveryId"]))["state"] == "pending"
    assert app[ENDPOINT_KEY].database.execute("SELECT count(*) FROM push_deliveries").fetchone()[0] == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("event_ids", [[], ["same", "same"], [""], ["contains space"], ["x" * 129], [42], None,
                                      ["event_" + str(index) for index in range(129)]])
async def test_selected_revocation_rejects_invalid_or_oversized_batches(gateway, event_ids):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    route = (await register(client, await token(client, phone)))["route"]
    raw, headers = signed(phone, key, "POST", PUSH + "/deliveries/revoke", {"route": route, "eventIds": event_ids})
    response = await client.post(PUSH + "/deliveries/revoke", data=raw, headers=headers)
    assert response.status == 400, await response.text()
    assert (await response.json())["error"]["code"] == "SCHEMA_INVALID"


@pytest.mark.asyncio
async def test_selected_revocation_accepts_max_batch_of_unknown_ids_without_creating_delivery_rows(gateway):
    client, core, app = gateway
    phone, key = await phone_login(client, core)
    route = (await register(client, await token(client, phone)))["route"]
    response = await request_signed(client, phone, key, "POST", PUSH + "/deliveries/revoke",
        {"route": route, "eventIds": ["event_" + str(index) for index in range(128)]})
    assert response == {"route": route, "revoked": True}
    assert app[ENDPOINT_KEY].database.execute("SELECT count(*) FROM push_deliveries").fetchone()[0] == 0


@pytest.mark.asyncio
async def test_unknown_event_revocation_survives_restart_and_never_seals_late_content(gateway, tmp_path, monkeypatch):
    from oai_core.gateway.core import AccountStore

    client, core, app = gateway
    phone, key = await phone_login(client, core)
    receiver = await token(client, phone)
    route = (await register(client, receiver))["route"]
    other_route = (await register(client, receiver, "still-allowed-receiver"))["route"]
    body = event(route, "late_revoked_event", {"text": "late-body-must-never-be-retained"})
    revocation = {"route": route, "eventIds": [body["eventId"]]}
    assert await request_signed(client, phone, key, "POST", PUSH + "/deliveries/revoke", revocation) == {"route": route, "revoked": True}
    service = app[ENDPOINT_KEY]
    tombstone = service.database.execute("SELECT * FROM push_revocations WHERE event_id=?", (body["eventId"],)).fetchone()
    assert tombstone is not None
    assert service.database.execute("SELECT count(*) FROM push_deliveries").fetchone()[0] == 0

    settings = {"storageRoot": str(core.storage_root), "masterKeyFile": str(tmp_path / "secrets/master"), "contractRoot": str(CONTRACT)}
    reopened = create_app(settings, registry=AgentRegistry(), core=create_core(settings))
    async with TestClient(TestServer(reopened)) as other:
        original_seal = AccountStore.seal_json

        def refuse_push_content(self, value, purpose):
            if purpose.startswith("push:"):
                raise AssertionError("A revoked event must return before payload encryption or storage")
            return original_seal(self, value, purpose)

        with monkeypatch.context() as guard:
            guard.setattr(AccountStore, "seal_json", refuse_push_content)
            late = await request_signed(other, phone, key, "POST", PUSH + "/deliveries", body)
            assert late == {"deliveryId": tombstone["delivery_id"], "eventId": body["eventId"], "route": route,
                            "state": "revoked", "payloadExpiresAt": None, "acknowledgedAt": None}
            # Repeated cleanup, POST and GET all retain the original metadata.
            await request_signed(other, phone, key, "POST", PUSH + "/deliveries/revoke", revocation)
            assert await request_signed(other, phone, key, "POST", PUSH + "/deliveries", body) == late
            assert await request_signed(other, phone, key, "GET", PUSH + "/deliveries/" + late["deliveryId"]) == late
        assert reopened[ENDPOINT_KEY].database.execute("SELECT count(*) FROM push_deliveries").fetchone()[0] == 0
        received = await other.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=receiver)
        assert (await received.json())["deliveries"] == []
        # A stable event ID alone does not revoke a different selected route.
        allowed = {**body, "route": other_route}
        assert (await request_signed(other, phone, key, "POST", PUSH + "/deliveries", allowed))["state"] == "pending"
        bob, bob_key = await phone_login(other, reopened[ENDPOINT_KEY].core, "bob")
        raw, headers = signed(bob, bob_key, "GET", PUSH + "/deliveries/" + late["deliveryId"])
        denied = await other.get(PUSH + "/deliveries/" + late["deliveryId"], data=raw, headers=headers)
        assert denied.status == 403


@pytest.mark.asyncio
async def test_unknown_revocation_lives_for_max_event_ttl_and_retry_never_shortens_it(gateway, monkeypatch):
    from oai_core.endpoints import MAX_EVENT_SECONDS

    client, core, app = gateway
    phone, key = await phone_login(client, core)
    route = (await register(client, await token(client, phone)))["route"]
    service = app[ENDPOINT_KEY]
    actual_now = _now
    instant = actual_now()
    monkeypatch.setattr("oai_core.endpoints._now", lambda value=None: instant if value is None else actual_now(value))
    body = {"route": route, "eventIds": ["unseen_event"]}
    await request_signed(client, phone, key, "POST", PUSH + "/deliveries/revoke", body)
    initial = service.database.execute("SELECT delivery_id,expires_at FROM push_revocations").fetchone()
    assert initial["expires_at"] == instant.timestamp() + MAX_EVENT_SECONDS
    assert MAX_EVENT_SECONDS == 7 * 86400
    instant += timedelta(seconds=5)
    await request_signed(client, phone, key, "POST", PUSH + "/deliveries/revoke", body)
    refreshed = service.database.execute("SELECT delivery_id,expires_at FROM push_revocations").fetchone()
    assert refreshed["delivery_id"] == initial["delivery_id"]
    assert refreshed["expires_at"] == instant.timestamp() + MAX_EVENT_SECONDS
    instant += timedelta(seconds=MAX_EVENT_SECONDS - 1)
    service.sweep()
    assert service.database.execute("SELECT count(*) FROM push_revocations").fetchone()[0] == 1
    instant += timedelta(seconds=2)
    service.sweep()
    assert service.database.execute("SELECT count(*) FROM push_revocations").fetchone()[0] == 0
    assert service.database.execute("SELECT count(*) FROM push_deliveries").fetchone()[0] == 0
