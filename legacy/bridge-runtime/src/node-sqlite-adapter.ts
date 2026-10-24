import { randomUUID } from "node:crypto";
import { createHash } from "node:crypto";
import { backup } from "node:sqlite";
import { DatabaseSync } from "node:sqlite";
import { mkdir, readFile, readdir, rename, rm, stat } from "node:fs/promises";
import { dirname, join } from "node:path";
import {
  BRIDGE_STORE_NAMESPACES,
  type BridgeStoreNamespace,
  type DurableBridgeEntry,
  type DurableBridgeTransaction,
} from "../../bridge-contract/src/durable-store.js";
import {
  NODE_SQLITE_BRIDGE_DRIVER,
  SQLITE_BRIDGE_ADAPTER_PORT,
  type BridgeBackupArtifact,
  type BridgeRecoveryReport,
  type BridgeRestoreReport,
  type SqliteBridgeAdapterPort,
  type SqliteMigrationWork,
} from "../../bridge-contract/src/persistence.js";
import { BridgeServiceError } from "../../bridge-contract/src/service-types.js";
import {
  BRIDGE_LEASE_COORDINATOR_PORT,
  type BridgeLease,
  type BridgeLeaseCoordinatorPort,
} from "./production-ports.js";

const SQLITE_VERSION = "3.53.1";
const NODE_VERSION = "24.18.0";
const SCHEMA_VERSION = 1;
const BIGINT_TAG = "$openAndroidIntelligence.bigint";
const NODE_SQLITE_LEASE_COORDINATOR = Symbol("open-android-intelligence.node-sqlite-lease-coordinator");

export type NodeSqliteBridgeAdapterOptions = Readonly<{
  databasePath: string;
  ownerId: string;
  busyTimeoutMs?: number;
  clock?: () => number;
}>;

type JsonValue = null | boolean | number | string | JsonValue[] | { readonly [key: string]: JsonValue };

const namespaceSet = new Set<string>(BRIDGE_STORE_NAMESPACES);
const error = (code: string): BridgeServiceError => new BridgeServiceError(code);

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const encode = (value: unknown, seen = new WeakSet<object>()): JsonValue => {
  if (value === null || typeof value === "string" || typeof value === "boolean") return value;
  if (typeof value === "bigint") return Object.freeze({ [BIGINT_TAG]: value.toString(10) });
  if (typeof value === "number" && Number.isFinite(value)) return Object.is(value, -0) ? 0 : value;
  if (typeof value !== "object" || value === null || seen.has(value)) throw error("SQLITE_VALUE_INVALID");
  seen.add(value);
  let output: JsonValue;
  if (Array.isArray(value)) output = value.map((item) => encode(item, seen));
  else {
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) throw error("SQLITE_VALUE_INVALID");
    const record: Record<string, JsonValue> = {};
    for (const key of Object.keys(value)) {
      if (key === BIGINT_TAG) throw error("SQLITE_VALUE_INVALID");
      record[key] = encode((value as Record<string, unknown>)[key], seen);
    }
    output = Object.freeze(record);
  }
  seen.delete(value);
  return output;
};

const decode = (value: unknown, seen = new WeakSet<object>()): unknown => {
  if (value === null || typeof value === "string" || typeof value === "boolean") return value;
  if (typeof value === "number" && Number.isFinite(value)) return Object.is(value, -0) ? 0 : value;
  if (typeof value !== "object" || value === null || seen.has(value)) throw error("SQLITE_STATE_INVALID");
  seen.add(value);
  let output: unknown;
  if (Array.isArray(value)) output = Object.freeze(value.map((item) => decode(item, seen)));
  else {
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) throw error("SQLITE_STATE_INVALID");
    const keys = Object.keys(value);
    if (keys.includes(BIGINT_TAG)) {
      const encoded = (value as Record<string, unknown>)[BIGINT_TAG];
      if (keys.length !== 1 || typeof encoded !== "string" || !/^-?(?:0|[1-9][0-9]*)$/.test(encoded)) {
        throw error("SQLITE_STATE_INVALID");
      }
      output = BigInt(encoded);
    } else {
      const record: Record<string, unknown> = {};
      for (const key of keys) record[key] = decode((value as Record<string, unknown>)[key], seen);
      output = Object.freeze(record);
    }
  }
  seen.delete(value);
  return output;
};

