import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { z } from 'zod';

export const BRIDGE_PREFIX = '/oai-android-mcp/v1';
const id = z.string().min(1).max(256);
const requestId = z.string().regex(/^[A-Za-z0-9_-]{16,128}$/u);
const target = z.object({ hostId: id, backend: z.literal('harness'), workspaceId: z.literal('phone-capabilities') }).strict();
const binding = target.extend({ taskSessionId: id });
const envelope = z.object({ request_id: requestId, op: z.string(), args: z.record(z.string(), z.unknown()) }).strict();
const inputs = {
  status: z.object({}).strict(),
  list_devices: z.object({}).strict(),
  capabilities: target,
  start: target.extend({ taskSessionId: id.optional(), request: envelope }),
  request_status: binding.extend({ targetRequestId: requestId, request_id: requestId }),
  cancel: binding.extend({ targetRequestId: requestId, request_id: requestId }),
  read_artifact: binding.extend({ artifactId: z.string().regex(/^[A-Za-z0-9_-]+$/u) }),
  request_result: binding.extend({ targetRequestId: requestId }),
  delivery: target.extend({ taskSessionId: id.optional(), resume: z.boolean().optional(), request_id: requestId, deliveryId: z.string().regex(/^delivery_[0-9a-f]{32}$/u), eventId: z.string().regex(/^push_[0-9a-f]{64}$/u), payloadDigest: z.string().regex(/^sha256:[0-9a-f]{64}$/u), acknowledge: z.boolean() }),
};
const operations = new Set(['ui.dump_tree', 'ui.capture_som', 'ui.tap', 'ui.long_press', 'ui.swipe', 'ui.input_text',
  'ui.global_action', 'ui.scroll', 'ui.drag', 'ui.launch_app', 'plugin.invoke']);
const diagnosticOperations = new Set(['status', 'list_devices', 'capabilities', 'request_status', 'request_result', 'read_artifact']);
const loopbackAddresses = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1']);
const loopbackHosts = new Set(['127.0.0.1', '::1', '[::1]']);

function liveConnectedTarget(status) {
  if (!status || status.connected === false) return undefined;
  return typeof status.connectedTargetDeviceId === 'string' && status.connectedTargetDeviceId.length > 0
    ? status.connectedTargetDeviceId
    : undefined;
}

function connectionSnapshot(runtime) {
  const status = runtime.status?.();
  if (!status) return undefined;
  return {
    connected: status.connected,
    connectedTargetDeviceId: liveConnectedTarget(status),
    recoveryGeneration: status.recoveryGeneration,
  };
}

function sameConnection(before, after) {
  if (!before || !after) return before === after;
  return before.connected === after.connected
    && before.connectedTargetDeviceId === after.connectedTargetDeviceId
    && before.recoveryGeneration === after.recoveryGeneration;
}

