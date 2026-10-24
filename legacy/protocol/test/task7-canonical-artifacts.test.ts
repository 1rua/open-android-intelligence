/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { verifyEs256 } from "../src/crypto.js";
import { canonicalBytes, parseCanonicalJson, sha256B64Url, signingPreimage } from "../src/encoding.js";
import { loadMessageRegistry, type MessageRegistryEntry } from "../src/message-registry.js";
import { parseSignatureDomain } from "../src/profile.js";
import {
  DeterministicReplayLedger,
  REPLAY_POLICY_LITERALS,
  acceptSequence,
  buildDeterministicAdapterReplayMetadata,
  buildDeterministicDeviceReplayMetadata,
  canonicalReplayIntentMetadataBytes,
  type LockedReplayRegistryIdentity,
  type PersistedReplayIntentMetadata,
  type ReplaySpace,
} from "../src/replay-window.js";
import {
  DeterministicAdapterSecurityBackend,
  verifyAdapterAdmission,
  verifyAuthenticatedBinding,
  type AuthenticatedBindingContext,
  type VerifiedSignedEnvelope,
} from "../src/control-envelope.js";
import { DeterministicConnectionFenceStore } from "../src/connection-fence.js";
import { validateSchema } from "../src/schema-validator.js";

type IntentVector = {
  vector_id: string;
  semantic_input: PersistedReplayIntentMetadata<string>;
  metadata_jcs_b64: string;
  metadata_jcs_byte_length: string;
};

type ReceiptReplayVector = {
  id: string;
  admission_time: string;
  expected_decision: "ACCEPT_HISTORICAL_INNER" | "REJECT_INVALID_ORIGINAL_LIFETIME";
  inner_envelope: Record<string, unknown>;
  inner_wire_b64: string;
  inner_wire_byte_length: string;
  inner_digest: string;
  outer_envelope: Record<string, unknown>;
  outer_unsigned_preimage_b64: string;
  outer_wire_b64: string;
  outer_wire_byte_length: string;
};

type ReplayVectorHeader = Record<string, string> & {
  message_type: string;
  message_schema: string;
  direction: string;
  payload_digest: string;
  issued_at: string;
  expires_at: string;
  sequence: string;
};

const B32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const LEASE_ID = B32;
const MESSAGE_ID = "018f4f9a-4444-4444-8444-444444444444";
const readJson = <T>(path: string): T => JSON.parse(
  readFileSync(new URL(path, import.meta.url), "utf8"),
) as T;
const readJwk = (name: string): JsonWebKey => readJson(`../test-only/keys/${name}`);
const registry = loadMessageRegistry();
const replayPolicies = readJson<{
  message_classification: Array<{ message_type: string; class_id: string; retention_rule_id: string }>;
}>("../registries/v1/replay-policies.json");

const entryFor = (messageType: string): MessageRegistryEntry => {
  const matches = registry.messages.filter((entry) => entry.message_type === messageType);
  expect(matches).toHaveLength(1);
  return matches[0] as MessageRegistryEntry;
};

const identityFor = <T extends string>(messageType: T): LockedReplayRegistryIdentity<T> => {
  const entry = entryFor(messageType);
  return {
    messageType,
    messageSchemaId: entry.schema_id,
    headerSchemaId: `urn:open-android-intelligence:protocol:v1:header:${messageType}`,
    envelopeSchemaId: `urn:open-android-intelligence:protocol:v1:envelope:${messageType}`,
    direction: entry.direction,
    signatureDomain: parseSignatureDomain(entry.signature_domain),
    signerRole: entry.direction === "app-to-bridge" ? "device"
      : entry.direction === "adapter-to-bridge" ? "adapter" : "bridge-command",
  };
};

const envelopeFor = <T extends string>(messageType: T, direction: ReplaySpace["direction"], payload: Record<string, unknown>): VerifiedSignedEnvelope<T> => ({
  rawWire: {} as never,
  messageType,
  header: {
    message_id: MESSAGE_ID,
    sequence: "1",
    expires_at: "2026-08-08T00:01:00.000Z",
    direction,
    device_id: "device-1",
    pairing_generation: "2",
    connection_generation: "7",
    adapter_credential_id: "adapter-credential",
    adapter_credential_generation: "4",
  },
  payload,
  registryEntry: entryFor(messageType),
  signerRole: direction === "app-to-bridge" ? "device" : direction === "adapter-to-bridge" ? "adapter" : "bridge-command",
  envelopeDigest: B32,
} as never);

