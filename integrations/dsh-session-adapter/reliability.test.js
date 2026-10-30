import { EventEmitter } from "node:events";
import { createServer } from "node:http";
import { Readable } from "node:stream";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Config, createSessionHandler } from "./index.js";

const token = "test-only-dsh-reliability-token-12345678";
const prefix = "/oai/session-adapter/v1";
const servers = [];
const tick = () => new Promise((resolve) => setImmediate(resolve));
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
async function until(check) {
  const deadline = performance.now() + 500;
  while (!check()) {
    if (performance.now() >= deadline) throw new Error("fixture deadline");
    await tick();
  }
}
afterEach(async () => {
  for (const server of servers.splice(0)) {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
});
function fixture(prompt, config = {}) {
  const listeners = new Map();
  const controller = { prompt: vi.fn(prompt), cancel: vi.fn(async () => ({ accepted: true })) };
  const ctx = {
    get: (name) => ({
      sessionQuery: {
        listSessions: async () => ["one", "two"].map((id) => ({ header: { id, createdAt: 1000 } })),
        readSurface: async () => ({ events: [] }),
      },
      sessionController: controller,
    })[name],
    on: (name, fn) => {
      if (!listeners.has(name)) listeners.set(name, new Set());
      listeners.get(name).add(fn);
      return () => listeners.get(name).delete(fn);
    },
  };
  const emit = (name, ...args) => { for (const fn of listeners.get(name) ?? []) fn(...args); };
  const event = (id, type, data) => emit("session/event", { id }, { type, data });
  const start = (input) => {
    event(input.sessionId, "turn/start", { turn: 7 });
    event(input.sessionId, "user/message", { id: "native-user", role: "user", source: { rpcId: input.requestId }, content: input.content });
    emit("agent/assistant-stream", { agent: { session: { id: input.sessionId } }, frame: { type: "start", turn: 7, attemptId: input.sessionId } });
  };
  const delta = (id, text = "text") => emit("agent/assistant-stream", { agent: { session: { id } }, frame: { type: "chunk", attemptId: id, chunk: { type: "text-delta", text } } });
  const end = (id) => event(id, "turn/end", { turn: 7, reason: { kind: "completed" } });
  const handler = createSessionHandler(ctx, { promptTimeoutMs: 40, observationTimeoutMs: 100, drainTimeoutMs: 40, ...config }, token);
  const count = () => [...listeners.values()].reduce((n, set) => n + set.size, 0);
  return { handler, controller, listeners, count, start, delta, end };
}
async function serve(h) {
  const server = createServer(h.handler);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  servers.push(server);
  return async (id, body = { text: "hello" }, options = {}) => fetch(`http://127.0.0.1:${server.address().port}${prefix}/sessions/${id}/messages`, {
    method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, body: JSON.stringify(body), ...options,
  });
}
class SlowResponse extends EventEmitter {
  constructor() { super(); this.frames = []; this.writableEnded = false; this.destroyed = false; this.headersSent = false; }
  writeHead() { this.headersSent = true; }
  write(frame) { this.frames.push(JSON.parse(frame)); return false; }
  end() { this.writableEnded = true; }
  destroy() { this.destroyed = true; this.emit("close"); }
}
function request(id, body = { text: "hello" }) {
  return Object.assign(Readable.from([Buffer.from(JSON.stringify(body))]), {
    method: "POST", url: `${prefix}/sessions/${id}/messages`, headers: { authorization: `Bearer ${token}` },
  });
}

describe("DSH execution and observer lifetimes", () => {
  it("uses the stable received clientMessageId without advertising recovery or deduplication", async () => {
    const h = fixture(async (input) => { h.start(input); h.end(input.sessionId); return { accepted: true }; });
    const post = await serve(h);
    const response = await post("one", { text: "hello", clientMessageId: "stable-client-1" });
    expect(response.status).toBe(200);
    expect((await response.text())).toContain('"type":"done"');
    expect(h.controller.prompt.mock.calls[0][0].requestId).toBe("stable-client-1");
    const statusReq = Object.assign(Readable.from([]), { method: "GET", url: `${prefix}/status`, headers: { authorization: `Bearer ${token}` } });
    const statusRes = new SlowResponse();
    statusRes.end = (body) => { statusRes.body = JSON.parse(body); };
    await h.handler(statusReq, statusRes);
    expect(statusRes.body.capabilities.generationRecovery).toBe(false);
    expect(statusRes.body.capabilities.idempotentSend).toBe(false);
  });
  it("ends a hung prompt observation on deadline and releases listeners and busy before a late acceptance", async () => {
    const admission = deferred();
    const h = fixture(() => admission.promise);
    const post = await serve(h);
    const response = await post("one");
    const text = await response.text();
    expect(text).toContain('"state":"unknown"');
    expect(text).not.toContain('"type":"done"');
    expect(h.count()).toBe(0);
    h.controller.prompt.mockImplementationOnce(async (input) => { h.start(input); h.end(input.sessionId); return { accepted: true }; });
    expect((await (await post("one")).text())).toContain('"type":"done"');
    admission.resolve({ accepted: true });
    await tick();
    expect(h.count()).toBe(0);
    expect(h.controller.cancel).not.toHaveBeenCalled();
  });
  it("cleans up when the connection closes during admission without aborting an already queued input", async () => {
    const admission = deferred();
    let signal;
    const h = fixture((_, suppliedSignal) => { signal = suppliedSignal; return admission.promise; });
    const res = new SlowResponse();
    const task = h.handler(request("one"), res);
    await until(() => h.controller.prompt.mock.calls.length === 1);
    res.destroy();
    await until(() => h.count() === 0);
    await task;
    expect(signal?.aborted ?? false).toBe(false);
    admission.resolve({ accepted: true });
    await tick();
    expect(res.frames).toEqual([]);
    expect(h.controller.cancel).not.toHaveBeenCalled();
  });
  it("observes a native terminal even when its accepted receipt arrives late", async () => {
    const admission = deferred();
    const h = fixture((input) => { h.start(input); h.end(input.sessionId); return admission.promise; });
    const post = await serve(h);
    const frames = (await (await post("one")).text()).trim().split("\n").map(JSON.parse);
    expect(frames).toEqual([{ type: "done", text: "", userMessageId: "native-user" }]);
    expect(h.count()).toBe(0);
    admission.resolve({ accepted: true });
    await tick();
    expect(h.controller.cancel).not.toHaveBeenCalled();
  });
  it("waits for drain, bounds a 2000 delta burst, and keeps another session responsive", async () => {
    const h = fixture(async (input) => {
      h.start(input);
      if (input.sessionId === "one") h.delta("one", "first");
      else h.end("two");
      return { accepted: true };
    });
    const res = new SlowResponse();
    const task = h.handler(request("one"), res);
    await until(() => res.frames.length > 0);
    for (let i = 0; i < 2000; i++) h.delta("one", String(i));
    expect(res.frames).toHaveLength(1);
    const post = await serve(h);
    expect((await (await post("two")).text())).toContain('"type":"done"');
    await until(() => h.count() === 0);
    await task;
    expect(res.destroyed || res.writableEnded).toBe(true);
    expect(res.listenerCount("drain")).toBe(0);
    expect(res.listenerCount("close")).toBe(0);
    expect(h.controller.cancel).not.toHaveBeenCalled();
  });
  it("resumes writes only after drain and cleans a stalled writer on budget expiry", async () => {
    const h = fixture(async (input) => { h.start(input); h.delta("one", "first"); return { accepted: true }; });
    const res = new SlowResponse();
    const task = h.handler(request("one"), res);
    await until(() => res.frames.length === 1);
    h.delta("one", "second");
    await tick();
    expect(res.frames).toHaveLength(1);
    res.emit("drain");
    await until(() => res.frames.length === 2);
    await task;
    expect(h.count()).toBe(0);
    expect(res.listenerCount("drain")).toBe(0);
    expect(h.controller.cancel).not.toHaveBeenCalled();
  });
  it("cleans a real HTTP disconnect while prompt admission remains unresolved", async () => {
    const admission = deferred();
    const h = fixture(() => admission.promise);
    const post = await serve(h);
    const abort = new AbortController();
    const receiving = post("one", { text: "hello" }, { signal: abort.signal }).then((res) => res.text());
    const outcome = receiving.catch((error) => error.name);
    await until(() => h.controller.prompt.mock.calls.length === 1);
    abort.abort();
    expect(await outcome).toBe("AbortError");
    await until(() => h.count() === 0);
    admission.reject(new Error("private host detail must not be exposed"));
    await tick();
    expect(h.controller.cancel).not.toHaveBeenCalled();
  });
  it("keeps accepted execution running when only the observation deadline expires", async () => {
    const h = fixture(async (input) => { h.start(input); return { accepted: true }; });
    const post = await serve(h);
    const frames = (await (await post("one")).text()).trim().split("\n").map(JSON.parse);
    expect(frames).toEqual([{ type: "error", code: "OUTCOME_UNKNOWN", state: "unknown" }]);
    expect(h.count()).toBe(0);
    expect(h.controller.cancel).not.toHaveBeenCalled();
  });
  it("keeps explicit cancellation on the native controller", async () => {
    const h = fixture(async (input) => { h.start(input); return { accepted: true }; });
    const req = request("one", {});
    req.url = `${prefix}/sessions/one/cancel`;
    const res = new SlowResponse();
    res.end = (body) => { res.result = JSON.parse(body); res.writableEnded = true; };
    await h.handler(req, res);
    expect(h.controller.cancel).toHaveBeenCalledExactlyOnceWith({ sessionId: "one" });
    expect(res.result).toEqual({ id: "one", action: "cancel" });
  });
  it("bounds queued bytes as well as event count", async () => {
    const h = fixture(async (input) => { h.start(input); h.delta("one", "first"); return { accepted: true }; });
    const res = new SlowResponse();
    const task = h.handler(request("one"), res);
    await until(() => res.frames.length === 1);
    h.delta("one", "x".repeat(1024 * 1024));
    await task;
    expect(res.frames).toHaveLength(1);
    expect(res.destroyed).toBe(true);
    expect(h.count()).toBe(0);
    expect(res.listenerCount("drain")).toBe(0);
  });
  it.each(["", "bad id", 12, "x".repeat(129)])("rejects invalid client identity %s before native prompt", async (clientMessageId) => {
    const h = fixture(async () => ({ accepted: true }));
    const post = await serve(h);
    const response = await post("one", { text: "hello", clientMessageId });
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ code: "SCHEMA_INVALID" });
    expect(h.controller.prompt).not.toHaveBeenCalled();
    expect(h.count()).toBe(0);
  });
  it("validates finite observer budgets", () => {
    for (const key of ["promptTimeoutMs", "observationTimeoutMs", "drainTimeoutMs"]) {
      for (const value of [0, -1, Infinity, 1.5]) expect(Config["~standard"].validate({ [key]: value }).issues).toBeDefined();
    }
  });
});

