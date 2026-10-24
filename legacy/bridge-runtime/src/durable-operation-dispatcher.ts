import {
  assertDurableBridgeStore,
  runDurableBridgeTransaction,
  type DurableBridgeStore,
} from "../../bridge-contract/src/durable-store.js";
import {
  BridgeServiceError,
  type OperationDispatcherPort,
  type OperationRequest,
} from "../../bridge-contract/src/operation-dispatch.js";
import { sessionKey } from "../../bridge-contract/src/service-types.js";

export const DURABLE_OPERATION_DISPATCHER_PORT = "open-android-intelligence.operation-dispatcher.v1" as const;
const OPERATION_NAMESPACE = "operation.claims" as const;
const REPLAY_ASSOCIATION_NAMESPACE = "operation.replay-associations" as const;
const OPERATION_SCOPE = "operation.claim" as const;

type JsonValue = null | boolean | number | string | JsonValue[] | { readonly [key: string]: JsonValue };
const BIGINT_TAG = "$openAndroidIntelligenceType" as const;
const BIGINT_KIND = "bigint" as const;

type StoredOperation = Readonly<{
  sessionKey: string;
  parametersDigest: string;
  status: "pending" | "completed";
  claims: number;
  result?: unknown;
}>;

type StoredReplayAssociation = Readonly<{
  operationId: string;
  sessionKey: string;
  payloadDigest: string;
}>;

export type ReplayAssociationInput = Readonly<{
  replayKey: string;
  payloadDigest: string;
}>;

export type DurableOperationDispatcherOptions = Readonly<{
  store: DurableBridgeStore;
}>;

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const encodeValue = (value: unknown, seen = new WeakSet<object>()): JsonValue => {
  if (typeof value === "bigint") return Object.freeze({ [BIGINT_TAG]: BIGINT_KIND, value: value.toString() });
  if (value === null || typeof value === "string" || typeof value === "boolean") return value;
  if (typeof value === "number" && Number.isFinite(value)) return Object.is(value, -0) ? 0 : value;
  if (typeof value !== "object" || value === null) throw new BridgeServiceError("DURABLE_OPERATION_RESULT_INVALID");
  if (seen.has(value)) throw new BridgeServiceError("DURABLE_OPERATION_RESULT_INVALID");
  seen.add(value);
  let encoded: JsonValue;
  if (Array.isArray(value)) {
    encoded = value.map((item) => encodeValue(item, seen));
  } else {
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) throw new BridgeServiceError("DURABLE_OPERATION_RESULT_INVALID");
    const output: Record<string, JsonValue> = {};
    for (const key of Object.keys(value).sort()) {
      if (key === BIGINT_TAG) throw new BridgeServiceError("DURABLE_OPERATION_RESULT_INVALID");
      output[key] = encodeValue((value as Record<string, unknown>)[key], seen);
    }
    encoded = Object.freeze(output);
  }
  seen.delete(value);
  return encoded;
};

const decodeValue = (value: unknown, seen = new WeakSet<object>()): unknown => {
  if (value === null || typeof value === "string" || typeof value === "boolean") return value;
  if (typeof value === "number" && Number.isFinite(value)) return Object.is(value, -0) ? 0 : value;
  if (typeof value !== "object" || value === null || seen.has(value)) throw new BridgeServiceError("DURABLE_OPERATION_STATE_INVALID");
  seen.add(value);
  let decoded: unknown;
  if (Array.isArray(value)) {
    decoded = value.map((item) => decodeValue(item, seen));
  } else {
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) throw new BridgeServiceError("DURABLE_OPERATION_STATE_INVALID");
    const keys = Object.keys(value);
    if (keys.includes(BIGINT_TAG)) {
      if (keys.length !== 2 || (value as Record<string, unknown>)[BIGINT_TAG] !== BIGINT_KIND || typeof (value as Record<string, unknown>).value !== "string" || !/^-?(?:0|[1-9][0-9]*)$/.test((value as Record<string, unknown>).value as string)) {
        throw new BridgeServiceError("DURABLE_OPERATION_STATE_INVALID");
      }
      try {
        decoded = BigInt((value as Record<string, unknown>).value as string);
      } catch {
        throw new BridgeServiceError("DURABLE_OPERATION_STATE_INVALID");
      }
    } else {
      const output: Record<string, unknown> = {};
      for (const key of keys) output[key] = decodeValue((value as Record<string, unknown>)[key], seen);
      decoded = Object.freeze(output);
    }
  }
  seen.delete(value);
  return decoded;
};

