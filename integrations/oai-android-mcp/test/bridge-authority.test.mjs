import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, rename, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { PhoneMcpBridge, registerPhoneMcpRoute } from '../src/bridge.mjs';
import { apply, createRuntimeAuthority, inject, name } from '../index.mjs';

function identityStatus(deviceId, overrides = {}) {
  return {
    available: true,
    identityReady: true,
    clientAuthRequired: false,
    deviceId,
    serverUrl: 'https://remote.invalid',
    host: { authorized: true, accountRequired: false, pairingHostOnly: false, deviceId: 'host-1', account: 'owner' },
    ...overrides,
  };
}

class FixtureClientServerApi {
  role = 'client';
  baseUrl = 'https://remote.invalid';
  constructor(path) { this.store = { path }; }
  async authorizeWithAccount(_identity, _account, authorize) { return authorize(); }
  async saveTokens() { return {}; }
}

function fakeRuntime(deviceId, overrides = {}) {
  let hasCredentials = true;
  let status = identityStatus(deviceId, overrides);
  let logoutCount = 0;
  return {
    server: new FixtureClientServerApi(deviceId),
    status: () => status,
    async handleControl(endpoint) {
      assert.equal(endpoint, 'client.authorization.status');
      return { ok: true, value: { hasCredentials } };
    },
    async devices() { return []; },
    async discoverToolWorkspaces() { return { workspaces: [{ backend: 'harness', workspaceId: 'phone-capabilities' }] }; },
    async runSessionTool() { return { status: 'idle', records: [] }; },
    async clearClientAuthorization() {
      logoutCount += 1;
      hasCredentials = false;
    },
    async authorizeClientWithAccount() {
      hasCredentials = true;
      return { method: 'account', account: status.host?.account ?? 'owner' };
    },
    async pollClientOAuthQrLogin() {
      return { status: 'pending' };
    },
    getLogoutCount: () => logoutCount,
    setCredentials(value) { hasCredentials = value; },
    setDeviceId(value) { status = identityStatus(value); },
    setStatus(next) { status = next; },
  };
}

function routeFixture(bridge, bearer = 'auth-test-bearer-0001') {
  let handler;
  const webServer = { host: '127.0.0.1', port: 43123, protocol: 'http:', getOrigin: () => 'http://127.0.0.1:43123', register(route) { handler = route.handler; return () => {}; } };
  const registration = registerPhoneMcpRoute({ bridge, bearer, connection: { requestRejection: () => 401 }, webServer });
  return { handler, registration };
}

async function invoke(handler, url, { method = 'POST', headers = {}, body = '{}', remoteAddress = '127.0.0.1' } = {}) {
  const response = { status: undefined, body: '', writeHead(status) { this.status = status; }, end(value = '') { this.body += value; } };
  const request = {
    method, url, headers: { host: '127.0.0.1:43123', authorization: 'Bearer auth-test-bearer-0001', 'content-type': 'application/json', ...headers },
    socket: { remoteAddress }, complete: true,
    on() {},
    async *[Symbol.asyncIterator]() { yield Buffer.from(body); },
  };
  await handler(request, response);
  return response;
}

test('OAI 路由不使用 DSH Cookie/API Peer，但通用 DSH 校验函数保持存在且拒绝非回环与浏览器头', async () => {
  const runtime = fakeRuntime('client-1');
  const bridge = new PhoneMcpBridge(runtime, createRuntimeAuthority());
  const { handler, registration } = routeFixture(bridge);
  try {
    const response = await invoke(handler, '/oai-android-mcp/v1/status');
    assert.equal(response.status, 200);
    assert.deepEqual(JSON.parse(response.body).value, { available: true, protocol: 1, phoneOnly: true });

    const remoteAddr = await invoke(handler, '/oai-android-mcp/v1/status', { remoteAddress: '192.168.1.9' });
    assert.equal(remoteAddr.status, 403);
    assert.equal(JSON.parse(remoteAddr.body).error.code, 'BRIDGE_LOOPBACK_REQUIRED');

    const fetchSite = await invoke(handler, '/oai-android-mcp/v1/status', { headers: { 'sec-fetch-site': 'same-origin' } });
    assert.equal(fetchSite.status, 403);
    assert.equal(JSON.parse(fetchSite.body).error.code, 'BRIDGE_WEB_ORIGIN_REJECTED');

    const browserOrigin = await invoke(handler, '/oai-android-mcp/v1/status', { headers: { origin: 'http://127.0.0.1:43123' } });
    assert.equal(browserOrigin.status, 403);
    assert.equal(JSON.parse(browserOrigin.body).error.code, 'BRIDGE_WEB_ORIGIN_REJECTED');
  } finally { await registration.close(); }
});

