import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
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

function receiptRuntime({
  taskHistory = [],
  taskStatus = 'idle',
  taskReadError = undefined,
  busStatusReply = undefined,
  downloadHandler = undefined,
  scopedFiles = undefined,
  requireSessionError = undefined,
  statusOverrides = undefined,
} = {}) {
  const calls = [];
  const busRecords = [];
  let state = { connected: true, connectedTargetDeviceId: 'phone-a', recoveryGeneration: 0, ...statusOverrides };
  const runtime = {
    calls,
    status: () => state,
    setState(next) { state = { ...state, ...next }; },
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
        if (args.sessionId === 'control-bus') {
          if (typeof taskReadError === 'function') {
            const maybeError = taskReadError(args);
            if (maybeError) throw maybeError;
          }
          return { status: 'idle', records: busRecords };
        }
        if (taskReadError) {
          const error = typeof taskReadError === 'function' ? taskReadError(args) : taskReadError;
          if (error) throw error;
        }
        return { status: taskStatus, records: taskHistory };
      }
      return {};
    },
  };
  if (scopedFiles) {
    runtime.sessionTools = {
      async requireSession(_gateway, args) {
        calls.push({ operation: 'requireSession', args });
        if (requireSessionError) throw requireSessionError;
      },
    };
    runtime.withToolRemote = async (hostId, signal, query) => {
      calls.push({ operation: 'withToolRemote', args: { hostId } });
      return query({ features: { remoteGateway: true } }, signal);
    };
    runtime.remoteTypertGateway = () => ({
      async invoke(request) {
        calls.push({ operation: `${request.namespace}/${request.method}`, args: request.args });
        return scopedFiles(request.method, request.args);
      },
    });
  }
  return runtime;
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

