import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PhoneMcpBridge } from '../src/bridge.mjs';

const fixed = { hostId: 'phone-a', backend: 'harness', workspaceId: 'phone-capabilities' };
const authority = { snapshot: async () => 'epoch' };

function runtimeFor({ workspaces = [{ backend: 'harness', workspaceId: 'phone-capabilities' }], unavailable = [], connected = 'phone-a', recovery = 0, connectedFlag = true } = {}) {
  const calls = [];
  let state = { connected: connectedFlag, connectedTargetDeviceId: connected, recoveryGeneration: recovery };
  return {
    calls,
    status: () => state,
    setState(next) { state = { ...state, ...next }; },
    async discoverToolWorkspaces(hostId) { calls.push(['discover', hostId]); return { workspaces, unavailable }; },
    async runSessionTool(operation, args) { calls.push([operation, args]); return { status: 'accepted', sessionId: 'task-1' }; },
    async devices() { return [{ deviceId: 'phone-a', name: 'A', online: true }, { deviceId: 'phone-b', name: 'B', online: true }]; },
  };
}

test('外部写入只允许当前已连接 phone，不触发另一个 Host 探测', async () => {
  const runtime = runtimeFor();
  const bridge = new PhoneMcpBridge(runtime, authority);
  await assert.rejects(bridge.call('start', { ...fixed, hostId: 'phone-b', request: { request_id: 'request-phone-b-0001', op: 'ui.tap', args: {} } }), /PHONE_TARGET_CONNECTION_MISMATCH|PHONE_TARGET_NOT_CONNECTED/u);
  assert.deepEqual(runtime.calls, []);
});

test('连接断开后写入门禁必须立即拒绝，不得回退沿用旧的已连接目标', async () => {
  const runtime = runtimeFor({ connected: 'phone-a', connectedFlag: true });
  const bridge = new PhoneMcpBridge(runtime, authority);
  assert.deepEqual(await bridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });
  // 模拟真机连接断开（recoveryGeneration 尚未自增）
  runtime.setState({ connected: false, connectedTargetDeviceId: undefined });
  await assert.rejects(bridge.call('start', { ...fixed, request: { request_id: 'request-after-drop01', op: 'ui.dump_tree', args: {} } }), /PHONE_TARGET_NOT_CONNECTED/u);
  await assert.rejects(bridge.call('cancel', { ...fixed, taskSessionId: 'task-1', request_id: 'cancel-after-drop001', targetRequestId: 'request-after-drop01' }), /PHONE_TARGET_NOT_CONNECTED/u);
  await assert.rejects(bridge.call('delivery', { ...fixed, request_id: 'delivery-after-drop1', deliveryId: `delivery_${'1'.repeat(32)}`, eventId: `push_${'2'.repeat(64)}`, payloadDigest: `sha256:${'3'.repeat(64)}`, acknowledge: false }), /PHONE_TARGET_NOT_CONNECTED/u);
  assert.deepEqual(runtime.calls, []);
});

test('切换桌面目标后旧目标读写均受限且新目标仍可正常使用', async () => {
  const runtime = runtimeFor({ connected: 'phone-a', connectedFlag: true });
  const bridge = new PhoneMcpBridge(runtime, authority);
  assert.deepEqual(await bridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });
  runtime.setState({ connected: true, connectedTargetDeviceId: 'phone-b' });
  await assert.rejects(bridge.call('start', { ...fixed, hostId: 'phone-a', request: { request_id: 'request-old-target01', op: 'ui.dump_tree', args: {} } }), /PHONE_TARGET_NOT_CONNECTED/u);
  await assert.rejects(bridge.call('request_result', { ...fixed, hostId: 'phone-a', taskSessionId: 'task-1', targetRequestId: 'request-old-target01' }), /PHONE_TARGET_CONNECTION_MISMATCH/u);
  assert.deepEqual(runtime.calls, []);
  const listed = await bridge.call('list_devices', {});
  assert.equal(listed.devices.find(item => item.hostId === 'phone-b').workspaceId, 'phone-capabilities');
  assert.equal(listed.devices.find(item => item.hostId === 'phone-a').state, 'not_probed');
});

