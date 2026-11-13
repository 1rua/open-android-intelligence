import { createServer } from "node:http";
import { createSessionHandler } from "./index.js";

// 仅供跨语言契约回归使用的合成宿主，不读取模型配置或用户会话。
const token = "test-only-dsh-cross-language-token-123456789";
const listeners = new Map();
const events = [];
let running = false;
const emit = (type, data) => {
  const event = { seq: events.length, time: 1000 + events.length, type, data };
  events.push(event);
  for (const listener of listeners.get("session/event") ?? []) listener({ id: "native" }, event);
};
const agent = {
  get status() { return running ? "running" : "idle"; },
  session: { snapshotEvents: () => events },
  cancel() { emit("turn/end", { turn: 1, reason: { kind: "aborted" } }); running = false; },
};
const services = {
  sessionQuery: { listSessions: async () => [{ header: { id: "native", createdAt: 1000 } }], readSession: async () => ({ events }), readSurface: async () => ({ events: [] }) },
  agents: { get: () => agent },
  sessionController: {
    prompt: async (request, signal) => {
      signal.throwIfAborted();
      const message = { id: "native-user", role: "user", source: { kind: "user", rpcId: request.requestId }, content: request.content };
      emit("agent/inbox/spliced", { target: "next-turn", start: 0, inserted: [message] });
      running = true;
      emit("turn/start", { turn: 1 });
      emit("agent/inbox/spliced", { target: "next-turn", start: 0, removedCount: 1, inserted: [] });
      emit("user/message", message);
      emit("assistant/message", { turn: 1, message: { id: "native-answer", role: "assistant", content: [{ type: "text", text: "合成回复" }] } });
      emit("turn/end", { turn: 1, reason: { kind: "completed" } });
      running = false;
      return { accepted: true };
    },
  },
};
const handler = createSessionHandler({
  get: (name) => services[name],
  on(name, listener) {
    if (!listeners.has(name)) listeners.set(name, new Set());
    listeners.get(name).add(listener);
    return () => listeners.get(name).delete(listener);
  },
}, {}, token);
const server = createServer(handler);
server.listen(0, "127.0.0.1", () => process.stdout.write(JSON.stringify({ origin: `http://127.0.0.1:${server.address().port}`, token }) + "\n"));
process.on("SIGTERM", () => { server.closeAllConnections(); server.close(); });
