/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { loadProtocolProfile } from "../src/profile.js";
import * as schemaCatalog from "../src/schema-catalog.js";
import { validateSchema } from "../src/schema-validator.js";

type ProfileFixture = Record<string, unknown> & {
  profile_id: string;
  max_envelope_bytes: string;
};

const readProfile = (): ProfileFixture => JSON.parse(
  readFileSync(new URL("../profile/v1.json", import.meta.url), "utf8"),
) as ProfileFixture;

const PROFILE_SCHEMA_ID = "urn:open-android-intelligence:protocol:v1:profile";
const { PROTOCOL_SCHEMA_DOCUMENTS, REQUIRED_PROTOCOL_SCHEMA_IDS } = schemaCatalog;

describe("Draft 2020-12 schema validation", () => {
  it("loads a closed static data-only schema catalog and resolves every required ID", () => {
    expect(Object.isFrozen(PROTOCOL_SCHEMA_DOCUMENTS)).toBe(true);
    expect(PROTOCOL_SCHEMA_DOCUMENTS.every((document) => Object.isFrozen(document))).toBe(true);
    expect(Object.isFrozen(REQUIRED_PROTOCOL_SCHEMA_IDS)).toBe(true);
    expect(Object.keys(schemaCatalog).sort()).toEqual(["PROTOCOL_SCHEMA_DOCUMENTS", "REQUIRED_PROTOCOL_SCHEMA_IDS"]);
    const source = readFileSync(new URL("../src/schema-catalog.ts", import.meta.url), "utf8");
    const imports = [...source.matchAll(/^import .+ from "([^"]+)"/gm)].map((match) => match[1]);
    expect(imports).toHaveLength(14);
    expect(imports.every((specifier) => specifier?.startsWith("../schemas/v1/") && specifier.endsWith(".schema.json"))).toBe(true);
    expect(source).not.toMatch(/registerSchema|schema-validator|profile\.js|registries\/|runtime/);
    expect(PROTOCOL_SCHEMA_DOCUMENTS).toHaveLength(14);
    expect(REQUIRED_PROTOCOL_SCHEMA_IDS).toHaveLength(100);
    const task5LeafIds = REQUIRED_PROTOCOL_SCHEMA_IDS.filter((id) => /:v1:(message|header|envelope):(?:device|bridge|adapter)_(?:ping|presence|key_rotation)/.test(id));
    expect(task5LeafIds).toHaveLength(27);
    expect(REQUIRED_PROTOCOL_SCHEMA_IDS.some((id) => /family/.test(id))).toBe(false);
    for (const schemaId of REQUIRED_PROTOCOL_SCHEMA_IDS) {
      try {
        validateSchema(schemaId, fixtureFor(schemaId));
      } catch (error) {
        throw new Error(`${schemaId}: ${error instanceof Error ? error.message : String(error)}`);
      }
    }
  });

  it("accepts the exact committed profile fixture", () => {
    expect(() => validateSchema(PROFILE_SCHEMA_ID, readProfile())).not.toThrow();
  });

  it("rejects an unknown schema ID instead of selecting a fallback", () => {
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:unknown", readProfile()))
      .toThrowError("UNKNOWN_SCHEMA_ID");
  });

  it("rejects a JSON number where the profile requires a decimal string", () => {
    const profile = { ...readProfile(), max_envelope_bytes: 262_144 };
    expect(() => validateSchema(PROFILE_SCHEMA_ID, profile)).toThrowError("SCHEMA_INVALID");
  });

  it("enforces the mandatory arbitrary-precision decimal-u64 format", () => {
    const decimalU64SchemaId = `${PROFILE_SCHEMA_ID}#/$defs/decimalU64`;
    expect(() => validateSchema(decimalU64SchemaId, "18446744073709551615")).not.toThrow();
    expect(() => validateSchema(decimalU64SchemaId, "18446744073709551616"))
      .toThrowError("SCHEMA_INVALID");
  });

  it.each([
    ["0", true],
    ["18446744073709551615", true],
    ["18446744073709551616", false],
    ["01", false],
    ["-1", false],
  ])("enforces the common decimal-u64 boundary for %s", (value, valid) => {
    const run = () => validateSchema("urn:open-android-intelligence:protocol:v1:common#/$defs/decimal_u64", value);
    if (valid) expect(run).not.toThrow();
    else expect(run).toThrowError("SCHEMA_INVALID");
  });

  it.each([
    ["urn:open-android-intelligence:protocol:v1:common#/$defs/lowercase_uuid_v4", "018f4f9a-4444-4444-8444-444444444444", true],
    ["urn:open-android-intelligence:protocol:v1:common#/$defs/lowercase_uuid_v4", "018F4F9A-4444-4444-8444-444444444444", false],
    ["urn:open-android-intelligence:protocol:v1:common#/$defs/timestamp", "2026-08-08T00:00:00.000Z", true],
    ["urn:open-android-intelligence:protocol:v1:common#/$defs/timestamp", "2026-08-08T00:00:00Z", false],
    ["urn:open-android-intelligence:protocol:v1:common#/$defs/timestamp", "2026-02-30T00:00:00.000Z", false],
  ])("enforces custom format %s", (schemaId, value, valid) => {
    const run = () => validateSchema(schemaId, value);
    if (valid) expect(run).not.toThrow();
    else expect(run).toThrowError("SCHEMA_INVALID");
  });

  it("keeps enrollment error retry fields coupled to RATE_LIMITED", () => {
    const schemaId = "urn:open-android-intelligence:protocol:v1:message:enrollment_error";
    expect(() => validateSchema(schemaId, { code: "RATE_LIMITED", retry_after_seconds: "7" })).not.toThrow();
    expect(() => validateSchema(schemaId, { code: "RATE_LIMITED" })).toThrowError("SCHEMA_INVALID");
    expect(() => validateSchema(schemaId, { code: "AUTH_FAILED", retry_after_seconds: "7" })).toThrowError("SCHEMA_INVALID");
    expect(() => validateSchema(schemaId, { code: "RATE_LIMITED", retry_after_seconds: 7 })).toThrowError("SCHEMA_INVALID");
  });

  it.each([
    ["max_envelope_bytes", "262144"],
    ["max_clock_skew_seconds", "60"],
    ["replay_window_size", "1024"],
    ["key_rotation_grace_seconds", "900"],
    ["wait_read_max_seconds", "900"],
    ["channel_ticket_lifetime_seconds", "300"],
    ["artifact_chunk_min_bytes", "65536"],
    ["artifact_chunk_default_bytes", "262144"],
    ["artifact_chunk_max_bytes", "1048576"],
    ["artifact_max_files_per_message", "4"],
    ["artifact_max_file_bytes", "26214400"],
    ["artifact_max_message_bytes", "52428800"],
    ["artifact_orphan_lifetime_seconds", "86400"],
  ])("rejects drift in frozen profile field %s", (field, approved) => {
    const profile = { ...readProfile(), [field]: approved === "1" ? "2" : "1" };
    expect(() => validateSchema(PROFILE_SCHEMA_ID, profile)).toThrowError("SCHEMA_INVALID");
  });
});

