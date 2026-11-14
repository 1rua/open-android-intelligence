import { createServer } from 'node:http';
import { once } from 'node:events';
import test from 'node:test';
import assert from 'node:assert/strict';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { PhoneMcpBridge, registerPhoneMcpRoute } from '../src/bridge.mjs';

test('真实 Stdio SDK tools/list 与 tools/call 严格保持 android 命名和明确任务', async () => {
  const calls = [];
  const bridge = new PhoneMcpBridge({
    async devices() { return [{ deviceId: 'phone-1', name: '隔离手机' }]; },
    async discoverToolWorkspaces() { return { workspaces: [{ backend: 'harness', workspaceId: 'phone-capabilities' }] }; },
    async runSessionTool(operation, args) {
      calls.push({ operation, args });
      if (operation === 'remote_create_session') return { status: 'accepted', sessionId: 'task-explicit' };
      if (operation === 'remote_send_message') return { status: 'accepted' };
      if (operation === 'remote_read_session') return { status: 'needs_user_input', records: [] };
      return { sessions: [] };
    },
  }, { snapshot: () => 'isolated-authority-1' });
  let handler;
  const registration = registerPhoneMcpRoute({ bridge, bearer: 'test-bearer-0001',
    connection: { requestRejection: () => undefined }, webServer: { register(route) { handler = route.handler; return () => {}; } } });
  // 测试专用 listener；生产只注册 DSH 已有的 WebServer。
  const http = createServer((req, res) => handler(req, res));
  http.listen(0, '127.0.0.1'); await once(http, 'listening');
  const client = new Client({ name: 'phone-mcp-test', version: '1.0.0' });
  const transport = new StdioClientTransport({ command: process.execPath, args: [new URL('../src/stdio.mjs', import.meta.url).pathname],
    env: { ...process.env, OAI_ANDROID_BRIDGE_URL: `http://127.0.0.1:${http.address().port}`, OAI_ANDROID_BRIDGE_BEARER: registration.bearer }, stderr: 'pipe' });
  try {
    await client.connect(transport);
    const listed = await client.listTools();
    assert.ok(listed.tools.length >= 15); assert.ok(listed.tools.every(tool => tool.name.startsWith('android_')));
    assert.ok(!listed.tools.some(tool => tool.name.includes('delegate') || tool.name.includes('rpc')));
    assert.ok(listed.tools.some(tool => tool.name === 'android_read_delivery'));
    assert.ok(listed.tools.some(tool => tool.name === 'android_ack_delivery'));
    const status = await client.callTool({ name: 'android_bridge_status', arguments: {} });
    assert.equal(status.structuredContent.phoneOnly, true);
    const fixed = { hostId: 'phone-1', backend: 'harness', workspaceId: 'phone-capabilities' };
    const tree = await client.callTool({ name: 'android_get_ui_tree', arguments: { ...fixed, request_id: 'request-tree-000001', args: {} } });
    assert.equal(tree.structuredContent.taskSessionId, 'task-explicit');
    const missing = await client.callTool({ name: 'android_tap', arguments: { ...fixed, request_id: 'request-tap-0000001', args: { snapshotId: 's1', nodeId: 'n1' } } });
    assert.equal(missing.isError, true);
    await client.callTool({ name: 'android_cancel_request', arguments: { ...fixed, taskSessionId: 'task-explicit', request_id: 'cancel-request-00001', targetRequestId: 'request-tree-000001' } });
    const cancellation = calls.filter(call => call.operation === 'remote_send_message').at(-1);
    assert.equal(cancellation.args.sessionId, 'task-explicit'); assert.equal(JSON.parse(cancellation.args.message).op, 'request.cancel');
    assert.ok(!calls.some(call => call.operation === 'remote_interrupt_session'));
    await client.callTool({ name: 'android_read_delivery', arguments: { ...fixed, request_id: 'delivery-read-000001',
      deliveryId: `delivery_${'1'.repeat(32)}`, eventId: `push_${'2'.repeat(64)}`, token: 't'.repeat(43), payloadDigest: `sha256:${'3'.repeat(64)}` } });
    const deliveryCall = calls.filter(call => call.operation === 'remote_send_message').at(-1);
    assert.equal(JSON.parse(deliveryCall.args.message).op, 'push.read_delivery');
    assert.equal(deliveryCall.args.sessionId, 'task-explicit');
    assert.ok(calls.every(call => (call.args.targets ?? [call.args]).every(target => target.backend === 'harness' && target.workspaceId === 'phone-capabilities')));
  } finally { await client.close(); await registration.close(); await new Promise(resolve => http.close(resolve)); }
});

