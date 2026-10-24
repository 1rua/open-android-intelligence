/** A compact in-memory reference implementation for Task 7's execution CAS.
 * Production adapters may replace the store, but must preserve these outcomes.
 */
import { randomBytes } from "node:crypto";
import { canonicalBytes, sha256B64Url } from "./encoding.js";
import {
  createOperationRecord,
  reduceOperation,
  validateOperationRecord,
  validateWireOperationState,
  type OperationRecord,
  type OperationReason,
  type WireOperationState,
} from "./operation-machine.js";

const receiptBrand: unique symbol = Symbol("signed-operation-receipt");
const reconcilerBrand: unique symbol = Symbol("trusted-execution-reconciler");

export type ReceiptBytes = Readonly<{ readonly byteLength: number; copy(): Uint8Array }>;
export type SignedOperationReceipt = Readonly<{
  readonly operationId: string;
  readonly parametersDigest: string;
  readonly stateRevision: bigint;
  readonly state: WireOperationState;
  readonly resultDigest: string | null;
  readonly envelopeDigest: string;
  readonly messageId: string;
  readonly canonicalBytes: ReceiptBytes;
  readonly [receiptBrand]: true;
}>;

export type TrustedExecutionReconciler = Readonly<{ readonly reconcilerId: string; readonly [reconcilerBrand]: true }>;

export type ExecutionLedgerEntry =
  | Readonly<{ kind: "registered"; record: OperationRecord; registrationReplayClaimId: string }>
  | Readonly<{ kind: "claimed"; record: OperationRecord; claimId: string; claimedAt: string; parametersDigest: string }>
  | Readonly<{ kind: "result"; record: OperationRecord; claimId: string; parametersDigest: string; receipt: SignedOperationReceipt }>;

const DIGEST = /^[A-Za-z0-9_-]{43}$/;
const ID = /^[^\u0000-\u001f\u007f]{1,512}$/u;
const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};
const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const freeze = <T>(value: T): T => {
  if (typeof value === "object" && value !== null && !Object.isFrozen(value)) { for (const child of Object.values(value)) freeze(child); Object.freeze(value); }
  return value;
};
const equalBytes = (left: Uint8Array, right: Uint8Array): boolean => left.byteLength === right.byteLength && left.every((value, index) => value === right[index]);
const bytes = (input: Uint8Array): ReceiptBytes => {
  const copy = new Uint8Array(input);
  return Object.freeze({ byteLength: copy.byteLength, copy: () => new Uint8Array(copy) });
};

const stateCopy = (state: WireOperationState): WireOperationState => state.request_status === null
  ? Object.freeze({ request_status: null, terminal_outcome: state.terminal_outcome, operation_reason: state.operation_reason })
  : Object.freeze({ request_status: state.request_status, terminal_outcome: null, operation_reason: null });

export function mintSignedOperationReceipt(input: Readonly<{
  operationId: string;
  parametersDigest: string;
  stateRevision: bigint;
  state: WireOperationState;
  resultDigest: string | null;
  envelopeDigest: string;
  messageId: string;
  canonicalValue: unknown;
}>): SignedOperationReceipt {
  if (!ID.test(input.operationId) || !DIGEST.test(input.parametersDigest) || input.stateRevision < 0n || !validateWireOperationState(input.state) || input.state.request_status !== null || input.state.terminal_outcome === "result_unknown" || (input.resultDigest !== null && !DIGEST.test(input.resultDigest)) || !DIGEST.test(input.envelopeDigest) || !ID.test(input.messageId)) throw new Error("SCHEMA_INVALID");
  const raw = canonicalBytes(input.canonicalValue);
  return freeze({
    operationId: input.operationId,
    parametersDigest: input.parametersDigest,
    stateRevision: input.stateRevision,
    state: stateCopy(input.state),
    resultDigest: input.resultDigest,
    envelopeDigest: input.envelopeDigest,
    messageId: input.messageId,
    canonicalBytes: bytes(raw),
    [receiptBrand]: true as const,
  });
}

