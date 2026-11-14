import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
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
};
const operations = new Set(['ui.dump_tree', 'ui.capture_som', 'ui.tap', 'ui.long_press', 'ui.swipe', 'ui.input_text',
  'ui.global_action', 'ui.scroll', 'ui.drag', 'ui.launch_app', 'plugin.invoke']);

/** 仅调用固定上游公开 runSessionTool，始终显式传手机 target，不选择或切换桌面工作区。 */
export class PhoneMcpBridge {
  #closed = false;
  #ledger = new Map();
  constructor(runtime, authority) { this.runtime = runtime; this.authority = authority; this.epoch = authority.snapshot(); }
  #current() { if (this.#closed || this.authority.snapshot() !== this.epoch) throw new Error('BRIDGE_AUTHORITY_CHANGED'); }
  async #tool(operation, args, signal) {
    this.#current(); const result = await this.runtime.runSessionTool(operation, args, signal); this.#current(); return result;
  }
  async #target(args, signal) {
    this.#current();
    const discovery = await this.runtime.discoverToolWorkspaces(args.hostId, signal);
    this.#current();
    const rows = discovery.workspaces ?? discovery.items ?? [];
    if (!rows.some(row => row.workspaceId === 'phone-capabilities' && (row.backend === undefined || row.backend === 'harness'))) {
      throw new Error('PHONE_WORKSPACE_UNAVAILABLE');
    }
  }
  async call(operation, input, signal = new AbortController().signal) {
    if (!(operation in inputs)) throw new Error('BRIDGE_OPERATION_UNSUPPORTED');
    const args = inputs[operation].parse(input); this.#current();
    if (operation === 'status') return { available: true, protocol: 1, phoneOnly: true };
    if (operation === 'list_devices') {
      const devices = await this.runtime.devices(signal); this.#current();
      const items = [];
      for (const device of devices) {
        try { await this.#target({ hostId: device.deviceId }, signal); items.push({ hostId: device.deviceId, name: device.name, backend: 'harness', workspaceId: 'phone-capabilities' }); }
        catch (error) { this.#current(); if (signal.aborted) throw error; }
      }
      return { devices: items };
    }
    await this.#target(args, signal);
    const fixed = { hostId: args.hostId, backend: 'harness', workspaceId: 'phone-capabilities' };
    if (operation === 'read_artifact') return this.#tool('remote_download_file', { ...fixed, sessionId: args.taskSessionId, path: `artifacts/${args.artifactId}` }, signal);
    if (operation === 'capabilities') return this.#send(fixed, 'control-bus', { request_id: randomUUID(), op: 'phone.capabilities', args: {} }, signal);
    if (operation === 'request_result') return this.#readResult(fixed, args.taskSessionId, args.targetRequestId, signal);
    if (operation === 'request_status' || operation === 'cancel') {
      const taskSessionId = operation === 'request_status' ? 'control-bus' : args.taskSessionId;
      const request = { request_id: args.request_id, op: operation === 'cancel' ? 'request.cancel' : 'request.status',
        args: { targetRequestId: args.targetRequestId, ...(operation === 'request_status' ? { targetTaskSessionId: args.taskSessionId } : {}) } };
      return this.#send(fixed, taskSessionId, request, signal);
    }
    if (!operations.has(args.request.op)) throw new Error('CONTROL_OPERATION_UNSUPPORTED');
    if (!args.taskSessionId && !['ui.dump_tree', 'ui.capture_som'].includes(args.request.op)) throw new Error('TASK_SESSION_REQUIRED');
    const fingerprint = createHash('sha256').update(JSON.stringify([fixed, args.taskSessionId ?? null, args.request])).digest('hex');
    const key = args.request.request_id;
    const old = this.#ledger.get(key);
    if (old) { if (old.fingerprint !== fingerprint) throw new Error('REQUEST_ID_CONFLICT'); return old.pending; }
    // 仅当前进程保存受理关联；手机持久账本才是执行与防重权威。
    const entry = { fingerprint, taskSessionId: args.taskSessionId, pending: undefined };
    entry.pending = (async () => {
      if (!entry.taskSessionId) {
        const created = await this.#tool('remote_create_session', { ...fixed, operationId: randomUUID() }, signal);
        if (created.status !== 'accepted' || !created.sessionId) throw new Error('PHONE_TASK_CREATE_UNCERTAIN');
        entry.taskSessionId = created.sessionId;
      }
      return this.#send(fixed, entry.taskSessionId, args.request, signal);
    })();
    this.#ledger.set(key, entry); return entry.pending;
  }
  async #send(fixed, taskSessionId, request, signal) {
    const receipt = await this.#tool('remote_send_message', { ...fixed, sessionId: taskSessionId,
      operationId: request.request_id, message: JSON.stringify(request) }, signal);
    if (receipt.status !== 'accepted') return { ...fixed, taskSessionId, request_id: request.request_id, state: 'outcome_unknown', receipt };
    await this.#tool('remote_wait_sessions', { targets: [{ ...fixed, sessionId: taskSessionId }], timeoutMs: 30000 }, signal);
    return this.#readResult(fixed, taskSessionId, request.request_id, signal);
  }
  async #readResult(fixed, taskSessionId, request_id, signal) {
    let cursor;
    let history;
    do {
      history = await this.#tool('remote_read_session', { ...fixed, sessionId: taskSessionId, ...(cursor ? { cursor } : {}) }, signal);
      for (const record of [...(history.records ?? [])].reverse()) {
      if (record.truncated) continue;
      let event; try { event = JSON.parse(record.json).event; } catch { continue; }
      if (event?.type !== 'assistant/message') continue;
      const content = event.data?.message?.content;
      const text = content?.find(block => block.type === 'text')?.text;
      let result; try { result = JSON.parse(text); } catch { continue; }
        if (result.request_id === request_id) return { ...fixed, taskSessionId, ...result };
      }
      cursor = history.nextCursor;
    } while (cursor);
    return { ...fixed, taskSessionId, request_id, state: history.status === 'needs_user_input' ? 'awaiting_confirmation' : 'accepted', resultAvailable: false };
  }
  close() { this.#closed = true; }
}

