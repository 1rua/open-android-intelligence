import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createTsVmLoader, loadVerifiedFixtures } from './shared-vm-loader.mjs';

const fixtureRoot = new URL('./fixtures/dsh-remote/', import.meta.url);
const { sources } = await loadVerifiedFixtures(fixtureRoot, { pathKey: 'fixture' });

// 保留原始投影、工作区映射、事件载体与队列；仅替换后端连接、会话内容和标题展示。
async function projectionFixture() {
  class TestCache { getFresh() {} getStale() {} set() {} }
  class TestLive { async ensureFollow() {} }
  class TestTitles {
    nativeTitle(title) { return title; }
    applyNative(session, title) { session.title = title; }
    applyPrompt() {}
    close() {}
  }
  const unavailable = () => { throw new Error('未声明的外部探针调用'); };
  const doubles = {
    '@dsh-remote/client-core': { AgentAcpClient: unavailable },
    '@dsh-remote/protocol': { ACP_TOOL_CALL_ENDPOINTS: [], ACP_TOOL_STREAM_ENDPOINTS: [] },
    '../session-history-cache.js': { AcpSessionHistoryCache: TestCache },
    './image-content.js': { acpImageContent: unavailable, parseAcpImage: unavailable, acpImageLimits: () => ({}) },
    './virtual-live.js': { AcpLiveProjection: TestLive },
    './virtual-titles.js': { AcpSessionTitles: TestTitles },
  };
  const loader = createTsVmLoader({ sources, doubles });
  const rootNamespace = await loader.load('acp/virtual-harness.ts');
  const calls = [];
  const client = {
    async createSession(cwd) { return { sessionId: 'seed-session', cwd }; },
    async listSessions() { return []; },
    async loadSessionHistory() { return []; },
    async prompt(sessionId, text, signal, images, backend) { calls.push({ sessionId, backend }); },
  };
  const projection = new rootNamespace.AcpVirtualHarness(client, { deviceId: 'host-a', name: 'isolated' }, 'hermes');
  return { projection, calls, workspaceId: path => rootNamespace.acpCwdWorkspaceId(path, 'hermes') };
}

test('原始ACP投影不使用prompt中旧workspaceId阻止同名Session发往当前工作区', async () => {
  const env = await projectionFixture();
  const oldWorkspace = env.workspaceId('/workspace-a');
  const currentWorkspace = env.workspaceId('/workspace-b');
  await env.projection.selectWorkspace(currentWorkspace);
  const result = await env.projection.invoke({
    namespace: 'session', method: 'prompt', args: { request: {
      workspaceId: oldWorkspace,
      address: { kind: 'session', sessionId: 'hermes:collision' },
      content: [{ type: 'text', text: 'isolated probe' }],
    } },
  });
  assert.equal(result.accepted, true);
  assert.deepEqual(env.calls, [{ sessionId: 'collision', backend: 'hermes' }]);
  const list = await env.projection.invoke({ namespace: 'session', method: 'list', args: {} });
  assert.equal(list.items.find(row => row.sessionId === 'hermes:collision').cwd, '/workspace-b');
});

test('原始workspace流close后仍可先读到缓冲旧baseline，没有公开同步closed状态', async () => {
  const env = await projectionFixture();
  const workspace = env.workspaceId('/workspace-a');
  await env.projection.selectWorkspace(workspace);
  const stream = await env.projection.open('workspace/follow', { args: {} }, new AbortController().signal);
  await env.projection.close();
  assert.equal(stream.closed, undefined);
  const reader = stream[Symbol.asyncIterator]();
  const buffered = await reader.next();
  assert.equal(buffered.done, false);
  assert.equal(buffered.value.type, 'baseline');
  assert.equal(buffered.value.value.items[0].workspaceId, workspace);
  assert.equal((await reader.next()).done, true);
});

test('原始队列关闭只结束后续读取，关闭前排队事件并未作为同步失效信号清空', async () => {
  const env = await projectionFixture();
  await env.projection.selectWorkspace(env.workspaceId('/workspace-a'));
  const stream = await env.projection.open('workspace/follow', { args: {} }, new AbortController().signal);
  const reader = stream[Symbol.asyncIterator]();
  await reader.next();
  await env.projection.selectWorkspace(env.workspaceId('/workspace-b'));
  await env.projection.close();
  let buffered = 0;
  while (!(await reader.next()).done) buffered += 1;
  assert.ok(buffered >= 2);
});
