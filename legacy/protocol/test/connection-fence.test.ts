import { describe, expect, it } from "vitest";
import {
  DeterministicConnectionFenceStore,
  allocateConnectionGeneration,
  fenceConnection,
} from "../src/connection-fence.js";

describe("connection generation fencing", () => {
  it("accepts only the current inspected and header generation", () => {
    expect(fenceConnection({ kind: "current", generation: 8n, fenceRevision: 11n }, 8n, 8n)).toEqual({ ok: true });
    expect(fenceConnection({ kind: "current", generation: 8n, fenceRevision: 11n }, 8n, 7n)).toEqual({ ok: false, error: "CONNECTION_FENCED" });
    expect(fenceConnection({ kind: "current", generation: 8n, fenceRevision: 11n }, 8n, 9n)).toEqual({ ok: false, error: "CONNECTION_FENCED" });
    expect(fenceConnection({ kind: "fenced" }, 8n, 8n)).toEqual({ ok: false, error: "CONNECTION_FENCED" });
  });

  it("serializes reconnects, fences the loser and continues from the durable restart snapshot", async () => {
    const key = { credentialId: "cred", pairingGeneration: 4n };
    const store = new DeterministicConnectionFenceStore(key, {
      generation: 7n, fenceRevision: 7n, connectionId: "old", transportProfileId: "tailnet",
    });
    const [first, second] = await Promise.all([
      store.allocateNext(key, "connection-8", "tailnet"),
      store.allocateNext(key, "connection-9", "public"),
    ]);
    expect(first).toMatchObject({ kind: "allocated", allocation: { generation: 8n, fenceRevision: 8n } });
    expect(second).toMatchObject({ kind: "allocated", allocation: { generation: 9n, fenceRevision: 9n } });
    if (first.kind !== "allocated" || second.kind !== "allocated") throw new Error("expected allocations");
    await expect(store.inspect(first.allocation.lease)).resolves.toEqual({ kind: "fenced" });
    await expect(store.inspect(second.allocation.lease)).resolves.toEqual({ kind: "current", generation: 9n, fenceRevision: 9n });
    const restarted = new DeterministicConnectionFenceStore(key, store.snapshot());
    await expect(restarted.allocateNext(key, "connection-10", "tailnet"))
      .resolves.toMatchObject({ kind: "allocated", allocation: { generation: 10n, fenceRevision: 10n } });
  });

  it("maps durable allocation exhaustion without inventing or wrapping a generation", async () => {
    const store = new DeterministicConnectionFenceStore({ credentialId: "cred", pairingGeneration: 4n }, {
      generation: 18_446_744_073_709_551_615n,
      fenceRevision: 18_446_744_073_709_551_615n,
      connectionId: "connection", transportProfileId: "tailnet",
    });
    await expect(allocateConnectionGeneration(store, { credentialId: "cred", pairingGeneration: 4n }, "connection", "tailnet"))
      .resolves.toEqual({ ok: false, error: "CONNECTION_FENCED" });
  });
});