test('桥路由复核 bearer、已有 Host/Origin 校验和账号代际', async () => {
  let epoch = 'one';
  const bridge = new PhoneMcpBridge({}, { snapshot: () => epoch });
  assert.equal((await bridge.call('status', {})).available, true);
  epoch = 'two'; await assert.rejects(bridge.call('status', {}), /BRIDGE_AUTHORITY_CHANGED/u);
  let handler;
  const registration = registerPhoneMcpRoute({ bridge, bearer: 'token', connection: { requestRejection: req => req.headers.host === 'bad' ? 403 : undefined },
    webServer: { register(route) { handler = route.handler; return () => {}; } } });
  for (const headers of [{ authorization: 'Bearer wrong' }, { authorization: 'Bearer token', origin: 'https://evil.invalid' }, { authorization: 'Bearer token', host: 'bad' }]) {
    let status;
    await handler({ headers, socket: { remoteAddress: '127.0.0.1' } }, { writeHead(value) { status = value; }, end() {} });
    assert.equal(status, 403);
  }
  await registration.close();
});

test('收件请求同进程超时和受理重试复用原任务且重启显式恢复只查询', async () => {
  const calls = []; let creates = 0; let timeout = true;
  const runtime = {
    async discoverToolWorkspaces() { return { workspaces: [{ backend: 'harness', workspaceId: 'phone-capabilities' }] }; },
    async runSessionTool(operation, args) {
      calls.push({ operation, args });
      if (operation === 'remote_create_session') return { status: 'accepted', sessionId: `task-${++creates}` };
      if (operation === 'remote_send_message') return { status: 'accepted' };
      if (operation === 'remote_wait_sessions' && timeout) { timeout = false; throw new Error('WAIT_TIMEOUT'); }
      if (operation === 'remote_read_session') return { status: 'running', records: [] };
      return {};
    },
  };
  const authority = { snapshot: () => 'epoch' };
  const bridge = new PhoneMcpBridge(runtime, authority);
  const args = { hostId: 'phone', backend: 'harness', workspaceId: 'phone-capabilities', request_id: 'delivery-retry-00001',
    deliveryId: `delivery_${'1'.repeat(32)}`, eventId: `push_${'2'.repeat(64)}`, token: 't'.repeat(43), payloadDigest: `sha256:${'3'.repeat(64)}`, acknowledge: false };
  await assert.rejects(bridge.call('delivery', args), error => error.message === 'WAIT_TIMEOUT' && error.taskSessionId === 'task-1');
  const retried = await bridge.call('delivery', args);
  assert.equal(retried.taskSessionId, 'task-1');
  assert.equal((await bridge.call('delivery', args)).taskSessionId, 'task-1');
  assert.equal(creates, 1);
  assert.equal(calls.filter(call => call.operation === 'remote_send_message').length, 1);
  await assert.rejects(bridge.call('delivery', { ...args, token: 'x'.repeat(43) }), /REQUEST_ID_CONFLICT/u);
  const restarted = new PhoneMcpBridge(runtime, authority);
  const recovered = await restarted.call('delivery', { ...args, taskSessionId: 'task-1' });
  assert.equal(recovered.taskSessionId, 'task-1');
  assert.equal(creates, 1);
  assert.equal(calls.filter(call => call.operation === 'remote_send_message').length, 1);
});