const mintedDeviceMetadata = async (): Promise<PersistedReplayIntentMetadata<"device_ping">> => {
  const fence = new DeterministicConnectionFenceStore(
    { credentialId: "credential-1", pairingGeneration: 2n },
    { generation: 6n, fenceRevision: 6n, connectionId: null, transportProfileId: null },
    { leaseIdSource: () => LEASE_ID },
  );
  const allocated = await fence.allocateNext(
    { credentialId: "credential-1", pairingGeneration: 2n }, "connection-1", "tailnet",
  );
  if (allocated.kind !== "allocated") throw new Error("expected device allocation");
  const envelope = envelopeFor("device_ping", "app-to-bridge", { challenge: B32 });
  const binding = verifyAuthenticatedBinding(envelope as never, {
    kind: "device", transport: "https", transportProfileId: "tailnet", connectionId: "connection-1",
    allocatedConnectionGeneration: allocated.allocation.generation, connectionLease: allocated.allocation.lease,
    credential: { credentialId: "credential-1", tenantId: "tenant-1", humanPrincipalId: "human-1", deviceId: "device-1", pairingGeneration: 2n, active: true },
  } as never, await fence.inspect(allocated.allocation.lease));
  if (!binding.ok || binding.context.kind !== "device") throw new Error("expected device binding");
  const ledger = new DeterministicReplayLedger<"device_ping">();
  const claim = ledger.previewClaim({
    kind: "device", credentialId: "credential-1", pairingGeneration: 2n, keyId: "key-1", direction: "app-to-bridge",
  }, envelope, "2026-08-08T00:00:01.000Z");
  if (!claim) throw new Error("expected device claim");
  const persistenceId = fence.persistenceId(allocated.allocation.lease);
  if (!persistenceId) throw new Error("expected device lease persistence ID");
  return buildDeterministicDeviceReplayMetadata({
    claim, registryIdentity: identityFor("device_ping"), bindingSnapshot: binding.context,
    connectionLease: allocated.allocation.lease, connectionLeasePersistenceId: persistenceId,
    admittedAt: "2026-08-08T00:00:01.000Z",
  });
};

const mintedAdapterMetadata = async (scopeCeiling: readonly string[]): Promise<PersistedReplayIntentMetadata<"operation_submit">> => {
  const backend = new DeterministicAdapterSecurityBackend({
    credential: {
      credentialId: "adapter-credential", generation: 4n, tenantId: "tenant", agentPrincipalId: "agent-principal",
      agentInstanceId: "agent-instance", workspaceId: "workspace", scopeCeiling, active: true,
    },
    principal: { humanPrincipalId: "human", agentPrincipalId: "agent-principal" },
    keyRings: [], leaseIdSource: () => LEASE_ID,
  });
  const authenticated = await backend.authenticateAdapter({ handleId: "adapter-session", connectionId: "adapter-connection" });
  const trusted = await backend.loadCommittedAdapterBinding(authenticated.ingress);
  const envelope = envelopeFor("operation_submit", "adapter-to-bridge", {
    operation_expires_at: "2026-08-08T00:15:00.000Z",
  });
  const binding = verifyAdapterAdmission(envelope as never, trusted);
  if (!binding.ok || binding.context.kind !== "adapter") throw new Error("expected adapter binding");
  const ledger = new DeterministicReplayLedger<"operation_submit">();
  const claim = ledger.previewClaim({
    kind: "adapter", credentialId: "adapter-credential", adapterCredentialGeneration: 4n,
    keyId: "key-1", direction: "adapter-to-bridge",
  }, envelope, "2026-08-08T00:00:01.000Z");
  if (!claim) throw new Error("expected adapter claim");
  const persistenceId = backend.persistenceId(authenticated.credentialLease);
  if (!persistenceId) throw new Error("expected adapter lease persistence ID");
  return buildDeterministicAdapterReplayMetadata({
    claim, registryIdentity: identityFor("operation_submit"), bindingSnapshot: binding.context,
    adapterCredentialLease: authenticated.credentialLease, adapterCredentialLeasePersistenceId: persistenceId,
    admittedAt: "2026-08-08T00:00:01.000Z",
  });
};

const intentCases = [
  ["../test-only/replay/v1/intent-metadata-device-v1.json", () => mintedDeviceMetadata()],
  ["../test-only/replay/v1/intent-metadata-adapter-v1.json", () => mintedAdapterMetadata(["artifact.read", "tools.write"])],
  ["../test-only/replay/v1/intent-metadata-adapter-empty-scope-v1.json", () => mintedAdapterMetadata([])],
] as const;

