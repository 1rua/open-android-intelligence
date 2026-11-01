"""Declarations are mandatory display facts, never trusted identity or authority."""
from datetime import timedelta

import pytest

from oai_core.gateway.core import _now, iso_millis
from oai_core.server import DEVICE_API
from test_core_transport import PREFIX, gateway, phone_login, publish, request_signed, signed, token


async def device_request(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    binding, revision = await publish(client, core, phone, key)
    bearer = await token(client, phone)
    body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
            "caller": {"agentName": "Unverified Agent", "purpose": "用户确认后读取"}}
    return client, core, phone, key, bearer, body, revision


@pytest.mark.asyncio
@pytest.mark.parametrize("caller", [None, {}, {"agentName": " ", "purpose": "test"}, {"agentName": "x", "purpose": "\n"}, {"agentName": 1, "purpose": "test"}])
async def test_no_caller_is_never_invented(gateway, caller):
    client, _, _, _, bearer, body, _ = await device_request(gateway)
    if caller is None:
        body.pop("caller")
    else:
        body["caller"] = caller
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**bearer, "Idempotency-Key": "no-declaration"})
    assert response.status == 400
    assert (await response.json())["error"]["code"] == "CALLER_DECLARATION_REQUIRED"


@pytest.mark.asyncio
async def test_caller_persists_and_authorization_progress_does_not_approve_or_extend(gateway):
    client, core, phone, key, bearer, body, revision = await device_request(gateway)
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**bearer, "Idempotency-Key": "with-caller"})
    assert response.status == 200, await response.text()
    record = await response.json()
    request_id = record["requestId"]
    assert record["caller"] == body["caller"]
    account = core.open_gateway_account("alice")
    try:
        events = account.events.read_after(None)
        assert next(e for e in events if e["eventType"] == "device.requested")["payload"]["caller"] == body["caller"]
    finally:
        account.close()
    claim = await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
    progress = PREFIX + f"/device-requests/{request_id}/authorization"
    result = await request_signed(client, phone, key, "POST", progress, {"state": "awaiting_confirmation"})
    assert result == {"requestId": request_id, "localAuthorizationState": "awaiting_confirmation"}
    for path in (DEVICE_API + f"/device-requests/{request_id}",):
        status = await (await client.get(path, headers=bearer)).json()
        assert status["state"] == "claimed" and status["localAuthorizationState"] == "awaiting_confirmation"
        assert status["expiresAt"] == record["expiresAt"]
    phone_record = await request_signed(client, phone, key, "GET", PREFIX + f"/device-requests/{request_id}")
    assert phone_record["request"]["caller"] == body["caller"]
    assert phone_record["request"]["localAuthorizationState"] == "awaiting_confirmation"
    await request_signed(client, phone, key, "POST", progress, {"state": "executing"})
    account = core.open_gateway_account("alice")
    try:
        audit = [item for item in account.audit.list() if item["eventType"] == "device.request.authorization.progress"]
        assert [item["subject"]["localState"] for item in audit] == ["awaiting_confirmation", "executing"]
    finally:
        account.close()
    raw, headers = signed(phone, key, "POST", progress, {"state": "awaiting_confirmation"})
    assert (await client.post(progress, data=raw, headers=headers)).status == 400
    await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/result",
        {"claimId": claim["claimId"], "grantRevision": revision, "result": {"outcome": "succeeded", "data": {"records": []}}})
    raw, headers = signed(phone, key, "POST", progress, {"state": "executing"})
    assert (await client.post(progress, data=raw, headers=headers)).status == 400


@pytest.mark.asyncio
async def test_result_ack_before_result_is_rejected_and_repeat_ack_is_durable(gateway):
    client, _, phone, key, bearer, body, revision = await device_request(gateway)
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**bearer, "Idempotency-Key": "ack-order"})
    request_id = (await response.json())["requestId"]
    ack_path = DEVICE_API + f"/device-requests/{request_id}/ack"
    assert (await client.post(ack_path, json={}, headers=bearer)).status == 409
    claim = await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
    assert (await client.post(ack_path, json={}, headers=bearer)).status == 409
    await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/result",
        {"claimId": claim["claimId"], "grantRevision": revision, "result": {"outcome": "denied"}})
    record = await (await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=bearer)).json()
    assert (await client.post(ack_path, json={"resultDigest": "sha256:" + "0" * 64}, headers=bearer)).status == 409
    ack = await (await client.post(ack_path, json={"resultDigest": record["resultDigest"]}, headers=bearer)).json()
    assert ack["acknowledged"] is True and ack["acknowledgedAt"]
    assert await (await client.post(ack_path, json={}, headers=bearer)).json() == ack
    assert "result" not in await (await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=bearer)).json()


