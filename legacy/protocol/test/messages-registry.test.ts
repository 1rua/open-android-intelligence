/// <reference types="node" />

import { describe, expect, it } from "vitest";
import { canonicalBytes } from "../src/encoding.js";
import { PROTOCOL_SCHEMA_DOCUMENTS } from "../src/schema-catalog.js";
import { loadProtocolProfile } from "../src/profile.js";
import {
  loadMessageRegistry,
  verifyConnectMessage,
  verifyEnrollmentBridgeMessage,
  type ConnectMessageAdmissionContext,
  type EnrollmentBridgeAdmissionContext,
} from "../src/message-registry.js";
import { validateSchema } from "../src/schema-validator.js";

const B32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const SIG = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const JWK = { alg: "ES256", crv: "P-256", kid: "key", kty: "EC", use: "sig", x: B32, y: B32 } as const;
const TIMESTAMP = "2026-08-08T00:00:00.000Z";
const MESSAGE_ID = "018f4f9a-4444-4444-8444-444444444444";

const payloadFixtures = {
  enrollment_challenge: { challenge: B32, bridge_nonce: B32, bridge_fingerprint: B32, bridge_command_public_jwk: JWK, supported_versions: ["1.0"] },
  enrollment_response: { ticket: B32, challenge_response: B32, device_public_jwk: JWK, client_nonce: B32, supported_versions: ["1.0"] },
  enrollment_complete: { device_id: "device", pairing_generation: "1", tenant_id: "tenant", human_principal_id: "human", agent_instance_id: "agent", enrollment_scope_ceiling: [], selected_protocol: "1.0", client_nonce: B32, bridge_nonce: B32, bridge_fingerprint: B32, device_jwk_thumbprint: B32 },
  enrollment_error: { code: "AUTH_FAILED" },
  connect_hello: { client_nonce: B32, supported_versions: ["1.0"], last_manifest_generation: null, last_event_cursor: null },
  connect_welcome: { client_offer_digest: B32, client_nonce: B32, bridge_nonce: B32, selected_protocol: "1.0", bridge_time: TIMESTAMP, command_key_set: { current: JWK, next: null }, connection_generation: "1" },
  device_ping: { challenge: B32 },
  bridge_ping: { challenge: B32 },
  device_presence: { state: "online" },
  device_key_rotation: { rotation_id: MESSAGE_ID, old_key_id: "key", new_public_jwk: JWK, new_key_thumbprint: B32, challenge: B32 },
  device_key_rotation_ack: { rotation_id: MESSAGE_ID, old_key_id: "key", new_key_id: "new-key", new_key_thumbprint: B32, challenge: B32, proposal_digest: B32 },
  bridge_key_rotation: { rotation_id: MESSAGE_ID, old_key_id: "key", new_public_jwk: JWK, new_key_thumbprint: B32, challenge: B32 },
  bridge_key_rotation_ack: { rotation_id: MESSAGE_ID, old_key_id: "key", new_key_id: "new-key", new_key_thumbprint: B32, challenge: B32, proposal_digest: B32 },
  adapter_key_rotation: { rotation_id: MESSAGE_ID, old_key_id: "key", new_public_jwk: JWK, new_key_thumbprint: B32, challenge: B32, next_adapter_credential_generation: "2" },
  adapter_key_rotation_ack: { rotation_id: MESSAGE_ID, old_key_id: "key", new_key_id: "new-key", new_key_thumbprint: B32, challenge: B32, next_adapter_credential_generation: "2", proposal_digest: B32 },
  device_event: {
    source_epoch: "018f4f9a-4444-4444-8444-444444444444", occurrence_id: "018f4f9a-4444-4444-8444-444444444445",
    record_key: "notification.n1", record_revision: "2", cursor: "9", captured_at: TIMESTAMP,
    event_kind: "upsert", source_capability: "notifications.metadata",
    capture_revision: { pairing_generation: "1", authorization_epoch: "1", scope_revisions: { "notifications.metadata": "1" } },
    record: {}, loss: null,
  },
  event_ack: { source_epoch: "018f4f9a-4444-4444-8444-444444444444", source_capability: "notifications.metadata", highest_contiguous_cursor: "9" },
} as const;

