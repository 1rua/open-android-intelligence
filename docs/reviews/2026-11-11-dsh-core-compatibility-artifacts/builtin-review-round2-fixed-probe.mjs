import { EventEmitter } from "node:events";
import { Readable } from "node:stream";
import { createSessionHandler } from "../../../integrations/dsh-session-adapter/index.js";

const token = "synthetic-round-two-review-token-123456789";
const input = { id: "native-user", role: "user", source: { kind: "user", rpcId: "stable-client" }, content: [{ type: "text", text: "合成输入" }] };
const generationId = "dsh:one:message:native-user";
async function probe(mode) {
  const events = [{ seq: 0, time: 1000, type: "agent/inbox/spliced", data: { target: "next-turn", start: 0, inserted: [input] } }];
  const listeners = new Map();
  const begin = () => events.push(
    { seq: events.length, time: 1001, type: "turn/start", data: { turn: 1 } },
    { seq: events.length + 1, time: 1002, type: "agent/inbox/spliced", data: { target: "next-turn", start: 0, removedCount: 1, inserted: [] } },
    { seq: events.length + 2, time: 1003, type: "user/message", data: input, surfaceOp: "append" },
  );
  if (mode === "已提交回复进入follow快照") begin();
  const agent = { status: "running", session: { id: "one", snapshotEvents: () => [...events] } };
  const ctx = { get: (name) => ({
    sessionQuery: { listSessions: async () => [{ header: { id: "one", createdAt: 1000 } }], readSession: async () => ({ events: [...events] }) },
    agents: { get: () => agent },
    sessionController: {
      prompt: async () => { throw new Error("此探针不应重新受理"); },
      async *follow(request, signal) {
        if (mode === "排队输入在follow开场前开始") {
          begin();
          yield { type: "snapshot", cursor: events.length - 1, records: [], assistantStream: { revision: 2, activeAttempt: { turn: 1, attemptId: "attempt-one", stream: [{ type: "text-chunks", texts: ["已有前缀"] }] } } };
          yield { type: "assistant-stream", frame: { type: "chunk", attemptId: "attempt-one", chunk: { type: "text-delta", text: "后续分片" } } };
        }
        const reply = { seq: events.length, time: 1004, type: "assistant/message", surfaceOp: "append", data: { turn: 1, message: { id: "native-reply", role: "assistant", content: [{ type: "text", text: "已有前缀后续分片" }] } } };
        events.push(reply);
        if (mode === "已提交回复进入follow快照") yield { type: "snapshot", cursor: reply.seq, records: [{ type: "event", event: reply }], assistantStream: { revision: 3 } };
        else yield { type: "event", event: reply };
        const terminal = { seq: events.length, time: 1005, type: "turn/end", data: { turn: 1, reason: { kind: "completed" } } };
        events.push(terminal);
        yield { type: "event", event: terminal };
      },
    },
  })[name], on(name, listener) {
    if (!listeners.has(name)) listeners.set(name, new Set());
    listeners.get(name).add(listener);
    return () => listeners.get(name).delete(listener);
  } };
  const req = Object.assign(Readable.from([Buffer.from(JSON.stringify({ text: "合成输入", clientMessageId: "stable-client" }))]), {
    method: "POST", url: "/oai/session-adapter/v1/sessions/one/messages", headers: { authorization: `Bearer ${token}` },
  });
  const res = Object.assign(new EventEmitter(), {
    frames: [], writeHead(status) { this.status = status; this.headersSent = true; },
    write(frame) { if (frame.trim()) this.frames.push(JSON.parse(frame)); return true; },
    end() { this.writableEnded = true; },
  });
  await createSessionHandler(ctx, { observationTimeoutMs: 100, promptTimeoutMs: 100 }, token)(req, res);
  console.log(JSON.stringify({ check: mode, generationId, frames: res.frames, remainingListeners: [...listeners.values()].reduce((sum, items) => sum + items.size, 0) }));
}
await probe("已提交回复进入follow快照");
await probe("排队输入在follow开场前开始");