/** 仅调用固定上游公开 runSessionTool，始终显式传手机 target，不选择或切换桌面工作区。 */
export class PhoneMcpBridge {
  #closed = false;
  #ledger = new Map();
  constructor(runtime, authority) { this.runtime = runtime; this.authority = authority; this.epoch = undefined; }
  async #current(signal, fence) {
    if (this.#closed) throw new Error('BRIDGE_AUTHORITY_CHANGED');
    const runtime = typeof this.runtime === 'function' ? this.runtime() : this.runtime;
    if (!runtime) throw new Error('BRIDGE_RUNTIME_UNAVAILABLE');
    const epoch = await this.authority.snapshot(runtime, signal);
    if (this.epoch === undefined) this.epoch = epoch;
    else if (epoch !== this.epoch) throw new Error('BRIDGE_AUTHORITY_CHANGED');
    // 单次调用内锁住连接目标与恢复代次，断开、换目标或重连时立即中止；不永久锁死后续新连接上的回执查询。
    if (fence !== undefined) {
      const current = connectionSnapshot(runtime);
      if (!sameConnection(fence, current)) throw new Error('BRIDGE_CONNECTION_CHANGED');
    }
    return runtime;
  }
  async #tool(operation, args, signal, fence) {
    const runtime = await this.#current(signal, fence);
    const result = await runtime.runSessionTool(operation, args, signal);
    await this.#current(signal, fence);
    return result;
  }
  async #target(args, signal, requireConnected = false, fence = undefined) {
    const runtime = await this.#current(signal, fence);
    const connected = liveConnectedTarget(runtime.status?.());
    if (requireConnected && connected !== args.hostId) {
      const error = new Error('PHONE_TARGET_NOT_CONNECTED'); error.category = 'target_unavailable'; throw error;
    }
    if (connected !== undefined && connected !== args.hostId) {
      const error = new Error('PHONE_TARGET_CONNECTION_MISMATCH'); error.category = 'target_unavailable'; throw error;
    }
    const discovery = await runtime.discoverToolWorkspaces(args.hostId, signal);
    await this.#current(signal, fence);
    const rows = discovery.workspaces ?? discovery.items ?? [];
    if (!rows.some(row => row.workspaceId === 'phone-capabilities' && (row.backend === undefined || row.backend === 'harness'))) {
      const unavailable = Array.isArray(discovery.unavailable) && discovery.unavailable.some(row => row.backend === 'harness');
      const error = new Error(unavailable ? 'PHONE_WORKSPACE_UNAVAILABLE' : 'PHONE_WORKSPACE_MISSING');
      error.category = unavailable ? 'target_unavailable' : 'target_missing';
      throw error;
    }
  }
  async call(operation, input, signal = new AbortController().signal) {
    if (!(operation in inputs)) throw new Error('BRIDGE_OPERATION_UNSUPPORTED');
    const args = inputs[operation].parse(input);
    const runtime = await this.#current(signal);
    const fence = connectionSnapshot(runtime);
    if (operation === 'status') return { available: true, protocol: 1, phoneOnly: true };
    if (operation === 'list_devices') {
      const devices = await runtime.devices(signal); await this.#current(signal, fence);
      const items = [];
      for (const device of devices) {
        if (device.online === false) {
          items.push({ hostId: device.deviceId, name: device.name, backend: 'harness', state: 'offline' });
          continue;
        }
        const connected = liveConnectedTarget(runtime.status?.());
        if (connected !== undefined && connected !== device.deviceId) {
          // 已有控制连接时不探测另一个 Host，避免抢掉现有连接；保留脱敏的未检查类别。
          items.push({ hostId: device.deviceId, name: device.name, backend: 'harness', state: 'not_probed' });
          continue;
        }
        try { await this.#target({ hostId: device.deviceId }, signal, false, fence); items.push({ hostId: device.deviceId, name: device.name, backend: 'harness', workspaceId: 'phone-capabilities' }); }
        catch (error) {
          if (signal.aborted) throw error;
          await this.#current(signal, fence);
          if (error?.category === 'target_missing') items.push({ hostId: device.deviceId, name: device.name, backend: 'harness', state: 'missing' });
          else items.push({ hostId: device.deviceId, name: device.name, backend: 'harness', state: 'unavailable' });
          // 健康候选不受其他 Host 的 unavailable/missing 影响；只返回脱敏类别。
        }
      }
      return { devices: items };
    }
    const requiresConnected = operation === 'start' || operation === 'cancel' || (operation === 'delivery' && args.resume !== true);
    await this.#target(args, signal, requiresConnected, fence);
    const fixed = { hostId: args.hostId, backend: 'harness', workspaceId: 'phone-capabilities' };
    if (operation === 'delivery') {
      const request = { request_id: args.request_id, op: args.acknowledge ? 'push.ack_delivery' : 'push.read_delivery', args: { deliveryId: args.deliveryId, eventId: args.eventId, payloadDigest: args.payloadDigest } };
      // 任务ID只确定归属；新request_id的ACK可以复用读取任务。恢复必须显式只查询。
      if (args.resume && !args.taskSessionId) throw new Error('TASK_SESSION_REQUIRED');
      return this.#dispatch(fixed, args.taskSessionId, request, signal, args.resume === true, fence);
    }
    if (operation === 'read_artifact') return this.#tool('remote_download_file', { ...fixed, sessionId: args.taskSessionId, path: `artifacts/${args.artifactId}` }, signal, fence);
    if (operation === 'capabilities') return this.#send(fixed, 'control-bus', { request_id: randomUUID(), op: 'phone.capabilities', args: {} }, signal, fence);
    if (operation === 'request_result') return this.#readResult(fixed, args.taskSessionId, args.targetRequestId, signal, fence);
    if (operation === 'request_status') {
      const request = { request_id: args.request_id, op: 'request.status', args: { targetRequestId: args.targetRequestId, targetTaskSessionId: args.taskSessionId } };
      const receipt = await this.#send(fixed, 'control-bus', request, signal, fence);
      if (receipt?.state === 'completed') {
        const nested = receipt.result;
        if (!nested || typeof nested !== 'object' || nested.request_id !== args.targetRequestId || nested.taskSessionId !== args.taskSessionId) {
          throw Object.assign(new Error('BRIDGE_RECEIPT_IDENTITY_MISMATCH'), { taskSessionId: args.taskSessionId, state: 'outcome_unknown' });
        }
      }
      return receipt;
    }
    if (operation === 'cancel') {
      const request = { request_id: args.request_id, op: 'request.cancel', args: { targetRequestId: args.targetRequestId } };
      return this.#send(fixed, args.taskSessionId, request, signal, fence);
    }
    if (!operations.has(args.request.op)) throw new Error('CONTROL_OPERATION_UNSUPPORTED');
    if (!args.taskSessionId && !['ui.dump_tree', 'ui.capture_som'].includes(args.request.op)) throw new Error('TASK_SESSION_REQUIRED');
    return this.#dispatch(fixed, args.taskSessionId, args.request, signal, false, fence);
  }
  async #dispatch(fixed, taskSessionId, request, signal, resume = false, fence = undefined) {
    const fingerprint = createHash('sha256').update(JSON.stringify([fixed, request])).digest('hex');
    const key = request.request_id;
    const old = this.#ledger.get(key);
    if (old) {
      if (old.fingerprint !== fingerprint || (taskSessionId !== undefined && old.taskSessionId !== taskSessionId)) throw new Error('REQUEST_ID_CONFLICT');
      if (old.pending) return old.pending;
      if (!old.taskSessionId) throw new Error('PHONE_TASK_CREATE_UNCERTAIN');
      return this.#observe(fixed, old, key, signal, fence);
    }
    // 仅当前进程保存关联；重启使用返回的taskSessionId和既有状态/结果查询契约。
    const entry = { fingerprint, taskSessionId, pending: undefined };
    this.#ledger.set(key, entry);
    entry.pending = (async () => {
      if (resume) return this.#readResult(fixed, entry.taskSessionId, key, signal, fence);
      if (!entry.taskSessionId) {
        const created = await this.#tool('remote_create_session', { ...fixed, operationId: randomUUID() }, signal, fence);
        if (created.status !== 'accepted' || !created.sessionId) throw new Error('PHONE_TASK_CREATE_UNCERTAIN');
        entry.taskSessionId = created.sessionId;
      }
      try { return await this.#send(fixed, entry.taskSessionId, request, signal, fence); }
      catch (error) {
        // 连接代次变化或等待中断后保留原请求ID的未知含义；只允许原request_id查询，不自动换连接或重投。
        throw Object.assign(error, { taskSessionId: entry.taskSessionId, state: 'outcome_unknown' });
      }
    })();
    try { return await entry.pending; } finally { entry.pending = undefined; }
  }
  async #observe(fixed, entry, request_id, signal, fence) {
    entry.pending = this.#readResult(fixed, entry.taskSessionId, request_id, signal, fence);
    try { return await entry.pending; } finally { entry.pending = undefined; }
  }
  async #send(fixed, taskSessionId, request, signal, fence) {
    const receipt = await this.#tool('remote_send_message', { ...fixed, sessionId: taskSessionId,
      operationId: request.request_id, message: JSON.stringify(request) }, signal, fence);
    if (receipt.status !== 'accepted') return { ...fixed, taskSessionId, request_id: request.request_id, state: 'outcome_unknown', receipt };
    await this.#tool('remote_wait_sessions', { targets: [{ ...fixed, sessionId: taskSessionId }], timeoutMs: 30000 }, signal, fence);
    return this.#readResult(fixed, taskSessionId, request.request_id, signal, fence);
  }
  async #resolveResultArtifact(fixed, taskSessionId, request_id, summary, signal, fence) {
    const ref = summary.resultArtifact;
    if (!ref || typeof ref !== 'object') return { ...fixed, taskSessionId, ...summary };
    const path = ref.path;
    const expectedBytes = ref.bytes;
    const expectedVersion = ref.version;
    if (typeof path !== 'string' || !/^artifacts\/[A-Za-z0-9_-]+$/u.test(path)
      || (ref.artifactId !== undefined && path !== `artifacts/${ref.artifactId}`)
      || !Number.isSafeInteger(expectedBytes) || expectedBytes < 0
      || typeof expectedVersion !== 'string' || !/^sha256:[0-9a-f]{64}$/u.test(expectedVersion)) {
      throw Object.assign(new Error('BRIDGE_ARTIFACT_INTEGRITY_FAILED'), { taskSessionId, state: 'outcome_unknown' });
    }
    const downloaded = await this.#tool('remote_download_file', { ...fixed, sessionId: taskSessionId, path }, signal, fence);
    if (downloaded?.status !== 'downloaded' || downloaded.bytes !== expectedBytes || typeof downloaded.localPath !== 'string' || !downloaded.localPath) {
      throw Object.assign(new Error('BRIDGE_ARTIFACT_INTEGRITY_FAILED'), { taskSessionId, state: 'outcome_unknown' });
    }
    let rawBytes;
    try { rawBytes = await readFile(downloaded.localPath); }
    catch { throw Object.assign(new Error('BRIDGE_ARTIFACT_INTEGRITY_FAILED'), { taskSessionId, state: 'outcome_unknown' }); }
    if (rawBytes.length !== expectedBytes) {
      throw Object.assign(new Error('BRIDGE_ARTIFACT_INTEGRITY_FAILED'), { taskSessionId, state: 'outcome_unknown' });
    }
    const actualVersion = `sha256:${createHash('sha256').update(rawBytes).digest('hex')}`;
    if (actualVersion !== expectedVersion) {
      throw Object.assign(new Error('BRIDGE_ARTIFACT_INTEGRITY_FAILED'), { taskSessionId, state: 'outcome_unknown' });
    }
    let full;
    try { full = JSON.parse(rawBytes.toString('utf8')); }
    catch { throw Object.assign(new Error('BRIDGE_ARTIFACT_INTEGRITY_FAILED'), { taskSessionId, state: 'outcome_unknown' }); }
    if (!full || typeof full !== 'object' || Array.isArray(full)
      || full.request_id !== request_id
      || (full.taskSessionId !== undefined && full.taskSessionId !== taskSessionId)) {
      throw Object.assign(new Error('BRIDGE_RECEIPT_IDENTITY_MISMATCH'), { taskSessionId, state: 'outcome_unknown' });
    }
    return { ...fixed, taskSessionId, ...full, resultArtifact: ref };
  }
  async #readResult(fixed, taskSessionId, request_id, signal, fence) {
    let cursor;
    let history;
    let initialStatus;
    let sawTruncated = false;
    do {
      history = await this.#tool('remote_read_session', { ...fixed, sessionId: taskSessionId, ...(cursor ? { cursor } : {}) }, signal, fence);
      if (initialStatus === undefined) initialStatus = history.status;
      for (const record of [...(history.records ?? [])].reverse()) {
        if (record.truncated) { sawTruncated = true; continue; }
        let event; try { event = JSON.parse(record.json).event; } catch { continue; }
        if (event?.type !== 'assistant/message') continue;
        const content = event.data?.message?.content;
        const text = content?.find(block => block.type === 'text')?.text;
        let result; try { result = JSON.parse(text); } catch { continue; }
        if (result?.request_id !== request_id) continue;
        if (result.taskSessionId !== undefined && result.taskSessionId !== taskSessionId) {
          throw Object.assign(new Error('BRIDGE_RECEIPT_IDENTITY_MISMATCH'), { taskSessionId, state: 'outcome_unknown' });
        }
        return this.#resolveResultArtifact(fixed, taskSessionId, request_id, result, signal, fence);
      }
      cursor = history.nextCursor;
    } while (cursor);
    const status = initialStatus ?? history.status;
    const settled = status === 'idle' || status === 'failed';
    // 控制总线自身不再递归查询状态；未见截断且会话未进入终态（running/needs_user_input 等）时只返回原状态，不回退 request.status。
    if (taskSessionId === 'control-bus' || (!sawTruncated && !settled)) {
      return { ...fixed, taskSessionId, request_id, state: status === 'needs_user_input' ? 'awaiting_confirmation' : 'accepted', resultAvailable: false };
    }
    const fallbackId = `receipt-${randomUUID()}`;
    const receipt = await this.#send(fixed, 'control-bus', { request_id: fallbackId, op: 'request.status',
      args: { targetRequestId: request_id, targetTaskSessionId: taskSessionId } }, signal, fence);
    // 仅在 request.status 查询自身已完成时校验内层原回执身份；未完成或传输未知须忠实保留原状态，不误报伪造。
    if (receipt?.state === 'outcome_unknown') {
      return { ...fixed, taskSessionId, request_id, state: 'outcome_unknown', resultAvailable: false, ...(receipt.receipt !== undefined ? { receipt: receipt.receipt } : {}) };
    }
    if (receipt?.state !== 'completed') {
      return { ...fixed, taskSessionId, request_id, state: receipt?.state === 'awaiting_confirmation' ? 'awaiting_confirmation' : 'accepted', resultAvailable: false };
    }
    const nested = receipt.result && typeof receipt.result === 'object' ? receipt.result : undefined;
    if (!nested || nested.request_id !== request_id || nested.taskSessionId !== taskSessionId) {
      throw Object.assign(new Error('BRIDGE_RECEIPT_IDENTITY_MISMATCH'), { taskSessionId, state: 'outcome_unknown' });
    }
    if (nested.resultArtifact) {
      return this.#resolveResultArtifact(fixed, taskSessionId, request_id, nested, signal, fence);
    }
    return { ...fixed, taskSessionId, ...nested, resultAvailable: nested.resultAvailable === true };
  }
  close() { this.#closed = true; }
}

