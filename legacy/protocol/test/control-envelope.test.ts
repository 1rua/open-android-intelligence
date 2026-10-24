/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  DeterministicDeviceSecurityBackend,
  retainExactWireBytes,
  verifySignedEnvelope,
  verifyAuthenticatedBinding,
  verifyTransportFrame,
  type AuthenticatedIngressHandle,
  type AdapterReplayAdmissionStore,
  type AuthenticatedBindingContext,
  type DeviceReplayAdmissionStore,
  type LoadedTrustedBinding,
  type VerifiedSignedEnvelope,
} from "../src/control-envelope.js";
import { signTestOnly } from "../src/crypto.js";
import { canonicalBytes, sha256B64Url, signingPreimage } from "../src/encoding.js";
import type { KeyRingState } from "../src/key-ring.js";
import { parseSignatureDomain } from "../src/profile.js";
import { canonicalReplayIntentMetadataBytes } from "../src/replay-window.js";

type TestJwk = JsonWebKey & {
  alg: "ES256"; crv: "P-256"; kid: string; kty: "EC"; use: "sig"; x: string; y: string;
};
const readJwk = (name: string): TestJwk => JSON.parse(
  readFileSync(new URL(`../test-only/keys/${name}`, import.meta.url), "utf8"),
) as TestJwk;
const DEVICE_PRIVATE = readJwk("device-a-private.jwk.json");
const DEVICE_PUBLIC = readJwk("device-a-public.jwk.json");
const DEVICE_NEXT_PUBLIC = readJwk("device-a-next-public.jwk.json");
const BRIDGE_PUBLIC = readJwk("bridge-command-public.jwk.json");
const B32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

const ringState = (pending: KeyRingState["pending"] = null, keys?: KeyRingState["keys"]): KeyRingState => ({
  activeKeyId: DEVICE_PUBLIC.kid,
  bindingGeneration: 3n,
  keys: keys ?? new Map([[DEVICE_PUBLIC.kid, {
    keyId: DEVICE_PUBLIC.kid, publicJwk: DEVICE_PUBLIC, lifecycle: "active",
    activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null,
  }]]),
  pending,
  activationOutbox: new Map(),
});

const backendWithRing = (state: KeyRingState = ringState(), beforeReplayCommit?: () => Promise<void>) => new DeterministicDeviceSecurityBackend({
  credential: {
    credentialId: "credential", tenantId: "tenant", humanPrincipalId: "human",
    deviceId: "device", pairingGeneration: 3n, active: true,
  },
  initialConnection: { generation: 7n, fenceRevision: 7n },
  keyRings: [{
    id: { owner: "device-installation", credentialId: "credential" },
    snapshot: { storeRevision: 1n, state },
  }],
  beforeReplayCommit,
});

const wireFor = ({
  messageType = "device_ping",
  messageId = "018f4f9a-4444-4444-8444-444444444444",
  sequence = "1",
  issuedAt = "2026-08-08T00:00:00.000Z",
  expiresAt = "2026-08-08T00:01:00.000Z",
  connectionGeneration = "8",
  payload = { challenge: B32 } as Record<string, unknown>,
  privateJwk = DEVICE_PRIVATE,
}: {
  messageType?: "device_ping" | "device_key_rotation";
  messageId?: string;
  sequence?: string;
  issuedAt?: string;
  expiresAt?: string;
  connectionGeneration?: string;
  payload?: Record<string, unknown>;
  privateJwk?: TestJwk;
} = {}): Uint8Array => {
  const rotation = messageType === "device_key_rotation";
  const domain = rotation ? "key-rotation/app-to-bridge" : "control/app-to-bridge";
  const header = {
    protocol_version: "1.0",
    message_schema: `urn:open-android-intelligence:protocol:v1:message:${messageType}`,
    message_type: messageType,
    message_id: messageId,
    key_id: DEVICE_PUBLIC.kid,
    direction: "app-to-bridge",
    sequence,
    issued_at: issuedAt,
    expires_at: expiresAt,
    payload_digest: sha256B64Url(canonicalBytes(payload)),
    device_id: "device",
    pairing_generation: "3",
    connection_generation: connectionGeneration,
  };
  const signature = signTestOnly(privateJwk, signingPreimage(parseSignatureDomain(domain), { header, payload }));
  return canonicalBytes({ header, payload, signature });
};

