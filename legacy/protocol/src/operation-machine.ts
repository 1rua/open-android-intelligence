/** Task 7's closed, deterministic operation state machine.
 *
 * This module deliberately contains no transport/replay admission code.  It is
 * the small reference reducer that operation and execution stores can share.
 */

export type RequestStatus =
  | "created" | "waiting_device" | "dispatching" | "accepted_device"
  | "awaiting_approval" | "approved" | "executing";

export type TerminalOutcome =
  | "succeeded" | "failed" | "denied" | "cancelled" | "expired" | "result_unknown";

export type OperationReason =
  | "NOT_AUTHORIZED" | "POLICY_BLOCKED" | "USER_DENIED" | "PLATFORM_UNSUPPORTED"
  | "BACKEND_UNAVAILABLE" | "DEVICE_OFFLINE" | "DEVICE_LOCKED"
  | "PAYLOAD_TOO_LARGE" | "RATE_LIMITED" | "SECURITY_LEDGER_FULL"
  | "QUEUE_LIMIT" | "INTERNAL_ERROR";

export type WireOperationState =
  | Readonly<{ request_status: RequestStatus; terminal_outcome: null; operation_reason: null }>
  | Readonly<{ request_status: null; terminal_outcome: TerminalOutcome; operation_reason: OperationReason | null }>;

export type SessionOrJob =
  | Readonly<{ kind: "session"; sessionId: string }>
  | Readonly<{ kind: "job"; jobId: string }>;

export type StoredOperationBinding = Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  agentPrincipalId: string;
  agentInstanceId: string;
  workspaceId: string;
  sessionOrJob: SessionOrJob;
  deviceId: string;
  operationId: string;
  capability: string;
  parametersDigest: string;
}>;

export type RevisionSnapshot = Readonly<{
  pairingGeneration: bigint;
  authorizationEpoch: bigint;
  scopeRevisions: Readonly<Record<string, bigint>>;
}>;

export type OperationRecord = Readonly<{
  binding: StoredOperationBinding;
  revisionSnapshot: RevisionSnapshot;
  operationExpiresAt: string;
  offlinePolicy: "WAIT_READ" | "FAIL_OFFLINE";
  stateRevision: bigint;
  state: WireOperationState;
  reconciliation: null | Readonly<{
    outcome: "succeeded" | "failed";
    evidenceDigest: string;
    observedAt: string;
  }>;
}>;

export type OperationEvent =
  | { type: "queue_wait_read" }
  | { type: "dispatch" }
  | { type: "device_accepted" }
  | { type: "approval_required" }
  | { type: "approval_not_required" }
  | { type: "approval_granted" }
  | { type: "approval_denied" }
  | { type: "execution_claimed" }
  | { type: "fail_before_claim"; reason: Extract<OperationReason,
      "PLATFORM_UNSUPPORTED" | "BACKEND_UNAVAILABLE" | "DEVICE_OFFLINE"
      | "DEVICE_LOCKED" | "PAYLOAD_TOO_LARGE" | "SECURITY_LEDGER_FULL"
      | "QUEUE_LIMIT" | "INTERNAL_ERROR"> }
  | { type: "execution_succeeded" }
  | { type: "execution_failed"; reason: Extract<OperationReason,
      "PLATFORM_UNSUPPORTED" | "BACKEND_UNAVAILABLE" | "DEVICE_OFFLINE"
      | "DEVICE_LOCKED" | "PAYLOAD_TOO_LARGE" | "SECURITY_LEDGER_FULL"
      | "QUEUE_LIMIT" | "INTERNAL_ERROR"> }
  | { type: "deny"; reason: Extract<OperationReason, "NOT_AUTHORIZED" | "POLICY_BLOCKED" | "USER_DENIED" | "RATE_LIMITED"> }
  | { type: "execution_result_unknown" }
  | { type: "cancel" }
  | { type: "expire" }
  | { type: "reconcile_evidence"; outcome: "succeeded" | "failed"; evidenceDigest: string; observedAt: string };

export type OperationReduction =
  | { ok: true; record: OperationRecord; changed: boolean }
  | { ok: false; error: "INVALID_STATE_TRANSITION" | "RESULT_CONFLICT"; record: OperationRecord };

