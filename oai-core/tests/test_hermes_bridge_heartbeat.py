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
        self.send_task = None
        self.close_task = None

    async def status(self):
        return True

    def descriptor(self):
        return {'capabilities': {}}

    async def send(self, principal, session_id, text, **kwargs):
        if text == 'forbidden':
            raise AdapterError('SESSION_NOT_BOUND', 403)
        if text == 'busy':
            raise AdapterError('SESSION_BUSY', 409)
        self.send_task = asyncio.current_task()
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
            self.close_task = asyncio.current_task()
            self.closed.set()


class YieldBoundTimeoutAdapter(Adapter):
    """用短时超时复现生产生成器跨 yield 保留超时上下文的任务绑定问题。"""

    def __init__(self):
        super().__init__()
        self.iteration_tasks = set()
        self.close_task = None

    async def send(self, principal, session_id, text, **kwargs):
        self.iteration_tasks.add(asyncio.current_task())
        yield {'type': 'generation'}
        try:
            async with asyncio.timeout(0.08):
                self.iteration_tasks.add(asyncio.current_task())
                yield {'type': 'delta', 'text': 'start'}
                await asyncio.Event().wait()
        except TimeoutError:
            self.iteration_tasks.add(asyncio.current_task())
            yield {'type': 'error', 'code': 'AGENT_TIMEOUT'}
        finally:
            self.close_task = asyncio.current_task()
            self.closed.set()


class DelayedFirstAdapter(Adapter):
    def __init__(self):
        super().__init__()
        self.started = asyncio.Event()

    async def send(self, principal, session_id, text, **kwargs):
        self.started.set()
        await self.release.wait()
        try:
            yield {'type': 'delta', 'text': 'first'}
            yield {'type': 'done'}
        finally:
            self.closed.set()


class MidstreamErrorAdapter(Adapter):
    async def send(self, principal, session_id, text, **kwargs):
        try:
            yield {'type': 'delta', 'text': 'start'}
            raise AdapterError('AGENT_FAILED', 502)
        finally:
            self.closed.set()


class SlowQueueTimeoutAdapter(Adapter):
    """让超时发生在 producer 等待满队列时，而不是生成器内部。"""

    def __init__(self):
        super().__init__()
        self.timeout_converted = False
        self.yielded = 0

    async def send(self, principal, session_id, text, **kwargs):
        self.send_task = asyncio.current_task()
        try:
            async with asyncio.timeout(0.08):
                while True:
                    value = self.yielded
                    self.yielded += 1
                    yield {'type': 'delta', 'text': str(value)}
        except TimeoutError:
            self.timeout_converted = True
            yield {'type': 'error', 'code': 'AGENT_TIMEOUT'}
        finally:
            self.close_task = asyncio.current_task()
            self.closed.set()


class CancelledFirstAdapter(Adapter):
    """首次迭代直接取消，验证首帧等待不会脱离 producer 生命周期。"""

    async def send(self, principal, session_id, text, **kwargs):
        self.send_task = asyncio.current_task()
        try:
            if text == 'unreachable':
                yield {'type': 'delta', 'text': 'unreachable'}
            raise asyncio.CancelledError()
        finally:
            self.close_task = asyncio.current_task()
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
        assert adapter.close_task is adapter.send_task
        assert not active_waits


@pytest.mark.asyncio
async def test_generator_timeout_survives_all_yield_iterations(monkeypatch):
    monkeypatch.setattr(bridge, 'STREAM_HEARTBEAT_SECONDS', 0.02, raising=False)
    adapter = YieldBoundTimeoutAdapter()
    async with TestClient(TestServer(bridge.create_hermes_bridge_app(adapter, TOKEN, ['alice']))) as client:
        response = await client.post(PATH, headers=HEADERS, json={'text': 'hello'})
        assert json.loads(await response.content.readline()) == {'type': 'generation'}
        assert json.loads(await response.content.readline()) == {'type': 'delta', 'text': 'start'}
        try:
            remaining = await asyncio.wait_for(response.content.read(), 0.5)
        except TimeoutError:
            pytest.fail('跨 yield 后短时超时未结束流，说明超时仍绑定在已完成的旧任务上')
        events = [json.loads(line) for line in remaining.splitlines() if line]
        assert events == [{'type': 'error', 'code': 'AGENT_TIMEOUT'}]
        assert adapter.closed.is_set()
        assert len(adapter.iteration_tasks) == 1
        assert adapter.close_task in adapter.iteration_tasks


