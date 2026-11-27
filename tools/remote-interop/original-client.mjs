// 使用固定上游原始 Client、Noise、选路和能力探测；不替换线上协议实现。
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { stripTypeScriptTypes } from 'node:module';
import { SourceTextModule, SyntheticModule } from 'node:vm';
import { posix } from 'node:path';
import { pathToFileURL } from 'node:url';
import * as ws from 'ws';
import { z } from 'zod';
import { Context } from '@deepseek-ai/cordis';

const expectedClientTimeZone = 'Asia/Shanghai';
process.env.TZ = expectedClientTimeZone;
globalThis.WebSocket = ws.WebSocket;
const modules = new Map();
const fixtureRoot = new URL('./fixtures/', import.meta.url);
const sources = [
  [fixtureRoot, 'path', '', 'ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7', false],
  [new URL('../remote-server/fixtures/', import.meta.url), 'path', '', 'ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7', false],
  [new URL('../../integrations/oai-android-mcp/test/fixtures/dsh-remote/', import.meta.url), 'fixture', 'packages/plugin/src/', 'ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7', false],
  [new URL('./release-fixtures/', import.meta.url), 'path', '', 'eef232d2c43b0c9d04998a18db98cbc9e673e488', true],
];
for (const [directory, field, prefix, commit, override] of sources) {
  const manifest = JSON.parse(await readFile(new URL('source.json', directory), 'utf8'));
  assert.equal(manifest.repository, 'liguobao/ds-harness-remote');
  assert.equal(manifest.commit, commit);
  for (const entry of manifest.files) {
    const file = entry[field];
    const bytes = await readFile(new URL(file, directory));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), entry.sha256, `固定上游摘要不一致：${file}`);
    if (!file.endsWith('.ts')) continue;
    const id = prefix + file;
    if (override || !modules.has(id)) modules.set(id, new SourceTextModule(stripTypeScriptTypes(bytes.toString('utf8'), { mode: 'transform' }), { identifier: id }));
  }
}
const packages = new Map(['protocol', 'crypto', 'webrtc', 'client-core'].map(name => [`@dsh-remote/${name}`, `packages/${name}/src/index.ts`]));
const externals = new Map();
async function link(specifier, referencing) {
  const id = packages.get(specifier) ?? (specifier.startsWith('.')
    ? posix.normalize(posix.join(posix.dirname(referencing.identifier), specifier.replace(/\.js$/, '.ts'))) : undefined);
  if (id) {
    assert.ok(modules.has(id), `互通探针缺少固定源码：${id}`);
    return modules.get(id);
  }
  if (!externals.has(specifier)) {
    // 原文件清理临时下载时也移入回收站，遵守项目禁止永久删除的约定。
    let values = specifier === 'zod' ? { z } : await import(specifier);
    if (specifier === 'node:fs/promises') values = { ...values, rm: async path => {
      const trash = new URL('../../.worktrees/trash/', import.meta.url);
      await values.mkdir(trash, { recursive: true });
      await values.rename(path, new URL(`interop-${crypto.randomUUID()}`, trash));
    } };
    externals.set(specifier, new SyntheticModule(Object.keys(values), function () {
      for (const [name, value] of Object.entries(values)) this.setExport(name, value);
    }, { identifier: specifier }));
  }
  return externals.get(specifier);
}
for (const id of ['packages/plugin/src/client-secure-transport.ts', 'packages/plugin/src/client-capabilities.ts',
  'packages/plugin/src/remote-session-tools.ts', 'packages/plugin/src/client-workspace-queries.ts',
  'packages/plugin/src/remote-tools.ts', 'packages/client-core/src/index.ts', 'packages/webrtc/src/index.ts', 'packages/crypto/src/index.ts']) {
  const module = modules.get(id);
  if (module.status === 'unlinked') await module.link(link);
  if (module.status === 'linked') await module.evaluate();
}
const { ClientSecureTransport } = modules.get('packages/plugin/src/client-secure-transport.ts').namespace;
const { probeRemoteHostFeatures } = modules.get('packages/plugin/src/client-capabilities.ts').namespace;
const { HarnessAlphaClient, RemoteClientCore } = modules.get('packages/client-core/src/index.ts').namespace;
const { AdaptiveTransport } = modules.get('packages/webrtc/src/index.ts').namespace;
const { generateKeyPair } = modules.get('packages/crypto/src/index.ts').namespace;
const { RemoteSessionTools } = modules.get('packages/plugin/src/remote-session-tools.ts').namespace;
const { readRemoteWorkspaceBaseline } = modules.get('packages/plugin/src/client-workspace-queries.ts').namespace;
const { remoteDiscoveryTools } = modules.get('packages/plugin/src/remote-tools.ts').namespace;

const installedRuntimeEntry = process.env.OAI_DSH_REMOTE_ENTRY;
const requireInstalledDiscovery = process.env.OAI_REQUIRE_INSTALLED_DISCOVERY === '1';
const installedRuntime = installedRuntimeEntry === undefined ? undefined : await (async () => {
  const entryUrl = pathToFileURL(installedRuntimeEntry);
  const packageJson = JSON.parse(await readFile(new URL('../package.json', entryUrl), 'utf8'));
  assert.equal(packageJson.name, 'ds-harness-remote');
  assert.equal(packageJson.version, '0.6.5', '仅允许复用已安装的 0.6.5 官方运行时');
  const runtime = await import(entryUrl.href);
  assert.equal(typeof runtime.ClientModeRuntime, 'function', '已安装包缺少原生 ClientModeRuntime');
  assert.equal(typeof runtime.ClientServerApi, 'function', '已安装包缺少原生 ClientServerApi');
  return runtime;
})();
// 固定版本验证器：直接运行已安装 v0.6.5 的真实 ClientModeRuntime 方法。
// 以下 Client/Host 连接事实全部是隔离替身，只证明入口、作用域和 RPC 选路，不冒充 Noise、真实 Host 或 Android 门面。
if (requireInstalledDiscovery) assert.ok(installedRuntime, '本次验收要求运行已安装的 0.6.5 官方发现函数');
async function createVerifierClientRuntime(runtime, target, client, features) {
  const config = runtime.resolveConfig({ role: 'client', serverUrl: 'http://127.0.0.1:9', hostControl: { enabled: false } });
  const store = {
    async load() { return undefined; }, async save(value) { return value; }, async clear() {},
    async pairingHostOnly() { return false; }, async restrictPairingHostOnly() {}, async withRefreshLock(operation) { return operation(); },
  };
  const server = new runtime.ClientServerApi(config.serverUrl, store, fetch, ['127.0.0.1:9']);
  const localGateway = {
    async invoke() { throw new Error('运行时验证不应调用本地 Gateway'); },
    async dispatchRpc() { throw new Error('运行时验证不应调用本地 Gateway'); },
    async openWireStream() { throw new Error('运行时验证不应打开本地流'); },
    failure() { return { code: 'internal', message: '', details: {} }; },
  };
  const logger = { debug() {}, info() {}, warn() {}, error() {} };
  const host = { localHarnessVersion: () => '0.2.2-rc.1', hostStatus: () => ({ pairingHostOnly: false, deviceId: 'isolated-host' }) };
  const instance = new runtime.ClientModeRuntime(config,
    { async loadOrCreate() { return { deviceId: 'isolated-client', name: 'isolated-client' }; } }, server, localGateway, logger, host);
  instance.connected = { target, client, transport: {}, features, progressRunId: 0 };
  await instance.gatewaySwitch.install();
  return instance;
}