const assertNamespace = (namespace: BridgeStoreNamespace): void => {
  if (!namespaceSet.has(namespace)) throw error("SQLITE_NAMESPACE_INVALID");
};

const assertKey = (key: string): void => {
  if (typeof key !== "string" || key.length === 0) throw error("SQLITE_KEY_INVALID");
};

class Transaction implements DurableBridgeTransaction {
  readonly transactionId = randomUUID();
  readonly #database: DatabaseSync;

  constructor(database: DatabaseSync) {
    this.#database = database;
  }

  read(namespace: BridgeStoreNamespace, key: string): Promise<unknown | null> {
    assertNamespace(namespace);
    assertKey(key);
    const row = this.#database.prepare(
      "SELECT value FROM bridge_entries WHERE namespace = ? AND key = ?",
    ).get(namespace, key) as { value?: string | null } | undefined;
    return Promise.resolve(row?.value === undefined || row.value === null ? null : decode(JSON.parse(row.value)));
  }

  scan(namespace: BridgeStoreNamespace): Promise<readonly DurableBridgeEntry[]> {
    assertNamespace(namespace);
    const rows = this.#database.prepare(
      "SELECT key, value FROM bridge_entries WHERE namespace = ? ORDER BY key COLLATE BINARY",
    ).all(namespace) as { key: string; value: string }[];
    return Promise.resolve(Object.freeze(rows.map((row) => Object.freeze({
      key: row.key,
      value: decode(JSON.parse(row.value)),
    }))));
  }

  write(namespace: BridgeStoreNamespace, key: string, value: unknown): Promise<void> {
    assertNamespace(namespace);
    assertKey(key);
    const encoded = encode(value);
    this.#database.prepare(
      "INSERT INTO bridge_entries(namespace, key, value) VALUES (?, ?, ?) ON CONFLICT(namespace, key) DO UPDATE SET value = excluded.value",
    ).run(namespace, key, JSON.stringify(encoded));
    return Promise.resolve();
  }

  remove(namespace: BridgeStoreNamespace, key: string): Promise<void> {
    assertNamespace(namespace);
    assertKey(key);
    this.#database.prepare(
      "DELETE FROM bridge_entries WHERE namespace = ? AND key = ?",
    ).run(namespace, key);
    return Promise.resolve();
  }
}

export class NodeSqliteBridgeAdapter implements SqliteBridgeAdapterPort {
  readonly port = SQLITE_BRIDGE_ADAPTER_PORT;
  readonly backend = "sqlite" as const;
  readonly driver = NODE_SQLITE_BRIDGE_DRIVER;
  readonly status = "connected" as const;
  readonly databasePath: string;
  readonly #database: DatabaseSync;
  readonly #ownerId: string;
  readonly #clock: () => number;
  #queue: Promise<unknown> = Promise.resolve();
  #closed = false;

  private constructor(options: NodeSqliteBridgeAdapterOptions, database: DatabaseSync) {
    this.databasePath = options.databasePath;
    this.#ownerId = options.ownerId;
    this.#clock = options.clock ?? (() => Date.now());
    this.#database = database;
  }

