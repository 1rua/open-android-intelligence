import { describe, expect, it } from "vitest";
import { validateSchema } from "../src/schema-validator.js";

const DIGEST = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const UUID = "018f4f9a-4444-4444-8444-444444444444";
const session = { kind: "session", session_id: "session", job_id: null };
const revision = { pairing_generation: "1", authorization_epoch: "1", scope_revisions: { "notifications.metadata": "1" } };
const adapterBase = {
  tenant_id: "tenant", human_principal_id: "human", agent_principal_id: "agent", agent_instance_id: "instance", workspace_id: "workspace",
  session_or_job: session, device_id: "device", operation_id: "operation",
};
const bound = { ...adapterBase, capability: "notifications.metadata", parameters_digest: DIGEST, revision_snapshot: revision };
const pending = { request_status: "created", terminal_outcome: null, operation_reason: null };

describe("Task 7 closed wire schemas", () => {
  it("rejects unknown members and action cross-use for adapter requests", () => {
    const submit = { ...adapterBase, capability: "notifications.metadata", parameters: {}, parameters_digest: DIGEST, revision_snapshot: revision, operation_expires_at: "2026-08-11T00:15:00.000Z" };
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_submit", submit)).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_submit", { ...submit, unknown: true })).toThrowError("SCHEMA_INVALID");
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_get", { ...adapterBase, parameters: {} })).toThrowError("SCHEMA_INVALID");
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_wait", { ...adapterBase, after_state_revision: "0", wait_timeout_ms: "30001" })).toThrowError("SCHEMA_INVALID");
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_wait", { ...adapterBase, after_state_revision: "0", wait_timeout_ms: "30000" })).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_cancel", { ...adapterBase, outcome: "succeeded" })).toThrowError("SCHEMA_INVALID");
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_reconcile", { ...adapterBase, evidence: {} })).toThrowError("SCHEMA_INVALID");
  });

  it("keeps command/snapshot and receipt branches bound and content-free", () => {
    const command = { ...bound, parameters: {}, operation_expires_at: "2026-08-11T00:15:00.000Z" };
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_command", command)).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_command", { ...command, result: {} })).toThrowError("SCHEMA_INVALID");
    const snapshot = { ...bound, state_revision: "0", state: pending, reconciliation: null };
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_snapshot", snapshot)).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_snapshot", { ...snapshot, parameters: {} })).toThrowError("SCHEMA_INVALID");
    const noResult = { ...bound, state_revision: "1", state: { request_status: null, terminal_outcome: "cancelled", operation_reason: null }, reconciliation: null, result_digest: null };
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_receipt", noResult)).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_receipt", { ...noResult, result: { kind: "system_ui_handoff", external_side_effect_status: "not_observed" } })).toThrowError("SCHEMA_INVALID");
    const withResult = { ...bound, state_revision: "1", state: { request_status: null, terminal_outcome: "succeeded", operation_reason: null }, reconciliation: null, result_digest: DIGEST, result: { kind: "system_ui_handoff", external_side_effect_status: "not_observed" } };
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_receipt", withResult)).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:operation_receipt", { ...withResult, result: { kind: "confirmed_send" } })).toThrowError("SCHEMA_INVALID");
  });

  it("requires exact receipt replay and retry-field coupling for protocol errors", () => {
    const replay = { original_receipt_wire_b64: "AAAA", original_receipt_digest: DIGEST };
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:receipt_replay", replay)).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:receipt_replay", { ...replay, extra: true })).toThrowError("SCHEMA_INVALID");
    const error = { code: "SCHEMA_INVALID", stage: "canonical_schema", correlation_message_id: null, retry_after: null };
    for (const type of ["device_protocol_error", "bridge_protocol_error", "adapter_protocol_error"]) {
      expect(() => validateSchema(`urn:open-android-intelligence:protocol:v1:message:${type}`, error)).not.toThrow();
      expect(() => validateSchema(`urn:open-android-intelligence:protocol:v1:message:${type}`, { ...error, retry_after: "5" })).toThrowError("SCHEMA_INVALID");
    }
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:adapter_protocol_error", { ...error, code: "SECURITY_LEDGER_FULL", retry_after: "5" })).not.toThrow();
  });
});