export function validateSignedOperationReceipt(value: unknown): value is SignedOperationReceipt {
  if (!isRecord(value) || !exactKeys(value, ["operationId", "parametersDigest", "stateRevision", "state", "resultDigest", "envelopeDigest", "messageId", "canonicalBytes"]) || (value as Record<PropertyKey, unknown>)[receiptBrand] !== true) return false;
  if (typeof value.operationId !== "string" || !ID.test(value.operationId) || typeof value.parametersDigest !== "string" || !DIGEST.test(value.parametersDigest) || typeof value.stateRevision !== "bigint" || value.stateRevision < 0n || !validateWireOperationState(value.state) || value.state.request_status !== null || value.state.terminal_outcome === "result_unknown" || (value.resultDigest !== null && (typeof value.resultDigest !== "string" || !DIGEST.test(value.resultDigest))) || typeof value.envelopeDigest !== "string" || !DIGEST.test(value.envelopeDigest) || typeof value.messageId !== "string" || !ID.test(value.messageId)) return false;
  const raw = value.canonicalBytes;
  return isRecord(raw) && exactKeys(raw, ["byteLength", "copy"]) && typeof raw.byteLength === "number" && Number.isSafeInteger(raw.byteLength) && raw.byteLength > 0 && typeof raw.copy === "function" && (() => { try { const copy = raw.copy(); return copy instanceof Uint8Array && copy.byteLength === raw.byteLength; } catch { return false; } })();
}

export function mintTrustedExecutionReconciler(reconcilerId: string): TrustedExecutionReconciler {
  if (!ID.test(reconcilerId)) throw new Error("SCHEMA_INVALID");
  return Object.freeze({ reconcilerId, [reconcilerBrand]: true as const });
}

const equivalentRecord = (left: OperationRecord, right: OperationRecord): boolean => {
  const encode = (record: OperationRecord): unknown => ({
    binding: { ...record.binding, sessionOrJob: { ...record.binding.sessionOrJob } },
    revisionSnapshot: { pairingGeneration: record.revisionSnapshot.pairingGeneration.toString(), authorizationEpoch: record.revisionSnapshot.authorizationEpoch.toString(), scopeRevisions: Object.fromEntries(Object.entries(record.revisionSnapshot.scopeRevisions).map(([key, value]) => [key, value.toString()])) },
    operationExpiresAt: record.operationExpiresAt,
    offlinePolicy: record.offlinePolicy,
    stateRevision: record.stateRevision.toString(), state: record.state, reconciliation: record.reconciliation,
  });
  try { return equalBytes(canonicalBytes(encode(left)), canonicalBytes(encode(right))); } catch { return false; }
};