type MessageType = keyof typeof payloadFixtures;
const expectedByTask = {
  task4: [
    ["enrollment_challenge", "bridge-to-app", "enrollment/bridge-to-app", "urn:open-android-intelligence:protocol:v1:message:enrollment_challenge", "bridge-command"],
    ["enrollment_response", "app-to-bridge", "enrollment/app-to-bridge", "urn:open-android-intelligence:protocol:v1:message:enrollment_response", "device"],
    ["enrollment_complete", "bridge-to-app", "enrollment/bridge-to-app", "urn:open-android-intelligence:protocol:v1:message:enrollment_complete", "bridge-command"],
    ["enrollment_error", "bridge-to-app", "enrollment/bridge-to-app", "urn:open-android-intelligence:protocol:v1:message:enrollment_error", "bridge-command"],
    ["connect_hello", "app-to-bridge", "control/app-to-bridge", "urn:open-android-intelligence:protocol:v1:message:connect_hello", "device"],
    ["connect_welcome", "bridge-to-app", "control/bridge-to-app", "urn:open-android-intelligence:protocol:v1:message:connect_welcome", "bridge-command"],
    ["device_ping", "app-to-bridge", "control/app-to-bridge", "urn:open-android-intelligence:protocol:v1:message:device_ping", "device"],
    ["bridge_ping", "bridge-to-app", "control/bridge-to-app", "urn:open-android-intelligence:protocol:v1:message:bridge_ping", "bridge-command"],
    ["device_presence", "app-to-bridge", "control/app-to-bridge", "urn:open-android-intelligence:protocol:v1:message:device_presence", "device"],
    ["device_key_rotation", "app-to-bridge", "key-rotation/app-to-bridge", "urn:open-android-intelligence:protocol:v1:message:device_key_rotation", "device"],
    ["device_key_rotation_ack", "bridge-to-app", "key-rotation/bridge-to-app", "urn:open-android-intelligence:protocol:v1:message:device_key_rotation_ack", "bridge-command"],
    ["bridge_key_rotation", "bridge-to-app", "key-rotation/bridge-to-app", "urn:open-android-intelligence:protocol:v1:message:bridge_key_rotation", "bridge-command"],
    ["bridge_key_rotation_ack", "app-to-bridge", "key-rotation/app-to-bridge", "urn:open-android-intelligence:protocol:v1:message:bridge_key_rotation_ack", "device"],
    ["adapter_key_rotation", "adapter-to-bridge", "key-rotation/adapter-to-bridge", "urn:open-android-intelligence:protocol:v1:message:adapter_key_rotation", "adapter"],
    ["adapter_key_rotation_ack", "bridge-to-adapter", "key-rotation/bridge-to-adapter", "urn:open-android-intelligence:protocol:v1:message:adapter_key_rotation_ack", "bridge-command"],
  ],
  task7: [
    ["operation_submit", "adapter-to-bridge", "adapter/adapter-to-bridge", "urn:open-android-intelligence:protocol:v1:message:operation_submit", "adapter"],
    ["operation_get", "adapter-to-bridge", "adapter/adapter-to-bridge", "urn:open-android-intelligence:protocol:v1:message:operation_get", "adapter"],
    ["operation_wait", "adapter-to-bridge", "adapter/adapter-to-bridge", "urn:open-android-intelligence:protocol:v1:message:operation_wait", "adapter"],
    ["operation_cancel", "adapter-to-bridge", "adapter/adapter-to-bridge", "urn:open-android-intelligence:protocol:v1:message:operation_cancel", "adapter"],
    ["operation_reconcile", "adapter-to-bridge", "adapter/adapter-to-bridge", "urn:open-android-intelligence:protocol:v1:message:operation_reconcile", "adapter"],
    ["operation_command", "bridge-to-app", "control/bridge-to-app", "urn:open-android-intelligence:protocol:v1:message:operation_command", "bridge-command"],
    ["operation_receipt", "app-to-bridge", "receipt/device", "urn:open-android-intelligence:protocol:v1:message:operation_receipt", "device"],
    ["operation_receipt_ack", "bridge-to-app", "control/bridge-to-app", "urn:open-android-intelligence:protocol:v1:message:operation_receipt_ack", "bridge-command"],
    ["receipt_replay", "app-to-bridge", "control/app-to-bridge", "urn:open-android-intelligence:protocol:v1:message:receipt_replay", "device"],
    ["operation_snapshot", "bridge-to-adapter", "adapter/bridge-to-adapter", "urn:open-android-intelligence:protocol:v1:message:operation_snapshot", "bridge-command"],
    ["device_protocol_error", "app-to-bridge", "control/app-to-bridge", "urn:open-android-intelligence:protocol:v1:message:device_protocol_error", "device"],
    ["bridge_protocol_error", "bridge-to-app", "control/bridge-to-app", "urn:open-android-intelligence:protocol:v1:message:bridge_protocol_error", "bridge-command"],
    ["adapter_protocol_error", "bridge-to-adapter", "adapter/bridge-to-adapter", "urn:open-android-intelligence:protocol:v1:message:adapter_protocol_error", "bridge-command"],
  ],
} as const;

