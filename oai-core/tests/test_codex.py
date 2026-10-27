"""Native protocol fixture tests; no real Codex home or model account is used."""

import asyncio
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from oai_core.adapters.codex import CodexSessionAdapter, SOURCE_KINDS
from oai_core.sessions import AdapterError, Principal


NATIVE_SERVER = r'''
import json, os, sys
from pathlib import Path
root = Path(os.environ['CODEX_HOME'])
(root / 'environment-observed.json').write_text(json.dumps({
    name: os.environ.get(name) for name in ['OAI_DEVICE_TOKEN', 'AUTHORIZATION', 'UNRELATED_SECRET', 'OPENAI_API_KEY']
}))
def emit(value):
    print(json.dumps(value), flush=True)
for line in sys.stdin:
    request = json.loads(line)
    with (root / 'requests.jsonl').open('a') as log:
        log.write(json.dumps(request) + '\n')
    method, params = request.get('method'), request.get('params', {})
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
            ] if params.get('includeTurns') else []}}
    elif method == 'thread/turns/list':
        n = 1 if params.get('cursor') is None else 2
        result = {'data': [{'id': 't' + str(n), 'items': [{'type': 'agentMessage', 'id': 'p' + str(n), 'text': str(n)}]}], 'nextCursor': 'page2' if n == 1 else None}
    elif method == 'thread/start':
        thread = {'id': 'created-native', 'preview': '', 'updatedAt': 1700000000}
        threads = json.loads((root / 'threads.json').read_text())
        threads.append(thread)
        (root / 'threads.json').write_text(json.dumps(threads))
        result = {'thread': thread}
    elif method == 'turn/start':
        thread = params['threadId']
        turn = {'id': 'turn-' + thread, 'status': 'completed'}
        result = {'turn': {'id': turn['id'], 'status': 'inProgress'}}
        # Deliberately notify before the start response to test dispatcher races.
        if thread == 'approval':
            emit({'id': 'native-approval', 'method': 'item/commandExecution/requestApproval', 'params': {'threadId': thread, 'turnId': turn['id']}})
        elif thread == 'hanging':
            pass
        else:
            emit({'method': 'item/agentMessage/delta', 'params': {'threadId': thread, 'turnId': 'unrelated-turn', 'itemId': 'wrong', 'delta': 'IGNORE'}})
            emit({'method': 'item/agentMessage/delta', 'params': {'threadId': thread, 'turnId': turn['id'], 'itemId': 'a2', 'delta': 'hello'}})
            emit({'method': 'item/completed', 'params': {'threadId': thread, 'turnId': turn['id'], 'item': {'type': 'agentMessage', 'id': 'a2', 'text': 'hello'}}})
            emit({'method': 'turn/completed', 'params': {'threadId': thread, 'turn': turn}})
    elif method == 'turn/interrupt':
        emit({'method': 'turn/completed', 'params': {'threadId': params['threadId'], 'turn': {'id': params['turnId'], 'status': 'interrupted'}}})
    elif method == 'unknown':
        emit({'id': request['id'], 'error': {'code': -32601, 'message': 'unknown'}})
        continue
    emit({'id': request['id'], 'result': result})
'''


class CodexAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
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
        events = [event async for event in self.adapter.send(self.alice, 'terminal', 'hello from OAI')]
        self.assertEqual(events, [{'type': 'delta', 'text': 'hello', 'messageId': 'a2'}, {'type': 'done', 'messageId': 'a2'}])
        turn = next(r for r in self.requests() if r.get('method') == 'turn/start')
        self.assertEqual(turn['params']['input'][0]['text'], 'hello from OAI')
        self.assertNotIn('approvalPolicy', turn['params'])

    async def test_native_approvals_are_declined_without_escalating_oai_login(self):
        events = [event async for event in self.adapter.send(self.alice, 'approval', 'do something sensitive')]
        self.assertEqual(events, [{'type': 'error', 'code': 'NATIVE_APPROVAL_UNSUPPORTED'}])
        reply = next(r for r in self.requests() if r.get('id') == 'native-approval')
        self.assertEqual(reply['result'], {'decision': 'decline'})
        self.assertTrue(any(r.get('method') == 'turn/interrupt' for r in self.requests()))

    async def test_timeout_interrupts_native_turn(self):
        self.adapter.turn_timeout = 0.05
        with self.assertRaises(AdapterError) as caught:
            _ = [event async for event in self.adapter.send(self.alice, 'hanging', 'wait')]
        self.assertEqual(caught.exception.code, 'AGENT_TIMEOUT')
        self.assertTrue(any(r.get('method') == 'turn/interrupt' for r in self.requests()))

    async def test_cancel_targets_the_actual_native_turn(self):
        stream_task = asyncio.create_task(self._collect_hanging())
        for _ in range(100):
            if 'hanging' in self.adapter._turns:
                break
            await asyncio.sleep(0.01)
        await self.adapter.manage(self.bob, 'hanging', 'cancel')
        self.assertEqual(await stream_task, [{'type': 'error', 'code': 'TURN_INTERRUPTED'}])
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
                self.assertEqual([{'type': 'error', 'code': 'AGENT_OFFLINE'}], events)
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
