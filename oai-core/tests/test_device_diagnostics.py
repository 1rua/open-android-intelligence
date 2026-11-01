"""Direct invocation diagnostics come from authenticated Android facts, never grants."""
from __future__ import annotations

import asyncio
import hashlib
import json

import pytest

from oai_core.cli import main
from oai_core.client import CoreClient, CoreClientError, save_client_config
from oai_core.gateway.core import _jcs
from oai_core.mcp import create_mcp
from oai_core.server import DEVICE_API
from test_core_transport import PREFIX, gateway, phone_login, publish, request_signed, token


async def publish_diagnostic(gateway, status="ANDROID_PERMISSION_REQUIRED", reason="READ_SMS_REQUIRED", *, inventory=True):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    schema = {"type": "object", "additionalProperties": False, "properties": {"limit": {"type": "integer"}}, "required": ["limit"]}
    binding = {"pluginId": "org.openandroidintelligence.sms", "authorKeyId": "sha256:" + "a" * 64,
               "capabilityId": "org.openandroidintelligence.sms.query", "capabilityVersion": "1.0.0",
               "schemaSha256": "sha256:" + hashlib.sha256(_jcs(schema).encode()).hexdigest(), "schema": schema, "risk": "read"}
    state = {**{name: binding[name] for name in ("pluginId", "capabilityId", "capabilityVersion")}, "status": status, "reason": reason}
    body = {"bindings": [], "expectedGrantRevision": 1, "localGrantRevision": 1}
    if inventory:
        body["inventory"] = [state]
    revision = await request_signed(client, phone, key, "POST", PREFIX + "/pairings/current/capabilities", body)
    headers = await token(client, phone)
    invocation = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
                  "caller": {"agentName": "diagnostic-test", "purpose": "直接调用时解释能力不可用原因"}}
    return client, core, phone, key, headers, binding, invocation, revision["grantRevision"]


@pytest.mark.asyncio
@pytest.mark.parametrize("status,reason,code,http_status", [
    ("ANDROID_PERMISSION_REQUIRED", "READ_SMS_REQUIRED", "ANDROID_PERMISSION_REQUIRED", 403),
    ("ANDROID_PERMISSION_REQUIRED", "READ_CALL_LOG_REQUIRED", "ANDROID_PERMISSION_REQUIRED", 403),
    ("ANDROID_PERMISSION_REQUIRED", "NOTIFICATION_ACCESS_REQUIRED", "ANDROID_PERMISSION_REQUIRED", 403),
    ("ANDROID_PERMISSION_REQUIRED", "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED", "ANDROID_PERMISSION_REQUIRED", 403),
    ("LOCAL_GRANT_REQUIRED", "LOCAL_CAPABILITY_DISABLED", "LOCAL_CAPABILITY_DISABLED", 403),
    ("PLUGIN_DISABLED", "PLUGIN_DISABLED", "LOCAL_CAPABILITY_DISABLED", 403),
    ("LOCAL_GRANT_REQUIRED", "LOCAL_POLICY_CORRUPTED", "CAPABILITY_UNAVAILABLE", 409),
    ("PROVIDER_UNAVAILABLE", "NOTIFICATION_LISTENER_UNAVAILABLE", "CAPABILITY_UNAVAILABLE", 409),
    ("PROVIDER_NOT_SELECTED", "PROVIDER_NOT_SELECTED", "CAPABILITY_UNAVAILABLE", 409),
])
async def test_direct_invoke_reports_current_android_inventory_without_enqueuing(gateway, status, reason, code, http_status):
    client, core, _, _, headers, _, body, _ = await publish_diagnostic(gateway, status, reason)
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**headers, "Idempotency-Key": "diagnostic-once"})
    assert response.status == http_status, await response.text()
    failure = (await response.json())["error"]
    assert failure["code"] == code
    assert failure["details"] == {"capabilityId": body["capabilityId"], "capabilityVersion": "1.0.0",
                                  "source": "android-inventory", "androidStatus": status, "reason": reason}
    account = core.open_gateway_account("alice")
    try:
        assert account.device_requests.list() == []
    finally:
        account.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("capability,version,code,reason", [
    ("org.openandroidintelligence.notifications.query", "1.0.0", "CAPABILITY_NOT_INSTALLED", "NOT_IN_ANDROID_INVENTORY"),
    ("org.openandroidintelligence.sms.schedule", "1.0.0", "CAPABILITY_UNAVAILABLE", "CAPABILITY_NOT_IMPLEMENTED"),
    ("org.openandroidintelligence.sms.query", "2.0.0", "CAPABILITY_UNAVAILABLE", "CAPABILITY_VERSION_UNAVAILABLE"),
])
async def test_missing_capability_version_and_package_are_distinct(gateway, capability, version, code, reason):
    client, _, _, _, headers, _, body, _ = await publish_diagnostic(gateway)
    body.update({"capabilityId": capability, "capabilityVersion": version})
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**headers, "Idempotency-Key": "missing-entry"})
    failure = (await response.json())["error"]
    assert failure["code"] == code and failure["details"]["reason"] == reason