test('原始 URL 只接受精确自有操作，拒绝规范化、查询、absolute-form 与原型属性', async () => {
  const bridge = new PhoneMcpBridge(fakeRuntime('client-1'), createRuntimeAuthority());
  const { handler, registration } = routeFixture(bridge);
  try {
    for (const url of ['/oai-android-mcp/v1/../v1/status', '/oai-android-mcp/v1/status?x=1', 'http://127.0.0.1:43123/oai-android-mcp/v1/status', '/oai-android-mcp/v1/__proto__', '/oai-android-mcp/v1/toString']) {
      const response = await invoke(handler, url);
      assert.equal(response.status, 404, url);
    }
  } finally { await registration.close(); }
});

test('真实授权控制、注销（含重新登录）、换服、换账号、配对受限都会让旧桥永久 fail closed', async () => {
  // 1. 注销后即使重新出现凭据，旧桥仍须永久失效
  const logoutRuntime = fakeRuntime('client-1');
  const logoutBridge = new PhoneMcpBridge(logoutRuntime, createRuntimeAuthority());
  assert.deepEqual(await logoutBridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });
  logoutRuntime.setCredentials(false);
  await assert.rejects(logoutBridge.call('status', {}), /BRIDGE_AUTHORITY_CHANGED/u);
  logoutRuntime.setCredentials(true);
  await assert.rejects(logoutBridge.call('status', {}), /BRIDGE_AUTHORITY_CHANGED/u);

  // 2. 切换 Remote Server（替换 runtime 实例或 serverUrl）后旧桥必须失效，切回原服也不得复活
  let currentRuntime = fakeRuntime('client-1');
  const serverBridge = new PhoneMcpBridge(() => currentRuntime, createRuntimeAuthority());
  assert.deepEqual(await serverBridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });
  const firstRuntime = currentRuntime;
  currentRuntime = fakeRuntime('client-1', { serverUrl: 'https://other-server.invalid' });
  await assert.rejects(serverBridge.call('status', {}), /BRIDGE_AUTHORITY_CHANGED/u);
  currentRuntime = firstRuntime;
  await assert.rejects(serverBridge.call('status', {}), /BRIDGE_AUTHORITY_CHANGED/u);

  // 3. 仅配对 Host 受限身份或 Host 已被吊销/需重登时不能授权客户端桥
  const pairingRuntime = fakeRuntime('client-1', {
    host: { configured: true, authorized: true, accountRequired: false, pairingHostOnly: true, deviceId: 'host-1', account: 'owner' },
  });
  const pairingBridge = new PhoneMcpBridge(pairingRuntime, createRuntimeAuthority());
  await assert.rejects(pairingBridge.call('status', {}), /BRIDGE_AUTHORITY_UNAVAILABLE/u);

  const revokedHostRuntime = fakeRuntime('client-1', {
    host: { configured: true, authorized: false, accountRequired: true, pairingHostOnly: false, error: 'DEVICE_REVOKED', deviceId: 'host-1' },
  });
  await assert.rejects(new PhoneMcpBridge(revokedHostRuntime, createRuntimeAuthority()).call('status', {}), /BRIDGE_AUTHORITY_UNAVAILABLE/u);

  // 4. 仅启用 Client（桌面 Host 未开启且无错误/受限）时客户端桥仍可正常工作
  const clientOnlyRuntime = fakeRuntime('client-only-1', {
    host: { configured: false, authorized: false, accountRequired: false, pairingHostOnly: false },
  });
  assert.deepEqual(await new PhoneMcpBridge(clientOnlyRuntime, createRuntimeAuthority()).call('status', {}), { available: true, protocol: 1, phoneOnly: true });
});

