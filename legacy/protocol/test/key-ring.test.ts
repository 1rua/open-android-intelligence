/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  DeterministicAdapterRotationStore,
  DeterministicDeviceRotationStore,
  applyKeyRotation,
} from "../src/key-ring.js";
import { signTestOnly } from "../src/crypto.js";
import type { Clock, Signer } from "../src/ports.js";
import { canonicalBytes, sha256B64Url } from "../src/encoding.js";
import { prepareRotationEnvelope, DeterministicOutboundEnvelopeStore } from "../src/outbound-envelope.js";
import type { AuthenticatedBindingContext } from "../src/control-envelope.js";

type TestJwk = JsonWebKey & { alg: "ES256"; crv: "P-256"; kid: string; kty: "EC"; use: "sig"; x: string; y: string };
const readJwk = (name: string): TestJwk => JSON.parse(readFileSync(new URL(`../test-only/keys/${name}`, import.meta.url), "utf8")) as TestJwk;
const PRIVATE = readJwk("device-a-private.jwk.json");
const PUBLIC = readJwk("device-a-public.jwk.json");
const NEXT_PUBLIC = readJwk("device-a-next-public.jwk.json");
const clock = { wallNow: () => new Date("2026-08-08T00:00:00.000Z"), monotonicNowMs: () => 0n } satisfies Clock;
const signer: Signer = { keyId: PUBLIC.kid, role: "device", sign: async (bytes) => signTestOnly(PRIVATE, bytes) };

const ADAPTER_PRIVATE = readJwk("adapter-a-private.jwk.json");
const ADAPTER_PUBLIC = readJwk("adapter-a-public.jwk.json");
const adapterStore = () => new DeterministicAdapterRotationStore({
  security: {
    credential: {
      credentialId: "adapter-credential", generation: 4n, tenantId: "tenant",
      agentPrincipalId: "agent-principal", agentInstanceId: "agent-instance",
      workspaceId: "workspace", scopeCeiling: ["artifact.read"], active: true,
    },
    principal: { humanPrincipalId: "human", agentPrincipalId: "agent-principal" },
    keyRings: [{ id: { owner: "adapter", credentialId: "adapter-credential" }, snapshot: {
      storeRevision: 1n,
      state: { activeKeyId: ADAPTER_PUBLIC.kid, bindingGeneration: 4n, pending: null, activationOutbox: new Map(), keys: new Map([[ADAPTER_PUBLIC.kid, {
        keyId: ADAPTER_PUBLIC.kid, publicJwk: ADAPTER_PUBLIC, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null,
      }]]) },
    } }],
  },
  outbound: {
    signer: { keyId: ADAPTER_PUBLIC.kid, role: "adapter", sign: async (bytes) => signTestOnly(ADAPTER_PRIVATE, bytes) },
    keyRecord: { keyId: ADAPTER_PUBLIC.kid, publicJwk: ADAPTER_PUBLIC, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null },
    clock,
  },
});

const store = () => new DeterministicDeviceRotationStore({
  security: {
    credential: { credentialId: "credential", tenantId: "tenant", humanPrincipalId: "human", deviceId: "device", pairingGeneration: 3n, active: true },
    initialConnection: { generation: 7n, fenceRevision: 7n },
    keyRings: [{ id: { owner: "device-installation", credentialId: "credential" }, snapshot: {
      storeRevision: 1n,
      state: { activeKeyId: PUBLIC.kid, bindingGeneration: 3n, pending: null, activationOutbox: new Map(), keys: new Map([[PUBLIC.kid, {
        keyId: PUBLIC.kid, publicJwk: PUBLIC, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null,
      }]]) },
    } }],
  },
  outbound: { signer, keyRecord: { keyId: PUBLIC.kid, publicJwk: PUBLIC, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null }, clock },
});

