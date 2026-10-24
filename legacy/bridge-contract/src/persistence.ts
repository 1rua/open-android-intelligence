import type {
  DurableBridgeEntry,
  DurableBridgeStore,
  DurableBridgeTransaction,
} from "./durable-store.js";
import { BridgeServiceError } from "./service-types.js";

/** Stable identity for the SQLite-backed Bridge adapter boundary. */
export const SQLITE_BRIDGE_ADAPTER_PORT = "open-android-intelligence.bridge-sqlite-adapter.v1" as const;

/** Immutable Node-built-in SQLite driver selected by MVP-DEP-BRIDGE. */
export const NODE_SQLITE_BRIDGE_DRIVER = "node:sqlite@24.18.0/sqlite@3.53.1" as const;

/**
 * Closed state partitions that a production persistence adapter may expose.
 * The list is deliberately shared with the generic durable-store contract so
 * an adapter cannot add a second, unreviewed persistence domain.
 */
export const BRIDGE_PERSISTENCE_NAMESPACES = Object.freeze([
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
] as const);

export type BridgePersistenceNamespace = (typeof BRIDGE_PERSISTENCE_NAMESPACES)[number];

export type BridgeBackupArtifact = Readonly<{
  artifact: "backup";
  path: string;
  schemaVersion: number;
  createdAt?: string;
  digest?: string;
}>;

export type BridgeRestoreReport = Readonly<{
  restored: true;
  schemaVersion: number;
  digest?: string;
}>;

export type BridgeRecoveryReport = Readonly<{
  recovered: true;
  schemaVersion: number;
  repaired: boolean;
  discardedArtifacts: readonly string[];
}>;

/** A migration callback is run inside the adapter's one atomic transaction. */
export type SqliteMigrationWork =
  (transaction: DurableBridgeTransaction) => Promise<void> | void;

/**
 * Explicit port for the locked Node-built-in SQLite driver. The production
 * adapter in `bridge-runtime` implements this boundary; test fakes may only
 * represent the pending state and cannot be marked connected.
 *
 * `runMigration` must atomically execute the callback and publish `toVersion`
 * in the same SQLite transaction. A driver that cannot provide that guarantee
 * must reject rather than report a successful migration.
 */
export interface SqliteBridgeAdapterPort {
  readonly port: typeof SQLITE_BRIDGE_ADAPTER_PORT;
  readonly backend: "sqlite";
  readonly driver: typeof NODE_SQLITE_BRIDGE_DRIVER;
  readonly status: "external-driver-required" | "connected";
  readonly databasePath: string;
  readonly transact: DurableBridgeStore["transact"];
  readonly schemaVersion: () => Promise<number>;
  readonly runMigration: (
    scope: string,
    fromVersion: number,
    toVersion: number,
    work: SqliteMigrationWork,
  ) => Promise<void>;
  readonly backup: (destination: string) => Promise<BridgeBackupArtifact>;
  readonly restore: (source: string) => Promise<BridgeRestoreReport>;
  /** Reconcile an interrupted backup/restore or journal recovery before serve. */
  readonly recover: () => Promise<BridgeRecoveryReport>;
}

/** Generic production persistence port currently locked to SQLite for MVP. */
export type BridgePersistencePort = SqliteBridgeAdapterPort;

const callable = (value: unknown): value is (...args: never[]) => unknown =>
  typeof value === "function";

/** Runtime guard for composition roots. It never turns a port into a driver. */
export const isSqliteBridgeAdapterPort = (value: unknown): value is SqliteBridgeAdapterPort => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const candidate = value as Partial<SqliteBridgeAdapterPort>;
  return candidate.port === SQLITE_BRIDGE_ADAPTER_PORT
    && candidate.backend === "sqlite"
    && candidate.driver === NODE_SQLITE_BRIDGE_DRIVER
    && (candidate.status === "external-driver-required" || candidate.status === "connected")
    && typeof candidate.databasePath === "string"
    && candidate.databasePath.length > 0
    && callable(candidate.transact)
    && callable(candidate.schemaVersion)
    && callable(candidate.runMigration)
    && callable(candidate.backup)
    && callable(candidate.restore)
    && callable(candidate.recover);
};

export const assertSqliteBridgeAdapterPort = (value: unknown): SqliteBridgeAdapterPort => {
  if (!isSqliteBridgeAdapterPort(value)) {
    throw new BridgeServiceError("SQLITE_BRIDGE_ADAPTER_REQUIRED");
  }
  return value;
};

export const isBridgePersistencePort = isSqliteBridgeAdapterPort;
export const assertBridgePersistencePort = assertSqliteBridgeAdapterPort;

/** Production composition guard; source fakes intentionally remain pending. */
export const assertConnectedSqliteBridgeAdapter = (value: unknown): SqliteBridgeAdapterPort => {
  const adapter = assertSqliteBridgeAdapterPort(value);
  if (adapter.status !== "connected") throw new BridgeServiceError("SQLITE_DRIVER_PENDING");
  return adapter;
};

export type DurableBridgeEntryForPersistence = DurableBridgeEntry;
export type DurableBridgeTransactionForPersistence = DurableBridgeTransaction;
