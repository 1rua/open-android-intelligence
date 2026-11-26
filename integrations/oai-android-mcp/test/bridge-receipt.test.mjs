import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, rename, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PhoneMcpBridge } from '../src/bridge.mjs';

const fixed = { hostId: 'phone-a', backend: 'harness', workspaceId: 'phone-capabilities' };
const authority = { snapshot: async () => 'epoch' };

function assistantRecord(payload, truncated = false) {
  return {
    truncated,
    json: JSON.stringify({
      event: {
        type: 'assistant/message',
        data: { message: { content: [{ type: 'text', text: JSON.stringify(payload) }] } },
      },
    }),
  };
}

function receiptRuntime({ taskHistory = [], taskStatus = 'idle', busStatusReply = undefined, downloadHandler = undefined } = {}) {
  const calls = [];
  const busRecords = [];
  return {
    calls,
    status: () => ({ connected: true, connectedTargetDeviceId: 'phone-a', recoveryGeneration: 0 }),
    async discoverToolWorkspaces() { return { workspaces: [{ backend: 'harness', workspaceId: 'phone-capabilities' }] }; },
    async runSessionTool(operation, args) {
      calls.push({ operation, args });
      if (operation === 'remote_download_file') {
        if (!downloadHandler) throw new Error('DOWNLOAD_NOT_CONFIGURED');
        return downloadHandler(args);
      }
      if (operation === 'remote_send_message') {
        const message = JSON.parse(args.message);
        if (message.op === 'request.status' && busStatusReply) {
          busRecords.push(assistantRecord(busStatusReply(message)));
        }
        return { status: 'accepted' };
      }
      if (operation === 'remote_wait_sessions') return { sessions: [{ status: 'idle' }] };
      if (operation === 'remote_read_session') {
        if (args.sessionId === 'control-bus') return { status: 'idle', records: busRecords };
        return { status: taskStatus, records: taskHistory };
      }
      return {};
    },
  };
}