export class MemoryExecutionLedger {
  readonly #entries = new Map<string, ExecutionLedgerEntry>();
  readonly #claimIdSource: () => string;
  constructor(options: Readonly<{ claimIdSource?: () => string }> = {}) {
    this.#claimIdSource = options.claimIdSource ?? (() => randomBytes(32).toString("base64url"));
  }
  async register(input: Readonly<{ record: OperationRecord; registrationReplayClaimId: string }>): Promise<"new" | "same" | "conflict"> {
    if (!validateOperationRecord(input.record) || !ID.test(input.registrationReplayClaimId)) throw new Error("SCHEMA_INVALID");
    const prior = this.#entries.get(input.record.binding.operationId);
    if (!prior) { this.#entries.set(input.record.binding.operationId, Object.freeze({ kind: "registered", record: input.record, registrationReplayClaimId: input.registrationReplayClaimId })); return "new"; }
    return prior.record.binding.parametersDigest === input.record.binding.parametersDigest && equivalentRecord(prior.record, input.record) ? "same" : "conflict";
  }
  async claim(input: Readonly<{ operationId: string; parametersDigest: string; claimedAt: string }>): Promise<
    | { kind: "claimed"; claimId: string }
    | { kind: "already_claimed"; claimId: string }
    | { kind: "already_result"; receipt: SignedOperationReceipt }
    | { kind: "invalid_state"; state: WireOperationState }
    | { kind: "digest_conflict" }
    | { kind: "not_found" }
  > {
    if (!ID.test(input.operationId) || !DIGEST.test(input.parametersDigest) || !Number.isFinite(Date.parse(input.claimedAt))) return { kind: "not_found" };
    const prior = this.#entries.get(input.operationId);
    if (!prior) return { kind: "not_found" };
    if (prior.record.binding.parametersDigest !== input.parametersDigest) return { kind: "digest_conflict" };
    if (prior.kind === "result") return { kind: "already_result", receipt: prior.receipt };
    if (prior.kind === "claimed") return { kind: "already_claimed", claimId: prior.claimId };
    const reduced = reduceOperation(prior.record, { type: "execution_claimed" });
    if (!reduced.ok) return { kind: "invalid_state", state: prior.record.state };
    const claimId = this.#claimIdSource();
    if (!ID.test(claimId)) throw new Error("SCHEMA_INVALID");
    this.#entries.set(input.operationId, Object.freeze({ kind: "claimed", record: reduced.record, claimId, claimedAt: input.claimedAt, parametersDigest: input.parametersDigest }));
    return { kind: "claimed", claimId };
  }
  async putResult(input: Readonly<{ operationId: string; parametersDigest: string; claimId: string; receipt: SignedOperationReceipt }>): Promise<"stored" | "same" | "result_conflict" | "digest_conflict" | "claim_mismatch" | "invalid_state"> {
    const prior = this.#entries.get(input.operationId);
    if (!prior) return "invalid_state";
    if (prior.record.binding.parametersDigest !== input.parametersDigest || input.receipt.parametersDigest !== input.parametersDigest) return "digest_conflict";
    if (!validateSignedOperationReceipt(input.receipt) || input.receipt.operationId !== input.operationId) return "claim_mismatch";
    if (prior.kind === "result") {
      return equalBytes(prior.receipt.canonicalBytes.copy(), input.receipt.canonicalBytes.copy()) ? "same" : "result_conflict";
    }
    if (prior.kind !== "claimed") return "invalid_state";
    if (prior.claimId !== input.claimId) return "claim_mismatch";
    if (input.receipt.stateRevision !== prior.record.stateRevision + 1n) return "invalid_state";
    const terminal = input.receipt.state.terminal_outcome;
    const reduced = terminal === "succeeded"
      ? reduceOperation(prior.record, { type: "execution_succeeded" })
      : terminal === "failed" && input.receipt.state.operation_reason !== null
        ? reduceOperation(prior.record, { type: "execution_failed", reason: input.receipt.state.operation_reason as Extract<OperationReason, "PLATFORM_UNSUPPORTED" | "BACKEND_UNAVAILABLE" | "DEVICE_OFFLINE" | "DEVICE_LOCKED" | "PAYLOAD_TOO_LARGE" | "SECURITY_LEDGER_FULL" | "QUEUE_LIMIT" | "INTERNAL_ERROR"> })
        : { ok: false as const, error: "INVALID_STATE_TRANSITION" as const, record: prior.record };
    if (!reduced.ok || reduced.record.stateRevision !== input.receipt.stateRevision) return "invalid_state";
    this.#entries.set(input.operationId, Object.freeze({ kind: "result", record: reduced.record, claimId: prior.claimId, parametersDigest: prior.parametersDigest, receipt: input.receipt }));
    return "stored";
  }
  async get(operationId: string): Promise<ExecutionLedgerEntry | undefined> { return this.#entries.get(operationId); }
  async recoverClaimedWithoutResult(reconciler: TrustedExecutionReconciler): Promise<Readonly<{ recoveredOperationIds: readonly string[] }>> {
    if (!reconciler || reconciler[reconcilerBrand] !== true) throw new Error("NOT_AUTHORIZED");
    const recovered: string[] = [];
    for (const [operationId, prior] of this.#entries) {
      if (prior.kind !== "claimed") continue;
      const reduced = reduceOperation(prior.record, { type: "execution_result_unknown" });
      if (!reduced.ok) throw new Error("INTEGRITY_FAILED");
      this.#entries.set(operationId, Object.freeze({ ...prior, record: reduced.record }));
      recovered.push(operationId);
    }
    return Object.freeze({ recoveredOperationIds: Object.freeze(recovered) });
  }
}

export function claimExecution(store: MemoryExecutionLedger, input: Readonly<{ operationId: string; parametersDigest: string; claimedAt: string }>) { return store.claim(input); }

export function recordResult(store: MemoryExecutionLedger, input: Readonly<{ operationId: string; parametersDigest: string; claimId: string; receipt: SignedOperationReceipt }>): Promise<"stored" | "same" | "RESULT_CONFLICT" | "INTEGRITY_FAILED" | "INVALID_STATE_TRANSITION"> {
  return store.putResult(input).then((result) => result === "result_conflict" ? "RESULT_CONFLICT" : result === "digest_conflict" || result === "claim_mismatch" ? "INTEGRITY_FAILED" : result === "invalid_state" ? "INVALID_STATE_TRANSITION" : result);
}
