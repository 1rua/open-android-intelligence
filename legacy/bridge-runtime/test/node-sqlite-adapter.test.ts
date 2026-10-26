import { randomUUID } from "node:crypto";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  BRIDGE_STORE_NAMESPACES,
  type BridgeStoreNamespace,
} from "../../bridge-contract/src/durable-store.js";
import {
  NODE_SQLITE_BRIDGE_DRIVER,
  SQLITE_BRIDGE_ADAPTER_PORT,
} from "../../bridge-contract/src/persistence.js";
import { openNodeSqliteBridgeAdapter } from "../src/node-sqlite-adapter.js";

const root = await mkdtemp(join(tmpdir(), "open-android-intelligence-node-sqlite-"));
const databasePath = join(root, "bridge.sqlite");
let adapter = await openNodeSqliteBridgeAdapter({
  databasePath,
  ownerId: "test-owner",
});

afterAll(async () => {
  await adapter.close();
  await rm(root, { recursive: true, force: true });
});

const namespace = BRIDGE_STORE_NAMESPACES[0] as BridgeStoreNamespace;

describe("Node SQLite production adapter", () => {
  it("publishes the locked driver and schema v1", () => {
    expect(adapter.port).toBe(SQLITE_BRIDGE_ADAPTER_PORT);
    expect(adapter.backend).toBe("sqlite");
    expect(adapter.driver).toBe(NODE_SQLITE_BRIDGE_DRIVER);
    expect(adapter.status).toBe("connected");
    expect(adapter.databasePath).toBe(databasePath);
  });

  it("commits entries, rolls back failures, and preserves bigint values", async () => {
    await expect(adapter.schemaVersion()).resolves.toBe(1);
    await adapter.transact("node-sqlite.test.write", async (transaction) => {
      await transaction.write(namespace, "entry:a", { count: 1n, name: "a" });
      await transaction.write(namespace, "entry:b", { count: 2n, name: "b" });
    });
    await expect(adapter.transact("node-sqlite.test.read", async (transaction) =>
      transaction.read(namespace, "entry:a"))).resolves.toEqual({ count: 1n, name: "a" });
    await expect(adapter.transact("node-sqlite.test.scan", async (transaction) =>
      transaction.scan(namespace))).resolves.toEqual([
        { key: "entry:a", value: { count: 1n, name: "a" } },
        { key: "entry:b", value: { count: 2n, name: "b" } },
      ]);
    await expect(adapter.transact("node-sqlite.test.rollback", async (transaction) => {
      await transaction.write(namespace, "entry:c", "must-not-commit");
      throw new Error("intentional rollback");
    })).rejects.toThrowError(/intentional rollback/);
    await expect(adapter.transact("node-sqlite.test.after-rollback", async (transaction) =>
      transaction.read(namespace, "entry:c"))).resolves.toBeNull();
  });

  it("rejects unknown namespaces and non-plain state values", async () => {
    await expect(adapter.transact("node-sqlite.test.namespace", async (transaction) =>
      transaction.write("unknown.namespace" as BridgeStoreNamespace, "key", "value")))
      .rejects.toMatchObject({ code: "SQLITE_NAMESPACE_INVALID" });
    await expect(adapter.transact("node-sqlite.test.value", async (transaction) =>
      transaction.write(namespace, "invalid", new Date(0))))
      .rejects.toMatchObject({ code: "SQLITE_VALUE_INVALID" });
  });

  it("discards interrupted staged restore artifacts during recovery", async () => {
    await writeFile(`${databasePath}.restore-interrupted`, "stale", { mode: 0o600 });
    await writeFile(`${databasePath}.old-interrupted`, "stale", { mode: 0o600 });
    await expect(adapter.recover()).resolves.toMatchObject({
      recovered: true,
      schemaVersion: 1,
      discardedArtifacts: [
        "bridge.sqlite.old-interrupted",
        "bridge.sqlite.restore-interrupted",
      ],
    });
  });

  it("creates a digest-verified backup and restores it into an isolated database", async () => {
    const artifactPath = join(root, `${randomUUID()}.sqlite.bak`);
    const restorePath = join(root, `${randomUUID()}.restore.sqlite`);
    const artifact = await adapter.backup(artifactPath);
    expect(artifact.schemaVersion).toBe(1);
    expect(artifact.digest).toMatch(/^sha256:[0-9a-f]{64}$/);

    const restore = await openNodeSqliteBridgeAdapter({
      databasePath: restorePath,
      ownerId: "restore-owner",
    });
    try {
      await expect(restore.restore(artifactPath)).resolves.toMatchObject({
        restored: true,
        schemaVersion: 1,
        digest: artifact.digest,
      });
      await expect(restore.transact("node-sqlite.test.restored", async (transaction) =>
        transaction.read(namespace, "entry:a"))).resolves.toEqual({ count: 1n, name: "a" });
    } finally {
      await restore.close();
      await rm(restorePath, { force: true });
    }
  });
});
