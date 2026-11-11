import { EventEmitter } from "node:events";
import { Readable } from "node:stream";
import { execFileSync } from "node:child_process";
import { realpathSync } from "node:fs";
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import { createSessionHandler } from "../../../integrations/dsh-session-adapter/index.js";
import { projectGenerations, findGeneration } from "../../../integrations/dsh-session-adapter/generation.js";

const command = realpathSync(execFileSync("which", ["dsh"], { encoding: "utf8" }).trim());
const requireHost = createRequire(command);
const { Session, interruptedTurnClosers } = await import(pathToFileURL(requireHost.resolve("@deepseek-ai/dsh-session")).href);
const token = "synthetic-builtin-review-token-123456789";
const input = { id: "native-user", role: "user", source: { kind: "user", rpcId: "stable-client" }, content: [{ type: "text", text: "合成输入" }] };
const running = () => [
  { seq: 0, time: 1000, type: "turn/start", data: { turn: 1 } },
  { seq: 1, time: 1001, type: "user/message", data: input, surfaceOp: "append" },
];
function fixture(events, { lookupRace = false } = {}) {
  const listeners = new Map();
  const emit = (name, ...args) => { for (const listener of listeners.get(name) ?? []) listener(...args); };
  let raceScheduled = false;
  const agent = { status: "running", session: { id: "one", snapshotEvents() {
    const snapshot = [...events];
    if (lookupRace && !raceScheduled) {
      raceScheduled = true;
      queueMicrotask(() => {
        for (const event of [
          { seq: events.length, time: 1002, type: "assistant/message", data: { turn: 1, message: { id: "answer", role: "assistant", content: [{ type: "text", text: "合成回复" }] } }, surfaceOp: "append" },
          { seq: events.length + 1, time: 1003, type: "turn/end", data: { turn: 1, reason: { kind: "completed" } } },
        ]) { events.push(event); emit("session/event", { id: "one" }, event); }
      });
    }
    return snapshot;
  } } };
  let promptCalls = 0;
  const ctx = { get: (name) => ({
    sessionQuery: { listSessions: async () => [{ header: { id: "one", createdAt: 1000 } }], readSession: async () => ({ events: [...events] }), readSurface: async () => ({ events: [...events] }) },
    sessionController: {
      prompt: async () => { promptCalls++; return { accepted: true }; },
      // 使用官方 follow 的开场 baseline 形状，保证既有 attempt 有权威轮次来源。
      async *follow(request, signal) {
        const frames = [];
        let wake;
        const listen = (payload) => { if (payload.agent === agent) { frames.push({ type: "assistant-stream", frame: payload.frame }); wake?.(); } };
        if (!listeners.has("agent/assistant-stream")) listeners.set("agent/assistant-stream", new Set());
        listeners.get("agent/assistant-stream").add(listen);
        const eventListener = (session, event) => { if (session.id === "one") { frames.push({ type: "event", event }); wake?.(); } };
        listeners.get("session/event").add(eventListener);
        const abort = () => wake?.();
        signal.addEventListener("abort", abort);
        try {
          yield { type: "snapshot", assistantStream: { revision: 1, activeAttempt: { attemptId: "already-started", turn: 1, stream: [] } } };
          while (!signal.aborted) {
            if (!frames.length) await new Promise((resolve) => { wake = resolve; });
            while (frames.length && !signal.aborted) yield frames.shift();
          }
        } finally {
          listeners.get("agent/assistant-stream").delete(listen);
          listeners.get("session/event").delete(eventListener);
          signal.removeEventListener("abort", abort);
        }
      },
    },
    agents: { get: () => agent },
  })[name], on(name, listener) {
    if (!listeners.has(name)) listeners.set(name, new Set());
    listeners.get(name).add(listener);
    return () => listeners.get(name).delete(listener);
  } };
  const handler = createSessionHandler(ctx, { observationTimeoutMs: 40, promptTimeoutMs: 30 }, token, { heartbeatIntervalMs: 100 });
  const request = async (action, body) => {
    const req = Object.assign(Readable.from(body ? [Buffer.from(JSON.stringify(body))] : []), {
      method: body ? "POST" : "GET", url: "/oai/session-adapter/v1/sessions/one" + action, headers: { authorization: `Bearer ${token}` },
    });
    const res = Object.assign(new EventEmitter(), {
      frames: [], writeHead(status) { this.status = status; this.headersSent = true; },
      write(frame) { if (frame.trim()) this.frames.push(JSON.parse(frame)); return true; },
      end(value) { if (value) this.body = JSON.parse(value); this.writableEnded = true; },
    });
    await handler(req, res);
    return res;
  };
  return { request, emit, agent, promptCalls: () => promptCalls, listeners };
}