const B32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const SIG = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const JWK = { alg: "ES256", crv: "P-256", kid: "key", kty: "EC", use: "sig", x: B32, y: B32 };
const TASK5_TYPES = [
  "device_ping", "bridge_ping", "device_presence",
  "device_key_rotation", "device_key_rotation_ack",
  "bridge_key_rotation", "bridge_key_rotation_ack",
  "adapter_key_rotation", "adapter_key_rotation_ack",
] as const;
type Task5Type = typeof TASK5_TYPES[number];
const TASK5_PAYLOADS: Record<Task5Type, unknown> = {
  device_ping: { challenge: B32 },
  bridge_ping: { challenge: B32 },
  device_presence: { state: "online" },
  device_key_rotation: { rotation_id: "018f4f9a-4444-4444-8444-444444444444", old_key_id: "key", new_public_jwk: JWK, new_key_thumbprint: B32, challenge: B32 },
  device_key_rotation_ack: { rotation_id: "018f4f9a-4444-4444-8444-444444444444", old_key_id: "key", new_key_id: "new-key", new_key_thumbprint: B32, challenge: B32, proposal_digest: B32 },
  bridge_key_rotation: { rotation_id: "018f4f9a-4444-4444-8444-444444444444", old_key_id: "key", new_public_jwk: JWK, new_key_thumbprint: B32, challenge: B32 },
  bridge_key_rotation_ack: { rotation_id: "018f4f9a-4444-4444-8444-444444444444", old_key_id: "key", new_key_id: "new-key", new_key_thumbprint: B32, challenge: B32, proposal_digest: B32 },
  adapter_key_rotation: { rotation_id: "018f4f9a-4444-4444-8444-444444444444", old_key_id: "key", new_public_jwk: JWK, new_key_thumbprint: B32, challenge: B32, next_adapter_credential_generation: "2" },
  adapter_key_rotation_ack: { rotation_id: "018f4f9a-4444-4444-8444-444444444444", old_key_id: "key", new_key_id: "new-key", new_key_thumbprint: B32, challenge: B32, next_adapter_credential_generation: "2", proposal_digest: B32 },
};
const TASK5_DIRECTIONS: Record<Task5Type, "app-to-bridge" | "bridge-to-app" | "adapter-to-bridge" | "bridge-to-adapter"> = {
  device_ping: "app-to-bridge",
  bridge_ping: "bridge-to-app",
  device_presence: "app-to-bridge",
  device_key_rotation: "app-to-bridge",
  device_key_rotation_ack: "bridge-to-app",
  bridge_key_rotation: "bridge-to-app",
  bridge_key_rotation_ack: "app-to-bridge",
  adapter_key_rotation: "adapter-to-bridge",
  adapter_key_rotation_ack: "bridge-to-adapter",
};
const EVENT_EPOCH = "018f4f9a-4444-4444-8444-444444444444";
const EVENT_PAYLOAD = {
  source_epoch: EVENT_EPOCH, occurrence_id: "018f4f9a-4444-4444-8444-444444444445", record_key: "notification.n1", record_revision: "2",
  cursor: "9", captured_at: "2026-08-08T00:00:00.000Z", event_kind: "upsert", source_capability: "notifications.metadata",
  capture_revision: { pairing_generation: "1", authorization_epoch: "1", scope_revisions: { "notifications.metadata": "1" } }, record: {}, loss: null,
};
const EVENT_ACK_PAYLOAD = { source_epoch: EVENT_EPOCH, source_capability: "notifications.metadata", highest_contiguous_cursor: "9" };
const task5Header = (type: Task5Type) => {
  const base = {
    protocol_version: "1.0", message_schema: `urn:open-android-intelligence:protocol:v1:message:${type}`,
    message_type: type, message_id: "018f4f9a-4444-4444-8444-444444444444",
    key_id: "key", direction: TASK5_DIRECTIONS[type], sequence: "1",
    issued_at: "2026-08-08T00:00:00.000Z", expires_at: "2026-08-08T00:01:00.000Z",
    payload_digest: B32,
  };
  return type.startsWith("adapter_")
    ? { ...base, adapter_credential_id: "adapter-credential", adapter_credential_generation: "1" }
    : { ...base, device_id: "device", pairing_generation: "1", connection_generation: "1" };
};