test('单次请求取消/超时、本机 Host 暂态错误与 host.account 懒加载不击杀共享桥，但跨账号切换永久 fail closed', async () => {
  // 1. 单次请求 abort（含 handleControl 抛错或返回 ok:false）绝不将全局桥置为 revoked
  const abortRuntime = fakeRuntime('client-abort-1');
  const origHandleControl = abortRuntime.handleControl.bind(abortRuntime);
  let rejectOnAbort = true;
  abortRuntime.handleControl = async (endpoint, payload, signal) => {
    if (signal?.aborted) {
      if (rejectOnAbort) throw new DOMException('aborted', 'AbortError');
      return { ok: false, error: { code: 'CANCELLED' } };
    }
    return origHandleControl(endpoint, payload, signal);
  };
  const abortBridge = new PhoneMcpBridge(abortRuntime, createRuntimeAuthority());
  assert.deepEqual(await abortBridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });

  const aborted1 = new AbortController();
  aborted1.abort(new Error('REQUEST_TIMED_OUT'));
  await assert.rejects(abortBridge.call('status', {}, aborted1.signal), /REQUEST_TIMED_OUT/u);
  assert.deepEqual(await abortBridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });

  // 在 handleControl 执行途中触发取消并由 runtime 返回 ok:false 时，同样不得污染全局桥
  rejectOnAbort = false;
  const inflightAbort = new AbortController();
  abortRuntime.handleControl = async (_endpoint, _payload, signal) => {
    inflightAbort.abort(new Error('INFLIGHT_ABORT'));
    return { ok: false, error: { code: 'CANCELLED' }, aborted: signal?.aborted };
  };
  await assert.rejects(abortBridge.call('status', {}, inflightAbort.signal), /INFLIGHT_ABORT/u);
  abortRuntime.handleControl = origHandleControl;
  assert.deepEqual(await abortBridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });

  // list_devices 探测途中取消亦不得击杀共享桥
  const listAbort = new AbortController();
  abortRuntime.devices = async () => [{ deviceId: 'phone-a', name: 'A', online: true }];
  abortRuntime.discoverToolWorkspaces = async () => {
    listAbort.abort(new Error('DISCOVER_ABORT'));
    throw new Error('DISCOVER_ABORT');
  };
  await assert.rejects(abortBridge.call('list_devices', {}, listAbort.signal), /DISCOVER_ABORT/u);
  assert.deepEqual(await abortBridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });

  // 2. 本机 Host 暂态网络错误（CONNECTION_FAILED / RATE_LIMITED 且 accountRequired !== true）不误杀健康 Client 桥
  const transientHostRuntime = fakeRuntime('client-transient-1', {
    host: { configured: true, authorized: false, accountRequired: false, pairingHostOnly: false, error: 'CONNECTION_FAILED', deviceId: 'host-1' },
  });
  const transientBridge = new PhoneMcpBridge(transientHostRuntime, createRuntimeAuthority());
  assert.deepEqual(await transientBridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });
  transientHostRuntime.setStatus(identityStatus('client-transient-1', {
    host: { configured: true, authorized: false, accountRequired: false, pairingHostOnly: false, error: 'RATE_LIMITED', deviceId: 'host-1' },
  }));
  assert.deepEqual(await transientBridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });

  // 3. host.account 从 null -> acc-1 懒加载以及开关本机被控（acc-1 -> null -> acc-1）均正常，但切换为 acc-2 永久 fail closed
  const lazyRuntime = fakeRuntime('client-lazy-1', {
    host: { configured: true, authorized: false, accountRequired: false, pairingHostOnly: false, deviceId: 'host-1' },
  });
  const lazyBridge = new PhoneMcpBridge(lazyRuntime, createRuntimeAuthority());
  assert.deepEqual(await lazyBridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });
  lazyRuntime.setStatus(identityStatus('client-lazy-1', {
    host: { configured: true, authorized: true, accountRequired: false, pairingHostOnly: false, deviceId: 'host-1', account: 'acc-1' },
  }));
  assert.deepEqual(await lazyBridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });
  lazyRuntime.setStatus(identityStatus('client-lazy-1', {
    host: { configured: true, authorized: false, accountRequired: false, pairingHostOnly: false, deviceId: 'host-1' },
  }));
  assert.deepEqual(await lazyBridge.call('status', {}), { available: true, protocol: 1, phoneOnly: true });
  lazyRuntime.setStatus(identityStatus('client-lazy-1', {
    host: { configured: true, authorized: true, accountRequired: false, pairingHostOnly: false, deviceId: 'host-1', account: 'acc-2' },
  }));
  await assert.rejects(lazyBridge.call('status', {}), /BRIDGE_AUTHORITY_CHANGED/u);
  // 即使切回 acc-1 也已永久失效
  lazyRuntime.setStatus(identityStatus('client-lazy-1', {
    host: { configured: true, authorized: true, accountRequired: false, pairingHostOnly: false, deviceId: 'host-1', account: 'acc-1' },
  }));
  await assert.rejects(lazyBridge.call('status', {}), /BRIDGE_AUTHORITY_CHANGED/u);
});

