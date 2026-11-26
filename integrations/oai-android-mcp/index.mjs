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

/** 授权代次由当前注入的 runtime 对象和公开身份字段共同确定；不保存 token 或 Cookie。 */
export function createRuntimeAuthority() {
  const generations = new WeakMap();
  let nextGeneration = 1;
  let bound;
  let boundHostDeviceId = null;
  let boundHostAccount = null;
  let revoked = false;
  return {
    async snapshot(runtime, signal) {
      if (signal?.aborted) throw abortError(signal);
      if (revoked) throw new Error('BRIDGE_AUTHORITY_CHANGED');
      if (!runtime) {
        if (bound !== undefined) { revoked = true; throw new Error('BRIDGE_AUTHORITY_CHANGED'); }
        throw new Error('BRIDGE_RUNTIME_UNAVAILABLE');
      }
      if (typeof runtime.handleControl !== 'function') throw new Error('DSH_AUTHORIZATION_CONTROL_REQUIRED');
      let before;
      try { before = authoritySnapshot(runtime); }
      catch (error) {
        if (bound !== undefined) { revoked = true; throw new Error('BRIDGE_AUTHORITY_CHANGED'); }
        throw error;
      }
      let result;
      try { result = await runtime.handleControl('client.authorization.status', {}, signal); }
      catch (error) {
        if (signal?.aborted) throw error;
        if (bound !== undefined) { revoked = true; throw new Error('BRIDGE_AUTHORITY_CHANGED'); }
        throw new Error('BRIDGE_AUTHORITY_UNAVAILABLE');
      }
      // 单次请求取消或超时绝不污染全局桥的 revoked 闩锁。
      if (signal?.aborted) throw abortError(signal);
      if (result?.ok !== true || result.value?.hasCredentials !== true) {
        if (bound !== undefined) { revoked = true; throw new Error('BRIDGE_AUTHORITY_CHANGED'); }
        throw new Error('BRIDGE_AUTHORITY_UNAVAILABLE');
      }
      let after;
      try { after = authoritySnapshot(runtime); }
      catch (error) {
        if (bound !== undefined) { revoked = true; throw new Error('BRIDGE_AUTHORITY_CHANGED'); }
        throw error;
      }
      if (after.core !== before.core
        || (before.hostDeviceId !== null && after.hostDeviceId !== null && after.hostDeviceId !== before.hostDeviceId)
        || (before.hostAccount !== null && after.hostAccount !== null && after.hostAccount !== before.hostAccount)) {
        if (bound !== undefined) revoked = true;
        throw new Error('BRIDGE_AUTHORITY_CHANGED');
      }
      const hostDeviceId = after.hostDeviceId ?? before.hostDeviceId;
      const hostAccount = after.hostAccount ?? before.hostAccount;
      // 仅拦截已观测到非空 Host 身份/账号发生变更；允许 null -> account 懒加载或开关本机被控而不误杀 Client 桥。
      if (hostDeviceId !== null) {
        if (boundHostDeviceId !== null && hostDeviceId !== boundHostDeviceId) {
          revoked = true; throw new Error('BRIDGE_AUTHORITY_CHANGED');
        }
        boundHostDeviceId = hostDeviceId;
      }
      if (hostAccount !== null) {
        if (boundHostAccount !== null && hostAccount !== boundHostAccount) {
          revoked = true; throw new Error('BRIDGE_AUTHORITY_CHANGED');
        }
        boundHostAccount = hostAccount;
      }
      let generation = generations.get(runtime);
      if (generation === undefined) { generation = nextGeneration++; generations.set(runtime, generation); }
      const epoch = JSON.stringify([generation, after.core]);
      if (bound === undefined) bound = epoch;
      else if (epoch !== bound) { revoked = true; throw new Error('BRIDGE_AUTHORITY_CHANGED'); }
      return epoch;
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
  if (!Number.isInteger(webServer.port) || webServer.port <= 0) throw new Error('DSH_WEB_SERVER_PORT_UNAVAILABLE');
  // runtime 可能在 apply 时尚未就绪；getter 每次从当前注入服务读取，桥在首次成功调用时绑定身份。
  const bridge = new PhoneMcpBridge(() => ctx.get('dshRemoteClient'), createRuntimeAuthority());
  const registration = registerPhoneMcpRoute({ webServer, connection, bridge });
  const lifecycle = ctx.effect(async () => {
    try {
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
      await rename(temporary, config.privateConfigPath);
      await chmod(config.privateConfigPath, 0o600);
    } catch (error) {
      await registration.close();
      throw error;
    }
    return async () => { await registration.close(); };
  });
  return lifecycle;
}
