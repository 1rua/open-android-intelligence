import { open, mkdir, readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import { resolve, join } from "node:path";
import {
  BRIDGE_STORE_NAMESPACES,
  DURABLE_BRIDGE_STORE_PORT,
  type BridgeStoreNamespace,
  type DurableBridgeEntry,
  type DurableBridgeStore,
  type DurableBridgeTransaction,
} from "../../bridge-contract/src/durable-store.js";
import { BridgeServiceError, compareCodePoints } from "../../bridge-contract/src/service-types.js";

/**
 * File format owned by this deterministic local adapter. This is deliberately
 * distinct from the Bridge contract port: the port is the compatibility
 * boundary, while this format is only a reproducible fixture/development
 * implementation of that boundary.
 */
export const FILE_BACKED_BRIDGE_STORE_FORMAT = "open-android-intelligence.bridge-store.file.v1" as const;
// Version 3 adds replay associations to the closed namespace set. This local
// fixture upgrades v1/v2 snapshots by initializing new partitions; it still
// makes no production migration or multi-process database claim.
export const FILE_BACKED_BRIDGE_STORE_VERSION = 3 as const;

const STATE_FORMAT = "open-android-intelligence.bridge-store.state.v3" as const;
const LEGACY_STATE_FORMAT_V2 = "open-android-intelligence.bridge-store.state.v2" as const;
const LEGACY_STATE_FORMAT_V1 = "open-android-intelligence.bridge-store.state.v1" as const;
const MANIFEST_FILE = "manifest.json" as const;
const GENERATIONS_DIR = "generations" as const;
const SCOPE_PATTERN = /^[a-z0-9][a-z0-9._/-]{0,127}$/;
const STATE_FILE_PATTERN = /^state-([0-9]+)\.json$/;

type JsonPrimitive = null | boolean | number | string;
type JsonValue = JsonPrimitive | JsonValue[] | { readonly [key: string]: JsonValue };
type StateMap = Map<BridgeStoreNamespace, Map<string, JsonValue>>;

export type FileBackedBridgeManifest = Readonly<{
  format: typeof FILE_BACKED_BRIDGE_STORE_FORMAT;
  version: typeof FILE_BACKED_BRIDGE_STORE_VERSION;
  generation: number;
  stateFile: string;
  committedAt: string;
}>;

export type FileBackedRecoveryReport = Readonly<{
  generation: number;
  manifestVersion: typeof FILE_BACKED_BRIDGE_STORE_VERSION;
  repaired: boolean;
  discardedGenerations: readonly number[];
  removedTempArtifacts: number;
}>;

export type FileBackedBridgeStoreOptions = Readonly<{
  /** Directory owned exclusively by this adapter. It must not be a shared DB path. */
  rootDir: string;
  /** Injected clock keeps tests deterministic; production deployment must choose its own clock policy. */
  now?: () => Date;
}>;

type ParsedGeneration = Readonly<{
  generation: number;
  state: StateMap;
  stateFile: string;
}>;

const namespaceSet = new Set<string>(BRIDGE_STORE_NAMESPACES);

const durableError = (code: string): BridgeServiceError => new BridgeServiceError(code);

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

function assertNamespace(value: unknown): asserts value is BridgeStoreNamespace {
  if (typeof value !== "string" || !namespaceSet.has(value)) throw durableError("DURABLE_NAMESPACE_INVALID");
}

function assertKey(value: unknown): asserts value is string {
  if (typeof value !== "string") throw durableError("DURABLE_KEY_INVALID");
}

/**
 * Clone and validate the JSON subset accepted by the file format. Rejecting
 * richer JavaScript values is important: JSON.stringify would otherwise turn
 * `undefined`, NaN, BigInt, class instances, or cyclic values into a lossy
 * snapshot while still reporting a successful transaction.
 */
const cloneJson = (value: unknown, seen = new WeakSet<object>()): JsonValue => {
  if (value === null || typeof value === "string" || typeof value === "boolean") return value;
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw durableError("DURABLE_VALUE_INVALID");
    return Object.is(value, -0) ? 0 : value;
  }
  if (typeof value !== "object" || value === null) throw durableError("DURABLE_VALUE_INVALID");
  if (seen.has(value)) throw durableError("DURABLE_VALUE_INVALID");
  seen.add(value);
  let result: JsonValue;
  if (Array.isArray(value)) {
    result = value.map((item) => cloneJson(item, seen));
  } else {
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) throw durableError("DURABLE_VALUE_INVALID");
    const output: Record<string, JsonValue> = Object.create(null) as Record<string, JsonValue>;
    const record = value as Record<string, unknown>;
    for (const key of Object.keys(record).sort(compareCodePoints)) {
      Object.defineProperty(output, key, {
        configurable: true,
        enumerable: true,
        value: cloneJson(record[key], seen),
        writable: true,
      });
    }
    result = output;
  }
  seen.delete(value);
  return result;
};

