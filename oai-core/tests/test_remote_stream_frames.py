"""Exercise actual HTTP line framing, including final snapshots above 64 KiB."""
import json

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.adapters.remote import RemoteSessionAdapter
from oai_core.sessions import AdapterError


@pytest.mark.asyncio
async def test_large_final_snapshot_survives_fragmented_http_transport():
    text = "合成回复" * 50000
    expected = [
        {"type": "generation", "generationId": "g-one"},
        {"type": "delta", "messageId": "reply-one", "text": "合成"},
        {"type": "done", "messageId": "reply-one", "generationId": "g-one", "text": text},
    ]

    async def host(request):
        response = web.StreamResponse(headers={"Content-Type": "application/x-ndjson"})
        await response.prepare(request)
        body = "\n".join(json.dumps(event, ensure_ascii=False) for event in expected).encode() + b"\n"
        for start in range(0, len(body), 4093):
            await response.write(body[start:start + 4093])
        await response.write_eof()
        return response

    app = web.Application()
    app.router.add_post("/oai/session-adapter/v1/sessions/chat/messages", host)
    async with TestClient(TestServer(app)) as client:
        adapter = RemoteSessionAdapter(str(client.make_url("/")).rstrip("/"), "test-only-token-12345678901234567890",
                                       agent_id="synthetic", name="synthetic", kind="remote")
        assert [event async for event in adapter._send("chat", "synthetic prompt")] == expected


@pytest.mark.asyncio
async def test_stream_without_terminal_is_still_unknown_and_never_reposted():
    requests = []

    async def host(request):
        requests.append(request.method)
        return web.Response(text='{"type":"delta","text":"partial"}\n', content_type="application/x-ndjson")

    app = web.Application()
    app.router.add_post("/oai/session-adapter/v1/sessions/chat/messages", host)
    async with TestClient(TestServer(app)) as client:
        adapter = RemoteSessionAdapter(str(client.make_url("/")).rstrip("/"), "test-only-token-12345678901234567890",
                                       agent_id="synthetic", name="synthetic", kind="remote")
        with pytest.raises(AdapterError) as error:
            _ = [event async for event in adapter._send("chat", "synthetic prompt")]
        assert error.value.code == "STREAM_INTERRUPTED"
        assert requests == ["POST"]


@pytest.mark.asyncio
async def test_oversized_frame_has_a_bounded_redacted_error(monkeypatch):
    import traceback
    from oai_core.adapters import remote

    monkeypatch.setattr(remote, "MAX_STREAM_EVENT_BYTES", 1024)
    secret_body = "synthetic-private-prefix" + "x" * 2048

    async def host(request):
        return web.Response(text=json.dumps({"type": "done", "text": secret_body}) + "\n",
                            content_type="application/x-ndjson")

    app = web.Application()
    app.router.add_post("/oai/session-adapter/v1/sessions/chat/messages", host)
    async with TestClient(TestServer(app)) as client:
        adapter = RemoteSessionAdapter(str(client.make_url("/")).rstrip("/"), "test-only-token-12345678901234567890",
                                       agent_id="synthetic", name="synthetic", kind="remote")
        with pytest.raises(AdapterError) as error:
            _ = [event async for event in adapter._send("chat", "synthetic prompt")]
        assert error.value.code == "AGENT_STREAM_TOO_LARGE"
        assert "synthetic-private-prefix" not in "".join(traceback.format_exception(error.value))