const NEW_MESSAGE_TYPES = [
  "operation_submit", "operation_get", "operation_wait", "operation_cancel", "operation_reconcile",
  "operation_command", "operation_snapshot", "operation_receipt", "operation_receipt_ack", "receipt_replay",
  "device_protocol_error", "bridge_protocol_error", "adapter_protocol_error",
] as const;
type NewMessageType = typeof NEW_MESSAGE_TYPES[number];
const NEW_UUID = "018f4f9a-4444-4444-8444-444444444444";
const BASE_ADAPTER = {
  tenant_id: "tenant", human_principal_id: "human", agent_principal_id: "agent",
  agent_instance_id: "instance", workspace_id: "workspace",
  session_or_job: { kind: "session", session_id: "session", job_id: null },
  device_id: "device", operation_id: "operation",
};
const REVISION = { pairing_generation: "1", authorization_epoch: "1", scope_revisions: { "notifications.metadata": "1" } };
const BASE_BOUND = { ...BASE_ADAPTER, capability: "notifications.metadata", parameters_digest: B32, revision_snapshot: REVISION };
const OPERATION_STATE = { request_status: "created", terminal_outcome: null, operation_reason: null };
const NEW_PAYLOADS: Record<NewMessageType, Record<string, unknown>> = {
  operation_submit: { ...BASE_ADAPTER, capability: "notifications.metadata", parameters: {}, parameters_digest: B32, revision_snapshot: REVISION, operation_expires_at: "2026-08-08T00:15:00.000Z" },
  operation_get: { ...BASE_ADAPTER },
  operation_wait: { ...BASE_ADAPTER, after_state_revision: "0", wait_timeout_ms: "0" },
  operation_cancel: { ...BASE_ADAPTER },
  operation_reconcile: { ...BASE_ADAPTER },
  operation_command: { ...BASE_BOUND, parameters: {}, operation_expires_at: "2026-08-08T00:15:00.000Z" },
  operation_snapshot: { ...BASE_BOUND, state_revision: "0", state: OPERATION_STATE, reconciliation: null },
  operation_receipt: { ...BASE_BOUND, state_revision: "0", state: { request_status: null, terminal_outcome: "cancelled", operation_reason: null }, reconciliation: null, result_digest: null },
  operation_receipt_ack: { ...BASE_BOUND, receipt_message_id: NEW_UUID, receipt_envelope_digest: B32, accepted_state_revision: "0", bridge_ack_at: "2026-08-08T00:15:00.000Z" },
  receipt_replay: { original_receipt_wire_b64: "AAAA", original_receipt_digest: B32 },
  device_protocol_error: { code: "SCHEMA_INVALID", stage: "canonical_schema", correlation_message_id: null, retry_after: null },
  bridge_protocol_error: { code: "SCHEMA_INVALID", stage: "canonical_schema", correlation_message_id: null, retry_after: null },
  adapter_protocol_error: { code: "SCHEMA_INVALID", stage: "canonical_schema", correlation_message_id: null, retry_after: null },
};
const NEW_DIRECTIONS: Record<NewMessageType, "app-to-bridge" | "bridge-to-app" | "adapter-to-bridge" | "bridge-to-adapter"> = {
  operation_submit: "adapter-to-bridge", operation_get: "adapter-to-bridge", operation_wait: "adapter-to-bridge", operation_cancel: "adapter-to-bridge", operation_reconcile: "adapter-to-bridge",
  operation_command: "bridge-to-app", operation_snapshot: "bridge-to-adapter", operation_receipt: "app-to-bridge", operation_receipt_ack: "bridge-to-app", receipt_replay: "app-to-bridge",
  device_protocol_error: "app-to-bridge", bridge_protocol_error: "bridge-to-app", adapter_protocol_error: "bridge-to-adapter",
};
const newHeader = (type: NewMessageType): Record<string, unknown> => {
  const base: Record<string, unknown> = {
    protocol_version: "1.0", message_schema: `urn:open-android-intelligence:protocol:v1:message:${type}`, message_type: type,
    message_id: NEW_UUID, key_id: "key", direction: NEW_DIRECTIONS[type], sequence: "1",
    issued_at: "2026-08-08T00:00:00.000Z", expires_at: "2026-08-08T00:01:00.000Z", payload_digest: B32,
  };
  if (NEW_DIRECTIONS[type] === "adapter-to-bridge" || NEW_DIRECTIONS[type] === "bridge-to-adapter") return { ...base, adapter_credential_id: "adapter", adapter_credential_generation: "1" };
  return { ...base, device_id: "device", pairing_generation: "1", connection_generation: "1" };
};
const operationRecordFixture = {
  operation_id: "operation", binding: {
    tenant_id: "tenant", human_principal_id: "human", agent_principal_id: "agent", agent_instance_id: "instance", workspace_id: "workspace",
    session_or_job: { kind: "session", session_id: "session", job_id: null }, device_id: "device", operation_id: "operation", capability: "notifications.metadata", parameters_digest: B32,
  }, operation_expires_at: "2026-08-08T00:15:00.000Z", offline_policy: "WAIT_READ", state_revision: "0", state: OPERATION_STATE, reconciliation: null,
};
const receiptFixture = NEW_PAYLOADS.operation_receipt;
const errorRegistryFixture = { $schema: "urn:open-android-intelligence:protocol:v1:errors-registry", registry_id: "urn:open-android-intelligence:protocol:v1:registry:errors", protocol_version: "1.0", errors: [{ code: "SCHEMA_INVALID", stage: "canonical_schema", retryable: false, operation_reason: null, internal_reason: "NONE" }] };
const fixtureFor = (schemaId: string): unknown => {
  const fixtures: Record<string, unknown> = {
    "urn:open-android-intelligence:protocol:v1:profile": readProfile(),
    "urn:open-android-intelligence:protocol:v1:common": {},
    "urn:open-android-intelligence:protocol:v1:enrollment": {},
    "urn:open-android-intelligence:protocol:v1:connect": {},
    "urn:open-android-intelligence:protocol:v1:control-envelope": {},
    "urn:open-android-intelligence:protocol:v1:event": {},
    "urn:open-android-intelligence:protocol:v1:key-rotation": {},
    "urn:open-android-intelligence:protocol:v1:messages-registry": { $schema: "urn:open-android-intelligence:protocol:v1:messages-registry", registry_id: "urn:open-android-intelligence:protocol:v1:registry:messages", protocol_version: "1.0", messages: [] },
    "urn:open-android-intelligence:protocol:v1:versions-registry": { $schema: "urn:open-android-intelligence:protocol:v1:versions-registry", registry_id: "urn:open-android-intelligence:protocol:v1:registry:versions", protocol_version: "1.0", versions: [{ version: "1.0", negotiable: true }, { version: "0.9", negotiable: false, fixture_owner: "Task7" }] },
    "urn:open-android-intelligence:protocol:v1:message:enrollment_challenge": { challenge: B32, bridge_nonce: B32, bridge_fingerprint: B32, bridge_command_public_jwk: JWK, supported_versions: ["1.0"] },
    "urn:open-android-intelligence:protocol:v1:message:enrollment_response": { ticket: B32, challenge_response: B32, device_public_jwk: JWK, client_nonce: B32, supported_versions: ["1.0"] },
    "urn:open-android-intelligence:protocol:v1:message:enrollment_complete": { device_id: "device", pairing_generation: "1", tenant_id: "tenant", human_principal_id: "human", agent_instance_id: "agent", enrollment_scope_ceiling: [], selected_protocol: "1.0", client_nonce: B32, bridge_nonce: B32, bridge_fingerprint: B32, device_jwk_thumbprint: B32 },
    "urn:open-android-intelligence:protocol:v1:message:enrollment_error": { code: "AUTH_FAILED" },
    "urn:open-android-intelligence:protocol:v1:message:connect_hello": { client_nonce: B32, supported_versions: ["1.0"], last_manifest_generation: null, last_event_cursor: null },
    "urn:open-android-intelligence:protocol:v1:message:connect_welcome": { client_offer_digest: B32, client_nonce: B32, bridge_nonce: B32, selected_protocol: "1.0", bridge_time: "2026-08-08T00:00:00.000Z", command_key_set: { current: JWK, next: null }, connection_generation: "1" },
  };
  const payload = fixtures[schemaId];
  if (payload !== undefined) return payload;
  for (const type of TASK5_TYPES) {
    if (schemaId === `urn:open-android-intelligence:protocol:v1:message:${type}`) return TASK5_PAYLOADS[type];
    if (schemaId === `urn:open-android-intelligence:protocol:v1:header:${type}`) return task5Header(type);
    if (schemaId === `urn:open-android-intelligence:protocol:v1:envelope:${type}`) {
      return { header: task5Header(type), payload: TASK5_PAYLOADS[type], signature: SIG };
    }
  }
  if (schemaId === "urn:open-android-intelligence:protocol:v1:message:device_event") return EVENT_PAYLOAD;
  if (schemaId === "urn:open-android-intelligence:protocol:v1:message:event_ack") return EVENT_ACK_PAYLOAD;
  const enrollmentHeader = { protocol_version: "1.0", message_schema: "urn:open-android-intelligence:protocol:v1:message:enrollment_response", message_type: "enrollment_response", message_id: "018f4f9a-4444-4444-8444-444444444444", key_id: "key", direction: "app-to-bridge", issued_at: "2026-08-08T00:00:00.000Z", expires_at: "2026-08-08T00:05:00.000Z", payload_digest: B32, enrollment_ticket_digest: B32 };
  const connectHeader = { protocol_version: "1.0", message_schema: "urn:open-android-intelligence:protocol:v1:message:connect_hello", message_type: "connect_hello", message_id: "018f4f9a-4444-4444-8444-444444444444", key_id: "key", direction: "app-to-bridge", sequence: "1", issued_at: "2026-08-08T00:00:00.000Z", expires_at: "2026-08-08T00:05:00.000Z", payload_digest: B32, device_id: "device", pairing_generation: "1" };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:header:enrollment_app_to_bridge") return enrollmentHeader;
  if (schemaId === "urn:open-android-intelligence:protocol:v1:header:enrollment_bridge_to_app") return { ...enrollmentHeader, message_type: "enrollment_challenge", message_schema: "urn:open-android-intelligence:protocol:v1:message:enrollment_challenge", direction: "bridge-to-app" };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:header:connect_hello") return connectHeader;
  if (schemaId === "urn:open-android-intelligence:protocol:v1:header:connect_welcome") return { ...connectHeader, message_type: "connect_welcome", message_schema: "urn:open-android-intelligence:protocol:v1:message:connect_welcome", direction: "bridge-to-app" };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:envelope:enrollment_app_to_bridge") return { header: enrollmentHeader, payload: fixtures["urn:open-android-intelligence:protocol:v1:message:enrollment_response"], signature: SIG };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:envelope:enrollment_bridge_to_app") return { header: { ...enrollmentHeader, message_type: "enrollment_challenge", message_schema: "urn:open-android-intelligence:protocol:v1:message:enrollment_challenge", direction: "bridge-to-app" }, payload: fixtures["urn:open-android-intelligence:protocol:v1:message:enrollment_challenge"], signature: SIG };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:envelope:connect_hello") return { header: connectHeader, payload: fixtures["urn:open-android-intelligence:protocol:v1:message:connect_hello"], signature: SIG };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:envelope:connect_welcome") return { header: { ...connectHeader, message_type: "connect_welcome", message_schema: "urn:open-android-intelligence:protocol:v1:message:connect_welcome", direction: "bridge-to-app" }, payload: fixtures["urn:open-android-intelligence:protocol:v1:message:connect_welcome"], signature: SIG };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:header:device_event") return { ...connectHeader, message_type: "device_event", message_schema: "urn:open-android-intelligence:protocol:v1:message:device_event", direction: "app-to-bridge", expires_at: "2026-08-09T00:00:00.000Z", connection_generation: "1" };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:header:event_ack") return { ...connectHeader, message_type: "event_ack", message_schema: "urn:open-android-intelligence:protocol:v1:message:event_ack", direction: "bridge-to-app", connection_generation: "1" };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:envelope:device_event") return { header: { ...connectHeader, message_type: "device_event", message_schema: "urn:open-android-intelligence:protocol:v1:message:device_event", direction: "app-to-bridge", expires_at: "2026-08-09T00:00:00.000Z", connection_generation: "1" }, payload: EVENT_PAYLOAD, signature: SIG };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:envelope:event_ack") return { header: { ...connectHeader, message_type: "event_ack", message_schema: "urn:open-android-intelligence:protocol:v1:message:event_ack", direction: "bridge-to-app", connection_generation: "1" }, payload: EVENT_ACK_PAYLOAD, signature: SIG };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:operation") return operationRecordFixture;
  if (schemaId === "urn:open-android-intelligence:protocol:v1:receipt") return receiptFixture;
  if (schemaId === "urn:open-android-intelligence:protocol:v1:migration-receipt") return { migration_id: "migration", source_schema_id: "urn:open-android-intelligence:protocol:v0.9:pending-operation", source_record_digest: B32, source_signature: SIG, target_schema_id: "urn:open-android-intelligence:protocol:v1:operation", target_record_digest: B32, target_record_id: "operation", migrated_at: "2026-08-08T00:00:00.000Z" };
  if (schemaId === "urn:open-android-intelligence:protocol:v1:error-response") return NEW_PAYLOADS.device_protocol_error;
  if (schemaId === "urn:open-android-intelligence:protocol:v1:errors-registry") return errorRegistryFixture;
  for (const type of NEW_MESSAGE_TYPES) {
    if (schemaId === `urn:open-android-intelligence:protocol:v1:message:${type}`) return NEW_PAYLOADS[type];
    if (schemaId === `urn:open-android-intelligence:protocol:v1:header:${type}`) return newHeader(type);
    if (schemaId === `urn:open-android-intelligence:protocol:v1:envelope:${type}`) return { header: newHeader(type), payload: NEW_PAYLOADS[type], signature: SIG };
  }
  throw new Error(`missing fixture ${schemaId}`);
};

describe("validated protocol profile", () => {
  it("derives every runtime constant from the machine-readable profile", () => {
    expect(loadProtocolProfile()).toMatchObject({
      profile_id: "open-android-intelligence-json-es256/1.0",
      max_envelope_bytes: "262144",
      replay_window_size: "1024",
      key_rotation_grace_seconds: "900",
      wait_read_max_seconds: "900",
    });
  });
});
