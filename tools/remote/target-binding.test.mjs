import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { test } from 'node:test';
import { SourceTextModule, SyntheticModule } from 'node:vm';

const fixtureRoot = new URL('./fixtures/dsh-remote/', import.meta.url);
const sourceIndex = JSON.parse(await readFile(new URL('source.json', fixtureRoot), 'utf8'));
const sources = new Map();
for (const entry of sourceIndex.files) {
  const bytes = await readFile(new URL(entry.fixture, fixtureRoot));
  assert.equal(createHash('sha256').update(bytes).digest('hex'), entry.sha256, entry.fixture);
  sources.set(entry.fixture, bytes.toString('utf8'));
}

function deferred() {
  let resolve;
  const promise = new Promise(accept => { resolve = accept; });
  return { promise, resolve };
}

// 只替换网络与后端投影的测试依赖；目标选择、状态和同步分发均执行固定上游原文。
async function isolatedRuntime() {
  const calls = [];
  const pending = [];
  const openStreams = new Set();
  const features = { remoteGateway: true, sessionFormat: 3, codex: true, hermes: true };
  class TestStream {
    constructor(scope) { this.scope = scope; this.closed = false; this.waiting = deferred(); }
    [Symbol.asyncIterator]() { return this; }
    next() { return this.closed ? Promise.resolve({ done: true }) : this.waiting.promise; }
    close() { this.closed = true; this.waiting.resolve({ done: true }); }
  }
  function beginCall(scope, request) {
    const completion = deferred();
    const call = { ...scope, sessionId: request.args.request?.sessionId ?? 'same-session' };
    calls.push(call);
    pending.push(() => completion.resolve(call));
    return completion.promise;
  }
  function beginStream(scope) {
    const stream = new TestStream(scope);
    openStreams.add(stream);
    return stream;
  }
  class TestConnection {
    async connect(hostId) {
      const clientStreams = new Set();
      let closed = false;
      const client = {
        hostId,
        getStats: () => ({ connected: !closed, mode: 'Relay' }),
        onClose: () => undefined,
        async close() { closed = true; for (const stream of clientStreams) stream.close(); },
        invoke(request) { return beginCall({ hostId, backend: 'harness' }, request); },
        async open() {
          const stream = beginStream({ hostId, backend: 'harness' });
          clientStreams.add(stream);
          return stream;
        },
      };
      return { client, target: { deviceId: hostId, name: hostId }, features, progressRunId: 1, harnessVersion: '0.2.2' };
    }
    clearConnectionProgress() {}
  }
  class TestProjection {
    constructor(client, host, backend) {
      this.client = client; this.host = host; this.backend = backend; this.streams = new Set();
    }
    static remote(client, host, backend) { return new TestProjection(client, host, typeof backend === 'string' ? backend : 'codex'); }
    async selectWorkspace(workspaceId) { this.workspaceId = workspaceId; return { workspaceId, path: `./${workspaceId}` }; }
    async preferredSessionId() { return 'same-session'; }
    invoke(request) {
      return beginCall({ hostId: this.host.deviceId, backend: this.backend, workspaceId: this.workspaceId }, request);
    }
    async open() {
      const stream = beginStream({ hostId: this.host.deviceId, backend: this.backend, workspaceId: this.workspaceId });
      this.streams.add(stream); return stream;
    }
    async close() { for (const stream of this.streams) stream.close(); }
  }
  class TestRemoteGateway {
    constructor(client) { this.client = client; }
    invoke(request) { return this.client.invoke(request); }
    open() { return this.client.open(); }
  }
  class TestCache { clear() {} }
  class TestError extends Error {
    constructor(code, message) { super(message); this.code = code; }
  }
  class TestReconnect { cancel() {} }
  class TestSessionTools { invalidate() {} }
  class TestCodexStreams { async closeCodexStreams() {} }
  const unused = () => { throw new Error('探针不允许调用未声明的外部接口'); };
  const modules = {
    './local-mention-upload.js': { uploadLocalMentions: async payload => payload },
    '@dsh-remote/client-core': { AgentAcpClient: unused, CodexRemoteClient: unused },
    './acp/virtual-harness.js': { acpCwdWorkspaceId: unused, AcpVirtualHarness: TestProjection, discoverAcpVirtualWorkspaces: unused },
    './client-capabilities.js': { probeRemoteHostFeatures: async () => features, remoteHostFeatures: () => features },
    './client-codex-streams.js': { ClientCodexStreams: TestCodexStreams },
    './client-connection.js': { ClientConnection: TestConnection },
    './client-reconnect.js': { ClientReconnect: TestReconnect },
    './client-runtime-contract.js': {
      ClientModeError: TestError, HOST_AUTHORIZATION_ERRORS: new Set(),
      record: value => value, shortId: value => value, isRecord: value => value !== null && typeof value === 'object',
      ok: value => ({ ok: true, value }), fail: error => ({ ok: false, error }),
    },
    './client-workspace-queries.js': { readRemoteWorkspaceBaseline: unused, remoteWorkspaceTitle: unused, workspaceRecordId: unused },
    './codex/virtual-harness.js': { codexProjectWorkspaceId: unused, CodexVirtualHarness: TestProjection, discoverCodexVirtualWorkspaces: unused },
    './control-route.js': { registerControlRoute: unused },
    './harness-version.js': { harnessSessionGeneration: () => 'v3' },
    './ids.js': { uuidV7: unused },
    './loopback-preview.js': { LoopbackPreview: unused },
    './plugin-route-bridge.js': { isAllowedPluginRoute: unused },
    './remote-typert-gateway.js': { RemoteTypertGateway: TestRemoteGateway },
    './session-history-cache.js': { AcpSessionHistoryCache: TestCache, CodexSessionHistoryCache: TestCache, RemoteSessionHistoryCache: TestCache },
    './safe-error.js': { safeErrorCode: () => 'TEST_ERROR' },
    './remote-session-tools.js': { RemoteSessionTools: TestSessionTools },
    './server-api.js': { ClientServerApi: unused, ServerApiError: TestError },
    './werift-rtc.js': { loadNodeRtcFactory: unused },
    './terminal-carrier-compat.js': { openRemoteTerminal: unused },
  };
  const compiled = new Map();
  for (const name of ['typert-gateway-switch', 'client-control', 'client-runtime']) {
    compiled.set(`./${name}.js`, new SourceTextModule(stripTypeScriptTypes(sources.get(`${name}.ts`), {
      mode: 'transform', sourceUrl: `fixed-upstream/${name}.ts`,
    }), { identifier: `fixed-upstream/${name}.ts` }));
  }
  const linked = new Map();
  await compiled.get('./client-runtime.js').link(async specifier => {
    if (compiled.has(specifier)) return compiled.get(specifier);
    assert.ok(modules[specifier], `未声明依赖：${specifier}`);
    if (!linked.has(specifier)) {
      const values = modules[specifier];
      linked.set(specifier, new SyntheticModule(Object.keys(values), function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value);
      }, { identifier: specifier }));
    }
    return linked.get(specifier);
  });
  const module = compiled.get('./client-runtime.js');
  await module.evaluate();
  const gateway = {
    invoke: request => beginCall({ hostId: "local-host", backend: "harness" }, request), stream: unused,
    dispatchRpc: unused, openWireStream: unused,
  };
  const logger = { info() {}, warn() {} };
  const client = new module.namespace.ClientModeRuntime(
    { serverUrl: 'https://remote.invalid', deviceName: 'isolated-probe', reconnect: {}, hostControl: { enabled: false } },
    { loadOrCreate: async () => ({ deviceId: 'isolated-client' }) },
    { bindIdentity() {} }, gateway, logger,
  );
  await client.start();
  // 测试通过公开服务面操作，不读取或修改 ClientModeRuntime 私有字段。
  return {
    ctx: { dshRemoteClient: client, typertGateway: gateway }, calls,
    async completeCalls() { await Promise.resolve(); pending.splice(0).forEach(complete => complete()); },
    async consume(hostId, workspaceId, backend = 'hermes') {
      const result = await client.handleControl('workspace.selection.consume', {
        targetDeviceId: hostId, workspaceId, backend,
      }, new AbortController().signal);
      assert.equal(result.ok, true);
    },
  };
}

