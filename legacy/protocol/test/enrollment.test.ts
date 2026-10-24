/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { signTestOnly, verifyEs256 } from "../src/crypto.js";
import { canonicalBytes, sha256B64Url, signingPreimage } from "../src/encoding.js";
import { consumeEnrollmentAttempt, type CanonicalIpAddress, type EnrollmentAttemptLimiter, type EnrollmentTicketRecord, type EnrollmentTicketStore, type LimitDecision } from "../src/enrollment.js";
import {
  loadMessageRegistry,
  verifyConnectMessage,
  verifyEnrollmentBridgeMessage,
  type P256PublicJwk,
  type VerifiedEnrollmentChallenge,
  type VerifiedEnrollmentComplete,
  type VerifiedEnrollmentError,
} from "../src/message-registry.js";
import { pairingShortCode, type PairingTranscript } from "../src/pairing-transcript.js";
import type { Clock, SignerRole, Verifier } from "../src/ports.js";
import { parseSignatureDomain } from "../src/profile.js";

type TestJwk = JsonWebKey & { alg: "ES256"; crv: "P-256"; kid: string; kty: "EC"; use: "sig"; x: string; y: string };
const readJwk = (name: string): TestJwk => JSON.parse(readFileSync(new URL(`../test-only/keys/${name}`, import.meta.url), "utf8")) as TestJwk;
const devicePrivate = readJwk("device-a-private.jwk.json");
const devicePublic = readJwk("device-a-public.jwk.json");
const bridgePrivate = readJwk("bridge-command-private.jwk.json");
const bridgePublic = readJwk("bridge-command-public.jwk.json");
const TICKET = Buffer.alloc(32, 3).toString("base64url");
const TICKET_DIGEST = sha256B64Url(Buffer.from(TICKET, "base64url"));
const CHALLENGE = Buffer.alloc(32, 4).toString("base64url");
const CLIENT_NONCE = Buffer.alloc(32, 5).toString("base64url");
const BRIDGE_NONCE = Buffer.alloc(32, 6).toString("base64url");
const fingerprint = (jwk: TestJwk) => sha256B64Url(canonicalBytes({ crv: jwk.crv, kty: jwk.kty, x: jwk.x, y: jwk.y }));
const BRIDGE_FP = fingerprint(bridgePublic);
const DEVICE_FP = fingerprint(devicePublic);
const remoteIp = "192.0.2.1" as CanonicalIpAddress;

class TestClock implements Clock {
  constructor(public wall = new Date("2026-08-08T00:01:00.000Z"), public mono = 1_000n) {}
  wallNow() { return new Date(this.wall); }
  monotonicNowMs() { return this.mono; }
}
class Store implements EnrollmentTicketStore {
  consumed = false;
  calls = 0;
  dispositions: string[] = [];
  constructor(readonly record: EnrollmentTicketRecord, readonly forced?: "unknown" | "expired" | "already_consumed") {}
  async consume(digest: string, now: Date) {
    this.calls += 1;
    if (this.forced) { this.dispositions.push(this.forced); return { kind: this.forced } as const; }
    if (digest !== this.record.ticketDigest) { this.dispositions.push("unknown"); return { kind: "unknown" } as const; }
    if (this.consumed) { this.dispositions.push("already_consumed"); return { kind: "already_consumed" } as const; }
    if (now.getTime() >= Date.parse(this.record.expiresAt)) { this.consumed = true; this.dispositions.push("expired"); return { kind: "expired" } as const; }
    this.consumed = true;
    this.dispositions.push("consumed");
    return { kind: "consumed", record: this.record } as const;
  }
}

class BarrierStore implements EnrollmentTicketStore {
  arrivals = 0;
  dispositions: string[] = [];
  #consumed = false;
  #arrivedResolve!: () => void;
  #releaseResolve!: () => void;
  readonly #arrived = new Promise<void>((resolve) => { this.#arrivedResolve = resolve; });
  readonly #release = new Promise<void>((resolve) => { this.#releaseResolve = resolve; });

  constructor(readonly record: EnrollmentTicketRecord) {}

  async consume(digest: string, _now: Date) {
    this.arrivals += 1;
    if (this.arrivals === 2) this.#arrivedResolve();
    await this.#release;
    if (digest !== this.record.ticketDigest) {
      this.dispositions.push("unknown");
      return { kind: "unknown" } as const;
    }
    if (this.#consumed) {
      this.dispositions.push("already_consumed");
      return { kind: "already_consumed" } as const;
    }
    this.#consumed = true;
    this.dispositions.push("consumed");
    return { kind: "consumed", record: this.record } as const;
  }

