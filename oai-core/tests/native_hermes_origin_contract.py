"""固定 SDK 的来源前缀/身份断点复现；所有写入只进入本目录的 runs。"""
from __future__ import annotations

import argparse
import asyncio
from contextlib import closing, nullcontext
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
from types import SimpleNamespace
from unittest.mock import patch
import uuid

REPO, RUN = map(Path, sys.argv[1:3])
SDK = Path(os.environ["HERMES_SDK_ROOT"])
RUN.mkdir(parents=True, exist_ok=True)
# 必须先隔离，再导入宿主；从不读取实际账号/模型配置。
for key in tuple(os.environ):
    if key.startswith(('HERMES_', 'OAI_HERMES_', 'OPEN_ANDROID_')) or key.endswith(('_API_KEY', '_TOKEN', '_SECRET', '_PASSWORD', '_CREDENTIALS')):
        os.environ.pop(key)
os.environ.update(HERMES_HOME=str(RUN / 'hermes'), HERMES_TEST_ISOLATION=str(RUN),
                  PYTHONDONTWRITEBYTECODE='1', HERMES_GATEWAY_BUSY_ACK_ENABLED='false')
os.environ["HERMES_SDK_ROOT"] = str(SDK)
sys.dont_write_bytecode = True
sys.path[:0] = [str(REPO / 'oai-core/src'), str(REPO / 'integrations/hermes-session-adapter/src'), str(SDK)]


from gateway.config import GatewayConfig, Platform, PlatformConfig
from gateway.platforms.base import MessageEvent
from gateway.run import GatewayRunner
from gateway.session import SessionStore, SessionSource
from gateway.session_state import SessionState
from hermes_cli.plugins import PluginContext, PluginManager, parse_manifest_file
from gateway.platform_registry import platform_registry
from run_agent import AIAgent
from agent.agent_runtime_helpers import apply_pending_steer_to_tool_results
from hermes_state import SessionDB
from oai_core.server import create_core
from oai_hermes_adapter.bridge import register
from oai_hermes_adapter.host_history import HermesHistoryPort
from oai_hermes_adapter.platform_identity import GATEWAY_PLATFORM_ID


def bare_agent(database, session_id):
    agent = object.__new__(AIAgent)
    agent._pending_steer = None
    agent._pending_steer_lock = threading.Lock()
    agent._active_children = []
    agent._active_children_lock = threading.Lock()
    agent._session_db = database
    agent._session_db_created = True
    agent._last_flushed_db_idx = 0
    agent._flushed_db_message_ids = set()
    agent._session_persist_lock = threading.RLock()
    agent._pending_redirect = None
    agent._pending_redirect_lock = threading.Lock()
    agent._interrupt_thread_signal_pending = False
    agent._tool_worker_threads = None
    agent._tool_worker_threads_lock = None
    agent._execution_thread_id = None
    agent._interrupt_requested = False
    agent.session_id = session_id
    return agent


class ControlledRunner(GatewayRunner):
    """仅控制授权/策略/当前任务，不替换 SDK 的 busy/steer/落库方法。"""
    def _profile_scope_for_source(self, source):
        return nullcontext()
    def _delivery_adapter_for(self, source):
        return self.synthetic_runtime
    def _is_user_authorized_for_source(self, source):
        return True
    def _admit_bot_message_for_source(self, source):
        return True
    def _effective_busy_input_mode(self, source):
        return 'steer'
    def _effective_busy_text_mode(self, source):
        return 'steer'
    async def _route_plaintext_approval_while_busy(self, event, session_key):
        return False
    async def _session_has_compression_in_flight(self, session_key):
        return False


def setup_runtime():
    settings = {'storageRoot': str(RUN / 'accounts'), 'masterKeyFile': str(RUN / 'master-key'),
                'contractRoot': str(REPO / 'gateway-contract')}
    initializer = create_core(settings)
    with closing(initializer.open_gateway_account('synthetic-origin')) as account:
        account.credentials.create_password('synthetic-origin-password')
    os.environ.update(OAI_HERMES_GATEWAY_ROOT=settings['storageRoot'], OAI_HERMES_ACCOUNTS='["synthetic-origin"]',
        OAI_HERMES_SESSION_TOKEN='synthetic-origin-token-000000000000000000000',
        OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE=settings['masterKeyFile'])
    plugin = REPO / 'integrations/hermes-session-adapter'
    manifest = parse_manifest_file(plugin / 'plugin.yaml', plugin, 'project', '')
    manager = PluginManager()
    register(PluginContext(manifest, manager))
    runtime = platform_registry.get(GATEWAY_PLATFORM_ID).adapter_factory(PlatformConfig(enabled=True, extra={}))
    core = runtime.services.core
    core.acquire_accounts()
    store = SessionStore(RUN / 'sessions', GatewayConfig())
    runtime.set_session_store(store)
    history = HermesHistoryPort(core, store)
    core.history_reader = history.read
    core.conversation_manager = history.manage
    return runtime, store, history