describe("Task 7 canonical replay-intent artifacts", () => {
  it.each(intentCases)("reconstructs %s from store-minted branch authorities", async (path, mint) => {
    const vector = readJson<IntentVector>(path);
    expect(Object.keys(vector).sort()).toEqual([
      "metadata_jcs_b64", "metadata_jcs_byte_length", "semantic_input", "vector_id",
    ]);
    expect(await mint()).toEqual(vector.semantic_input);
    const bytes = canonicalReplayIntentMetadataBytes(vector.semantic_input);
    const expectedBytes = Buffer.from(vector.metadata_jcs_b64, "base64");
    expect(Buffer.from(bytes)).toEqual(expectedBytes);
    expect(expectedBytes.toString("base64")).toBe(vector.metadata_jcs_b64);
    expect(String(bytes.byteLength)).toBe(vector.metadata_jcs_byte_length);
    expect(vector.metadata_jcs_byte_length).toMatch(/^(0|[1-9][0-9]*)$/);
  });

  it("keeps branch nulls, adapter scope ordering and replay classification exact", () => {
    const device = readJson<IntentVector>(intentCases[0][0]).semantic_input;
    const adapter = readJson<IntentVector>(intentCases[1][0]).semantic_input;
    const empty = readJson<IntentVector>(intentCases[2][0]).semantic_input;
    expect(device.binding_snapshot).toMatchObject({
      kind: "device", adapter_credential_generation: null, agent_instance_id: null,
      agent_principal_id: null, scope_ceiling: null, workspace_id: null,
    });
    expect(device.lease_ref).toMatchObject({ adapter_credential_lease_id: null, kind: "device_connection" });
    expect(device.space).toMatchObject({ adapter_credential_generation: null, kind: "device" });
    expect(device.replay_policy).toEqual(REPLAY_POLICY_LITERALS.task5Default);
    expect(adapter.binding_snapshot).toMatchObject({
      kind: "adapter", connection_generation: null, device_id: null,
      pairing_generation: null, scope_ceiling: ["artifact.read", "tools.write"],
    });
    expect(adapter.lease_ref).toMatchObject({ connection_lease_id: null, kind: "adapter_credential" });
    expect(adapter.space).toMatchObject({ kind: "adapter", pairing_generation: null });
    expect(adapter.replay_policy).toEqual(REPLAY_POLICY_LITERALS.operationSecurityLedger);
    expect(empty.binding_snapshot.scope_ceiling).toEqual([]);
    for (const metadata of [device, adapter, empty]) {
      const classification = replayPolicies.message_classification.find((row) => row.message_type === metadata.registry_identity.message_type);
      expect(classification).toMatchObject(metadata.replay_policy);
      expect(entryFor(metadata.registry_identity.message_type)).toMatchObject({
        direction: metadata.registry_identity.direction,
        schema_id: metadata.registry_identity.message_schema_id,
        signature_domain: metadata.registry_identity.signature_domain,
      });
    }
  });

  it("rejects omission, unknown members and registry-policy mismatches", () => {
    const vector = readJson<IntentVector>(intentCases[0][0]);
    const missingNull = structuredClone(vector.semantic_input) as unknown as Record<string, unknown>;
    delete (missingNull.binding_snapshot as Record<string, unknown>).workspace_id;
    expect(() => canonicalReplayIntentMetadataBytes(missingNull as never)).toThrowError("INVALID_REPLAY_INTENT_METADATA");
    expect(() => canonicalReplayIntentMetadataBytes({ ...vector.semantic_input, raw_wire: "forbidden" } as never))
      .toThrowError("INVALID_REPLAY_INTENT_METADATA");
    expect(() => canonicalReplayIntentMetadataBytes({
      ...vector.semantic_input,
      replay_policy: REPLAY_POLICY_LITERALS.operationSecurityLedger,
    } as never)).toThrowError("INVALID_REPLAY_INTENT_METADATA");
  });
});