function assertOperationId(operationId: unknown): asserts operationId is string {
  if (typeof operationId !== "string" || operationId.length === 0) throw new BridgeServiceError("OPERATION_ID_INVALID");
}

const assertStoredOperation = (key: string, value: unknown): StoredOperation => {
  if (!isRecord(value)) throw new BridgeServiceError("DURABLE_OPERATION_STATE_INVALID");
  const allowed = new Set(["claims", "parametersDigest", "result", "sessionKey", "status"]);
  if (Object.keys(value).some((field) => !allowed.has(field))) throw new BridgeServiceError("DURABLE_OPERATION_STATE_INVALID");
  if (typeof value.sessionKey !== "string" || typeof value.parametersDigest !== "string") {
    throw new BridgeServiceError("DURABLE_OPERATION_STATE_INVALID");
  }
  if (value.status !== "pending" && value.status !== "completed") throw new BridgeServiceError("DURABLE_OPERATION_STATE_INVALID");
  const claims = value.claims;
  if (typeof claims !== "number" || !Number.isSafeInteger(claims) || claims < 1) throw new BridgeServiceError("DURABLE_OPERATION_STATE_INVALID");
  const result = "result" in value ? decodeValue(value.result) : undefined;
  assertOperationId(key);
  return Object.freeze({
    sessionKey: value.sessionKey,
    parametersDigest: value.parametersDigest,
    status: value.status,
    claims,
    ...(Object.prototype.hasOwnProperty.call(value, "result") ? { result } : {}),
  });
};

const assertReplayAssociationInput = (value: ReplayAssociationInput): void => {
  if (!isRecord(value) || typeof value.replayKey !== "string" || value.replayKey.length === 0
    || typeof value.payloadDigest !== "string" || value.payloadDigest.length === 0
    || Object.keys(value).some((field) => field !== "replayKey" && field !== "payloadDigest")) {
    throw new BridgeServiceError("REPLAY_ASSOCIATION_INVALID");
  }
};

const assertStoredReplayAssociation = (key: string, value: unknown): StoredReplayAssociation => {
  if (typeof key !== "string" || key.length === 0 || !isRecord(value)) {
    throw new BridgeServiceError("DURABLE_REPLAY_ASSOCIATION_STATE_INVALID");
  }
  const allowed = new Set(["operationId", "payloadDigest", "sessionKey"]);
  if (Object.keys(value).length !== allowed.size || Object.keys(value).some((field) => !allowed.has(field))
    || typeof value.operationId !== "string" || value.operationId.length === 0
    || typeof value.sessionKey !== "string" || value.sessionKey.length === 0
    || typeof value.payloadDigest !== "string" || value.payloadDigest.length === 0) {
    throw new BridgeServiceError("DURABLE_REPLAY_ASSOCIATION_STATE_INVALID");
  }
  return Object.freeze({
    operationId: value.operationId,
    sessionKey: value.sessionKey,
    payloadDigest: value.payloadDigest,
  });
};

const cloneStored = (value: StoredOperation): StoredOperation => Object.freeze({
  ...value,
  ...(Object.prototype.hasOwnProperty.call(value, "result") ? { result: value.result } : {}),
});

/**
 * Durable operation claim/result ledger used by the Bridge composition root.
 *
 * It persists `operation.claims` and optional
 * `operation.replay-associations` through the reviewed `DurableBridgeStore`
 * port. The action itself remains outside the transaction; callers must
 * therefore make external side effects idempotent or use an outbox. This class
 * does not provide network, authentication, or a database.
 */
export class DurableOperationDispatcher implements OperationDispatcherPort {
  readonly port = DURABLE_OPERATION_DISPATCHER_PORT;
  readonly durability = "durable" as const;

  readonly #store: DurableBridgeStore;
  readonly #operations = new Map<string, StoredOperation>();

  private constructor(store: DurableBridgeStore) {
    this.#store = assertDurableBridgeStore(store);
  }