test('apply 原子写入用户指定的 privateConfigPath（0700/0600），卸载后旧桥授权立即失效', async (t) => {
  const dir = await mkdtemp(join(tmpdir(), 'oai-android-mcp-apply-'));
  const privateDir = join(dir, 'private');
  const privateConfigPath = join(privateDir, 'mcp.json');
  t.after(async () => {
    const trash = join(tmpdir(), 'oai-android-mcp-trash');
    await mkdir(trash, { recursive: true });
    await rename(dir, join(trash, `apply-${Date.now()}`));
  });
  let handler;
  let disposedRoute = false;
  const runtime = fakeRuntime('client-apply-1');
  let cleanup;
  const ctx = {
    get(service) {
      if (service === 'dshRemoteClient') return runtime;
      if (service === 'connection') return { requestRejection: () => 401 };
      if (service === 'webServer') return {
        host: '127.0.0.1', port: 43123, protocol: 'http:',
        getOrigin: () => 'http://127.0.0.1:43123',
        register(route) { handler = route.handler; return () => { disposedRoute = true; }; },
      };
      return undefined;
    },
    effect(fn) { return Promise.resolve(fn()).then((dispose) => { cleanup = dispose; }); },
  };

  await apply(ctx, { privateConfigPath });
  const dirMode = (await stat(privateDir)).mode & 0o777;
  const fileMode = (await stat(privateConfigPath)).mode & 0o777;
  assert.equal(dirMode, 0o700);
  assert.equal(fileMode, 0o600);
  const written = JSON.parse(await readFile(privateConfigPath, 'utf8'));
  const env = written.mcpServers['oai-android'].env;
  assert.equal(env.OAI_ANDROID_BRIDGE_URL, 'http://127.0.0.1:43123');
  assert.match(env.OAI_ANDROID_BRIDGE_BEARER, /^[A-Za-z0-9._~-]{16,256}$/u);

  const okResp = await invoke(handler, '/oai-android-mcp/v1/status', {
    headers: { authorization: `Bearer ${env.OAI_ANDROID_BRIDGE_BEARER}` },
  });
  assert.equal(okResp.status, 200);

  // 卸载/停用插件必须立即撤销旧桥 bearer 与路由
  await cleanup();
  assert.equal(disposedRoute, true);
  const closedResp = await invoke(handler, '/oai-android-mcp/v1/status', {
    headers: { authorization: `Bearer ${env.OAI_ANDROID_BRIDGE_BEARER}` },
  });
  assert.equal(closedResp.status, 410);
  assert.equal(JSON.parse(closedResp.body).error.code, 'BRIDGE_CLOSED');
});

