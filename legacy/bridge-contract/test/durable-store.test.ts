import { describe, expect, it } from "vitest";
import { NotificationStore } from "../src/notification-store.js";
import {
  BRIDGE_STORE_NAMESPACES,
  DURABLE_BRIDGE_STORE_PORT,
  assertDurableBridgeStore,
  isDurableBridgeStore,
  runDurableBridgeTransaction,
  type BridgeStoreNamespace,
  type DurableBridgeStore,
  type DurableBridgeTransaction,
} from "../src/durable-store.js";

/**
 * A test-only implementation of the port. It is intentionally kept here so
 * the contract test cannot accidentally turn the production package into an
 * in-memory "durable" implementation.
 */
class FakeDurableStore implements DurableBridgeStore {
  readonly port = DURABLE_BRIDGE_STORE_PORT;
  readonly durability = "durable" as const;
  #state = new Map<BridgeStoreNamespace, Map<string, unknown>>();
  #sequence = 0;

  async transact<T>(scope: string, work: (transaction: DurableBridgeTransaction) => Promise<T> | T): Promise<T> {
    const staged = new Map<BridgeStoreNamespace, Map<string, unknown>>();
    for (const namespace of BRIDGE_STORE_NAMESPACES) {
      staged.set(namespace, new Map(this.#state.get(namespace) ?? []));
    }
    const transaction = this.#transaction(staged, `tx-${++this.#sequence}`);
    const result = await work(transaction);
    this.#state = staged;
    return result;
  }

  snapshot(namespace: BridgeStoreNamespace): readonly Readonly<{ key: string; value: unknown }>[] {
    return Object.freeze([...this.#state.get(namespace)?.entries() ?? []]
      .map(([key, value]) => Object.freeze({ key, value })));
  }

  #transaction(state: Map<BridgeStoreNamespace, Map<string, unknown>>, transactionId: string): DurableBridgeTransaction {
    return Object.freeze({
      transactionId,
      read: async (namespace: BridgeStoreNamespace, key: string) => state.get(namespace)?.get(key) ?? null,
      scan: async (namespace: BridgeStoreNamespace) => Object.freeze([...state.get(namespace)?.entries() ?? []]
        .map(([key, value]) => Object.freeze({ key, value }))),
      write: async (namespace: BridgeStoreNamespace, key: string, value: unknown) => {
        state.get(namespace)!.set(key, value);
      },
      remove: async (namespace: BridgeStoreNamespace, key: string) => {
        state.get(namespace)!.delete(key);
      },
    });
  }
}

describe("WP-06 durable Bridge store transaction port", () => {
  it("commits a transaction atomically after the callback resolves", async () => {
    const store = new FakeDurableStore();
    const result = await runDurableBridgeTransaction(store, "notification.ingest", async (transaction) => {
      expect(transaction.transactionId).toBe("tx-1");
      await transaction.write("notification.records", "device-a/1", { recordId: "record-1" });
      await transaction.write("notification.positions", "device-a", { sourceEpoch: "1", cursor: "1" });
      return "committed";
    });

    expect(result).toBe("committed");
    expect(store.snapshot("notification.records")).toEqual([{ key: "device-a/1", value: { recordId: "record-1" } }]);
    expect(store.snapshot("notification.positions")).toEqual([{ key: "device-a", value: { sourceEpoch: "1", cursor: "1" } }]);
  });

  it("does not publish staged writes when the callback rejects", async () => {
    const store = new FakeDurableStore();
    await expect(runDurableBridgeTransaction(store, "notification.ingest", async (transaction) => {
      await transaction.write("notification.records", "device-a/1", { recordId: "record-1" });
      throw new Error("simulated crash before commit");
    })).rejects.toThrow("simulated crash before commit");

    expect(store.snapshot("notification.records")).toEqual([]);
  });

  it("requires an explicit durable marker and rejects process-local stores", () => {
    const durable = new FakeDurableStore();
    expect(isDurableBridgeStore(durable)).toBe(true);
    expect(assertDurableBridgeStore(durable)).toBe(durable);

    const processLocal = new NotificationStore();
    expect(isDurableBridgeStore(processLocal)).toBe(false);
    expect(() => assertDurableBridgeStore(processLocal)).toThrowError(/DURABLE_STORE_REQUIRED/);
  });

  it("rejects an empty transaction scope before invoking the port", async () => {
    const store = new FakeDurableStore();
    let called = false;
    await expect(runDurableBridgeTransaction(store, "", async () => {
      called = true;
      return null;
    })).rejects.toThrowError(/TRANSACTION_SCOPE_INVALID/);
    expect(called).toBe(false);
  });

  it("normalizes a missing durable adapter to an async fail-closed error", async () => {
    await expect(runDurableBridgeTransaction(new NotificationStore(), "notification.ingest", async () => null))
      .rejects.toThrowError(/DURABLE_STORE_REQUIRED/);
  });
});
