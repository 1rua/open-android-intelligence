/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  DeterministicOutboundEnvelopeStore,
  prepareRotationEnvelope,
  prepareSignedEnvelope,
  type SendableOutboundEnvelope,
  type TransportSender,
} from "../src/outbound-envelope.js";
import { DeterministicDeviceSecurityBackend, verifyTransportFrame } from "../src/control-envelope.js";
import { signTestOnly, verifyEs256 } from "../src/crypto.js";
import { canonicalBytes, parseCanonicalJson, sha256B64Url, signingPreimage } from "../src/encoding.js";
import { parseSignatureDomain } from "../src/profile.js";
import type { KeyRecord } from "../src/key-ring.js";
import type { Signer } from "../src/ports.js";

type TestJwk = JsonWebKey & {
  alg: "ES256"; crv: "P-256"; kid: string; kty: "EC"; use: "sig"; x: string; y: string;
};
const readJwk = (name: string): TestJwk => JSON.parse(
  readFileSync(new URL(`../test-only/keys/${name}`, import.meta.url), "utf8"),
) as TestJwk;
const PRIVATE = readJwk("device-a-private.jwk.json");
const PUBLIC = readJwk("device-a-public.jwk.json");
const NEXT_PUBLIC = readJwk("device-a-next-public.jwk.json");
const CHALLENGE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const KEY_RECORD = { keyId: PUBLIC.kid, publicJwk: PUBLIC, lifecycle: "active" as const, activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null };
const clock = { wallNow: () => new Date("2026-08-08T00:00:00.000Z"), monotonicNowMs: () => 0n };

const inboundWire = (): Uint8Array => {
  const payload = { challenge: CHALLENGE };
  const header = {
    protocol_version: "1.0", message_schema: "urn:open-android-intelligence:protocol:v1:message:device_ping",
    message_type: "device_ping", message_id: "018f4f9a-5555-4555-8555-555555555555",
    key_id: PUBLIC.kid, direction: "app-to-bridge", sequence: "9",
    issued_at: "2026-08-08T00:00:00.000Z", expires_at: "2026-08-08T00:01:00.000Z",
    payload_digest: sha256B64Url(canonicalBytes(payload)), device_id: "device",
    pairing_generation: "3", connection_generation: "8",
  };
  return canonicalBytes({
    header, payload,
    signature: signTestOnly(PRIVATE, signingPreimage(parseSignatureDomain("control/app-to-bridge"), { header, payload })),
  });
};

const realContext = async () => {
  const backend = new DeterministicDeviceSecurityBackend({
    credential: { credentialId: "credential", tenantId: "tenant", humanPrincipalId: "human", deviceId: "device", pairingGeneration: 3n, active: true },
    initialConnection: { generation: 7n, fenceRevision: 7n },
    keyRings: [{ id: { owner: "device-installation", credentialId: "credential" }, snapshot: {
      storeRevision: 1n,
      state: { activeKeyId: PUBLIC.kid, bindingGeneration: 3n, pending: null, activationOutbox: new Map(), keys: new Map([[PUBLIC.kid, {
        keyId: PUBLIC.kid, publicJwk: PUBLIC, lifecycle: "active", activatedAt: "2026-08-07T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null,
      }]]) },
    } }],
  });
  const session = await backend.authenticateDevice({ handleId: "outbound", transport: "https", connectionId: "connection-8", transportProfileId: "tailnet" });
  const decision = await verifyTransportFrame(inboundWire(), session.ingress, {
    clock, keyRings: backend, admission: backend, authorization: { evaluate: () => ({ allowed: true }) },
  });
  if (!decision.ok || decision.kind !== "accepted" || decision.frame.context.kind !== "device") throw new Error("expected context");
  return decision.frame.context;
};

const signer = (onSign?: () => void): Signer => ({
  keyId: PUBLIC.kid,
  role: "device",
  sign: async (preimage) => { onSign?.(); return signTestOnly(PRIVATE, preimage); },
});