@pytest.mark.asyncio
@pytest.mark.parametrize("stale", [False, True])
async def test_missing_or_stale_inventory_does_not_claim_permissions_or_installation(gateway, stale):
    client, core, phone, _, headers, _, body, _ = await publish_diagnostic(gateway, inventory=stale)
    if stale:
        account = core.open_gateway_account("alice")
        try:
            account.bump_grant_revision(phone["deviceId"], "stale-publication")
        finally:
            account.close()
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**headers, "Idempotency-Key": "unknown-state"})
    failure = (await response.json())["error"]
    assert failure["code"] == "CAPABILITY_UNAVAILABLE"
    assert failure["details"]["reason"] == ("SYNC_REQUIRED" if stale else "ANDROID_STATE_UNKNOWN")


@pytest.mark.asyncio
async def test_inventory_ready_cannot_create_a_callable_binding(gateway):
    client, core, phone, _, headers, _, body, _ = await publish_diagnostic(gateway)
    account = core.open_gateway_account("alice")
    try:
        publication = account.device_requests.capabilities.publication(phone["deviceId"])
        publication["inventory"][0].update({"status": "READY", "reason": ""})
        # Persisted inconsistent pre-upgrade state cannot stand in for binding.
        account.store.database.execute("UPDATE account_metadata SET value=? WHERE key=?",
            (_jcs(publication), "device-capabilities:" + phone["deviceId"]))
    finally:
        account.close()
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**headers, "Idempotency-Key": "inventory-is-not-grant"})
    assert response.status == 409
    assert (await response.json())["error"]["details"]["reason"] == "SYNC_REQUIRED"
    account = core.open_gateway_account("alice")
    try:
        assert not account.device_requests.list()
    finally:
        account.close()


@pytest.mark.asyncio
async def test_direct_cli_and_mcp_keep_permission_reason_without_capability_preflight(gateway, tmp_path, capsys):
    client, _, _, _, headers, binding, _, _ = await publish_diagnostic(gateway)
    url = str(client.make_url("/")).rstrip("/")
    token_value = headers["Authorization"][7:]
    core_client = CoreClient(url, token_value)
    arguments = {"agent_name": "diagnostic-test", "purpose": "解释权限缺失"}
    with pytest.raises(CoreClientError) as captured:
        await asyncio.to_thread(core_client.invoke, binding["capabilityId"], "1.0.0", {"limit": 1}, "client-diagnostics", **arguments)
    assert captured.value.code == "ANDROID_PERMISSION_REQUIRED"
    assert captured.value.details["reason"] == "READ_SMS_REQUIRED"
    config = tmp_path / "diagnostic-client.json"
    save_client_config(config, {"url": url, "token": token_value})
    exit_code = await asyncio.to_thread(main, ["--client-config", str(config), "invoke", binding["capabilityId"],
        "--version", "1.0.0", "--parameters", '{"limit":1}', "--agent-name", "diagnostic-test", "--purpose", "解释权限缺失"])
    assert exit_code == 1
    cli_error = json.loads(capsys.readouterr().err)["error"]
    assert cli_error["code"] == "ANDROID_PERMISSION_REQUIRED" and cli_error["details"]["reason"] == "READ_SMS_REQUIRED"
    mcp = create_mcp(core_client)
    with pytest.raises(Exception, match="READ_SMS_REQUIRED") as mcp_error:
        await mcp.call_tool("oai_invoke", {"capability_id": binding["capabilityId"], "capability_version": "1.0.0",
            "parameters": {"limit": 1}, "idempotency_key": "mcp-diagnostics", **arguments})
    assert "ANDROID_PERMISSION_REQUIRED" in str(mcp_error.value)