test('签发桥凭据前必须身份就绪并绑定登录代次；首次使用前注销并重新登录必须拒绝旧配置', async (t) => {
  const dir = await mkdtemp(join(tmpdir(), 'oai-android-mcp-preuse-logout-'));
  t.after(async () => {
    const trash = join(tmpdir(), 'oai-android-mcp-trash');
    await mkdir(trash, { recursive: true });
    await rename(dir, join(trash, `preuse-logout-${Date.now()}`));
  });
  const createCtx = (getRuntime) => {
    let handler;
    let registerCalls = 0;
    let disposedRoute = false;
    let cleanup;
    const ctx = {
      get(service) {
        if (service === 'dshRemoteClient') return getRuntime();
        if (service === 'connection') return { requestRejection: () => 401 };
        if (service === 'webServer') return {
          host: '127.0.0.1', port: 43123, protocol: 'http:',
          getOrigin: () => 'http://127.0.0.1:43123',
          register(route) { registerCalls += 1; handler = route.handler; return () => { disposedRoute = true; }; },
        };
        return undefined;
      },
      effect(fn) { return Promise.resolve(fn()).then((dispose) => { cleanup = dispose; }); },
    };
    return { ctx, getHandler: () => handler, getRegisterCalls: () => registerCalls, isDisposed: () => disposedRoute, cleanup: () => cleanup?.() };
  };

  // 1. 身份未就绪或未登录时，绝不注册路由、不签发 bearer、不落盘配置文件
  for (const [label, runtime] of [
    ['identity-not-ready', fakeRuntime('client-unready', { identityReady: false })],
    ['no-credentials', (() => { const rt = fakeRuntime('client-no-cred'); rt.setCredentials(false); return rt; })()],
  ]) {
    const configPath = join(dir, label, 'mcp.json');
    const envCtx = createCtx(() => runtime);
    await assert.rejects(apply(envCtx.ctx, { privateConfigPath: configPath }), /BRIDGE_AUTHORITY_UNAVAILABLE/u, label);
    assert.equal(envCtx.getRegisterCalls(), 0, label);
    await assert.rejects(stat(configPath), { code: 'ENOENT' });
  }

  // 2. 配置签发后、从未调用过 -> 注销并换新 deviceId 重新登录 -> 首次拿旧配置调用必须被拒绝（绝不懒绑定到新登录）
  const rotatedRuntime = fakeRuntime('client-gen-1');
  const rotatedConfigPath = join(dir, 'rotated', 'mcp.json');
  const rotatedCtx = createCtx(() => rotatedRuntime);
  await apply(rotatedCtx.ctx, { privateConfigPath: rotatedConfigPath });
  const rotatedBearer = JSON.parse(await readFile(rotatedConfigPath, 'utf8')).mcpServers['oai-android'].env.OAI_ANDROID_BRIDGE_BEARER;
  // 模拟在首次调用前发生注销 + 重登（不调用 status，且不经 clearClientAuthorization 包装，纯靠签发时已绑定的 bound 拦截）
  rotatedRuntime.setCredentials(false);
  rotatedRuntime.setDeviceId('client-gen-2');
  rotatedRuntime.setCredentials(true);
  const firstAfterRelogin = await invoke(rotatedCtx.getHandler(), '/oai-android-mcp/v1/status', {
    headers: { authorization: `Bearer ${rotatedBearer}` },
  });
  assert.equal(firstAfterRelogin.status, 409);
  assert.equal(JSON.parse(firstAfterRelogin.body).error.code, 'BRIDGE_AUTHORITY_CHANGED');
  // 即使切回原 deviceId 也必须永久失效
  rotatedRuntime.setDeviceId('client-gen-1');
  const revertedResp = await invoke(rotatedCtx.getHandler(), '/oai-android-mcp/v1/status', {
    headers: { authorization: `Bearer ${rotatedBearer}` },
  });
  assert.equal(revertedResp.status, 409);
  assert.equal(JSON.parse(revertedResp.body).error.code, 'BRIDGE_AUTHORITY_CHANGED');
  await rotatedCtx.cleanup();

  // 3. 配置签发后、从未调用过 -> 调用 clearClientAuthorization 注销并同 deviceId 重新登录 -> 同一代次签发的全部凭据立即失效
  const sameIdRuntime = fakeRuntime('client-same-id');
  const configA = join(dir, 'same-a', 'mcp.json');
  const configB = join(dir, 'same-b', 'mcp.json');
  const ctxA = createCtx(() => sameIdRuntime);
  const ctxB = createCtx(() => sameIdRuntime);
  await apply(ctxA.ctx, { privateConfigPath: configA });
  await apply(ctxB.ctx, { privateConfigPath: configB });
  const bearerA = JSON.parse(await readFile(configA, 'utf8')).mcpServers['oai-android'].env.OAI_ANDROID_BRIDGE_BEARER;
  const bearerB = JSON.parse(await readFile(configB, 'utf8')).mcpServers['oai-android'].env.OAI_ANDROID_BRIDGE_BEARER;
  // 注销前从未调用过任何工具
  await sameIdRuntime.clearClientAuthorization();
  assert.equal(sameIdRuntime.getLogoutCount(), 1);
  assert.equal(ctxA.isDisposed(), true);
  assert.equal(ctxB.isDisposed(), true);
  // 同一 deviceId 重新登录，旧配置首次使用仍必须被拒绝
  await sameIdRuntime.authorizeClientWithAccount();
  for (const [label, currentCtx, bearer] of [['A', ctxA, bearerA], ['B', ctxB, bearerB]]) {
    const resp = await invoke(currentCtx.getHandler(), '/oai-android-mcp/v1/status', {
      headers: { authorization: `Bearer ${bearer}` },
    });
    assert.equal(resp.status, 409, label);
    assert.equal(JSON.parse(resp.body).error.code, 'BRIDGE_AUTHORITY_CHANGED', label);
    await currentCtx.cleanup();
  }
});

