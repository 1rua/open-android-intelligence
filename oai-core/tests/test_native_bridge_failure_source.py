"""Core's SSE boundary preserves the source of upstream bridge errors."""
import json

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.adapters.remote import RemoteSessionAdapter
from oai_core.auth import Principal
from oai_core.sessions import AgentRegistry, create_agent_routes


@pytest.mark.asyncio
@pytest.mark.parametrize('status', [401, 403])
async def test_message_bridge_authentication_error_retains_safe_hop_details(status):
    posts = []
    async def reject(request):
        posts.append(request.method)
        return web.json_response({'code': 'AUTHENTICATION_REQUIRED', 'details': {'token': 'synthetic-private'}}, status=status)

    bridge = web.Application()
    bridge.router.add_post('/oai/session-adapter/v1/sessions/chat/messages', reject)
    async with TestClient(TestServer(bridge)) as upstream:
        adapter = RemoteSessionAdapter(str(upstream.make_url('/')).rstrip('/'),
            'synthetic-stability-token-12345678901234567890',
            agent_id='probe', name='probe', kind='remote', capabilities={'send': True, 'stream': True})
        registry = AgentRegistry([adapter])
        async def authenticate(request):
            return Principal('synthetic-account')
        core = web.Application()
        core.add_routes(create_agent_routes(registry, authenticate, lambda p: True))
        try:
            async with TestClient(TestServer(core)) as client:
                response = await client.post('/open-android-intelligence/v2/agents/probe/sessions/chat/messages',
                    headers={'Idempotency-Key': 'synthetic-message'}, json={'text': 'synthetic'})
                raw = await response.text()
                events = [json.loads(line[6:]) for line in raw.splitlines() if line.startswith('data: ')]
                assert events == [{'type': 'error', 'code': 'AGENT_UNAVAILABLE', 'details': {
                    'source': 'agent_bridge',
                    'reason': 'authentication_failed' if status == 401 else 'permission_denied',
                    'upstreamStatus': status}}]
                assert 'synthetic-private' not in raw
                assert posts == ['POST']
        finally:
            await registry.close()