const registryByType = new Map(loadMessageRegistry().messages.map((entry) => [entry.message_type as MessageType, entry]));
const enrollmentTypes = new Set<MessageType>(["enrollment_challenge", "enrollment_response", "enrollment_complete", "enrollment_error"]);
const adapterTypes = new Set<MessageType>(["adapter_key_rotation", "adapter_key_rotation_ack"]);
const task5Types = new Set<MessageType>([
  "device_ping", "bridge_ping", "device_presence", "device_key_rotation", "device_key_rotation_ack",
  "bridge_key_rotation", "bridge_key_rotation_ack", "adapter_key_rotation", "adapter_key_rotation_ack",
]);
const task9Types = new Set<MessageType>(["device_event", "event_ack"]);

const headerFor = (type: MessageType) => {
  const entry = registryByType.get(type);
  if (!entry) throw new Error("missing registry entry");
  const common = {
    protocol_version: "1.0", message_schema: entry.schema_id, message_type: type,
    message_id: MESSAGE_ID, key_id: "key", direction: entry.direction,
    issued_at: TIMESTAMP, expires_at: "2026-08-08T00:05:00.000Z", payload_digest: B32,
  };
  if (enrollmentTypes.has(type)) return { ...common, enrollment_ticket_digest: B32 };
  if (adapterTypes.has(type)) return { ...common, sequence: "1", adapter_credential_id: "adapter-credential", adapter_credential_generation: "1" };
  if (task5Types.has(type) || task9Types.has(type)) return { ...common, sequence: "1", device_id: "device", pairing_generation: "1", connection_generation: "1" };
  return { ...common, sequence: "1", device_id: "device", pairing_generation: "1" };
};