def bound_conversation(runtime, store, label):
    with closing(runtime.services.core.open_gateway_account('synthetic-origin')) as account:
        created = account.conversations.create(label, label, label)
        cid = created['conversationId']
        entry = store.get_or_create_session(runtime._agent_source(cid, 'synthetic-origin'), False)
        account.agent_sessions.record(cid, 'conversation-create')
        account.agent_sessions.attach(cid, entry.session_id, entry.session_key)
    return cid, entry, store._db_for_key(entry.session_key)



from agent.conversation_loop import _apply_active_turn_redirect
from gateway.run_turn_runner import TurnRunner
from oai_hermes_adapter.host_history import NATIVE_INPUT_KEY, NATIVE_INPUT_NAMESPACE


async def busy(runtime, store, history, label, operation):
    cid, entry, database = bound_conversation(runtime, store, label)
    agent = bare_agent(database, entry.session_id)
    agent._supports_active_turn_redirect = True
    agent._model_request_active = threading.Event()
    agent._model_request_active.set()
    agent._interrupt_requested = False
    agent._execution_thread_id = None
    agent._strip_think_blocks = lambda content: content
    runner = object.__new__(ControlledRunner)
    runner.synthetic_runtime = runtime
    runner.session_store = store
    runner._sessions = {entry.session_key: SessionState()}
    runner._session_state(entry.session_key).turn.agent = agent
    runner._draining = False
    runtime.gateway_runner = runner
    runtime._loop = asyncio.get_running_loop()
    runtime.set_message_handler(runner._handle_message)
    runtime.set_busy_session_handler(runner._handle_active_session_busy_message)
    gate = asyncio.Event()
    task = asyncio.create_task(gate.wait())
    runtime._session_tasks[entry.session_key] = task
    runtime._active_sessions[entry.session_key] = asyncio.Event()
    runtime._native_turns[('synthetic-origin', cid, 'synthetic-generation')] = (entry.session_key, task, None)
    try:
        await operation(cid, entry, database, agent, runner)
    finally:
        gate.set()
        await task
        runtime._active_sessions.pop(entry.session_key, None)


def drain(agent):
    messages = [{'role': 'tool', 'content': 'synthetic-result', 'tool_call_id': 'synthetic-call'}]
    apply_pending_steer_to_tool_results(agent, messages, 1)
    assert agent._flush_messages_to_session_db(messages, []) is True
    return messages


def users(history, cid):
    return [item for item in history.read('synthetic-origin', cid) if item['sender'] == 'user']


