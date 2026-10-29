"""Receipt lookup preserves the native host's identity and account boundary."""
import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.adapters.hermes import HermesRemoteSessionAdapter
from oai_core.auth import Principal


@pytest.mark.asyncio
async def test_remote_generation_query_forwards_encoded_identity_and_account_without_resubmitting():
    requests = []
    receipt = {"generationId": "gen/one?x", "clientMessageId": "cm_one", "conversationId": "chat",
               "revision": 3, "state": "completed"}

    async def host(request):
        requests.append((request.method, request.path, dict(request.query), request.headers.get("X-OAI-Account")))
        return web.json_response({"generation": receipt})

    app = web.Application()
    app.router.add_route("GET", "/{tail:.*}", host)
    async with TestClient(TestServer(app)) as client:
        adapter = HermesRemoteSessionAdapter(str(client.make_url("/")).rstrip("/"),
                                             "test-only-receipt-token-1234567890123456", {"core-alice": "native-alice"})
        result = await adapter.generation(Principal("core-alice"), "chat", client_message_id="cm_one",
                                          generation_id="gen/one?x", after_revision=2, wait_ms=20000)
        assert result == receipt
        assert requests == [("GET", "/oai/session-adapter/v1/sessions/chat/generation",
                             {"clientMessageId": "cm_one", "generationId": "gen/one?x", "afterRevision": "2", "waitMs": "20000"},
                             "native-alice")]