describe("Task 7 canonical receipt-replay artifacts", () => {
  it("verifies exact inner/outer bytes, signatures, expiry policy and replay behavior", () => {
    const fixture = readJson<{ vector_set: string; vectors: ReceiptReplayVector[] }>("../test-only/operation/v1/receipt-replay-vectors.json");
    expect(Object.keys(fixture).sort()).toEqual(["vector_set", "vectors"]);
    expect(fixture.vector_set).toBe("task7-receipt-replay-v1");
    expect(fixture.vectors.map((vector) => vector.id)).toEqual([
      "historical-inner-expired-now-accepted", "invalid-original-lifetime-rejected",
    ]);
    const devicePublic = readJwk("device-a-public.jwk.json");
    for (const vector of fixture.vectors) {
      expect(Object.keys(vector).sort()).toEqual([
        "admission_time", "expected_decision", "id", "inner_digest", "inner_envelope",
        "inner_wire_b64", "inner_wire_byte_length", "outer_envelope", "outer_unsigned_preimage_b64",
        "outer_wire_b64", "outer_wire_byte_length",
      ]);
      const innerBytes = Buffer.from(vector.inner_wire_b64, "base64");
      const outerBytes = Buffer.from(vector.outer_wire_b64, "base64");
      expect(innerBytes.toString("base64")).toBe(vector.inner_wire_b64);
      expect(outerBytes.toString("base64")).toBe(vector.outer_wire_b64);
      expect(parseCanonicalJson(innerBytes)).toEqual(vector.inner_envelope);
      expect(parseCanonicalJson(outerBytes)).toEqual(vector.outer_envelope);
      expect(Buffer.from(canonicalBytes(vector.inner_envelope))).toEqual(innerBytes);
      expect(Buffer.from(canonicalBytes(vector.outer_envelope))).toEqual(outerBytes);
      expect(String(innerBytes.byteLength)).toBe(vector.inner_wire_byte_length);
      expect(String(outerBytes.byteLength)).toBe(vector.outer_wire_byte_length);
      expect(sha256B64Url(innerBytes)).toBe(vector.inner_digest);

      const inner = vector.inner_envelope as { header: ReplayVectorHeader; payload: Record<string, unknown>; signature: string };
      const outer = vector.outer_envelope as {
        header: ReplayVectorHeader;
        payload: { original_receipt_wire_b64: string; original_receipt_digest: string };
        signature: string;
      };
      validateSchema("urn:open-android-intelligence:protocol:v1:envelope:operation_receipt", inner);
      validateSchema("urn:open-android-intelligence:protocol:v1:envelope:receipt_replay", outer);
      expect(entryFor(inner.header.message_type)).toMatchObject({
        direction: inner.header.direction, schema_id: inner.header.message_schema, signature_domain: "receipt/device",
      });
      expect(entryFor(outer.header.message_type)).toMatchObject({
        direction: outer.header.direction, schema_id: outer.header.message_schema, signature_domain: "control/app-to-bridge",
      });
      expect(sha256B64Url(canonicalBytes(inner.payload))).toBe(inner.header.payload_digest);
      expect(sha256B64Url(canonicalBytes(outer.payload))).toBe(outer.header.payload_digest);
      expect(verifyEs256(devicePublic, signingPreimage(parseSignatureDomain("receipt/device"), {
        header: inner.header, payload: inner.payload,
      }), inner.signature)).toBe(true);
      const outerPreimage = signingPreimage(parseSignatureDomain("control/app-to-bridge"), {
        header: outer.header, payload: outer.payload,
      });
      expect(Buffer.from(outerPreimage).toString("base64")).toBe(vector.outer_unsigned_preimage_b64);
      expect(verifyEs256(devicePublic, outerPreimage, outer.signature)).toBe(true);
      expect(outer.payload).toEqual({ original_receipt_wire_b64: vector.inner_wire_b64, original_receipt_digest: vector.inner_digest });

      const innerInterval = Date.parse(inner.header.expires_at) - Date.parse(inner.header.issued_at);
      const innerLifetimeValid = innerInterval > 0 && innerInterval <= 300_000;
      const outerNow = Date.parse(vector.admission_time);
      const outerLifetimeValid = Date.parse(outer.header.expires_at) > outerNow
        && Date.parse(outer.header.expires_at) - Date.parse(outer.header.issued_at) <= 60_000;
      expect(Date.parse(inner.header.expires_at)).toBeLessThan(outerNow);
      expect(outerLifetimeValid).toBe(true);
      expect(innerLifetimeValid).toBe(vector.expected_decision === "ACCEPT_HISTORICAL_INNER");

      const first = acceptSequence({ highestSeen: null, seenBitmap: 0n }, BigInt(outer.header.sequence));
      expect(first.kind).toBe("accept");
      if (first.kind !== "accept") throw new Error("expected outer replay admission");
      expect(acceptSequence(first.next, BigInt(outer.header.sequence))).toEqual({ kind: "reject", error: "REPLAY_REJECTED" });
    }
  });
});
