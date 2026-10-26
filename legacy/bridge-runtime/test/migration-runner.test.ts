import { describe, expect, it } from "vitest";
import { MigrationRunner, type MigrationStep } from "../src/migration-runner.js";
import type { DurableBridgeTransaction } from "../../bridge-contract/src/durable-store.js";
import {
  NODE_SQLITE_BRIDGE_DRIVER,
  SQLITE_BRIDGE_ADAPTER_PORT,
  type SqliteBridgeAdapterPort,
} from "../../bridge-contract/src/persistence.js";

const tx = (): DurableBridgeTransaction => ({
  transactionId: "migration:1",
  read: async () => null,
  scan: async () => [],
  write: async () => undefined,
  remove: async () => undefined,
});

const fakeAdapter = (initial = 0): SqliteBridgeAdapterPort & { applied: string[]; version: number } => {
  const state = {
    version: initial,
    applied: [] as string[],
  };
  return {
    port: SQLITE_BRIDGE_ADAPTER_PORT,
    backend: "sqlite",
    driver: NODE_SQLITE_BRIDGE_DRIVER,
    status: "external-driver-required",
    databasePath: ":memory:",
    transact: async (_scope, work) => work(tx()),
    schemaVersion: async () => state.version,
    runMigration: async (_scope, from, to, step) => {
      if (state.version !== from) throw new Error("SCHEMA_VERSION_RACE");
      await step(tx());
      state.version = to;
      state.applied.push(`${from}->${to}`);
    },
    backup: async () => ({ artifact: "backup", path: ":memory:", schemaVersion: state.version }),
    restore: async () => ({ restored: true, schemaVersion: state.version }),
    recover: async () => ({ recovered: true, schemaVersion: state.version, repaired: false, discardedArtifacts: [] }),
    get applied() { return state.applied; },
    get version() { return state.version; },
  };
};

describe("versioned Bridge migration runner", () => {
  it("applies a contiguous migration chain atomically in order", async () => {
    const adapter = fakeAdapter();
    const calls: string[] = [];
    const migrations: MigrationStep[] = [
      { id: "bridge-0001", from: 0, to: 1, apply: async () => { calls.push("0001"); } },
      { id: "bridge-0002", from: 1, to: 2, apply: async () => { calls.push("0002"); } },
    ];
    await expect(new MigrationRunner(adapter, migrations).run()).resolves.toEqual({ from: 0, to: 2, applied: ["bridge-0001", "bridge-0002"] });
    expect(calls).toEqual(["0001", "0002"]);
    expect(adapter.applied).toEqual(["0->1", "1->2"]);
  });

  it("fails closed on a gap, duplicate target, or downgrade", async () => {
    const adapter = fakeAdapter();
    await expect(new MigrationRunner(adapter, [{ id: "bad", from: 1, to: 2, apply: async () => undefined }]).run()).rejects.toMatchObject({ code: "MIGRATION_CHAIN_INVALID" });
    expect(() => new MigrationRunner(adapter, [
      { id: "a", from: 0, to: 1, apply: async () => undefined },
      { id: "b", from: 0, to: 1, apply: async () => undefined },
    ])).toThrowError(/MIGRATION_CHAIN_INVALID/);
    expect(() => new MigrationRunner(fakeAdapter(2), [{ id: "down", from: 2, to: 1, apply: async () => undefined }])).toThrowError(/MIGRATION_CHAIN_INVALID/);
  });

  it("does not continue after an adapter reports an atomic migration failure", async () => {
    const adapter = fakeAdapter();
    let attempts = 0;
    adapter.runMigration = async () => {
      attempts += 1;
      throw new Error("driver unavailable");
    };
    await expect(new MigrationRunner(adapter, [{ id: "bridge-0001", from: 0, to: 1, apply: async () => undefined }]).run())
      .rejects.toThrow("driver unavailable");
    expect(attempts).toBe(1);
    expect(adapter.version).toBe(0);
  });

  it("preflights gaps before committing an earlier step", async () => {
    const adapter = fakeAdapter();
    await expect(new MigrationRunner(adapter, [
      { id: "bridge-0001", from: 0, to: 1, apply: async () => undefined },
      { id: "bridge-0003", from: 2, to: 3, apply: async () => undefined },
    ]).run()).rejects.toMatchObject({ code: "MIGRATION_CHAIN_INVALID" });
    expect(adapter.applied).toEqual([]);
    expect(adapter.version).toBe(0);
  });

  it("reopens from the committed version without replaying migrations", async () => {
    const adapter = fakeAdapter();
    const migrations: MigrationStep[] = [
      { id: "bridge-0001", from: 0, to: 1, apply: async () => undefined },
      { id: "bridge-0002", from: 1, to: 2, apply: async () => undefined },
    ];
    await expect(new MigrationRunner(adapter, migrations).run())
      .resolves.toEqual({ from: 0, to: 2, applied: ["bridge-0001", "bridge-0002"] });
    await expect(new MigrationRunner(adapter, migrations).run())
      .resolves.toEqual({ from: 2, to: 2, applied: [] });
    expect(adapter.applied).toEqual(["0->1", "1->2"]);
  });
});
