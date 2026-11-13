"""Account access refresh does not change a registered Android receive route."""
import pytest
from oai_core.server import DEVICE_API
from test_account_core_integration import modern_core
from test_android_account_auth import begin, finish
from oai_core.android_auth import AndroidAccountAuthentication
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


@pytest.mark.asyncio
async def test_refresh_keeps_endpoint_but_other_family_cannot_read(modern_core):
    client, core, app, identity, admin = modern_core
    service = AndroidAccountAuthentication(core)
    key = Ed25519PrivateKey.generate()
    facts = begin_for_fixture(core, service, key)
    phone = finish(service, facts, key)
    response = await client.post(DEVICE_API + "/tokens", json={"username": "admin", "password": "synthetic-password"})
    first = await response.json()
    headers = {"Authorization": "Bearer " + first["token"]}
    response = await client.put(DEVICE_API + "/devices/default", json={"deviceId": phone["deviceId"]}, headers=headers)
    assert response.status == 200, await response.text()
    body = {"registrationKey": "receiver", "label": "test", "ttlSeconds": 3600}
    response = await client.post(DEVICE_API + "/endpoints", json=body, headers=headers)
    assert response.status == 200, await response.text()
    route = (await response.json())["route"]
    response = await client.post(DEVICE_API + "/tokens/refresh", json={"refreshCredential": first["refreshCredential"], "clientId": first["clientId"], "operationId": "rotation"})
    rotated = await response.json()
    headers = {"Authorization": "Bearer " + rotated["token"]}
    response = await client.post(DEVICE_API + "/endpoints", json=body, headers=headers)
    assert response.status == 200 and (await response.json())["route"] == route
    response = await client.post(DEVICE_API + "/tokens", json={"username": "admin", "password": "synthetic-password"})
    other = await response.json()
    response = await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers={"Authorization": "Bearer " + other["token"]})
    assert response.status == 403
    assert (await client.post(DEVICE_API + "/tokens/revoke", json={"refreshCredential": first["refreshCredential"], "clientId": first["clientId"]})).status == 200


def begin_for_fixture(core, service, key):
    import json
    import secrets
    negotiation = json.loads((core.contracts.root / "vectors/protocol-negotiation.json").read_text())["cases"][0]["input"]["value"]
    negotiation["negotiationId"] = "neg_" + secrets.token_hex(8)
    negotiation["schemaHashes"]["core"] = core.contracts.core_schema_hash
    result = core._handle_pre_auth({"body": negotiation, "method": "POST", "target": "/open-android-intelligence/v2/negotiate"})
    assert "error" not in result
    from test_android_account_auth import b64
    return service.challenge({"username": "admin", "password": "synthetic-password", "negotiationId": negotiation["negotiationId"],
        "installation": {"installationId": negotiation["client"]["installationId"], "displayName": "phone", "devicePublicKey": b64(key.public_key().public_bytes_raw())}})