test('超长回执直接在内存中分块读取并校验字节长度、SHA-256 与内层身份，不触发落盘下载；显式 read_artifact 保持下载交付', async () => {
  const payloadLength = 300_000;
  const fullPayload = {
    request_id: 'target-oversized-001',
    taskSessionId: 'task-1',
    state: 'completed',
    execution: { status: 'succeeded' },
    resultAvailable: true,
    result: { snapshotId: 'snap-long', payload: 'x'.repeat(payloadLength) },
  };
  const raw = Buffer.from(JSON.stringify(fullPayload), 'utf8');
  const version = `sha256:${createHash('sha256').update(raw).digest('hex')}`;

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

  const memoryFileServer = (bytes, reportedVersion = version, { mutateOnSecondStat = false } = {}) => {
    let statCalls = 0;
    return (method, args) => {
      assert.equal(args.workspaceFileScopeId, 'task-1');
      assert.equal(args.path, 'artifacts/result-target-oversized-001');
      const absolutePath = `/phone-capabilities/${args.workspaceFileScopeId}/${args.path}`;
      if (method === 'stat') {
        statCalls += 1;
        return {
          absolutePath,
          version: mutateOnSecondStat && statCalls > 1 ? `sha256:${'0'.repeat(64)}` : reportedVersion,
          bytes: bytes.length,
        };
      }
      if (method === 'readBytes') {
        const { offset, length } = args.range;
        const end = Math.min(bytes.length, offset + length);
        return {
          absolutePath,
          version: reportedVersion,
          bytes: bytes.length,
          offset,
          data: new Uint8Array(bytes.subarray(offset, end)),
          eof: end === bytes.length,
        };
      }
      throw new Error(`UNEXPECTED_METHOD_${method}`);
    };
  };

  let explicitDownloadCalls = 0;
  const validRuntime = receiptRuntime({
    taskHistory: [assistantRecord(summary)],
    scopedFiles: memoryFileServer(raw),
    downloadHandler: async (args) => {
      explicitDownloadCalls += 1;
      return { status: 'downloaded', localPath: `/tmp/explicit/${args.path}`, bytes: 18 };
    },
  });
  const validBridge = new PhoneMcpBridge(validRuntime, authority);
  const loaded = await validBridge.call('request_result', {
    ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-oversized-001',
  });
  assert.equal(loaded.result.snapshotId, 'snap-long');
  assert.equal(loaded.result.payload.length, payloadLength);
  assert.equal(loaded.resultArtifact.version, version);
  // 自动读取长回执绝不调用落盘到 Downloads 的 remote_download_file，且应完成分块读取
  assert.equal(explicitDownloadCalls, 0);
  assert.equal(validRuntime.calls.some(call => call.operation === 'remote_download_file'), false);
  assert.ok(validRuntime.calls.filter(call => call.operation === 'workspaceFiles/readBytes').length >= 2);
  assert.equal(validRuntime.calls.filter(call => call.operation === 'workspaceFiles/stat').length, 2);

  // 显式 android_read_artifact 仍保持既有 remote_download_file 下载交付语义
  const explicitArtifact = await validBridge.call('read_artifact', {
    ...fixed, taskSessionId: 'task-1', artifactId: 'som-shot-1',
  });
  assert.equal(explicitDownloadCalls, 1);
  assert.equal(explicitArtifact.status, 'downloaded');

  // 篡改一个字节（长度不变但 SHA-256 变化）必须拒绝
  const tampered = Buffer.from(raw);
  tampered[tampered.length - 4] = tampered[tampered.length - 4] === 0x78 ? 0x79 : 0x78;
  const tamperedRuntime = receiptRuntime({
    taskHistory: [assistantRecord(summary)],
    scopedFiles: memoryFileServer(tampered, version),
  });
  await assert.rejects(
    new PhoneMcpBridge(tamperedRuntime, authority).call('request_result', {
      ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-oversized-001',
    }),
    (error) => error.message === 'BRIDGE_ARTIFACT_INTEGRITY_FAILED' && error.state === 'outcome_unknown',
  );

  // 读取期间远端文件版本发生变更必须拒绝
  const changedDuringReadRuntime = receiptRuntime({
    taskHistory: [assistantRecord(summary)],
    scopedFiles: memoryFileServer(raw, version, { mutateOnSecondStat: true }),
  });
  await assert.rejects(
    new PhoneMcpBridge(changedDuringReadRuntime, authority).call('request_result', {
      ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-oversized-001',
    }),
    (error) => error.message === 'BRIDGE_ARTIFACT_INTEGRITY_FAILED' && error.state === 'outcome_unknown',
  );

  // 内层 JSON 的 request_id 不匹配时必须拒绝伪造
  const forgedRaw = Buffer.from(JSON.stringify({ ...fullPayload, request_id: 'other-request-99999' }), 'utf8');
  const forgedVersion = `sha256:${createHash('sha256').update(forgedRaw).digest('hex')}`;
  const forgedSummary = { ...summary, resultArtifact: { ...summary.resultArtifact, bytes: forgedRaw.length, version: forgedVersion } };
  const forgedRuntime = receiptRuntime({
    taskHistory: [assistantRecord(forgedSummary)],
    scopedFiles: memoryFileServer(forgedRaw, forgedVersion),
  });
  await assert.rejects(
    new PhoneMcpBridge(forgedRuntime, authority).call('request_result', {
      ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-oversized-001',
    }),
    (error) => error.message === 'BRIDGE_RECEIPT_IDENTITY_MISMATCH' && error.state === 'outcome_unknown',
  );

  // 缺少内存受限文件通道时绝不回退调用 remote_download_file
  let fallbackDownloadCalled = false;
  const noMemoryRuntime = receiptRuntime({
    taskHistory: [assistantRecord(summary)],
    downloadHandler: async () => { fallbackDownloadCalled = true; return { status: 'downloaded', localPath: '/tmp/should-not-run', bytes: raw.length }; },
  });
  await assert.rejects(
    new PhoneMcpBridge(noMemoryRuntime, authority).call('request_result', {
      ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-oversized-001',
    }),
    (error) => error.message === 'BRIDGE_ARTIFACT_INTEGRITY_FAILED' && error.state === 'outcome_unknown',
  );
  assert.equal(fallbackDownloadCalled, false);
});