function headerValue(req, name) {
  const value = req.headers[name];
  return typeof value === 'string' ? value : Array.isArray(value) ? value[0] : undefined;
}

function writeJson(res, status, payload) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(JSON.stringify(payload));
}

function bridgeFailure(res, status, error) {
  const code = error instanceof z.ZodError ? 'BRIDGE_INPUT_INVALID' : error instanceof Error && /^[A-Z0-9_]+$/u.test(error.message) ? error.message : 'BRIDGE_CALL_FAILED';
  writeJson(res, status, { ok: false, error: { code, message: '手机工具调用失败，请核对授权与原请求状态。', ...(typeof error?.taskSessionId === 'string' ? { taskSessionId: error.taskSessionId } : {}) } });
}

/** 注册到 DSH 已有 WebServer；该模块不创建 HTTP/WS 监听器。 */
export function registerPhoneMcpRoute({ webServer, connection, bridge, bearer = randomBytes(32).toString('base64url') }) {
  if (typeof connection.requestRejection !== 'function') throw new Error('DSH_LOCAL_REQUEST_VALIDATOR_REQUIRED');
  if (typeof webServer.register !== 'function' || typeof webServer.getOrigin !== 'function') throw new Error('DSH_WEB_SERVER_REQUIRED');
  if (!Number.isInteger(webServer.port) || webServer.port <= 0 || !loopbackHosts.has(webServer.host)) throw new Error('DSH_WEB_SERVER_LOOPBACK_REQUIRED');
  if (typeof bearer !== 'string' || !/^[A-Za-z0-9._~-]{16,256}$/u.test(bearer)) throw new Error('BRIDGE_BEARER_INVALID');
  const origin = webServer.getOrigin();
  if (typeof origin !== 'string' || !origin) throw new Error('DSH_WEB_SERVER_ORIGIN_INVALID');
  let parsed; try { parsed = new URL(origin); } catch { throw new Error('DSH_WEB_SERVER_ORIGIN_INVALID'); }
  const normalizedOriginHost = parsed.hostname === '[::1]' ? '::1' : parsed.hostname;
  if (parsed.protocol !== 'http:' || parsed.protocol !== webServer.protocol || normalizedOriginHost !== webServer.host
    || parsed.port !== String(webServer.port) || parsed.username || parsed.password || parsed.pathname !== '/' || parsed.search || parsed.hash) throw new Error('DSH_WEB_SERVER_ORIGIN_INVALID');
  const token = Buffer.from(bearer, 'utf8');
  let closed = false;
  const dispose = webServer.register({ kind: 'prefix', path: BRIDGE_PREFIX, async handler(req, res) {
    // 本路由服务 OAI 自己的本机 Stdio，而不是浏览器；不能把 DSH 的 Cookie/API Peer
    // 校验结果当作本通道认证。DSH 通用/Remote 路由的 requestRejection 保持不变。
    const host = headerValue(req, 'host');
    const supplied = headerValue(req, 'authorization');
    const webOrigin = headerValue(req, 'origin');
    const fetchSite = headerValue(req, 'sec-fetch-site');
    const local = loopbackAddresses.has(req.socket?.remoteAddress);
    if (!local) { bridgeFailure(res, 403, new Error('BRIDGE_LOOPBACK_REQUIRED')); return; }
    if (host === undefined || host.toLowerCase() !== parsed.host.toLowerCase()) { bridgeFailure(res, 403, new Error('BRIDGE_HOST_REJECTED')); return; }
    if (webOrigin !== undefined || fetchSite !== undefined) { bridgeFailure(res, 403, new Error('BRIDGE_WEB_ORIGIN_REJECTED')); return; }
    if (closed) { bridgeFailure(res, 410, new Error('BRIDGE_CLOSED')); return; }
    if (supplied === undefined || !/^Bearer [A-Za-z0-9._~-]+$/u.test(supplied)) { bridgeFailure(res, 401, new Error('BRIDGE_AUTHORIZATION_REJECTED')); return; }
    const actual = Buffer.from(supplied.slice(7), 'utf8');
    if (actual.length !== token.length || !timingSafeEqual(actual, token)) { bridgeFailure(res, 401, new Error('BRIDGE_AUTHORIZATION_REJECTED')); return; }
    const rawUrl = req.url;
    const operationPrefix = `${BRIDGE_PREFIX}/`;
    const operation = typeof rawUrl === 'string' && rawUrl.startsWith(operationPrefix) ? rawUrl.slice(operationPrefix.length) : undefined;
    if (req.method !== 'POST' || !operation || operation.includes('/') || operation.includes('?') || operation.includes('#') || !Object.hasOwn(inputs, operation)) { bridgeFailure(res, 404, new Error('BRIDGE_ROUTE_NOT_FOUND')); return; }
    if (headerValue(req, 'content-type')?.split(';', 1)[0]?.trim().toLowerCase() !== 'application/json') { bridgeFailure(res, 415, new Error('BRIDGE_CONTENT_TYPE_REQUIRED')); return; }
    const abort = new AbortController(); req.on('close', () => { if (!req.complete) abort.abort(); });
    const parts = []; let size = 0;
    try {
      for await (const part of req) {
        const chunk = Buffer.from(part); size += chunk.length;
        if (size > 2 * 1024 * 1024) { bridgeFailure(res, 413, new Error('BRIDGE_MESSAGE_TOO_LARGE')); abort.abort(); return; }
        parts.push(chunk);
      }
      let input; try { input = JSON.parse(Buffer.concat(parts).toString('utf8')); } catch { bridgeFailure(res, 400, new Error('BRIDGE_BODY_INVALID')); return; }
      const value = await bridge.call(operation, input, abort.signal);
      writeJson(res, 200, { ok: true, value });
    } catch (error) {
      if (abort.signal.aborted && !res.headersSent) return;
      const code = error instanceof z.ZodError ? 'BRIDGE_INPUT_INVALID' : error instanceof Error && /^[A-Z0-9_]+$/u.test(error.message) ? error.message : 'BRIDGE_CALL_FAILED';
      // 诊断保留真实安全 HTTP 状态；固定工具操作维持既有 200 + ok:false 结果映射。
      const status = diagnosticOperations.has(operation)
        ? code === 'BRIDGE_AUTHORIZATION_REJECTED' ? 401 : ['BRIDGE_HOST_REJECTED', 'BRIDGE_WEB_ORIGIN_REJECTED', 'BRIDGE_LOOPBACK_REQUIRED'].includes(code) ? 403
          : ['BRIDGE_RUNTIME_UNAVAILABLE', 'BRIDGE_AUTHORITY_UNAVAILABLE', 'BRIDGE_AUTHORITY_CHANGED'].includes(code) ? 409 : code === 'BRIDGE_CLOSED' ? 410 : 200
        : 200;
      bridgeFailure(res, status, error);
    }
  } });
  return { bearer, origin, close: async () => { closed = true; bridge.close(); token.fill(0); await dispose(); } };
}
