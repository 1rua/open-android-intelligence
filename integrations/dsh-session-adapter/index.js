/** DSH native session adapter. Android auth and all device work stay in Core. */
import { createServer } from "node:http";
import { randomUUID, timingSafeEqual } from "node:crypto";

export const name = "oai-session-adapter";
export const inject = ["sessionQuery"];
const PREFIX = "/oai/session-adapter/v1";
const MAX_REQUEST_BYTES = 1024 * 1024;
const MAX_OBSERVER_FRAMES = 128;
const MAX_OBSERVER_BYTES = 1024 * 1024;
const observerDefaults = { promptTimeoutMs: 30_000, observationTimeoutMs: 30 * 60 * 1000, drainTimeoutMs: 5000 };
const methods = ["list", "history", "create", "send", "rename", "archive", "delete", "cancel", "stream"];
const fail = (code, status = 409) => Object.assign(new Error(code), { code, status });
const textOf = (message) => (message?.content ?? []).filter((part) => part.type === "text").map((part) => part.text).join("");
// Current DSH logs store a user/message's UserMessage directly in data;
// assistant/message keeps its turn/step wrapper. Older hosts wrapped both.
const messageOf = (event) => event.type === "user/message" ? (event.data?.message ?? event.data) : event.data?.message;
const iso = (time) => new Date(time).toISOString();
const turnResult = (reason, text, identity = {}) => {
  if (reason === "completed") return { type: "done", text, ...identity };
  const codes = { error: "AGENT_FAILED", aborted: "CANCELLED", blocked: "AGENT_BLOCKED", "max-tokens": "AGENT_MAX_TOKENS", interrupted: "OUTCOME_UNKNOWN" };
  const code = codes[reason] ?? "HOST_INCOMPATIBLE";
  return { type: "error", code, ...(["OUTCOME_UNKNOWN", "HOST_INCOMPATIBLE"].includes(code) ? { state: "unknown" } : {}) };
};

// The pending host promise retains only this detachable slot after an observer
// leaves, rather than retaining its response, transcript buffer and listeners.
function observeAdmission(promise, observer) {
  promise.then((receipt) => observer.accept?.(receipt), () => observer.fail?.());
}

export const Config = {
  "~standard": {
    version: 1,
    vendor: "oai-session-adapter",
    validate(input = {}) {
      const value = {
        port: input.port ?? 8791, tokenEnv: input.tokenEnv ?? "OAI_DSH_ADAPTER_TOKEN", cwd: input.cwd, agentPreset: input.agentPreset,
        ...Object.fromEntries(Object.entries(observerDefaults).map(([key, fallback]) => [key, input[key] ?? fallback])),
      };
      if (!Number.isInteger(value.port) || value.port < 1 || value.port > 65535
          || typeof value.tokenEnv !== "string" || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(value.tokenEnv)
          || (value.cwd !== undefined && typeof value.cwd !== "string")
          || (value.agentPreset !== undefined && typeof value.agentPreset !== "string")
          || Object.keys(observerDefaults).some((key) => !Number.isSafeInteger(value[key]) || value[key] <= 0 || value[key] > 2_147_483_647)) {
        return { issues: [{ message: "Invalid OAI session adapter configuration" }] };
      }
      return { value };
    },
  },
};

async function bodyOf(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req.iterator({ destroyOnReturn: false })) {
    size += chunk.length;
    if (size >= MAX_REQUEST_BYTES) {
      // Keep the socket available long enough to deliver the 413. Discard
      // remaining bytes without retaining another request-sized buffer.
      req.resume();
      throw fail("PAYLOAD_TOO_LARGE", 413);
    }
    chunks.push(chunk);
  }
  try {
    const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error();
    return body;
  } catch { throw fail("SCHEMA_INVALID", 400); }
}

