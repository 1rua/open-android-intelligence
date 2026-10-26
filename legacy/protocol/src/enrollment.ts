/// <reference types="node" />

import { timingSafeEqual } from "node:crypto";
import { verifyEs256 } from "./crypto.js";
import { canonicalBytes, parseCanonicalJson, sha256B64Url, signingPreimage } from "./encoding.js";
import { loadMessageRegistry, type P256PublicJwk } from "./message-registry.js";
import { pairingShortCode, type PairingTranscript } from "./pairing-transcript.js";
import type { Clock } from "./ports.js";
import { parseSignatureDomain } from "./profile.js";
import { validateSchema } from "./schema-validator.js";
import { compareProtocolVersions, loadVersionRegistry, parseProtocolVersion } from "./version-negotiation.js";

declare const canonicalIp: unique symbol;
export type CanonicalIpAddress = string & { readonly [canonicalIp]: true };

export type LimitDecision =
  | { readonly allowed: true }
  | { readonly allowed: false; readonly retryAfterSeconds: string };

export interface TrustedEnrollmentIntake {
  readonly wire: Uint8Array;
  readonly remoteIp: CanonicalIpAddress;
}

export interface EnrollmentTicketRecord {
  readonly ticketDigest: string;
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly agentInstanceId: string;
  readonly enrollmentScopeCeiling: readonly string[];
  readonly challenge: string;
  readonly bridgeId: string;
  readonly bridgeFingerprint: string;
  readonly bridgeCommandPublicJwk: P256PublicJwk;
  readonly bridgeNonce: string;
  readonly expiresAt: string;
}

export type EnrollmentTicketConsumeResult =
  | { readonly kind: "consumed"; readonly record: EnrollmentTicketRecord }
  | { readonly kind: "unknown" | "expired" | "already_consumed" };

export interface EnrollmentTicketStore {
  consume(ticketDigest: string, wallNow: Date): Promise<EnrollmentTicketConsumeResult>;
}

export interface EnrollmentAttemptLimiter {
  admitIp(remoteIp: CanonicalIpAddress, monotonicNowMs: bigint): LimitDecision;
  admitPrincipal(humanPrincipalId: string, monotonicNowMs: bigint): LimitDecision;
}

export interface PendingEnrollment {
  readonly ticketDigest: string;
  readonly ownership: Readonly<{
    tenantId: string;
    humanPrincipalId: string;
    agentInstanceId: string;
    enrollmentScopeCeiling: readonly string[];
  }>;
  readonly devicePublicJwk: P256PublicJwk;
  readonly selectedProtocol: string;
  readonly transcript: PairingTranscript;
  readonly shortCode: string;
}

export type EnrollmentAdmissionError =
  | "MESSAGE_TOO_LARGE" | "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED"
  | "MESSAGE_EXPIRED" | "AUTH_BINDING_MISMATCH" | "RATE_LIMITED" | "VERSION_UNSUPPORTED";
export type NonRateEnrollmentAdmissionError = Exclude<EnrollmentAdmissionError, "RATE_LIMITED">;
export type EnrollmentAttemptResult =
  | { readonly ok: true; readonly pending: PendingEnrollment }
  | { readonly ok: false; readonly error: "RATE_LIMITED"; readonly retryAfterSeconds: string }
  | { readonly ok: false; readonly error: NonRateEnrollmentAdmissionError; readonly retryAfterSeconds?: never };

interface EnrollmentResponsePayload {
  readonly ticket: string;
  readonly challenge_response: string;
  readonly device_public_jwk: P256PublicJwk;
  readonly client_nonce: string;
  readonly supported_versions: readonly string[];
}