@pytest.mark.asyncio
async def test_timeout_at_full_queue_drains_frames_then_reports_interruption(monkeypatch):
    monkeypatch.setattr(bridge, 'STREAM_HEARTBEAT_SECONDS', 0.02, raising=False)
    monkeypatch.setattr(bridge, 'STREAM_QUEUE_MAXSIZE', 1, raising=False)
    adapter = SlowQueueTimeoutAdapter()
    first_write_blocked = asyncio.Event()
    release_first_write = asyncio.Event()
    blocked = False
    original_write = bridge.web.StreamResponse.write

    async def gated_write(response, data):
        nonlocal blocked
        result = await original_write(response, data)
        if not blocked and data.startswith(b'{'):
            blocked = True
            first_write_blocked.set()
            await release_first_write.wait()
        return result

    monkeypatch.setattr(bridge.web.StreamResponse, 'write', gated_write)
    async with TestClient(TestServer(bridge.create_hermes_bridge_app(adapter, TOKEN, ['alice']))) as client:
        request = asyncio.create_task(client.post(PATH, headers=HEADERS, json={'text': 'hello'}))
        await asyncio.wait_for(first_write_blocked.wait(), 0.3)
        response = await asyncio.wait_for(request, 0.3)
        await asyncio.wait_for(adapter.closed.wait(), 0.3)
        release_first_write.set()
        try:
            body = await asyncio.wait_for(response.content.read(), 0.5)
        except TimeoutError:
            pytest.fail('producer 在满队列等待时取消后，消费端仍在发送无限心跳')

    events = [json.loads(line) for line in body.splitlines() if line]
    assert [event['text'] for event in events if event.get('type') == 'delta'] == ['0', '1']
    assert events[-1] == {'type': 'error', 'code': 'AGENT_STREAM_INTERRUPTED'}
    assert all(event.get('type') != 'done' for event in events)
    assert not adapter.timeout_converted
    assert adapter.close_task is adapter.send_task


@pytest.mark.asyncio
async def test_cancelled_first_iteration_returns_controlled_http_error(monkeypatch):
    monkeypatch.setattr(bridge, 'STREAM_HEARTBEAT_SECONDS', 0.02, raising=False)
    adapter = CancelledFirstAdapter()
    async with TestClient(TestServer(bridge.create_hermes_bridge_app(adapter, TOKEN, ['alice']))) as client:
        try:
            response = await asyncio.wait_for(client.post(PATH, headers=HEADERS, json={'text': 'hello'}), 0.4)
        except TimeoutError:
            pytest.fail('首次迭代取消后，首帧等待仍挂起')
        assert response.status == 502
        assert await response.json() == {'code': 'AGENT_STREAM_INTERRUPTED'}
    assert adapter.closed.is_set()
    assert adapter.close_task is adapter.send_task


@pytest.mark.asyncio
async def test_first_frame_precedes_headers_and_heartbeat(monkeypatch):
    monkeypatch.setattr(bridge, 'STREAM_HEARTBEAT_SECONDS', 0.01, raising=False)
    adapter = DelayedFirstAdapter()
    async with TestClient(TestServer(bridge.create_hermes_bridge_app(adapter, TOKEN, ['alice']))) as client:
        request = asyncio.create_task(client.post(PATH, headers=HEADERS, json={'text': 'hello'}))
        await asyncio.wait_for(adapter.started.wait(), 0.3)
        await asyncio.sleep(0.06)
        assert not request.done()
        adapter.release.set()
        response = await asyncio.wait_for(request, 0.3)
        assert json.loads(await response.content.readline()) == {'type': 'delta', 'text': 'first'}
        assert json.loads(await response.content.readline()) == {'type': 'done'}
        assert await response.content.read() == b''
        assert adapter.closed.is_set()


@pytest.mark.asyncio
async def test_midstream_adapter_error_is_forwarded_once(monkeypatch):
    monkeypatch.setattr(bridge, 'STREAM_HEARTBEAT_SECONDS', 0.02, raising=False)
    adapter = MidstreamErrorAdapter()
    async with TestClient(TestServer(bridge.create_hermes_bridge_app(adapter, TOKEN, ['alice']))) as client:
        response = await client.post(PATH, headers=HEADERS, json={'text': 'hello'})
        assert json.loads(await response.content.readline()) == {'type': 'delta', 'text': 'start'}
        assert json.loads(await asyncio.wait_for(response.content.readline(), 0.3)) == {
            'type': 'error', 'code': 'AGENT_FAILED'}
        assert await response.content.read() == b''
        assert adapter.closed.is_set()


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
        assert adapter.close_task is adapter.send_task
        assert not adapter.host.cancelled()
        adapter.release.set()
        await asyncio.wait_for(adapter.finished.wait(), 0.3)
