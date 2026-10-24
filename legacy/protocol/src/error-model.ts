/** Task 7 closed protocol-error classifier.
 *
 * Internal decision reasons never cross the wire. This module is deliberately
 * independent of transport admission: callers pass the already-determined
 * precedence stage and the classifier emits only the closed public payload.
 */

export type ProtocolErrorCode =
  | "MESSAGE_TOO_LARGE" | "SCHEMA_INVALID" | "AUTH_FAILED"
  | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "CONNECTION_FENCED"
  | "AUTH_BINDING_MISMATCH" | "VERSION_UNSUPPORTED" | "REPLAY_REJECTED"
  | "IDEMPOTENCY_CONFLICT" | "NOT_AUTHORIZED" | "SECURITY_LEDGER_FULL"
  | "FLOW_CONTROL_VIOLATION" | "INVALID_STATE_TRANSITION"
  | "RESULT_CONFLICT" | "RATE_LIMITED" | "INTERNAL_ERROR";

export type InternalDecisionReason =
  | "ADAPTER_PRINCIPAL_MISSING" | "PAIRING_INACTIVE" | "SCOPE_DENIED"
  | "REVISION_MISMATCH" | "POLICY_BLOCKED" | "CAPACITY_EXHAUSTED"
  | "SECURITY_PARTITION_EXHAUSTED" | "MESSAGE_ID_CONFLICT" | "PENDING"
  | "WINDOW_REJECTED" | "COMPACTED_DUPLICATE" | "PENDING_LIMIT"
  | "COOLDOWN" | "CREDIT_EXHAUSTED";

export type InternalProtocolFailureReason = ProtocolErrorCode | InternalDecisionReason;

export type ProtocolStage =
  | "size" | "canonical_schema" | "registry_schema"
  | "authentication" | "payload_integrity" | "expiry"
  | "connection_fence" | "authenticated_binding" | "operation_binding"
  | "semantic_integrity" | "authorization_revision"
  | "replay_duplicate_sequence_capacity" | "operation_transition"
  | "execution_claim" | "result_persistence";

export type ErrorResponsePayload =
  | Readonly<{ code: "RATE_LIMITED"; stage: ProtocolStage; correlation_message_id: string | null; retry_after: string }>
  | Readonly<{ code: Exclude<ProtocolErrorCode, "RATE_LIMITED">; stage: ProtocolStage; correlation_message_id: string | null }>;

export type ProtocolFailure = Readonly<{
  stage: ProtocolStage;
  reason: InternalProtocolFailureReason;
  correlationMessageId: string | null;
  retryAfterSeconds?: bigint;
}>;

const CODES = new Set<ProtocolErrorCode>([
  "MESSAGE_TOO_LARGE", "SCHEMA_INVALID", "AUTH_FAILED", "INTEGRITY_FAILED",
  "MESSAGE_EXPIRED", "CONNECTION_FENCED", "AUTH_BINDING_MISMATCH",
  "VERSION_UNSUPPORTED", "REPLAY_REJECTED", "IDEMPOTENCY_CONFLICT",
  "NOT_AUTHORIZED", "SECURITY_LEDGER_FULL", "FLOW_CONTROL_VIOLATION",
  "INVALID_STATE_TRANSITION", "RESULT_CONFLICT", "RATE_LIMITED", "INTERNAL_ERROR",
]);
const INTERNAL = new Set<string>([
  "ADAPTER_PRINCIPAL_MISSING", "PAIRING_INACTIVE", "SCOPE_DENIED", "REVISION_MISMATCH",
  "POLICY_BLOCKED", "CAPACITY_EXHAUSTED", "SECURITY_PARTITION_EXHAUSTED", "MESSAGE_ID_CONFLICT",
  "PENDING", "WINDOW_REJECTED", "COMPACTED_DUPLICATE", "PENDING_LIMIT", "COOLDOWN", "CREDIT_EXHAUSTED",
]);
const STAGES = new Set<string>([
  "size", "canonical_schema", "registry_schema", "authentication", "payload_integrity", "expiry",
  "connection_fence", "authenticated_binding", "operation_binding", "semantic_integrity",
  "authorization_revision", "replay_duplicate_sequence_capacity", "operation_transition",
  "execution_claim", "result_persistence",
]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const U64 = /^(0|[1-9][0-9]*)$/;

const mapInternal = (reason: InternalDecisionReason): ProtocolErrorCode => {
  switch (reason) {
    case "ADAPTER_PRINCIPAL_MISSING": return "AUTH_BINDING_MISMATCH";
    case "PAIRING_INACTIVE": return "CONNECTION_FENCED";
    case "SCOPE_DENIED": case "REVISION_MISMATCH": case "POLICY_BLOCKED": return "NOT_AUTHORIZED";
    case "CAPACITY_EXHAUSTED": case "PENDING": case "WINDOW_REJECTED": case "COMPACTED_DUPLICATE": return "REPLAY_REJECTED";
    case "MESSAGE_ID_CONFLICT": return "INTEGRITY_FAILED";
    case "SECURITY_PARTITION_EXHAUSTED": return "SECURITY_LEDGER_FULL";
    case "PENDING_LIMIT": case "COOLDOWN": return "RATE_LIMITED";
    case "CREDIT_EXHAUSTED": return "FLOW_CONTROL_VIOLATION";
  }
};

const isProtocolCode = (value: string): value is ProtocolErrorCode => CODES.has(value as ProtocolErrorCode);
const mappedCode = (reason: InternalProtocolFailureReason): ProtocolErrorCode =>
  isProtocolCode(reason) ? reason : mapInternal(reason);

export function classifyProtocolFailure(failure: ProtocolFailure): ErrorResponsePayload {
  if (!STAGES.has(failure.stage) || !isProtocolCode(failure.reason) && !INTERNAL.has(failure.reason)) throw new Error("SCHEMA_INVALID");
  if (failure.correlationMessageId !== null && !UUID.test(failure.correlationMessageId)) throw new Error("SCHEMA_INVALID");
  if (failure.retryAfterSeconds !== undefined && (typeof failure.retryAfterSeconds !== "bigint" || failure.retryAfterSeconds < 0n)) throw new Error("SCHEMA_INVALID");
  const code = mappedCode(failure.reason);
  if (code === "RATE_LIMITED") {
    if (failure.retryAfterSeconds === undefined) throw new Error("RETRY_AFTER_REQUIRED");
    return Object.freeze({ code, stage: failure.stage, correlation_message_id: failure.correlationMessageId, retry_after: failure.retryAfterSeconds.toString(10) });
  }
  if (failure.retryAfterSeconds !== undefined) throw new Error("INTEGRITY_FAILED");
  return Object.freeze({ code, stage: failure.stage, correlation_message_id: failure.correlationMessageId });
}

export function validateErrorResponse(value: unknown): value is ErrorResponsePayload {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const candidate = value as Record<string, unknown>;
  if (typeof candidate.code !== "string" || !CODES.has(candidate.code as ProtocolErrorCode)
    || typeof candidate.stage !== "string" || !STAGES.has(candidate.stage)
    || !(candidate.correlation_message_id === null || typeof candidate.correlation_message_id === "string" && UUID.test(candidate.correlation_message_id))) return false;
  if (candidate.code === "RATE_LIMITED") {
    return Object.keys(candidate).sort().join(",") === "code,correlation_message_id,retry_after,stage"
      && typeof candidate.retry_after === "string" && U64.test(candidate.retry_after);
  }
  return Object.keys(candidate).sort().join(",") === "code,correlation_message_id,stage";
}