  static async open(options: NodeSqliteBridgeAdapterOptions): Promise<NodeSqliteBridgeAdapter> {
    if (process.versions.node !== NODE_VERSION || process.versions.sqlite !== SQLITE_VERSION) {
      throw error("SQLITE_DRIVER_LOCK_MISMATCH");
    }
    if (!options || typeof options.databasePath !== "string" || options.databasePath.length === 0
      || options.databasePath === ":memory:") throw error("SQLITE_PATH_INVALID");
    if (typeof options.ownerId !== "string" || options.ownerId.length === 0
      || options.ownerId.includes("\u0000")) throw error("SQLITE_OWNER_INVALID");
    const timeout = options.busyTimeoutMs ?? 5_000;
    if (!Number.isSafeInteger(timeout) || timeout < 0) throw error("SQLITE_TIMEOUT_INVALID");
    await mkdir(dirname(options.databasePath), { recursive: true });
    const database = new DatabaseSync(options.databasePath, {
      timeout,
      enableForeignKeyConstraints: true,
      enableDoubleQuotedStringLiterals: false,
      allowExtension: false,
      defensive: true,
      readBigInts: true,
    });
    try {
      const version = database.prepare("SELECT sqlite_version() AS version").get()?.version;
      if (version !== SQLITE_VERSION) throw error("SQLITE_DRIVER_LOCK_MISMATCH");
      database.exec("PRAGMA journal_mode = WAL; PRAGMA synchronous = FULL; PRAGMA trusted_schema = OFF;");
      database.exec(`
        CREATE TABLE IF NOT EXISTS bridge_meta (
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE IF NOT EXISTS bridge_entries (
          namespace TEXT NOT NULL,
          key TEXT NOT NULL,
          value TEXT NOT NULL,
          PRIMARY KEY (namespace, key)
        ) WITHOUT ROWID;
        CREATE TABLE IF NOT EXISTS bridge_leases (
          scope TEXT PRIMARY KEY,
          owner_id TEXT NOT NULL,
          fencing_token INTEGER NOT NULL,
          expires_at_ms INTEGER NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE IF NOT EXISTS bridge_transaction_log (
          id TEXT PRIMARY KEY,
          scope TEXT NOT NULL,
          owner_id TEXT NOT NULL,
          started_at_ms INTEGER NOT NULL,
          committed_at_ms INTEGER NOT NULL
        ) WITHOUT ROWID;
        INSERT INTO bridge_meta(key, value) VALUES ('schema_version', '1')
          ON CONFLICT(key) DO NOTHING;
      `);
      const report = await new NodeSqliteBridgeAdapter(options, database).recover();
      if (report.schemaVersion !== SCHEMA_VERSION) throw error("SQLITE_SCHEMA_INVALID");
      return new NodeSqliteBridgeAdapter(options, database);
    } catch (caught) {
      try { database.close(); } catch { /* ownership remains with this constructor */ }
      throw caught;
    }
  }

