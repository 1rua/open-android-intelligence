"""Real HTTP regressions for probe snapshots, live streams and hop isolation."""
import asyncio
import json
from unittest.mock import patch

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.agent_health import AgentHealth
from oai_core.adapters.remote import RemoteSessionAdapter
from oai_core.auth import Principal
from oai_core.gateway.core import GatewayError
from oai_core.sessions import AdapterError, AgentRegistry, create_agent_routes

PREFIX = '/oai/session-adapter/v1'
TOKEN = 'synthetic-stability-token-12345678901234567890'


def adapter_for(client, **options):
    return RemoteSessionAdapter(str(client.make_url('/')).rstrip('/'), TOKEN,
                               agent_id='probe', name='probe', kind='remote', **options)


@pytest.mark.asyncio
@pytest.mark.parametrize('failure', ['http', 'offline', 'invalid'])
async def test_probe_failure_preserves_the_last_verified_capabilities_and_identity(failure):
    calls = 0

    async def status(request):
        nonlocal calls
        calls += 1
        if calls == 1:
            return web.json_response({'online': True, 'capabilities': {
                'send': True, 'eventSync': True, 'clientMessageIdentity': True,
                'streamHeartbeat': True}})
        if failure == 'http':
            return web.json_response({'code': 'AGENT_UNAVAILABLE'}, status=503)
        if failure == 'offline':
            return web.json_response({'online': False, 'capabilities': {}})
        return web.json_response({'online': True, 'capabilities': 'invalid'})

    app = web.Application()
    app.router.add_get(PREFIX + '/status', status)
    async with TestClient(TestServer(app)) as client:
        adapter = adapter_for(client)
        assert await adapter.status() is True
        snapshot = adapter.descriptor()['capabilities']
        assert await adapter.status() is False
        assert adapter.descriptor()['capabilities'] == snapshot
        assert adapter.forward_client_message_ids is True
        assert adapter._capabilities_verified is True
        assert adapter.health_failure is not None


@pytest.mark.asyncio
@pytest.mark.parametrize('failure', ['html', 'invalid-json', 'online-string', 'online-missing'])
async def test_malformed_success_status_is_protocol_and_immediately_offline(failure):
    calls = 0

    async def status(request):
        nonlocal calls
        calls += 1
        if calls == 1:
            return web.json_response({'online': True, 'capabilities': {'send': True}})
        if failure == 'html':
            return web.Response(text='<html>not the status protocol</html>', content_type='text/html')
        if failure == 'invalid-json':
            return web.Response(text='{"online":', content_type='application/json')
        if failure == 'online-string':
            return web.json_response({'online': 'true', 'capabilities': {'send': True}})
        return web.json_response({'capabilities': {'send': True}})

    app = web.Application()
    app.router.add_get(PREFIX + '/status', status)
    async with TestClient(TestServer(app)) as client:
        adapter = adapter_for(client)
        health = AgentHealth()
        try:
            with patch('oai_core.agent_health.PROBE_INTERVAL', 0):
                assert await health.online('probe', adapter) is True
                assert await health.online('probe', adapter) is False
            assert adapter.health_failure == {
                'kind': 'protocol', 'code': 'HOST_INCOMPATIBLE', 'source': 'agent_bridge'}
        finally:
            await health.close()


@pytest.mark.asyncio
async def test_non_json_failure_status_remains_a_transport_failure():
    calls = 0

    async def status(request):
        nonlocal calls
        calls += 1
        if calls == 1:
            return web.json_response({'online': True, 'capabilities': {'send': True}})
        return web.Response(text='<html>edge service unavailable</html>', content_type='text/html', status=503)

    app = web.Application()
    app.router.add_get(PREFIX + '/status', status)
    async with TestClient(TestServer(app)) as client:
        adapter = adapter_for(client)
        assert await adapter.status() is True
        assert await adapter.status() is False
        assert adapter.health_failure == {
            'kind': 'transient', 'code': 'AGENT_UNAVAILABLE', 'source': 'agent_bridge'}


@pytest.mark.asyncio
async def test_a_continuously_active_stream_can_outlive_the_old_total_budget():
    posts = []
    expected = [{'type': 'delta', 'text': 'x'} for _ in range(12)] + [{'type': 'done'}]

    async def stream(request):
        posts.append(request.method)
        response = web.StreamResponse(headers={'Content-Type': 'application/x-ndjson'})
        await response.prepare(request)
        try:
            for event in expected:
                await response.write((json.dumps(event) + '\n').encode())
                await asyncio.sleep(.01)
        except ConnectionResetError:
            pass
        return response

    app = web.Application()
    app.router.add_post(PREFIX + '/sessions/chat/messages', stream)
    async with TestClient(TestServer(app)) as client:
        adapter = adapter_for(client, stream_timeout_seconds=.06)
        assert [event async for event in adapter._send('chat', 'synthetic')] == expected
        assert posts == ['POST']


@pytest.mark.asyncio
async def test_a_silent_stream_times_out_without_reposting_the_message():
    posts, release = [], asyncio.Event()

    async def stream(request):
        posts.append(request.method)
        response = web.StreamResponse(headers={'Content-Type': 'application/x-ndjson'})
        await response.prepare(request)
        await release.wait()
        return response

    app = web.Application()
    app.router.add_post(PREFIX + '/sessions/chat/messages', stream)
    async with TestClient(TestServer(app)) as client:
        adapter = adapter_for(client, stream_timeout_seconds=.03)
        try:
            with pytest.raises(AdapterError) as failure:
                _ = [event async for event in adapter._send('chat', 'synthetic')]
            assert failure.value.code == 'AGENT_UNAVAILABLE'
            assert failure.value.details['source'] == 'agent_bridge'
            assert failure.value.details['reason'] == 'timeout'
            assert posts == ['POST']
        finally:
            release.set()