const cloneState = (state: StateMap): StateMap => {
  const copy: StateMap = new Map();
  for (const namespace of BRIDGE_STORE_NAMESPACES) {
    const entries = new Map<string, JsonValue>();
    for (const [key, value] of state.get(namespace) ?? []) entries.set(key, cloneJson(value));
    copy.set(namespace, entries);
  }
  return copy;
};

const emptyState = (): StateMap => {
  const state: StateMap = new Map();
  for (const namespace of BRIDGE_STORE_NAMESPACES) state.set(namespace, new Map());
  return state;
};

const stateFileFor = (generation: number): string =>
  `${GENERATIONS_DIR}/state-${String(generation).padStart(20, "0")}.json`;

const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort(compareCodePoints);
  const expected = [...keys].sort(compareCodePoints);
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};

const parseManifest = (value: unknown): FileBackedBridgeManifest | null => {
  if (!isObject(value) || !exactKeys(value, ["committedAt", "format", "generation", "stateFile", "version"])) return null;
  if (value.format !== FILE_BACKED_BRIDGE_STORE_FORMAT || value.version !== FILE_BACKED_BRIDGE_STORE_VERSION) return null;
  if (typeof value.generation !== "number" || !Number.isSafeInteger(value.generation) || value.generation < 0) return null;
  if (typeof value.stateFile !== "string" || value.stateFile !== stateFileFor(value.generation)) return null;
  if (typeof value.committedAt !== "string" || Number.isNaN(Date.parse(value.committedAt))) return null;
  return Object.freeze({
    format: FILE_BACKED_BRIDGE_STORE_FORMAT,
    version: FILE_BACKED_BRIDGE_STORE_VERSION,
    generation: value.generation,
    stateFile: value.stateFile,
    committedAt: value.committedAt,
  });
};

const parseState = (value: unknown, expectedGeneration: number): StateMap | null => {
  if (!isObject(value) || !exactKeys(value, ["format", "generation", "namespaces", "version"])) return null;
  const isCurrent = value.format === STATE_FORMAT && value.version === FILE_BACKED_BRIDGE_STORE_VERSION;
  const isLegacyV2 = value.format === LEGACY_STATE_FORMAT_V2 && value.version === 2;
  const isLegacyV1 = value.format === LEGACY_STATE_FORMAT_V1 && value.version === 1;
  if (!isCurrent && !isLegacyV2 && !isLegacyV1) return null;
  if (value.generation !== expectedGeneration) return null;
  const expectedNamespaces = BRIDGE_STORE_NAMESPACES.filter((namespace) =>
    (!isLegacyV1 || (namespace !== "authorization.grants" && namespace !== "authorization.revisions"))
    && (!(isLegacyV1 || isLegacyV2) || namespace !== "operation.replay-associations"));
  if (!isObject(value.namespaces) || !exactKeys(value.namespaces, expectedNamespaces)) return null;

  const state = emptyState();
  for (const namespace of expectedNamespaces) {
    const rawEntries = value.namespaces[namespace];
    if (!Array.isArray(rawEntries)) return null;
    const entries = state.get(namespace)!;
    for (const rawEntry of rawEntries) {
      if (!isObject(rawEntry) || !exactKeys(rawEntry, ["key", "value"]) || typeof rawEntry.key !== "string") return null;
      if (entries.has(rawEntry.key)) return null;
      try {
        entries.set(rawEntry.key, cloneJson(rawEntry.value));
      } catch {
        return null;
      }
    }
  }
  return state;
};