  #enqueue<T>(work: () => Promise<T>): Promise<T> {
    const next = this.#queue.then(work, work);
    this.#queue = next.catch(() => undefined);
    return next;
  }

  #assertScope(scope: string): void {
    if (typeof scope !== "string" || !/^[a-z0-9][a-z0-9._/-]{0,127}$/.test(scope)) {
      throw error("TRANSACTION_SCOPE_INVALID");
    }
  }

  #readLease(scope: string): { ownerId: string; fencingToken: bigint; expiresAtMs: number } | null {
    const row = this.#database.prepare(
      "SELECT owner_id, fencing_token, expires_at_ms FROM bridge_leases WHERE scope = ?",
    ).get(scope) as { owner_id?: string; fencing_token?: bigint; expires_at_ms?: number } | undefined;
    if (row === undefined || row.owner_id === undefined || row.fencing_token === undefined
      || row.expires_at_ms === undefined) return null;
    return { ownerId: row.owner_id, fencingToken: BigInt(row.fencing_token), expiresAtMs: Number(row.expires_at_ms) };
  }

  async #acquireLease(input: Readonly<{ scope: string; ownerId: string; ttlMs: number }>): Promise<BridgeLease> {
    if (this.#closed) throw error("BRIDGE_LEASE_ADAPTER_CLOSED");
    if (typeof input.scope !== "string" || input.scope.length === 0 || input.scope.includes("\u0000")
      || typeof input.ownerId !== "string" || input.ownerId.length === 0 || input.ownerId.includes("\u0000")
      || !Number.isSafeInteger(input.ttlMs) || input.ttlMs < 1) throw error("BRIDGE_LEASE_INPUT_INVALID");
    return this.#enqueue(async () => {
      const now = this.#clock();
      if (!Number.isFinite(now)) throw error("BRIDGE_LEASE_CLOCK_INVALID");
      this.#database.exec("BEGIN IMMEDIATE");
      try {
        const current = this.#readLease(input.scope);
        if (current !== null && now < current.expiresAtMs && current.ownerId !== input.ownerId) {
          throw error("BRIDGE_LEASE_BUSY");
        }
        const token = (current?.fencingToken ?? 0n) + 1n;
        this.#database.prepare(
          `INSERT INTO bridge_leases(scope, owner_id, fencing_token, expires_at_ms)
           VALUES (?, ?, ?, ?)
           ON CONFLICT(scope) DO UPDATE SET owner_id = excluded.owner_id,
             fencing_token = excluded.fencing_token, expires_at_ms = excluded.expires_at_ms`,
        ).run(input.scope, input.ownerId, token, now + input.ttlMs);
        this.#database.exec("COMMIT");
        return Object.freeze({
          scope: input.scope,
          ownerId: input.ownerId,
          fencingToken: token,
          expiresAtMs: now + input.ttlMs,
          ttlMs: input.ttlMs,
        });
      } catch (caught) {
        try { this.#database.exec("ROLLBACK"); } catch { /* BEGIN failure is reported below */ }
        throw caught;
      }
    });
  }

  async #renewLease(lease: BridgeLease): Promise<BridgeLease> {
    if (this.#closed) throw error("BRIDGE_LEASE_ADAPTER_CLOSED");
    return this.#enqueue(async () => {
      const now = this.#clock();
      this.#database.exec("BEGIN IMMEDIATE");
      try {
        const current = this.#readLease(lease.scope);
        if (current === null || current.ownerId !== lease.ownerId
          || current.fencingToken !== lease.fencingToken || now >= current.expiresAtMs) {
          throw error(current === null || current.fencingToken !== lease.fencingToken
            ? "BRIDGE_LEASE_FENCED" : "BRIDGE_LEASE_EXPIRED");
        }
        const ttl = lease.ttlMs ?? lease.expiresAtMs - now;
        if (!Number.isSafeInteger(ttl) || ttl < 1) throw error("BRIDGE_LEASE_INPUT_INVALID");
        const expiresAtMs = now + ttl;
        this.#database.prepare(
          "UPDATE bridge_leases SET expires_at_ms = ? WHERE scope = ? AND owner_id = ? AND fencing_token = ?",
        ).run(expiresAtMs, lease.scope, lease.ownerId, lease.fencingToken);
        this.#database.exec("COMMIT");
        return Object.freeze({ ...lease, expiresAtMs });
      } catch (caught) {
        try { this.#database.exec("ROLLBACK"); } catch { /* BEGIN failure is reported below */ }
        throw caught;
      }
    });
  }

  transact<T>(
    scope: string,
    work: (transaction: DurableBridgeTransaction) => Promise<T> | T,
  ): Promise<T> {
    if (this.#closed) return Promise.reject(error("SQLITE_ADAPTER_CLOSED"));
    return this.#enqueue(async () => this.#transactNow(scope, work));
  }

  async #transactNow<T>(
    scope: string,
    work: (transaction: DurableBridgeTransaction) => Promise<T> | T,
  ): Promise<T> {
    this.#assertScope(scope);
    const transactionId = randomUUID();
    const started = Date.now();
    this.#database.exec("BEGIN IMMEDIATE");
    try {
      this.#database.prepare(
        "INSERT INTO bridge_transaction_log(id, scope, owner_id, started_at_ms, committed_at_ms) VALUES (?, ?, ?, ?, ?)",
      ).run(transactionId, scope, this.#ownerId, started, started);
      const result = await work(new Transaction(this.#database));
      this.#database.prepare(
        "UPDATE bridge_transaction_log SET committed_at_ms = ? WHERE id = ?",
      ).run(Date.now(), transactionId);
      this.#database.exec("COMMIT");
      return result;
    } catch (caught) {
      try { this.#database.exec("ROLLBACK"); } catch { /* the failed BEGIN owns rollback reporting */ }
      throw caught;
    }
  }

  async #transactFencedNow<T>(
    lease: BridgeLease,
    scope: string,
    work: (transaction: DurableBridgeTransaction) => Promise<T> | T,
  ): Promise<T> {
    this.#assertScope(scope);
    this.#database.exec("BEGIN IMMEDIATE");
    try {
      const current = this.#readLease(lease.scope);
      if (current === null || current.ownerId !== lease.ownerId
        || current.fencingToken !== lease.fencingToken) throw error("BRIDGE_LEASE_FENCED");
      if (this.#clock() >= current.expiresAtMs) throw error("BRIDGE_LEASE_EXPIRED");
      const result = await work(new Transaction(this.#database));
      this.#database.exec("COMMIT");
      return result;
    } catch (caught) {
      try { this.#database.exec("ROLLBACK"); } catch { /* the failed BEGIN owns rollback reporting */ }
      throw caught;
    }
  }

  async #releaseLease(lease: BridgeLease): Promise<void> {
    if (this.#closed) throw error("BRIDGE_LEASE_ADAPTER_CLOSED");
    await this.#enqueue(async () => {
      this.#database.exec("BEGIN IMMEDIATE");
      try {
        const current = this.#readLease(lease.scope);
        if (current === null || current.ownerId !== lease.ownerId
          || current.fencingToken !== lease.fencingToken) throw error("BRIDGE_LEASE_FENCED");
        this.#database.prepare(
          "DELETE FROM bridge_leases WHERE scope = ? AND owner_id = ? AND fencing_token = ?",
        ).run(lease.scope, lease.ownerId, lease.fencingToken);
        this.#database.exec("COMMIT");
      } catch (caught) {
        try { this.#database.exec("ROLLBACK"); } catch { /* the failed BEGIN owns rollback reporting */ }
        throw caught;
      }
    });
  }

  createLeaseCoordinator(): BridgeLeaseCoordinatorPort {
    return Object.freeze({
      [NODE_SQLITE_LEASE_COORDINATOR]: this,
      port: BRIDGE_LEASE_COORDINATOR_PORT,
      status: "connected",
      acquire: (input: Readonly<{ scope: string; ownerId: string; ttlMs: number }>) => this.#acquireLease(input),
      renew: (lease: BridgeLease) => this.#renewLease(lease),
      transact: <T>(
        lease: BridgeLease,
        scope: string,
        work: (transaction: DurableBridgeTransaction) => Promise<T> | T,
      ) => this.#enqueue(() => this.#transactFencedNow(lease, scope, work)),
      release: (lease: BridgeLease) => this.#releaseLease(lease),
    });
  }

  async schemaVersion(): Promise<number> {
    const row = this.#database.prepare(
      "SELECT value FROM bridge_meta WHERE key = 'schema_version'",
    ).get() as { value?: string } | undefined;
    const value = Number(row?.value);
    if (!Number.isSafeInteger(value) || value < 0) throw error("SQLITE_SCHEMA_INVALID");
    return value;
  }

  async runMigration(
    scope: string,
    fromVersion: number,
    toVersion: number,
    work: SqliteMigrationWork,
  ): Promise<void> {
    if (fromVersion !== 0 || toVersion !== SCHEMA_VERSION) throw error("SQLITE_MIGRATION_UNSUPPORTED");
    const current = await this.schemaVersion();
    if (current !== fromVersion && current !== toVersion) throw error("SQLITE_MIGRATION_VERSION_MISMATCH");
    await this.transact(scope, async (transaction) => {
      if (current === fromVersion) await work(transaction);
      const next = await this.schemaVersion();
      if (next !== toVersion) throw error("SQLITE_MIGRATION_COMMIT_UNVERIFIED");
    });
  }

  async backup(destination: string): Promise<BridgeBackupArtifact> {
    if (this.#closed) throw error("SQLITE_ADAPTER_CLOSED");
    if (typeof destination !== "string" || destination.length === 0 || destination === this.databasePath) {
      throw error("SQLITE_BACKUP_DESTINATION_INVALID");
    }
    await mkdir(dirname(destination), { recursive: true });
    await rm(destination, { force: true });
    await rm(`${destination}-wal`, { force: true });
    await rm(`${destination}-shm`, { force: true });
    const pages = await backup(this.#database, destination);
    if (!Number.isSafeInteger(pages) || pages < 0) throw error("SQLITE_BACKUP_FAILED");
    const digest = createHash("sha256");
    await digestWrite(destination, digest);
    return Object.freeze({
      artifact: "backup",
      path: destination,
      schemaVersion: await this.schemaVersion(),
      createdAt: new Date().toISOString(),
      digest: `sha256:${digest.digest("hex")}`,
    });
  }

  async restore(source: string): Promise<BridgeRestoreReport> {
    if (this.#closed) throw error("SQLITE_ADAPTER_CLOSED");
    if (typeof source !== "string" || source.length === 0 || source === this.databasePath) {
      throw error("SQLITE_RESTORE_SOURCE_INVALID");
    }
    const sourceStat = await stat(source);
    if (!sourceStat.isFile()) throw error("SQLITE_RESTORE_SOURCE_INVALID");
    const digest = createHash("sha256");
    await digestWrite(source, digest);
    const digestText = `sha256:${digest.digest("hex")}`;
    const staged = `${this.databasePath}.restore-${randomUUID()}`;
    const stagedOld = `${this.databasePath}.old-${randomUUID()}`;
    await backup(new DatabaseSync(source, { readOnly: true }), staged);
    const verify = new DatabaseSync(staged, { readOnly: true });
    const check = (verify.prepare("PRAGMA quick_check").get() as { quick_check?: string } | undefined)?.quick_check;
    verify.close();
    if (check !== "ok") {
      await rm(staged, { force: true });
      throw error("SQLITE_RESTORE_CONTENT_INVALID");
    }
    this.#database.close();
    try {
      // DatabaseSync close checkpoints committed WAL frames. Remove the old
      // sidecars before publication so they cannot be mistaken for the newly
      // restored database's WAL.
      await rm(`${this.databasePath}-wal`, { force: true });
      await rm(`${this.databasePath}-shm`, { force: true });
      await rename(this.databasePath, stagedOld);
      await rename(staged, this.databasePath);
      this.#database.open();
      const version = await this.schemaVersion();
      if (version !== SCHEMA_VERSION) throw error("SQLITE_RESTORE_SCHEMA_INVALID");
      await rm(stagedOld, { force: true });
      return Object.freeze({ restored: true, schemaVersion: version, digest: digestText });
    } catch (caught) {
      try { this.#database.close(); } catch { /* already closed */ }
      try { await rename(stagedOld, this.databasePath); } catch { /* rollback best effort */ }
      try { this.#database.open(); } catch { /* original constructor failure is reported below */ }
      throw caught;
    }
  }

  async recover(): Promise<BridgeRecoveryReport> {
    const directory = dirname(this.databasePath);
    const base = this.databasePath.slice(directory.length + 1);
    const entries = new Set((await readdir(directory).catch(() => []))
      .filter((name) => name.startsWith(`${base}.restore-`) || name.startsWith(`${base}.old-`)));
    const obsolete = [...entries].sort();
    // If publication stopped after moving the old main database but before
    // publishing the staged restore, that old file is the only valid database.
    // Otherwise both classes of staged/old leftovers are safe to discard.
    let repaired = false;
    let currentStat = await stat(this.databasePath).catch(() => null);
    if (currentStat === null && obsolete.some((name) => name.startsWith(`${base}.old-`))) {
      const survivor = obsolete.filter((name) => name.startsWith(`${base}.old-`)).at(-1)!;
      this.#database.close();
      await rename(join(directory, survivor), this.databasePath);
      this.#database.open();
      currentStat = await stat(this.databasePath);
      entries.delete(survivor);
      repaired = true;
    }
    if (!currentStat?.isFile()) throw error("SQLITE_RECOVERY_FAILED");
    for (const name of entries) await rm(join(directory, name), { force: true });
    const quick = (this.#database.prepare("PRAGMA quick_check").get() as { quick_check?: string } | undefined)?.quick_check;
    if (quick !== "ok") throw error("SQLITE_RECOVERY_FAILED");
    const foreign = this.#database.prepare("PRAGMA foreign_key_check").get() as Record<string, unknown> | undefined;
    if (foreign !== undefined) throw error("SQLITE_RECOVERY_FAILED");
    return Object.freeze({
      recovered: true,
      schemaVersion: await this.schemaVersion(),
      repaired,
      discardedArtifacts: Object.freeze([...entries].sort()),
    });
  }

  async close(): Promise<void> {
    if (this.#closed) return;
    await this.#queue.catch(() => undefined);
    this.#database.close();
    this.#closed = true;
  }
}

const digestWrite = async (path: string, digest: ReturnType<typeof createHash>): Promise<void> => {
  digest.update(await readFile(path));
};

export const isNodeSqliteLeaseCoordinator = (value: unknown): boolean => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const brand = (value as Record<symbol, unknown>)[NODE_SQLITE_LEASE_COORDINATOR];
  return brand instanceof NodeSqliteBridgeAdapter;
};

export const openNodeSqliteBridgeAdapter = NodeSqliteBridgeAdapter.open;