const envelopeFor = (type: MessageType) => ({ header: headerFor(type), payload: payloadFixtures[type], signature: SIG });
const payloadRequired: Record<MessageType, readonly string[]> = {
  enrollment_challenge: ["challenge", "bridge_nonce", "bridge_fingerprint", "bridge_command_public_jwk", "supported_versions"],
  enrollment_response: ["ticket", "challenge_response", "device_public_jwk", "client_nonce", "supported_versions"],
  enrollment_complete: ["device_id", "pairing_generation", "tenant_id", "human_principal_id", "agent_instance_id", "enrollment_scope_ceiling", "selected_protocol", "client_nonce", "bridge_nonce", "bridge_fingerprint", "device_jwk_thumbprint"],
  enrollment_error: ["code"],
  connect_hello: ["client_nonce", "supported_versions", "last_manifest_generation", "last_event_cursor"],
  connect_welcome: ["client_offer_digest", "client_nonce", "bridge_nonce", "selected_protocol", "bridge_time", "command_key_set", "connection_generation"],
  device_ping: ["challenge"],
  bridge_ping: ["challenge"],
  device_presence: ["state"],
  device_key_rotation: ["rotation_id", "old_key_id", "new_public_jwk", "new_key_thumbprint", "challenge"],
  device_key_rotation_ack: ["rotation_id", "old_key_id", "new_key_id", "new_key_thumbprint", "challenge", "proposal_digest"],
  bridge_key_rotation: ["rotation_id", "old_key_id", "new_public_jwk", "new_key_thumbprint", "challenge"],
  bridge_key_rotation_ack: ["rotation_id", "old_key_id", "new_key_id", "new_key_thumbprint", "challenge", "proposal_digest"],
  adapter_key_rotation: ["rotation_id", "old_key_id", "new_public_jwk", "new_key_thumbprint", "challenge", "next_adapter_credential_generation"],
  adapter_key_rotation_ack: ["rotation_id", "old_key_id", "new_key_id", "new_key_thumbprint", "challenge", "next_adapter_credential_generation", "proposal_digest"],
  device_event: ["source_epoch", "occurrence_id", "record_key", "record_revision", "cursor", "captured_at", "event_kind", "source_capability", "capture_revision", "record", "loss"],
  event_ack: ["source_epoch", "source_capability", "highest_contiguous_cursor"],
};
const familyCases = [
  ["enrollment_app_to_bridge", "enrollment_response"],
  ["enrollment_bridge_to_app", "enrollment_challenge"],
  ["enrollment_bridge_to_app", "enrollment_complete"],
  ["enrollment_bridge_to_app", "enrollment_error"],
  ["connect_hello", "connect_hello"],
  ["connect_welcome", "connect_welcome"],
  ["device_ping", "device_ping"],
  ["bridge_ping", "bridge_ping"],
  ["device_presence", "device_presence"],
  ["device_key_rotation", "device_key_rotation"],
  ["device_key_rotation_ack", "device_key_rotation_ack"],
  ["bridge_key_rotation", "bridge_key_rotation"],
  ["bridge_key_rotation_ack", "bridge_key_rotation_ack"],
  ["adapter_key_rotation", "adapter_key_rotation"],
  ["adapter_key_rotation_ack", "adapter_key_rotation_ack"],
  ["device_event", "device_event"],
  ["event_ack", "event_ack"],
] as const;

const remove = (value: Record<string, unknown>, field: string): Record<string, unknown> => {
  const copy = { ...value };
  delete copy[field];
  return copy;
};

const wrongNonNull = (value: unknown): unknown => {
  if (Array.isArray(value)) return {};
  if (typeof value === "object" && value !== null) return [];
  if (typeof value === "string") return false;
  return true;
};

const collectIds = (value: unknown, ids: string[] = []): string[] => {
  if (Array.isArray(value)) for (const member of value) collectIds(member, ids);
  else if (typeof value === "object" && value !== null) {
    for (const [key, member] of Object.entries(value)) {
      if (key === "$id" && typeof member === "string") ids.push(member);
      else collectIds(member, ids);
    }
  }
  return ids;
};