// 使用真实发行包生成崩溃恢复结束事件，验证未知状态不会消失。
const interruptedEvents = running();
interruptedEvents.push(...interruptedTurnClosers(interruptedEvents));
const interrupted = projectGenerations("one", interruptedEvents);
console.log(JSON.stringify({ check: "宿主重启中断投影", terminalReason: interruptedEvents.at(-1).data.reason, specificState: findGeneration(interrupted, { clientMessageId: "stable-client" }).state, current: interrupted.current }));

// 快照已捕获但流尚未绑定轮次时到达的原生完成事件。
const racedEvents = running();
const raced = fixture(racedEvents, { lookupRace: true });
const racedResponse = await raced.request("/messages", { text: "合成输入", clientMessageId: "stable-client" });
console.log(JSON.stringify({ check: "重复附着预查完成窗口", frames: racedResponse.frames, durableState: findGeneration(projectGenerations("one", racedEvents), { clientMessageId: "stable-client" }).state, promptCalls: raced.promptCalls(), remainingListeners: [...raced.listeners.values()].reduce((n, value) => n + value.size, 0) }));

// 已有模型流的 start 在附着前发生，后续有效正文分片应继续可见。
const active = fixture(running());
const task = active.request("/messages", { text: "合成输入", clientMessageId: "stable-client" });
await new Promise((resolve) => setImmediate(resolve));
active.emit("agent/assistant-stream", { agent: active.agent, frame: { type: "chunk", attemptId: "already-started", chunk: { type: "text-delta", text: "后续分片" } } });
active.emit("session/event", { id: "one" }, { type: "assistant/message", data: { turn: 1, message: { id: "answer", role: "assistant", content: [{ type: "text", text: "完整回复" }] } } });
active.emit("session/event", { id: "one" }, { type: "turn/end", data: { turn: 1, reason: { kind: "completed" } } });
console.log(JSON.stringify({ check: "重复附着既有模型流", frames: (await task).frames }));

// inbox 已经消费、用户消息尚未写入时重新附着，后到用户事件不得清掉已恢复的轮次。
const claimedEvents = [
  { seq: 0, time: 1000, type: "agent/inbox/spliced", data: { target: "next-turn", start: 0, inserted: [input] } },
  { seq: 1, time: 1001, type: "turn/start", data: { turn: 1 } },
  { seq: 2, time: 1002, type: "agent/inbox/spliced", data: { target: "next-turn", start: 0, removedCount: 1, inserted: [] } },
];
const claimed = fixture(claimedEvents);
const claimedTask = claimed.request("/messages", { text: "合成输入", clientMessageId: "stable-client" });
await new Promise((resolve) => setImmediate(resolve));
for (const event of [
  { seq: 3, time: 1003, type: "user/message", data: input, surfaceOp: "append" },
  { seq: 4, time: 1004, type: "assistant/message", data: { turn: 1, message: { id: "answer", role: "assistant", content: [{ type: "text", text: "完整回复" }] } }, surfaceOp: "append" },
  { seq: 5, time: 1005, type: "turn/end", data: { turn: 1, reason: { kind: "completed" } } },
]) { claimedEvents.push(event); claimed.emit("session/event", { id: "one" }, event); }
console.log(JSON.stringify({ check: "重复附着已消费未落用户消息", frames: (await claimedTask).frames, durableState: findGeneration(projectGenerations("one", claimedEvents), { clientMessageId: "stable-client" }).state }));

// 真实 Session 确认替换事件有效；历史不应回到已经替换的旧正文。
const replaced = Session.create("one");
replaced.append("turn/start", { turn: 1 });
const old = replaced.append("user/message", input, { surfaceOp: "append" });
replaced.append("user/message", { ...input, id: "edited-user", content: [{ type: "text", text: "已编辑正文" }] }, { surfaceOp: { op: "replace", startSeq: old.seq, endSeq: old.seq }, sourceEventSeqs: [old.seq] });
replaced.append("turn/end", { turn: 1, reason: { kind: "completed" } });
const history = await fixture([...replaced.snapshotEvents()]).request("/history");
console.log(JSON.stringify({ check: "真实Session合法替换后历史", surfaceUserSeqs: replaced.surface.nodes, rawUserIds: history.body.messages.filter((message) => message.role === "user").map((message) => message.id) }));

// 异步原生终态必须由取消操作本身等待并回传。
const cancelEvents = running();
const cancelled = fixture(cancelEvents);
cancelled.agent.cancel = () => setImmediate(() => {
  const event = { seq: cancelEvents.length, time: 1010, type: "turn/end", data: { turn: 1, reason: { kind: "aborted" } } };
  cancelEvents.push(event); cancelled.emit("session/event", { id: "one" }, event);
});
const cancelResponse = await cancelled.request("/cancel", { value: { generationId: "dsh:one:message:native-user" } });
console.log(JSON.stringify({ check: "取消等待同目标异步终态", receipt: cancelResponse.body }));
