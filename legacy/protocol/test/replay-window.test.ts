import { describe, expect, it } from "vitest";
import {
  DeterministicReplayLedger,
  TASK5_REPLAY_LIMITS,
  REPLAY_POLICY_LITERALS,
  acceptSequence,
  canonicalReplayIntentMetadataBytes,
  type ReplaySpace,
  type PersistedReplayIntentMetadata,
} from "../src/replay-window.js";
import { retainExactWireBytes, type VerifiedSignedEnvelope } from "../src/control-envelope.js";
import { parseSignatureDomain } from "../src/profile.js";

describe("1024-slot replay value model", () => {
  it("accepts empty, reordering and the exact 1023 boundary while rejecting duplicates and offset 1024", () => {
    const first = acceptSequence({ highestSeen: null, seenBitmap: 0n }, 1024n);
    expect(first).toEqual({ kind: "accept", next: { highestSeen: 1024n, seenBitmap: 1n } });
    if (first.kind !== "accept") throw new Error("expected acceptance");

    const reordered = acceptSequence(first.next, 1n);
    expect(reordered).toEqual({ kind: "accept", next: { highestSeen: 1024n, seenBitmap: (1n << 1023n) | 1n } });
    expect(acceptSequence(first.next, 1024n)).toEqual({ kind: "reject", error: "REPLAY_REJECTED" });
    expect(acceptSequence(first.next, 0n)).toEqual({ kind: "reject", error: "REPLAY_REJECTED" });
  });

  it("handles huge bigint jumps without Number conversion overflow and keeps only 1024 bits", () => {
    const huge = 18_446_744_073_709_551_615n;
    const decision = acceptSequence({ highestSeen: 9n, seenBitmap: (1n << 1023n) | 5n }, huge);
    expect(decision).toEqual({ kind: "accept", next: { highestSeen: huge, seenBitmap: 1n } });
    expect(acceptSequence({ highestSeen: huge, seenBitmap: 1n }, huge + 1n))
      .toEqual({ kind: "reject", error: "REPLAY_REJECTED" });
  });
});