describe("locked message registry", () => {
  it("freezes the exact cumulative Task 9 direction/domain/signer-role matrix", () => {
    const registry = loadMessageRegistry();
    validateSchema("urn:open-android-intelligence:protocol:v1:messages-registry", registry);
    const actual = registry.messages.map((entry) => [
      entry.message_type, entry.direction, entry.signature_domain, entry.schema_id,
      entry.direction === "app-to-bridge" ? "device"
        : entry.direction === "adapter-to-bridge" ? "adapter" : "bridge-command",
    ]);
    expect(actual.slice(0, expectedByTask.task4.length)).toEqual(expectedByTask.task4);
    expect(actual.slice(expectedByTask.task4.length)).toEqual([
      ...expectedByTask.task7,
      ["device_event", "app-to-bridge", "control/app-to-bridge", "urn:open-android-intelligence:protocol:v1:message:device_event", "device"],
      ["event_ack", "bridge-to-app", "control/bridge-to-app", "urn:open-android-intelligence:protocol:v1:message:event_ack", "bridge-command"],
    ]);
  });

  it("has global uniqueness, profile domains, and one registry entry per production payload ID", () => {
    const registry = loadMessageRegistry();
    expect(new Set(registry.messages.map((entry) => entry.message_type)).size).toBe(registry.messages.length);
    const domains = new Set(loadProtocolProfile().signature_domains);
    expect(registry.messages.every((entry) => domains.has(entry.signature_domain))).toBe(true);
    const productionPayloadIds = PROTOCOL_SCHEMA_DOCUMENTS.flatMap((document) => collectIds(document))
      .filter((id) => id.startsWith("urn:open-android-intelligence:protocol:v1:message:"))
      .sort();
    expect([...new Set(productionPayloadIds)]).toEqual(registry.messages.map((entry) => entry.schema_id).sort());
    expect(productionPayloadIds).toHaveLength(new Set(productionPayloadIds).size);
    expect(registry.messages.every((entry) => !/(container|reducer|store|channel[-_]frame)/.test(`${entry.message_type}:${entry.schema_id}`))).toBe(true);
  });

  it("is recursively frozen and cannot be extended or replaced", () => {
    const first = loadMessageRegistry();
    expect(Object.isFrozen(first)).toBe(true);
    expect(Object.isFrozen(first.messages)).toBe(true);
    expect(first.messages.every(Object.isFrozen)).toBe(true);
    expect(() => (first.messages as unknown as unknown[]).push({ message_type: "root_shell" })).toThrow();
    const attackerRegistry = { ...first, messages: [...first.messages, { message_type: "root_shell" }] };
    expect(loadMessageRegistry()).toBe(first);
    expect(loadMessageRegistry()).not.toBe(attackerRegistry);
  });
});

describe("closed cumulative Task 5 payload matrix", () => {
  for (const type of Object.keys(payloadFixtures) as MessageType[]) {
    const schemaId = `urn:open-android-intelligence:protocol:v1:message:${type}`;
    it(`${type} accepts only its complete closed fixture`, () => {
      expect(() => validateSchema(schemaId, payloadFixtures[type])).not.toThrow();
      for (const field of payloadRequired[type]) expect(() => validateSchema(schemaId, remove(payloadFixtures[type] as unknown as Record<string, unknown>, field))).toThrowError("SCHEMA_INVALID");
      for (const field of payloadRequired[type]) {
        const current = (payloadFixtures[type] as unknown as Record<string, unknown>)[field];
        if (current !== null) expect(() => validateSchema(schemaId, { ...payloadFixtures[type], [field]: null })).toThrowError("SCHEMA_INVALID");
        expect(() => validateSchema(schemaId, { ...payloadFixtures[type], [field]: wrongNonNull(current) })).toThrowError("SCHEMA_INVALID");
      }
      expect(() => validateSchema(schemaId, { ...payloadFixtures[type], unknown_field: true })).toThrowError("SCHEMA_INVALID");
      if (task5Types.has(type) || task9Types.has(type)) {
        for (const forbidden of [
          "tenant_id", "human_principal_id", "agent_principal_id", "agent_instance_id", "workspace_id",
          "scope_ceiling", "transport", "transport_profile_id", "connection_id", "connection_generation",
          "operation_id", "capability", "authorization_epoch", "revision",
        ]) {
          expect(() => validateSchema(schemaId, { ...payloadFixtures[type], [forbidden]: "forbidden" }))
            .toThrowError("SCHEMA_INVALID");
        }
      }
    });
  }
});

