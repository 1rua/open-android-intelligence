import { describe, expect, it } from "vitest";
import {
  BRIDGE_PERSISTENCE_NAMESPACES,
  NODE_SQLITE_BRIDGE_DRIVER,
  SQLITE_BRIDGE_ADAPTER_PORT,
  assertConnectedSqliteBridgeAdapter,
  isSqliteBridgeAdapterPort,
  type SqliteBridgeAdapterPort,
} from "../src/persistence.js";
import { BRIDGE_STORE_NAMESPACES } from "../src/durable-store.js";

describe("Bridge persistence contract", () => {
  it("closes the production persistence namespace set", () => {
    expect(BRIDGE_PERSISTENCE_NAMESPACES).toEqual([
      "pairing.tickets",
      "pairing.bindings",
      "authorization.grants",
      "authorization.revisions",
      "notification.records",
      "notification.positions",
      "subscription.bindings",
      "subscription.events",
      "operation.claims",
      "operation.replay-associations",
      "assistant.metadata",
    ]);
    expect(BRIDGE_STORE_NAMESPACES).toEqual(BRIDGE_PERSISTENCE_NAMESPACES);
  });

  it("requires an explicit external SQLite driver marker", () => {
    const incomplete = { port: SQLITE_BRIDGE_ADAPTER_PORT };
    expect(isSqliteBridgeAdapterPort(incomplete)).toBe(false);
    expect(isSqliteBridgeAdapterPort({
      port: SQLITE_BRIDGE_ADAPTER_PORT,
      backend: "sqlite",
      driver: NODE_SQLITE_BRIDGE_DRIVER,
      status: "external-driver-required",
      databasePath: "",
    })).toBe(false);
    const adapter = {
      port: SQLITE_BRIDGE_ADAPTER_PORT,
      backend: "sqlite",
      driver: NODE_SQLITE_BRIDGE_DRIVER,
      status: "external-driver-required",
      databasePath: "/var/lib/open-android-intelligence/bridge.sqlite",
      transact: async () => undefined,
      schemaVersion: async () => 0,
      runMigration: async () => undefined,
      backup: async () => ({ artifact: "backup", path: "/tmp/bridge.sqlite.bak", schemaVersion: 0 }),
      restore: async () => ({ restored: true, schemaVersion: 0 }),
      recover: async () => ({ recovered: true, schemaVersion: 0, repaired: false, discardedArtifacts: [] }),
    } satisfies SqliteBridgeAdapterPort;
    expect(isSqliteBridgeAdapterPort(adapter)).toBe(true);
    expect(() => assertConnectedSqliteBridgeAdapter(adapter)).toThrowError(/SQLITE_DRIVER_PENDING/);
  });

  it("keeps backup, restore, and crash-recovery on the same adapter boundary", async () => {
    const adapter: SqliteBridgeAdapterPort = {
      port: SQLITE_BRIDGE_ADAPTER_PORT,
      backend: "sqlite",
      driver: NODE_SQLITE_BRIDGE_DRIVER,
      status: "external-driver-required",
      databasePath: ":memory:",
      transact: async () => undefined,
      schemaVersion: async () => 3,
      runMigration: async () => undefined,
      backup: async (destination) => ({ artifact: "backup", path: destination, schemaVersion: 3, digest: "sha256:test" }),
      restore: async () => ({ restored: true, schemaVersion: 3, digest: "sha256:test" }),
      recover: async () => ({ recovered: true, schemaVersion: 3, repaired: true, discardedArtifacts: ["journal"] }),
    };
    await expect(adapter.backup("/tmp/bridge.sqlite.bak")).resolves.toMatchObject({ artifact: "backup", schemaVersion: 3 });
    await expect(adapter.restore("/tmp/bridge.sqlite.bak")).resolves.toMatchObject({ restored: true, schemaVersion: 3 });
    await expect(adapter.recover()).resolves.toMatchObject({ recovered: true, repaired: true });
  });
});