it("correlates real HTTP native callbacks without transcript or credential diagnostics", async () => {
  const old = process.env.OAI_DSH_ADAPTER_DIAGNOSTICS;
  process.env.OAI_DSH_ADAPTER_DIAGNOSTICS = "1";
  const lines = [];
  const write = vi.spyOn(process.stderr, "write").mockImplementation((line) => { lines.push(String(line)); return true; });
  try {
    const h = fixture(async (input) => { h.start(input); h.delta(input.sessionId, "PRIVATE-OUTPUT"); h.end(input.sessionId); return { accepted: true }; });
    const send = await serve(h);
    const response = await send("one", { text: "PRIVATE-PROMPT", clientMessageId: "stable-client" }, {
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json",
        traceparent: `00-${"c".repeat(32)}-${"d".repeat(16)}-00`, "X-OAI-Request-Id": "core-request" },
    });
    expect(response.status).toBe(200);
    await response.text();
    const records = lines.filter((line) => line.startsWith("oai_native ")).map((line) => JSON.parse(line.slice(11)));
    expect(records.map((record) => record.stage)).toEqual([
      "adapter.dsh.message_received", "adapter.dsh.host_submitted", "adapter.dsh.native_user_event",
      "adapter.dsh.first_content_event", "adapter.dsh.native_terminal", "adapter.dsh.observer_closed",
    ]);
    expect(records.every((record) => record.traceId === "c".repeat(32) && record.requestId === "core-request")).toBe(true);
    expect(records[2].userMessageId).toBe("native-user");
    expect(lines.join("")).not.toContain("PRIVATE");
    expect(lines.join("")).not.toContain(token);
  } finally {
    write.mockRestore();
    if (old === undefined) Reflect.deleteProperty(process.env, "OAI_DSH_ADAPTER_DIAGNOSTICS");
    else process.env.OAI_DSH_ADAPTER_DIAGNOSTICS = old;
  }
});