test('连接代次变化或调用中断开后旧结果保留未知含义且不自动重投，重连后允许查询原请求', async () => {
  const runtime = runtimeFor();
  const bridge = new PhoneMcpBridge(runtime, authority);
  const input = { ...fixed, request: { request_id: 'request-race-0000001', op: 'ui.dump_tree', args: {} } };
  let createCalls = 0;
  let sendCalls = 0;
  runtime.runSessionTool = async (operation, args) => {
    runtime.calls.push([operation, args]);
    if (operation === 'remote_create_session') {
      createCalls++;
      return { status: 'accepted', sessionId: 'task-1' };
    }
    if (operation === 'remote_send_message') {
      sendCalls++;
      runtime.setState({ connected: true, connectedTargetDeviceId: 'phone-a', recoveryGeneration: 1 });
      return { status: 'accepted' };
    }
    if (operation === 'remote_read_session') {
      return {
        status: 'idle',
        records: [{
          truncated: false,
          json: JSON.stringify({
            event: {
              type: 'assistant/message',
              data: { message: { content: [{ type: 'text', text: JSON.stringify({ request_id: 'request-race-0000001', taskSessionId: 'task-1', state: 'outcome_unknown', execution: { status: 'unknown' }, resultAvailable: false }) }] } },
            },
          }),
        }],
      };
    }
    return { status: 'accepted' };
  };
  await assert.rejects(
    bridge.call('start', input),
    (error) => error.message === 'BRIDGE_CONNECTION_CHANGED' && error.taskSessionId === 'task-1' && error.state === 'outcome_unknown',
  );
  // 重连后同 request_id 仅观察回执，绝不重发副作用
  const observed = await bridge.call('start', input);
  assert.equal(observed.state, 'outcome_unknown');
  assert.equal(observed.taskSessionId, 'task-1');
  assert.equal(createCalls, 1);
  assert.equal(sendCalls, 1);
});

test('发现隐藏已确认无手机工作区的主机，保留手机与 not_probed、unavailable、offline 候选', async () => {
  const runtime = runtimeFor({ workspaces: [{ backend: 'harness', workspaceId: 'phone-capabilities' }], unavailable: [], connected: 'phone-a' });
  const bridge = new PhoneMcpBridge(runtime, authority);
  const listed = await bridge.call('list_devices', {});
  assert.equal(listed.devices.length, 2);
  assert.equal(listed.devices.find(item => item.hostId === 'phone-a').workspaceId, 'phone-capabilities');
  assert.equal(listed.devices.find(item => item.hostId === 'phone-b').state, 'not_probed');
  assert.deepEqual(runtime.calls, [['discover', 'phone-a']]);

  // 未绑定活动连接时逐台发现，区分已确认无手机工作区与暂时无法确认。
  const multiRuntime = {
    status: () => ({ connected: false, connectedTargetDeviceId: undefined, recoveryGeneration: 0 }),
    async devices() {
      return [
        { deviceId: 'phone-ok', name: 'OK', online: true },
        { deviceId: 'phone-unavail', name: 'Unavail', online: true },
        { deviceId: 'desktop', name: 'Desktop', online: true },
        { deviceId: 'phone-offline', name: 'Offline', online: false },
      ];
    },
    async discoverToolWorkspaces(hostId) {
      if (hostId === 'phone-ok') return { workspaces: [{ backend: 'harness', workspaceId: 'phone-capabilities' }], unavailable: [] };
      if (hostId === 'phone-unavail') return { workspaces: [], unavailable: [{ backend: 'harness', code: 'BACKEND_UNAVAILABLE' }] };
      if (hostId === 'desktop') return { workspaces: [{ backend: 'harness', workspaceId: 'desktop-workspace' }], unavailable: [] };
      throw new Error('SHOULD_NOT_PROBE_OFFLINE');
    },
  };
  const multiBridge = new PhoneMcpBridge(multiRuntime, authority);
  const multiListed = await multiBridge.call('list_devices', {});
  assert.equal(multiListed.devices.find(item => item.hostId === 'phone-ok').workspaceId, 'phone-capabilities');
  assert.equal(multiListed.devices.find(item => item.hostId === 'phone-unavail').state, 'unavailable');
  assert.deepEqual(multiListed.devices.map(item => item.hostId), ['phone-ok', 'phone-unavail', 'phone-offline']);
  await assert.rejects(multiBridge.call('capabilities', { ...fixed, hostId: 'desktop' }), /PHONE_WORKSPACE_MISSING/u);
  assert.equal(multiListed.devices.find(item => item.hostId === 'phone-offline').state, 'offline');
});

test('外部写入门禁不把未连接的只读候选当成可写入目标', async () => {
  const noStatusRuntime = runtimeFor({ workspaces: [{ backend: 'harness', workspaceId: 'phone-capabilities' }], connected: undefined });
  delete noStatusRuntime.status;
  const bridge = new PhoneMcpBridge(noStatusRuntime, authority);
  await assert.rejects(bridge.call('cancel', { ...fixed, taskSessionId: 'task-1', request_id: 'cancel-not-connected1', targetRequestId: 'target-not-connected1' }), /PHONE_TARGET_NOT_CONNECTED/u);
  assert.deepEqual(noStatusRuntime.calls, []);
});

test('request.cancel 精确携带原任务与原请求，不取消同任务其他请求', async () => {
  const runtime = runtimeFor();
  const bridge = new PhoneMcpBridge(runtime, authority);
  await bridge.call('cancel', { ...fixed, taskSessionId: 'task-1', request_id: 'cancel-exact-0001', targetRequestId: 'target-exact-0001' });
  const sent = runtime.calls.find(([operation]) => operation === 'remote_send_message');
  assert.equal(sent[1].sessionId, 'task-1');
  assert.deepEqual(JSON.parse(sent[1].message), { request_id: 'cancel-exact-0001', op: 'request.cancel', args: { targetRequestId: 'target-exact-0001' } });
});