/** 注册到 DSH 已有 WebServer；该模块不创建 HTTP/WS 监听器。 */
export function registerPhoneMcpRoute({ webServer, connection, bridge, bearer = randomBytes(32).toString('base64url') }) {
  if (typeof connection.requestRejection !== 'function') throw new Error('DSH_LOCAL_REQUEST_VALIDATOR_REQUIRED');
  const token = Buffer.from(bearer);
  const dispose = webServer.register({ kind: 'prefix', path: BRIDGE_PREFIX, async handler(req, res) {
    const reject = connection.requestRejection(req);
    const supplied = Buffer.from(typeof req.headers.authorization === 'string' ? req.headers.authorization.replace(/^Bearer /u, '') : '');
    const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress);
    if (reject !== undefined || !local || req.headers.origin !== undefined || supplied.length !== token.length || !timingSafeEqual(supplied, token)) {
      res.writeHead(reject ?? 403); res.end(); return;
    }
    const operation = req.url?.slice(BRIDGE_PREFIX.length + 1);
    if (req.method !== 'POST' || !(operation in inputs) || req.headers['content-type']?.split(';')[0] !== 'application/json') { res.writeHead(404); res.end(); return; }
    try {
      const abort = new AbortController(); req.on('close', () => { if (!req.complete) abort.abort(); });
      const parts = []; let size = 0;
      for await (const part of req) {
        const chunk = Buffer.from(part); size += chunk.length;
        // 固定上游 Remote 最大安全消息为 2 MiB，本机桥不会扩张该边界。
        if (size > 2 * 1024 * 1024) throw new Error('BRIDGE_MESSAGE_TOO_LARGE');
        parts.push(chunk);
      }
      const value = await bridge.call(operation, JSON.parse(Buffer.concat(parts).toString('utf8')), abort.signal);
      res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ ok: true, value }));
    } catch (error) {
      const code = error instanceof z.ZodError ? 'BRIDGE_INPUT_INVALID' : error instanceof Error && /^[A-Z0-9_]+$/u.test(error.message) ? error.message : 'BRIDGE_CALL_FAILED';
      res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify({ ok: false, error: { code, message: '手机工具调用失败，请核对授权与原请求状态。' } }));
    }
  } });
  return { bearer, close: async () => { bridge.close(); token.fill(0); await dispose(); } };
}