const serializeState = (state: StateMap, generation: number): string => {
  const namespaces: Record<string, unknown> = Object.create(null) as Record<string, unknown>;
  for (const namespace of BRIDGE_STORE_NAMESPACES) {
    const entries = [...(state.get(namespace) ?? new Map())]
      .sort(([left], [right]) => compareCodePoints(left, right))
      .map(([key, value]) => ({ key, value: cloneJson(value) }));
    Object.defineProperty(namespaces, namespace, {
      configurable: true,
      enumerable: true,
      value: entries,
      writable: true,
    });
  }
  return `${JSON.stringify({
    format: STATE_FORMAT,
    version: FILE_BACKED_BRIDGE_STORE_VERSION,
    generation,
    namespaces,
  }, null, 2)}\n`;
};

const isNotFound = (error: unknown): boolean =>
  typeof error === "object" && error !== null && "code" in error && (error as { code?: unknown }).code === "ENOENT";

const readOptional = async (path: string): Promise<string | null> => {
  try {
    return await readFile(path, "utf8");
  } catch (error) {
    if (isNotFound(error)) return null;
    throw error;
  }
};

/**
 * Deterministic fs/promises adapter for local tests and development.
 *
 * A generation is published by writing a complete immutable state file to a
 * temporary sibling and renaming it, then writing/renaming the manifest
 * pointer. The manifest is the publication point: an orphan state file left by
 * a crash before pointer publication is never observed and is removed on
 * recovery. This adapter intentionally provides no network, authentication,
 * database, migration, health, or deployment semantics.
 */
export class FileBackedBridgeStore implements DurableBridgeStore {
  readonly port = DURABLE_BRIDGE_STORE_PORT;
  readonly durability = "durable" as const;

  readonly #rootDir: string;
  readonly #now: () => Date;
  #manifest!: FileBackedBridgeManifest;
  #state: StateMap = emptyState();
  #tail: Promise<unknown> = Promise.resolve();
  #transactionSequence = 0;
  #writeSequence = 0;

  private constructor(options: FileBackedBridgeStoreOptions) {
    if (typeof options.rootDir !== "string" || options.rootDir.length === 0) throw durableError("DURABLE_ROOT_INVALID");
    this.#rootDir = resolve(options.rootDir);
    this.#now = options.now ?? (() => new Date());
  }