test('普通历史缺失或遇到 truncated:true 时回查 control-bus request.status，解包并核对内层身份', async () => {
  for (const [label, taskStatus, taskHistory] of [
    ['history-missing', 'idle', []],
    ['history-truncated', 'running', [{ truncated: true, json: '{"event":{"type":"assistant/message"' }]],
  ]) {
    const runtime = receiptRuntime({
      taskStatus,
      taskHistory,
      busStatusReply: (query) => ({
        request_id: query.request_id,
        taskSessionId: 'control-bus',
        state: 'completed',
        execution: { status: 'succeeded' },
        result: {
          request_id: query.args.targetRequestId,
          taskSessionId: query.args.targetTaskSessionId,
          state: 'outcome_unknown',
          execution: { status: 'unknown' },
          resultAvailable: false,
        },
      }),
    });
    const bridge = new PhoneMcpBridge(runtime, authority);
    const result = await bridge.call('request_result', { ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-request-0001' });
    assert.equal(result.request_id, 'target-request-0001', label);
    assert.equal(result.taskSessionId, 'task-1', label);
    assert.equal(result.state, 'outcome_unknown', label);
    assert.equal(result.execution.status, 'unknown', label);
    assert.equal(result.resultAvailable, false, label);
    const statusCalls = runtime.calls.filter(call => call.operation === 'remote_send_message');
    assert.equal(statusCalls.length, 1, label);
    assert.equal(statusCalls[0].args.sessionId, 'control-bus', label);
    assert.equal(JSON.parse(statusCalls[0].args.message).op, 'request.status', label);
  }
});

test('pending 与权限失败只返回原状态，不回退 request.status', async () => {
  for (const [status, records] of [
    ['needs_user_input', []],
    ['running', []],
    ['idle', [assistantRecord({ request_id: 'target-pending-0001', taskSessionId: 'task-1', state: 'denied', execution: { status: 'not_started' }, error: { code: 'PERMISSION_DENIED', message: 'denied' } })]],
  ]) {
    const runtime = receiptRuntime({ taskHistory: records, taskStatus: status });
    const bridge = new PhoneMcpBridge(runtime, authority);
    const result = await bridge.call('request_result', { ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-pending-0001' });
    assert.equal(runtime.calls.some(call => call.operation === 'remote_send_message'), false);
    if (status === 'needs_user_input') assert.equal(result.state, 'awaiting_confirmation');
    if (status === 'idle') assert.equal(result.state, 'denied');
  }
});

test('回执内层 taskSessionId 或 request_id 不一致时拒绝伪造结果（含直接 request_status 与回退）', async () => {
  for (const wrongInner of [
    { request_id: 'other-request-00001', taskSessionId: 'task-1', state: 'completed', resultAvailable: false },
    { request_id: 'target-bad-0000001', taskSessionId: 'task-other', state: 'completed', resultAvailable: false },
  ]) {
    const runtime = receiptRuntime({
      taskHistory: [],
      busStatusReply: (query) => ({
        request_id: query.request_id,
        taskSessionId: 'control-bus',
        state: 'completed',
        execution: { status: 'succeeded' },
        result: wrongInner,
      }),
    });
    const bridge = new PhoneMcpBridge(runtime, authority);
    await assert.rejects(
      bridge.call('request_result', { ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-bad-0000001' }),
      (error) => error.message === 'BRIDGE_RECEIPT_IDENTITY_MISMATCH' && error.state === 'outcome_unknown',
    );
    await assert.rejects(
      bridge.call('request_status', { ...fixed, taskSessionId: 'task-1', request_id: 'status-bad-00000001', targetRequestId: 'target-bad-0000001' }),
      (error) => error.message === 'BRIDGE_RECEIPT_IDENTITY_MISMATCH' && error.state === 'outcome_unknown',
    );
  }
});

test('超长回执经同任务私有产物读取并严格校验字节长度、SHA-256 与内层身份', async (t) => {
  const dir = await mkdtemp(join(tmpdir(), 'oai-android-mcp-artifact-'));
  t.after(async () => {
    const trash = join(tmpdir(), 'oai-android-mcp-trash');
    await mkdir(trash, { recursive: true });
    await rename(dir, join(trash, `artifact-${Date.now()}`));
  });
  const fullPayload = {
    request_id: 'target-oversized-001',
    taskSessionId: 'task-1',
    state: 'completed',
    execution: { status: 'succeeded' },
    resultAvailable: true,
    result: { snapshotId: 'snap-long', payload: 'x'.repeat(20_000) },
  };
  const raw = Buffer.from(JSON.stringify(fullPayload), 'utf8');
  const version = `sha256:${createHash('sha256').update(raw).digest('hex')}`;
  const validPath = join(dir, 'result-valid.json');
  await writeFile(validPath, raw);

  const summary = {
    request_id: 'target-oversized-001',
    taskSessionId: 'task-1',
    state: 'completed',
    execution: { status: 'succeeded' },
    resultAvailable: true,
    resultArtifact: {
      artifactId: 'result-target-oversized-001',
      path: 'artifacts/result-target-oversized-001',
      mimeType: 'application/json',
      bytes: raw.length,
      version,
    },
  };

  const validRuntime = receiptRuntime({
    taskHistory: [assistantRecord(summary)],
    downloadHandler: async (args) => {
      assert.equal(args.sessionId, 'task-1');
      assert.equal(args.path, 'artifacts/result-target-oversized-001');
      return { status: 'downloaded', localPath: validPath, bytes: raw.length };
    },
  });
  const loaded = await new PhoneMcpBridge(validRuntime, authority).call('request_result', {
    ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-oversized-001',
  });
  assert.equal(loaded.result.snapshotId, 'snap-long');
  assert.equal(loaded.result.payload.length, 20_000);
  assert.equal(loaded.resultArtifact.version, version);

  // 篡改一个字节（长度不变但 SHA-256 变化）必须拒绝
  const tampered = Buffer.from(raw);
  tampered[tampered.length - 4] = tampered[tampered.length - 4] === 0x78 ? 0x79 : 0x78;
  const tamperedPath = join(dir, 'result-tampered.json');
  await writeFile(tamperedPath, tampered);
  const tamperedRuntime = receiptRuntime({
    taskHistory: [assistantRecord(summary)],
    downloadHandler: async () => ({ status: 'downloaded', localPath: tamperedPath, bytes: raw.length }),
  });
  await assert.rejects(
    new PhoneMcpBridge(tamperedRuntime, authority).call('request_result', {
      ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-oversized-001',
    }),
    (error) => error.message === 'BRIDGE_ARTIFACT_INTEGRITY_FAILED' && error.state === 'outcome_unknown',
  );
});

test('回查 control-bus 未完成或传输未知时忠实保留 accepted 或 outcome_unknown，不误抛身份不匹配', async () => {
  // 1. control-bus 尚未可见 request.status 回复 -> 返回 accepted / resultAvailable: false
  const pendingBusRuntime = receiptRuntime({ taskStatus: 'idle', taskHistory: [] });
  const pendingResult = await new PhoneMcpBridge(pendingBusRuntime, authority).call('request_result', {
    ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-bus-pending01',
  });
  assert.equal(pendingResult.request_id, 'target-bus-pending01');
  assert.equal(pendingResult.taskSessionId, 'task-1');
  assert.equal(pendingResult.state, 'accepted');
  assert.equal(pendingResult.resultAvailable, false);

  // 2. control-bus 的 remote_send_message 返回非 accepted（如 unknown）-> 忠实保留 outcome_unknown
  const unknownBusRuntime = receiptRuntime({ taskStatus: 'idle', taskHistory: [] });
  const origRun = unknownBusRuntime.runSessionTool.bind(unknownBusRuntime);
  unknownBusRuntime.runSessionTool = async (operation, args) => {
    if (operation === 'remote_send_message' && args.sessionId === 'control-bus') {
      unknownBusRuntime.calls.push({ operation, args });
      return { status: 'unknown' };
    }
    return origRun(operation, args);
  };
  const unknownResult = await new PhoneMcpBridge(unknownBusRuntime, authority).call('request_result', {
    ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-bus-unknown01',
  });
  assert.equal(unknownResult.request_id, 'target-bus-unknown01');
  assert.equal(unknownResult.taskSessionId, 'task-1');
  assert.equal(unknownResult.state, 'outcome_unknown');
  assert.equal(unknownResult.resultAvailable, false);
});
