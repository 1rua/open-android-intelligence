import { randomUUID } from 'node:crypto';
import { mkdir, writeFile, rename, chmod } from 'node:fs/promises';
import { dirname, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PhoneMcpBridge, registerPhoneMcpRoute } from './src/bridge.mjs';

export const name = 'oai-android-mcp';
export const inject = ['dshRemoteClient', 'connection', 'webServer'];

const HOST_AUTHORIZATION_ERRORS = new Set([
  'ACCOUNT_AUTH_REQUIRED', 'AUTH_REQUIRED', 'AUTH_INVALID', 'TOKEN_EXPIRED', 'DEVICE_REVOKED', 'DEVICE_OWNERSHIP_REQUIRED',
]);

function abortError(signal) {
  return signal?.reason instanceof Error ? signal.reason : Object.assign(new Error('BRIDGE_REQUEST_ABORTED'), { name: 'AbortError' });
}

function authoritySnapshot(runtime) {
  if (!runtime || typeof runtime.status !== 'function') throw new Error('BRIDGE_RUNTIME_UNAVAILABLE');
  let status;
  try { status = runtime.status(); } catch { throw new Error('BRIDGE_AUTHORITY_UNAVAILABLE'); }
  if (!status || status.identityReady !== true || status.available !== true || status.clientAuthRequired === true) throw new Error('BRIDGE_AUTHORITY_UNAVAILABLE');
  if (typeof status.serverUrl !== 'string' || !status.serverUrl || typeof status.deviceId !== 'string' || !status.deviceId) throw new Error('BRIDGE_AUTHORITY_UNAVAILABLE');
  // 与上游 ds-harness-remote@v0.6.5 client-runtime.ts:375 对齐：仅在配对受限或 Host 终态鉴权错误时阻断，不因本机 Host 暂态网络错误误杀健康 Client 桥。
  if (status.host && (status.host.pairingHostOnly === true || status.host.accountRequired === true || HOST_AUTHORIZATION_ERRORS.has(status.host.error))) {
    throw new Error('BRIDGE_AUTHORITY_UNAVAILABLE');
  }
  const hostDeviceId = typeof status.host?.deviceId === 'string' && status.host.deviceId.length > 0 ? status.host.deviceId : null;
  const hostAccount = typeof status.host?.account === 'string' && status.host.account.length > 0 ? status.host.account : null;
  return {
    core: JSON.stringify([status.serverUrl, status.deviceId]),
    hostDeviceId,
    hostAccount,
  };
}

const runtimeAuthObservers = new WeakMap();

/** 固定 DSH 0.6.5 的控制入口动态调用这些方法。每个 runtime 只挂一组包装。 */
function observeRuntimeAuthTransitions(runtime, revoke) {
  let entry = runtimeAuthObservers.get(runtime);
  if (!entry) {
    const listeners = new Set();
    const restoreMethods = [];
    entry = { pending: 0, listeners, detach };
    function detach() {
      if (entry.pending > 0 || listeners.size > 0) return;
      for (const restore of restoreMethods) restore();
      runtimeAuthObservers.delete(runtime);
    }
    function invalidate() {
      for (const listener of [...listeners]) listener();
    }
    try {
      for (const key of ['clearClientAuthorization', 'authorizeClientWithAccount', 'pollClientOAuthQrLogin']) {
        const original = runtime[key];
        if (typeof original !== 'function') throw new Error('DSH_AUTHORIZATION_CONTROL_REQUIRED');
        const own = Object.getOwnPropertyDescriptor(runtime, key);
        const wrapped = async function (...args) {
          entry.pending++;
          try {
            // 注销一进入就吊销，登录期间暂停签发和调用，成功后再吊销上一代。
            if (key === 'clearClientAuthorization') invalidate();
            const result = await original.apply(this, args);
            if (key === 'authorizeClientWithAccount' || (key === 'pollClientOAuthQrLogin' && result?.status === 'complete')) invalidate();
            return result;
          } finally {
            entry.pending--;
            detach();
          }
        };
        Object.defineProperty(runtime, key, { value: wrapped, configurable: true, writable: true, enumerable: own?.enumerable ?? false });
        restoreMethods.push(() => {
          if (runtime[key] !== wrapped) return;
          if (own) Object.defineProperty(runtime, key, own);
          else delete runtime[key];
        });
      }
    } catch {
      detach();
      throw new Error('DSH_AUTHORIZATION_CONTROL_REQUIRED');
    }
    runtimeAuthObservers.set(runtime, entry);
  }
  entry.listeners.add(revoke);
  return {
    get transitioning() { return entry.pending > 0; },
    dispose() { if (entry.listeners.delete(revoke)) entry.detach(); },
  };
}