@pytest.mark.asyncio
async def test_authorization_progress_refuses_other_device_pending_cancelled_and_fenced(gateway):
    client, core, phone, key, bearer, body, _ = await device_request(gateway)
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**bearer, "Idempotency-Key": "progress-gates"})
    request_id = (await response.json())["requestId"]
    path = PREFIX + f"/device-requests/{request_id}/authorization"
    raw, headers = signed(phone, key, "POST", path, {"state": "executing"})
    assert (await client.post(path, data=raw, headers=headers)).status == 400
    await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
    bob, bob_key = await phone_login(client, core, "bob")
    raw, headers = signed(bob, bob_key, "POST", path, {"state": "executing"})
    assert (await client.post(path, data=raw, headers=headers)).status != 200
    await client.post(DEVICE_API + f"/device-requests/{request_id}/cancel", json={}, headers=bearer)
    raw, headers = signed(phone, key, "POST", path, {"state": "executing"})
    assert (await client.post(path, data=raw, headers=headers)).status == 400
    account = core.open_gateway_account("alice")
    try:
        account.store.database.execute("INSERT INTO account_metadata VALUES (?,?)", ("device-request-policy-revoked:" + request_id, "1"))
    finally:
        account.close()
    raw, headers = signed(phone, key, "POST", path, {"state": "executing"})
    response = await client.post(path, data=raw, headers=headers)
    assert (await response.json())["error"]["code"] == "CAPABILITY_DENIED"


@pytest.mark.asyncio
async def test_online_high_privilege_request_allows_full_confirmation_window(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    binding, revision = await publish(client, core, phone, key, risk="high-privilege-ephemeral")
    bearer = await token(client, phone)
    # A live authenticated event connection is still required for this risk.
    _, headers = signed(phone, key, "GET", PREFIX + "/events")
    stream = await client.ws_connect(PREFIX + "/events", headers=headers)
    try:
        body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
                "caller": {"agentName": "screen-test", "purpose": "等待用户确认屏幕操作"}}
        response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**bearer, "Idempotency-Key": "five-minutes"})
        record = await response.json()
        assert response.status == 200, record
        assert (_now(record["expiresAt"]) - _now(record["createdAt"])).total_seconds() == 360
        request_id = record["requestId"]
        claim = await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
        account = core.open_gateway_account("alice")
        try:
            after_confirmation = _now(record["createdAt"]) + timedelta(seconds=301)
            report = account.device_requests.report_authorization(request_id, phone["deviceId"], 1, revision, "executing", "window-test", after_confirmation)
            assert report["localAuthorizationState"] == "executing"
            completed = account.device_requests.submit_result(request_id, phone["deviceId"], 1, revision, claim["claimId"],
                {"outcome": "succeeded"}, "window-test", after_confirmation + timedelta(seconds=20))
            assert completed["state"] == "succeeded"
            short = account.device_requests.enqueue(request_id="short-deadline", device_id=phone["deviceId"], pairing_generation=1, grant_revision=revision,
                risk="high-privilege-ephemeral", capability={"id": binding["capabilityId"], "version": "1.0.0"},
                provider={"pluginId": binding["pluginId"], "authorKeyId": binding["authorKeyId"]}, parameters={"limit": 1},
                correlation_id="short-deadline", online=True, timeout_seconds=10, caller=body["caller"])
            assert (_now(short["expiresAt"]) - _now(short["createdAt"])).total_seconds() == 10
        finally:
            account.close()
    finally:
        await stream.close()