const fixedClock = (instant = "2026-08-08T00:00:00.000Z") => {
  let reads = 0;
  return {
    wallNow: () => { reads += 1; return new Date(instant); },
    monotonicNowMs: () => 0n,
    reads: () => reads,
  };
};

const rotationPayload = () => ({
  rotation_id: "018f4f9a-4444-4444-8444-444444444445",
  old_key_id: DEVICE_PUBLIC.kid,
  new_public_jwk: DEVICE_NEXT_PUBLIC,
  new_key_thumbprint: sha256B64Url(canonicalBytes({
    crv: DEVICE_NEXT_PUBLIC.crv, kty: DEVICE_NEXT_PUBLIC.kty,
    x: DEVICE_NEXT_PUBLIC.x, y: DEVICE_NEXT_PUBLIC.y,
  })),
  challenge: B32,
});

describe("real signed Task 5 admission", () => {
  it("verifies and admits a deterministic valid ES256 device ping with one wall-clock sample", async () => {
    const backend = backendWithRing();
    const session = await backend.authenticateDevice({
      handleId: "https-session", transport: "https", connectionId: "connection-8", transportProfileId: "tailnet",
    });
    const clock = fixedClock();
    const result = await verifyTransportFrame(wireFor(), session.ingress, {
      clock, keyRings: backend, admission: backend, authorization: { evaluate: () => ({ allowed: true }) },
    });
    expect(result).toMatchObject({ ok: true, kind: "accepted", frame: { envelope: { messageType: "device_ping" } } });
    expect(clock.reads()).toBe(1);
  });

  it.each([
    ["2026-08-08T00:00:59.999Z", "2026-08-08T00:01:59.999Z", true],
    ["2026-08-08T00:01:00.000Z", "2026-08-08T00:02:00.000Z", true],
    ["2026-08-08T00:01:00.001Z", "2026-08-08T00:02:00.001Z", false],
    ["2026-08-08T00:00:00.000Z", "2026-08-08T00:00:59.999Z", true],
    ["2026-08-08T00:00:00.000Z", "2026-08-08T00:01:00.000Z", true],
    ["2026-08-08T00:00:00.000Z", "2026-08-08T00:01:00.001Z", false],
  ])("enforces ping skew/lifetime millisecond boundary %s → %s", async (issuedAt, expiresAt, accepted) => {
    const backend = backendWithRing();
    const session = await backend.authenticateDevice({
      handleId: "boundary-session", transport: "wss", connectionId: "connection-8", transportProfileId: "public",
    });
    const result = await verifySignedEnvelope(wireFor({ issuedAt, expiresAt }), session.ingress, {
      clock: fixedClock(), keyRings: backend, admission: backend,
    });
    expect(result.ok).toBe(accepted);
    if (!accepted) expect(result).toEqual({ ok: false, error: "MESSAGE_EXPIRED" });
  });

  it("uses only the lifecycle-authorizing same-ID public JWK", async () => {
    const alternate = { ...BRIDGE_PUBLIC, kid: DEVICE_PUBLIC.kid };
    const keys = new Map([[DEVICE_PUBLIC.kid, {
      keyId: DEVICE_PUBLIC.kid, publicJwk: alternate, lifecycle: "active" as const,
      activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null,
    }]]);
    const backend = backendWithRing(ringState(null, keys));
    const session = await backend.authenticateDevice({
      handleId: "alternate-key", transport: "https", connectionId: "connection-8", transportProfileId: "tailnet",
    });
    await expect(verifySignedEnvelope(wireFor(), session.ingress, {
      clock: fixedClock(), keyRings: backend, admission: backend,
    })).resolves.toEqual({ ok: false, error: "AUTH_FAILED" });
  });

  it.each([
    ["2026-08-08T00:00:59.999Z", "2026-08-08T00:05:59.999Z", true],
    ["2026-08-08T00:01:00.000Z", "2026-08-08T00:06:00.000Z", true],
    ["2026-08-08T00:01:00.001Z", "2026-08-08T00:06:00.001Z", false],
    ["2026-08-08T00:00:00.000Z", "2026-08-08T00:04:59.999Z", true],
    ["2026-08-08T00:00:00.000Z", "2026-08-08T00:05:00.000Z", true],
    ["2026-08-08T00:00:00.000Z", "2026-08-08T00:05:00.001Z", false],
  ])("enforces rotation skew/lifetime millisecond boundary %s → %s", async (issuedAt, expiresAt, accepted) => {
    const backend = backendWithRing();
    const session = await backend.authenticateDevice({
      handleId: "rotation-boundary", transport: "https", connectionId: "connection-8", transportProfileId: "tailnet",
    });
    const result = await verifySignedEnvelope(wireFor({
      messageType: "device_key_rotation", payload: rotationPayload(), issuedAt, expiresAt,
    }), session.ingress, { clock: fixedClock(), keyRings: backend, admission: backend });
    expect(result.ok).toBe(accepted);
    if (!accepted) expect(result).toEqual({ ok: false, error: "MESSAGE_EXPIRED" });
  });

  it("accepts only the byte-identical retained pending rotation proposal key reuse", async () => {
    const payload = rotationPayload();
    const exact = wireFor({ messageType: "device_key_rotation", payload });
    const digest = sha256B64Url(exact);
    const pending = {
      transcript: {
        rotationId: payload.rotation_id, oldKeyId: payload.old_key_id,
        newPublicJwk: DEVICE_NEXT_PUBLIC, newKeyThumbprint: payload.new_key_thumbprint,
        challenge: payload.challenge, proposalDigest: digest, pairingGeneration: 3n,
        oldAdapterCredentialGeneration: null, nextAdapterCredentialGeneration: null,
      },
      localProposal: null, cachedAck: null, proposalClaimId: "claim-rotation", ackClaimId: null,
    };
    const keys = new Map([
      [DEVICE_PUBLIC.kid, { keyId: DEVICE_PUBLIC.kid, publicJwk: DEVICE_PUBLIC, lifecycle: "active" as const, activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null }],
      [DEVICE_NEXT_PUBLIC.kid, { keyId: DEVICE_NEXT_PUBLIC.kid, publicJwk: DEVICE_NEXT_PUBLIC, lifecycle: "pending" as const, activatedAt: null, signingNotAfter: null, liveVerifyUntil: null }],
    ]);
    const backend = backendWithRing(ringState(pending, keys));
    const session = await backend.authenticateDevice({
      handleId: "rotation", transport: "https", connectionId: "connection-8", transportProfileId: "tailnet",
    });
    const dependencies = { clock: fixedClock(), keyRings: backend, admission: backend };
    await expect(verifySignedEnvelope(exact, session.ingress, dependencies)).resolves.toMatchObject({ ok: true });
    const changed = wireFor({ messageType: "device_key_rotation", payload: { ...payload, challenge: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQ" } });
    await expect(verifySignedEnvelope(changed, session.ingress, dependencies))
      .resolves.toEqual({ ok: false, error: "INTEGRITY_FAILED" });
  });

  it("rejects a foreign ingress/backend pair before any replay lookup", async () => {
    const owner = backendWithRing();
    const foreign = backendWithRing();
    const session = await owner.authenticateDevice({
      handleId: "owner-session", transport: "https", connectionId: "owner-8", transportProfileId: "tailnet",
    });
    await expect(verifyTransportFrame(wireFor(), session.ingress, {
      clock: fixedClock(), keyRings: foreign, admission: foreign,
      authorization: { evaluate: () => ({ allowed: true }) },
    })).resolves.toEqual({ ok: false, error: "AUTH_FAILED" });
    expect(foreign.diagnostics()).toEqual({ replayLookups: 0n, replayMutations: 0n });
  });

  it("rejects a separately substituted key-ring backend before signature authority is used", async () => {
    const owner = backendWithRing();
    const foreignKeyRings = backendWithRing();
    const session = await owner.authenticateDevice({
      handleId: "owner-with-foreign-ring", transport: "https", connectionId: "owner-8", transportProfileId: "tailnet",
    });
    await expect(verifySignedEnvelope(wireFor(), session.ingress, {
      clock: fixedClock(), keyRings: foreignKeyRings, admission: owner,
    })).resolves.toEqual({ ok: false, error: "AUTH_FAILED" });
  });

  it("rejects a foreign connection lease before replay state is read or mutated", async () => {
    const owner = backendWithRing();
    const foreign = backendWithRing();
    const ownedSession = await owner.authenticateDevice({
      handleId: "owned", transport: "wss", connectionId: "owned-8", transportProfileId: "public",
    });
    const foreignSession = await foreign.authenticateDevice({
      handleId: "foreign", transport: "https", connectionId: "foreign-8", transportProfileId: "tailnet",
    });
    const admitted = await verifyTransportFrame(wireFor(), ownedSession.ingress, {
      clock: fixedClock(), keyRings: owner, admission: owner,
      authorization: { evaluate: () => ({ allowed: true }) },
    });
    if (!admitted.ok || admitted.kind !== "accepted" || admitted.frame.context.kind !== "device") {
      throw new Error("expected accepted device frame");
    }
    const before = owner.diagnostics();
    await expect(owner.admitDevice({
      envelope: admitted.frame.envelope,
      context: admitted.frame.context,
      connectionLease: foreignSession.allocation.lease,
      admittedAt: "2026-08-08T00:00:00.000Z",
    })).resolves.toEqual({ kind: "rejected", error: "CONNECTION_FENCED", denial: "DEVICE_LEASE_STALE" });
    expect(owner.diagnostics()).toEqual(before);
  });

  it("serializes identical contenders to one stable accepted claim and one pending denial", async () => {
    let arrivals = 0;
    let release = (): void => {};
    const gate = new Promise<void>((resolve) => { release = resolve; });
    let releaseArrivals = (): void => {};
    const bothArrived = new Promise<void>((resolve) => { releaseArrivals = resolve; });
    const barrier = async () => {
      arrivals += 1;
      if (arrivals === 2) releaseArrivals();
      await gate;
    };
    const backend = backendWithRing(ringState(), barrier);
    const session = await backend.authenticateDevice({
      handleId: "race", transport: "https", connectionId: "race-8", transportProfileId: "tailnet",
    });
    const dependencies = {
      clock: fixedClock(), keyRings: backend, admission: backend,
      authorization: { evaluate: () => ({ allowed: true as const }) },
    };
    const first = verifyTransportFrame(wireFor(), session.ingress, dependencies);
    const second = verifyTransportFrame(wireFor(), session.ingress, dependencies);
    await bothArrived;
    release();
    const decisions = await Promise.all([first, second]);
    expect(decisions.filter((decision) => decision.ok && decision.kind === "accepted")).toHaveLength(1);
    expect(decisions.filter((decision) => !decision.ok && decision.error === "REPLAY_REJECTED")).toHaveLength(1);
    expect(backend.diagnostics()).toEqual({ replayLookups: 2n, replayMutations: 1n });
  });

  it("serializes different admissible sequences without caller CAS", async () => {
    let arrivals = 0;
    let release = (): void => {};
    let releaseArrivals = (): void => {};
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const bothArrived = new Promise<void>((resolve) => { releaseArrivals = resolve; });
    const backend = backendWithRing(ringState(), async () => {
      arrivals += 1;
      if (arrivals === 2) releaseArrivals();
      await gate;
    });
    const session = await backend.authenticateDevice({
      handleId: "different-race", transport: "wss", connectionId: "race-8", transportProfileId: "public",
    });
    const dependencies = {
      clock: fixedClock(), keyRings: backend, admission: backend,
      authorization: { evaluate: () => ({ allowed: true as const }) },
    };
    const first = verifyTransportFrame(wireFor(), session.ingress, dependencies);
    const second = verifyTransportFrame(wireFor({
      messageId: "018f4f9a-4444-4444-8444-444444444446", sequence: "2",
    }), session.ingress, dependencies);
    await bothArrived;
    release();
    const decisions = await Promise.all([first, second]);
    expect(decisions.every((decision) => decision.ok && decision.kind === "accepted")).toBe(true);
    expect(backend.diagnostics()).toEqual({ replayLookups: 2n, replayMutations: 2n });
  });

  it("reconstructs an accepted pending frame after restart without retry bytes", async () => {
    const backend = backendWithRing();
    const session = await backend.authenticateDevice({
      handleId: "restart-source", transport: "https", connectionId: "connection-8", transportProfileId: "tailnet",
    });
    const rawWire = wireFor();
    const admitted = await verifyTransportFrame(rawWire, session.ingress, {
      clock: fixedClock(), keyRings: backend, admission: backend,
      authorization: { evaluate: () => ({ allowed: true }) },
    });
    if (!admitted.ok || admitted.kind !== "accepted") throw new Error("expected pending frame");
    const snapshot = backend.snapshot();
    expect(snapshot.pendingRows).toHaveLength(1);
    expect(snapshot.pendingRows[0]?.rawWire).toEqual(rawWire);
    const pending = snapshot.pendingRows[0];
    if (!pending) throw new Error("expected persisted pending row");
    const leaseId = pending.persistedMetadata.lease_ref.connection_lease_id;
    expect(leaseId).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(pending.persistedMetadata).toEqual({
      admitted_at: "2026-08-08T00:00:00.000Z",
      binding_snapshot: {
        adapter_credential_generation: null, agent_instance_id: null, agent_principal_id: null,
        connection_generation: "8", credential_id: "credential", device_id: "device",
        direction: "app-to-bridge", human_principal_id: "human", kind: "device",
        pairing_generation: "3", scope_ceiling: null, tenant_id: "tenant", workspace_id: null,
      },
      claim_id: admitted.frame.claim.claimId,
      lease_ref: { adapter_credential_lease_id: null, connection_lease_id: leaseId, kind: "device_connection" },
      registry_identity: {
        direction: "app-to-bridge", envelope_schema_id: "urn:open-android-intelligence:protocol:v1:envelope:device_ping",
        header_schema_id: "urn:open-android-intelligence:protocol:v1:header:device_ping",
        message_schema_id: "urn:open-android-intelligence:protocol:v1:message:device_ping",
        message_type: "device_ping", signature_domain: "control/app-to-bridge", signer_role: "device",
      },
      replay_policy: {
        class_id: "task5_default",
        retention_rule_id: "retain_until_max_expires_at_or_admitted_at_plus_86400_seconds_v1",
      },
      retention_until: "2026-08-09T00:00:00.000Z",
      space: {
        adapter_credential_generation: null, credential_id: "credential", direction: "app-to-bridge",
        key_id: DEVICE_PUBLIC.kid, kind: "device", pairing_generation: "3",
      },
    });
    expect(pending.intentMetadataBytes)
      .toEqual(canonicalReplayIntentMetadataBytes(pending.persistedMetadata));
    expect(backend.capacityDiagnostics(admitted.frame.claim.space)).toEqual({
      rows: 1,
      retainedBytes: rawWire.byteLength + pending.intentMetadataBytes.byteLength + 16_384,
    });
    const restarted = DeterministicDeviceSecurityBackend.restart(snapshot);
    const reconciler = restarted.createReconciler("rotation-recovery");
    const recovered = await restarted.loadPending(reconciler, admitted.frame.claim.claimId);
    expect(recovered).toMatchObject({
      kind: "pending",
      frame: { claim: { claimId: admitted.frame.claim.claimId }, envelope: { envelopeDigest: admitted.frame.envelope.envelopeDigest } },
    });
    if (recovered.kind !== "pending") throw new Error("expected recovered frame");
    expect(recovered.frame.envelope.rawWire.copy()).toEqual(rawWire);
  });

  it("finalizes a real claim and serves only defensive cached receipt bytes", async () => {
    const backend = backendWithRing();
    const session = await backend.authenticateDevice({
      handleId: "receipt", transport: "wss", connectionId: "connection-8", transportProfileId: "public",
    });
    const rawWire = wireFor();
    const dependencies = {
      clock: fixedClock(), keyRings: backend, admission: backend,
      authorization: { evaluate: () => ({ allowed: true as const }) },
    };
    const admitted = await verifyTransportFrame(rawWire, session.ingress, dependencies);
    if (!admitted.ok || admitted.kind !== "accepted") throw new Error("expected accepted frame");
    const receiptSource = canonicalBytes({ status: "ok", correlation_id: "018f4f9a-4444-4444-8444-444444444444" });
    await expect(backend.finalize(admitted.frame.claim, retainExactWireBytes(receiptSource)))
      .resolves.toEqual({ kind: "stored" });
    const duplicate = await verifyTransportFrame(rawWire, session.ingress, dependencies);
    expect(duplicate).toMatchObject({ ok: true, kind: "duplicate" });
    if (!duplicate.ok || duplicate.kind !== "duplicate") throw new Error("expected cached receipt");
    const callerCopy = duplicate.cachedReceipt.copy();
    expect(callerCopy).toEqual(receiptSource);
    callerCopy.fill(0);
    const duplicateAgain = await verifyTransportFrame(rawWire, session.ingress, dependencies);
    if (!duplicateAgain.ok || duplicateAgain.kind !== "duplicate") throw new Error("expected cached receipt");
    expect(duplicateAgain.cachedReceipt.copy()).toEqual(receiptSource);
    await expect(backend.finalize(admitted.frame.claim, retainExactWireBytes(new Uint8Array())))
      .resolves.toEqual({ kind: "rejected", error: "INTEGRITY_FAILED" });
    await expect(backend.finalize(admitted.frame.claim, retainExactWireBytes(new Uint8Array(16_385))))
      .resolves.toEqual({ kind: "rejected", error: "INTEGRITY_FAILED" });
  });
});

describe("device binding fencing", () => {
  const trusted = {
    kind: "device",
    transport: "https",
    transportProfileId: "tailnet",
    connectionId: "connection",
    allocatedConnectionGeneration: 8n,
    connectionLease: {},
    credential: {
      credentialId: "credential", tenantId: "tenant", humanPrincipalId: "human",
      deviceId: "device", pairingGeneration: 3n, active: true,
    },
  } as unknown as Extract<LoadedTrustedBinding, { kind: "device" }>;

  const envelope = (generation: string) => ({
    header: {
      device_id: "device", pairing_generation: "3", connection_generation: generation,
      direction: "app-to-bridge",
    },
  }) as unknown as VerifiedSignedEnvelope;

  it("fences old and unallocated future connection generations", () => {
    expect(verifyAuthenticatedBinding(envelope("7"), trusted, { kind: "current", generation: 8n, fenceRevision: 8n }))
      .toEqual({ ok: false, error: "CONNECTION_FENCED" });
    expect(verifyAuthenticatedBinding(envelope("9"), trusted, { kind: "current", generation: 8n, fenceRevision: 8n }))
      .toEqual({ ok: false, error: "CONNECTION_FENCED" });
  });

  it("mints a frozen context only from matching wire claims and committed binding", () => {
    const result = verifyAuthenticatedBinding(envelope("8"), trusted, { kind: "current", generation: 8n, fenceRevision: 8n });
    expect(result).toMatchObject({ ok: true, context: { credentialId: "credential", pairingGeneration: 3n, connectionGeneration: 8n } });
    if (!result.ok) throw new Error("expected binding");
    expect(Object.isFrozen(result.context)).toBe(true);
  });
});

describe("fixed admission precedence", () => {
  it("samples the wall clock once yet rejects an oversized frame before every store", async () => {
    let wallReads = 0;
    const dependencies = {
      clock: { wallNow: () => { wallReads += 1; return new Date("2026-08-08T00:00:00.000Z"); }, monotonicNowMs: () => 0n },
      keyRings: { load: async () => { throw new Error("late key lookup"); } },
      admission: { loadCommittedDeviceBinding: async () => { throw new Error("late binding lookup"); } },
      authorization: { evaluate: () => { throw new Error("late authorization"); } },
    };
    const ingress = { kind: "device", handleId: "handle" } as unknown as AuthenticatedIngressHandle<"device">;
    await expect(verifyTransportFrame(new Uint8Array(262_145), ingress, dependencies as never))
      .resolves.toEqual({ ok: false, error: "MESSAGE_TOO_LARGE" });
    expect(wallReads).toBe(1);
  });

  it.each([
    ["oversized wire beats malformed ingress", new Uint8Array(262_145), "MESSAGE_TOO_LARGE"],
    ["stale connection beats authorization denial", wireFor({ connectionGeneration: "7" }), "CONNECTION_FENCED"],
  ])("returns the earliest failure in the fixed stage order: %s", async (_name, rawWire, expected) => {
    const backend = backendWithRing();
    const session = await backend.authenticateDevice({
      handleId: "precedence", transport: "https", connectionId: "connection-8", transportProfileId: "tailnet",
    });
    let authorizationCalls = 0;
    const decision = await verifyTransportFrame(rawWire, session.ingress, {
      clock: fixedClock(), keyRings: backend, admission: backend,
      authorization: { evaluate: () => { authorizationCalls += 1; return { allowed: false }; } },
    } as never);
    expect(decision).toEqual({ ok: false, error: expected });
    expect(authorizationCalls).toBe(0);
    expect(backend.diagnostics()).toEqual({ replayLookups: 0n, replayMutations: 0n });
  });

  it("returns NOT_AUTHORIZED before replay admission and samples time once", async () => {
    const backend = backendWithRing();
    const session = await backend.authenticateDevice({
      handleId: "authorization-precedence", transport: "wss", connectionId: "connection-8", transportProfileId: "public",
    });
    const clock = fixedClock();
    await expect(verifyTransportFrame(wireFor(), session.ingress, {
      clock, keyRings: backend, admission: backend,
      authorization: { evaluate: () => ({ allowed: false }) },
    } as never)).resolves.toEqual({ ok: false, error: "NOT_AUTHORIZED" });
    expect(clock.reads()).toBe(1);
    expect(backend.diagnostics()).toEqual({ replayLookups: 0n, replayMutations: 0n });
  });
});

const compileTimeCapabilityEvidence = (
  deviceIngress: AuthenticatedIngressHandle<"device">,
  adapterAdmission: AdapterReplayAdmissionStore,
  deviceAdmission: DeviceReplayAdmissionStore,
  context: AuthenticatedBindingContext,
): void => {
  // @ts-expect-error Ingress handles are constructor-private opaque values.
  const _structuralIngress: AuthenticatedIngressHandle<"device"> = { kind: "device", handleId: "forged" };
  // @ts-expect-error A device ingress cannot be paired with an adapter backend capability.
  void verifyTransportFrame(new Uint8Array(), deviceIngress, { clock: {} as never, keyRings: {} as never, admission: adapterAdmission, authorization: {} as never });
  // @ts-expect-error Specialized stores expose no caller-selectable union admission method.
  void deviceAdmission.admit({ envelope: {} as never, context, admittedAt: "x" });
};
void compileTimeCapabilityEvidence;
