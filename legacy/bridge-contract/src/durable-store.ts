import { BridgeServiceError } from "./service-types.js";
import { BRIDGE_PERSISTENCE_NAMESPACES } from "./persistence.js";

/** Stable identifier for the storage port consumed by a production Bridge. */
export const DURABLE_BRIDGE_STORE_PORT = "open-android-intelligence.bridge-store.v1" as const;

/**
 * State partitions that the WP-06 runtime is allowed to persist through this
 * port. The names are deliberately closed so an adapter cannot silently add a
 * second, unreviewed persistence domain. Authorization grants/revisions and
 * notification cursor positions are separate partitions so revocation and
 * cursor fencing can be committed atomically with their owning records.
 */
export const BRIDGE_STORE_NAMESPACES = BRIDGE_PERSISTENCE_NAMESPACES;

export type BridgeStoreNamespace = (typeof BRIDGE_STORE_NAMESPACES)[number];

export type DurableBridgeEntry = Readonly<{
  key: string;
  value: unknown;
}>;

/**
 * Operations available inside one atomic Bridge transaction.
 *
 * Implementations must provide a snapshot/serializable transaction: writes
 * become visible only when `DurableBridgeStore.transact` resolves, and a
 * rejected callback publishes no writes. The callback must not perform
 * external side effects because an implementation may retry it on a
 * serialization conflict.
 */
export type DurableBridgeTransaction = Readonly<{
  /** Stable implementation-generated ID useful for audit correlation. */
  transactionId: string;
  read(namespace: BridgeStoreNamespace, key: string): Promise<unknown | null>;
  scan(namespace: BridgeStoreNamespace): Promise<readonly DurableBridgeEntry[]>;
  write(namespace: BridgeStoreNamespace, key: string, value: unknown): Promise<void>;
  remove(namespace: BridgeStoreNamespace, key: string): Promise<void>;
}>;

/**
 * Database- and network-neutral durability boundary for the Bridge runtime.
 *
 * This is a contract only. No implementation in `bridge-contract` claims to
 * satisfy crash durability. The production adapter must make the callback's
 * successful return conditional on an atomic, crash-recoverable commit and
 * must expose the reviewed SQLite migration/backup/recovery port and health
 * checks at its deployment boundary (see `persistence.ts`).
 */
export interface DurableBridgeStore {
  readonly port: typeof DURABLE_BRIDGE_STORE_PORT;
  readonly durability: "durable";
  transact<T>(
    scope: string,
    work: (transaction: DurableBridgeTransaction) => Promise<T> | T,
  ): Promise<T>;
}

const TRANSACTION_SCOPE = /^[a-z0-9][a-z0-9._/-]{0,127}$/;

/** Runtime guard used by Bridge composition roots before accepting a store. */
export const isDurableBridgeStore = (value: unknown): value is DurableBridgeStore => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const candidate = value as Partial<DurableBridgeStore>;
  return candidate.port === DURABLE_BRIDGE_STORE_PORT
    && candidate.durability === "durable"
    && typeof candidate.transact === "function";
};

/**
 * Fail-closed composition guard. Process-local stores intentionally do not
 * carry the durable marker and therefore cannot be passed as production
 * persistence by accident.
 */
export const assertDurableBridgeStore = (value: unknown): DurableBridgeStore => {
  if (!isDurableBridgeStore(value)) throw new BridgeServiceError("DURABLE_STORE_REQUIRED");
  return value;
};

/**
 * Shared entry point for runtime code. Keeping scope validation here ensures
 * every caller uses the same port and makes an accidental empty/audit-less
 * transaction visible as a contract error.
 */
export const runDurableBridgeTransaction = <T>(
  store: unknown,
  scope: string,
  work: (transaction: DurableBridgeTransaction) => Promise<T> | T,
): Promise<T> => {
  if (typeof scope !== "string" || !TRANSACTION_SCOPE.test(scope)) {
    return Promise.reject(new BridgeServiceError("TRANSACTION_SCOPE_INVALID"));
  }
  if (typeof work !== "function") {
    return Promise.reject(new BridgeServiceError("TRANSACTION_WORK_INVALID"));
  }
  // Normalize adapter/marker failures to a rejected promise as well. Callers
  // can therefore handle invalid composition roots with one async path.
  return Promise.resolve().then(() => assertDurableBridgeStore(store).transact(scope, work));
};
