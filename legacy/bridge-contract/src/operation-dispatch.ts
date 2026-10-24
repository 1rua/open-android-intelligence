import {
  BridgeServiceError,
  freezeRecord,
  type BridgeSessionIdentity,
  sessionKey,
} from "./service-types.js";

export type OperationRequest = Readonly<{
  operationId: string;
  session: BridgeSessionIdentity;
  parameters?: unknown;
}>;

type StoredOperation = {
  sessionKey: string;
  parametersDigest: string;
  status: "pending" | "completed";
  result?: unknown;
  claims: number;
};

export type OperationClaim<T> = Readonly<{
  operationId: string;
  existing: boolean;
  result?: T;
}>;

/**
 * The service-facing operation seam. Implementations may keep claims in
 * process memory or in a durable adapter, but the service only depends on
 * idempotent execution and a diagnostic claim snapshot.
 */
export interface OperationDispatcherPort {
  execute<T>(request: OperationRequest, action: () => Promise<T> | T): Promise<T>;
  claims(): readonly Readonly<{ operationId: string; claims: number }>[];
}

const canonical = (value: unknown): string => {
  if (typeof value === "bigint") return `bigint:${value.toString()}`;
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  return `{${Object.keys(value as object).sort().map((key) => `${JSON.stringify(key)}:${canonical((value as Record<string, unknown>)[key])}`).join(",")}}`;
};

/**
 * Task-7-shaped operation claim/result ledger for the WP-06 in-memory seam.
 * Completed results survive `restart()`; pending claims are intentionally
 * released so the caller can retry after a simulated crash cut.
 */
export class OperationDispatcher implements OperationDispatcherPort {
  readonly #operations = new Map<string, StoredOperation>();

  begin<T>(request: OperationRequest): OperationClaim<T> {
    if (typeof request.operationId !== "string" || request.operationId.length === 0) throw new BridgeServiceError("OPERATION_ID_INVALID");
    const session = sessionKey(request.session);
    const parametersDigest = canonical(request.parameters ?? null);
    const previous = this.#operations.get(request.operationId);
    if (previous) {
      if (previous.sessionKey !== session) throw new BridgeServiceError("OPERATION_IDENTITY_MISMATCH");
      if (previous.parametersDigest !== parametersDigest) throw new BridgeServiceError("OPERATION_PARAMETERS_MISMATCH");
      if (previous.status === "completed") return freezeRecord({ operationId: request.operationId, existing: true, result: previous.result as T });
      throw new BridgeServiceError("OPERATION_IN_PROGRESS");
    }
    this.#operations.set(request.operationId, { sessionKey: session, parametersDigest, status: "pending", claims: 1 });
    return freezeRecord({ operationId: request.operationId, existing: false });
  }

  complete<T>(request: OperationRequest, result: T): T {
    const previous = this.#operations.get(request.operationId);
    if (!previous) throw new BridgeServiceError("OPERATION_CLAIM_MISSING");
    if (previous.sessionKey !== sessionKey(request.session)) throw new BridgeServiceError("OPERATION_IDENTITY_MISMATCH");
    if (previous.parametersDigest !== canonical(request.parameters ?? null)) throw new BridgeServiceError("OPERATION_PARAMETERS_MISMATCH");
    if (previous.status === "completed") return previous.result as T;
    previous.status = "completed";
    previous.result = result;
    return result;
  }

  release(request: OperationRequest): void {
    const previous = this.#operations.get(request.operationId);
    if (!previous) return;
    if (previous.sessionKey !== sessionKey(request.session) || previous.parametersDigest !== canonical(request.parameters ?? null)) throw new BridgeServiceError("OPERATION_IDENTITY_MISMATCH");
    if (previous.status === "pending") this.#operations.delete(request.operationId);
  }

  async execute<T>(request: OperationRequest, action: () => Promise<T> | T): Promise<T> {
    const claim = this.begin<T>(request);
    if (claim.existing) return claim.result as T;
    try {
      return this.complete(request, await action());
    } catch (error) {
      this.release(request);
      throw error;
    }
  }

  claims(): readonly Readonly<{ operationId: string; claims: number }>[] {
    return Object.freeze([...this.#operations].map(([operationId, operation]) => freezeRecord({ operationId, claims: operation.claims })));
  }

  /** Simulate a process restart: completed results remain, pending claims are recoverable. */
  restart(): OperationDispatcher {
    const next = new OperationDispatcher();
    for (const [operationId, operation] of this.#operations) {
      if (operation.status === "completed") next.#operations.set(operationId, { ...operation });
    }
    return next;
  }
}

export { BridgeServiceError } from "./service-types.js";