async function verifyWorkspaceOpenAndSessionCreate(runtime, instance, targetDeviceId, trace, signal, opPrefix, label) {
  const openTraceStart = trace.length;
  const first = await runtime.ClientModeRuntime.prototype.handleControl.call(instance, 'workspace.open',
    { targetDeviceId, path: 'phone-capabilities' }, signal);
  const second = await runtime.ClientModeRuntime.prototype.handleControl.call(instance, 'workspace.open',
    { targetDeviceId, path: 'phone-capabilities' }, signal);
  assert.equal(first.ok, true, `${label} workspace.open 失败：${JSON.stringify(first)}`);
  assert.equal(second.ok, true, `${label}重复 workspace.open 失败：${JSON.stringify(second)}`);
  assert.deepEqual(first.value.workspace, second.value.workspace, `${label}重复打开同一工作区必须返回同一记录`);
  assert.equal(first.value.workspace.workspace.workspaceId, 'phone-capabilities');
  assert.equal(first.value.workspace.created, false, `${label}打开已有虚拟工作区必须返回 created:false`);
  const openCalls = trace.slice(openTraceStart);
  assert.equal(openCalls.filter(item => item.endpoint === 'workspace/create').length, 2, `${label} workspace.open 必须两次调用 workspace/create`);
  assert.equal(openCalls.filter(item => item.endpoint === 'session/create').length, 0, `${label}打开工作区不得创建任务`);

  const wrong = await runtime.ClientModeRuntime.prototype.handleControl.call(instance, 'workspace.open',
    { targetDeviceId, path: 'other-workspace' }, signal);
  assert.equal(wrong.ok, false, `${label}错误工作区路径必须拒绝`);
  assert.equal(wrong.error.details.remoteCode, 'CONTROL_WORKSPACE_NOT_FOUND');

  const workspace = { hostId: targetDeviceId, backend: 'harness', workspaceId: 'phone-capabilities' };
  const createTraceStart = trace.length;
  const firstCreate = await instance.runSessionTool('remote_create_session', { ...workspace, operationId: `${opPrefix}-create-000001` }, signal);
  const secondCreate = await instance.runSessionTool('remote_create_session', { ...workspace, operationId: `${opPrefix}-create-000002` }, signal);
  assert.equal(firstCreate.status, 'accepted');
  assert.equal(secondCreate.status, 'accepted');
  assert.notEqual(firstCreate.sessionId, secondCreate.sessionId, `${label}独立 session/create 必须返回不同任务 ID`);
  const sessionCreateCalls = trace.slice(createTraceStart).filter(item => item.endpoint === 'session/create').length;
  assert.equal(sessionCreateCalls, 2, `${label}两次独立创建必须各自调用 session/create`);

  const scopeTraceStart = trace.length;
  await assert.rejects(instance.runSessionTool('remote_create_session',
    { ...workspace, workspaceId: 'other-workspace', operationId: `${opPrefix}-scope-0000001` }, signal), /REMOTE_PREFLIGHT_FAILED/u);
  assert.ok(trace.slice(scopeTraceStart).every(item => item.endpoint !== 'session/create'), `${label}错误工作区不得进入 session/create`);
  return { sessionCreateCalls };
}