const REQUEST_STATUSES = new Set<RequestStatus>([
  "created", "waiting_device", "dispatching", "accepted_device", "awaiting_approval", "approved", "executing",
]);
const TERMINAL_OUTCOMES = new Set<TerminalOutcome>([
  "succeeded", "failed", "denied", "cancelled", "expired", "result_unknown",
]);
const FAILURE_REASONS = new Set<OperationReason>([
  "PLATFORM_UNSUPPORTED", "BACKEND_UNAVAILABLE", "DEVICE_OFFLINE", "DEVICE_LOCKED",
  "PAYLOAD_TOO_LARGE", "SECURITY_LEDGER_FULL", "QUEUE_LIMIT", "INTERNAL_ERROR",
]);
const DENIAL_REASONS = new Set<OperationReason>([
  "NOT_AUTHORIZED", "POLICY_BLOCKED", "USER_DENIED", "RATE_LIMITED",
]);
const DIGEST = /^[A-Za-z0-9_-]{43}$/;
const NON_EMPTY = /^[^\u0000\u0001-\u001f\u007f]{1,512}$/u;

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};

const freeze = <T>(value: T): T => {
  if (typeof value === "object" && value !== null && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) freeze(child);
    Object.freeze(value);
  }
  return value;
};

const validInstant = (value: unknown): value is string =>
  typeof value === "string" && Number.isFinite(Date.parse(value)) && value === new Date(value).toISOString();

function validateOperationEvent(value: unknown): value is OperationEvent {
  if (!isRecord(value) || typeof value.type !== "string") return false;
  switch (value.type) {
    case "queue_wait_read": case "dispatch": case "device_accepted": case "approval_required":
    case "approval_not_required": case "approval_granted": case "approval_denied":
    case "execution_claimed": case "execution_succeeded": case "execution_result_unknown":
    case "cancel": case "expire":
      return exactKeys(value, ["type"]);
    case "fail_before_claim": case "execution_failed":
      return exactKeys(value, ["type", "reason"]) && typeof value.reason === "string" && FAILURE_REASONS.has(value.reason as OperationReason);
    case "deny":
      return exactKeys(value, ["type", "reason"]) && typeof value.reason === "string" && DENIAL_REASONS.has(value.reason as OperationReason);
    case "reconcile_evidence":
      return exactKeys(value, ["type", "outcome", "evidenceDigest", "observedAt"])
        && (value.outcome === "succeeded" || value.outcome === "failed")
        && typeof value.evidenceDigest === "string" && NON_EMPTY.test(value.evidenceDigest)
        && validInstant(value.observedAt);
    default:
      return false;
  }
}

function validateSessionOrJob(value: unknown): value is SessionOrJob {
  if (!isRecord(value) || typeof value.kind !== "string") return false;
  if (value.kind === "session") return exactKeys(value, ["kind", "sessionId"]) && typeof value.sessionId === "string" && NON_EMPTY.test(value.sessionId);
  if (value.kind === "job") return exactKeys(value, ["kind", "jobId"]) && typeof value.jobId === "string" && NON_EMPTY.test(value.jobId);
  return false;
}

function validateBinding(value: unknown): value is StoredOperationBinding {
  if (!isRecord(value) || !exactKeys(value, [
    "tenantId", "humanPrincipalId", "agentPrincipalId", "agentInstanceId", "workspaceId",
    "sessionOrJob", "deviceId", "operationId", "capability", "parametersDigest",
  ])) return false;
  return ["tenantId", "humanPrincipalId", "agentPrincipalId", "agentInstanceId", "workspaceId", "deviceId", "operationId", "capability"]
    .every((key) => typeof value[key] === "string" && NON_EMPTY.test(value[key] as string))
    && validateSessionOrJob(value.sessionOrJob)
    && typeof value.parametersDigest === "string" && DIGEST.test(value.parametersDigest);
}

function validateRevisionSnapshot(value: unknown): value is RevisionSnapshot {
  if (!isRecord(value) || !exactKeys(value, ["pairingGeneration", "authorizationEpoch", "scopeRevisions"]) || typeof value.pairingGeneration !== "bigint" || value.pairingGeneration < 0n || typeof value.authorizationEpoch !== "bigint" || value.authorizationEpoch < 0n || !isRecord(value.scopeRevisions)) return false;
  const scopes = value.scopeRevisions as Record<string, unknown>;
  return Object.keys(scopes).every((key) => NON_EMPTY.test(key) && typeof scopes[key] === "bigint" && (scopes[key] as bigint) >= 0n);
}