describe("restartable replay row lifecycle", () => {
  const space: ReplaySpace = {
    kind: "device", credentialId: "credential", pairingGeneration: 3n,
    keyId: "key", direction: "app-to-bridge",
  };
  const envelope = (messageId: string): VerifiedSignedEnvelope<"device_ping"> => ({
    rawWire: retainExactWireBytes(Uint8Array.from([1, 2, 3])),
    messageType: "device_ping",
    header: {
      message_id: messageId,
      sequence: "1",
      expires_at: "2026-08-08T00:01:00.000Z",
    },
    payload: {},
    registryEntry: {} as never,
    signerRole: "device",
    envelopeDigest: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  } as never);

  it("does not allow a finalized or abandoned claim to be finalized again", () => {
    let claimIndex = 0;
    const ledger = new DeterministicReplayLedger<"device_ping">({
      claimIdSource: () => {
        const bytes = Buffer.alloc(32);
        bytes.writeUInt32BE(claimIndex++, 0);
        return bytes.toString("base64url");
      },
    });
    const accepted = ledger.admit(space, envelope("018f4f9a-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), "2026-08-08T00:00:00.000Z", 16_384);
    expect(accepted.kind).toBe("accepted");
    if (accepted.kind !== "accepted") throw new Error("expected accepted claim");
    expect(ledger.finalize(accepted.claim, retainExactWireBytes(Uint8Array.from([9])))).toBe("stored");
    expect(ledger.finalize(accepted.claim, retainExactWireBytes(Uint8Array.from([9])))).toBe("same");
    expect(ledger.abandon(accepted.claim)).toBe("rejected");

    const second = ledger.admit(space, {
      ...envelope("018f4f9a-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
      header: { ...envelope("018f4f9a-bbbb-4bbb-8bbb-bbbbbbbbbbbb").header, sequence: "2" },
    } as never, "2026-08-08T00:00:00.000Z", 16_384);
    expect(second.kind).toBe("accepted");
    if (second.kind !== "accepted") throw new Error("expected second claim");
    expect(ledger.abandon(second.claim)).toBe("abandoned");
    expect(ledger.finalize(second.claim, retainExactWireBytes(Uint8Array.from([7])))).toBe("rejected");
  });

  it("fails closed on a duplicate claim ID before mutating the replay window or capacity", () => {
    const duplicateClaimId = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    const ledger = new DeterministicReplayLedger<"device_ping">({ claimIdSource: () => duplicateClaimId });
    const first = ledger.admit(space, envelope("018f4f9a-f111-4111-8111-111111111111"), "2026-08-08T00:00:00.000Z", 16_384);
    expect(first.kind).toBe("accepted");
    if (first.kind !== "accepted") throw new Error("expected accepted claim");
    const before = ledger.snapshot();
    const beforeCapacity = ledger.capacity(space);

    const duplicate = ledger.admit(space, {
      ...envelope("018f4f9a-f222-4222-8222-222222222222"),
      header: { ...envelope("018f4f9a-f222-4222-8222-222222222222").header, sequence: "2" },
    } as never, "2026-08-08T00:00:00.000Z", 16_384);
    expect(duplicate).toEqual({ kind: "rejected", error: "INTEGRITY_FAILED", denial: "CLAIM_ID_CONFLICT" });
    expect(ledger.capacity(space)).toEqual(beforeCapacity);
    expect(ledger.snapshot()).toEqual(before);
  });

  it("defensively retains finalized receipt bytes instead of the caller's mutable source", () => {
    const ledger = new DeterministicReplayLedger<"device_ping">({
      claimIdSource: () => "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    });
    const accepted = ledger.admit(space, envelope("018f4f9a-eeee-4eee-8eee-eeeeeeeeeeee"), "2026-08-08T00:00:00.000Z", 16_384);
    expect(accepted.kind).toBe("accepted");
    if (accepted.kind !== "accepted") throw new Error("expected accepted claim");

    const source = Uint8Array.from([9]);
    const mutableReceipt = {
      byteLength: source.byteLength,
      copy: () => Uint8Array.from(source),
    } as never;
    expect(ledger.finalize(accepted.claim, mutableReceipt)).toBe("stored");
    source[0] = 4;

    expect(ledger.row(accepted.claim)?.receipt).toEqual(Uint8Array.from([9]));
  });

  it("rejects restart snapshots whose row lifecycle or capacity accounting was tampered with", () => {
    const ledger = new DeterministicReplayLedger<"device_ping">({
      claimIdSource: () => "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    });
    const accepted = ledger.admit(space, envelope("018f4f9a-cccc-4ccc-8ccc-cccccccccccc"), "2026-08-08T00:00:00.000Z", 16_384);
    if (accepted.kind !== "accepted") throw new Error("expected accepted claim");
    const snapshot = ledger.snapshot();
    expect(() => DeterministicReplayLedger.restart({
      ...snapshot,
      capacity: [{ spaceKey: snapshot.capacity[0]?.spaceKey ?? "", rows: 0, retainedBytes: 0 }],
    })).toThrowError("INTEGRITY_FAILED");
    expect(() => DeterministicReplayLedger.restart({
      ...snapshot,
      rows: [{ ...snapshot.rows[0]!, status: "finalized", receipt: null }],
    })).toThrowError("INTEGRITY_FAILED");
    expect(() => DeterministicReplayLedger.restart({
      ...snapshot,
      rows: [{ ...snapshot.rows[0]!, retainedBytes: -1 }],
    })).toThrowError("INTEGRITY_FAILED");
  });

  it("rejects restart snapshots whose replay window disagrees with the durable rows", () => {
    const ledger = new DeterministicReplayLedger<"device_ping">({
      claimIdSource: () => "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    });
    const accepted = ledger.admit(space, envelope("018f4f9a-dddd-4ddd-8ddd-dddddddddddd"), "2026-08-08T00:00:00.000Z", 16_384);
    if (accepted.kind !== "accepted") throw new Error("expected accepted claim");
    const snapshot = ledger.snapshot();
    expect(snapshot.windows[0]).toBeDefined();
    expect(() => DeterministicReplayLedger.restart({
      ...snapshot,
      windows: [{ ...snapshot.windows[0]!, state: { highestSeen: 99n, seenBitmap: 1n } }],
    })).toThrowError("INTEGRITY_FAILED");
  });

  it("rejects duplicate claim IDs in restorePending and restart snapshots", () => {
    const claimId = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    const ledger = new DeterministicReplayLedger<"device_ping">();
    const restored = {
      claimId,
      space,
      messageType: "device_ping" as const,
      messageId: "018f4f9a-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
      sequence: 1n,
      envelopeDigest: claimId,
      expiresAt: "2026-08-08T00:01:00.000Z",
      retentionUntil: "2026-08-09T00:00:00.000Z",
    } as const;
    ledger.restorePending(restored, 1);
    expect(() => ledger.restorePending({
      ...restored,
      messageId: "018f4f9a-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      sequence: 2n,
    }, 1)).toThrowError("INTEGRITY_FAILED");

    const snapshot = ledger.snapshot();
    const row = snapshot.rows[0];
    if (!row) throw new Error("expected restored row");
    expect(() => DeterministicReplayLedger.restart({
      ...snapshot,
      rows: [row, { ...row, claim: { ...row.claim, messageId: "018f4f9a-cccc-4ccc-8ccc-cccccccccccc" } }],
    })).toThrowError("INTEGRITY_FAILED");
  });

  it("admission-triggered compaction removes only safe expired rows before declaring capacity exhaustion", () => {
    const ledger = new DeterministicReplayLedger<"device_ping">({
      claimIdSource: (() => {
      let index = 0;
        return () => { const bytes = Buffer.alloc(32); bytes.writeUInt32BE(index++, 0); return bytes.toString("base64url"); };
      })(),
    });
    for (let index = 0; index < TASK5_REPLAY_LIMITS.maxRowsPerSpace; index += 1) {
      const decision = ledger.admit(space, {
        ...envelope(`018f4f9a-${index.toString(16).padStart(4, "0")}-4aaa-8aaa-aaaaaaaaaaaa`),
        header: { message_id: `018f4f9a-${index.toString(16).padStart(4, "0")}-4aaa-8aaa-aaaaaaaaaaaa`, sequence: String(index), expires_at: "2026-08-08T00:01:00.000Z" },
      } as never, "2026-08-08T00:00:00.000Z", 1);
      expect(decision.kind).toBe("accepted");
      if (decision.kind === "accepted") ledger.finalize(decision.claim, retainExactWireBytes(Uint8Array.from([7])));
    }
    const before = ledger.capacity(space);
    expect(before.rows).toBe(TASK5_REPLAY_LIMITS.maxRowsPerSpace);
    const next = ledger.admit(space, {
      ...envelope("018f4f9a-ffff-4fff-8fff-ffffffffffff"),
      header: { message_id: "018f4f9a-ffff-4fff-8fff-ffffffffffff", sequence: String(TASK5_REPLAY_LIMITS.maxRowsPerSpace), expires_at: "2026-08-08T00:01:00.000Z" },
    } as never, "2026-08-08T00:00:00.000Z", 1, {
      now: new Date("2026-08-10T00:00:00.000Z"),
      canRemove: () => true,
    });
    expect(next.kind).toBe("accepted");
    expect(ledger.capacity(space).rows).toBe(1025);
  });

  it("never compacts a referenced row during admission-triggered compaction", () => {
    const ledger = new DeterministicReplayLedger<"device_ping">({
      claimIdSource: (() => {
        let index = 0;
        return () => { const bytes = Buffer.alloc(32); bytes.writeUInt32BE(index++, 0); return bytes.toString("base64url"); };
      })(),
    });
    let referencedClaimId: string | null = null;
    for (let index = 0; index < TASK5_REPLAY_LIMITS.maxRowsPerSpace; index += 1) {
      const id = `018f4f9a-${index.toString(16).padStart(4, "0")}-4aaa-8aaa-bbbbbbbbbbbb`;
      const decision = ledger.admit(space, { ...envelope(id), header: { message_id: id, sequence: String(index), expires_at: "2026-08-08T00:01:00.000Z" } } as never, "2026-08-08T00:00:00.000Z", 1);
      if (decision.kind !== "accepted") throw new Error("expected accepted claim");
      if (index === 0) {
        referencedClaimId = decision.claim.claimId;
        ledger.finalize(decision.claim, retainExactWireBytes(Uint8Array.from([9])));
      } else ledger.finalize(decision.claim, retainExactWireBytes(Uint8Array.from([7])));
    }
    const id = "018f4f9a-eeee-4eee-8eee-eeeeeeeeeeee";
    const decision = ledger.admit(space, { ...envelope(id), header: { message_id: id, sequence: String(TASK5_REPLAY_LIMITS.maxRowsPerSpace), expires_at: "2026-08-08T00:01:00.000Z" } } as never, "2026-08-08T00:00:00.000Z", 1, {
      now: new Date("2026-08-10T00:00:00.000Z"),
      canRemove: (claimId) => claimId !== referencedClaimId,
    });
    expect(decision.kind).toBe("accepted");
    expect(ledger.capacity(space).rows).toBe(1026);
  });
});

describe("closed persisted replay intent metadata", () => {
  const metadata: PersistedReplayIntentMetadata<"device_ping"> = {
    admitted_at: "2026-08-08T00:00:01.000Z",
    binding_snapshot: {
      adapter_credential_generation: null,
      agent_instance_id: null,
      agent_principal_id: null,
      connection_generation: "7",
      credential_id: "credential-1",
      device_id: "device-1",
      direction: "app-to-bridge",
      human_principal_id: "human-1",
      kind: "device",
      pairing_generation: "2",
      scope_ceiling: null,
      tenant_id: "tenant-1",
      workspace_id: null,
    },
    claim_id: "claim-1",
    lease_ref: {
      adapter_credential_lease_id: null,
      connection_lease_id: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      kind: "device_connection",
    },
    registry_identity: {
      direction: "app-to-bridge",
      envelope_schema_id: "urn:open-android-intelligence:protocol:v1:envelope:device_ping",
      header_schema_id: "urn:open-android-intelligence:protocol:v1:header:device_ping",
      message_schema_id: "urn:open-android-intelligence:protocol:v1:message:device_ping",
      message_type: "device_ping",
      signature_domain: parseSignatureDomain("control/app-to-bridge"),
      signer_role: "device",
    },
    replay_policy: REPLAY_POLICY_LITERALS.task5Default,
    retention_until: "2026-08-09T00:00:01.000Z",
    space: {
      adapter_credential_generation: null,
      credential_id: "credential-1",
      direction: "app-to-bridge",
      key_id: "key-1",
      kind: "device",
      pairing_generation: "2",
    },
  };

  it("emits the exact RFC 8785 bytes and rejects unknown projection members", () => {
    const expected = "{\"admitted_at\":\"2026-08-08T00:00:01.000Z\",\"binding_snapshot\":{\"adapter_credential_generation\":null,\"agent_instance_id\":null,\"agent_principal_id\":null,\"connection_generation\":\"7\",\"credential_id\":\"credential-1\",\"device_id\":\"device-1\",\"direction\":\"app-to-bridge\",\"human_principal_id\":\"human-1\",\"kind\":\"device\",\"pairing_generation\":\"2\",\"scope_ceiling\":null,\"tenant_id\":\"tenant-1\",\"workspace_id\":null},\"claim_id\":\"claim-1\",\"lease_ref\":{\"adapter_credential_lease_id\":null,\"connection_lease_id\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"kind\":\"device_connection\"},\"registry_identity\":{\"direction\":\"app-to-bridge\",\"envelope_schema_id\":\"urn:open-android-intelligence:protocol:v1:envelope:device_ping\",\"header_schema_id\":\"urn:open-android-intelligence:protocol:v1:header:device_ping\",\"message_schema_id\":\"urn:open-android-intelligence:protocol:v1:message:device_ping\",\"message_type\":\"device_ping\",\"signature_domain\":\"control/app-to-bridge\",\"signer_role\":\"device\"},\"replay_policy\":{\"class_id\":\"task5_default\",\"retention_rule_id\":\"retain_until_max_expires_at_or_admitted_at_plus_86400_seconds_v1\"},\"retention_until\":\"2026-08-09T00:00:01.000Z\",\"space\":{\"adapter_credential_generation\":null,\"credential_id\":\"credential-1\",\"direction\":\"app-to-bridge\",\"key_id\":\"key-1\",\"kind\":\"device\",\"pairing_generation\":\"2\"}}";
    expect(new TextDecoder().decode(canonicalReplayIntentMetadataBytes(metadata))).toBe(expected);
    expect(() => canonicalReplayIntentMetadataBytes({ ...metadata, database_id: "leak" } as never))
      .toThrowError("INVALID_REPLAY_INTENT_METADATA");
  });

  it("rejects every semantically noncanonical device registry, direction, schema, role and lease identity", () => {
    const valid = metadata;
    const mutations: unknown[] = [
      { ...valid, registry_identity: { ...valid.registry_identity, direction: "adapter-to-bridge" }, binding_snapshot: { ...valid.binding_snapshot, direction: "adapter-to-bridge" }, space: { ...valid.space, direction: "adapter-to-bridge" } },
      { ...valid, registry_identity: { ...valid.registry_identity, signer_role: "bridge-command" } },
      { ...valid, registry_identity: { ...valid.registry_identity, signature_domain: "key-rotation/app-to-bridge" } },
      { ...valid, registry_identity: { ...valid.registry_identity, message_schema_id: "urn:open-android-intelligence:protocol:v1:message:bridge_ping" } },
      { ...valid, registry_identity: { ...valid.registry_identity, header_schema_id: "urn:open-android-intelligence:protocol:v1:header:bridge_ping" } },
      { ...valid, registry_identity: { ...valid.registry_identity, envelope_schema_id: "urn:open-android-intelligence:protocol:v1:envelope:bridge_ping" } },
      { ...valid, lease_ref: { ...valid.lease_ref, connection_lease_id: `${"A".repeat(42)}B` } },
      { ...valid, claim_id: "contains space" },
      { ...valid, binding_snapshot: { ...valid.binding_snapshot, credential_id: "" }, space: { ...valid.space, credential_id: "" } },
    ];
    for (const mutation of mutations) {
      expect(() => canonicalReplayIntentMetadataBytes(mutation as never))
        .toThrowError("INVALID_REPLAY_INTENT_METADATA");
    }
  });

  it("rejects invalid, duplicate and non-code-point-sorted adapter scopes", () => {
    const base = {
      admitted_at: "2026-08-08T00:00:01.000Z",
      binding_snapshot: {
        adapter_credential_generation: "4", agent_instance_id: "agent-instance",
        agent_principal_id: "agent-principal", connection_generation: null,
        credential_id: "adapter-credential", device_id: null, direction: "adapter-to-bridge",
        human_principal_id: "human", kind: "adapter", pairing_generation: null,
        scope_ceiling: ["artifact.read", "tools.write"], tenant_id: "tenant", workspace_id: "workspace",
      },
      claim_id: "claim-2",
      lease_ref: { adapter_credential_lease_id: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", connection_lease_id: null, kind: "adapter_credential" },
      registry_identity: {
        direction: "adapter-to-bridge", envelope_schema_id: "urn:open-android-intelligence:protocol:v1:envelope:adapter_key_rotation",
        header_schema_id: "urn:open-android-intelligence:protocol:v1:header:adapter_key_rotation",
        message_schema_id: "urn:open-android-intelligence:protocol:v1:message:adapter_key_rotation",
        message_type: "adapter_key_rotation", signature_domain: parseSignatureDomain("key-rotation/adapter-to-bridge"), signer_role: "adapter",
      },
      replay_policy: REPLAY_POLICY_LITERALS.task5Default,
      retention_until: "2026-08-09T00:00:01.000Z",
      space: { adapter_credential_generation: "4", credential_id: "adapter-credential", direction: "adapter-to-bridge", key_id: "key-1", kind: "adapter", pairing_generation: null },
    } as const;
    expect(() => canonicalReplayIntentMetadataBytes(base)).not.toThrow();
    for (const scopes of [["tools.write", "artifact.read"], ["artifact.read", "artifact.read"], ["Admin"]]) {
      expect(() => canonicalReplayIntentMetadataBytes({
        ...base,
        binding_snapshot: { ...base.binding_snapshot, scope_ceiling: scopes },
      } as never)).toThrowError("INVALID_REPLAY_INTENT_METADATA");
    }
  });
});