async function installedRuntimeVerifier(runtime) {
  const source = runtime.ClientModeRuntime.prototype.handleControl.toString();
  assert.match(source, /this\.control\.handleControl\(endpoint, payload, signal\)/u, '已安装 ClientModeRuntime.handleControl 必须委托真实 ClientControl');
  assert.equal(typeof runtime.resolveConfig, 'function', '已安装包缺少 resolveConfig');
  assert.equal(typeof runtime.ClientServerApi, 'function', '已安装包缺少 ClientServerApi');

  const events = new Set();
  const trace = [];
  let createCount = 0;
  let visibleSessionIds = ['control-bus'];
  const busPromptRecords = [];
  const oversizedId = 'verify-oversized-0001';
  const oversizedPayload = {
    request_id: oversizedId,
    taskSessionId: 'active-artifact-task',
    state: 'completed',
    execution: { status: 'succeeded' },
    resultAvailable: true,
    result: { snapshotId: 'snap-065', payload: 'z'.repeat(20_000) },
  };
  const oversizedRaw = Buffer.from(JSON.stringify(oversizedPayload), 'utf8');
  const oversizedVersion = `sha256:${createHash('sha256').update(oversizedRaw).digest('hex')}`;
  const oversizedSummary = {
    request_id: oversizedId,
    taskSessionId: 'active-artifact-task',
    state: 'completed',
    execution: { status: 'succeeded' },
    resultAvailable: true,
    resultArtifact: {
      artifactId: `result-${oversizedId}`,
      path: `artifacts/result-${oversizedId}`,
      mimeType: 'application/json',
      bytes: oversizedRaw.length,
      version: oversizedVersion,
    },
  };
  const client = {
    getStats() { return { mode: 'Relay', connected: true }; },
    onEvent(handler) { events.add(handler); return () => events.delete(handler); },
    onClose() { return () => {}; },
    async close() {},
    async rpc(route, payload) {
      trace.push({ route, endpoint: payload?.endpoint });
      if (route === 'harness.transport.describe') return { capabilities: ['harness.remote.v3'] };
      if (route === 'harness.remote.stream.open') {
        const endpoint = payload?.endpoint;
        setImmediate(() => {
          let frameValue;
          if (endpoint === 'workspace/follow') {
            frameValue = {
              type: 'baseline', cursor: 0,
              value: { items: [{ workspaceId: 'phone-capabilities', path: 'phone-capabilities', title: '隔离手机', sessionIds: [...visibleSessionIds] }] },
            };
          } else if (endpoint === 'session/follow') {
            const sessionId = payload?.payload?.args?.request?.address?.sessionId;
            if (sessionId === 'control-bus') {
              frameValue = { type: 'snapshot', cursor: busPromptRecords.length, hasMore: false, records: [...busPromptRecords], projections: { values: {} } };
            } else if (sessionId === 'active-artifact-task') {
              frameValue = {
                type: 'snapshot', cursor: 1, hasMore: false,
                records: [{
                  type: 'event',
                  event: {
                    seq: 0, type: 'assistant/message',
                    data: { message: { content: [{ type: 'text', text: JSON.stringify(oversizedSummary) }] } },
                  },
                }],
                projections: { values: {} },
              };
            }
          }
          if (!frameValue) return;
          for (const handler of events) handler({
            event: 'harness.remote.frame',
            data: { streamId: payload.streamId, hasValue: true, value: frameValue },
          });
        });
        return {};
      }
      if (route === 'harness.remote.stream.close') return {};
      if (route === 'harness.remote.call') {
        if (payload.endpoint === 'workspace/create') {
          if (payload.payload?.args?.request?.path !== 'phone-capabilities') {
            return { ok: false, error: { code: 'CONTROL_WORKSPACE_NOT_FOUND', message: '隔离范围拒绝', details: {} } };
          }
          return { ok: true, value: { workspace: { workspaceId: 'phone-capabilities', path: 'phone-capabilities', title: '隔离手机', sessionIds: [...visibleSessionIds] }, created: false } };
        }
        if (payload.endpoint === 'session/create') {
          createCount++;
          return { ok: true, value: { sessionId: `isolated-task-${createCount}` } };
        }
        if (payload.endpoint === 'session/list') {
          return {
            ok: true,
            value: {
              items: visibleSessionIds.map((sessionId, index) => ({
                sessionId, title: sessionId, running: false, blank: false, updatedAt: 1000 + index,
              })),
            },
          };
        }
        if (payload.endpoint === 'session/prompt') {
          const req = payload.payload?.args?.request;
          if (req?.sessionId === 'control-bus') {
            const envelope = JSON.parse(req.content[0].text);
            if (envelope.op === 'request.status') {
              busPromptRecords.push({
                type: 'event',
                event: {
                  seq: busPromptRecords.length,
                  type: 'assistant/message',
                  data: {
                    message: {
                      content: [{
                        type: 'text',
                        text: JSON.stringify({
                          request_id: envelope.request_id,
                          taskSessionId: 'control-bus',
                          state: 'completed',
                          execution: { status: 'succeeded' },
                          result: {
                            request_id: envelope.args.targetRequestId,
                            taskSessionId: envelope.args.targetTaskSessionId,
                            state: 'completed',
                            execution: { status: 'succeeded' },
                            resultAvailable: false,
                          },
                        }),
                      }],
                    },
                  },
                },
              });
              return { ok: true, value: { accepted: true } };
            }
          }
        }
        if (payload.endpoint === 'workspaceFiles/stat') {
          const args = payload.payload?.args;
          if (args?.workspaceFileScopeId === 'active-artifact-task' && args?.path === oversizedSummary.resultArtifact.path) {
            return {
              ok: true,
              value: {
                absolutePath: `/phone-capabilities/${args.workspaceFileScopeId}/${args.path}`,
                version: oversizedVersion,
                bytes: oversizedRaw.length,
              },
            };
          }
        }
        if (payload.endpoint === 'workspaceFiles/readBytes') {
          const args = payload.payload?.args;
          if (args?.workspaceFileScopeId === 'active-artifact-task' && args?.path === oversizedSummary.resultArtifact.path) {
            const { offset, length } = args.range;
            const end = Math.min(oversizedRaw.length, offset + length);
            return {
              ok: true,
              value: {
                absolutePath: `/phone-capabilities/${args.workspaceFileScopeId}/${args.path}`,
                version: oversizedVersion,
                bytes: oversizedRaw.length,
                offset,
                data: oversizedRaw.subarray(offset, end).toString('base64'),
                eof: end === oversizedRaw.length,
              },
            };
          }
        }
      }
      throw new Error(`隔离客户端收到未登记请求：${route}/${payload?.endpoint ?? ''}`);
    },
  };
  const instance = await createVerifierClientRuntime(runtime, {
    deviceId: 'phone', name: '隔离手机', platform: 'linux', publicKey: 'isolated-key',
    fingerprint: 'isolated-fingerprint', trustedAt: 0, membershipId: 'isolated-membership',
  }, client, {
    commandList: true, fileViewer: false, terminal: false, remoteGateway: true, sessionFormat: 3,
    codex: false, cursor: false, antigravity: false, opencode: false, pluginRoute: false,
  });
  const signal = AbortSignal.timeout(2_000);
  const { sessionCreateCalls } = await verifyWorkspaceOpenAndSessionCreate(runtime, instance, 'phone', trace, signal, 'verify', '固定');

  // 仅安装版探针使用桥，默认固定源码互通只需要自己的 Node 依赖。
  const { PhoneMcpBridge } = await import('../../integrations/oai-android-mcp/src/bridge.mjs');
  // 使用真实已安装 0.6.5 ClientModeRuntime 验证 PhoneMcpBridge：
  // 1) 隔离目录仅含 control-bus 时，旧任务 request_result 经真实 SESSION_NOT_IN_WORKSPACE 回查 control-bus request.status；
  // 2) 长回执通过真实 withToolRemote + remoteTypertGateway 内存读取 workspaceFiles/stat/readBytes，零 Downloads 落盘。
  const verifyBridge = new PhoneMcpBridge(instance, { snapshot: async () => 'verify-epoch' });
  const retiredStart = trace.length;
  const retiredReceipt = await verifyBridge.call('request_result', {
    hostId: 'phone', backend: 'harness', workspaceId: 'phone-capabilities',
    taskSessionId: 'retired-task-065', targetRequestId: 'verify-retired-00001',
  }, signal);
  assert.equal(retiredReceipt.request_id, 'verify-retired-00001');
  assert.equal(retiredReceipt.taskSessionId, 'retired-task-065');
  assert.equal(retiredReceipt.state, 'completed');
  assert.equal(retiredReceipt.resultAvailable, false);
  const retiredTrace = trace.slice(retiredStart);
  assert.ok(retiredTrace.some(item => item.endpoint === 'session/list'), '0.6.5 应先经 session/list 命中 SESSION_NOT_IN_WORKSPACE');
  assert.ok(retiredTrace.some(item => item.endpoint === 'session/prompt'), '0.6.5 命中 SESSION_NOT_IN_WORKSPACE 后必须回查 control-bus request.status');

  visibleSessionIds = ['control-bus', 'active-artifact-task'];
  const artifactStart = trace.length;
  const loadedLong = await verifyBridge.call('request_result', {
    hostId: 'phone', backend: 'harness', workspaceId: 'phone-capabilities',
    taskSessionId: 'active-artifact-task', targetRequestId: oversizedId,
  }, signal);
  assert.equal(loadedLong.result.snapshotId, 'snap-065');
  assert.equal(loadedLong.result.payload.length, 20_000);
  const artifactTrace = trace.slice(artifactStart);
  assert.ok(artifactTrace.some(item => item.endpoint === 'workspaceFiles/stat'), '0.6.5 内存长回执必须走 workspaceFiles/stat');
  assert.ok(artifactTrace.some(item => item.endpoint === 'workspaceFiles/readBytes'), '0.6.5 内存长回执必须走 workspaceFiles/readBytes');

  await runtime.ClientModeRuntime.prototype.handleControl.call(instance, 'mode.set', { mode: 'local' }, signal);
  return { workspacePath: 'workspace/open->workspace/create', workspaceOpenCalls: 2, sessionCreateCalls,
    workspaceScopeRejected: true, distinctSessions: true, retiredTaskFallbackVerified: true, inMemoryResultArtifactVerified: true, isolatedConnectionFacts: true };
}