const prompt = () => ({ namespace: 'session', method: 'prompt', args: { request: { sessionId: 'same-session' } } });
const follow = () => ({ namespace: 'session', method: 'follow', args: { request: { sessionId: 'same-session' } } });
function assertHost(ctx, hostId, backend = 'harness') {
  const status = ctx.dshRemoteClient.status();
  assert.equal(status.mode, 'remote');
  assert.equal(status.target?.deviceId, hostId);
  assert.equal(status.connectedTargetDeviceId, hostId);
  assert.equal(status.backend, backend);
}

// 这是隔离探针断言，尚非生产 Bridge；真实账号、协议和后端未参与。
test('固定上游文件逐个保持原始摘要与MIT许可', () => {
  assert.equal(sourceIndex.commit, 'ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7');
  assert.equal(sourceIndex.files.length, 5);
  assert.match(sources.get('LICENSE'), /MIT License/);
});

test('公开invoke同步捕获原Host，等待期间改选不会将该次写入转投同名Session', async () => {
  const env = await isolatedRuntime(); const { ctx } = env;
  await ctx.dshRemoteClient.setMode('remote', 'host-a');
  const pending = ctx.typertGateway.invoke(prompt());
  await ctx.dshRemoteClient.setMode('remote', 'host-b');
  await env.completeCalls();
  assert.equal((await pending).hostId, 'host-a');
  assert.deepEqual(env.calls.map(call => call.hostId), ['host-a']);
});