@pytest.mark.asyncio
async def test_completed_legacy_result_can_be_acknowledged_without_fabricating_a_declaration(gateway):
    client, core, phone, key, bearer, body, revision = await device_request(gateway)
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**bearer, "Idempotency-Key": "old-completed-result"})
    request_id = (await response.json())["requestId"]
    claim = await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
    await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/result",
        {"claimId": claim["claimId"], "grantRevision": revision, "result": {"outcome": "succeeded", "data": {"retained": True}}})
    account = core.open_gateway_account("alice")
    try:
        # Upgrade fixture: only the new metadata columns were absent in the
        # old binary; actual result bytes and the original claim remain intact.
        account.store.database.execute("UPDATE device_requests SET result_digest='',caller_json='' WHERE request_id=?", (request_id,))
    finally:
        account.close()
    status = await (await client.get(DEVICE_API + "/device-requests/" + request_id, headers=bearer)).json()
    assert "caller" not in status and status["result"]["data"] == {"retained": True}
    ack = await client.post(DEVICE_API + f"/device-requests/{request_id}/ack", json={}, headers=bearer)
    assert ack.status == 200, await ack.text()
    assert (await ack.json())["acknowledged"] is True


@pytest.mark.asyncio
async def test_old_schema_hash_is_explicitly_rejected_before_device_connection(gateway):
    import json
    from test_core_transport import CONTRACT

    client, core, _ = gateway
    body = json.loads((CONTRACT / "vectors/protocol-negotiation.json").read_text())["cases"][0]["input"]["value"]
    body["schemaHashes"]["core"] = "sha256:032f580246eb4a214624bded717819b4d5a76191208207edc9e5a4607f5c4d23"
    assert body["schemaHashes"]["core"] != core.contracts.core_schema_hash
    response = await client.post(PREFIX + "/negotiate", json=body)
    assert response.status == 406
    assert (await response.json())["error"]["code"] == "PROTOCOL_INCOMPATIBLE"


@pytest.mark.asyncio
async def test_sms_entries_use_android_binding_risk_without_package_primitive_union(gateway):
    import hashlib
    from oai_core.gateway.core import GatewayError, _jcs

    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    schema = {"type": "object", "additionalProperties": False, "properties": {"limit": {"type": "integer"}}, "required": ["limit"]}
    bindings = [{"pluginId": "org.openandroidintelligence.sms", "authorKeyId": "sha256:" + "a" * 64,
                 "capabilityId": "org.openandroidintelligence.sms." + entry, "capabilityVersion": "1.0.0",
                 "schemaSha256": "sha256:" + hashlib.sha256(_jcs(schema).encode()).hexdigest(), "schema": schema, "risk": risk}
                for entry, risk in (("query", "read"), ("schedule", "write"), ("job-status", "read"), ("cancel", "write"))]
    publication = await request_signed(client, phone, key, "POST", PREFIX + "/pairings/current/capabilities",
        {"bindings": bindings, "expectedGrantRevision": 1, "localGrantRevision": 1})
    bearer = await token(client, phone)
    for binding in bindings:
        body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
                "caller": {"agentName": "untrusted caller", "purpose": "验证SMS单entry风险"}}
        response = await client.post(DEVICE_API + "/device-requests", json=body,
            headers={**bearer, "Idempotency-Key": "entry-" + binding["capabilityId"].rsplit(".", 1)[1]})
        assert response.status == 200, await response.text()
        record = await response.json()
        assert record["risk"] == binding["risk"]
        assert record["requiresForegroundConfirmation"] is (binding["risk"] == "write")
    # Supplying a caller-selected risk is not a supported REST input.
    forged = {**body, "risk": "read"}
    response = await client.post(DEVICE_API + "/device-requests", json=forged,
        headers={**bearer, "Idempotency-Key": "caller-chosen-risk"})
    assert response.status == 400 and (await response.json())["error"]["code"] == "SCHEMA_INVALID"
    account = core.open_gateway_account("alice")
    try:
        with pytest.raises(GatewayError, match="SCHEMA_INVALID"):
            account.device_requests.enqueue(request_id="forged-risk", device_id=phone["deviceId"], pairing_generation=1,
                grant_revision=publication["grantRevision"], risk="read",
                capability={"id": bindings[1]["capabilityId"], "version": "1.0.0"},
                provider={"pluginId": bindings[1]["pluginId"], "authorKeyId": bindings[1]["authorKeyId"]},
                parameters={"limit": 1}, correlation_id="risk-test", caller=body["caller"])
    finally:
        account.close()
