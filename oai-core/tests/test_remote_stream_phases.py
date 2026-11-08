"""通过真实 HTTP 验证响应头等待与流读取空闲预算相互独立。"""
import asyncio
import json

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.adapters import remote
from oai_core.sessions import AdapterError

PREFIX = '/oai/session-adapter/v1'
TOKEN = 'review-stream-phases-token-01234567890123456789'


def adapter_for(client, budget=.6):
    adapter = remote.RemoteSessionAdapter(
        str(client.make_url('/')).rstrip('/'), TOKEN,
        agent_id='review', name='review', kind='remote', stream_timeout_seconds=budget,
    )
    adapter._stream_heartbeat = True
    return adapter


@pytest.mark.asyncio
async def test_delayed_headers_keep_admission_budget_and_post_once(monkeypatch):
    monkeypatch.setattr(remote, 'STREAM_HEARTBEAT_IDLE_SECONDS', .04)
    posts = []

    async def command(request):
        posts.append(await request.json())
        await asyncio.sleep(.15)
        return web.Response(text=json.dumps({'type': 'done', 'text': 'completed'}) + '\n',
                            content_type='application/x-ndjson')

    app = web.Application()
    app.router.add_post(PREFIX + '/sessions/chat/messages', command)
    async with TestClient(TestServer(app)) as client:
        events = [event async for event in adapter_for(client)._send('chat', '/slow-command')]
        assert events == [{'type': 'done', 'text': 'completed'}]
        assert posts == [{'text': '/slow-command'}]


@pytest.mark.asyncio
async def test_admission_budget_remains_bounded_without_reposting(monkeypatch):
    monkeypatch.setattr(remote, 'STREAM_HEARTBEAT_IDLE_SECONDS', .6)
    release = asyncio.Event()
    posts = []

    async def command(request):
        posts.append(await request.json())
        await release.wait()
        return web.Response(text='{"type":"done"}\n', content_type='application/x-ndjson')

    app = web.Application()
    app.router.add_post(PREFIX + '/sessions/chat/messages', command)
    async with TestClient(TestServer(app)) as client:
        try:
            with pytest.raises(AdapterError) as failure:
                async with asyncio.timeout(.4):
                    _ = [event async for event in adapter_for(client, budget=.04)._send('chat', '/slow-command')]
            assert failure.value.code == 'AGENT_UNAVAILABLE'
            assert failure.value.details['reason'] == 'timeout'
            assert len(posts) == 1
        finally:
            release.set()


@pytest.mark.asyncio
async def test_prepared_but_silent_stream_still_uses_heartbeat_idle_budget(monkeypatch):
    monkeypatch.setattr(remote, 'STREAM_HEARTBEAT_IDLE_SECONDS', .04)
    release = asyncio.Event()
    posts = []

    async def stream(request):
        posts.append(await request.json())
        response = web.StreamResponse(headers={'Content-Type': 'application/x-ndjson'})
        await response.prepare(request)
        await release.wait()
        return response

    app = web.Application()
    app.router.add_post(PREFIX + '/sessions/chat/messages', stream)
    async with TestClient(TestServer(app)) as client:
        try:
            with pytest.raises(AdapterError) as failure:
                async with asyncio.timeout(.4):
                    _ = [event async for event in adapter_for(client)._send('chat', 'hello')]
            assert failure.value.details['reason'] == 'timeout'
            assert len(posts) == 1
        finally:
            release.set()


@pytest.mark.asyncio
async def test_partial_frame_activity_resets_idle_and_preserves_unterminated_final_line(monkeypatch):
    monkeypatch.setattr(remote, 'STREAM_HEARTBEAT_IDLE_SECONDS', .06)
    events = [{'type': 'delta', 'text': '持续收到字节'}, {'type': 'done'}]
    data = ('\n' + '\n'.join(json.dumps(event, ensure_ascii=False) for event in events)).encode()

    async def stream(request):
        response = web.StreamResponse(headers={'Content-Type': 'application/x-ndjson'})
        await response.prepare(request)
        for index in range(0, len(data), 6):
            await response.write(data[index:index + 6])
            await asyncio.sleep(.012)
        return response

    app = web.Application()
    app.router.add_post(PREFIX + '/sessions/chat/messages', stream)
    async with TestClient(TestServer(app)) as client:
        assert [event async for event in adapter_for(client)._send('chat', 'hello')] == events


@pytest.mark.asyncio
async def test_idle_budget_is_not_an_absolute_stream_duration(monkeypatch):
    monkeypatch.setattr(remote, 'STREAM_HEARTBEAT_IDLE_SECONDS', .06)
    posts = []

    async def stream(request):
        posts.append(await request.json())
        response = web.StreamResponse(headers={'Content-Type': 'application/x-ndjson'})
        await response.prepare(request)
        for _ in range(12):
            await response.write(b'\n')
            await asyncio.sleep(.012)
        await response.write(b'{"type":"done"}\n')
        return response

    app = web.Application()
    app.router.add_post(PREFIX + '/sessions/chat/messages', stream)
    async with TestClient(TestServer(app)) as client:
        assert [event async for event in adapter_for(client)._send('chat', 'hello')] == [{'type': 'done'}]
        assert len(posts) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize('terminated', [False, True])
async def test_oversized_partial_frame_is_rejected_without_leaking_body(monkeypatch, terminated):
    monkeypatch.setattr(remote, 'MAX_STREAM_EVENT_BYTES', 128)

    async def stream(request):
        data = b'{"type":"delta","text":"' + b'x' * 200 + b'"}'
        return web.Response(body=data + (b'\n' if terminated else b''), content_type='application/x-ndjson')

    app = web.Application()
    app.router.add_post(PREFIX + '/sessions/chat/messages', stream)
    async with TestClient(TestServer(app)) as client:
        with pytest.raises(AdapterError) as failure:
            _ = [event async for event in adapter_for(client)._send('chat', 'hello')]
        assert failure.value.code == 'AGENT_STREAM_TOO_LARGE'
        assert 'xxxxx' not in str(failure.value)


@pytest.mark.asyncio
async def test_non_success_response_body_stall_has_a_finite_budget(monkeypatch):
    monkeypatch.setattr(remote, 'STREAM_HEARTBEAT_IDLE_SECONDS', .04)
    release = asyncio.Event()

    async def stream(request):
        response = web.StreamResponse(status=503, headers={'Content-Type': 'application/json'})
        await response.prepare(request)
        await release.wait()
        return response

    app = web.Application()
    app.router.add_post(PREFIX + '/sessions/chat/messages', stream)
    async with TestClient(TestServer(app)) as client:
        try:
            with pytest.raises(AdapterError) as failure:
                async with asyncio.timeout(.4):
                    _ = [event async for event in adapter_for(client)._send('chat', 'hello')]
            assert failure.value.details['reason'] == 'timeout'
        finally:
            release.set()