@pytest.mark.asyncio
async def test_offline_is_explicit_but_does_not_disable_risk_allowed_queueing(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    binding, revision = await publish(client, core, phone, key)
    headers = await token(client, phone)
    body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
            "caller": {"agentName": "diagnostic-test", "purpose": "解释离线队列"}}
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**headers, "Idempotency-Key": "offline-read"})
    assert response.status == 200, await response.text()
    pending = await response.json()
    assert pending["state"] == "pending" and pending["details"] == {"reason": "DEVICE_OFFLINE", "queued": True, "source": "core-connection"}
    high = {**binding, "risk": "high-privilege-ephemeral"}
    await request_signed(client, phone, key, "POST", PREFIX + "/pairings/current/capabilities",
        {"bindings": [high], "expectedGrantRevision": revision, "localGrantRevision": 2})
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**headers, "Idempotency-Key": "offline-high"})
    assert response.status == 503, await response.text()
    failure = (await response.json())["error"]
    assert failure["code"] == "DEVICE_OFFLINE" and failure["retryable"] is True
    assert failure["details"]["reason"] == "LIVE_CONNECTION_REQUIRED"


@pytest.mark.asyncio
@pytest.mark.parametrize("reason,outcome,expected,phase", [
    ("LOCAL_USER_REJECTED", "denied", "user_rejected", None),
    ("LOCAL_CONFIRMATION_TIMEOUT", "denied", "timeout", "confirmation"),
    ("DEVICE_REQUEST_TIMEOUT", "failed", "timeout", "execution"),
    ("READ_SMS_REQUIRED", "denied", "READ_SMS_REQUIRED", None),
])
async def test_android_terminal_reason_is_visible_without_inventing_success(gateway, reason, outcome, expected, phase):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    binding, revision = await publish(client, core, phone, key)
    headers = await token(client, phone)
    body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
            "caller": {"agentName": "diagnostic-test", "purpose": "解释真实确认结果"}}
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**headers, "Idempotency-Key": "result-reason"})
    request_id = (await response.json())["requestId"]
    claim = await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
    await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/authorization", {"state": "awaiting_confirmation"})
    status = await (await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=headers)).json()
    assert status["details"] == {"reason": "awaiting_confirmation", "source": "android-progress"}
    result = {"outcome": outcome, "data": {"code": "CAPABILITY_DENIED" if outcome == "denied" else reason, "reason": reason}}
    await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/result",
        {"claimId": claim["claimId"], "grantRevision": revision, "result": result})
    status = await (await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=headers)).json()
    assert status["state"] == outcome and status["result"] == result
    assert status["details"]["reason"] == expected and status["details"]["source"] == "android-result"
    if phase:
        assert status["details"]["phase"] == phase


@pytest.mark.asyncio
@pytest.mark.parametrize("claimed,state", [(False, "expired"), (True, "outcome_unknown")])
async def test_core_deadline_timeout_preserves_uncertain_side_effect_state(gateway, claimed, state):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    binding, _ = await publish(client, core, phone, key)
    headers = await token(client, phone)
    body = {"capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
            "caller": {"agentName": "diagnostic-test", "purpose": "解释请求过期"}}
    response = await client.post(DEVICE_API + "/device-requests", json=body, headers={**headers, "Idempotency-Key": "deadline-reason"})
    request_id = (await response.json())["requestId"]
    if claimed:
        await request_signed(client, phone, key, "POST", PREFIX + f"/device-requests/{request_id}/claim", {})
    account = core.open_gateway_account("alice")
    try:
        account.store.database.execute("UPDATE device_requests SET expires_at='2000-01-01T00:00:00.000Z' WHERE request_id=?", (request_id,))
    finally:
        account.close()
    status = await (await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=headers)).json()
    assert status["state"] == state
    assert status["details"] == {"reason": "timeout", "source": "core-deadline", "outcomeUnknown": claimed}
    assert "result" not in status
