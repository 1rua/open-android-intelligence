import { mkdir, mkdtemp, readFile, readdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
  BRIDGE_STORE_NAMESPACES,
  assertDurableBridgeStore,
  isDurableBridgeStore,
  type BridgeStoreNamespace,
} from "../../bridge-contract/src/durable-store.js";
import {
  FILE_BACKED_BRIDGE_STORE_FORMAT,
  FILE_BACKED_BRIDGE_STORE_VERSION,
  FileBackedBridgeStore,
} from "../src/file-backed-store.js";

const roots: string[] = [];

const makeRoot = async (): Promise<string> => {
  const root = await mkdtemp(join(tmpdir(), "open-android-intelligence-bridge-runtime-"));
  roots.push(root);
  return root;
};

afterEach(async () => {
  // Keep cleanup intentionally best-effort: the adapter's tests must not rely
  // on a shell command or recursive deletion implementation.
  const { rm } = await import("node:fs/promises");
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("FileBackedBridgeStore (deterministic local WP-06 adapter)", () => {
  it("creates a versioned manifest and advertises the durable contract port", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });

    expect(store.port).toBe("open-android-intelligence.bridge-store.v1");
    expect(store.durability).toBe("durable");
    expect(isDurableBridgeStore(store)).toBe(true);
    expect(assertDurableBridgeStore(store)).toBe(store);
    await expect(store.manifest()).resolves.toMatchObject({
      format: FILE_BACKED_BRIDGE_STORE_FORMAT,
      version: FILE_BACKED_BRIDGE_STORE_VERSION,
      generation: 0,
    });
    await expect(readFile(join(root, "manifest.json"), "utf8")).resolves.toContain(
      FILE_BACKED_BRIDGE_STORE_FORMAT,
    );
  });

  it("commits writes atomically, reopens them, and scans keys in deterministic order", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });

    await store.transact("notification.ingest", async (tx) => {
      await tx.write("notification.records", "z", { body: "last", nested: [1, true] });
      await tx.write("notification.records", "a", { body: "first" });
      return "committed";
    });

    await expect(store.transact("notification.read", (tx) => tx.scan("notification.records"))).resolves.toEqual([
      { key: "a", value: { body: "first" } },
      { key: "z", value: { body: "last", nested: [1, true] } },
    ]);
    await expect(store.scan("notification.records")).resolves.toEqual([
      { key: "a", value: { body: "first" } },
      { key: "z", value: { body: "last", nested: [1, true] } },
    ]);
    await expect(store.manifest()).resolves.toMatchObject({ generation: 1 });

    const reopened = await FileBackedBridgeStore.open({ rootDir: root });
    await expect(reopened.transact("notification.read", (tx) => tx.read("notification.records", "z"))).resolves.toEqual({
      body: "last",
      nested: [1, true],
    });
    await expect(reopened.recover()).resolves.toMatchObject({ generation: 1, repaired: false });
  });

  it("publishes no writes when the callback rejects and isolates mutable values", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const mutable = { nested: { count: 1 } };

    await expect(store.transact("notification.ingest", async (tx) => {
      await tx.write("notification.records", "a", mutable);
      mutable.nested.count = 99;
      throw new Error("abort");
    })).rejects.toThrow("abort");

    await expect(store.transact("notification.read", (tx) => tx.read("notification.records", "a"))).resolves.toBeNull();
    await expect(store.manifest()).resolves.toMatchObject({ generation: 0 });
  });

  it("serializes concurrent transactions instead of exposing a partial snapshot", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    let releaseFirst!: () => void;
    const firstStarted = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    const first = store.transact("notification.first", async (tx) => {
      await tx.write("notification.records", "a", "first");
      await firstStarted;
    });
    // The second transaction is queued behind the first and cannot observe a
    // staged value before the first transaction publishes its manifest.
    const second = store.transact("notification.second", (tx) => tx.read("notification.records", "a"));
    releaseFirst();
    await expect(first).resolves.toBeUndefined();
    await expect(second).resolves.toBe("first");
    await expect(store.manifest()).resolves.toMatchObject({ generation: 1 });
  });

  it("fails closed for an unknown namespace and non-JSON values", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });

    await expect(store.transact("notification.invalid", async (tx) => {
      await tx.write("unreviewed.namespace" as BridgeStoreNamespace, "x", 1);
    })).rejects.toMatchObject({ code: "DURABLE_NAMESPACE_INVALID" });
    await expect(store.transact("notification.invalid", async (tx) => {
      await tx.write("notification.records", "x", BigInt(1));
    })).rejects.toMatchObject({ code: "DURABLE_VALUE_INVALID" });
    await expect(store.manifest()).resolves.toMatchObject({ generation: 0 });
  });

  it("recovers a valid committed generation when the manifest is missing and removes temp artifacts", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    await store.transact("notification.ingest", (tx) => tx.write("notification.records", "a", "value"));

    await writeFile(join(root, "manifest.json.tmp-crash"), "not-json", "utf8");
    await writeFile(join(root, "manifest.json"), "not-json", "utf8");
    const report = await store.recover();
    expect(report.repaired).toBe(true);
    expect(report.generation).toBe(1);
    await expect(store.transact("notification.read", (tx) => tx.read("notification.records", "a"))).resolves.toBe("value");
    await expect(readdir(root)).resolves.not.toContain("manifest.json.tmp-crash");
  });

  it("ignores an orphan generation left before pointer publication", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    await store.transact("notification.ingest", (tx) => tx.write("notification.records", "a", "published"));
    const manifest = await store.manifest();
    const statePath = join(root, manifest.stateFile);
    const state = JSON.parse(await readFile(statePath, "utf8")) as Record<string, unknown>;
    const orphanState = JSON.stringify({ ...state, generation: 99 });
    await writeFile(join(root, "generations", "state-0000000099.json"), orphanState, "utf8");

    const reopened = await FileBackedBridgeStore.open({ rootDir: root });
    await expect(reopened.manifest()).resolves.toMatchObject({ generation: 1 });
    await expect(reopened.transact("notification.read", (tx) => tx.read("notification.records", "a"))).resolves.toBe("published");
  });

  it("keeps the namespace set closed and initializes every reviewed partition", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const entries = await store.transact("bridge.scan", async (tx) => {
      const values: Record<string, readonly unknown[]> = {};
      for (const namespace of BRIDGE_STORE_NAMESPACES) values[namespace] = await tx.scan(namespace);
      return values;
    });
    expect(Object.keys(entries)).toEqual([...BRIDGE_STORE_NAMESPACES]);
    expect(Object.values(entries).every((value) => value.length === 0)).toBe(true);
  });

  it("upgrades a version-one local snapshot without dropping existing state", async () => {
    const root = await makeRoot();
    const state = {
      format: "open-android-intelligence.bridge-store.state.v1",
      version: 1,
      generation: 4,
      namespaces: Object.fromEntries(BRIDGE_STORE_NAMESPACES
        .filter((namespace) => namespace !== "authorization.grants"
          && namespace !== "authorization.revisions"
          && namespace !== "operation.replay-associations")
        .map((namespace) => [namespace, namespace === "notification.records"
          ? [{ key: "device-a/1", value: { recordId: "legacy" } }]
          : []])),
    };
    await mkdir(join(root, "generations"), { recursive: true });
    await writeFile(join(root, "generations", "state-00000000000000000004.json"), `${JSON.stringify(state)}\n`, "utf8");
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    await expect(store.transact("notification.read", (transaction) => transaction.read("notification.records", "device-a/1")))
      .resolves.toEqual({ recordId: "legacy" });
    await expect(store.manifest()).resolves.toMatchObject({ generation: 4, version: FILE_BACKED_BRIDGE_STORE_VERSION });
  });
});