const installedRuntimeVerification = installedRuntime === undefined ? undefined : await installedRuntimeVerifier(installedRuntime);

// 完整联测中的同一入口验证：固定 v0.6.5 运行时直接连接真实隔离 Server/Android Host，
// 只追踪调用，不改写原 Client、Noise、门面或响应。
async function installedRuntimeFullChainVerifier(runtime, baseClient, baseTarget, features, trace) {
  const runtimeClient = {
    getStats: () => baseClient.getStats(),
    onEvent: handler => baseClient.onEvent(handler),
    onClose: handler => baseClient.onClose(handler),
    close: async () => {},
    // baseClient.rpc 已在主链路记账到 rpcTrace，这里只透传，避免双记账。
    rpc: async (route, payload, signal, options) => baseClient.rpc(route, payload, signal, options),
  };
  const instance = await createVerifierClientRuntime(runtime, baseTarget, runtimeClient, features);
  await verifyWorkspaceOpenAndSessionCreate(runtime, instance, baseTarget.deviceId, trace, AbortSignal.timeout(10_000), 'real', '真实');
  return { workspaceOpenCalls: 2, workspaceCreateRpc: 2, openedBeforeSessionCreate: true, scopeRejected: true,
    distinctSessionCreate: true, realAndroidFacade: true };
}
if (process.env.OAI_RUNTIME_VERIFY_ONLY === '1') {
  assert.ok(installedRuntimeVerification, '验证模式必须提供已安装的 0.6.5 运行时');
  await new Promise(resolve => process.stdout.write(`${JSON.stringify({ status: 'PASS', runtime: '0.6.5', runtimeVerification: installedRuntimeVerification })}\n`, resolve));
  process.exit(0);
}
const harnessRoot = new URL('./harness-fixtures/', import.meta.url);
const harnessSource = JSON.parse(await readFile(new URL('source.json', harnessRoot), 'utf8'));
for (const entry of harnessSource.files) {
  assert.equal(createHash('sha256').update(await readFile(new URL(entry.path, harnessRoot))).digest('hex'), entry.sha256);
}
const { installConnection } = await import('./harness-fixtures/dsh-client-connection/lib/client.node.js');
const { ClientRemoteEvents } = await import('./harness-fixtures/dsh-api-gateway/lib/types/client/remote-events.js');
const { default: sessionContract } = await import('./harness-fixtures/dsh-api-session-controller/lib/typert.remote-client.js');
const { RemoteTypertGateway } = modules.get('packages/plugin/src/remote-typert-gateway.ts').namespace;
const [origin, hostId] = process.argv.slice(2);
assert.ok(origin?.startsWith('http://127.0.0.1:') && hostId, '互通探针仅连接隔离本地服务');
async function api(path, body, bearer, signal) {
  const response = await fetch(origin + path, { method: body ? 'POST' : 'GET', signal,
    headers: { ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  assert.equal(response.status, 200, `固定服务请求失败：${path}`);
  return response.json();
}
function hostDeviceView(value) {
  assert.ok(value !== null && typeof value === 'object' && !Array.isArray(value), '设备目录项类型错误');
  assert.equal(value.role, 'host', '设备目录混入非 Host');
  for (const field of ['deviceId', 'name', 'platform', 'membershipId']) {
    assert.equal(typeof value[field], 'string', `设备目录字段类型错误：${field}`);
    assert.ok(value[field].length > 0, `设备目录字段为空：${field}`);
  }
  return {
    deviceId: value.deviceId, name: value.name, platform: value.platform, membershipId: value.membershipId,
    ...(typeof value.online === 'boolean' ? { online: value.online } : {}),
    ...(Number.isSafeInteger(value.lastSeenAt) ? { lastSeenAt: value.lastSeenAt } : {}),
    ...(typeof value.clientVersion === 'string' ? { clientVersion: value.clientVersion } : {}),
    ...(typeof value.harnessVersion === 'string' ? { harnessVersion: value.harnessVersion } : {}),
  };
}
// 与固定上游 ClientServerApi.devicePages()+ClientModeRuntime.devices() 相同的分页、游标、deviceId
// 去重和逐项 presence 规则；安装包可用时由真实原生函数替代这一段。
async function listHostDevices(accessToken, signal) {
  const devices = [];
  const ids = new Set();
  const cursors = new Set();
  let cursor;
  for (;;) {
    signal?.throwIfAborted();
    const query = new URLSearchParams({ limit: '50' });
    if (cursor !== undefined) query.set('cursor', cursor);
    const page = await api(`/api/v1/devices?${query}`, undefined, accessToken, signal);
    assert.ok(Array.isArray(page.items), '设备目录缺少 items');
    const next = page.nextCursor;
    if (next !== undefined && next !== null) {
      assert.equal(typeof next, 'string', '设备目录游标类型错误');
      assert.ok(next.length > 0 && next.length <= 512, '设备目录游标长度错误');
      assert.ok(page.items.length > 0, '设备目录空页不能继续分页');
      assert.ok(!cursors.has(next), '设备目录游标重复');
    }
    for (const item of page.items) {
      const device = hostDeviceView(item);
      assert.ok(!ids.has(device.deviceId), `设备目录返回重复 deviceId：${device.deviceId}`);
      ids.add(device.deviceId);
      devices.push(device);
    }
    if (typeof next !== 'string') break;
    cursor = next;
    cursors.add(cursor);
  }
  const presence = await Promise.all(devices.map(async device => {
    signal?.throwIfAborted();
    const value = await api(`/api/v1/devices/${encodeURIComponent(device.deviceId)}/presence`, undefined, accessToken, signal);
    assert.equal(value.deviceId, device.deviceId, 'presence 返回了错误 deviceId');
    assert.equal(typeof value.online, 'boolean', 'presence online 类型错误');
    assert.ok(value.lastSeenAt === null || value.lastSeenAt === undefined || Number.isSafeInteger(value.lastSeenAt), 'presence lastSeenAt 类型错误');
    return { online: value.online, ...(Number.isSafeInteger(value.lastSeenAt) ? { lastSeenAt: value.lastSeenAt } : {}) };
  }));
  return devices.map((device, index) => ({ ...device, ...presence[index] }));
}
async function discoverHost(accessToken, wantedHostId, signal) {
  const devices = await listHostDevices(accessToken, signal);
  const found = devices.find(item => item.deviceId === wantedHostId);
  assert.ok(found, `设备目录未发现指定 Android Host：${wantedHostId}`);
  return found;
}
let stage = '账号登录与设备目录发现';
const login = await api('/api/v1/auth/login', { email: 'fixture@example.test', password: 'fixture-local-password' });
const identity = { deviceId: crypto.randomUUID(), name: '上游原客户端互通探针', ...generateKeyPair() };
const tokens = await api('/api/v1/devices/register', { v: 1, device: { deviceId: identity.deviceId, name: identity.name,
  platform: 'linux', role: 'client', identityKey: identity.publicKey, clientVersion: '0.6.5' } }, login.token);
const discovered = await discoverHost(tokens.accessToken, hostId, AbortSignal.timeout(10_000));
assert.equal(discovered.deviceId, hostId);
assert.equal(discovered.platform, 'android');
const presence = { online: discovered.online };
assert.equal(presence.online, true, '设备目录发现的 Android Host 未在线');
const descriptor = await api(`/api/v1/devices/${hostId}`, undefined, tokens.accessToken);
const target = { deviceId: hostId, name: descriptor.name, publicKey: descriptor.identityKey, membershipId: descriptor.membershipId };
const transport = new AdaptiveTransport(origin.replace('http:', 'ws:') + '/ws/v1/connect', {
  role: 'client', deviceId: identity.deviceId, accessToken: tokens.accessToken,
  targetDeviceId: hostId, forceRelay: true, preferredTransports: ['relay'],
});
const client = new RemoteClientCore(new ClientSecureTransport(transport, identity, target), 5_000);
const rpcTrace = [];
const directRpc = client.rpc.bind(client);
client.rpc = async (route, payload, signal, options) => {
  const endpoint = payload && typeof payload === 'object' && typeof payload.endpoint === 'string' ? payload.endpoint : route;
  rpcTrace.push({ route, endpoint });
  return directRpc(route, payload, signal, options);
};
const forbiddenFileWorkspaceRpc = new Set(['directory.list', 'directoryPicker/list', 'workspace.open']);
function assertNoFileWorkspaceRpc(trace, label) {
  const forbidden = trace.filter(item => forbiddenFileWorkspaceRpc.has(item.endpoint));
  assert.deepEqual(forbidden, [], `${label}不应依赖普通文件目录/workspace.open：${JSON.stringify(forbidden)}`);
}
stage = '控制连接与Noise握手';
let lifecycle;
let events;
let eventsClientId;
let eventGenerationEnded;
let connection;
let discoveryServerApi;
const owner = new Context();
const deliveries = [];
const waiting = new Set();
function waitEvent(predicate) {
  const seen = deliveries.find(predicate);
  if (seen) return Promise.resolve(seen);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { waiting.delete(observer); reject(Error('官方事件消费者未收到预期通知')); }, 4_000);
    const observer = value => { if (predicate(value)) { clearTimeout(timer); waiting.delete(observer); resolve(value); } };
    waiting.add(observer);
  });
}
function businessTerminal(records, requestId) {
  for (const item of records) {
    if (item.type !== 'assistant/message') continue;
    let content;
    try { content = JSON.parse(item.json)?.event?.data?.message?.content; } catch { continue; }
    if (!Array.isArray(content)) continue;
    const text = content.find(part => part?.type === 'text')?.text;
    if (typeof text !== 'string') continue;
    try {
      const value = JSON.parse(text);
      if (value?.request_id === requestId) return value;
    } catch { /* 非终态正文不是业务回执。 */ }
  }
  return undefined;
}
try {
  await client.connect();
  stage = '真实能力探测';
  const features = await probeRemoteHostFeatures(client, descriptor.clientVersion);
  assert.equal(features.remoteGateway, true);
  assert.equal(features.sessionFormat, 3);
  stage = '官方桌面事件代际就绪';
  const gateway = new RemoteTypertGateway(client);
  installConnection({ provide: (_name, value) => { connection = value; } }, {
    interactions: 'answer', transport: { rpc: {
      call: async (_channel, endpoint, payload, signal) => gateway.dispatch(endpoint, payload, signal),
    } }, recovery: { generationReadyTimeoutMs: 3_000, generationReadyWarnMs: 2_500,
      backoffBaseMs: 1, backoffMaxMs: 1, backoffFactor: 1 },
  });
  events = new ClientRemoteEvents(owner, connection, (endpoint, payload, signal) => ({
    async *[Symbol.asyncIterator]() {
      const stream = await gateway.open(endpoint, payload, signal);
      try { for await (const item of stream) {
        if (item.type === 'ready') eventsClientId = item.clientId;
        yield item;
      } } finally { await stream.return?.(); eventGenerationEnded?.(); }
    },
  }));
  for (const name of ['api-session/added', 'api-session/status']) events.subscribe(owner, name, (...args) => {
    const value = { name, args }; deliveries.push(value); for (const observer of waiting) observer(value);
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(Error('官方桌面事件代际未进入connected')), 4_000);
    lifecycle = connection.start({ onConnected: host => { clearTimeout(timeout); resolve(host); },
      onReconnectRequested: () => { clearTimeout(timeout); reject(Error('官方桌面事件代际失败并进入自动重试')); } });
  });
  assert.equal(connection.state.getSnapshot(), 'connected');
  assert.equal(connection.generation.getSnapshot().host.home, '/phone-capabilities');
  stage = '官方SessionControl投影契约';
  const control = await gateway.open('session/control', { args: {} }, AbortSignal.timeout(5_000));
  const controlIterator = control[Symbol.asyncIterator]();
  try {
    const baseline = await controlIterator.next();
    assert.equal(baseline.done, false);
    const schema = sessionContract.descriptors.find(item => item.namespace === 'session' && item.method === 'control').result.create();
    // 旧数组不能冒充按 sessionId 索引的投影映射，使用官方生成的真实结果解析器锁住该缺陷。
    assert.throws(() => schema.parse({ type: 'baseline', value: { projections: [] } }));
    const parsed = schema.parse(baseline.value);
    assert.ok(parsed.value.projections['control-bus']);
  } finally { await controlIterator.return?.(); }
  const remote = { client, features };
  stage = '已安装0.6.5运行时全链路验证';
  const fullChainRuntimeVerification = installedRuntime === undefined ? undefined : await installedRuntimeFullChainVerifier(
    installedRuntime, client, target, features, rpcTrace);
  let discoveryClient;
  if (installedRuntime !== undefined) {
    discoveryServerApi = new installedRuntime.ClientServerApi(origin, {
      save: async value => value,
      load: async () => undefined,
      withRefreshLock: async operation => operation(),
      pairingHostOnly: async () => false,
    }, fetch, [origin]);
    discoveryServerApi.bindIdentity(identity);
    discoveryServerApi.credentials = tokens;
    const deviceRuntime = {
      remoteLifetime: new AbortController(),
      closed: false,
      host: undefined,
      server: discoveryServerApi,
      connection: {
        async authorizeHostPeer(device, signal) {
          signal?.throwIfAborted();
          const authorized = await api(`/api/v1/devices/${encodeURIComponent(device.deviceId)}`, undefined, tokens.accessToken, signal);
          assert.equal(authorized.role, 'host', '已安装原生发现路径授权了非 Host');
          assert.equal(authorized.deviceId, device.deviceId, '已安装原生发现路径授权了错误设备');
          assert.equal(authorized.membershipId, device.membershipId, '已安装原生发现路径的 membership 不一致');
          return authorized;
        },
      },
      assertHostAuthorizationForDeviceDiscovery() { },
      requireIdentity() { return identity; },
    };
    discoveryClient = {
      devices: signal => installedRuntime.ClientModeRuntime.prototype.devices.call(deviceRuntime, signal),
      discoverToolWorkspaces: (targetDeviceId, signal) => {
        assert.equal(targetDeviceId, hostId, '已安装原生工作区发现收到错误 Host');
        return installedRuntime.ClientModeRuntime.prototype.discoverToolWorkspaces.call({
          withToolRemote: async (_target, activeSignal, query) => query({ client, target, features, progressRunId: 0 }, activeSignal),
        }, targetDeviceId, signal);
      },
    };
  } else {
    discoveryClient = {
      devices: signal => listHostDevices(tokens.accessToken, signal),
      discoverToolWorkspaces: async (targetDeviceId, signal) => {
        assert.equal(targetDeviceId, hostId, '固定源码工作区发现收到错误 Host');
        const workspaces = [], unavailable = [];
        if (features.remoteGateway) {
          try {
            for (const row of await readRemoteWorkspaceBaseline(new RemoteTypertGateway(client), signal)) {
              workspaces.push({ backend: 'harness', ...row });
            }
          } catch {
            signal.throwIfAborted();
            unavailable.push({ backend: 'harness', code: 'BACKEND_UNAVAILABLE' });
          }
        }
        return { workspaces, unavailable };
      },
    };
  }
  const discoveryTools = remoteDiscoveryTools(() => discoveryClient);
  const listHostsTool = discoveryTools.find(tool => tool.name === 'remote_list_hosts');
  const listWorkspacesTool = discoveryTools.find(tool => tool.name === 'remote_list_workspaces');
  assert.ok(listHostsTool && listWorkspacesTool, '固定上游未注册桌面发现工具');
  const discoveryTraceStart = rpcTrace.length;
  stage = '官方桌面remote_list_hosts入口';
  const discoveredHosts = await listHostsTool.execute({}, { signal: AbortSignal.timeout(10_000) });
  assert.deepEqual(Object.keys(discoveredHosts), ['hosts']);
  assert.equal(discoveredHosts.hosts.length, 1, '固定服务应只返回一个在线 Host');
  assert.equal(new Set(discoveredHosts.hosts.map(item => item.hostId)).size, discoveredHosts.hosts.length, '官方 Host 发现返回重复 hostId');
  const officialHost = discoveredHosts.hosts[0];
  assert.deepEqual(officialHost, { hostId, name: descriptor.name, platform: descriptor.platform });
  stage = '官方桌面remote_list_workspaces入口';
  const discoveredWorkspaces = await listWorkspacesTool.execute({ hostId }, { signal: AbortSignal.timeout(10_000) });
  assert.deepEqual(Object.keys(discoveredWorkspaces), ['hostId', 'workspaces', 'unavailable']);
  assert.equal(discoveredWorkspaces.hostId, hostId);
  assert.deepEqual(discoveredWorkspaces.unavailable, []);
  assert.equal(new Set(discoveredWorkspaces.workspaces.map(item => `${item.backend}:${item.workspaceId}`)).size,
    discoveredWorkspaces.workspaces.length, '官方工作区发现返回重复 backend/workspaceId');
  const officialWorkspace = discoveredWorkspaces.workspaces.find(item => item.backend === 'harness' && item.workspaceId === 'phone-capabilities');
  assert.ok(officialWorkspace, '官方工作区发现未返回 phone-capabilities');
  assert.equal(officialWorkspace.path, 'phone-capabilities');
  assert.ok(officialWorkspace.title.length > 0, '官方工作区标题为空');
  assert.equal(discoveredWorkspaces.workspaces.length, 1, '固定手机 Host 应只返回一个工作区');
  const discoveryTrace = rpcTrace.slice(discoveryTraceStart);
  assert.ok(discoveryTrace.some(item => item.endpoint === 'workspace/follow'), 'remote_list_workspaces 必须读取 workspace/follow 基线');
  assertNoFileWorkspaceRpc(discoveryTrace, 'remote_list_workspaces');
  assert.equal(connection.state.getSnapshot(), 'connected');
  assert.equal(client.getStats().connected, true, '发现期间不得替换控制握手');
  const tools = new RemoteSessionTools(async (_host, signal, query) => query(remote, signal));
  const signal = AbortSignal.timeout(10_000);
  const workspace = { hostId, backend: 'harness', workspaceId: 'phone-capabilities' };
  stage = 'Typert幂等打开手机虚拟工作区';
  const openTraceStart = rpcTrace.length;
  const openedFirst = await gateway.invoke({ namespace: 'workspace', method: 'create', args: { request: { path: 'phone-capabilities' } }, signal });
  const openedSecond = await gateway.invoke({ namespace: 'workspace', method: 'create', args: { request: { path: 'phone-capabilities' } }, signal });
  assert.equal(openedFirst?.created, false, 'workspace/create 幂等打开手机虚拟工作区必须返回 created:false');
  assert.equal(openedFirst?.workspace?.workspaceId, 'phone-capabilities');
  assert.equal(openedFirst?.workspace?.path, 'phone-capabilities');
  assert.deepEqual(openedFirst, openedSecond, '重复 workspace/create 必须返回同一共享手机工作区记录');
  const openTrace = rpcTrace.slice(openTraceStart);
  assert.equal(openTrace.filter(item => item.endpoint === 'workspace/create').length, 2);
  assert.equal(openTrace.filter(item => item.endpoint === 'session/create').length, 0, 'workspace/create 不得隐式创建任务');
  const rejectedOpen = await gateway.dispatch('workspace/create', { args: { request: { path: 'other-workspace' } } }, signal);
  assert.equal(rejectedOpen.ok, false, '非手机虚拟路径必须被 workspace/create 拒绝');
  assert.equal(rejectedOpen.error?.code, 'CONTROL_WORKSPACE_NOT_FOUND');
  stage = 'Typert工作区与会话目录';
  const listing = await tools.run('remote_list_sessions', workspace, signal);
  assert.ok(listing.sessions.some(item => item.sessionId === 'control-bus'));
  stage = 'Typert创建任务与能力请求';
  const createTraceStart = rpcTrace.length;
  const created = await tools.run('remote_create_session', { ...workspace, operationId: 'interop-create-00000001' }, signal);
  const secondCreated = await tools.run('remote_create_session', { ...workspace, operationId: 'interop-create-00000002' }, signal);
  const createTrace = rpcTrace.slice(createTraceStart);
  assert.ok(createTrace.some(item => item.endpoint === 'workspace/follow'), 'remote_create_session 应先验证 workspace/follow 基线');
  assert.equal(createTrace.filter(item => item.endpoint === 'session/create').length, 2, '两次独立 remote_create_session 必须各自调用 session/create');
  assertNoFileWorkspaceRpc(createTrace, 'remote_create_session');
  assert.equal(created.status, 'accepted');
  assert.equal(secondCreated.status, 'accepted');
  assert.notEqual(created.sessionId, secondCreated.sessionId, '独立任务创建必须返回不同 sessionId');
  assert.notEqual(created.sessionId, 'control-bus');
  assert.notEqual(secondCreated.sessionId, 'control-bus');
  await waitEvent(item => item.name === 'api-session/added' && item.args[0]?.sessionId === created.sessionId);
  const requestId = 'interop-capabilities-00000001';
  const sent = await tools.run('remote_send_message', { ...workspace, sessionId: created.sessionId, operationId: requestId,
    message: JSON.stringify({ request_id: requestId, op: 'phone.capabilities', args: {} }) }, signal);
  assert.equal(sent.status, 'accepted');
  stage = 'Typert读取请求回执';
  await tools.run('remote_wait_sessions', { targets: [{ ...workspace, sessionId: created.sessionId }], timeoutMs: 2_000 }, signal);
  const history = await tools.run('remote_read_session', { ...workspace, sessionId: created.sessionId }, signal);
  assert.equal(history.status, 'idle');
  const capabilityTerminal = businessTerminal(history.records, requestId);
  assert.equal(capabilityTerminal?.state, 'completed');
  assert.equal(capabilityTerminal?.execution?.status, 'succeeded');
  stage = '官方桌面Prompt携带时区元数据';
  assert.equal(Intl.DateTimeFormat().resolvedOptions().timeZone, expectedClientTimeZone);
  const desktop = new HarnessAlphaClient(client, {
    clientVersion: descriptor.clientVersion,
    ...(descriptor.harnessVersion === undefined ? {} : { harnessVersion: descriptor.harnessVersion }),
    sessionFormat: features.sessionFormat,
    capabilities: features.capabilities,
  });
  const desktopOpened = await desktop.workspaceCreate('phone-capabilities');
  assert.equal(desktopOpened.created, false, 'HarnessAlphaClient.workspaceCreate 必须返回 created:false');
  assert.equal(desktopOpened.workspace.workspaceId, 'phone-capabilities');
  const timeZoneRequest = 'interop-timezone-0000001';
  await desktop.sessionPrompt(created.sessionId, JSON.stringify({ request_id: timeZoneRequest,
    op: 'phone.capabilities', args: {} }), timeZoneRequest);
  const timeZoneWait = await tools.run('remote_wait_sessions', {
    targets: [{ ...workspace, sessionId: created.sessionId }], timeoutMs: 2_000,
  }, signal);
  assert.equal(timeZoneWait.sessions[0].status, 'idle');
  const timeZoneHistory = await tools.run('remote_read_session', { ...workspace, sessionId: created.sessionId }, signal);
  const timeZoneTerminal = businessTerminal(timeZoneHistory.records, timeZoneRequest);
  assert.equal(timeZoneTerminal?.state, 'completed');
  assert.equal(timeZoneTerminal?.execution?.status, 'succeeded');
  stage = '官方事件消费者持续观察真实执行状态';
  const runningRequest = 'interop-execute-00000001';
  const running = await tools.run('remote_send_message', { ...workspace, sessionId: created.sessionId, operationId: runningRequest,
    message: JSON.stringify({ request_id: runningRequest, op: 'ui.dump_tree', args: {} }) }, signal);
  assert.equal(running.status, 'accepted');
  await waitEvent(item => item.name === 'api-session/status' && item.args[0] === created.sessionId && item.args[1] === true);
  await client.rpc('probe.release', {});
  await waitEvent(item => item.name === 'api-session/status' && item.args[0] === created.sessionId && item.args[1] === false);
  stage = 'Typert读取真实执行终态';
  const runningHistory = await tools.run('remote_read_session', { ...workspace, sessionId: created.sessionId }, signal);
  const runningTerminal = businessTerminal(runningHistory.records, runningRequest);
  assert.equal(runningTerminal?.state, 'completed');
  assert.equal(runningTerminal?.execution?.status, 'succeeded');
  assert.equal(connection.state.getSnapshot(), 'connected');
  stage = 'Typert经control-bus核验原请求回执';
  const statusQueryId = 'interop-status-00000001';
  const statusSent = await tools.run('remote_send_message', { ...workspace, sessionId: 'control-bus', operationId: statusQueryId,
    message: JSON.stringify({ request_id: statusQueryId, op: 'request.status', args: { targetRequestId: runningRequest, targetTaskSessionId: created.sessionId } }) }, signal);
  assert.equal(statusSent.status, 'accepted');
  const busHistory = await tools.run('remote_read_session', { ...workspace, sessionId: 'control-bus' }, signal);
  const statusTerminal = businessTerminal(busHistory.records, statusQueryId);
  assert.equal(statusTerminal?.request_id, statusQueryId);
  assert.equal(statusTerminal?.taskSessionId, 'control-bus');
  assert.equal(statusTerminal?.state, 'completed');
  assert.equal(statusTerminal?.result?.request_id, runningRequest);
  assert.equal(statusTerminal?.result?.taskSessionId, created.sessionId);
  assert.equal(statusTerminal?.result?.state, 'completed');
  assert.equal(statusTerminal?.result?.execution?.status, 'succeeded');
  assert.equal(statusTerminal?.result?.resultAvailable, true);
  stage = '真实事件回答与订阅关闭';
  const answered = await gateway.dispatch('$events/result', { args: { clientId: eventsClientId, eventId: 'unknown',
    outcome: { kind: 'rejected', error: { name: 'Error', message: '', code: '' } } } }, signal);
  assert.equal(answered.ok, true);
  const generationEnded = new Promise(resolve => { eventGenerationEnded = resolve; });
  lifecycle.stop(); await events.dispose(); await generationEnded;
  const stale = await gateway.dispatch('$events/result', { args: { clientId: eventsClientId, eventId: 'unknown', outcome: { kind: 'next' } } }, signal);
  assert.equal(stale.ok, false);
  console.log(JSON.stringify({ status: 'PASS', upstream: 'ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7', release: 'eef232d2c43b0c9d04998a18db98cbc9e673e488',
    transport: client.getStats().mode, sessionFormat: features.sessionFormat, workspace: workspace.workspaceId,
    capabilities: features.capabilities, harnessVersion: descriptor.harnessVersion ?? null,
    discoveryRuntime: installedRuntime === undefined ? 'fixed-source-adapter' : 'installed-0.6.5', runtimeVerification: installedRuntimeVerification ?? null, fullChainRuntimeVerification: fullChainRuntimeVerification ?? null,
    desktopHosts: discoveredHosts.hosts.length, desktopWorkspaces: discoveredWorkspaces.workspaces.length,
    workspaceDiscoveryRpc: 'workspace/follow', createSessionRpc: 'session/create', forbiddenFileWorkspaceRpcs: [],
    directoryDiscovered: true, presenceOnline: presence.online, clientTimeZone: expectedClientTimeZone }));
} catch (error) {
  console.error(JSON.stringify({ status: 'FAIL', stage, code: error.code ?? error.name, message: error.message }));
  process.exitCode = 1;
} finally { discoveryServerApi?.close(); lifecycle?.stop(); await events?.dispose(); await client.close(); }