test('回环 HTTP 使用默认端口 80 时可注册并按精确 Host 放行，同时保留端口与回环严格校验', async () => {
  for (const [host, origin, validHosts, rejectedHosts] of [
    ['127.0.0.1', 'http://127.0.0.1:80', ['127.0.0.1', '127.0.0.1:80'], ['127.0.0.1:8080', '127.0.0.1:080', 'localhost', 'localhost:80', '[::1]', 'evil.invalid']],
    ['127.0.0.1', 'http://127.0.0.1', ['127.0.0.1', '127.0.0.1:80'], ['127.0.0.1:8080', 'localhost:80']],
    ['::1', 'http://[::1]:80', ['[::1]', '[::1]:80'], ['[::1]:8080', '127.0.0.1', '127.0.0.1:80', 'localhost']],
    ['::1', 'http://[::1]', ['[::1]', '[::1]:80'], ['[::1]:3080', '127.0.0.1']],
  ]) {
    let handler;
    const bridge = new PhoneMcpBridge(fakeRuntime('client-port-80'), createRuntimeAuthority());
    const webServer = {
      host, port: 80, protocol: 'http:',
      getOrigin: () => origin,
      register(route) { handler = route.handler; return () => {}; },
    };
    const registration = registerPhoneMcpRoute({ bridge, bearer: 'port80-test-bearer-001', connection: { requestRejection: () => 401 }, webServer });
    try {
      const remoteAddress = host === '::1' ? '::1' : '127.0.0.1';
      for (const validHost of validHosts) {
        const resp = await invoke(handler, '/oai-android-mcp/v1/status', {
          remoteAddress,
          headers: { host: validHost, authorization: 'Bearer port80-test-bearer-001' },
        });
        assert.equal(resp.status, 200, `${origin} host=${validHost}`);
      }
      for (const badHost of rejectedHosts) {
        const resp = await invoke(handler, '/oai-android-mcp/v1/status', {
          remoteAddress,
          headers: { host: badHost, authorization: 'Bearer port80-test-bearer-001' },
        });
        assert.equal(resp.status, 403, `${origin} badHost=${badHost}`);
        assert.equal(JSON.parse(resp.body).error.code, 'BRIDGE_HOST_REJECTED');
      }
    } finally {
      await registration.close();
    }
  }

  // 非默认端口仍拒绝省略端口的 Host，且拒绝 :0 / 超界端口 / https / 非回环域名
  const nonDefaultBridge = new PhoneMcpBridge(fakeRuntime('client-port-43123'), createRuntimeAuthority());
  const { handler, registration } = routeFixture(nonDefaultBridge);
  try {
    for (const badHost of ['127.0.0.1', '127.0.0.1:80']) {
      const resp = await invoke(handler, '/oai-android-mcp/v1/status', { headers: { host: badHost } });
      assert.equal(resp.status, 403);
      assert.equal(JSON.parse(resp.body).error.code, 'BRIDGE_HOST_REJECTED');
    }
  } finally {
    await registration.close();
  }

  for (const invalidServer of [
    { host: '127.0.0.1', port: 0, protocol: 'http:', getOrigin: () => 'http://127.0.0.1:0' },
    { host: '127.0.0.1', port: 65536, protocol: 'http:', getOrigin: () => 'http://127.0.0.1:65536' },
    { host: '127.0.0.1', port: 80, protocol: 'https:', getOrigin: () => 'https://127.0.0.1:80' },
    { host: '127.0.0.1', port: 80, protocol: 'http:', getOrigin: () => 'http://localhost:80' },
    { host: '127.0.0.1', port: 80, protocol: 'http:', getOrigin: () => 'http://127.0.0.1:8080' },
  ]) {
    assert.throws(() => registerPhoneMcpRoute({
      bridge: nonDefaultBridge,
      bearer: 'port80-test-bearer-001',
      connection: { requestRejection: () => 401 },
      webServer: { ...invalidServer, register: () => () => {} },
    }), /DSH_WEB_SERVER_(?:LOOPBACK_REQUIRED|ORIGIN_INVALID)/u);
  }
});

test('可选真实 DSH 组件：requestRejection 无 Cookie 返回 401，真实 WebServer 上 OAI bearer 仍可走严格回环路由', { skip: !process.env.OAI_DSH_FIXTURES_NODE_MODULES }, async () => {
  const root = process.env.OAI_DSH_FIXTURES_NODE_MODULES;
  const cordis = await import(pathToFileURL(join(root, '@deepseek-ai/cordis/lib/index.js')));
  const dsh = await import(pathToFileURL(join(root, '@deepseek-ai/dsh-client-connection/lib/index.js')));
  const webServerMod = await import(pathToFileURL(join(root, '@deepseek-ai/dsh-host-webserver/lib/index.js')));
  const ctx = new cordis.Context();
  await ctx.plugin(webServerMod.WebServer, { host: '127.0.0.1', port: 0, compression: 'none' });
  const connection = new dsh.HostConnectionService(ctx, [], { isAuthenticated: () => false, authenticateApi: () => undefined });
  const webServer = ctx.get('webServer');
  assert.equal(connection.requestRejection({ headers: { host: `127.0.0.1:${webServer.port}` } }), 401);

  const bridge = new PhoneMcpBridge(fakeRuntime('client-1'), createRuntimeAuthority());
  const registration = registerPhoneMcpRoute({ bridge, connection, webServer, bearer: 'auth-test-bearer-0001' });
  try {
    const ok = await fetch(`${registration.origin}/oai-android-mcp/v1/status`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${registration.bearer}` },
      body: '{}',
    });
    assert.equal(ok.status, 200);
    assert.deepEqual((await ok.json()).value, { available: true, protocol: 1, phoneOnly: true });

    const unauthorized = await fetch(`${registration.origin}/oai-android-mcp/v1/status`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: 'Bearer wrong-bearer-000001' },
      body: '{}',
    });
    assert.equal(unauthorized.status, 401);

    const browserOrigin = await fetch(`${registration.origin}/oai-android-mcp/v1/status`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${registration.bearer}`, origin: 'https://evil.invalid' },
      body: '{}',
    });
    assert.equal(browserOrigin.status, 403);
  } finally {
    await registration.close();
    await ctx.fiber.dispose();
  }
});