describe("durable outbound preparation reference model", () => {
  it("reserves, signs, self-verifies, retains immutable bytes and reloads them across restart", async () => {
    const context = await realContext();
    let signs = 0;
    const store = new DeterministicOutboundEnvelopeStore({
      signer: signer(() => { signs += 1; }), keyRecord: KEY_RECORD, clock,
    });
    const input = {
      messageType: "device_ping" as const, payload: { challenge: CHALLENGE },
      messageId: "018f4f9a-6666-4666-8666-666666666666",
      issuedAt: "2026-08-08T00:00:00.000Z", expiresAt: "2026-08-08T00:01:00.000Z",
    };
    const first = await prepareSignedEnvelope(input, context, store);
    expect(first).toMatchObject({ ok: true, kind: "prepared", envelope: { sequence: 0n, messageType: "device_ping" } });
    if (!first.ok) throw new Error("expected sendable");
    const original = first.envelope.rawWire.copy();
    const callerCopy = first.envelope.rawWire.copy();
    callerCopy.fill(0);
    expect(first.envelope.rawWire.copy()).toEqual(original);
    const parsed = parseCanonicalJson(original) as { header: Record<string, unknown>; payload: Record<string, unknown>; signature: string };
    expect(verifyEs256(PUBLIC, signingPreimage(parseSignatureDomain("control/app-to-bridge"), {
      header: parsed.header, payload: parsed.payload,
    }), parsed.signature)).toBe(true);
    await expect(prepareSignedEnvelope(input, context, store)).resolves.toMatchObject({ ok: true, kind: "same" });
    expect(signs).toBe(1);
    const restarted = DeterministicOutboundEnvelopeStore.restart(store.snapshot(), { signer: signer(), keyRecord: KEY_RECORD, clock });
    const loaded = await restarted.loadOrdinarySendable(first.envelope.space, input.messageId);
    expect(loaded?.rawWire.copy()).toEqual(original);
  });

  it("rejects wrong-key signer output before committing a row or bytes", async () => {
    const context = await realContext();
    const wrong = readJwk("bridge-command-private.jwk.json");
    const store = new DeterministicOutboundEnvelopeStore({
      signer: { keyId: PUBLIC.kid, role: "device", sign: async (bytes) => signTestOnly(wrong, bytes) },
      keyRecord: KEY_RECORD, clock,
    });
    await expect(prepareSignedEnvelope({
      messageType: "device_ping", payload: { challenge: CHALLENGE },
      messageId: "018f4f9a-7777-4777-8777-777777777777",
      issuedAt: "2026-08-08T00:00:00.000Z", expiresAt: "2026-08-08T00:01:00.000Z",
    }, context, store)).resolves.toEqual({ ok: false, error: "INTEGRITY_FAILED" });
    expect(store.snapshot().rows).toHaveLength(0);
  });

  it("retains rotation bytes while exposing only an opaque recoverable handle", async () => {
    const context = await realContext();
    const store = new DeterministicOutboundEnvelopeStore({ signer: signer(), keyRecord: KEY_RECORD, clock });
    const result = await prepareRotationEnvelope({
      messageType: "device_key_rotation", payload: {
        rotation_id: "018f4f9a-9999-4999-8999-999999999999", old_key_id: PUBLIC.kid,
        new_public_jwk: NEXT_PUBLIC,
        new_key_thumbprint: sha256B64Url(canonicalBytes({ crv: NEXT_PUBLIC.crv, kty: NEXT_PUBLIC.kty, x: NEXT_PUBLIC.x, y: NEXT_PUBLIC.y })),
        challenge: CHALLENGE,
      },
      messageId: "018f4f9a-8888-4888-8888-888888888888",
      issuedAt: "2026-08-08T00:00:00.000Z", expiresAt: "2026-08-08T00:05:00.000Z",
    }, context, store);
    expect(result).toMatchObject({ ok: true, kind: "prepared" });
    if (!result.ok) throw new Error("expected handle");
    expect("rawWire" in result.handle).toBe(false);
    expect("copy" in result.handle).toBe(false);
    expect((await store.loadRotationHandle(result.handle.space, result.handle.messageId))?.envelopeDigest)
      .toBe(result.handle.envelopeDigest);
  });

  it("serializes concurrent reservations so each space advances exactly once", async () => {
    const context = await realContext();
    let arrived!: () => void;
    const atReservation = new Promise<void>((resolve) => { arrived = resolve; });
    let release!: () => void;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    let barriers = 0;
    const store = new DeterministicOutboundEnvelopeStore({
      signer: signer(), keyRecord: KEY_RECORD, clock,
      barrier: async (stage) => {
        if (stage === "after_reservation" && barriers++ === 0) {
          arrived();
          await gate;
        }
      },
    });
    const base = { messageType: "device_ping" as const, payload: { challenge: CHALLENGE }, issuedAt: "2026-08-08T00:00:00.000Z", expiresAt: "2026-08-08T00:01:00.000Z" };
    const firstPromise = prepareSignedEnvelope({ ...base, messageId: "018f4f9a-aaaa-4aaa-8aaa-aaaaaaaaaaaa" }, context, store);
    await atReservation;
    const secondPromise = prepareSignedEnvelope({ ...base, messageId: "018f4f9a-bbbb-4bbb-8bbb-bbbbbbbbbbbb" }, context, store);
    release();
    const [first, second] = await Promise.all([firstPromise, secondPromise]);
    expect(first).toMatchObject({ ok: true, envelope: { sequence: 0n } });
    expect(second).toMatchObject({ ok: true, envelope: { sequence: 1n } });
  });

  it("rechecks dynamically supplied signing authority before durable commit", async () => {
    const context = await realContext();
    let current: KeyRecord = KEY_RECORD;
    const store = new DeterministicOutboundEnvelopeStore({
      signer: signer(), keyRecord: KEY_RECORD, keyRecordFor: () => current, clock,
      barrier: async (stage) => {
        if (stage === "before_commit") current = Object.freeze({ ...KEY_RECORD, lifecycle: "revoked" });
      },
    });
    await expect(prepareSignedEnvelope({
      messageType: "device_ping", payload: { challenge: CHALLENGE },
      messageId: "018f4f9a-cccc-4ccc-8ccc-cccccccccccc",
      issuedAt: "2026-08-08T00:00:00.000Z", expiresAt: "2026-08-08T00:01:00.000Z",
    }, context, store)).resolves.toEqual({ ok: false, error: "AUTH_FAILED" });
    expect(store.snapshot().rows).toHaveLength(0);
  });
});

const compileTimeOutboundEvidence = (sender: TransportSender, sendable: SendableOutboundEnvelope): void => {
  void sender.send(sendable);
  // @ts-expect-error A rotation handle cannot be sent by a transport.
  void sender.send({ handleId: "rotation" });
  // @ts-expect-error Raw bytes are not a sendable envelope capability.
  void sender.send(new Uint8Array());
};
void compileTimeOutboundEvidence;