test('旧任务退出目录报 SESSION_NOT_IN_WORKSPACE 时回查同一目标 control-bus，同时保留授权与连接错误失败语义', async () => {
  // 1. 手机重启后原任务退出目录，remote_read_session 抛出 SESSION_NOT_IN_WORKSPACE -> 回查 control-bus request.status
  const retiredRuntime = receiptRuntime({
    taskReadError: (args) => (args.sessionId === 'retired-task-1' ? new Error('SESSION_NOT_IN_WORKSPACE') : undefined),
    busStatusReply: (query) => ({
      request_id: query.request_id,
      taskSessionId: 'control-bus',
      state: 'completed',
      execution: { status: 'succeeded' },
      result: {
        request_id: query.args.targetRequestId,
        taskSessionId: query.args.targetTaskSessionId,
        state: 'completed',
        execution: { status: 'succeeded' },
        resultAvailable: false,
      },
    }),
  });
  const retiredResult = await new PhoneMcpBridge(retiredRuntime, authority).call('request_result', {
    ...fixed, taskSessionId: 'retired-task-1', targetRequestId: 'target-retired-0001',
  });
  assert.equal(retiredResult.request_id, 'target-retired-0001');
  assert.equal(retiredResult.taskSessionId, 'retired-task-1');
  assert.equal(retiredResult.state, 'completed');
  assert.equal(retiredResult.execution.status, 'succeeded');
  assert.equal(retiredResult.resultAvailable, false);
  const statusCalls = retiredRuntime.calls.filter(call => call.operation === 'remote_send_message');
  assert.equal(statusCalls.length, 1);
  assert.equal(statusCalls[0].args.sessionId, 'control-bus');
  assert.deepEqual(JSON.parse(statusCalls[0].args.message).args, {
    targetRequestId: 'target-retired-0001',
    targetTaskSessionId: 'retired-task-1',
  });

  // 2. control-bus 自身报 SESSION_NOT_IN_WORKSPACE 绝不递归回查
  const busMissingRuntime = receiptRuntime({
    taskReadError: () => new Error('SESSION_NOT_IN_WORKSPACE'),
  });
  await assert.rejects(
    new PhoneMcpBridge(busMissingRuntime, authority).call('request_result', {
      ...fixed, taskSessionId: 'control-bus', targetRequestId: 'target-bus-missing1',
    }),
    /SESSION_NOT_IN_WORKSPACE/u,
  );
  assert.equal(busMissingRuntime.calls.some(call => call.operation === 'remote_send_message'), false);

  // 3. 授权失败、连接断开、工作区缺失等非目录缺失错误必须保留原失败语义，绝不走 control-bus 回查
  for (const [label, errorCode] of [
    ['auth-revoked', 'DEVICE_REVOKED'],
    ['remote-authority', 'REMOTE_AUTHORITY_CHANGED'],
    ['transport-closed', 'TRANSPORT_CLOSED'],
    ['workspace-missing', 'WORKSPACE_NOT_FOUND'],
  ]) {
    const failRuntime = receiptRuntime({ taskReadError: new Error(errorCode) });
    await assert.rejects(
      new PhoneMcpBridge(failRuntime, authority).call('request_result', {
        ...fixed, taskSessionId: 'task-err', targetRequestId: 'target-fail-0000001',
      }),
      new RegExp(errorCode, 'u'),
      label,
    );
    assert.equal(failRuntime.calls.some(call => call.operation === 'remote_send_message'), false, label);
  }

  // 4. 抛出 SESSION_NOT_IN_WORKSPACE 的同时若发生连接变更或授权失效，必须立刻按连接/授权错误失败，不得进入回查
  const racingRuntime = receiptRuntime({
    taskReadError: (args) => {
      if (args.sessionId === 'retired-race-1') {
        racingRuntime.setState({ connected: false, connectedTargetDeviceId: undefined });
        return new Error('SESSION_NOT_IN_WORKSPACE');
      }
      return undefined;
    },
    busStatusReply: () => { throw new Error('SHOULD_NOT_QUERY_BUS'); },
  });
  await assert.rejects(
    new PhoneMcpBridge(racingRuntime, authority).call('request_result', {
      ...fixed, taskSessionId: 'retired-race-1', targetRequestId: 'target-race-0000001',
    }),
    /BRIDGE_CONNECTION_CHANGED/u,
  );
  assert.equal(racingRuntime.calls.some(call => call.operation === 'remote_send_message'), false);
});

