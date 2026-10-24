/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  DeterministicAdapterSecurityBackend,
  retainExactWireBytes,
  verifyAdapterAdmission,
  verifyTransportFrame,
  type LoadedTrustedBinding,
  type VerifiedSignedEnvelope,
} from "../src/adapter-admission.js";
import { signTestOnly } from "../src/crypto.js";
import { canonicalBytes, sha256B64Url, signingPreimage } from "../src/encoding.js";
import { parseSignatureDomain } from "../src/profile.js";

const envelope = (generation = "4") => ({
  header: {
    adapter_credential_id: "adapter-credential",
    adapter_credential_generation: generation,
    direction: "adapter-to-bridge",
  },
  payload: {},
}) as unknown as VerifiedSignedEnvelope;

const trusted = (scopes: string[]) => ({
  kind: "adapter",
  connectionId: "connection",
  credential: {
    credentialId: "adapter-credential", generation: 4n, tenantId: "tenant",
    agentPrincipalId: "agent-principal", agentInstanceId: "agent-instance",
    workspaceId: "workspace", scopeCeiling: scopes, active: true,
  },
  principal: { humanPrincipalId: "human", agentPrincipalId: "agent-principal" },
  credentialLease: {},
}) as unknown as Extract<LoadedTrustedBinding, { kind: "adapter" }>;

describe("adapter trusted admission", () => {
  it("copies, code-point sorts and freezes the independently loaded scope ceiling", () => {
    const source = ["tools.write", "artifact.read"];
    const decision = verifyAdapterAdmission(envelope(), trusted(source));
    source[0] = "admin";
    expect(decision).toMatchObject({ ok: true, context: { scopeCeiling: ["artifact.read", "tools.write"] } });
    if (!decision.ok || decision.context.kind !== "adapter") throw new Error("expected adapter context");
    expect(Object.isFrozen(decision.context.scopeCeiling)).toBe(true);
    expect(Object.isFrozen(decision.context)).toBe(true);
  });

  it("rejects a missing principal before authorization can inherit authority", () => {
    const binding = trusted([]) as unknown as { principal: { humanPrincipalId: string | null; agentPrincipalId: string | null } };
    binding.principal.agentPrincipalId = null;
    expect(verifyAdapterAdmission(envelope(), binding as never)).toEqual({ ok: false, error: "ADAPTER_PRINCIPAL_MISSING" });
  });

  it("fences an old or future adapter credential generation", () => {
    expect(verifyAdapterAdmission(envelope("3"), trusted([]))).toEqual({ ok: false, error: "CONNECTION_FENCED" });
    expect(verifyAdapterAdmission(envelope("5"), trusted([]))).toEqual({ ok: false, error: "CONNECTION_FENCED" });
  });
});

