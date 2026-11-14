import { mkdir, writeFile, rename, chmod } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { PhoneMcpBridge, registerPhoneMcpRoute } from './src/bridge.mjs';

export const name = 'oai-android-mcp';
export const inject = ['dshRemoteClient', 'connection', 'webServer'];

/** Cordis 安装入口；所有服务均由现有 DSH 注入，插件不创建监听器或读取 Remote 密钥。 */
export function apply(ctx, config) {
  if (!config || typeof config.privateConfigPath !== 'string' || !config.privateConfigPath) throw new Error('PRIVATE_MCP_CONFIG_PATH_REQUIRED');
  const runtime = ctx.get('dshRemoteClient');
  const authority = { snapshot: () => {
    const status = runtime.status();
    if (!status.identityReady || !status.available || status.clientAuthRequired) throw new Error('BRIDGE_AUTHORITY_UNAVAILABLE');
    // DSH 注销会撤销当前 runtime；插件在其生命周期之外不保留授权。
    if (status.host && (!status.host.authorized || status.host.accountRequired)) throw new Error('BRIDGE_AUTHORITY_UNAVAILABLE');
    return JSON.stringify([status.serverUrl, status.deviceId, status.host?.deviceId, status.host?.account]);
  } };
  const bridge = new PhoneMcpBridge(runtime, authority);
  const registration = registerPhoneMcpRoute({ webServer: ctx.get('webServer'), connection: ctx.get('connection'), bridge });
  const port = ctx.get('webServer').port;
  if (!Number.isInteger(port) || port <= 0) throw new Error('DSH_WEB_SERVER_PORT_UNAVAILABLE');
  ctx.effect(async () => {
    const directory = dirname(config.privateConfigPath);
    await mkdir(directory, { recursive: true, mode: 0o700 }); await chmod(directory, 0o700);
    const temporary = join(directory, `.oai-mcp-${process.pid}.tmp`);
    await writeFile(temporary, JSON.stringify({ mcpServers: { 'oai-android': { command: process.execPath,
      args: [new URL('./src/stdio.mjs', import.meta.url).pathname], env: {
        OAI_ANDROID_BRIDGE_URL: `http://127.0.0.1:${port}`, OAI_ANDROID_BRIDGE_BEARER: registration.bearer,
      } } } }, null, 2), { mode: 0o600, flag: 'wx' });
    await rename(temporary, config.privateConfigPath); await chmod(config.privateConfigPath, 0o600);
    return async () => { await registration.close(); };
  });
}