describe("closed cumulative Task 5 header and envelope matrix", () => {
  for (const [family, type] of familyCases) {
    const headerSchema = `urn:open-android-intelligence:protocol:v1:header:${family}`;
    const envelopeSchema = `urn:open-android-intelligence:protocol:v1:envelope:${family}`;
    it(`${type} requires every ${family} header/envelope leaf and rejects unknown fields`, () => {
      const header = headerFor(type);
      const envelope = envelopeFor(type);
      expect(() => validateSchema(headerSchema, header)).not.toThrow();
      expect(() => validateSchema(envelopeSchema, envelope)).not.toThrow();
      for (const field of Object.keys(header)) expect(() => validateSchema(headerSchema, remove(header, field))).toThrowError("SCHEMA_INVALID");
      for (const field of Object.keys(header)) {
        const current = (header as Record<string, unknown>)[field];
        expect(() => validateSchema(headerSchema, { ...header, [field]: null })).toThrowError("SCHEMA_INVALID");
        expect(() => validateSchema(headerSchema, { ...header, [field]: wrongNonNull(current) })).toThrowError("SCHEMA_INVALID");
      }
      for (const field of ["header", "payload", "signature"]) expect(() => validateSchema(envelopeSchema, remove(envelope as unknown as Record<string, unknown>, field))).toThrowError("SCHEMA_INVALID");
      for (const forbidden of ["device_id", "pairing_generation", "adapter_credential_id", "connection_generation", "agent_principal_id", "session_id"]) {
        if (!(forbidden in header)) expect(() => validateSchema(headerSchema, { ...header, [forbidden]: "forbidden" })).toThrowError("SCHEMA_INVALID");
      }
      expect(() => validateSchema(envelopeSchema, { ...envelope, unknown_field: true })).toThrowError("SCHEMA_INVALID");
      for (const field of ["header", "payload", "signature"] as const) {
        const current = envelope[field];
        expect(() => validateSchema(envelopeSchema, { ...envelope, [field]: null })).toThrowError("SCHEMA_INVALID");
        expect(() => validateSchema(envelopeSchema, { ...envelope, [field]: wrongNonNull(current) })).toThrowError("SCHEMA_INVALID");
      }
      if (task5Types.has(type) || task9Types.has(type)) {
        const wrongType = type === "device_presence" ? "device_ping" : "device_presence";
        expect(() => validateSchema(envelopeSchema, { ...envelope, payload: payloadFixtures[wrongType] }))
          .toThrowError("SCHEMA_INVALID");
      }
    });

    it(`${type} rejects every registered ${family} type/schema/direction swap`, () => {
      const envelope = envelopeFor(type);
      for (const candidate of loadMessageRegistry().messages) {
        if (candidate.message_type !== type) expect(() => validateSchema(envelopeSchema, { ...envelope, header: { ...envelope.header, message_type: candidate.message_type } })).toThrowError("SCHEMA_INVALID");
        if (candidate.schema_id !== envelope.header.message_schema) expect(() => validateSchema(envelopeSchema, { ...envelope, header: { ...envelope.header, message_schema: candidate.schema_id } })).toThrowError("SCHEMA_INVALID");
        if (candidate.direction !== envelope.header.direction) expect(() => validateSchema(envelopeSchema, { ...envelope, header: { ...envelope.header, direction: candidate.direction } })).toThrowError("SCHEMA_INVALID");
      }
    });
  }
});

