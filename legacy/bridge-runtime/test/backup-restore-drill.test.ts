import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
  BRIDGE_STORE_NAMESPACES,
  type BridgeStoreNamespace,
  type DurableBridgeEntry,
} from "../../bridge-contract/src/durable-store.js";
import {
  NODE_SQLITE_BRIDGE_DRIVER,
  SQLITE_BRIDGE_ADAPTER_PORT,
  type SqliteBridgeAdapterPort,
} from "../../bridge-contract/src/persistence.js";
import { runBridgeBackupRestoreDrill } from "../src/backup-restore-drill.js";
import { FileBackedBridgeStore } from "../src/file-backed-store.js";

const roots: string[] = [];

const makeRoot = async (): Promise<string> => {
  const root = await mkdtemp(join(tmpdir(), "open-android-intelligence-backup-drill-"));
  roots.push(root);
  return root;
};

afterEach(async () => {
  const { rm } = await import("node:fs/promises");
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("Bridge backup/restore verification seam", () => {
  it("restores the implemented namespaces and verifies schema, digest, recovery and entries", async () => {
    const sourceStore = await FileBackedBridgeStore.open({ rootDir: await makeRoot() });
    const targetStore = await FileBackedBridgeStore.open({ rootDir: await makeRoot() });
    await sourceStore.transact("test.seed", async (transaction) => {
      await transaction.write("pairing.bindings", "binding-a", { pairingGeneration: "1" });
      await transaction.write("notification.records", "record-a", { cursor: "4" });
      await transaction.write("notification.records", "record-b", { cursor: "5" });
      await transaction.write("subscription.events", "event-a", { acknowledged: false });
      await transaction.write("operation.replay-associations", "replay-a", { operationId: "op-a" });
    });
    let snapshot = new Map<BridgeStoreNamespace, readonly DurableBridgeEntry[]>();
    const adapter = (
      databasePath: string,
      store: FileBackedBridgeStore,
      mode: "source" | "target",
    ): SqliteBridgeAdapterPort => ({
      port: SQLITE_BRIDGE_ADAPTER_PORT,
      backend: "sqlite",
      driver: NODE_SQLITE_BRIDGE_DRIVER,
      status: "connected",
      databasePath,
      transact: mode === "source"
        ? store.transact.bind(store)
        : (scope, work) => store.transact(scope, (transaction) => work(Object.freeze({
          transactionId: transaction.transactionId,
          read: transaction.read,
          scan: async (namespace) => Object.freeze([...(await transaction.scan(namespace))].reverse()),
          write: transaction.write,
          remove: transaction.remove,
        }))),
      schemaVersion: async () => 7,
      runMigration: async (_scope, _from, _to, work) => store.transact("test.migration", work),
      backup: async (destination) => {
        snapshot = new Map(await Promise.all(BRIDGE_STORE_NAMESPACES.map(async (namespace) => [
          namespace,
          await store.transact("test.backup", (transaction) => transaction.scan(namespace)),
        ] as const)));
        return { artifact: "backup", path: destination, schemaVersion: 7, digest: "sha256:backup-a" };
      },
      restore: async () => {
        if (mode !== "target") throw new Error("restore called on source");
        await store.transact("test.restore", async (transaction) => {
          for (const [namespace, entries] of snapshot) {
            for (const entry of entries) await transaction.write(namespace, entry.key, entry.value);
          }
        });
        return { restored: true, schemaVersion: 7, digest: "sha256:backup-a" };
      },
      recover: async () => ({ recovered: true, schemaVersion: 7, repaired: false, discardedArtifacts: [] }),
    });
    const namespaces = [
      "pairing.bindings",
      "notification.records",
      "subscription.events",
      "operation.replay-associations",
    ] as const;

    await expect(runBridgeBackupRestoreDrill({
      source: adapter("/external/source.sqlite", sourceStore, "source"),
      restoreTarget: adapter("/external/restore.sqlite", targetStore, "target"),
      destination: "/external/backup.sqlite",
      namespaces,
    })).resolves.toEqual({
      verified: true,
      schemaVersion: 7,
      digest: "sha256:backup-a",
      namespaces: [
        { namespace: "pairing.bindings", entries: 1 },
        { namespace: "notification.records", entries: 2 },
        { namespace: "subscription.events", entries: 1 },
        { namespace: "operation.replay-associations", entries: 1 },
      ],
    });
  });
});