  waitForBothArrivals(): Promise<void> { return this.#arrived; }
  release(): void { this.#releaseResolve(); }
}
const record = (): EnrollmentTicketRecord => ({ ticketDigest: TICKET_DIGEST, tenantId: "tenant-a", humanPrincipalId: "human-a", agentInstanceId: "agent-a", enrollmentScopeCeiling: ["clipboard.read"], challenge: CHALLENGE, bridgeId: "bridge-a", bridgeFingerprint: BRIDGE_FP, bridgeCommandPublicJwk: bridgePublic, bridgeNonce: BRIDGE_NONCE, expiresAt: "2026-08-08T00:05:00.000Z" });
const signEnvelope = (type: string, payload: Record<string, unknown>, key: TestJwk, privateKey: TestJwk, direction: "app-to-bridge" | "bridge-to-app", ticketDigest = TICKET_DIGEST, headerPatch: Record<string, unknown> = {}) => {
  const header = { protocol_version: "1.0", message_schema: `urn:open-android-intelligence:protocol:v1:message:${type}`, message_type: type, message_id: direction === "app-to-bridge" ? "018f4f9a-4444-4444-8444-444444444444" : "018f4f9a-4555-4555-8555-555555555555", key_id: key.kid, direction, issued_at: "2026-08-08T00:00:00.000Z", expires_at: "2026-08-08T00:05:00.000Z", payload_digest: sha256B64Url(canonicalBytes(payload)), enrollment_ticket_digest: ticketDigest, ...headerPatch };
  const unsigned = { header, payload };
  const domain = parseSignatureDomain(direction === "app-to-bridge" ? "enrollment/app-to-bridge" : "enrollment/bridge-to-app");
  return canonicalBytes({ ...unsigned, signature: signTestOnly(privateKey, signingPreimage(domain, unsigned)) });
};
const responsePayload = (patch: Record<string, unknown> = {}) => ({ ticket: TICKET, challenge_response: CHALLENGE, device_public_jwk: devicePublic, client_nonce: CLIENT_NONCE, supported_versions: ["0.9", "1.0"], ...patch });
const responseWire = (patch: Record<string, unknown> = {}) => signEnvelope("enrollment_response", responsePayload(patch), devicePublic, devicePrivate, "app-to-bridge");
const rewriteWire = (wire: Uint8Array, mutate: (value: Record<string, unknown>) => void): Uint8Array => {
  const value = JSON.parse(new TextDecoder().decode(wire)) as Record<string, unknown>;
  mutate(value);
  return canonicalBytes(value);
};

class TracingLimiter {
  ipCalls = 0;
  principalCalls = 0;
  admittedIpTimestamps: bigint[] = [];
  admittedPrincipalTimestamps: bigint[] = [];
  events: string[] = [];
  constructor(readonly ipDecision: LimitDecision = { allowed: true }, readonly principalDecision: LimitDecision = { allowed: true }) {}
  admitIp(_ip: CanonicalIpAddress, now: bigint) {
    this.ipCalls += 1;
    this.events.push("ip");
    if (this.ipDecision.allowed) this.admittedIpTimestamps.push(now);
    return this.ipDecision;
  }
  admitPrincipal(_principal: string, now: bigint) {
    this.principalCalls += 1;
    this.events.push("principal");
    if (this.principalDecision.allowed) this.admittedPrincipalTimestamps.push(now);
    return this.principalDecision;
  }
}

class InstrumentedWindowLimiter implements EnrollmentAttemptLimiter {
  readonly #ip = new Map<string, bigint[]>();
  readonly #principal = new Map<string, bigint[]>();
  constructor(readonly bucketCap = 64) {}
  get bucketCount() { return this.#ip.size + this.#principal.size; }
  #admit(store: Map<string, bigint[]>, key: string, now: bigint): LimitDecision {
    const retained = (store.get(key) ?? []).filter((timestamp) => timestamp + 600_000n > now);
    if (!store.has(key) && this.bucketCount >= this.bucketCap) return { allowed: false, retryAfterSeconds: "600" };
    if (retained.length < 5) { retained.push(now); store.set(key, retained); return { allowed: true }; }
    const oldest = retained[0];
    if (oldest === undefined) throw new Error("invalid test limiter state");
    return { allowed: false, retryAfterSeconds: ((oldest + 600_000n - now + 999n) / 1_000n).toString() };
  }
  admitIp(ip: CanonicalIpAddress, now: bigint) { return this.#admit(this.#ip, ip, now); }
  admitPrincipal(principal: string, now: bigint) { return this.#admit(this.#principal, principal, now); }
}

describe("enrollment intake", () => {
  it("derives ownership only from the consumed server ticket", async () => {
    const store = new Store(record());
    const limiter = new InstrumentedWindowLimiter();
    const result = await consumeEnrollmentAttempt({ wire: responseWire({ claimed_tenant_id: "tenant-b" }), remoteIp }, store, limiter, new TestClock());
    expect(result).toEqual({ ok: false, error: "SCHEMA_INVALID" });
    expect(store.consumed).toBe(true);
  });

  it("returns a pending enrollment bound to the ticket and mutual transcript", async () => {
    const result = await consumeEnrollmentAttempt({ wire: responseWire(), remoteIp }, new Store(record()), new InstrumentedWindowLimiter(), new TestClock());
    expect(result).toMatchObject({ ok: true, pending: { ownership: { tenantId: "tenant-a", humanPrincipalId: "human-a", agentInstanceId: "agent-a", enrollmentScopeCeiling: ["clipboard.read"] }, selectedProtocol: "1.0", ticketDigest: TICKET_DIGEST } });
    if (result.ok) {
      expect(result.pending.shortCode).toBe(pairingShortCode(result.pending.transcript));
      expect(Object.isFrozen(result.pending)).toBe(true);
      expect(Object.isFrozen(result.pending.transcript)).toBe(true);
      expect(Object.isFrozen(result.pending.devicePublicJwk)).toBe(true);
      expect(() => ((result.pending.transcript as unknown as Record<string, unknown>).client_nonce = BRIDGE_NONCE)).toThrow();
    }
  });

  it("calls IP once before CAS and principal once only after a winning CAS", async () => {
    const limiter = new TracingLimiter();
    const store = new Store(record());
    const originalConsume = store.consume.bind(store);
    store.consume = (digest, now) => { limiter.events.push("store"); return originalConsume(digest, now); };
    expect((await consumeEnrollmentAttempt({ wire: responseWire(), remoteIp }, store, limiter, new TestClock())).ok).toBe(true);
    expect(limiter.events).toEqual(["ip", "store", "principal"]);
    expect([limiter.ipCalls, limiter.principalCalls, store.calls]).toEqual([1, 1, 1]);
    expect(limiter.admittedIpTimestamps).toEqual([1_000n]);
    expect(limiter.admittedPrincipalTimestamps).toEqual([1_000n]);
  });

  it("invokes IP admission first while size and parse errors retain precedence", async () => {
    const limiter = new InstrumentedWindowLimiter();
    const clock = new TestClock();
    for (let i = 0; i < 5; i += 1) expect(limiter.admitIp(remoteIp, clock.mono).allowed).toBe(true);
    const store = new Store(record());
    expect(await consumeEnrollmentAttempt({ wire: new Uint8Array(262_145), remoteIp }, store, limiter, clock)).toEqual({ ok: false, error: "MESSAGE_TOO_LARGE" });
    expect(await consumeEnrollmentAttempt({ wire: new TextEncoder().encode("{"), remoteIp }, store, limiter, clock)).toEqual({ ok: false, error: "SCHEMA_INVALID" });
    expect(store.calls).toBe(0);
  });

  it("stops a sixth canonical request after parse without consuming a ticket", async () => {
    const limiter = new InstrumentedWindowLimiter();
    const clock = new TestClock();
    for (let i = 0; i < 5; i += 1) limiter.admitIp(remoteIp, clock.mono);
    const store = new Store(record());
    expect(await consumeEnrollmentAttempt({ wire: responseWire(), remoteIp }, store, limiter, clock)).toEqual({ ok: false, error: "RATE_LIMITED", retryAfterSeconds: "600" });
    expect(store.calls).toBe(0);
  });

  it("IP denial performs no store, principal, full-schema, or signature admission", async () => {
    const limiter = new TracingLimiter({ allowed: false, retryAfterSeconds: "9" } as const);
    const store = new Store(record());
    const structurallyAndCryptographicallyInvalid = rewriteWire(responseWire({ unknown_field: true }), (value) => { value.signature = "A".repeat(86); });
    expect(await consumeEnrollmentAttempt({ wire: structurallyAndCryptographicallyInvalid, remoteIp }, store, limiter, new TestClock())).toEqual({ ok: false, error: "RATE_LIMITED", retryAfterSeconds: "9" });
    expect([limiter.ipCalls, limiter.principalCalls, store.calls]).toEqual([1, 0, 0]);
    expect(limiter.admittedIpTimestamps).toEqual([]);
    const schemaValidBadSignature = rewriteWire(responseWire(), (value) => { value.signature = "A".repeat(86); });
    expect(await consumeEnrollmentAttempt({ wire: schemaValidBadSignature, remoteIp }, new Store(record()), limiter, new TestClock())).toEqual({ ok: false, error: "RATE_LIMITED", retryAfterSeconds: "9" });
    expect(limiter.ipCalls).toBe(2);
  });

  it("denied limiter calls never extend the horizon", () => {
    const limiter = new InstrumentedWindowLimiter();
    for (let i = 0; i < 5; i += 1) limiter.admitIp(remoteIp, 0n);
    expect(limiter.admitIp(remoteIp, 1_000n)).toEqual({ allowed: false, retryAfterSeconds: "599" });
    expect(limiter.admitIp(remoteIp, 1_000n)).toEqual({ allowed: false, retryAfterSeconds: "599" });
    expect(limiter.admitIp(remoteIp, 600_000n)).toEqual({ allowed: true });
  });

  it.each(["unknown", "expired", "already_consumed"] as const)("makes %s tickets externally indistinguishable", async (forced) => {
    expect(await consumeEnrollmentAttempt({ wire: responseWire(), remoteIp }, new Store(record(), forced), new InstrumentedWindowLimiter(), new TestClock())).toEqual({ ok: false, error: "AUTH_FAILED" });
  });

  it("retains validation precedence over principal denial after consuming the ticket", async () => {
    const store = new Store(record());
    const limiter = { admitIp: () => ({ allowed: true } as const), admitPrincipal: () => ({ allowed: false, retryAfterSeconds: "7" } as const) };
    expect(await consumeEnrollmentAttempt({ wire: responseWire({ claimed_tenant_id: "x" }), remoteIp }, store, limiter, new TestClock())).toEqual({ ok: false, error: "SCHEMA_INVALID" });
    expect(store.consumed).toBe(true);
    expect(await consumeEnrollmentAttempt({ wire: responseWire(), remoteIp }, new Store(record()), limiter, new TestClock())).toEqual({ ok: false, error: "RATE_LIMITED", retryAfterSeconds: "7" });
  });

  it.each([
    ["schema", () => responseWire({ unknown_field: true }), "SCHEMA_INVALID"],
    ["signature", () => rewriteWire(responseWire(), (value) => { value.signature = "A".repeat(86); }), "AUTH_FAILED"],
    ["digest", () => signEnvelope("enrollment_response", responsePayload(), devicePublic, devicePrivate, "app-to-bridge", TICKET_DIGEST, { payload_digest: Buffer.alloc(32, 9).toString("base64url") }), "INTEGRITY_FAILED"],
    ["expiry", () => signEnvelope("enrollment_response", responsePayload(), devicePublic, devicePrivate, "app-to-bridge", TICKET_DIGEST, { expires_at: "2026-08-08T00:01:00.000Z" }), "MESSAGE_EXPIRED"],
    ["binding", () => signEnvelope("enrollment_response", responsePayload(), devicePublic, devicePrivate, "app-to-bridge", Buffer.alloc(32, 8).toString("base64url")), "AUTH_BINDING_MISMATCH"],
    ["version", () => responseWire({ supported_versions: ["0.9"] }), "VERSION_UNSUPPORTED"],
  ] as const)("keeps %s validation ahead of retained principal denial", async (_label, wire, error) => {
    const limiter = new TracingLimiter({ allowed: true }, { allowed: false, retryAfterSeconds: "7" });
    const store = new Store(record());
    expect(await consumeEnrollmentAttempt({ wire: wire(), remoteIp }, store, limiter, new TestClock())).toEqual({ ok: false, error });
    expect([store.calls, limiter.principalCalls]).toEqual([1, 1]);
    expect(store.dispositions).toEqual(["consumed"]);
    expect(limiter.admittedPrincipalTimestamps).toEqual([]);
  });

  it("rejects an overflowing enrollment-response offer after the winning CAS", async () => {
    const store = new Store(record());
    expect(await consumeEnrollmentAttempt({ wire: responseWire({ supported_versions: ["18446744073709551616.0"] }), remoteIp }, store, new InstrumentedWindowLimiter(), new TestClock())).toEqual({ ok: false, error: "VERSION_UNSUPPORTED" });
    expect(store.dispositions).toEqual(["consumed"]);
  });

  it("rejects every registered response tuple swap through raw admission before signature acceptance", async () => {
    const base = JSON.parse(new TextDecoder().decode(responseWire())) as Record<string, unknown>;
    const baseHeader = base.header as Record<string, unknown>;
    const swaps = loadMessageRegistry().messages.flatMap((candidate) => {
      const mutations: Record<string, unknown>[] = [];
      if (candidate.message_type !== baseHeader.message_type) mutations.push({ message_type: candidate.message_type });
      if (candidate.schema_id !== baseHeader.message_schema) mutations.push({ message_schema: candidate.schema_id });
      if (candidate.direction !== baseHeader.direction) mutations.push({ direction: candidate.direction });
      return mutations;
    });
    for (const swap of swaps) {
      const store = new Store(record());
      const wire = canonicalBytes({ ...base, header: { ...baseHeader, ...swap }, signature: "A".repeat(86) });
      expect(await consumeEnrollmentAttempt({ wire, remoteIp }, store, new InstrumentedWindowLimiter(), new TestClock())).toEqual({ ok: false, error: "SCHEMA_INVALID" });
      expect(store.dispositions).toEqual(["consumed"]);
    }
  });

  const contenders = {
    "contender-a": { wire: responseWire({ client_nonce: CLIENT_NONCE }), nonce: CLIENT_NONCE },
    "contender-b": { wire: responseWire({ client_nonce: Buffer.alloc(32, 7).toString("base64url") }), nonce: Buffer.alloc(32, 7).toString("base64url") },
  } as const;
  it.each([["contender-a", "contender-b"], ["contender-b", "contender-a"]] as const)("linearizes shared-store contenders in order %s then %s", async (firstName, secondName) => {
    const store = new Store(record());
    const first = contenders[firstName];
    const second = contenders[secondName];
    const results = await Promise.all([
      consumeEnrollmentAttempt({ wire: first.wire, remoteIp }, store, new InstrumentedWindowLimiter(), new TestClock()),
      consumeEnrollmentAttempt({ wire: second.wire, remoteIp }, store, new InstrumentedWindowLimiter(), new TestClock()),
    ]);
    expect(results.filter((result) => result.ok)).toHaveLength(1);
    expect(results.filter((result) => !result.ok)).toEqual([{ ok: false, error: "AUTH_FAILED" }]);
    expect(store.dispositions).toEqual(["consumed", "already_consumed"]);
    expect(results[0]).toMatchObject({ ok: true, pending: { transcript: { client_nonce: first.nonce } } });
  });

  it("overlaps two signed contenders at an async store barrier and exposes one atomic CAS winner", async () => {
    const store = new BarrierStore(record());
    const attempts = [
      consumeEnrollmentAttempt({ wire: contenders["contender-a"].wire, remoteIp }, store, new InstrumentedWindowLimiter(), new TestClock()),
      consumeEnrollmentAttempt({ wire: contenders["contender-b"].wire, remoteIp }, store, new InstrumentedWindowLimiter(), new TestClock()),
    ];
    await store.waitForBothArrivals();
    expect(store.arrivals).toBe(2);
    store.release();
    const results = await Promise.all(attempts);
    expect(store.dispositions.sort()).toEqual(["already_consumed", "consumed"]);
    expect(results.filter((result) => result.ok)).toHaveLength(1);
    expect(results.filter((result) => !result.ok)).toEqual([{ ok: false, error: "AUTH_FAILED" }]);
  });

  it("enforces the exact five-minute ticket boundary atomically", async () => {
    expect((await consumeEnrollmentAttempt({ wire: responseWire(), remoteIp }, new Store(record()), new InstrumentedWindowLimiter(), new TestClock(new Date("2026-08-08T00:04:59.999Z")))).ok).toBe(true);
    const atBoundary = new Store(record());
    expect(await consumeEnrollmentAttempt({ wire: responseWire(), remoteIp }, atBoundary, new InstrumentedWindowLimiter(), new TestClock(new Date("2026-08-08T00:05:00.000Z")))).toEqual({ ok: false, error: "AUTH_FAILED" });
    expect(atBoundary.dispositions).toEqual(["expired"]);
  });

  it("uses wall time only for expiry and monotonic time only for limiter windows", async () => {
    const store = new Store(record());
    expect((await consumeEnrollmentAttempt({ wire: responseWire(), remoteIp }, store, new InstrumentedWindowLimiter(), new TestClock(new Date("2026-08-08T00:04:59.999Z"), 99_999_999n))).ok).toBe(true);
    const limiter = new InstrumentedWindowLimiter();
    for (let index = 0; index < 5; index += 1) limiter.admitIp(remoteIp, 0n);
    const earlyWall = new Store(record());
    const lateWall = new Store(record());
    expect(await consumeEnrollmentAttempt({ wire: responseWire(), remoteIp }, earlyWall, limiter, new TestClock(new Date("2026-08-08T00:00:00.000Z"), 1_000n))).toMatchObject({ error: "RATE_LIMITED", retryAfterSeconds: "599" });
    expect(await consumeEnrollmentAttempt({ wire: responseWire(), remoteIp }, lateWall, limiter, new TestClock(new Date("2030-01-01T00:00:00.000Z"), 1_000n))).toMatchObject({ error: "RATE_LIMITED", retryAfterSeconds: "599" });
    expect([earlyWall.calls, lateWall.calls]).toEqual([0, 0]);
  });

  it("uses a bounded test limiter for rotating unauthenticated IPs", () => {
    const limiter = new InstrumentedWindowLimiter(3);
    for (let index = 0; index < 3; index += 1) expect(limiter.admitIp(`2001:db8::${index}` as CanonicalIpAddress, 0n)).toEqual({ allowed: true });
    expect(limiter.admitIp("2001:db8::3" as CanonicalIpAddress, 0n)).toEqual({ allowed: false, retryAfterSeconds: "600" });
    expect(limiter.bucketCount).toBe(3);
  });
});

describe("pairing transcript and Bridge enrollment admission", () => {
  const transcript: PairingTranscript = { ticket_digest: TICKET_DIGEST, bridge_fingerprint: BRIDGE_FP, challenge: CHALLENGE, client_nonce: CLIENT_NONCE, bridge_nonce: BRIDGE_NONCE, device_jwk_thumbprint: DEVICE_FP, selected_protocol: "1.0" };
  const verifier: Verifier = { verify: async (role: SignerRole, keyId: string, preimage: Uint8Array, signature: string) => role === "bridge-command" && keyId === bridgePublic.kid && verifyEs256(bridgePublic, preimage, signature) };

  it("derives the same code only from the complete mutual transcript", () => {
    expect(pairingShortCode(transcript)).toMatch(/^[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}$/);
    expect(pairingShortCode({ ...transcript })).toBe(pairingShortCode(transcript));
    expect(pairingShortCode({ ...transcript, bridge_nonce: Buffer.alloc(32, 9).toString("base64url") })).not.toBe(pairingShortCode(transcript));
    for (const field of Object.keys(transcript) as (keyof PairingTranscript)[]) {
      expect(pairingShortCode({ ...transcript, [field]: `${transcript[field]}x` })).not.toBe(pairingShortCode(transcript));
    }
  });

  it("accepts a challenge only through the QR-pinned Bridge fingerprint", async () => {
    const payload = { challenge: CHALLENGE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, bridge_command_public_jwk: bridgePublic, supported_versions: ["1.0"] };
    const verified = await verifyEnrollmentBridgeMessage(signEnvelope("enrollment_challenge", payload, bridgePublic, bridgePrivate, "bridge-to-app"), { phase: "challenge", expectedTicketDigest: TICKET_DIGEST, expectedChallenge: CHALLENGE, qrPinnedBridgeFingerprint: BRIDGE_FP, clock: new TestClock() });
    expect(verified.type).toBe("enrollment_challenge");
    if (verified.type !== "enrollment_challenge") throw new Error("expected challenge");
    expect(Object.isFrozen(verified)).toBe(true);
    expect(Object.isFrozen(verified.payload)).toBe(true);
    expect(Object.isFrozen(verified.payload.bridge_command_public_jwk)).toBe(true);
    expect(() => (verified.payload.supported_versions as string[]).push("9.9")).toThrow();
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_challenge", payload, bridgePublic, bridgePrivate, "bridge-to-app"), { phase: "challenge", expectedTicketDigest: TICKET_DIGEST, expectedChallenge: CHALLENGE, qrPinnedBridgeFingerprint: DEVICE_FP, clock: new TestClock() })).rejects.toThrowError("AUTH_BINDING_MISMATCH");
  });

  it("rejects signed overflow versions in QR-pinned challenge admission", async () => {
    const payload = { challenge: CHALLENGE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, bridge_command_public_jwk: bridgePublic, supported_versions: ["18446744073709551616.0"] };
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_challenge", payload, bridgePublic, bridgePrivate, "bridge-to-app"), { phase: "challenge", expectedTicketDigest: TICKET_DIGEST, expectedChallenge: CHALLENGE, qrPinnedBridgeFingerprint: BRIDGE_FP, clock: new TestClock() })).rejects.toThrowError(/^VERSION_UNSUPPORTED$/);
  });

  it("rejects challenge mismatch, a self-supplied key, and key-id substitution", async () => {
    const base = { challenge: CHALLENGE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, bridge_command_public_jwk: bridgePublic, supported_versions: ["1.0"] };
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_challenge", base, bridgePublic, bridgePrivate, "bridge-to-app"), { phase: "challenge", expectedTicketDigest: TICKET_DIGEST, expectedChallenge: CLIENT_NONCE, qrPinnedBridgeFingerprint: BRIDGE_FP, clock: new TestClock() })).rejects.toThrowError("AUTH_BINDING_MISMATCH");
    const self = { ...base, bridge_fingerprint: DEVICE_FP, bridge_command_public_jwk: devicePublic };
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_challenge", self, devicePublic, devicePrivate, "bridge-to-app"), { phase: "challenge", expectedTicketDigest: TICKET_DIGEST, expectedChallenge: CHALLENGE, qrPinnedBridgeFingerprint: BRIDGE_FP, clock: new TestClock() })).rejects.toThrowError("AUTH_BINDING_MISMATCH");
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_challenge", base, bridgePublic, bridgePrivate, "bridge-to-app", TICKET_DIGEST, { key_id: devicePublic.kid }), { phase: "challenge", expectedTicketDigest: TICKET_DIGEST, expectedChallenge: CHALLENGE, qrPinnedBridgeFingerprint: BRIDGE_FP, clock: new TestClock() })).rejects.toThrowError("AUTH_FAILED");
  });

  it("verifies challenge authenticity before comparing its signed fingerprint field", async () => {
    const base = { challenge: CHALLENGE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, bridge_command_public_jwk: bridgePublic, supported_versions: ["1.0"] };
    const tampered = rewriteWire(signEnvelope("enrollment_challenge", base, bridgePublic, bridgePrivate, "bridge-to-app"), (value) => {
      (value.payload as Record<string, unknown>).bridge_fingerprint = DEVICE_FP;
    });
    await expect(verifyEnrollmentBridgeMessage(tampered, { phase: "challenge", expectedTicketDigest: TICKET_DIGEST, expectedChallenge: CHALLENGE, qrPinnedBridgeFingerprint: BRIDGE_FP, clock: new TestClock() })).rejects.toThrowError(/^AUTH_FAILED$/);
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_challenge", { ...base, bridge_fingerprint: DEVICE_FP }, bridgePublic, bridgePrivate, "bridge-to-app"), { phase: "challenge", expectedTicketDigest: TICKET_DIGEST, expectedChallenge: CHALLENGE, qrPinnedBridgeFingerprint: BRIDGE_FP, clock: new TestClock() })).rejects.toThrowError(/^AUTH_BINDING_MISMATCH$/);
  });

  it("maps detailed closed-schema failures to the coarse public code", async () => {
    const payload = { challenge: CHALLENGE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, bridge_command_public_jwk: bridgePublic, supported_versions: ["1.0"], injected: true };
    const admission = verifyEnrollmentBridgeMessage(signEnvelope("enrollment_challenge", payload, bridgePublic, bridgePrivate, "bridge-to-app"), { phase: "challenge", expectedTicketDigest: TICKET_DIGEST, expectedChallenge: CHALLENGE, qrPinnedBridgeFingerprint: BRIDGE_FP, clock: new TestClock() });
    await expect(admission).rejects.toThrowError(/^SCHEMA_INVALID$/);
  });

  it("pins complete messages to both the expected key and ticket transcript", async () => {
    const payload = { device_id: "device-a", pairing_generation: "3", tenant_id: "tenant-a", human_principal_id: "human-a", agent_instance_id: "agent-a", enrollment_scope_ceiling: ["clipboard.read"], selected_protocol: "1.0", client_nonce: CLIENT_NONCE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, device_jwk_thumbprint: DEVICE_FP };
    const context = { phase: "pinned" as const, expectedTicketDigest: TICKET_DIGEST, pendingTranscript: transcript, verifier, expectedKeyId: bridgePublic.kid, clock: new TestClock() };
    const verified = await verifyEnrollmentBridgeMessage(signEnvelope("enrollment_complete", payload, bridgePublic, bridgePrivate, "bridge-to-app"), context);
    expect(verified.type).toBe("enrollment_complete");
    expect(Object.isFrozen(verified)).toBe(true);
    expect(Object.isFrozen(verified.payload)).toBe(true);
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_complete", payload, bridgePublic, bridgePrivate, "bridge-to-app", Buffer.alloc(32, 8).toString("base64url")), context)).rejects.toThrowError("AUTH_BINDING_MISMATCH");
  });

  it.each([
    ["client_nonce", Buffer.alloc(32, 7).toString("base64url")],
    ["bridge_nonce", Buffer.alloc(32, 8).toString("base64url")],
    ["bridge_fingerprint", Buffer.alloc(32, 9).toString("base64url")],
    ["device_jwk_thumbprint", Buffer.alloc(32, 10).toString("base64url")],
    ["selected_protocol", "1.1"],
  ] as const)("rejects a signed complete whose %s differs from the pending transcript", async (field, value) => {
    const payload = {
      device_id: "device-a", pairing_generation: "3", tenant_id: "tenant-a",
      human_principal_id: "human-a", agent_instance_id: "agent-a",
      enrollment_scope_ceiling: ["clipboard.read"], selected_protocol: "1.0",
      client_nonce: CLIENT_NONCE, bridge_nonce: BRIDGE_NONCE,
      bridge_fingerprint: BRIDGE_FP, device_jwk_thumbprint: DEVICE_FP,
      [field]: value,
    };
    const context = { phase: "pinned" as const, expectedTicketDigest: TICKET_DIGEST, pendingTranscript: transcript, verifier, expectedKeyId: bridgePublic.kid, clock: new TestClock() };
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_complete", payload, bridgePublic, bridgePrivate, "bridge-to-app"), context)).rejects.toThrowError(/^AUTH_BINDING_MISMATCH$/);
  });

  it("invokes the pinned verifier exactly once only after closed schema admission", async () => {
    let calls = 0;
    const countingVerifier: Verifier = { verify: async (role, keyId, preimage, signature) => {
      calls += 1;
      return role === "bridge-command" && keyId === bridgePublic.kid && verifyEs256(bridgePublic, preimage, signature);
    } };
    const payload = { device_id: "device-a", pairing_generation: "3", tenant_id: "tenant-a", human_principal_id: "human-a", agent_instance_id: "agent-a", enrollment_scope_ceiling: ["clipboard.read"], selected_protocol: "1.0", client_nonce: CLIENT_NONCE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, device_jwk_thumbprint: DEVICE_FP };
    const context = { phase: "pinned" as const, expectedTicketDigest: TICKET_DIGEST, pendingTranscript: transcript, verifier: countingVerifier, expectedKeyId: bridgePublic.kid, clock: new TestClock() };
    await verifyEnrollmentBridgeMessage(signEnvelope("enrollment_complete", payload, bridgePublic, bridgePrivate, "bridge-to-app"), context);
    expect(calls).toBe(1);
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_complete", { ...payload, unknown_field: true }, bridgePublic, bridgePrivate, "bridge-to-app"), context)).rejects.toThrowError("SCHEMA_INVALID");
    expect(calls).toBe(1);
  });

  it("rejects a validly signed but unsorted scope ceiling", async () => {
    const payload = { device_id: "device-a", pairing_generation: "3", tenant_id: "tenant-a", human_principal_id: "human-a", agent_instance_id: "agent-a", enrollment_scope_ceiling: ["contacts.read", "clipboard.read"], selected_protocol: "1.0", client_nonce: CLIENT_NONCE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, device_jwk_thumbprint: DEVICE_FP };
    const context = { phase: "pinned" as const, expectedTicketDigest: TICKET_DIGEST, pendingTranscript: transcript, verifier, expectedKeyId: bridgePublic.kid, clock: new TestClock() };
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_complete", payload, bridgePublic, bridgePrivate, "bridge-to-app"), context)).rejects.toThrowError(/^SCHEMA_INVALID$/);
  });

  it("rejects signed overflow selected_protocol before returning a complete brand", async () => {
    const overflow = "18446744073709551616.0";
    const overflowTranscript = { ...transcript, selected_protocol: overflow };
    const payload = { device_id: "device-a", pairing_generation: "3", tenant_id: "tenant-a", human_principal_id: "human-a", agent_instance_id: "agent-a", enrollment_scope_ceiling: ["clipboard.read"], selected_protocol: overflow, client_nonce: CLIENT_NONCE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, device_jwk_thumbprint: DEVICE_FP };
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_complete", payload, bridgePublic, bridgePrivate, "bridge-to-app"), { phase: "pinned", expectedTicketDigest: TICKET_DIGEST, pendingTranscript: overflowTranscript, verifier, expectedKeyId: bridgePublic.kid, clock: new TestClock() })).rejects.toThrowError(/^VERSION_UNSUPPORTED$/);
  });

  it("prevents same-key challenge, complete, and error reuse across tickets", async () => {
    const otherTicket = Buffer.alloc(32, 8).toString("base64url");
    const challengePayload = { challenge: CHALLENGE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, bridge_command_public_jwk: bridgePublic, supported_versions: ["1.0"] };
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_challenge", challengePayload, bridgePublic, bridgePrivate, "bridge-to-app", otherTicket), { phase: "challenge", expectedTicketDigest: TICKET_DIGEST, expectedChallenge: CHALLENGE, qrPinnedBridgeFingerprint: BRIDGE_FP, clock: new TestClock() })).rejects.toThrowError("AUTH_BINDING_MISMATCH");
    const completePayload = { device_id: "device-a", pairing_generation: "3", tenant_id: "tenant-a", human_principal_id: "human-a", agent_instance_id: "agent-a", enrollment_scope_ceiling: ["clipboard.read"], selected_protocol: "1.0", client_nonce: CLIENT_NONCE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, device_jwk_thumbprint: DEVICE_FP };
    const pinned = { phase: "pinned" as const, expectedTicketDigest: TICKET_DIGEST, pendingTranscript: transcript, verifier, expectedKeyId: bridgePublic.kid, clock: new TestClock() };
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_complete", completePayload, bridgePublic, bridgePrivate, "bridge-to-app", otherTicket), pinned)).rejects.toThrowError("AUTH_BINDING_MISMATCH");
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_error", { code: "AUTH_FAILED" }, bridgePublic, bridgePrivate, "bridge-to-app", otherTicket), pinned)).rejects.toThrowError("AUTH_BINDING_MISMATCH");
  });

  it("rejects a pinned context whose expected ticket disagrees with its transcript", async () => {
    const inconsistent = { ...transcript, ticket_digest: Buffer.alloc(32, 8).toString("base64url") };
    const context = { phase: "pinned" as const, expectedTicketDigest: TICKET_DIGEST, pendingTranscript: inconsistent, verifier, expectedKeyId: bridgePublic.kid, clock: new TestClock() };
    const completePayload = { device_id: "device-a", pairing_generation: "3", tenant_id: "tenant-a", human_principal_id: "human-a", agent_instance_id: "agent-a", enrollment_scope_ceiling: ["clipboard.read"], selected_protocol: "1.0", client_nonce: CLIENT_NONCE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, device_jwk_thumbprint: DEVICE_FP };
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_complete", completePayload, bridgePublic, bridgePrivate, "bridge-to-app"), context)).rejects.toThrowError("AUTH_BINDING_MISMATCH");
    await expect(verifyEnrollmentBridgeMessage(signEnvelope("enrollment_error", { code: "AUTH_FAILED" }, bridgePublic, bridgePrivate, "bridge-to-app"), context)).rejects.toThrowError("AUTH_BINDING_MISMATCH");
  });

  it("deep-freezes pinned enrollment errors and rejects enrollment/control union confusion", async () => {
    const pinned = { phase: "pinned" as const, expectedTicketDigest: TICKET_DIGEST, pendingTranscript: transcript, verifier, expectedKeyId: bridgePublic.kid, clock: new TestClock() };
    const error = await verifyEnrollmentBridgeMessage(signEnvelope("enrollment_error", { code: "AUTH_FAILED" }, bridgePublic, bridgePrivate, "bridge-to-app"), pinned);
    expect(error.type).toBe("enrollment_error");
    expect(Object.isFrozen(error)).toBe(true);
    expect(Object.isFrozen(error.payload)).toBe(true);
    await expect(verifyEnrollmentBridgeMessage(responseWire(), { phase: "challenge", expectedTicketDigest: TICKET_DIGEST, expectedChallenge: CHALLENGE, qrPinnedBridgeFingerprint: BRIDGE_FP, clock: new TestClock() })).rejects.toThrowError("SCHEMA_INVALID");
    await expect(verifyConnectMessage(signEnvelope("enrollment_challenge", { challenge: CHALLENGE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, bridge_command_public_jwk: bridgePublic, supported_versions: ["1.0"] }, bridgePublic, bridgePrivate, "bridge-to-app"), "connect_welcome", { verifier, expectedSignerRole: "bridge-command", expectedKeyId: bridgePublic.kid, expectedDeviceId: "device-a", expectedPairingGeneration: "3", clock: new TestClock() })).rejects.toThrowError("SCHEMA_INVALID");
  });
});

const compileTimeEnrollmentOpacityEvidence = (): void => {
  const header = {
    message_type: "enrollment_challenge", message_schema: "urn:open-android-intelligence:protocol:v1:message:enrollment_challenge",
    key_id: "key", direction: "bridge-to-app", issued_at: "2026-08-08T00:00:00.000Z",
    expires_at: "2026-08-08T00:05:00.000Z", payload_digest: CLIENT_NONCE,
  } as VerifiedEnrollmentChallenge["header"];
  const structuralChallenge = { type: "enrollment_challenge" as const, header, payload: { challenge: CHALLENGE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, bridge_command_public_jwk: bridgePublic, supported_versions: ["1.0"] } };
  const structuralComplete = { type: "enrollment_complete" as const, header, payload: { device_id: "device", pairing_generation: "1", tenant_id: "tenant", human_principal_id: "human", agent_instance_id: "agent", enrollment_scope_ceiling: [], selected_protocol: "1.0", client_nonce: CLIENT_NONCE, bridge_nonce: BRIDGE_NONCE, bridge_fingerprint: BRIDGE_FP, device_jwk_thumbprint: DEVICE_FP } };
  const structuralError = { type: "enrollment_error" as const, header, payload: { code: "AUTH_FAILED" as const } };
  // @ts-expect-error Enrollment challenge brands have no public constructor.
  const challenge: VerifiedEnrollmentChallenge = structuralChallenge;
  // @ts-expect-error Enrollment complete brands have no public constructor.
  const complete: VerifiedEnrollmentComplete = structuralComplete;
  // @ts-expect-error Enrollment error brands have no public constructor.
  const error: VerifiedEnrollmentError = structuralError;
  // @ts-expect-error The exact public JWK surface forbids private key material.
  const jwk: P256PublicJwk = { alg: "ES256", crv: "P-256", kid: "key", kty: "EC", use: "sig", x: CLIENT_NONCE, y: CLIENT_NONCE, d: CLIENT_NONCE };
  void [challenge, complete, error, jwk];
};
void compileTimeEnrollmentOpacityEvidence;

const compileTimeEnrollmentPayloadEvidence = (
  complete: VerifiedEnrollmentComplete,
  error: VerifiedEnrollmentError,
): void => {
  const selected: string = complete.payload.selected_protocol;
  const scopes: readonly string[] = complete.payload.enrollment_scope_ceiling;
  const code: string = error.payload.code;
  if (error.payload.code === "RATE_LIMITED") {
    const retry: string = error.payload.retry_after_seconds;
    void retry;
  }
  void [selected, scopes, code];
};
void compileTimeEnrollmentPayloadEvidence;