test('control-bus 查询未完成、失败或传输未知时，只返回查询自身状态', async () => {
  const fixedInput = { ...fixed, taskSessionId: 'task-1', targetRequestId: 'target-bus-state0001' };
  const pendingRuntime = receiptRuntime();
  const pending = await new PhoneMcpBridge(pendingRuntime, authority).call('request_result', fixedInput);
  assert.equal(pending.query.state, 'accepted');

  const failure = { code: 'PERMISSION_DENIED', message: '查询无权访问回执。' };
  const failedRuntime = receiptRuntime({ busStatusReply: query => ({
    request_id: query.request_id,
    taskSessionId: 'control-bus',
    state: 'failed',
    execution: { status: 'not_started' },
    error: failure,
  }) });
  const failed = await new PhoneMcpBridge(failedRuntime, authority).call('request_result', fixedInput);
  assert.equal(failed.query.state, 'failed');
  assert.deepEqual(failed.query.error, failure);
  assert.deepEqual(failed.query.execution, { status: 'not_started' });

  const unknownRuntime = receiptRuntime();
  const run = unknownRuntime.runSessionTool;
  unknownRuntime.runSessionTool = async (operation, args) => {
    if (operation === 'remote_send_message' && args.sessionId === 'control-bus') {
      unknownRuntime.calls.push({ operation, args });
      return { status: 'unknown', operationId: args.operationId };
    }
    return run(operation, args);
  };
  const unknown = await new PhoneMcpBridge(unknownRuntime, authority).call('request_result', fixedInput);
  assert.equal(unknown.query.state, 'outcome_unknown');
  assert.equal(unknown.query.receipt.status, 'unknown');

  for (const [runtime, result] of [[pendingRuntime, pending], [failedRuntime, failed], [unknownRuntime, unknown]]) {
    const rpc = runtime.calls.find(call => call.operation === 'remote_send_message');
    assert.equal(result.request_id, fixedInput.targetRequestId);
    assert.equal(result.taskSessionId, fixedInput.taskSessionId);
    assert.equal(result.resultAvailable, false);
    assert.equal(Object.hasOwn(result, 'state'), false);
    assert.equal(Object.hasOwn(result, 'error'), false);
    assert.equal(result.query.taskSessionId, 'control-bus');
    assert.equal(result.query.request_id, rpc.args.operationId);
    assert.notEqual(result.query.request_id, result.request_id);
  }

  const matched = receiptRuntime({ busStatusReply: query => ({
    request_id: query.request_id,
    taskSessionId: 'control-bus',
    state: 'completed',
    result: { request_id: fixedInput.targetRequestId, taskSessionId: fixedInput.taskSessionId,
      state: 'failed', error: { code: 'EXECUTION_FAILED' } },
  }) });
  const result = await new PhoneMcpBridge(matched, authority).call('request_result', fixedInput);
  assert.equal(result.state, 'failed');
  assert.deepEqual(result.error, { code: 'EXECUTION_FAILED' });
  assert.equal(Object.hasOwn(result, 'query'), false);
});