export function validateWireOperationState(value: unknown): value is WireOperationState {
  if (!isRecord(value) || !exactKeys(value, ["request_status", "terminal_outcome", "operation_reason"])) return false;
  const request = value.request_status;
  const terminal = value.terminal_outcome;
  const reason = value.operation_reason;
  if (request !== null) return typeof request === "string" && REQUEST_STATUSES.has(request as RequestStatus) && terminal === null && reason === null;
  if (typeof terminal !== "string" || !TERMINAL_OUTCOMES.has(terminal as TerminalOutcome)) return false;
  if (reason !== null && (typeof reason !== "string" || !([...FAILURE_REASONS, ...DENIAL_REASONS] as string[]).includes(reason))) return false;
  if ((terminal === "succeeded" || terminal === "cancelled" || terminal === "expired" || terminal === "result_unknown") && reason !== null) return false;
  if (terminal === "denied" && (reason === null || !DENIAL_REASONS.has(reason as OperationReason))) return false;
  if (terminal === "failed" && (reason === null || !FAILURE_REASONS.has(reason as OperationReason))) return false;
  return true;
}

export function validateOperationRecord(value: unknown): value is OperationRecord {
  if (!isRecord(value) || !exactKeys(value, ["binding", "revisionSnapshot", "operationExpiresAt", "offlinePolicy", "stateRevision", "state", "reconciliation"])) return false;
  if (!validateBinding(value.binding) || !validateRevisionSnapshot(value.revisionSnapshot) || !validInstant(value.operationExpiresAt) || (value.offlinePolicy !== "WAIT_READ" && value.offlinePolicy !== "FAIL_OFFLINE") || typeof value.stateRevision !== "bigint" || value.stateRevision < 0n || !validateWireOperationState(value.state)) return false;
  if (value.reconciliation === null) return true;
  const reconciliation = value.reconciliation;
  return value.state.request_status === null && value.state.terminal_outcome === "result_unknown"
    && isRecord(reconciliation) && exactKeys(reconciliation, ["outcome", "evidenceDigest", "observedAt"])
    && (reconciliation.outcome === "succeeded" || reconciliation.outcome === "failed")
    && typeof reconciliation.evidenceDigest === "string" && NON_EMPTY.test(reconciliation.evidenceDigest)
    && validInstant(reconciliation.observedAt);
}

export function createOperationRecord(input: Readonly<{
  binding: StoredOperationBinding;
  revisionSnapshot: RevisionSnapshot;
  operationExpiresAt: string;
  offlinePolicy: "WAIT_READ" | "FAIL_OFFLINE";
  state?: WireOperationState;
}>): OperationRecord {
  const candidate = {
    binding: { ...input.binding, sessionOrJob: { ...input.binding.sessionOrJob } },
    revisionSnapshot: { ...input.revisionSnapshot, scopeRevisions: { ...input.revisionSnapshot.scopeRevisions } },
    operationExpiresAt: input.operationExpiresAt,
    offlinePolicy: input.offlinePolicy,
    stateRevision: 0n,
    state: input.state ?? { request_status: "created", terminal_outcome: null, operation_reason: null },
    reconciliation: null,
  };
  if (!validateOperationRecord(candidate)) throw new Error("SCHEMA_INVALID");
  return freeze(candidate);
}

const nonterminal = (record: OperationRecord): RequestStatus | null => record.state.request_status;
const terminal = (record: OperationRecord): TerminalOutcome | null => record.state.terminal_outcome;

const next = (record: OperationRecord, state: WireOperationState, reconciliation = record.reconciliation): OperationRecord => freeze({
  ...record,
  stateRevision: record.stateRevision + 1n,
  state: freeze(state),
  reconciliation: reconciliation === null ? null : freeze({ ...reconciliation }),
});

const invalid = (record: OperationRecord): OperationReduction => ({ ok: false, error: "INVALID_STATE_TRANSITION", record });

