"""Native protocol fixture tests; no real Codex home or model account is used."""

import asyncio
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.adapters.codex import CodexSessionAdapter, SOURCE_KINDS
from oai_core.sessions import AdapterError, AgentRegistry, Principal, create_agent_routes


NATIVE_SERVER = r'''
import json, os, sys
from pathlib import Path
root = Path(os.environ['CODEX_HOME'])
(root / 'environment-observed.json').write_text(json.dumps({
    name: os.environ.get(name) for name in ['OAI_DEVICE_TOKEN', 'AUTHORIZATION', 'UNRELATED_SECRET', 'OPENAI_API_KEY']
}))
def emit(value):
    print(json.dumps(value), flush=True)
pending_starts = {}
approval_decisions = []
native_turns = {}
def terminal(thread_id, turn_id, status):
    turn = {'id': turn_id, 'status': status, 'items': []}
    native_turns.setdefault(thread_id, {})[turn_id] = turn
    emit({'method': 'turn/completed', 'params': {'threadId': thread_id, 'turn': turn}})
for line in sys.stdin:
    request = json.loads(line)
    with (root / 'requests.jsonl').open('a') as log:
        log.write(json.dumps(request) + '\n')
    method, params = request.get('method'), request.get('params', {})
    if method is None and request.get('id') == 'native-approval':
        approval_decisions.append(request.get('result'))
        continue
    if 'id' not in request or method is None:
        continue
    result = {}
    if method == 'initialize':
        result = {'userAgent': 'native-test/1.0'}
    elif method == 'thread/list':
        threads = json.loads((root / 'threads.json').read_text())
        result = {'data': [t for t in threads if t.get('archived', False) == params.get('archived', False)], 'nextCursor': None}
    elif method == 'thread/read':
        if params['threadId'] == 'paginated':
            result = {'thread': {'id': 'paginated', 'historyMode': 'paginated', 'turns': []}}
        else:
            result = {'thread': {'id': params['threadId'], 'turns': [
                {'id': 'old-turn', 'items': [
                    {'type': 'userMessage', 'id': 'u1', 'content': [{'type': 'text', 'text': 'terminal message'}]},
                    {'type': 'agentMessage', 'id': 'a1', 'text': 'native reply'},
                    {'type': 'reasoning', 'id': 'r1', 'text': 'not chat text'},
                ]}
            ] + list(native_turns.get(params['threadId'], {}).values()) if params.get('includeTurns') else []}}
    elif method == 'thread/turns/list':
        n = 1 if params.get('cursor') is None else 2
        result = {'data': [{'id': 't' + str(n), 'items': [{'type': 'agentMessage', 'id': 'p' + str(n), 'text': str(n)}]}], 'nextCursor': 'page2' if n == 1 else None}
    elif method == 'thread/start':
        thread = {'id': 'created-native', 'preview': '', 'updatedAt': 1700000000}
        threads = json.loads((root / 'threads.json').read_text())
        threads.append(thread)
        (root / 'threads.json').write_text(json.dumps(threads))
        result = {'thread': thread}
    elif method == 'test/approval-barrier':
        # A response on this ordered native input stream proves the preceding
        # approval reply was consumed by the host, not merely buffered by Core.
        result = {'decisions': approval_decisions}
    elif method == 'test/release-start':
        for identifier, turn in list(pending_starts.items()):
            emit({'id': identifier, 'result': {'turn': turn}})
            del pending_starts[identifier]
    elif method == 'test/terminal':
        terminal(params['threadId'], params['turnId'], params['status'])
    elif method == 'test/disconnect':
        break
    elif method == 'turn/start':
        thread = params['threadId']
        turn = {'id': 'turn-' + thread, 'status': 'completed'}
        result = {'turn': {'id': turn['id'], 'status': 'inProgress'}}
        native_turns.setdefault(thread, {})[turn['id']] = {**result['turn'], 'items': []}
        # Deliberately notify before the start response to test dispatcher races.
        if thread == 'late-start':
            pending_starts[request['id']] = result['turn']
            continue
        elif thread == 'malformed-start':
            result = {'turn': None}
        elif thread == 'approval':
            emit({'id': 'native-approval', 'method': 'item/commandExecution/requestApproval', 'params': {'threadId': thread, 'turnId': turn['id']}})
        elif thread in ('hanging', 'observed', 'burst', 'cancel-ack-only', 'cancel-completed', 'cancel-failed', 'cancel-offline'):
            if thread == 'burst':
                for index in range(2000):
                    emit({'method': 'item/agentMessage/delta', 'params': {'threadId': thread, 'turnId': turn['id'], 'itemId': 'burst-message', 'delta': str(index)}})
                terminal(thread, turn['id'], turn['status'])
            elif thread == 'observed':
                emit({'method': 'item/agentMessage/delta', 'params': {'threadId': thread, 'turnId': turn['id'], 'itemId': 'live-message', 'delta': 'started'}})
        else:
            emit({'method': 'item/agentMessage/delta', 'params': {'threadId': thread, 'turnId': 'unrelated-turn', 'itemId': 'wrong', 'delta': 'IGNORE'}})
            emit({'method': 'item/agentMessage/delta', 'params': {'threadId': thread, 'turnId': turn['id'], 'itemId': 'a2', 'delta': 'hello'}})
            emit({'method': 'item/completed', 'params': {'threadId': thread, 'turnId': turn['id'], 'item': {'type': 'agentMessage', 'id': 'a2', 'text': 'hello'}}})
            terminal(thread, turn['id'], turn['status'])
    elif method == 'turn/interrupt':
        thread = params['threadId']
        if thread == 'cancel-offline':
            break
        if thread != 'cancel-ack-only':
            status = {'cancel-completed': 'completed', 'cancel-failed': 'failed'}.get(thread, 'interrupted')
            terminal(thread, params['turnId'], status)
    elif method == 'unknown':
        emit({'id': request['id'], 'error': {'code': -32601, 'message': 'unknown'}})
        continue
    emit({'id': request['id'], 'result': result})
'''


class CodexAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.service_home = self.root / "service-home"
        self.service_home.mkdir()
        environment = mock.patch.dict("os.environ", {"HOME": str(self.service_home)})
        environment.start()
        self.addCleanup(environment.stop)
        self.write_threads([{'id': 'terminal', 'name': 'CLI session', 'updatedAt': 1700000000}, {'id': 'archived', 'archived': True}])
        self.adapter = CodexSessionAdapter([sys.executable, '-u', '-c', NATIVE_SERVER], self.root, request_timeout=2, turn_timeout=2)
        self.alice, self.bob = Principal('alice'), Principal('bob')

    async def asyncTearDown(self):
        await self.adapter.close()
        self.temp.cleanup()

    def write_threads(self, threads):
        (self.root / 'threads.json').write_text(json.dumps(threads))

    def requests(self):
        return [json.loads(line) for line in (self.root / 'requests.jsonl').read_text().splitlines()]

    async def test_native_rpc_stages_keep_request_trace_and_omit_transcript(self):
        from oai_core.native_diagnostics import trace_context
        with self.assertLogs("oai_core.native_sessions", level="DEBUG") as capture:
            with trace_context({"traceparent": "00-" + "a" * 32 + "-" + "b" * 16 + "-00"}, request_id="native-original"):
                events = [event async for event in self.adapter.send(self.alice, "terminal", "PRIVATE-CODEX-INPUT", client_message_id="native-client")]
        records = [json.loads(record.getMessage().removeprefix("native_session ")) for record in capture.records]
        self.assertTrue(all(row["traceId"] == "a" * 32 and row["requestId"] == "native-original" for row in records))
        admission = next(row for row in records if row["stage"] == "adapter.codex.host_admitted")
        self.assertEqual(admission["generationId"], events[0]["generationId"])
        self.assertTrue(any(row.get("operation") == "turn/start" and row["stage"] == "adapter.codex.rpc_returned" for row in records))
        self.assertNotIn("PRIVATE-CODEX-INPUT", "".join(capture.output))

    async def test_explicit_native_home_and_shared_discovery_include_terminal_and_new_threads(self):
        self.assertTrue(await self.adapter.status())
        before = await self.adapter.list_sessions(self.alice)
        self.assertEqual({s['id'] for s in before}, {'terminal', 'archived'})
        self.assertTrue(next(s for s in before if s['id'] == 'archived')['archived'])
        self.write_threads([{'id': 'external-new', 'preview': 'from script'}])
        self.assertEqual([s['id'] for s in await self.adapter.list_sessions(self.bob)], ['external-new'])
        calls = self.requests()
        self.assertEqual(calls[0]['method'], 'initialize')
        self.assertEqual(calls[1]['method'], 'initialized')
        lists = [c for c in calls if c.get('method') == 'thread/list']
        self.assertTrue(all(c['params']['sourceKinds'] == SOURCE_KINDS for c in lists))
        self.assertTrue(all(c['params']['modelProviders'] == [] for c in lists))
        self.assertFalse(any('accountId' in c.get('params', {}) for c in calls))
        self.assertEqual(self.adapter.descriptor()['sessionScope'], 'deployment')

    async def test_native_history_preserves_real_ids_and_excludes_reasoning(self):
        messages = await self.adapter.history(self.alice, 'terminal')
        self.assertEqual([(m['id'], m['role'], m['text']) for m in messages], [('u1', 'user', 'terminal message'), ('a1', 'assistant', 'native reply')])
        self.assertTrue(all(m['createdAt'] is None for m in messages))

    async def test_paginated_history_reads_all_native_pages(self):
        messages = await self.adapter.history(self.alice, 'paginated')
        self.assertEqual([m['id'] for m in messages], ['p1', 'p2'])

    async def test_create_and_manage_call_native_methods_and_never_duplicate_storage(self):
        created = await self.adapter.create(self.alice, 'Native title')
        self.assertEqual(created['id'], 'created-native')
        self.assertEqual(created['title'], 'Native title')
        self.assertIn('created-native', [s['id'] for s in await self.adapter.list_sessions(self.bob)])
        await self.adapter.manage(self.bob, created['id'], 'archive')
        await self.adapter.manage(self.bob, created['id'], 'archive', False)
        with self.assertRaises(AdapterError) as caught:
            await self.adapter.manage(self.alice, created['id'], 'delete')
        self.assertEqual(caught.exception.code, 'UNSUPPORTED_CAPABILITY')
        methods = [r.get('method') for r in self.requests()]
        self.assertIn('thread/start', methods)
        self.assertIn('thread/name/set', methods)
        self.assertIn('thread/archive', methods)
        self.assertIn('thread/unarchive', methods)

    async def test_stream_handles_early_notifications_and_filters_foreign_turn(self):
        events = [event async for event in self.adapter.send(self.alice, 'terminal', 'hello from OAI', client_message_id='stable-client')]
        self.assertEqual(events[0], {'type': 'generation', 'generationId': 'turn-terminal', 'clientMessageId': 'stable-client', 'state': 'running'})
        self.assertEqual([e for e in events if e['type'] == 'delta'], [{'type': 'delta', 'text': 'hello', 'messageId': 'a2'}])
        self.assertEqual([e for e in events if e['type'] == 'done'], [{'type': 'done', 'messageId': 'a2', 'generationId': 'turn-terminal'}])
        self.assertTrue(all('userMessageId' not in event for event in events))
        turn = next(r for r in self.requests() if r.get('method') == 'turn/start')
        self.assertEqual(turn['params']['input'][0]['text'], 'hello from OAI')
        self.assertNotIn('approvalPolicy', turn['params'])

    async def test_native_approvals_are_declined_without_escalating_oai_login(self):
        events = [event async for event in self.adapter.send(self.alice, 'approval', 'do something sensitive')]
        self.assertEqual([e for e in events if e['type'] == 'error'], [{'type': 'error', 'code': 'NATIVE_APPROVAL_UNSUPPORTED', 'state': 'unknown'}])
        # drain() confirms delivery to the subprocess pipe, not host consumption.
        # Synchronize on a real RPC response after the decline on the same pipe.
        receipt = await self.adapter._rpc('test/approval-barrier', {})
        self.assertEqual(receipt['decisions'], [{'decision': 'decline'}])
        reply = next(r for r in self.requests() if r.get('id') == 'native-approval')
        self.assertEqual(reply['result'], {'decision': 'decline'})
        self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))

    async def test_timeout_ends_only_observation_and_preserves_native_turn(self):
        self.adapter.turn_timeout = 0.05
        with self.assertRaises(AdapterError) as caught:
            _ = [event async for event in self.adapter.send(self.alice, 'hanging', 'wait')]
        self.assertEqual(caught.exception.code, 'AGENT_TIMEOUT')
        self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))
        self.assertEqual(self.adapter._turns.get('hanging'), 'turn-hanging')
        await self.adapter.manage(self.bob, 'hanging', 'cancel')

    async def test_closing_observer_does_not_interrupt_execution_and_explicit_cancel_still_targets_turn(self):
        stream = self.adapter.send(self.alice, 'observed', 'wait')
        self.assertEqual((await anext(stream))['generationId'], 'turn-observed')
        self.assertEqual((await anext(stream))['text'], 'started')
        await stream.aclose()
        self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))
        self.assertEqual(self.adapter._turns.get('observed'), 'turn-observed')
        with self.assertRaises(AdapterError) as caught:
            _ = [event async for event in self.adapter.send(self.alice, 'observed', 'duplicate')]
        self.assertEqual(caught.exception.code, 'SESSION_BUSY')
        self.assertEqual((await self.adapter.list_sessions(self.bob))[0]['id'], 'terminal')
        await self.adapter.manage(self.bob, 'observed', 'cancel')
        async with asyncio.timeout(1):
            while 'observed' in self.adapter._turns:
                await asyncio.sleep(0)
        calls = [r for r in self.requests() if r.get('method') == 'turn/interrupt']
        self.assertEqual([c['params'] for c in calls], [{'threadId': 'observed', 'turnId': 'turn-observed'}])

    async def test_slow_observer_does_not_lose_native_terminal_or_block_another_session(self):
        stream = self.adapter.send(self.alice, 'burst', 'burst')
        first = await anext(stream)
        await self.adapter.list_sessions(self.bob)
        events = [first, *[event async for event in stream]]
        self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))
        self.assertNotIn('burst', self.adapter._turns)
        self.assertNotIn('burst', self.adapter._streams)
        self.assertIn(events[-1]['type'], ('done', 'error'))
        self.assertTrue(await self.adapter.status())

    async def wait_for_turn(self, session_id):
        async with asyncio.timeout(1):
            while session_id not in self.adapter._turns:
                await asyncio.sleep(0)

    async def wait_for_cleanup(self, session_id):
        async with asyncio.timeout(1):
            while session_id in self.adapter._executions:
                await asyncio.sleep(0)

    async def test_closed_observer_is_cleaned_on_native_terminal_without_interrupt(self):
        stream = self.adapter.send(self.alice, 'observed', 'wait')
        await anext(stream)
        await stream.aclose()
        self.assertNotIn('observed', self.adapter._streams)
        await self.adapter._rpc('test/terminal', {'threadId': 'observed', 'turnId': 'foreign', 'status': 'completed'})
        self.assertEqual(self.adapter._turns['observed'], 'turn-observed')
        await self.adapter._rpc('test/terminal', {'threadId': 'observed', 'turnId': 'turn-observed', 'status': 'completed'})
        await self.wait_for_cleanup('observed')
        self.assertNotIn('observed', self.adapter._turns)
        self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))
        # Native history is still the only transcript read interface.
        self.assertEqual((await self.adapter.history(self.bob, 'observed'))[-1]['id'], 'a1')

    async def test_late_start_response_remains_cancellable_after_observer_request_timeout(self):
        self.adapter.request_timeout = 0.05
        with self.assertRaises(AdapterError) as caught:
            _ = [event async for event in self.adapter.send(self.alice, 'late-start', 'wait')]
        self.assertEqual(caught.exception.code, 'AGENT_TIMEOUT')
        self.assertIn('late-start', self.adapter._executions)
        self.assertNotIn('late-start', self.adapter._streams)
        self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))
        self.assertTrue(await self.adapter.status())
        await self.adapter._rpc('test/release-start', {})
        await self.wait_for_turn('late-start')
        await self.adapter.manage(self.bob, 'late-start', 'cancel')
        await self.wait_for_cleanup('late-start')
        calls = [r for r in self.requests() if r.get('method') == 'turn/interrupt']
        self.assertEqual([c['params'] for c in calls], [{'threadId': 'late-start', 'turnId': 'turn-late-start'}])
        self.assertEqual(sum(r.get('method') == 'turn/start' for r in self.requests()), 1)
        self.assertEqual(self.adapter._pending, {})

    async def test_cancelling_observer_during_start_does_not_cancel_late_native_acceptance(self):
        stream = self.adapter.send(self.alice, 'late-start', 'wait')
        observing = asyncio.create_task(anext(stream))
        try:
            async with asyncio.timeout(1):
                while not (self.root / 'requests.jsonl').exists() or not any(
                    r.get('method') == 'turn/start' for r in self.requests()
                ):
                    await asyncio.sleep(0)
            observing.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await observing
            await stream.aclose()
            await self.adapter._rpc('test/release-start', {})
            await self.wait_for_turn('late-start')
            self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))
            await self.adapter.manage(self.bob, 'late-start', 'cancel')
            await self.wait_for_cleanup('late-start')
        finally:
            observing.cancel()
            await asyncio.gather(observing, return_exceptions=True)
            await stream.aclose()

    async def test_unknown_native_status_never_becomes_a_failed_or_completed_turn(self):
        stream = self.adapter.send(self.alice, 'observed', 'wait')
        self.assertEqual((await anext(stream))['generationId'], 'turn-observed')
        self.assertEqual((await anext(stream))['text'], 'started')
        await self.adapter._rpc('test/terminal', {'threadId': 'observed', 'turnId': 'turn-observed', 'status': 'inProgress'})
        self.assertEqual(await anext(stream), {'type': 'error', 'code': 'OUTCOME_UNKNOWN', 'state': 'unknown'})
        await stream.aclose()
        self.assertIn('observed', self.adapter._turns)
        await self.adapter.manage(self.bob, 'observed', 'cancel')
        await self.wait_for_cleanup('observed')

    async def test_malformed_start_identity_reserves_session_until_host_disconnect(self):
        with self.assertRaises(AdapterError) as caught:
            _ = [event async for event in self.adapter.send(self.alice, 'malformed-start', 'wait')]
        self.assertEqual(caught.exception.code, 'OUTCOME_UNKNOWN')
        self.assertIn('malformed-start', self.adapter._executions)
        self.assertNotIn('malformed-start', self.adapter._turns)
        with self.assertRaises(AdapterError) as duplicate:
            _ = [event async for event in self.adapter.send(self.alice, 'malformed-start', 'wait')]
        self.assertEqual(duplicate.exception.code, 'SESSION_BUSY')
        await self.adapter.close()
        self.assertEqual(self.adapter._executions, {})
        self.assertEqual(self.adapter._pending, {})
        self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))

    async def test_host_disconnect_reports_unknown_and_cleans_execution_without_interrupt(self):
        stream = self.adapter.send(self.alice, 'observed', 'wait')
        self.assertEqual((await anext(stream))['generationId'], 'turn-observed')
        self.assertEqual((await anext(stream))['text'], 'started')
        with self.assertRaises(AdapterError) as caught:
            await self.adapter._rpc('test/disconnect', {})
        self.assertEqual(caught.exception.code, 'AGENT_OFFLINE')
        events = [event async for event in stream]
        self.assertEqual(events, [{'type': 'error', 'code': 'AGENT_OFFLINE', 'state': 'unknown'}])
        await self.wait_for_cleanup('observed')
        self.assertEqual(self.adapter._pending, {})
        self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))

    async def test_transport_and_approval_do_not_claim_recovery_or_idempotency(self):
        caps = self.adapter.descriptor()['capabilities']
        self.assertFalse(caps['generationRecovery'])
        self.assertFalse(caps['idempotentSend'])

    async def test_cancel_with_stale_generation_does_not_interrupt_the_current_turn(self):
        stream = self.adapter.send(self.alice, 'observed', 'wait')
        await anext(stream)
        await stream.aclose()
        with self.assertRaises(AdapterError) as caught:
            await self.adapter.manage(self.bob, 'observed', 'cancel', {'generationId': 'foreign'})
        self.assertEqual(caught.exception.code, 'GENERATION_NOT_FOUND')
        self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))
        await self.adapter.manage(self.bob, 'observed', 'cancel', {'generationId': 'turn-observed'})
        await self.wait_for_cleanup('observed')

    async def test_core_http_disconnect_preserves_native_turn_and_explicit_cancel_endpoint_interrupts(self):
        handlers = []
        async def authenticate(request):
            handlers.append(asyncio.current_task())
            return self.alice
        registry = AgentRegistry([self.adapter])
        app = web.Application()
        app.add_routes(create_agent_routes(registry, authenticate, lambda p: True))
        client = TestClient(TestServer(app))
        base = '/open-android-intelligence/v2/agents/codex/sessions'
        await client.start_server()
        try:
            response = await client.post(base + '/observed/messages',
                json={'text': 'wait', 'clientMessageId': 'http-observer'},
                headers={'Idempotency-Key': 'http-observer'})
            self.assertEqual(response.status, 200)
            self.assertEqual(await asyncio.wait_for(response.content.readline(), 1), b'event: message\n')
            generation = json.loads((await asyncio.wait_for(response.content.readline(), 1)).removeprefix(b'data: '))
            self.assertEqual(generation, {'type': 'generation', 'generationId': 'turn-observed', 'clientMessageId': 'http-observer', 'state': 'running'})
            self.assertNotIn('userMessageId', generation)
            self.assertEqual(await asyncio.wait_for(response.content.readline(), 1), b'\n')
            self.assertEqual(await asyncio.wait_for(response.content.readline(), 1), b'event: message\n')
            delta = json.loads((await asyncio.wait_for(response.content.readline(), 1)).removeprefix(b'data: '))
            self.assertEqual(delta, {'type': 'delta', 'text': 'started', 'messageId': 'live-message'})
            response.close()
            handlers[0].cancel()
            await asyncio.gather(handlers[0], return_exceptions=True)
            self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))
            self.assertEqual(self.adapter._turns.get('observed'), 'turn-observed')
            other = await client.get(base + '/terminal/history')
            self.assertEqual(other.status, 200)
            self.assertEqual((await other.json())['messages'][0]['id'], 'u1')
            stale = await client.post(base + '/observed/cancel',
                json={'generationId': 'foreign'}, headers={'Idempotency-Key': 'http-stale'})
            self.assertEqual(stale.status, 404)
            self.assertEqual((await stale.json())['error']['code'], 'GENERATION_NOT_FOUND')
            self.assertFalse(any(r.get('method') == 'turn/interrupt' for r in self.requests()))
            cancel = await client.post(base + '/observed/cancel',
                json={'generationId': generation['generationId']}, headers={'Idempotency-Key': 'http-cancel'})
            self.assertEqual(cancel.status, 200, await cancel.text())
            self.assertEqual((await cancel.json())['session'], {'id': 'observed', 'action': 'cancel', 'generationId': generation['generationId'], 'outcome': 'cancelled'})
            await self.wait_for_cleanup('observed')
            calls = [r for r in self.requests() if r.get('method') == 'turn/interrupt']
            self.assertEqual([c['params'] for c in calls], [{'threadId': 'observed', 'turnId': 'turn-observed'}])
            async with asyncio.timeout(1):
                while registry._operation_tasks:
                    await asyncio.sleep(0)
        finally:
            await client.close()
            await registry.close()

    async def test_cancel_ack_without_native_terminal_reports_unknown_and_keeps_execution(self):
        self.adapter.request_timeout = 0.05
        stream = self.adapter.send(self.alice, 'cancel-ack-only', 'wait')
        generation = await anext(stream)
        self.assertEqual(generation, {'type': 'generation', 'generationId': 'turn-cancel-ack-only', 'state': 'running'})
        receipt = await self.adapter.manage(self.bob, 'cancel-ack-only', 'cancel', {'generationId': generation['generationId']})
        self.assertEqual(receipt, {'id': 'cancel-ack-only', 'action': 'cancel', 'generationId': generation['generationId'], 'outcome': 'unknown'})
        self.assertEqual(self.adapter._turns['cancel-ack-only'], generation['generationId'])
        await self.adapter._rpc('test/terminal', {'threadId': 'cancel-ack-only', 'turnId': generation['generationId'], 'status': 'interrupted'})
        await self.wait_for_cleanup('cancel-ack-only')
        await stream.aclose()

    async def test_cancel_receipt_uses_native_terminal_outcome_including_completion_race(self):
        for session_id, expected in [('hanging', 'cancelled'), ('cancel-completed', 'already_completed'), ('cancel-failed', 'unknown'), ('cancel-offline', 'unknown')]:
            with self.subTest(session_id=session_id):
                stream = self.adapter.send(self.alice, session_id, 'wait')
                generation = await anext(stream)
                receipt = await self.adapter.manage(self.bob, session_id, 'cancel', {'generationId': generation['generationId']})
                self.assertEqual(receipt, {'id': session_id, 'action': 'cancel', 'generationId': generation['generationId'], 'outcome': expected})
                await stream.aclose()
                await self.wait_for_cleanup(session_id)

    async def test_cancel_after_native_cleanup_reads_exact_native_fact_without_another_interrupt(self):
        for session_id, outcome in [('terminal', 'already_completed'), ('hanging', 'cancelled')]:
            with self.subTest(session_id=session_id):
                stream = self.adapter.send(self.alice, session_id, 'wait')
                generation = await anext(stream)
                if session_id == 'hanging':
                    await self.adapter.manage(self.bob, session_id, 'cancel', {'generationId': generation['generationId']})
                await self.wait_for_cleanup(session_id)
                await stream.aclose()
                before = sum(r.get('method') == 'turn/interrupt' for r in self.requests())
                receipt = await self.adapter.manage(self.bob, session_id, 'cancel', {'generationId': generation['generationId']})
                self.assertEqual(receipt, {'id': session_id, 'action': 'cancel', 'generationId': generation['generationId'], 'outcome': outcome})
                self.assertEqual(sum(r.get('method') == 'turn/interrupt' for r in self.requests()), before)
                with self.assertRaises(AdapterError) as stale:
                    await self.adapter.manage(self.bob, session_id, 'cancel', {'generationId': 'foreign'})
                self.assertEqual(stale.exception.code, 'GENERATION_NOT_FOUND')

    async def test_cancel_waits_for_native_terminal_after_successful_interrupt_rpc(self):
        stream = self.adapter.send(self.alice, 'cancel-ack-only', 'wait')
        generation = await anext(stream)
        acknowledged = asyncio.Event()
        native_rpc = self.adapter._rpc_raw
        async def record_ack(method, params, **kwargs):
            result = await native_rpc(method, params, **kwargs)
            if method == 'turn/interrupt':
                acknowledged.set()
            return result
        with mock.patch.object(self.adapter, '_rpc_raw', side_effect=record_ack):
            cancelling = asyncio.create_task(self.adapter.manage(self.bob, 'cancel-ack-only', 'cancel', {'generationId': generation['generationId']}))
            try:
                await asyncio.wait_for(acknowledged.wait(), 1)
                self.assertFalse(cancelling.done(), 'RPC success alone must not resolve cancellation')
                await self.adapter._rpc('test/terminal', {'threadId': 'cancel-ack-only', 'turnId': generation['generationId'], 'status': 'interrupted'})
                self.assertEqual((await asyncio.wait_for(cancelling, 1))['outcome'], 'cancelled')
                await self.wait_for_cleanup('cancel-ack-only')
            finally:
                cancelling.cancel()
                await asyncio.gather(cancelling, return_exceptions=True)
                await stream.aclose()

    async def test_cancel_targets_the_actual_native_turn(self):
        stream_task = asyncio.create_task(self._collect_hanging())
        for _ in range(100):
            if 'hanging' in self.adapter._turns:
                break
            await asyncio.sleep(0.01)
        receipt = await self.adapter.manage(self.bob, 'hanging', 'cancel')
        self.assertEqual(receipt, {'id': 'hanging', 'action': 'cancel', 'generationId': 'turn-hanging', 'outcome': 'cancelled'})
        self.assertEqual([e for e in await stream_task if e['type'] == 'error'], [{'type': 'error', 'code': 'TURN_INTERRUPTED'}])
        call = next(r for r in self.requests() if r.get('method') == 'turn/interrupt')
        self.assertEqual(call['params'], {'threadId': 'hanging', 'turnId': 'turn-hanging'})

    async def _collect_hanging(self):
        return [event async for event in self.adapter.send(self.alice, 'hanging', 'wait')]

    async def test_shutdown_during_active_turn_does_not_restart_or_leave_native_process(self):
        processes = []
        original = asyncio.create_subprocess_exec

        async def capture(*args, **kwargs):
            process = await original(*args, **kwargs)
            processes.append(process)
            return process

        with mock.patch('asyncio.create_subprocess_exec', side_effect=capture):
            task = asyncio.create_task(self._collect_hanging())
            try:
                async with asyncio.timeout(3):
                    while 'hanging' not in self.adapter._turns:
                        await asyncio.sleep(0.01)
                await self.adapter.close()
                events = await asyncio.wait_for(task, 3)
                self.assertEqual([{'type': 'error', 'code': 'AGENT_OFFLINE', 'state': 'unknown'}], [e for e in events if e['type'] == 'error'])
                self.assertEqual(1, len(processes))
                self.assertTrue(all(process.returncode is not None for process in processes))
                self.assertIsNone(self.adapter._process)
            finally:
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                for process in processes:
                    if process.returncode is None:
                        process.kill()
                    await process.wait()

    async def test_missing_configured_home_is_offline_without_ambient_scan(self):
        unavailable = CodexSessionAdapter([sys.executable, '-u', '-c', NATIVE_SERVER], self.root / 'missing')
        self.assertFalse(await unavailable.status())
        self.assertIsNone(unavailable._process)
        self.assertFalse((self.root / 'missing').exists())

    async def test_unknown_native_method_reports_unsupported(self):
        with self.assertRaises(AdapterError) as caught:
            await self.adapter._rpc('unknown', {})
        self.assertEqual(caught.exception.code, 'UNSUPPORTED_CAPABILITY')

    async def test_child_environment_excludes_ambient_device_and_provider_credentials(self):
        with mock.patch.dict('os.environ', {
            'OAI_DEVICE_TOKEN': 'fixture-device-secret', 'AUTHORIZATION': 'fixture-auth-secret',
            'UNRELATED_SECRET': 'fixture-unrelated-secret', 'OPENAI_API_KEY': 'fixture-ambient-model-secret',
        }):
            self.assertTrue(await self.adapter.status())
        observed = json.loads((self.root / 'environment-observed.json').read_text())
        self.assertTrue(all(value is None for value in observed.values()))
        self.assertFalse(self.adapter.descriptor()['deviceTools'])

    async def test_provider_credentials_require_explicit_allowed_env_configuration(self):
        adapter = CodexSessionAdapter([sys.executable, '-u', '-c', NATIVE_SERVER], self.root,
                                      env={'OPENAI_API_KEY': 'fixture-explicit-model-secret'})
        try:
            self.assertTrue(await adapter.status())
            observed = json.loads((self.root / 'environment-observed.json').read_text())
            self.assertEqual(observed['OPENAI_API_KEY'], 'fixture-explicit-model-secret')
        finally:
            await adapter.close()
        for forbidden in ('OAI_TOKEN', 'AUTHORIZATION', 'SOME_OTHER_SECRET'):
            with self.assertRaises(ValueError):
                CodexSessionAdapter(['codex', 'app-server'], self.root, env={forbidden: 'fixture-secret'})

    async def test_existing_default_cli_credentials_prevent_child_start_without_reading(self):
        user_home = self.root / 'service-home'
        credential = user_home / '.config' / 'oai' / 'client.json'
        credential.parent.mkdir(parents=True)
        credential.touch(mode=0)
        with mock.patch.dict('os.environ', {'HOME': str(user_home)}):
            with self.assertRaises(AdapterError) as caught:
                await self.adapter.list_sessions(self.alice)
        self.assertEqual(caught.exception.code, 'AGENT_UNSAFE_DEVICE_CREDENTIALS')
        self.assertIsNone(self.adapter._process)
        self.assertFalse((self.root / 'requests.jsonl').exists())

    async def test_static_oai_mcp_configuration_prevents_child_start(self):
        for configuration in (
            '[mcp_servers.android]\ncommand = "oai"\nargs = ["mcp"]\n',
            '[mcp_servers.android]\ncommand = "python"\nargs = ["-m", "oai_core.cli", "mcp"]\n',
            '[mcp_servers.android]\ncommand = "custom-wrapper"\n[mcp_servers.android.env]\nOAI_TOKEN = "fixture-secret"\n',
        ):
            (self.root / 'config.toml').write_text(configuration)
            with self.assertRaises(AdapterError) as caught:
                await self.adapter.list_sessions(self.bob)
            self.assertEqual(caught.exception.code, 'AGENT_UNSAFE_DEVICE_CREDENTIALS')
            self.assertIsNone(self.adapter._process)


if __name__ == '__main__':
    unittest.main()