async def main():
    runtime, store, history = setup_runtime()
    passed = []
    try:
        async def explicit(cid, entry, db, agent, runner):
            original = '中途追加：先确认原稿身份'
            for _ in range(2):
                result = await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', original,
                    client_message_id='synthetic-explicit')
                assert result['outcome'] == 'accepted'
            messages = drain(agent)
            native = db.get_messages(entry.session_id)[-1]
            assert 'Gateway message origin' in native['content']
            assert native['platform_message_id'] == 'synthetic-explicit'
            assert NATIVE_INPUT_KEY in native['display_metadata']
            assert messages[-1]['content'] == native['content']  # model transcript stays untouched
            projected = users(history, cid)
            assert [(row['clientMessageId'], row['text']) for row in projected] == [('synthetic-explicit', original)]
            assert projected[0]['generationId'] == 'synthetic-generation'
            # Same client/text retry after flush does not inject another host input.
            await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', original,
                client_message_id='synthetic-explicit')
            drain(agent)
            assert len([row for row in db.get_messages(entry.session_id) if row['role'] == 'user']) == 1
            assert users(HermesHistoryPort(runtime.services.core, store), cid) == projected
            try:
                await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', 'different input',
                    client_message_id='synthetic-explicit')
            except Exception:
                pass
            assert agent._drain_pending_steer() is None
        await busy(runtime, store, history, 'explicit', explicit)
        passed.append('explicit_identity_original_retry_restart')

        async def merged(cid, entry, db, agent, runner):
            for index in range(2):
                await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', '相同原稿',
                    client_message_id=f'synthetic-merged-{index}')
            drain(agent)
            rows = [row for row in db.get_messages(entry.session_id) if row['role'] == 'user']
            assert len(rows) == 1 and rows[0].get('platform_message_id') is None
            assert len(rows[0]['display_metadata'][NATIVE_INPUT_KEY]['inputKeys']) == 2
            projected = users(history, cid)
            assert len(projected) == 2
            assert {row['clientMessageId'] for row in projected} == {'synthetic-merged-0', 'synthetic-merged-1'}
            assert [row['text'] for row in projected] == ['相同原稿', '相同原稿']
            # Real compaction retains the producer's references and cannot duplicate inputs.
            db.archive_and_compact(entry.session_id, [dict(row) for row in db.get_messages(entry.session_id)])
            assert len(users(HermesHistoryPort(runtime.services.core, store), cid)) == 2
        await busy(runtime, store, history, 'merged', merged)
        passed.append('merged_two_inputs_compaction')

        async def normal(cid, entry, db, agent, runner):
            text, client = '正常 busy 提交', 'synthetic-normal-busy'
            with closing(runtime.services.core.open_gateway_account('synthetic-origin')) as account:
                accepted = account.conversations.accept_message(cid, client, text, [], 'synthetic-device', client, client)
            await runtime._dispatch_agent_inbound('/v1/conversations/' + cid + '/messages',
                json.dumps({'clientMessageId': client, 'text': text}).encode(), {'data': accepted}, 'synthetic-origin')
            drain(agent)
            projected = users(history, cid)
            assert len(projected) == 1 and projected[0]['messageId'] == accepted['messageId']
            assert projected[0]['text'] == text and projected[0]['clientMessageId'] == client
            before = projected[0]['contentRevision']
            assert users(history, cid)[0]['contentRevision'] == before
        await busy(runtime, store, history, 'normal-busy', normal)
        passed.append('normal_busy_keeps_existing_identity')

        async def redirect(cid, entry, db, agent, runner):
            runner._effective_busy_input_mode = lambda source: 'interrupt'
            original = '纠正当前轮次'
            await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', original,
                client_message_id='synthetic-redirect')
            text = agent._drain_pending_redirect()
            assert text and 'Gateway message origin' in text
            messages = [{'role': 'assistant', 'content': 'synthetic partial'}]
            _apply_active_turn_redirect(agent, messages, text)
            assert agent._flush_messages_to_session_db(messages, []) is True
            projected = users(history, cid)
            assert [(row['text'], row['clientMessageId']) for row in projected] == [(original, 'synthetic-redirect')]
        await busy(runtime, store, history, 'redirect', redirect)
        passed.append('native_redirect_keeps_original')

        async def requeue(cid, entry, db, agent, runner):
            await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', '排出后放回队列',
                client_message_id='synthetic-requeue')
            from agent.turn_iteration_prep import _inject_steer_after_newest_tool_result
            text = agent._drain_pending_steer()
            _inject_steer_after_newest_tool_result(agent, [], text)
            drain(agent)
            assert [row['text'] for row in users(history, cid)] == ['排出后放回队列']
        await busy(runtime, store, history, 'requeue', requeue)
        passed.append('native_pre_api_requeue')

        async def unchanged(cid, entry, db, agent, runner):
            # Legitimate content with every machine-looking prefix has no trusted input receipt.
            original = '[System note: legitimate user quote]\nGateway message origin\nRecovered reply'
            db.append_message(entry.session_id, 'user', original, platform_message_id='native-legitimate')
            db.append_message(entry.session_id, 'user', original, display_kind='steer')
            db.append_message(entry.session_id, 'assistant', '♻️ Recovered reply — legitimate upstream reply')
            projected = history.read('synthetic-origin', cid)
            assert len(projected) == 3 and [row['text'] for row in projected[:2]] == [original, original]
            # The real auto-resume path is also an ordinary, unassociated native row.
            assert store.mark_resume_pending(entry.session_key, 'restart_timeout')
            ctx = SimpleNamespace(message='', persist_user_message=None, persist_user_timestamp=None,
                session_key=entry.session_key, source=entry.origin,
                history=[{'role': 'assistant', 'content': 'prior reply', 'timestamp': 1}])
            prepared = object.__new__(TurnRunner)
            prepared._runner, prepared._ctx = runner, ctx
            durable, _ = prepared._prepare_turn_message(ctx.history)
            assert durable.startswith('[System note: The previous turn was interrupted by a gateway restart;')
            db.append_message(entry.session_id, 'user', durable)
            assert any(row['text'] == durable for row in history.read('synthetic-origin', cid))
        await busy(runtime, store, history, 'unchanged', unchanged)
        passed.append('legal_history_systemnote_recovered_unchanged')

        async def conflict(cid, entry, db, agent, runner):
            await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', '真实关联输入',
                client_message_id='synthetic-owner')
            drain(agent)
            row = db.get_messages(entry.session_id)[-1]
            metadata = row['display_metadata']
            metadata[NATIVE_INPUT_KEY]['conversationId'] = 'other-conversation'
            db._execute_write(lambda connection: connection.execute(
                'UPDATE messages SET display_metadata=? WHERE id=?', (json.dumps(metadata), row['id'])))
            try:
                users(history, cid)
            except RuntimeError as exc:
                assert str(exc) == 'HOST_HISTORY_BINDING_CONFLICT'
            else:
                raise AssertionError('foreign identity was allowed')
        await busy(runtime, store, history, 'conflict', conflict)
        passed.append('wrong_conversation_fails_closed')

        async def flush_retry(cid, entry, db, agent, runner):
            from copy import deepcopy
            await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', '写入失败后重试',
                client_message_id='synthetic-flush-retry')
            messages = [{'role': 'tool', 'content': 'result', 'tool_call_id': 'retry-tool'}]
            apply_pending_steer_to_tool_results(agent, messages, 1)
            with patch.object(db, 'append_messages_batch', side_effect=RuntimeError('synthetic flush failure')):
                assert agent._flush_messages_to_session_db(messages, []) is False
            assert db.get_messages(entry.session_id) == []
            assert agent._flush_messages_to_session_db(messages, []) is True
            assert agent._flush_messages_to_session_db(messages, []) is True
            assert len([row for row in db.get_messages(entry.session_id) if row['role'] == 'user']) == 1
            assert [row['text'] for row in users(history, cid)] == ['写入失败后重试']
            # Two drain batches before one native flush keep their order without text comparison.
            messages = []
            for client in ('batch-a', 'batch-b'):
                await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', '相同正文',
                    client_message_id=client)
                messages.append({'role': 'tool', 'content': 'result', 'tool_call_id': client})
                apply_pending_steer_to_tool_results(agent, messages, 1)
            assert agent._flush_messages_to_session_db(deepcopy(messages), []) is True
            projected = users(history, cid)
            assert [row['clientMessageId'] for row in projected] == ['synthetic-flush-retry', 'batch-a', 'batch-b']
            assert len({row['messageId'] for row in projected}) == 3
        await busy(runtime, store, history, 'flush-retry', flush_retry)
        passed.append('flush_retry_multiple_batches')

        async def cancellation(cid, entry, db, agent, runner):
            from agent.turn_iteration_prep import _inject_steer_after_newest_tool_result
            await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', '已接受后取消',
                client_message_id='synthetic-cancelled')
            text = agent._drain_pending_steer()
            _inject_steer_after_newest_tool_result(agent, [], text)
            agent.clear_interrupt(hard_cancel=True)
            assert agent._drain_pending_steer() is None
            # A fresh input after stop cannot inherit the discarded batch's receipt.
            await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', '新的引导',
                client_message_id='synthetic-after-cancel')
            drain(agent)
            projected = users(history, cid)
            assert {row['clientMessageId']: row['text'] for row in projected} == {
                'synthetic-cancelled': '已接受后取消', 'synthetic-after-cancel': '新的引导'}, projected
            native = [row for row in db.get_messages(entry.session_id) if row['role'] == 'user']
            assert len(native) == 1 and native[0]['platform_message_id'] == 'synthetic-after-cancel'
            # Soft clear preserves a pending native steer (SDK's actual stop semantics).
            await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', '软清理保留',
                client_message_id='synthetic-soft-clear')
            agent.clear_interrupt()
            drain(agent)
            assert any(row['text'] == '软清理保留' for row in users(history, cid))
        await busy(runtime, store, history, 'cancellation', cancellation)
        passed.append('hard_cancel_soft_clear')

        async def fallback(cid, entry, db, agent, runner):
            from copy import deepcopy
            from agent.turn_iteration_prep import _inject_steer_after_newest_tool_result
            await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', '无工具尾部的引导',
                client_message_id='synthetic-fallback')
            text = agent._drain_pending_steer()
            _inject_steer_after_newest_tool_result(agent, [], text)
            # A flush before the queued steer is emitted must not book its batch.
            assert agent._flush_messages_to_session_db([{'role': 'assistant', 'content': 'before next turn'}], [])
            # Native finalizer hands this same carrier to the next user turn.
            leftover = agent._drain_pending_steer()
            assert agent._flush_messages_to_session_db(deepcopy([{'role': 'user', 'content': leftover}]), [])
            assert [row['text'] for row in users(history, cid)] == ['无工具尾部的引导']
        await busy(runtime, store, history, 'fallback', fallback)
        passed.append('late_steer_fallback_and_empty_flush')

        async def unsupported(cid, entry, db, agent, runner):
            from oai_hermes_adapter.runtime import _native_input_contract
            assert runtime.native_steering_available()
            # A host missing the capture protocol cannot advertise or accept steer.
            runner._session_state(entry.session_key).turn.agent = object()
            assert not runtime.native_steering_available()
            try:
                await runtime.steer_native_turn('synthetic-origin', cid, 'synthetic-generation', 'must not enter host',
                    client_message_id='unsupported-host')
            except Exception as exc:
                assert exc.code == 'STEER_UNAVAILABLE'
            else:
                raise AssertionError('unsupported host advertised steering')
            # Normal busy delivery must also be refused; Base must not fall back to interrupt.
            try:
                await runtime.dispatch_native_input('synthetic-origin', cid, 'must not enter host', 'unsupported-busy')
            except Exception as exc:
                assert exc.code in {'HOST_INPUT_REJECTED', 'STEER_UNAVAILABLE'}
            else:
                raise AssertionError('busy dispatch fell back without capture')
            assert entry.session_key not in runtime._pending_messages
            assert db.get_messages(entry.session_id) == []
            runner._session_state(entry.session_key).turn.agent = agent
            with patch('oai_hermes_adapter.runtime._native_input_contract', return_value=False):
                assert not runtime.native_steering_available()
                try:
                    await runtime.dispatch_native_input('synthetic-origin', cid, 'must not enter host', 'unverified-sdk')
                except Exception as exc:
                    assert exc.code in {'HOST_INPUT_REJECTED', 'STEER_UNAVAILABLE'}
                else:
                    raise AssertionError('an unverified SDK bypassed the gate')
            assert agent._drain_pending_steer() is None
            with patch.object(agent, 'steer', return_value=True):
                assert not runtime.native_steering_available()
            with patch('agent.prompt_builder.steer_user_row', lambda text: {'role': 'user', 'content': text}):
                assert not runtime.native_steering_available()
        await busy(runtime, store, history, 'unsupported', unsupported)
        passed.append('unsupported_sdk_and_missing_capture_fail_closed')

        async def priority(cid, entry, db, agent, runner):
            # Real runner priority steering bypasses the adapter's busy callback.
            async def handle(event):
                runner._hm_busy_steer(event, agent, entry.session_key)
                event._gateway_accepted = True
            runtime.set_message_handler(handle)
            runtime._active_sessions.pop(entry.session_key, None)
            await runtime.dispatch_native_input('synthetic-origin', cid, '优先入口原稿', 'synthetic-priority')
            drain(agent)
            assert [(row['text'], row['clientMessageId']) for row in users(history, cid)] == [
                ('优先入口原稿', 'synthetic-priority')]
        await busy(runtime, store, history, 'priority', priority)
        passed.append('priority_busy_public_intake')

        # Removal uses the existing native management contract and retires this KV namespace too.
        cid, entry, db = bound_conversation(runtime, store, 'delete-original')
        staged = history.stage_native_input({'accountId': 'synthetic-origin', 'conversationId': cid,
            'sessionKey': entry.session_key, 'clientMessageId': 'synthetic-delete', 'text': 'synthetic original',
            'timestamp': 1, 'source': {'platform': GATEWAY_PLATFORM_ID}})
        history.accept_native_input(staged)
        assert db.get_meta(staged['key'])
        history.manage('synthetic-origin', cid, 'delete')
        assert db.get_meta(staged['key']) is None
        passed.append('conversation_delete_retires_originals')
        print(json.dumps({'passed': passed}, ensure_ascii=False))
    finally:
        runtime.services.core.close()


asyncio.run(main())