/** The injected query's corpus is the explicitly installed DSH instance only. */
export function createSessionHandler(ctx, config, token) {
  if (typeof token !== "string" || token.length < 32) throw fail("ADAPTER_TOKEN_REQUIRED", 500);
  const expected = Buffer.from(`Bearer ${token}`);
  const validated = Config["~standard"].validate(config);
  if (validated.issues) throw fail("SCHEMA_INVALID", 500);
  const budgets = validated.value;
  const busy = new Set();
  const services = () => ({ query: ctx.get("sessionQuery"), controller: ctx.get("sessionController"), registry: ctx.get("workspaceRegistry") });
  const capabilities = () => {
    const { query, controller, registry } = services();
    const value = Object.fromEntries(methods.map((method) => [method, false]));
    // A stable native rpcId correlates input; it does not prove that the host
    // deduplicates prompts or persists queryable execution receipts.
    value.generationRecovery = false;
    value.idempotentSend = false;
    value.list = typeof query?.listSessions === "function";
    value.history = typeof query?.readSurface === "function";
    value.create = value.list && typeof controller?.create === "function";
    value.send = value.list && typeof controller?.prompt === "function" && typeof ctx.on === "function";
    value.stream = value.send;
    value.rename = value.list && typeof controller?.rename === "function";
    value.cancel = value.list && typeof controller?.cancel === "function";
    // The verified public query has no archive-state projection. Do not
    // advertise management whose result cannot be read back truthfully.
    return value;
  };
  const records = async () => {
    if (!capabilities().list) throw fail("HOST_INCOMPATIBLE", 503);
    return await services().query.listSessions();
  };
  const requireSession = async (id) => {
    const record = (await records()).find((item) => item.header?.id === id);
    if (!record) throw fail("SESSION_NOT_FOUND", 404);
    return record;
  };
  const summary = async (record) => {
    const { query } = services();
    const title = typeof query.readTitle === "function" ? await query.readTitle(record.header.id) : undefined;
    const surface = typeof query.readSurface === "function" ? await query.readSurface(record.header.id) : undefined;
    const updatedAt = Math.max(record.header.createdAt, title?.updatedAt ?? 0, ...(surface?.events ?? []).map((event) => event.time));
    return { id: record.header.id, title: title?.title ?? record.header.id, updatedAt: iso(updatedAt), archived: false };
  };
  const json = (res, value, status = 200) => {
    res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
    res.end(JSON.stringify(value));
  };

  async function stream(req, res, id, body) {
    if (!capabilities().send) throw fail("UNSUPPORTED_CAPABILITY");
    if (typeof body.text !== "string" || !body.text.trim()
        || (body.clientMessageId !== undefined && (typeof body.clientMessageId !== "string"
          || !/^[A-Za-z0-9._~-]{1,128}$/.test(body.clientMessageId)))) throw fail("SCHEMA_INVALID", 400);
    if (busy.has(id)) throw fail("SESSION_BUSY");
    busy.add(id);
    const requestId = body.clientMessageId ?? randomUUID();
    const disposers = [];
    const queue = [];
    const ownAttempts = new Set();
    const admission = {};
    const identity = {};
    let currentTurn, ownTurn, finalText = "", ending = false, stopped = false, admitted = false;
    let queuedBytes = 0, wake, stopDrain, admissionTimer, observationTimer;
    const notify = () => { const resolve = wake; wake = undefined; resolve?.(); };
    const stop = () => {
      stopped = true;
      queue.length = 0;
      queuedBytes = 0;
      notify();
      stopDrain?.();
    };
    const overflow = () => {
      // Only the observation socket ends. The host inbox and turn are untouched.
      stop();
      res.destroy();
    };
    const enqueue = (value) => {
      if (stopped) return;
      const frame = JSON.stringify(value) + "\n";
      const size = Buffer.byteLength(frame);
      if (queue.length >= MAX_OBSERVER_FRAMES || queuedBytes + size > MAX_OBSERVER_BYTES) return overflow();
      queue.push({ frame, size });
      queuedBytes += size;
      notify();
    };
    const finish = (value) => {
      if (ending || stopped) return;
      ending = true;
      clearTimeout(admissionTimer);
      clearTimeout(observationTimer);
      enqueue(value);
    };
    // rpcId belongs to the native user message. Other terminal turns in this
    // shared session cannot prematurely complete this App submission.
    disposers.push(ctx.on("session/event", (session, event) => {
      if (session?.id !== id || ending || stopped) return;
      if (event.type === "turn/start") currentTurn = event.data.turn;
      if (event.type === "user/message" && messageOf(event)?.source?.rpcId === requestId) {
        ownTurn = currentTurn;
        admitted = true;
        // Native persistence is admission evidence even if prompt() is delayed.
        clearTimeout(admissionTimer);
        const message = messageOf(event);
        if (typeof message.id === "string" && message.id) identity.userMessageId = message.id;
      }
      if (ownTurn === undefined || (event.data.turn !== undefined && event.data.turn !== ownTurn)) return;
      if (event.type === "assistant/message") {
        const message = messageOf(event);
        finalText = textOf(message);
        if (typeof message?.id === "string" && message.id) identity.messageId = message.id;
        else delete identity.messageId;
      }
      if (event.type === "turn/end") finish(turnResult(event.data.reason?.kind, finalText, identity));
    }));
    disposers.push(ctx.on("agent/assistant-stream", (payload) => {
      const frame = payload?.frame;
      if (ending || stopped || payload?.agent?.session?.id !== id || ownTurn === undefined) return;
      if (frame?.type === "start" && frame.turn === ownTurn) {
        if (ownAttempts.size >= MAX_OBSERVER_FRAMES && !ownAttempts.has(frame.attemptId)) return overflow();
        ownAttempts.add(frame.attemptId);
      }
      if (frame?.type === "chunk" && ownAttempts.has(frame.attemptId)
          && frame.chunk?.type === "text-delta") enqueue({ type: "delta", text: frame.chunk.text });
      if (frame?.type === "end") ownAttempts.delete(frame.attemptId);
    }));
    const waitForDrain = () => new Promise((resolve) => {
      const done = () => {
        clearTimeout(timer);
        res.off("drain", done);
        stopDrain = undefined;
        resolve();
      };
      const timer = setTimeout(overflow, budgets.drainTimeoutMs);
      stopDrain = done;
      res.once("drain", done);
      if (stopped || res.destroyed) done();
    });
    res.on("close", stop);
    res.on("error", overflow);
    const unknown = () => finish({ type: "error", code: "OUTCOME_UNKNOWN", state: "unknown" });
    admissionTimer = setTimeout(unknown, budgets.promptTimeoutMs);
    observationTimer = setTimeout(unknown, budgets.observationTimeoutMs);
    try {
      res.writeHead(200, { "content-type": "application/x-ndjson", "cache-control": "no-store" });
      // Do not pass a socket-owned AbortSignal to an inbox mutation. Both
      // resolution paths are consumed, including after this observer departs.
      admission.accept = (receipt) => {
        if (stopped || ending) return;
        clearTimeout(admissionTimer);
        if (receipt?.accepted !== true && !admitted) finish({ type: "error", code: "AGENT_REJECTED" });
      };
      admission.fail = () => {
        // Transport errors cannot establish whether the input reached inbox.
        if (!admitted) unknown();
      };
      observeAdmission(Promise.resolve().then(() => services().controller.prompt({
        requestId, sessionId: id, mode: "queue", content: [{ type: "text", text: body.text }],
      })), admission);
      while (!stopped) {
        if (!queue.length) {
          if (ending) break;
          await new Promise((resolve) => { wake = resolve; });
          continue;
        }
        const { frame, size } = queue.shift();
        queuedBytes -= size;
        if (!res.write(frame)) await waitForDrain();
      }
      if (!res.destroyed && !res.writableEnded) res.end();
    } finally {
      stop();
      clearTimeout(admissionTimer);
      clearTimeout(observationTimer);
      res.off("close", stop);
      res.off("error", overflow);
      admission.accept = admission.fail = undefined;
      for (const dispose of disposers) dispose();
      ownAttempts.clear();
      busy.delete(id);
    }
  }

  return async (req, res) => {
    try {
      const supplied = Buffer.from(req.headers.authorization ?? "");
      if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) throw fail("AUTHENTICATION_REQUIRED", 401);
      const url = new URL(req.url, "http://localhost");
      if (url.search) throw fail("SCHEMA_INVALID", 400);
      const path = url.pathname;
      if (req.method === "GET" && path === PREFIX + "/status") {
        const caps = capabilities();
        // Call the actual query. An installed plugin with a broken persistence
        // backend must never report online merely because functions exist.
        await records();
        return json(res, { online: caps.list && caps.history, capabilities: caps });
      }
      if (path === PREFIX + "/sessions") {
        if (req.method === "GET") return json(res, { sessions: await Promise.all((await records()).map(summary)) });
        if (req.method === "POST") {
          if (!capabilities().create) throw fail("UNSUPPORTED_CAPABILITY");
          const body = await bodyOf(req);
          if (Object.keys(body).some((key) => key !== "title")) throw fail("SCHEMA_INVALID", 400);
          if (body.title != null && (typeof body.title !== "string" || !body.title.trim())) throw fail("SCHEMA_INVALID", 400);
          if (body.title && !capabilities().rename) throw fail("UNSUPPORTED_CAPABILITY");
          const created = await services().controller.create({ sessionId: randomUUID(), cwd: config.cwd, agentPreset: config.agentPreset });
          if (body.title) await services().controller.rename({ sessionId: created.sessionId, title: body.title });
          return json(res, { session: await summary(await requireSession(created.sessionId)) });
        }
      }
      const match = new RegExp(`^${PREFIX}/sessions/([^/]+)/(history|messages|rename|archive|delete|cancel)$`).exec(path);
      if (!match) throw fail("NOT_FOUND", 404);
      let id;
      try { id = decodeURIComponent(match[1]); } catch { throw fail("SCHEMA_INVALID", 400); }
      const action = match[2];
      await requireSession(id);
      if (req.method === "GET" && action === "history") {
        if (!capabilities().history) throw fail("UNSUPPORTED_CAPABILITY");
        const snapshot = await services().query.readSurface(id);
        const messages = snapshot.events.filter((event) => ["user/message", "assistant/message"].includes(event.type))
          .map((event) => {
            const message = messageOf(event);
            if (typeof message?.id !== "string" || !["user", "assistant"].includes(message.role) || !Array.isArray(message.content)) throw fail("HOST_INCOMPATIBLE", 502);
            return { id: message.id, role: message.role, text: textOf(message), createdAt: iso(event.time) };
          });
        return json(res, { messages });
      }
      if (req.method !== "POST") throw fail("NOT_FOUND", 404);
      const body = await bodyOf(req);
      if (action === "messages") {
        if (Object.keys(body).some((key) => !["text", "clientMessageId"].includes(key))) throw fail("SCHEMA_INVALID", 400);
        return await stream(req, res, id, body);
      }
      if (Object.keys(body).some((key) => key !== "value")) throw fail("SCHEMA_INVALID", 400);
      if (!capabilities()[action]) throw fail("UNSUPPORTED_CAPABILITY");
      const { controller, registry } = services();
      if (action === "rename") {
        if (typeof body.value !== "string" || !body.value.trim()) throw fail("SCHEMA_INVALID", 400);
        await controller.rename({ sessionId: id, title: body.value });
      } else if (action === "archive") {
        if (typeof body.value !== "boolean") throw fail("SCHEMA_INVALID", 400);
        await registry[body.value ? "archiveSession" : "unarchiveSession"](id);
      } else if (action === "cancel") {
        const result = await controller.cancel({ sessionId: id });
        if (result?.accepted !== true) throw fail("CANCEL_REJECTED");
      }
      return json(res, { id, action, ...(action === "archive" ? { archived: body.value } : {}) });
    } catch (error) {
      if (!res.headersSent) json(res, { code: error.code ?? "AGENT_UNAVAILABLE" }, error.status ?? 503);
      else if (!res.writableEnded) res.end();
    }
  };
}

export function apply(ctx, config) {
  const token = process.env[config.tokenEnv];
  const handler = createSessionHandler(ctx, config, token);
  ctx.effect(() => {
    const server = createServer(handler);
    server.listen(config.port, "127.0.0.1");
    server.on("error", (error) => ctx.logger?.error?.("OAI session adapter listener failed: %s", error.code));
    return () => new Promise((resolve) => { server.closeAllConnections(); server.close(resolve); });
  }, "oai-native-session-adapter");
}