describe("exact Task 5 envelope reference ownership", () => {
  const task5 = [
    "device_ping", "bridge_ping", "device_presence", "device_key_rotation", "device_key_rotation_ack",
    "bridge_key_rotation", "bridge_key_rotation_ack", "adapter_key_rotation", "adapter_key_rotation_ack",
  ] as const;

  it("binds every named envelope directly to its same-name header and payload leaf", () => {
    const definitions = PROTOCOL_SCHEMA_DOCUMENTS.flatMap((document) =>
      Object.values((document as { $defs?: Record<string, unknown> }).$defs ?? {}));
    for (const type of task5) {
      const id = `urn:open-android-intelligence:protocol:v1:envelope:${type}`;
      const definition = definitions.find((candidate) =>
        typeof candidate === "object" && candidate !== null && (candidate as { $id?: string }).$id === id) as {
          properties?: { header?: { $ref?: string }; payload?: { $ref?: string } };
        } | undefined;
      expect(definition?.properties?.header?.$ref).toBe(`urn:open-android-intelligence:protocol:v1:header:${type}`);
      expect(definition?.properties?.payload?.$ref).toBe(`urn:open-android-intelligence:protocol:v1:message:${type}`);
    }
  });
});

describe("raw admission rejects registry tuple swaps before signature work", () => {
  const clock = {
    wallNow: () => new Date("2026-08-08T00:01:00.000Z"),
    monotonicNowMs: () => 1_000n,
  };
  const transcript = {
    ticket_digest: B32,
    bridge_fingerprint: B32,
    challenge: B32,
    client_nonce: B32,
    bridge_nonce: B32,
    device_jwk_thumbprint: B32,
    selected_protocol: "1.0",
  } as const;

  for (const type of ["enrollment_challenge", "enrollment_complete", "enrollment_error", "connect_hello", "connect_welcome"] as const) {
    it(`${type} rejects all registered tuple swaps without invoking a port verifier`, async () => {
      let verifierCalls = 0;
      const verifier = { verify: async () => { verifierCalls += 1; return false; } };
      const base = envelopeFor(type);
      const admission = async (wire: Uint8Array): Promise<void> => {
        if (type === "enrollment_challenge") {
          await verifyEnrollmentBridgeMessage(wire, {
            phase: "challenge", expectedTicketDigest: B32, expectedChallenge: B32,
            qrPinnedBridgeFingerprint: B32, clock,
          });
          return;
        }
        if (type === "enrollment_complete" || type === "enrollment_error") {
          await verifyEnrollmentBridgeMessage(wire, {
            phase: "pinned", expectedTicketDigest: B32, pendingTranscript: transcript,
            verifier, expectedKeyId: JWK.kid, clock,
          });
          return;
        }
        const connectContext = {
          verifier,
          expectedSignerRole: type === "connect_hello" ? "device" : "bridge-command",
          expectedKeyId: JWK.kid,
          expectedDeviceId: "device",
          expectedPairingGeneration: "1",
          clock,
        } as const;
        if (type === "connect_hello") await verifyConnectMessage(wire, "connect_hello", connectContext);
        else await verifyConnectMessage(wire, "connect_welcome", connectContext);
      };

      for (const candidate of loadMessageRegistry().messages) {
        const mutations: Record<string, unknown>[] = [];
        if (candidate.message_type !== type) mutations.push({ message_type: candidate.message_type });
        if (candidate.schema_id !== base.header.message_schema) mutations.push({ message_schema: candidate.schema_id });
        if (candidate.direction !== base.header.direction) mutations.push({ direction: candidate.direction });
        for (const mutation of mutations) {
          const wire = canonicalBytes({ ...base, header: { ...base.header, ...mutation } });
          await expect(admission(wire)).rejects.toThrowError(/^SCHEMA_INVALID$/);
        }
      }
      expect(verifierCalls).toBe(0);
    });
  }
});

const compileTimeAdmissionRegistryEvidence = (
  connectContext: ConnectMessageAdmissionContext,
  enrollmentContext: EnrollmentBridgeAdmissionContext,
): void => {
  // @ts-expect-error Connect admission does not accept a caller registry.
  void verifyConnectMessage(new Uint8Array(), "connect_hello", connectContext, loadMessageRegistry());
  // @ts-expect-error Enrollment admission does not accept a caller registry.
  void verifyEnrollmentBridgeMessage(new Uint8Array(), enrollmentContext, loadMessageRegistry());
};
void compileTimeAdmissionRegistryEvidence;