  static async open(options: DurableOperationDispatcherOptions): Promise<DurableOperationDispatcher> {
    const dispatcher = new DurableOperationDispatcher(options.store);
    await runDurableBridgeTransaction(dispatcher.#store, "operation.replay.validate", async (transaction) => {
      for (const entry of await transaction.scan(REPLAY_ASSOCIATION_NAMESPACE)) {
        assertStoredReplayAssociation(entry.key, entry.value);
      }
    });
    const entries = await dispatcher.#scan();
    for (const entry of entries) {
      const operation = assertStoredOperation(entry.key, entry.value);
      if (operation.status === "pending") {
        // A pending claim has no committed result and is recoverable after a
        // process restart. The local adapter has no multi-process lease; the
        // production composition must add ownership fencing before sharing a
        // namespace between live workers.
        await runDurableBridgeTransaction(dispatcher.#store, "operation.recover", async (transaction) => {
          const current = await transaction.read(OPERATION_NAMESPACE, entry.key);
          if (current === null) return;
          const latest = assertStoredOperation(entry.key, current);
          if (latest.status === "pending") await transaction.remove(OPERATION_NAMESPACE, entry.key);
        });
      } else {
        dispatcher.#operations.set(entry.key, operation);
      }
    }
    return dispatcher;
  }

  async execute<T>(request: OperationRequest, action: () => Promise<T> | T): Promise<T> {
    return this.#execute(request, undefined, action);
  }

  /**
   * Claims an operation and binds its authenticated replay key in the same
   * durable transaction. The external side effect remains outside that
   * transaction and must follow the operation recovery policy.
   */
  async executeWithReplay<T>(
    request: OperationRequest,
    association: ReplayAssociationInput,
    action: () => Promise<T> | T,
  ): Promise<T> {
    assertReplayAssociationInput(association);
    return this.#execute(request, association, action);
  }

  async #execute<T>(request: OperationRequest, association: ReplayAssociationInput | undefined, action: () => Promise<T> | T): Promise<T> {
    assertOperationId(request.operationId);
    if (typeof action !== "function") throw new BridgeServiceError("OPERATION_ACTION_INVALID");
    const claim = await this.#begin(request, association);
    if (claim.existing) return claim.result as T;
    try {
      const result = await action();
      return await this.#complete(request, result);
    } catch (error) {
      await this.#release(request).catch(() => undefined);
      throw error;
    }
  }

  claims(): readonly Readonly<{ operationId: string; claims: number }>[] {
    return Object.freeze([...this.#operations]
      .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
      .map(([operationId, operation]) => Object.freeze({ operationId, claims: operation.claims })));
  }

  async #begin(request: OperationRequest, association?: ReplayAssociationInput): Promise<Readonly<{ existing: boolean; result?: unknown }>> {
    const requestedSessionKey = sessionKey(request.session);
    const parametersDigest = this.#parametersDigest(request.parameters ?? null);
    const outcome = await runDurableBridgeTransaction(this.#store, OPERATION_SCOPE, async (transaction) => {
      if (association !== undefined) {
        const rawAssociation = await transaction.read(REPLAY_ASSOCIATION_NAMESPACE, association.replayKey);
        if (rawAssociation !== null) {
          const previousAssociation = assertStoredReplayAssociation(association.replayKey, rawAssociation);
          if (previousAssociation.sessionKey !== requestedSessionKey) throw new BridgeServiceError("REPLAY_ASSOCIATION_IDENTITY_MISMATCH");
          if (previousAssociation.operationId !== request.operationId) throw new BridgeServiceError("REPLAY_ASSOCIATION_OPERATION_MISMATCH");
          if (previousAssociation.payloadDigest !== association.payloadDigest) throw new BridgeServiceError("REPLAY_ASSOCIATION_DIGEST_MISMATCH");
        } else {
          await transaction.write(REPLAY_ASSOCIATION_NAMESPACE, association.replayKey, {
            operationId: request.operationId,
            sessionKey: requestedSessionKey,
            payloadDigest: association.payloadDigest,
          });
        }
      }
      const raw = await transaction.read(OPERATION_NAMESPACE, request.operationId);
      if (raw !== null) {
        const previous = assertStoredOperation(request.operationId, raw);
        if (previous.sessionKey !== requestedSessionKey) throw new BridgeServiceError("OPERATION_IDENTITY_MISMATCH");
        if (previous.parametersDigest !== parametersDigest) throw new BridgeServiceError("OPERATION_PARAMETERS_MISMATCH");
        if (previous.status === "completed") return Object.freeze({ existing: true, ...(Object.hasOwn(previous, "result") ? { result: previous.result } : {}) });
        throw new BridgeServiceError("OPERATION_IN_PROGRESS");
      }
      const pending: StoredOperation = Object.freeze({ sessionKey: requestedSessionKey, parametersDigest, status: "pending", claims: 1 });
      await transaction.write(OPERATION_NAMESPACE, request.operationId, pending);
      return Object.freeze({ existing: false });
    });
    const current = await this.#read(request.operationId);
    if (current !== null) this.#operations.set(request.operationId, current);
    return outcome;
  }

  async #complete<T>(request: OperationRequest, result: T): Promise<T> {
    const encodedResult = encodeValue(result);
    const requestedSessionKey = sessionKey(request.session);
    const parametersDigest = this.#parametersDigest(request.parameters ?? null);
    const outcome = await runDurableBridgeTransaction(this.#store, OPERATION_SCOPE, async (transaction) => {
      const raw = await transaction.read(OPERATION_NAMESPACE, request.operationId);
      if (raw === null) throw new BridgeServiceError("OPERATION_CLAIM_MISSING");
      const previous = assertStoredOperation(request.operationId, raw);
      if (previous.sessionKey !== requestedSessionKey) throw new BridgeServiceError("OPERATION_IDENTITY_MISMATCH");
      if (previous.parametersDigest !== parametersDigest) throw new BridgeServiceError("OPERATION_PARAMETERS_MISMATCH");
      if (previous.status === "completed") return Object.freeze({ result: previous.result });
      const completed: StoredOperation = Object.freeze({ ...previous, status: "completed", result: encodedResult });
      await transaction.write(OPERATION_NAMESPACE, request.operationId, completed);
      return Object.freeze({ result });
    });
    const current = await this.#read(request.operationId);
    if (current !== null) this.#operations.set(request.operationId, current);
    return outcome.result as T;
  }

  async #release(request: OperationRequest): Promise<void> {
    const requestedSessionKey = sessionKey(request.session);
    const parametersDigest = this.#parametersDigest(request.parameters ?? null);
    await runDurableBridgeTransaction(this.#store, OPERATION_SCOPE, async (transaction) => {
      const raw = await transaction.read(OPERATION_NAMESPACE, request.operationId);
      if (raw === null) return;
      const previous = assertStoredOperation(request.operationId, raw);
      if (previous.sessionKey !== requestedSessionKey) throw new BridgeServiceError("OPERATION_IDENTITY_MISMATCH");
      if (previous.parametersDigest !== parametersDigest) throw new BridgeServiceError("OPERATION_PARAMETERS_MISMATCH");
      if (previous.status === "pending") await transaction.remove(OPERATION_NAMESPACE, request.operationId);
    });
    this.#operations.delete(request.operationId);
  }

  async #read(operationId: string): Promise<StoredOperation | null> {
    const entries = await this.#scan();
    const entry = entries.find((candidate) => candidate.key === operationId);
    return entry === undefined ? null : cloneStored(assertStoredOperation(operationId, entry.value));
  }

  async #scan(): Promise<readonly Readonly<{ key: string; value: unknown }>[]> {
    return runDurableBridgeTransaction(this.#store, "operation.scan", (transaction) =>
      transaction.scan(OPERATION_NAMESPACE));
  }

  #parametersDigest(value: unknown): string {
    const canonical = (current: unknown): string => {
      if (typeof current === "bigint") return `bigint:${current.toString()}`;
      if (current === null || typeof current !== "object") return JSON.stringify(current);
      if (Array.isArray(current)) return `[${current.map(canonical).join(",")}]`;
      return `{${Object.keys(current as object).sort().map((key) => `${JSON.stringify(key)}:${canonical((current as Record<string, unknown>)[key])}`).join(",")}}`;
    };
    return canonical(value);
  }
}

export const isDurableOperationDispatcher = (value: unknown): value is DurableOperationDispatcher =>
  typeof value === "object" && value !== null
  && (value as Partial<DurableOperationDispatcher>).port === DURABLE_OPERATION_DISPATCHER_PORT
  && (value as Partial<DurableOperationDispatcher>).durability === "durable"
  && typeof (value as Partial<DurableOperationDispatcher>).execute === "function"
  && typeof (value as Partial<DurableOperationDispatcher>).claims === "function";

export const assertDurableOperationDispatcher = (value: unknown): DurableOperationDispatcher => {
  if (!isDurableOperationDispatcher(value)) throw new BridgeServiceError("DURABLE_OPERATION_DISPATCHER_REQUIRED");
  return value;
};