export function reduceOperation(record: OperationRecord, event: OperationEvent, now = new Date().toISOString()): OperationReduction {
  if (!validateOperationRecord(record) || !validateOperationEvent(event)) throw new Error("SCHEMA_INVALID");
  const status = nonterminal(record);
  const terminalOutcome = terminal(record);
  if (event.type === "reconcile_evidence") {
    if (terminalOutcome !== "result_unknown") return invalid(record);
    const evidence = { outcome: event.outcome, evidenceDigest: event.evidenceDigest, observedAt: event.observedAt } as const;
    if (!NON_EMPTY.test(evidence.evidenceDigest) || !validInstant(evidence.observedAt)) return invalid(record);
    if (record.reconciliation && record.reconciliation.outcome === evidence.outcome && record.reconciliation.evidenceDigest === evidence.evidenceDigest && record.reconciliation.observedAt === evidence.observedAt) return { ok: true, record, changed: false };
    if (record.reconciliation !== null) return { ok: false, error: "RESULT_CONFLICT", record };
    return { ok: true, record: next(record, record.state, evidence), changed: true };
  }
  if (terminalOutcome !== null) return invalid(record);
  let state: WireOperationState | null = null;
  switch (event.type) {
    case "queue_wait_read": {
      if (status !== "created" || record.offlinePolicy !== "WAIT_READ" || !validInstant(now)) return invalid(record);
      const remaining = Date.parse(record.operationExpiresAt) - Date.parse(now);
      if (remaining < 0 || remaining > 900_000) return invalid(record);
      state = { request_status: "waiting_device", terminal_outcome: null, operation_reason: null }; break;
    }
    case "dispatch": if (status === "created" || status === "waiting_device") state = { request_status: "dispatching", terminal_outcome: null, operation_reason: null }; break;
    case "device_accepted": if (status === "dispatching") state = { request_status: "accepted_device", terminal_outcome: null, operation_reason: null }; break;
    case "approval_required": if (status === "accepted_device") state = { request_status: "awaiting_approval", terminal_outcome: null, operation_reason: null }; break;
    case "approval_not_required": if (status === "accepted_device") state = { request_status: "approved", terminal_outcome: null, operation_reason: null }; break;
    case "approval_granted": if (status === "awaiting_approval") state = { request_status: "approved", terminal_outcome: null, operation_reason: null }; break;
    case "approval_denied": if (status === "awaiting_approval") state = { request_status: null, terminal_outcome: "denied", operation_reason: "USER_DENIED" }; break;
    case "execution_claimed": if (status === "approved") state = { request_status: "executing", terminal_outcome: null, operation_reason: null }; break;
    case "fail_before_claim": if (status !== null && status !== "executing" && FAILURE_REASONS.has(event.reason)) state = { request_status: null, terminal_outcome: "failed", operation_reason: event.reason }; break;
    case "execution_succeeded": if (status === "executing") state = { request_status: null, terminal_outcome: "succeeded", operation_reason: null }; break;
    case "execution_failed": if (status === "executing" && FAILURE_REASONS.has(event.reason)) state = { request_status: null, terminal_outcome: "failed", operation_reason: event.reason }; break;
    case "execution_result_unknown": if (status === "executing") state = { request_status: null, terminal_outcome: "result_unknown", operation_reason: null }; break;
    case "cancel": if (status !== null && status !== "executing") state = { request_status: null, terminal_outcome: "cancelled", operation_reason: null }; break;
    case "expire": if (status !== null && status !== "executing") state = { request_status: null, terminal_outcome: "expired", operation_reason: null }; break;
    case "deny": if (status !== null && status !== "executing" && DENIAL_REASONS.has(event.reason)) state = { request_status: null, terminal_outcome: "denied", operation_reason: event.reason }; break;
  }
  return state === null ? invalid(record) : { ok: true, record: next(record, state), changed: true };
}

export function toWireOperationState(record: OperationRecord): WireOperationState {
  if (!validateOperationRecord(record)) throw new Error("SCHEMA_INVALID");
  if (record.state.request_status !== null) {
    return freeze({ request_status: record.state.request_status, terminal_outcome: null, operation_reason: null });
  }
  return freeze({ request_status: null, terminal_outcome: record.state.terminal_outcome, operation_reason: record.state.operation_reason });
}