describe("runtime adapter backend capabilities", () => {
  type TestJwk = JsonWebKey & { alg: "ES256"; crv: "P-256"; kid: string; kty: "EC"; use: "sig"; x: string; y: string };
  const readJwk = (name: string): TestJwk => JSON.parse(readFileSync(new URL(`../test-only/keys/${name}`, import.meta.url), "utf8")) as TestJwk;
  const privateJwk = readJwk("adapter-a-private.jwk.json");
  const publicJwk = readJwk("adapter-a-public.jwk.json");
  const nextJwk = readJwk("adapter-a-next-public.jwk.json");
  /** The production backend has no generation mutator.  This test-only
   * subclass exercises the stage-8 fencing barrier through the same guarded
   * transaction seam used by AdapterRotationStore. */
  class TestGenerationBackend extends DeterministicAdapterSecurityBackend {
    bumpGeneration(nextGeneration: bigint): void {
      this.runCredentialRotationTransaction(() => this.commitCredentialGeneration(nextGeneration));
    }
  }
  const makeBackend = (beforeReplayCommit?: () => Promise<void>) => new TestGenerationBackend({
    credential: {
      credentialId: "adapter-credential", generation: 4n, tenantId: "tenant",
      agentPrincipalId: "agent-principal", agentInstanceId: "agent-instance",
      workspaceId: "workspace", scopeCeiling: ["artifact.read", "tools.write"], active: true,
    },
    principal: { humanPrincipalId: "human", agentPrincipalId: "agent-principal" },
    keyRings: [{ id: { owner: "adapter", credentialId: "adapter-credential" }, snapshot: {
      storeRevision: 1n,
      state: { activeKeyId: publicJwk.kid, bindingGeneration: 4n, pending: null, activationOutbox: new Map(), keys: new Map([[publicJwk.kid, {
        keyId: publicJwk.kid, publicJwk, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null,
      }]]) },
    } }],
    beforeReplayCommit,
  });
  const wire = () => {
    const payload = {
      rotation_id: "018f4f9a-aaaa-4aaa-8aaa-aaaaaaaaaaaa", old_key_id: publicJwk.kid,
      new_public_jwk: nextJwk,
      new_key_thumbprint: sha256B64Url(canonicalBytes({ crv: nextJwk.crv, kty: nextJwk.kty, x: nextJwk.x, y: nextJwk.y })),
      challenge: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", next_adapter_credential_generation: "5",
    };
    const header = {
      protocol_version: "1.0", message_schema: "urn:open-android-intelligence:protocol:v1:message:adapter_key_rotation",
      message_type: "adapter_key_rotation", message_id: "018f4f9a-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
      key_id: publicJwk.kid, direction: "adapter-to-bridge", sequence: "1",
      issued_at: "2026-08-08T00:00:00.000Z", expires_at: "2026-08-08T00:05:00.000Z",
      payload_digest: sha256B64Url(canonicalBytes(payload)), adapter_credential_id: "adapter-credential",
      adapter_credential_generation: "4",
    };
    return canonicalBytes({ header, payload, signature: signTestOnly(privateJwk, signingPreimage(parseSignatureDomain("key-rotation/adapter-to-bridge"), { header, payload })) });
  };

  it("rejects an ingress minted by another valid adapter backend before replay lookup", async () => {
    const owner = makeBackend();
    const foreign = makeBackend();
    const session = await owner.authenticateAdapter({ handleId: "adapter-owner", connectionId: "adapter-connection" });
    await expect(verifyTransportFrame(wire(), session.ingress, {
      clock: { wallNow: () => new Date("2026-08-08T00:00:00.000Z"), monotonicNowMs: () => 0n },
      keyRings: foreign, admission: foreign, authorization: { evaluate: () => ({ allowed: true }) },
    })).resolves.toEqual({ ok: false, error: "AUTH_FAILED" });
    expect(foreign.diagnostics()).toEqual({ replayLookups: 0n, replayMutations: 0n });
  });

  it("rechecks the exact adapter lease after the stage-8 barrier before any replay lookup", async () => {
    let arrived = (): void => {};
    let release = (): void => {};
    const atBarrier = new Promise<void>((resolve) => { arrived = resolve; });
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const backend = makeBackend(async () => { arrived(); await gate; });
    const session = await backend.authenticateAdapter({ handleId: "adapter-race", connectionId: "adapter-connection" });
    const decision = verifyTransportFrame(wire(), session.ingress, {
      clock: { wallNow: () => new Date("2026-08-08T00:00:00.000Z"), monotonicNowMs: () => 0n },
      keyRings: backend, admission: backend, authorization: { evaluate: () => ({ allowed: true }) },
    });
    await atBarrier;
    backend.bumpGeneration(5n);
    release();
    await expect(decision).resolves.toEqual({ ok: false, error: "CONNECTION_FENCED" });
    expect(backend.diagnostics()).toEqual({ replayLookups: 0n, replayMutations: 0n });
  });

  it("does not expose a direct credential-generation bypass outside the guarded rotation seam", () => {
    const backend = makeBackend();
    expect(() => (backend as unknown as { commitCredentialGeneration(nextGeneration: bigint): void })
      .commitCredentialGeneration(5n)).toThrow("AUTH_FAILED");
    expect(backend.bumpGeneration).toBeTypeOf("function");
    // The test-only subclass seam is the only deliberate barrier hook; the
    // production backend itself has no unguarded rotateCredentialGeneration.
    expect(Reflect.has(DeterministicAdapterSecurityBackend.prototype, "rotateCredentialGeneration")).toBe(false);
  });

  it("persists exact adapter intent charge and recovers pending/finalized receipt state across restart", async () => {
    const backend = makeBackend();
    const session = await backend.authenticateAdapter({ handleId: "adapter-durable", connectionId: "adapter-connection" });
    const rawWire = wire();
    const dependencies = {
      clock: { wallNow: () => new Date("2026-08-08T00:00:00.000Z"), monotonicNowMs: () => 0n },
      keyRings: backend, admission: backend, authorization: { evaluate: () => ({ allowed: true as const }) },
    };
    const accepted = await verifyTransportFrame(rawWire, session.ingress, dependencies);
    if (!accepted.ok || accepted.kind !== "accepted" || accepted.frame.context.kind !== "adapter") {
      throw new Error("expected accepted adapter frame");
    }

    const snapshot = backend.snapshot();
    expect(snapshot.replayRows).toHaveLength(1);
    const stored = snapshot.replayRows[0];
    if (!stored) throw new Error("expected replay row");
    expect(stored).toMatchObject({
      status: "pending",
      rawWire,
      claim: { claimId: accepted.frame.claim.claimId },
      persistedMetadata: {
        admitted_at: "2026-08-08T00:00:00.000Z",
        binding_snapshot: { kind: "adapter", adapter_credential_generation: "4" },
        lease_ref: { kind: "adapter_credential" },
      },
    });
    expect(stored.retainedBytes).toBe(rawWire.byteLength + stored.intentMetadataBytes.byteLength + 16_384);
    expect(backend.capacityDiagnostics(accepted.frame.claim.space))
      .toEqual({ rows: 1, retainedBytes: stored.retainedBytes });

    const restarted = DeterministicAdapterSecurityBackend.restart(snapshot);
    const reconciler = restarted.createReconciler("adapter-rotation-recovery");
    const recovered = await restarted.loadPending(reconciler, accepted.frame.claim.claimId);
    expect(recovered).toMatchObject({ kind: "pending", frame: { claim: { claimId: accepted.frame.claim.claimId } } });
    if (recovered.kind !== "pending") throw new Error("expected recovered adapter frame");
    expect(recovered.frame.envelope.rawWire.copy()).toEqual(rawWire);

    const receiptBytes = canonicalBytes({ status: "adapter-rotation-committed" });
    await expect(restarted.finalize(recovered.frame.claim, retainExactWireBytes(receiptBytes)))
      .resolves.toEqual({ kind: "stored" });
    const finalizedSnapshot = restarted.snapshot();
    expect(finalizedSnapshot.replayRows[0]).toMatchObject({
      status: "finalized", receipt: receiptBytes,
      retainedBytes: rawWire.byteLength + stored.intentMetadataBytes.byteLength + receiptBytes.byteLength,
    });

    const restartedAgain = DeterministicAdapterSecurityBackend.restart(finalizedSnapshot);
    const retrySession = await restartedAgain.authenticateAdapter({ handleId: "adapter-retry", connectionId: "adapter-connection-2" });
    const duplicate = await verifyTransportFrame(rawWire, retrySession.ingress, {
      ...dependencies, keyRings: restartedAgain, admission: restartedAgain,
    });
    expect(duplicate).toMatchObject({ ok: true, kind: "duplicate" });
    if (!duplicate.ok || duplicate.kind !== "duplicate") throw new Error("expected cached adapter receipt");
    expect(duplicate.cachedReceipt.copy()).toEqual(receiptBytes);
  });
});