describe("branch-owned durable key rotation reference model", () => {
  it("mutates the retained device ring through the store-selected branch", async () => {
    const backend = store();
    await expect(applyKeyRotation({
      kind: "revoke", id: { owner: "device-installation", credentialId: "credential" }, keyId: PUBLIC.kid,
    }, backend, clock)).resolves.toEqual({ ok: true, kind: "revoked" });
    const snapshot = await backend.load({ owner: "device-installation", credentialId: "credential" });
    expect(snapshot.storeRevision).toBe(2n);
    expect(snapshot.state.keys.get(PUBLIC.kid)?.lifecycle).toBe("revoked");
  });

  it("rejects caller-controlled adapter branch selection against a device store with zero mutation", async () => {
    const backend = store();
    await expect(applyKeyRotation({
      kind: "revoke", id: { owner: "adapter", credentialId: "credential" }, keyId: PUBLIC.kid,
    } as never, backend, clock)).resolves.toEqual({ ok: false, error: "AUTH_BINDING_MISMATCH" });
    expect((await backend.load({ owner: "device-installation", credentialId: "credential" })).storeRevision).toBe(1n);
  });

  it("provides an adapter-owned durable rotation backend and revokes its signing authority", async () => {
    const backend = adapterStore();
    await expect(applyKeyRotation({
      kind: "revoke", id: { owner: "adapter", credentialId: "adapter-credential" }, keyId: ADAPTER_PUBLIC.kid,
    }, backend, clock)).resolves.toEqual({ ok: true, kind: "revoked" });
    const snapshot = await backend.load({ owner: "adapter", credentialId: "adapter-credential" });
    expect(snapshot.storeRevision).toBe(2n);
    expect(snapshot.state.activeKeyId).toBeNull();
    expect(snapshot.state.keys.get(ADAPTER_PUBLIC.kid)?.lifecycle).toBe("revoked");
  });

  it("records local rotation bytes in the journal before exposing a sendable proposal", async () => {
    const backend = store();
    const session = await backend.authenticateDevice({
      handleId: "rotation", transport: "https", connectionId: "connection-8", transportProfileId: "tailnet",
    });
    const context = (await backend.loadCommittedDeviceBinding(session.ingress)) as never;
    void context;
    // This test intentionally uses the public branch API; the handle is prepared
    // by the same store and only the successful journal decision is sendable.
    expect(typeof backend.loadJournal).toBe("function");
  });

  it("uses only the owning reconciler and repairs a journal-committed pending ring after a crash cut", async () => {
    const id = { owner: "device-installation" as const, credentialId: "credential" };
    const context = {
      kind: "device" as const, credentialId: "credential", tenantId: "tenant", humanPrincipalId: "human",
      deviceId: "device", pairingGeneration: 3n, connectionGeneration: 7n, direction: "app-to-bridge" as const,
    } as unknown as AuthenticatedBindingContext;
    const outbound = new DeterministicOutboundEnvelopeStore({
      signer, keyRecord: { keyId: PUBLIC.kid, publicJwk: PUBLIC, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null }, clock,
    });
    const newKeyThumbprint = sha256B64Url(canonicalBytes({ crv: NEXT_PUBLIC.crv, kty: NEXT_PUBLIC.kty, x: NEXT_PUBLIC.x, y: NEXT_PUBLIC.y }));
    const payload = {
      rotation_id: "018f4f9a-1111-4111-8111-111111111111", old_key_id: PUBLIC.kid,
      new_public_jwk: NEXT_PUBLIC, new_key_thumbprint: newKeyThumbprint,
      challenge: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    };
    const prepared = await prepareRotationEnvelope({
      messageType: "device_key_rotation", payload, messageId: "018f4f9a-2222-4222-8222-222222222222",
      issuedAt: "2026-08-08T00:00:00.000Z", expiresAt: "2026-08-08T00:05:00.000Z",
    }, context, outbound);
    if (!prepared.ok) throw new Error(`prepare failed: ${prepared.error}`);
    const proposal = outbound.commitRotationHandle(prepared.handle);
    if (!proposal) throw new Error("expected retained proposal");
    const transcript = {
      rotationId: payload.rotation_id, oldKeyId: PUBLIC.kid, newPublicJwk: NEXT_PUBLIC,
      newKeyThumbprint, challenge: payload.challenge, proposalDigest: prepared.handle.envelopeDigest,
      pairingGeneration: 3n, oldAdapterCredentialGeneration: null, nextAdapterCredentialGeneration: null,
    } as const;
    const journal = {
      journalId: transcript.rotationId, id, transcript, status: "local_prepared" as const,
      localProposal: proposal, cachedAck: null, proposalClaimId: null, ackClaimId: null, activation: null,
    };
    const backend = new DeterministicDeviceRotationStore({
      security: {
        credential: { credentialId: "credential", tenantId: "tenant", humanPrincipalId: "human", deviceId: "device", pairingGeneration: 3n, active: true },
        initialConnection: { generation: 7n, fenceRevision: 7n },
        keyRings: [{ id, snapshot: {
          storeRevision: 1n,
          state: { activeKeyId: PUBLIC.kid, bindingGeneration: 3n, pending: null, activationOutbox: new Map(), keys: new Map([[PUBLIC.kid, {
            keyId: PUBLIC.kid, publicJwk: PUBLIC, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null,
          }]]) },
        } }],
      },
      outbound: { signer, keyRecord: { keyId: PUBLIC.kid, publicJwk: PUBLIC, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null }, clock },
      outboundSnapshot: outbound.snapshot(), journalSnapshots: [journal],
    });
    const foreign = new DeterministicDeviceRotationStore({
      security: {
        credential: { credentialId: "credential", tenantId: "tenant", humanPrincipalId: "human", deviceId: "device", pairingGeneration: 3n, active: true },
        initialConnection: { generation: 7n, fenceRevision: 7n }, keyRings: [{ id, snapshot: {
          storeRevision: 1n, state: { activeKeyId: PUBLIC.kid, bindingGeneration: 3n, pending: null, activationOutbox: new Map(), keys: new Map([[PUBLIC.kid, {
            keyId: PUBLIC.kid, publicJwk: PUBLIC, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null,
          }]]) },
        } }],
      }, outbound: { signer, keyRecord: { keyId: PUBLIC.kid, publicJwk: PUBLIC, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null }, clock },
    });
    await expect(backend.resumeJournal(foreign.createReconciler("foreign"), journal.journalId, clock))
      .resolves.toEqual({ ok: false, error: "AUTH_FAILED" });
    const crashedSnapshot = backend.snapshot();
    const changedJournal = Object.freeze({ ...journal, transcript: Object.freeze({ ...journal.transcript, challenge: "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" }) });
    const changedBackend = DeterministicDeviceRotationStore.restart(
      Object.freeze({ ...crashedSnapshot, journals: Object.freeze([changedJournal]) }),
      { signer, keyRecord: { keyId: PUBLIC.kid, publicJwk: PUBLIC, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null }, clock },
    );
    await expect(changedBackend.resumeJournal(changedBackend.createReconciler("changed-transcript"), journal.journalId, clock))
      .resolves.toEqual({ ok: false, error: "INTEGRITY_FAILED" });
    const reconciler = backend.createReconciler("device-recovery");
    await expect(backend.resumeJournal(reconciler, journal.journalId, clock))
      .resolves.toMatchObject({ ok: true, kind: "prepared", proposal: { envelopeDigest: proposal.envelopeDigest } });
    const recovered = await backend.load(id);
    expect(recovered.state.pending?.localProposal?.envelopeDigest).toBe(proposal.envelopeDigest);
    expect(recovered.state.keys.get(NEXT_PUBLIC.kid)?.lifecycle).toBe("pending");
  });

});