const fixedSettingsTest = {
  skip: !process.env.OAI_DSH_REMOTE_ENTRY || !process.env.OAI_DSH_FIXTURES_NODE_MODULES,
  timeout: 10_000,
};

test('固定 DSH 0.6.5 设置页重授权拦截签发、丢弃在途结果，并保留上游失败语义', fixedSettingsTest,
  t => assertSettingsReauthorizationRevokesBridge(t));

test('桥启用前已发出的设置页重授权响应也能吊销首次签发的凭据', fixedSettingsTest,
  t => assertSettingsReauthorizationRevokesBridge(t, true));

async function assertSettingsReauthorizationRevokesBridge(t, startedBeforeBridge = false) {
  const entry = pathToFileURL(process.env.OAI_DSH_REMOTE_ENTRY);
  const manifest = JSON.parse(await readFile(new URL('../package.json', entry), 'utf8'));
  assert.equal(manifest.name, 'ds-harness-remote');
  assert.equal(manifest.version, '0.6.5');
  const dsh = await import(entry.href);
  const fixtures = process.env.OAI_DSH_FIXTURES_NODE_MODULES;
  const { Context } = await import(pathToFileURL(join(fixtures, '@deepseek-ai/cordis/lib/index.js')));
  const { WebServer } = await import(pathToFileURL(join(fixtures, '@deepseek-ai/dsh-host-webserver/lib/index.js')));
  const root = await mkdtemp(join(tmpdir(), 'oai-android-mcp-settings-'));
  const ctx = new Context();
  const originalFetch = globalThis.fetch;
  t.after(async () => {
    globalThis.fetch = originalFetch;
    await ctx.fiber.dispose();
    const trash = join(tmpdir(), 'oai-android-mcp-trash');
    await mkdir(trash, { recursive: true });
    await rename(root, join(trash, `settings-${root.split('/').at(-1)}`));
  });

  const serverUrl = 'https://dsh-settings-fixture.invalid';
  const configInput = { serverUrl, role: 'client', hostControl: { enabled: false } };
  const config = dsh.resolveConfig(configInput);
  const identitiesRoot = join(root, 'identities');
  const identities = new dsh.IdentityStore({ directory: dsh.serverStorageDirectory(identitiesRoot, serverUrl, 'client'), role: 'client' });
  const identity = await identities.loadOrCreate('隔离客户端');
  const store = new dsh.ServerCredentialStore(identities.directory);
  const tokens = label => ({ accessToken: `${label}-fixture-access-token`, refreshToken: `${label}-fixture-refresh-token`,
    accessTokenExpiresAt: Date.now() + 120_000, refreshTokenExpiresAt: Date.now() + 180_000 });
  const prior = { serverUrl, deviceId: identity.deviceId, authorizationMethod: 'account', account: 'original@example.invalid' };
  await store.save({ ...prior, ...tokens('original') });
  const api = new dsh.ClientServerApi(serverUrl, store);
  const logger = { debug() {}, info() {}, warn() {}, error() {} };
  const unexpectedGatewayCall = () => { throw new Error('设置测试不访问本地 Gateway'); };
  const gateway = { invoke: unexpectedGatewayCall, dispatchRpc: unexpectedGatewayCall,
    openWireStream: unexpectedGatewayCall, failure: unexpectedGatewayCall };
  const client = new dsh.ClientModeRuntime(config, identities, api, gateway, logger);
  await client.start();
  t.after(() => client.close());

  const fetchStarted = Promise.withResolvers();
  const admitRegistration = Promise.withResolvers();
  const settingsStarted = Promise.withResolvers();
  const finishSettings = Promise.withResolvers();
  const listStarted = Promise.withResolvers();
  const finishList = Promise.withResolvers();
  const nextAccount = startedBeforeBridge ? prior.account : 'replacement@example.invalid';
  let loginCalls = 0;
  globalThis.fetch = async (input, options) => {
    const url = new URL(input);
    assert.equal(url.origin, serverUrl);
    if (url.pathname === '/api/v1/auth/login') {
      loginCalls++;
      const { email } = JSON.parse(options.body);
      if (email !== nextAccount) return Response.json({ error: { code: 'AUTH_INVALID', message: '隔离登录拒绝', retryable: false } }, { status: 401 });
      return Response.json({ token: 'fixture-local-account-token', account: nextAccount, expiresAt: Date.now() + 120_000, isAdmin: false });
    }
    assert.equal(url.pathname, '/api/v1/devices/register');
    assert.equal(JSON.parse(options.body).device.deviceId, identity.deviceId);
    fetchStarted.resolve();
    await admitRegistration.promise;
    return Response.json(tokens('replacement'));
  };
  const settings = { get: () => configInput, async replace() {
    settingsStarted.resolve();
    await finishSettings.promise;
    throw new Error('隔离设置保存失败');
  } };
  const control = new dsh.PluginControlRuntime(config, identitiesRoot, settings, client);
  await ctx.plugin(WebServer, { host: '127.0.0.1', port: 0, compression: 'none' });
  const webServer = ctx.get('webServer');
  const connection = { requestRejection: req => req.headers['x-fixture-control'] === 'authorized' ? undefined : 401 };
  const disposeControl = control.register(connection, webServer);
  t.after(disposeControl);
  await ctx.provide('dshRemoteClient', client);
  await ctx.provide('connection', connection);
  const rpc = (method, payload, authorized = true) => originalFetch(`${webServer.getOrigin()}/ds-harness-remote/${method}`, {
    method: 'POST', headers: { 'content-type': 'application/json', ...(authorized ? { 'x-fixture-control': 'authorized' } : {}) },
    body: JSON.stringify({ type: 'client-request', rpcId: method, method, payload }), signal: AbortSignal.timeout(5_000),
  });
  const configure = (email, authorized) => rpc('settings.configure', { serverUrl, role: 'client', email, password: 'fixture-password' }, authorized);

  try {
    let switching;
    if (startedBeforeBridge) {
      switching = configure(nextAccount);
      await fetchStarted.promise;
    }
    const privateConfigPath = join(root, 'private', 'mcp.json');
    await ctx.plugin({ name, inject, apply }, { privateConfigPath });
    const env = JSON.parse(await readFile(privateConfigPath, 'utf8')).mcpServers['oai-android'].env;
    const bridgeCall = (op) => originalFetch(`${env.OAI_ANDROID_BRIDGE_URL}/oai-android-mcp/v1/${op}`, {
      method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${env.OAI_ANDROID_BRIDGE_BEARER}` }, body: '{}',
      signal: AbortSignal.timeout(5_000),
    });

    if (!startedBeforeBridge) {
      const unauthorized = await configure(nextAccount, false);
      assert.equal(unauthorized.status, 401);
      assert.equal(loginCalls, 0);
      const withoutAuthorization = await rpc('settings.configure', { serverUrl, role: 'client' });
      assert.equal((await withoutAuthorization.json()).result.ok, false);
      assert.equal(loginCalls, 0);
      const denied = await configure('denied@example.invalid');
      const deniedResult = (await denied.json()).result;
      assert.equal(deniedResult.error?.details?.remoteCode, 'AUTH_INVALID', deniedResult.error?.message);
      assert.equal((await store.load(serverUrl, identity.deviceId)).account, prior.account);
      assert.equal((await bridgeCall('status')).status, 200);
    }

    client.devices = async () => {
      listStarted.resolve();
      await finishList.promise;
      return [];
    };
    const listing = bridgeCall('list_devices');
    await listStarted.promise;
    if (!startedBeforeBridge) {
      switching = configure(nextAccount);
      await fetchStarted.promise;
      const blocked = await bridgeCall('status');
      assert.equal(blocked.status, 409);
      assert.equal((await blocked.json()).error.code, 'BRIDGE_AUTHORITY_UNAVAILABLE');
      const signing = createRuntimeAuthority();
      try {
        await assert.rejects(new PhoneMcpBridge(client, signing).bind(), /BRIDGE_AUTHORITY_UNAVAILABLE/u);
      } finally { signing.dispose(); }
    }

    admitRegistration.resolve();
    await settingsStarted.promise;
    assert.equal(client.status().deviceId, identity.deviceId);
    assert.equal(client.status().serverUrl, serverUrl);
    const current = await store.load(serverUrl, identity.deviceId);
    assert.equal(current.account, nextAccount);
    assert.equal(current.accessToken, tokens('replacement').accessToken);
    finishList.resolve();
    const response = await listing;
    assert.equal(response.status, 409);
    assert.equal((await response.json()).error.code, 'BRIDGE_AUTHORITY_CHANGED');
    finishSettings.resolve();
    assert.equal((await (await switching).json()).result.ok, false);
    assert.notEqual((await bridgeCall('status')).status, 200);
  } finally {
    admitRegistration.resolve();
    finishList.resolve();
    finishSettings.resolve();
  }
}
