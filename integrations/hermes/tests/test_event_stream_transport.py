"""End-to-end event stream over the real aiohttp transport.

The handshake and framing are covered by `test_event_stream.py`; this file runs
the actual server the phone talks to, so the streaming loop, the account
isolation and the response headers are exercised rather than assumed.
"""

import asyncio
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

aiohttp = pytest.importorskip("aiohttp")

from open_android_intelligence_gateway.adapter import (  # noqa: E402
    EVENT_STREAM_PATH,
    OpenAndroidPlatformAdapter,
)
from open_android_intelligence_gateway.admin import (  # noqa: E402
    HostApiCompatibility,
    create_admin_service,
)
from open_android_intelligence_gateway.core import create_gateway_core  # noqa: E402
from open_android_intelligence_gateway.http import create_gateway_exposure  # noqa: E402
from open_android_intelligence_gateway.plugin import GatewayServices  # noqa: E402
from test_support import make_secret_store, make_verified_request, trust_core  # noqa: E402


TEST_HOST_API = HostApiCompatibility(
    "1.0.0", "1.0.0", "0123456789abcdef0123456789abcdef01234567"
)
ACCOUNT_ID = "acct_alice"

_real_create_gateway_core = create_gateway_core


def create_gateway_core(storage_root=None, **options):
    options.setdefault("secret_store", make_secret_store())
    return trust_core(_real_create_gateway_core(storage_root=storage_root, **options))


class _Config:
    def __init__(self, port):
        self.extra = {"port": port, "host": "127.0.0.1", "account_id": ACCOUNT_ID}


def _services(tmp_path, verify_request):
    core = create_gateway_core(storage_root=tmp_path)
    exposure = create_gateway_exposure(
        "host-route", core=core, host_version="1.0.0",
        host_api=TEST_HOST_API, verify_request=verify_request,
    )
    admin = create_admin_service(core=core, host_version="1.0.0", host_api=TEST_HOST_API)
    return GatewayServices(core, admin, exposure), core


def _verifier(target, now=None):
    verified = make_verified_request({
        "context": {
            "accountId": ACCOUNT_ID, "deviceId": "dev_1", "sessionId": "sess_1",
            "requestId": "req_stream", "correlationId": "cor_stream",
            "pairingGeneration": 1, "grantRevision": 1,
        },
        "method": "GET",
        "target": target,
        "now": now,
    })
    return lambda request: verified


def _port(adapter):
    return adapter._site._server.sockets[0].getsockname()[1]


async def _read_event_frame(response, timeout=5.0):
    """Reads until a frame carrying an event id arrives, skipping heartbeat comments."""
    while True:
        raw = await asyncio.wait_for(response.content.readuntil(b"\n\n"), timeout)
        frame = raw.decode("utf-8")
        if frame.lstrip().startswith(":"):
            continue
        return frame


def _id_of(frame):
    return frame.split("\n", 1)[0].removeprefix("id: ")


def _data_of(frame):
    return json.loads(frame.split("data: ", 1)[1])


def test_stream_replays_backlog_is_account_scoped_and_has_no_wildcard_cors(tmp_path):
    async def scenario():
        services, core = _services(tmp_path, None)
        account = core.open_gateway_account(ACCOUNT_ID)
        try:
            backlog = account.events.append(
                "gateway.notice", "cor_backlog", {"noticeCode": "maintenance"},
                "2026-09-13T00:00:00.000Z",
            )
        finally:
            account.close()

        exposure = create_gateway_exposure(
            "host-route", core=core, host_version="1.0.0", host_api=TEST_HOST_API,
            verify_request=_verifier(EVENT_STREAM_PATH, now="2026-09-13T00:00:00.000Z"),
        )
        services = GatewayServices(core, services.admin, exposure)
        adapter = OpenAndroidPlatformAdapter(_Config(0), services)
        assert await adapter.connect() is True
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"http://127.0.0.1:{_port(adapter)}{EVENT_STREAM_PATH}",
                    headers={"Accept": "text/event-stream"},
                ) as response:
                    assert response.status == 200
                    assert response.headers["Content-Type"] == "text/event-stream"
                    assert "Access-Control-Allow-Origin" not in response.headers

                    replayed = await _read_event_frame(response)
                    assert _id_of(replayed) == backlog["eventId"]
                    assert "event: gateway.notice" in replayed
                    assert _data_of(replayed)["payload"] == {"noticeCode": "maintenance"}

                    # An event published for another account must not appear on
                    # this stream: delivery is scoped by account, not broadcast.
                    await adapter._broadcast_sse("acct_other", b"id: evt_leak\nevent: gateway.notice\ndata: {}\n\n")

                    await adapter.complete_message("conv_1", "msg_1", "hello phone")
                    delivered = await _read_event_frame(response)
                    assert _id_of(delivered) != "evt_leak"
                    assert "event: conversation.message.completed" in delivered
                    payload = _data_of(delivered)["payload"]
                    assert payload["messageId"] == "msg_1"
                    assert payload["text"] == "hello phone"
        finally:
            await adapter.disconnect()

    asyncio.run(scenario())


def test_stream_without_a_verifier_answers_401_over_http(tmp_path):
    async def scenario():
        services, _core = _services(tmp_path, None)
        adapter = OpenAndroidPlatformAdapter(_Config(0), services)
        assert await adapter.connect() is True
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"http://127.0.0.1:{_port(adapter)}{EVENT_STREAM_PATH}",
                    headers={"Accept": "text/event-stream"},
                ) as response:
                    assert response.status == 401
                    body = await response.json()
                    assert body["error"]["code"] == "AUTHENTICATION_REQUIRED"
        finally:
            await adapter.disconnect()

    asyncio.run(scenario())
