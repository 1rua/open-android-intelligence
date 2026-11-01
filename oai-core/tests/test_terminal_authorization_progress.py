"""Real signed HTTP progress is visible only while a live claim is active."""
from __future__ import annotations

import pytest

from oai_core.server import DEVICE_API
from test_core_transport import PREFIX, gateway, phone_login, publish, request_signed, token


@pytest.mark.asyncio
@pytest.mark.parametrize("progress", ["awaiting_confirmation", "executing"])
@pytest.mark.parametrize("transition", ["denied", "succeeded", "cancel_requested", "cancelled", "expired"])
async def test_claim_progress_disappears_after_rejection_completion_cancellation_or_expiry(gateway, progress, transition):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    binding, revision = await publish(client, core, phone, key)
    bearer = await token(client, phone)
    response = await client.post(DEVICE_API + "/device-requests", json={
        "capabilityId": binding["capabilityId"], "capabilityVersion": "1.0.0", "parameters": {"limit": 1},
        "caller": {"agentName": "terminal-progress-test", "purpose": "验证真实终态不再显示等待用户确认"},
    }, headers={**bearer, "Idempotency-Key": "terminal-progress"})
    assert response.status == 200, await response.text()
    submitted = await response.json()
    request_id = submitted["requestId"]
    assert "localAuthorizationState" not in submitted
    target = PREFIX + f"/device-requests/{request_id}"
    claim = await request_signed(client, phone, key, "POST", target + "/claim", {})
    await request_signed(client, phone, key, "POST", target + "/authorization", {"state": "awaiting_confirmation"})
    if progress == "executing":
        await request_signed(client, phone, key, "POST", target + "/authorization", {"state": "executing"})

    async def core_status():
        response = await client.get(DEVICE_API + f"/device-requests/{request_id}", headers=bearer)
        assert response.status == 200, await response.text()
        return await response.json()

    for active in ((await request_signed(client, phone, key, "GET", target))["request"], await core_status()):
        assert active["state"] == "claimed"
        assert active["localAuthorizationState"] == progress
    if transition in {"cancel_requested", "cancelled"}:
        response = await client.post(DEVICE_API + f"/device-requests/{request_id}/cancel", headers=bearer)
        assert response.status == 200, await response.text()
        cancelling = await response.json()
        assert cancelling["state"] == "cancel_requested"
        assert "localAuthorizationState" not in cancelling
    if transition == "expired":
        account = core.open_gateway_account(phone["accountId"])
        try:
            account.store.database.execute("UPDATE device_requests SET expires_at='2000-01-01T00:00:00.000Z' WHERE request_id=?", (request_id,))
        finally:
            account.close()
        # The signed GET can precede the maintenance sweep. Expired claims must
        # not expose live progress even while their stored state is claimed.
        expired = (await request_signed(client, phone, key, "GET", target))["request"]
        assert "localAuthorizationState" not in expired
    elif transition != "cancel_requested":
        result = {"outcome": transition}
        if transition == "denied":
            result["data"] = {"code": "CAPABILITY_DENIED", "reason": "LOCAL_USER_REJECTED"}
        returned = await request_signed(client, phone, key, "POST", target + "/result",
            {"claimId": claim["claimId"], "grantRevision": revision, "result": result})
        assert returned["deviceRequest"]["state"] == transition
        assert "localAuthorizationState" not in returned["deviceRequest"]
    ended = await core_status()
    assert ended["state"] == ("outcome_unknown" if transition == "expired" else transition)
    assert "localAuthorizationState" not in ended
    assert ended.get("details", {}).get("reason") != "awaiting_confirmation"
    if transition == "denied":
        assert ended["result"]["data"]["reason"] == "LOCAL_USER_REJECTED"
    assert "localAuthorizationState" not in (await request_signed(client, phone, key, "GET", target))["request"]
    account = core.open_gateway_account(phone["accountId"])
    try:
        assert account.device_requests._row(request_id)["local_authorization_state"] == progress
        audit = [item for item in account.audit.list() if item["eventType"] == "device.request.authorization.progress"]
        assert [item["subject"]["localState"] for item in audit] == (
            ["awaiting_confirmation", "executing"] if progress == "executing" else ["awaiting_confirmation"])
    finally:
        account.close()
