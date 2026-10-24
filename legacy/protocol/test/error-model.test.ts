import { describe, expect, it } from "vitest";
import { classifyProtocolFailure, validateErrorResponse, type ProtocolFailure } from "../src/error-model.js";

describe("Task 7 closed error model", () => {
  it("maps internal replay decisions without exposing internal reasons", () => {
    const cases: readonly [ProtocolFailure["reason"], string][] = [
      ["ADAPTER_PRINCIPAL_MISSING", "AUTH_BINDING_MISMATCH"],
      ["PAIRING_INACTIVE", "CONNECTION_FENCED"],
      ["SCOPE_DENIED", "NOT_AUTHORIZED"],
      ["MESSAGE_ID_CONFLICT", "INTEGRITY_FAILED"],
      ["PENDING", "REPLAY_REJECTED"],
      ["SECURITY_PARTITION_EXHAUSTED", "SECURITY_LEDGER_FULL"],
      ["PENDING_LIMIT", "RATE_LIMITED"],
      ["CREDIT_EXHAUSTED", "FLOW_CONTROL_VIOLATION"],
    ];
    for (const [reason, code] of cases) {
      const response = classifyProtocolFailure({ stage: "replay_duplicate_sequence_capacity", reason, correlationMessageId: null, ...(code === "RATE_LIMITED" ? { retryAfterSeconds: 0n } : {}) });
      expect(response.code).toBe(code);
      expect(JSON.stringify(response)).not.toContain(reason);
    }
  });

  it("requires retry_after only for RATE_LIMITED and validates closed response keys", () => {
    const rate = classifyProtocolFailure({ stage: "authorization_revision", reason: "COOLDOWN", correlationMessageId: null, retryAfterSeconds: 7n });
    expect(rate).toEqual({ code: "RATE_LIMITED", stage: "authorization_revision", correlation_message_id: null, retry_after: "7" });
    expect(validateErrorResponse(rate)).toBe(true);
    const permanent = classifyProtocolFailure({ stage: "payload_integrity", reason: "INTEGRITY_FAILED", correlationMessageId: null });
    expect(permanent).toEqual({ code: "INTEGRITY_FAILED", stage: "payload_integrity", correlation_message_id: null });
    expect(validateErrorResponse({ ...permanent, retry_after: null, extra: true })).toBe(false);
    expect(() => classifyProtocolFailure({ stage: "payload_integrity", reason: "INTEGRITY_FAILED", correlationMessageId: null, retryAfterSeconds: 1n })).toThrowError("INTEGRITY_FAILED");
  });
});
