import asyncio
import json

import pytest
from aiohttp.test_utils import TestClient, TestServer

import oai_hermes_adapter.bridge as bridge
from oai_core.sessions import AdapterError

TOKEN = 'heartbeat-test-token-01234567890123456789'
PATH = '/oai/session-adapter/v1/sessions/test/messages'
HEADERS = {'Authorization': f'Bearer {TOKEN}', 'X-OAI-Account': 'alice'}


class Adapter:
    def __init__(self):
        self.release = asyncio.Event()
        self.finished = asyncio.Event()
        self.closed = asyncio.Event()
        self.host = None
        self.next_cancelled = False

    async def status(self):
        return True

    def descriptor(self):
        return {'capabilities': {}}

    async def send(self, principal, session_id, text, **kwargs):
        if text == 'forbidden':
            raise AdapterError('SESSION_NOT_BOUND', 403)
        if text == 'busy':
            raise AdapterError('SESSION_BUSY', 409)
        async def host():
            await self.release.wait()
            self.finished.set()
        self.host = asyncio.create_task(host())
        try:
            yield {'type': 'delta', 'text': 'start'}
            try:
                await asyncio.shield(self.host)
            except asyncio.CancelledError:
                self.next_cancelled = True
                raise
            yield {'type': 'done'}
            await asyncio.Event().wait()
        finally:
            self.closed.set()


@pytest.mark.asyncio
async def test_real_http_heartbeat_and_terminal(monkeypatch):
    monkeypatch.setattr(bridge, 'STREAM_HEARTBEAT_SECONDS', 0.02, raising=False)
    adapter = Adapter()
    active_waits = set()
    original_wait = asyncio.wait

    async def tracked_wait(*args, **kwargs):
        task = asyncio.current_task()
        active_waits.add(task)
        try:
            return await original_wait(*args, **kwargs)
        finally:
            active_waits.discard(task)

    monkeypatch.setattr(bridge.asyncio, 'wait', tracked_wait)
    async with TestClient(TestServer(bridge.create_hermes_bridge_app(adapter, TOKEN, ['alice'], instance_id='hermes'))) as client:
        status = await client.get('/oai/session-adapter/v1/status', headers=HEADERS)
        assert (await status.json())['capabilities']['streamHeartbeat'] is True
        handshake = await client.post('/oai/session-adapter/v1/handshake', headers=HEADERS, json={'challenge': 'a' * 64})
        assert (await handshake.json())['capabilities']['streamHeartbeat'] is True
        response = await client.post(PATH, headers=HEADERS, json={'text': 'hello'})
        assert json.loads(await response.content.readline())['type'] == 'delta'
        assert await asyncio.wait_for(response.content.readline(), 0.3) == b'\n'
        assert await asyncio.wait_for(response.content.readline(), 0.3) == b'\n'
        assert not adapter.next_cancelled
        adapter.release.set()
        assert json.loads(await asyncio.wait_for(response.content.readline(), 0.3))['type'] == 'done'
        assert await response.content.read() == b''
        assert adapter.closed.is_set()
        assert not active_waits


@pytest.mark.asyncio
async def test_admission_and_disconnect(monkeypatch):
    monkeypatch.setattr(bridge, 'STREAM_HEARTBEAT_SECONDS', 0.02, raising=False)
    adapter = Adapter()
    async with TestClient(TestServer(bridge.create_hermes_bridge_app(adapter, TOKEN, ['alice']))) as client:
        for headers, body, code in [({}, {'text': 'hello'}, 401),
                                    ({**HEADERS, 'X-OAI-Account': 'bob'}, {'text': 'hello'}, 403),
                                    (HEADERS, {'text': ''}, 400), (HEADERS, {'text': 'forbidden'}, 403),
                                    (HEADERS, {'text': 'busy'}, 409)]:
            response = await client.post(PATH, headers=headers, json=body)
            assert response.status == code
            assert response.content_type == 'application/json'
            assert b'\n' not in await response.read()
        response = await client.post(PATH, headers=HEADERS, json={'text': 'hello'})
        await response.content.readline()
        assert await asyncio.wait_for(response.content.readline(), 0.3) == b'\n'
        response.close()
        await asyncio.wait_for(adapter.closed.wait(), 0.4)
        assert not adapter.host.cancelled()
        adapter.release.set()
        await asyncio.wait_for(adapter.finished.wait(), 0.3)