interface EnrollmentResponseWire {
  readonly header: {
    readonly message_type: string;
    readonly message_schema: string;
    readonly key_id: string;
    readonly direction: string;
    readonly issued_at: string;
    readonly expires_at: string;
    readonly payload_digest: string;
    readonly enrollment_ticket_digest: string;
  };
  readonly payload: EnrollmentResponsePayload;
  readonly signature: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const equalText = (left: string, right: string): boolean => {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.byteLength === b.byteLength && timingSafeEqual(a, b);
};

const deepFreeze = <T>(value: T): T => {
  if (typeof value === "object" && value !== null && !Object.isFrozen(value)) {
    for (const member of Object.values(value)) deepFreeze(member);
    Object.freeze(value);
  }
  return value;
};

const externalFailure = (error: NonRateEnrollmentAdmissionError): EnrollmentAttemptResult => ({ ok: false, error });

const parseTicketDigest = (parsed: unknown): string | undefined => {
  if (!isRecord(parsed) || !isRecord(parsed.payload) || typeof parsed.payload.ticket !== "string") return undefined;
  const ticket = parsed.payload.ticket;
  if (!/^[A-Za-z0-9_-]{43}$/.test(ticket)) return undefined;
  const bytes = Buffer.from(ticket, "base64url");
  if (bytes.byteLength !== 32 || bytes.toString("base64url") !== ticket) return undefined;
  return sha256B64Url(bytes);
};

const mapValidationError = (error: unknown): NonRateEnrollmentAdmissionError => {
  if (!(error instanceof Error)) return "SCHEMA_INVALID";
  const allowed: readonly NonRateEnrollmentAdmissionError[] = ["MESSAGE_TOO_LARGE", "SCHEMA_INVALID", "AUTH_FAILED", "INTEGRITY_FAILED", "MESSAGE_EXPIRED", "AUTH_BINDING_MISMATCH", "VERSION_UNSUPPORTED"];
  return allowed.includes(error.message as NonRateEnrollmentAdmissionError)
    ? error.message as NonRateEnrollmentAdmissionError
    : "SCHEMA_INVALID";
};

const selectEnrollmentVersion = (offers: readonly string[]): string => {
  const offered = new Set(offers.map((offer) => parseProtocolVersion(offer).canonical));
  const selected = loadVersionRegistry().versions
    .filter((entry) => entry.negotiable && offered.has(entry.version))
    .map((entry) => parseProtocolVersion(entry.version))
    .sort((left, right) => compareProtocolVersions(right, left))[0];
  if (!selected) throw new Error("VERSION_UNSUPPORTED");
  return selected.canonical;
};

export async function consumeEnrollmentAttempt(
  input: TrustedEnrollmentIntake,
  store: EnrollmentTicketStore,
  limiter: EnrollmentAttemptLimiter,
  clock: Clock,
): Promise<EnrollmentAttemptResult> {
  const wallNow = clock.wallNow();
  const monotonicNowMs = clock.monotonicNowMs();
  const ipDecision = limiter.admitIp(input.remoteIp, monotonicNowMs);

  let parsed: unknown;
  try {
    parsed = parseCanonicalJson(input.wire);
  } catch (error) {
    return externalFailure(error instanceof Error && error.message === "MESSAGE_TOO_LARGE" ? "MESSAGE_TOO_LARGE" : "SCHEMA_INVALID");
  }
  if (!ipDecision.allowed) return { ok: false, error: "RATE_LIMITED", retryAfterSeconds: ipDecision.retryAfterSeconds };

  const ticketDigest = parseTicketDigest(parsed);
  if (!ticketDigest) return externalFailure("AUTH_FAILED");
  const consumed = await store.consume(ticketDigest, wallNow);
  if (consumed.kind !== "consumed") return externalFailure("AUTH_FAILED");
  const principalDecision = limiter.admitPrincipal(consumed.record.humanPrincipalId, monotonicNowMs);

  try {
    validateSchema("urn:open-android-intelligence:protocol:v1:envelope:enrollment_app_to_bridge", parsed);
    const wire = parsed as EnrollmentResponseWire;
    const entry = loadMessageRegistry().messages.find((candidate) => candidate.message_type === "enrollment_response");
    if (!entry
      || wire.header.message_type !== entry.message_type
      || wire.header.message_schema !== entry.schema_id
      || wire.header.direction !== entry.direction) throw new Error("SCHEMA_INVALID");
    const unsigned = { header: wire.header, payload: wire.payload };
    if (wire.header.key_id !== wire.payload.device_public_jwk.kid
      || !verifyEs256(wire.payload.device_public_jwk, signingPreimage(parseSignatureDomain(entry.signature_domain), unsigned), wire.signature)) {
      throw new Error("AUTH_FAILED");
    }
    const digest = sha256B64Url(canonicalBytes(wire.payload));
    if (!equalText(digest, wire.header.payload_digest)) throw new Error("INTEGRITY_FAILED");
    if (wallNow.getTime() >= Date.parse(wire.header.expires_at)) throw new Error("MESSAGE_EXPIRED");
    if (!equalText(wire.header.enrollment_ticket_digest, ticketDigest)
      || !equalText(ticketDigest, consumed.record.ticketDigest)
      || !equalText(wire.payload.challenge_response, consumed.record.challenge)) {
      throw new Error("AUTH_BINDING_MISMATCH");
    }
    const bridgeThumbprint = sha256B64Url(canonicalBytes({
      crv: consumed.record.bridgeCommandPublicJwk.crv,
      kty: consumed.record.bridgeCommandPublicJwk.kty,
      x: consumed.record.bridgeCommandPublicJwk.x,
      y: consumed.record.bridgeCommandPublicJwk.y,
    }));
    if (!equalText(bridgeThumbprint, consumed.record.bridgeFingerprint)) throw new Error("AUTH_BINDING_MISMATCH");
    const selectedProtocol = selectEnrollmentVersion(wire.payload.supported_versions);
    const deviceJwkThumbprint = sha256B64Url(canonicalBytes({
      crv: wire.payload.device_public_jwk.crv,
      kty: wire.payload.device_public_jwk.kty,
      x: wire.payload.device_public_jwk.x,
      y: wire.payload.device_public_jwk.y,
    }));
    const transcript: PairingTranscript = {
      ticket_digest: ticketDigest,
      bridge_fingerprint: consumed.record.bridgeFingerprint,
      challenge: consumed.record.challenge,
      client_nonce: wire.payload.client_nonce,
      bridge_nonce: consumed.record.bridgeNonce,
      device_jwk_thumbprint: deviceJwkThumbprint,
      selected_protocol: selectedProtocol,
    };
    if (!principalDecision.allowed) return { ok: false, error: "RATE_LIMITED", retryAfterSeconds: principalDecision.retryAfterSeconds };
    const pending: PendingEnrollment = {
      ticketDigest,
      ownership: {
        tenantId: consumed.record.tenantId,
        humanPrincipalId: consumed.record.humanPrincipalId,
        agentInstanceId: consumed.record.agentInstanceId,
        enrollmentScopeCeiling: Object.freeze([...consumed.record.enrollmentScopeCeiling]),
      },
      devicePublicJwk: wire.payload.device_public_jwk,
      selectedProtocol,
      transcript,
      shortCode: pairingShortCode(transcript),
    };
    return { ok: true, pending: deepFreeze(pending) };
  } catch (error) {
    return externalFailure(mapValidationError(error));
  }
}