@pytest.mark.asyncio
@pytest.mark.parametrize('status', [401, 403])
async def test_upstream_credentials_failure_is_not_a_core_client_authentication_failure(status):
    async def reject(request):
        return web.Response(text='<html>bridge rejected credentials</html>', content_type='text/html', status=status)

    bridge = web.Application()
    bridge.router.add_get(PREFIX + '/sessions', reject)
    async with TestClient(TestServer(bridge)) as remote:
        adapter = adapter_for(remote, capabilities={'list': True})
        registry = AgentRegistry([adapter])
        authentications = []

        async def authenticate(request):
            authentications.append(True)
            return Principal('synthetic-account')

        core = web.Application()
        core.add_routes(create_agent_routes(registry, authenticate, lambda p: True))
        try:
            async with TestClient(TestServer(core)) as client:
                response = await client.get('/open-android-intelligence/v2/agents/probe/sessions')
                value = await response.json()
                assert authentications == [True]
                assert response.status == 502
                assert value['error']['code'] == 'AGENT_UNAVAILABLE'
                assert value['error']['details']['source'] == 'agent_bridge'
                assert value['error']['details']['upstreamStatus'] == status
        finally:
            await registry.close()


@pytest.mark.asyncio
async def test_core_authentication_failure_still_returns_401_without_calling_the_adapter():
    adapter = RemoteSessionAdapter('http://127.0.0.1:1', TOKEN, agent_id='probe', name='probe',
                                   kind='remote', capabilities={'list': True})
    registry = AgentRegistry([adapter])

    async def reject(request):
        raise GatewayError('AUTHENTICATION_REQUIRED')

    core = web.Application()
    core.add_routes(create_agent_routes(registry, reject, lambda p: True))
    try:
        async with TestClient(TestServer(core)) as client:
            response = await client.get('/open-android-intelligence/v2/agents/probe/sessions')
            assert response.status == 401
            assert (await response.json())['error']['code'] == 'AUTHENTICATION_REQUIRED'
    finally:
        await registry.close()


@pytest.mark.asyncio
@pytest.mark.parametrize('bridge_code,reason', [
    ('AUTHENTICATION_REQUIRED', 'authentication_failed'),
    ('AGENT_ACCOUNT_NOT_CONNECTED', 'account_not_connected'),
])
async def test_bridge_authentication_failure_inside_a_stream_does_not_reject_core_credentials(bridge_code, reason):
    async def stream(request):
        return web.Response(text=json.dumps({'type': 'error', 'code': bridge_code}) + '\n',
                            content_type='application/x-ndjson')

    app = web.Application()
    app.router.add_post(PREFIX + '/sessions/chat/messages', stream)
    async with TestClient(TestServer(app)) as client:
        events = [event async for event in adapter_for(client)._send('chat', 'synthetic')]
        assert events[0]['type'] == 'error'
        assert events[0]['code'] == 'AGENT_UNAVAILABLE'
        assert events[0]['details']['source'] == 'agent_bridge'
        assert events[0]['details']['reason'] == reason


@pytest.mark.asyncio
@pytest.mark.parametrize('code', ['SESSION_NOT_BOUND', 'ATTACHMENT_ACCESS_DENIED'])
async def test_business_permission_failure_inside_a_stream_is_not_reclassified(code):
    async def stream(request):
        return web.Response(text=json.dumps({'type': 'error', 'code': code}) + '\n',
                            content_type='application/x-ndjson')

    app = web.Application()
    app.router.add_post(PREFIX + '/sessions/chat/messages', stream)
    async with TestClient(TestServer(app)) as client:
        events = [event async for event in adapter_for(client)._send('chat', 'synthetic')]
        assert events == [{'type': 'error', 'code': code}]


@pytest.mark.asyncio
async def test_broken_diagnostic_sink_does_not_replace_a_bridge_failure(monkeypatch):
    from oai_core.adapters import remote

    def broken(*args, **kwargs):
        raise RuntimeError('synthetic diagnostic sink failed')

    monkeypatch.setattr(remote.logger, 'log', broken)
    async def reject(request):
        return web.json_response({'code': 'AUTHENTICATION_REQUIRED'}, status=401)
    app = web.Application()
    app.router.add_get(PREFIX + '/status', reject)
    async with TestClient(TestServer(app)) as client:
        adapter = adapter_for(client)
        assert await adapter.status() is False
        assert adapter.health_failure['kind'] == 'authentication'


@pytest.mark.asyncio
async def test_stream_connection_budget_also_bounds_dns_resolution(monkeypatch):
    from oai_core.adapters import remote
    import aiohttp.client

    class BlockedResolver:
        async def resolve(self, host, port=0, family=0):
            await asyncio.Event().wait()

        async def close(self):
            pass

    connector = aiohttp.client.TCPConnector
    monkeypatch.setattr(aiohttp.client, 'TCPConnector',
                        lambda *args, **kwargs: connector(*args, resolver=BlockedResolver(), **kwargs))
    monkeypatch.setattr(remote, 'CONNECT_TIMEOUT_SECONDS', 0.03)
    adapter = RemoteSessionAdapter('http://localhost:12345', TOKEN,
        agent_id='probe', name='probe', kind='remote', stream_timeout_seconds=10)
    with pytest.raises(AdapterError) as failure:
        async with asyncio.timeout(.3):
            _ = [event async for event in adapter._send('chat', 'synthetic')]
    assert failure.value.code == 'AGENT_UNAVAILABLE'
    assert failure.value.details['reason'] == 'timeout'