test('公开stream同步绑定原Host，关闭测试连接后旧流不会变成新Host事件流', async () => {
  const env = await isolatedRuntime(); const { ctx } = env;
  await ctx.dshRemoteClient.setMode('remote', 'host-a');
  const opening = ctx.typertGateway.stream(follow());
  await ctx.dshRemoteClient.setMode('remote', 'host-b');
  const stream = await opening;
  assert.equal(stream.scope.hostId, 'host-a');
  assert.equal((await stream.next()).done, true);
  const nextStream = await ctx.typertGateway.stream(follow());
  assert.equal(nextStream.scope.hostId, 'host-b');
});

test('反例：仅事前status再异步等待，仍会把同名Session的写入发给新Host', async () => {
  const env = await isolatedRuntime(); const { ctx } = env;
  await ctx.dshRemoteClient.setMode('remote', 'host-a');
  assertHost(ctx, 'host-a');
  await ctx.dshRemoteClient.setMode('remote', 'host-b');
  const pending = ctx.typertGateway.invoke(prompt());
  await env.completeCalls();
  assert.equal((await pending).hostId, 'host-b');
  assert.equal(env.calls[0].sessionId, 'same-session');
});

test('公开状态终检与invoke之间无异步间隙时，后续改选无法重定向已派发写入', async () => {
  const env = await isolatedRuntime(); const { ctx } = env;
  await ctx.dshRemoteClient.setMode('remote', 'host-a');
  function dispatchNow() {
    assertHost(ctx, 'host-a');
    return ctx.typertGateway.invoke(prompt());
  }
  const pending = dispatchNow();
  await ctx.dshRemoteClient.setMode('remote', 'host-b');
  await env.completeCalls();
  assert.equal((await pending).hostId, 'host-a');
  // 后验检查只能拒收旧观察，不能被用于声称尚未发生副作用。
  assert.throws(() => assertHost(ctx, 'host-a'));
});

test('同Host同后端换工作区且消费选择后，公开status不能单独证明工作区代际', async () => {
  const env = await isolatedRuntime(); const { ctx } = env;
  await ctx.dshRemoteClient.openCursorWorkspace('host-a', 'workspace-a', undefined, 'hermes');
  await env.consume('host-a', 'workspace-a');
  const before = ctx.dshRemoteClient.status();
  await ctx.dshRemoteClient.openCursorWorkspace('host-a', 'workspace-b', undefined, 'hermes');
  await env.consume('host-a', 'workspace-b');
  const after = ctx.dshRemoteClient.status();
  assert.deepEqual(after, before);
  const pending = ctx.typertGateway.invoke(prompt());
  await env.completeCalls();
  assert.equal((await pending).workspaceId, 'workspace-b');
});

test('公开工作区切换会关闭旧投影，但实际后端关闭行为仍属于测试替身边界', async () => {
  const env = await isolatedRuntime(); const { ctx } = env;
  await ctx.dshRemoteClient.openCursorWorkspace('host-a', 'workspace-a', undefined, 'hermes');
  const oldStream = await ctx.typertGateway.stream(follow());
  await ctx.dshRemoteClient.openCursorWorkspace('host-a', 'workspace-b', undefined, 'hermes');
  assert.equal((await oldStream.next()).done, true);
  const newStream = await ctx.typertGateway.stream(follow());
  assert.equal(newStream.scope.workspaceId, 'workspace-b');
});


test('反例：远端切为本机模式后不做终检，公开invoke会回落本机Agent', async () => {
  const env = await isolatedRuntime(); const { ctx } = env;
  await ctx.dshRemoteClient.setMode('remote', 'host-a');
  assertHost(ctx, 'host-a');
  await ctx.dshRemoteClient.setMode('local');
  const pending = ctx.typertGateway.invoke(prompt());
  await env.completeCalls();
  assert.equal((await pending).hostId, 'local-host');
  assert.throws(() => assertHost(ctx, 'host-a'));
});
