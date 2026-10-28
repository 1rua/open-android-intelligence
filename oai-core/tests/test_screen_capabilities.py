"""Screen schemas use the existing authenticated queue, CLI and MCP entrypoints.

The signed Android transport below is a protocol peer, not a real device. These
tests prove dispatch and lifecycle compatibility; Android framework execution is
covered separately by the screen modules and remains subject to device testing.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator

from oai_core.cli import main
from oai_core.client import CoreClient
from oai_core.gateway.capability_policy import DeviceCapabilityPolicy
from oai_core.gateway.core import _jcs
from oai_core.mcp import create_mcp
from oai_core.server import DEVICE_API

from test_core_transport import PREFIX, gateway, phone_login, request_signed, token

ROOT = Path(__file__).resolve().parents[2]
SCREEN = (
    ("screen-reader", "read", "read", {"operation": "snapshot", "maxNodes": 20}),
    ("screen-capture", "capture", "read", {"operation": "capture", "maxWidth": 1024}),
    ("screen-control", "control", "write", {"action": "global", "globalAction": "back", "expectedRevision": "rev-1"}),
)


def bindings():
    result = []
    for directory, operation, risk, _ in SCREEN:
        schema = json.loads((ROOT / f"plugins/schemas/screen-{operation}.json").read_text())
        result.append({"pluginId": f"org.openandroidintelligence.{directory}",
                       "authorKeyId": "sha256:" + "a" * 64,
                       "capabilityId": f"org.openandroidintelligence.{directory}.{operation}",
                       "capabilityVersion": "1.0.0", "risk": risk, "schema": schema,
                       "schemaSha256": "sha256:" + hashlib.sha256(_jcs(schema).encode()).hexdigest()})
    return result


async def publish_screen(client, core, bundle, key, ready=True):
    capabilities = bindings()
    inventory = [{**{field: binding[field] for field in ("pluginId", "capabilityId", "capabilityVersion")},
                  "status": "READY" if ready else "LOCAL_GRANT_REQUIRED",
                  "reason": "" if ready else "LOCAL_SCREEN_GRANT_REQUIRED"} for binding in capabilities]
    publication = await request_signed(client, bundle, key, "POST", PREFIX + "/pairings/current/capabilities", {
        "bindings": capabilities if ready else [], "inventory": inventory,
        "expectedGrantRevision": 1, "localGrantRevision": 1,
    })
    return capabilities, publication["grantRevision"]


@pytest.mark.asyncio
async def test_screen_permissions_are_diagnostics_not_callable_grants(gateway):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    capabilities, _ = await publish_screen(client, core, bundle, key, ready=False)
    headers = await token(client, bundle)
    response = await client.get(DEVICE_API + "/capabilities", headers=headers)
    catalog = {item["capabilityId"]: item for item in (await response.json())["capabilities"]}
    for binding, (_, _, _, parameters) in zip(capabilities, SCREEN):
        item = catalog[binding["capabilityId"]]
        assert item["androidStatus"] == "LOCAL_GRANT_REQUIRED"
        assert item["reason"] == "LOCAL_SCREEN_GRANT_REQUIRED"
        assert "schema" not in item and "provider" not in item
        response = await client.post(DEVICE_API + "/device-requests", json={
            "capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": parameters,
        }, headers={**headers, "Idempotency-Key": binding["pluginId"]})
        assert response.status == 403
        assert (await response.json())["error"]["code"] == "CAPABILITY_DENIED"
    account = core.open_gateway_account(bundle["accountId"])
    try:
        assert account.store.database.execute("SELECT count(*) FROM device_requests").fetchone()[0] == 0
    finally:
        account.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("index", range(len(SCREEN)))
async def test_screen_cli_api_and_mcp_share_exact_request_and_signed_result(gateway, tmp_path, capsys, index):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    capabilities, revision = await publish_screen(client, core, bundle, key)
    headers = await token(client, bundle)
    binding = capabilities[index]
    parameters = SCREEN[index][3]
    url = str(client.make_url("/")).rstrip("/")
    config = tmp_path / "screen-client.json"
    config.write_text(json.dumps({"url": url, "token": headers["Authorization"][7:]}))
    config.chmod(0o600)
    core_client = CoreClient(url, headers["Authorization"][7:])
    mcp = create_mcp(core_client)

    assert await asyncio.to_thread(main, ["--client-config", str(config), "capabilities"]) == 0
    catalog = json.loads(capsys.readouterr().out)["capabilities"]
    actual = next(item for item in catalog if item["capabilityId"] == binding["capabilityId"])
    assert actual["schema"] == binding["schema"] and actual["status"] == "READY"
    assert actual["risk"] == SCREEN[index][2]

    # First submit through the real CLI HTTP client, then replay via MCP and API.
    request_key = "screen-once-" + str(index)
    assert await asyncio.to_thread(main, ["--client-config", str(config), "invoke", binding["capabilityId"],
        "--version", "1.0.0", "--parameters", json.dumps(parameters), "--idempotency-key", request_key]) == 0
    receipt = json.loads(capsys.readouterr().out)
    request_id = receipt["requestId"]
    assert receipt["state"] == "pending"
    assert receipt["requiresForegroundConfirmation"] is (SCREEN[index][2] == "write")
    await mcp.call_tool("oai_invoke", {"capability_id": binding["capabilityId"], "capability_version": "1.0.0",
                        "parameters": parameters, "idempotency_key": request_key})
    replay = await asyncio.to_thread(core_client.invoke, binding["capabilityId"], "1.0.0", parameters, request_key)
    assert replay["requestId"] == request_id
    changed = {**parameters, "expectedRevision": "different"} if index == 2 else {**parameters, "operation": "state" if index == 0 else "release"}
    response = await client.post(DEVICE_API + "/device-requests", json={
        "capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": changed,
    }, headers={**headers, "Idempotency-Key": request_key})
    assert response.status == 409 and (await response.json())["error"]["code"] == "IDEMPOTENCY_CONFLICT"

    claim = await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
    same_claim = await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
    assert same_claim["claimId"] == claim["claimId"]
    data = {"revision": "rev-2", "hash": "screen-hash"} if index != 1 else {
        "imageId": "image-fixture", "mimeType": "image/png", "sizeBytes": 7,
        "width": 10, "height": 20, "sha256": hashlib.sha256(b"fixture").hexdigest(),
    }
    await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{request_id}/result", {
        "claimId": claim["claimId"], "grantRevision": revision, "result": {"outcome": "succeeded", "data": {"result": data}},
    })
    record = await asyncio.to_thread(core_client.status, request_id)
    assert record["state"] == "succeeded" and record["result"]["data"]["result"] == data
    await mcp.call_tool("oai_request_status", {"request_id": request_id})
    await mcp.call_tool("oai_ack_result", {"request_id": request_id})
    assert "result" not in await asyncio.to_thread(core_client.status, request_id)
    account = core.open_gateway_account(bundle["accountId"])
    try:
        assert account.store.database.execute("SELECT count(*) FROM device_requests").fetchone()[0] == 1
    finally:
        account.close()


@pytest.mark.asyncio
async def test_screen_pending_cancel_claimed_cancel_failure_and_revocation(gateway):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    capabilities, revision = await publish_screen(client, core, bundle, key)
    headers = await token(client, bundle)
    binding = capabilities[2]
    body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": SCREEN[2][3]}

    async def submit(idempotency):
        response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**headers, "Idempotency-Key": idempotency})
        assert response.status == 200, await response.text()
        return (await response.json())["requestId"]

    pending = await submit("screen-before")
    response = await client.post(DEVICE_API + f"/device-requests/{pending}/cancel", headers=headers)
    assert (await response.json())["state"] == "cancelled"
    claimed = await submit("screen-after")
    claim = await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{claimed}/claim", {})
    response = await client.post(DEVICE_API + f"/device-requests/{claimed}/cancel", headers=headers)
    assert (await response.json())["state"] == "cancel_requested"
    await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{claimed}/result", {
        "claimId": claim["claimId"], "grantRevision": revision,
        "result": {"outcome": "denied", "data": {"code": "SCREEN_SESSION_REQUIRED"}},
    })
    response = await client.get(DEVICE_API + f"/device-requests/{claimed}", headers=headers)
    record = await response.json()
    assert record["state"] == "denied" and record["result"]["data"]["code"] == "SCREEN_SESSION_REQUIRED"

    uncertain = await submit("screen-uncertain")
    await request_signed(client, bundle, key, "POST", PREFIX + f"/device-requests/{uncertain}/claim", {})
    account = core.open_gateway_account(bundle["accountId"])
    try:
        account.store.database.execute("UPDATE device_requests SET expires_at='2000-01-01T00:00:00.000Z' WHERE request_id=?", (uncertain,))
    finally:
        account.close()
    response = await client.get(DEVICE_API + f"/device-requests/{uncertain}", headers=headers)
    assert (await response.json())["state"] == "outcome_unknown"
    account = core.open_gateway_account(bundle["accountId"])
    try:
        account.bump_grant_revision(bundle["deviceId"], "screen-revoke")
    finally:
        account.close()
    assert (await client.get(DEVICE_API + f"/device-requests/{claimed}", headers=headers)).status == 403


@pytest.mark.asyncio
async def test_screen_capability_disabled_explicitly_blocks_new_requests(gateway):
    client, core, _ = gateway
    bundle, key = await phone_login(client, core)
    capabilities, _ = await publish_screen(client, core, bundle, key)
    headers = await token(client, bundle)
    binding = capabilities[0]

    # Explicitly disable this capability via DeviceCapabilityPolicy
    account = core.open_gateway_account(bundle["accountId"])
    try:
        DeviceCapabilityPolicy(account.store).set(binding["capabilityId"], False)
    finally:
        account.close()

    response = await client.post(DEVICE_API + "/device-requests", json={
        "capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": SCREEN[0][3],
    }, headers={**headers, "Idempotency-Key": "test-disabled"})
    assert response.status == 403
    assert (await response.json())["error"]["code"] == "CAPABILITY_DENIED"


@pytest.mark.parametrize("schema_name,parameters", [
    ("read", {"operation": "snapshot", "maxNodes": 513}),
    ("read", {"operation": "snapshot", "maxBytes": 48001}),
    ("read", {"operation": "search"}),
    ("capture", {"operation": "capture", "maxWidth": 2049}),
    ("capture", {"operation": "capture", "maxWidth": 63}),
    ("capture", {"operation": "read", "imageId": "image", "offset": -1}),
    ("control", {"action": "global", "globalAction": "back"}),
    ("control", {"action": "click", "expectedRevision": "r1", "x": 1}),
    ("control", {"action": "click", "expectedRevision": "r1", "x": 1, "y": 2, "nodeId": "node"}),
    ("control", {"action": "setText", "expectedRevision": "r1", "nodeId": "node", "text": "x" * 4097}),
    ("control", {"action": "shell", "expectedRevision": "r1"}),
])
def test_screen_schemas_reject_unbounded_ambiguous_or_unsupported_input(schema_name, parameters):
    schema = json.loads((ROOT / f"plugins/schemas/screen-{schema_name}.json").read_text())
    assert not Draft202012Validator(schema).is_valid(parameters)


def test_independent_screen_packages_cannot_inherit_each_others_primitives():
    for directory, operation, _, _ in SCREEN:
        manifest = json.loads((ROOT / f"plugins/{directory}/manifest.json").read_text())
        assert [primitive["id"] for primitive in manifest["capabilities"]["kernelPrimitives"]] == ["kernel.screen." + operation]
        assert manifest["ui"] == {"settings": [], "cards": []}
        assert manifest["security"]["network"] == []
        assert manifest["security"]["background"]["requested"] is False