/** 授权绑定当前注入的 runtime 与公开身份字段，不保存 token 或 Cookie。 */
export function createRuntimeAuthority(onRevoke) {
  let observation;
  let observedRuntime;
  let bound;
  let boundHostDeviceId = null;
  let boundHostAccount = null;
  let revoked = false;
  const dispose = () => {
    observation?.dispose();
    observation = undefined;
  };
  const revoke = () => {
    if (revoked) return;
    revoked = true;
    dispose();
    onRevoke?.();
  };
  function checkTransition(signal) {
    if (signal?.aborted) throw abortError(signal);
    if (revoked) throw new Error('BRIDGE_AUTHORITY_CHANGED');
    if (observation?.transitioning) throw new Error('BRIDGE_AUTHORITY_UNAVAILABLE');
  }
  function fail(error) {
    if (bound !== undefined) { revoke(); throw new Error('BRIDGE_AUTHORITY_CHANGED'); }
    throw error;
  }
  return {
    revoke,
    dispose,
    async snapshot(runtime, signal) {
      checkTransition(signal);
      if (observedRuntime !== undefined && runtime !== observedRuntime) {
        revoke();
        throw new Error('BRIDGE_AUTHORITY_CHANGED');
      }
      if (!runtime) throw new Error('BRIDGE_RUNTIME_UNAVAILABLE');
      if (typeof runtime.handleControl !== 'function') fail(new Error('DSH_AUTHORIZATION_CONTROL_REQUIRED'));
      // 先观察登录变化，再读取异步凭据状态，避免把上一代的迟到结果用于签发。
      if (!observation) {
        observation = observeRuntimeAuthTransitions(runtime, revoke);
        observedRuntime = runtime;
        checkTransition(signal);
      }
      let before;
      try { before = authoritySnapshot(runtime); }
      catch (error) { fail(error); }
      let result;
      try { result = await runtime.handleControl('client.authorization.status', {}, signal); }
      catch {
        checkTransition(signal);
        fail(new Error('BRIDGE_AUTHORITY_UNAVAILABLE'));
      }
      checkTransition(signal);
      if (result?.ok !== true || result.value?.hasCredentials !== true) fail(new Error('BRIDGE_AUTHORITY_UNAVAILABLE'));
      let after;
      try { after = authoritySnapshot(runtime); }
      catch (error) { fail(error); }
      if (after.core !== before.core || (bound !== undefined && after.core !== bound)
        || (before.hostDeviceId !== null && after.hostDeviceId !== null && after.hostDeviceId !== before.hostDeviceId)
        || (before.hostAccount !== null && after.hostAccount !== null && after.hostAccount !== before.hostAccount)) {
        revoke();
        throw new Error('BRIDGE_AUTHORITY_CHANGED');
      }
      const hostDeviceId = after.hostDeviceId ?? before.hostDeviceId;
      const hostAccount = after.hostAccount ?? before.hostAccount;
      // 允许 Host 信息懒加载，但已知身份不能切换。
      if ((hostDeviceId !== null && boundHostDeviceId !== null && hostDeviceId !== boundHostDeviceId)
        || (hostAccount !== null && boundHostAccount !== null && hostAccount !== boundHostAccount)) {
        revoke();
        throw new Error('BRIDGE_AUTHORITY_CHANGED');
      }
      boundHostDeviceId = hostDeviceId ?? boundHostDeviceId;
      boundHostAccount = hostAccount ?? boundHostAccount;
      bound = after.core;
      return bound;
    },
  };
}

/** Cordis 安装入口；所有服务均由现有 DSH 注入，插件不创建监听器或读取 Remote 密钥。 */
export function apply(ctx, config) {
  if (!config || typeof config.privateConfigPath !== 'string' || !config.privateConfigPath) throw new Error('PRIVATE_MCP_CONFIG_PATH_REQUIRED');
  if (!isAbsolute(config.privateConfigPath)) throw new Error('PRIVATE_MCP_CONFIG_PATH_ABSOLUTE_REQUIRED');
  const webServer = ctx.get('webServer');
  const connection = ctx.get('connection');
  if (!webServer || !connection) throw new Error('DSH_WEB_SERVICES_REQUIRED');
  if (!Number.isInteger(webServer.port) || webServer.port < 1 || webServer.port > 65535) throw new Error('DSH_WEB_SERVER_PORT_UNAVAILABLE');
  const lifecycle = ctx.effect(async () => {
    let registration;
    const authority = createRuntimeAuthority(() => {
      if (registration) void registration.revoke().catch(() => {});
    });
    const bridge = new PhoneMcpBridge(() => ctx.get('dshRemoteClient'), authority);
    try {
      await bridge.bind();
      registration = registerPhoneMcpRoute({ webServer, connection, bridge });
      const directory = dirname(config.privateConfigPath);
      await mkdir(directory, { recursive: true, mode: 0o700 });
      await chmod(directory, 0o700);
      const temporary = join(directory, `.oai-mcp-${process.pid}-${randomUUID()}.tmp`);
      const bridgeScript = fileURLToPath(new URL('./src/stdio.mjs', import.meta.url));
      await writeFile(temporary, JSON.stringify({ mcpServers: { 'oai-android': { command: process.execPath,
        args: [bridgeScript], env: {
          OAI_ANDROID_BRIDGE_URL: registration.origin, OAI_ANDROID_BRIDGE_BEARER: registration.bearer,
        } } } }, null, 2), { mode: 0o600, flag: 'wx' });
      await chmod(temporary, 0o600);
      // 发布前后复核同一登录代次。注销回调也会同步关闭路由，使任何迟到文件中的凭据失效。
      await bridge.bind();
      await rename(temporary, config.privateConfigPath);
      await bridge.bind();
    } catch (error) {
      authority.dispose();
      if (registration) await registration.close();
      throw error;
    }
    return async () => {
      authority.dispose();
      await registration.close();
    };
  });
  return lifecycle;
}
