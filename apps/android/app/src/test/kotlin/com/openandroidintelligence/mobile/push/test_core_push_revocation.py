"""Actual Core HTTP ordering contracts; no Android/device acceptance claim.

Reuses the repository's real Core server, signing, isolated account and endpoint
fixtures. The second case protects a concrete HTTP reorder: cleanup arrives
before an already-in-flight event. No Core or shared fixture files are changed.
"""
from __future__ import annotations

from pathlib import Path
import sys

import pytest

_REPOSITORY = next(path for path in Path(__file__).resolve().parents if (path / "gateway-contract").is_dir())
sys.path.insert(0, str(_REPOSITORY / "oai-core/src"))
sys.path.insert(0, str(_REPOSITORY / "oai-core/tests"))

from test_core_transport import DEVICE_API, gateway, phone_login, request_signed, signed, token  # noqa: E402,F401
from test_push_endpoints import PUSH, event, register  # noqa: E402


@pytest.mark.asyncio
async def test_shared_endpoint_sms_cleanup_preserves_notification_body_and_receiver_ack(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    receiver = await token(client, phone)
    route = (await register(client, receiver))["route"]
    sms = event(route, "sms_explicitly_withdrawn", {"kind": "sms", "body": "isolated SMS sample"})
    notification = event(route, "notification_still_allowed", {"kind": "notification", "body": "isolated notification sample"})
    sms_delivery = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", sms)
    notification_delivery = await request_signed(client, phone, key, "POST", PUSH + "/deliveries", notification)
    await request_signed(client, phone, key, "POST", PUSH + "/deliveries/revoke", {
        "route": route, "eventIds": [sms["eventId"]],
    })
    withdrawn = await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + sms_delivery["deliveryId"])
    allowed = await request_signed(client, phone, key, "GET", PUSH + "/deliveries/" + notification_delivery["deliveryId"])
    assert withdrawn["state"] == "revoked"
    assert allowed["state"] == "pending"
    received = await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=receiver)
    records = (await received.json())["deliveries"]
    assert [record["eventId"] for record in records] == [notification["eventId"]]
    assert records[0]["payload"] == notification["payload"]
    consumed = await client.post(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries/{notification_delivery['deliveryId']}/ack",
                                 json={"eventId": notification["eventId"]}, headers=receiver)
    assert consumed.status == 200
    assert (await consumed.json())["state"] == "acknowledged"


@pytest.mark.asyncio
async def test_cleanup_before_a_late_inflight_post_cannot_recreate_withdrawn_body(gateway):
    client, core, _ = gateway
    phone, key = await phone_login(client, core)
    receiver = await token(client, phone)
    route = (await register(client, receiver))["route"]
    body = event(route, "late_event_withdrawn_before_arrival", {"body": "isolated late SMS sample"})
    await request_signed(client, phone, key, "POST", PUSH + "/deliveries/revoke", {
        "route": route, "eventIds": [body["eventId"]],
    })
    raw, headers = signed(phone, key, "POST", PUSH + "/deliveries", body)
    late = await client.post(PUSH + "/deliveries", data=raw, headers=headers)
    response = await late.json()
    assert late.status == 409 or (late.status == 200 and response.get("data", {}).get("state") == "revoked"), \
        "A specifically revoked event ID must not accept an in-flight payload after cleanup ACK"
    received = await client.get(DEVICE_API + f"/endpoints/{route['endpointId']}/deliveries", headers=receiver)
    assert (await received.json())["deliveries"] == []
