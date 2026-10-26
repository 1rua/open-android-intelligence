import { describe, expect, it } from "vitest";
import {
  createOperationRecord,
  reduceOperation,
  type OperationEvent,
} from "../src/operation-machine.js";

const binding = {
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  agentPrincipalId: "agent-a",
  agentInstanceId: "instance-a",
  workspaceId: "workspace-a",
  sessionOrJob: { kind: "session", sessionId: "session-a" } as const,
  deviceId: "device-a",
  operationId: "operation-a",
  capability: "notifications.content",
  parametersDigest: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
};

const revisionSnapshot = {
  pairingGeneration: 1n,
  authorizationEpoch: 2n,
  scopeRevisions: { "notifications.content": 3n },
};

function record(offlinePolicy: "WAIT_READ" | "FAIL_OFFLINE" = "WAIT_READ") {
  return createOperationRecord({
    binding,
    revisionSnapshot,
    operationExpiresAt: "2026-08-11T00:00:30.000Z",
    offlinePolicy,
    state: { request_status: "created", terminal_outcome: null, operation_reason: null },
  });
}

describe("closed operation reducer", () => {
  it("accepts only the frozen legal path and increments revisions once", () => {
    let current = record();
    for (const event of [
      { type: "queue_wait_read" },
      { type: "dispatch" },
      { type: "device_accepted" },
      { type: "approval_not_required" },
      { type: "execution_claimed" },
      { type: "execution_succeeded" },
    ] satisfies readonly OperationEvent[]) {
      const result = reduceOperation(current, event, "2026-08-11T00:00:00.000Z");
      expect(result.ok).toBe(true);
      if (result.ok) current = result.record;
    }
    expect(current.stateRevision).toBe(6n);
    expect(current.state).toEqual({ request_status: null, terminal_outcome: "succeeded", operation_reason: null });
    const invalid = reduceOperation(current, { type: "cancel" });
    expect(invalid).toEqual({ ok: false, error: "INVALID_STATE_TRANSITION", record: current });
  });

  it("enforces WAIT_READ and the exact 900 second boundary", () => {
    const waiting = record();
    expect(reduceOperation(waiting, { type: "queue_wait_read" }, "2026-08-11T00:00:00.000Z").ok).toBe(true);
    expect(reduceOperation(waiting, { type: "queue_wait_read" }, "2026-08-10T23:44:59.999Z").ok).toBe(false);
    expect(reduceOperation(record("FAIL_OFFLINE"), { type: "queue_wait_read" }, "2026-08-11T00:00:00.000Z").ok).toBe(false);
  });

  it("makes reconciliation idempotent and conflicting evidence fail closed", () => {
    let current = record();
    for (const event of [
      { type: "dispatch" },
      { type: "device_accepted" },
      { type: "approval_not_required" },
      { type: "execution_claimed" },
      { type: "execution_result_unknown" },
      { type: "reconcile_evidence", outcome: "succeeded", evidenceDigest: "digest-a", observedAt: "2026-08-11T00:00:01.000Z" },
    ] satisfies readonly OperationEvent[]) {
      const result = reduceOperation(current, event);
      expect(result.ok).toBe(true);
      if (result.ok) current = result.record;
    }
    expect(current.stateRevision).toBe(6n);
    expect(reduceOperation(current, { type: "reconcile_evidence", outcome: "succeeded", evidenceDigest: "digest-a", observedAt: "2026-08-11T00:00:01.000Z" })).toEqual({ ok: true, record: current, changed: false });
    const conflict = reduceOperation(current, { type: "reconcile_evidence", outcome: "failed", evidenceDigest: "digest-b", observedAt: "2026-08-11T00:00:02.000Z" });
    expect(conflict).toEqual({ ok: false, error: "RESULT_CONFLICT", record: current });
  });

  it("rejects extra fields and invalid terminal reason combinations", () => {
    expect(() => createOperationRecord({
      binding: { ...binding, extra: true },
      revisionSnapshot,
      operationExpiresAt: "2026-08-11T00:00:30.000Z",
      offlinePolicy: "WAIT_READ",
      state: { request_status: "created", terminal_outcome: null, operation_reason: null },
    } as never)).toThrow("SCHEMA_INVALID");
    const denied = reduceOperation(record(), { type: "deny", reason: "USER_DENIED" });
    expect(denied.ok).toBe(true);
    if (denied.ok) expect(denied.record.state).toEqual({ request_status: null, terminal_outcome: "denied", operation_reason: "USER_DENIED" });
  });
});