  static async open(options: FileBackedBridgeStoreOptions): Promise<FileBackedBridgeStore> {
    const store = new FileBackedBridgeStore(options);
    await mkdir(join(store.#rootDir, GENERATIONS_DIR), { recursive: true });
    await store.#recoverLocked();
    return store;
  }

  /** Convenience factory with the same explicit local-only semantics. */
  static create(options: FileBackedBridgeStoreOptions): Promise<FileBackedBridgeStore> {
    return FileBackedBridgeStore.open(options);
  }

  /** Read the currently published manifest without exposing internal state. */
  async manifest(): Promise<FileBackedBridgeManifest> {
    return Object.freeze({ ...this.#manifest });
  }

  /** Read-only convenience scan for local diagnostics; transactions remain the
   * only mutation boundary exposed by the DurableBridgeStore port. */
  async scan(namespace: BridgeStoreNamespace): Promise<readonly DurableBridgeEntry[]> {
    const run = this.#tail.then(() => {
      assertNamespace(namespace);
      return Object.freeze([...this.#state.get(namespace)!]
        .sort(([left], [right]) => compareCodePoints(left, right))
        .map(([key, value]) => Object.freeze({ key, value: cloneJson(value) })));
    });
    this.#tail = run.then(() => undefined, () => undefined);
    return run;
  }

  /**
   * Reconcile the manifest and generation directory after a process crash.
   * Recovery is serialized with transactions and never promotes an orphan
   * generation while a valid manifest still points at an older generation.
   */
  async recover(): Promise<FileBackedRecoveryReport> {
    const run = this.#tail.then(() => this.#recoverLocked());
    this.#tail = run.then(() => undefined, () => undefined);
    return run;
  }

  transact<T>(
    scope: string,
    work: (transaction: DurableBridgeTransaction) => Promise<T> | T,
  ): Promise<T> {
    if (typeof scope !== "string" || !SCOPE_PATTERN.test(scope)) {
      return Promise.reject(durableError("TRANSACTION_SCOPE_INVALID"));
    }
    if (typeof work !== "function") return Promise.reject(durableError("TRANSACTION_WORK_INVALID"));

    const run = this.#tail.then(() => this.#transactLocked(scope, work));
    this.#tail = run.then(() => undefined, () => undefined);
    return run;
  }

  async #transactLocked<T>(scope: string, work: (transaction: DurableBridgeTransaction) => Promise<T> | T): Promise<T> {
    const staged = cloneState(this.#state);
    let dirty = false;
    const transaction: DurableBridgeTransaction = Object.freeze({
      // A monotonic process-local ID keeps SDK-free traces reproducible. The
      // ID is an audit correlation hint, not an authentication credential.
      transactionId: `${scope}:${String(++this.#transactionSequence).padStart(8, "0")}`,
      read: async (namespace: BridgeStoreNamespace, key: string): Promise<unknown | null> => {
        assertNamespace(namespace);
        assertKey(key);
        const value = staged.get(namespace)?.get(key);
        return value === undefined ? null : cloneJson(value);
      },
      scan: async (namespace: BridgeStoreNamespace): Promise<readonly DurableBridgeEntry[]> => {
        assertNamespace(namespace);
        return Object.freeze([...staged.get(namespace)!]
          .sort(([left], [right]) => compareCodePoints(left, right))
          .map(([key, value]) => Object.freeze({ key, value: cloneJson(value) })));
      },
      write: async (namespace: BridgeStoreNamespace, key: string, value: unknown): Promise<void> => {
        assertNamespace(namespace);
        assertKey(key);
        staged.get(namespace)!.set(key, cloneJson(value));
        dirty = true;
      },
      remove: async (namespace: BridgeStoreNamespace, key: string): Promise<void> => {
        assertNamespace(namespace);
        assertKey(key);
        dirty = staged.get(namespace)!.delete(key) || dirty;
      },
    });

    const result = await work(transaction);
    if (dirty) {
      const generation = this.#manifest.generation + 1;
      await this.#publish(staged, generation);
      this.#state = staged;
    }
    return result;
  }

  async #publish(state: StateMap, generation: number): Promise<void> {
    const stateFile = stateFileFor(generation);
    const statePath = join(this.#rootDir, stateFile);
    await mkdir(join(this.#rootDir, GENERATIONS_DIR), { recursive: true });
    await this.#atomicWrite(statePath, serializeState(state, generation));
    const manifest: FileBackedBridgeManifest = Object.freeze({
      format: FILE_BACKED_BRIDGE_STORE_FORMAT,
      version: FILE_BACKED_BRIDGE_STORE_VERSION,
      generation,
      stateFile,
      committedAt: this.#now().toISOString(),
    });
    await this.#atomicWrite(join(this.#rootDir, MANIFEST_FILE), `${JSON.stringify(manifest, null, 2)}\n`);
    this.#manifest = manifest;
  }

  async #atomicWrite(path: string, content: string): Promise<void> {
    const temporary = `${path}.tmp-${String(++this.#writeSequence).padStart(8, "0")}`;
    let handle: Awaited<ReturnType<typeof open>> | undefined;
    try {
      handle = await open(temporary, "wx");
      await handle.writeFile(content, "utf8");
      await handle.sync();
      await handle.close();
      handle = undefined;
      await rename(temporary, path);
    } catch (error) {
      if (handle !== undefined) await handle.close().catch(() => undefined);
      await rm(temporary, { force: true }).catch(() => undefined);
      throw error;
    }
  }

  async #recoverLocked(): Promise<FileBackedRecoveryReport> {
    await mkdir(join(this.#rootDir, GENERATIONS_DIR), { recursive: true });
    const generationFiles = await readdir(join(this.#rootDir, GENERATIONS_DIR), { withFileTypes: true });
    const parsed: ParsedGeneration[] = [];
    const temporaryPaths: string[] = [];
    const invalidGenerationPaths: string[] = [];
    for (const entry of generationFiles) {
      if (!entry.isFile()) continue;
      if (entry.name.includes(".tmp-")) {
        temporaryPaths.push(join(this.#rootDir, GENERATIONS_DIR, entry.name));
        continue;
      }
      const match = STATE_FILE_PATTERN.exec(entry.name);
      if (!match) continue;
      const rawGeneration = Number(match[1]);
      if (!Number.isSafeInteger(rawGeneration) || rawGeneration < 0) {
        invalidGenerationPaths.push(join(this.#rootDir, GENERATIONS_DIR, entry.name));
        continue;
      }
      const generationPath = join(this.#rootDir, GENERATIONS_DIR, entry.name);
      const raw = await readOptional(generationPath);
      if (raw === null) continue;
      try {
        const state = parseState(JSON.parse(raw), rawGeneration);
        if (state !== null) parsed.push({ generation: rawGeneration, state, stateFile: `${GENERATIONS_DIR}/${entry.name}` });
        else invalidGenerationPaths.push(generationPath);
      } catch {
        // Corrupt generations are discarded below; a valid manifest/generation
        // remains authoritative if one exists.
        invalidGenerationPaths.push(generationPath);
      }
    }

    const manifestPath = join(this.#rootDir, MANIFEST_FILE);
    const rawManifest = await readOptional(manifestPath);
    let manifest: FileBackedBridgeManifest | null = null;
    if (rawManifest !== null) {
      try {
        manifest = parseManifest(JSON.parse(rawManifest));
      } catch {
        manifest = null;
      }
    }
    const manifestGeneration = manifest === null ? undefined : parsed.find((candidate) =>
      candidate.generation === manifest!.generation && candidate.stateFile === manifest!.stateFile);
    const selected = manifestGeneration ?? [...parsed].sort((left, right) => left.generation - right.generation).at(-1);
    const repaired = manifestGeneration === undefined;
    let selectedGeneration: ParsedGeneration;
    if (selected === undefined) {
      selectedGeneration = { generation: 0, state: emptyState(), stateFile: stateFileFor(0) };
      await this.#publish(selectedGeneration.state, selectedGeneration.generation);
    } else {
      selectedGeneration = selected;
      if (repaired) {
        await this.#publish(selected.state, selected.generation);
      } else {
        this.#manifest = manifest!;
        this.#state = cloneState(selected.state);
      }
    }
    this.#state = cloneState(selectedGeneration.state);
    if (this.#manifest === undefined || this.#manifest.generation !== selectedGeneration.generation) {
      // #publish above sets the manifest; this branch only protects the type
      // invariant if recovery logic changes in the future.
      this.#manifest = Object.freeze({
        format: FILE_BACKED_BRIDGE_STORE_FORMAT,
        version: FILE_BACKED_BRIDGE_STORE_VERSION,
        generation: selectedGeneration.generation,
        stateFile: selectedGeneration.stateFile,
        committedAt: this.#now().toISOString(),
      });
    }

    let removedTempArtifacts = 0;
    for (const path of temporaryPaths) {
      await rm(path, { force: true });
      removedTempArtifacts += 1;
    }
    for (const path of invalidGenerationPaths) await rm(path, { force: true });
    const rootEntries = await readdir(this.#rootDir, { withFileTypes: true });
    for (const entry of rootEntries) {
      if (entry.isFile() && (entry.name === MANIFEST_FILE || entry.name.includes(".tmp-"))) {
        if (entry.name.includes(".tmp-")) {
          await rm(join(this.#rootDir, entry.name), { force: true });
          removedTempArtifacts += 1;
        }
      }
    }

    const discardedGenerations: number[] = [];
    for (const candidate of parsed) {
      if (candidate.generation === selectedGeneration.generation && candidate.stateFile === selectedGeneration.stateFile) continue;
      await rm(join(this.#rootDir, candidate.stateFile), { force: true });
      discardedGenerations.push(candidate.generation);
    }
    return Object.freeze({
      generation: selectedGeneration.generation,
      manifestVersion: FILE_BACKED_BRIDGE_STORE_VERSION,
      repaired,
      discardedGenerations: Object.freeze(discardedGenerations.sort((left, right) => left - right)),
      removedTempArtifacts,
    });
  }
}

export const openFileBackedBridgeStore = (options: FileBackedBridgeStoreOptions): Promise<FileBackedBridgeStore> =>
  FileBackedBridgeStore.open(options);

export const createFileBackedBridgeStore = openFileBackedBridgeStore;
