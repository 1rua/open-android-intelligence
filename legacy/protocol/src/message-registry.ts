/// <reference types="node" />

import { timingSafeEqual } from "node:crypto";
import messagesFixture from "../registries/v1/messages.json" with { type: "json" };
import { isValidP256PublicJwk, verifyEs256 } from "./crypto.js";
import { canonicalBytes, parseCanonicalJson, sha256B64Url, signingPreimage } from "./encoding.js";
import type { PairingTranscript } from "./pairing-transcript.js";
import type { Clock, SignerRole, Verifier } from "./ports.js";
import { parseSignatureDomain } from "./profile.js";
import { validateSchema } from "./schema-validator.js";
import { parseProtocolVersion } from "./version-negotiation.js";

export interface P256PublicJwk {
  readonly alg: "ES256";
  readonly crv: "P-256";
  readonly kid: string;
  readonly kty: "EC";
  readonly use: "sig";
  readonly x: string;
  readonly y: string;
}

export interface MessageRegistryEntry {
  readonly message_type: string;
  readonly direction: "app-to-bridge" | "bridge-to-app" | "adapter-to-bridge" | "bridge-to-adapter";
  readonly signature_domain: string;
  readonly schema_id: string;
}

export interface MessageRegistry {
  readonly $schema: "urn:open-android-intelligence:protocol:v1:messages-registry";
  readonly registry_id: "urn:open-android-intelligence:protocol:v1:registry:messages";
  readonly protocol_version: "1.0";
  readonly messages: readonly MessageRegistryEntry[];
}

declare const lockedMessageRegistryBrand: unique symbol;
export type LockedMessageRegistry = Readonly<MessageRegistry> & {
  readonly [lockedMessageRegistryBrand]: true;
};

const deepFreeze = <T>(value: T): T => {
  if (typeof value === "object" && value !== null && !Object.isFrozen(value)) {
    for (const member of Object.values(value)) deepFreeze(member);
    Object.freeze(value);
  }
  return value;
};

validateSchema("urn:open-android-intelligence:protocol:v1:messages-registry", messagesFixture);
const lockedRegistry = deepFreeze(messagesFixture) as unknown as LockedMessageRegistry;

export function loadMessageRegistry(): LockedMessageRegistry {
  return lockedRegistry;
}

interface SignedHeader {
  readonly message_type: string;
  readonly message_schema: string;
  readonly key_id: string;
  readonly direction: string;
  readonly issued_at: string;
  readonly expires_at: string;
  readonly payload_digest: string;
  readonly enrollment_ticket_digest?: string;
  readonly device_id?: string;
  readonly pairing_generation?: string;
}

interface SignedWire {
  readonly header: SignedHeader;
  readonly payload: Record<string, unknown>;
  readonly signature: string;
}

export interface VerifiedEnrollmentChallenge {
  readonly type: "enrollment_challenge";
  readonly header: SignedHeader;
  readonly payload: Readonly<{
    challenge: string;
    bridge_nonce: string;
    bridge_fingerprint: string;
    bridge_command_public_jwk: P256PublicJwk;
    supported_versions: readonly string[];
  }>;
  readonly [verifiedEnrollmentChallengeBrand]: true;
}

export interface VerifiedEnrollmentComplete {
  readonly type: "enrollment_complete";
  readonly header: SignedHeader;
  readonly payload: EnrollmentCompletePayload;
  readonly [verifiedEnrollmentCompleteBrand]: true;
}

export interface VerifiedEnrollmentError {
  readonly type: "enrollment_error";
  readonly header: SignedHeader;
  readonly payload: EnrollmentErrorPayload;
  readonly [verifiedEnrollmentErrorBrand]: true;
}

export interface EnrollmentCompletePayload {
  readonly device_id: string;
  readonly pairing_generation: string;
  readonly tenant_id: string;
  readonly human_principal_id: string;
  readonly agent_instance_id: string;
  readonly enrollment_scope_ceiling: readonly string[];
  readonly selected_protocol: string;
  readonly client_nonce: string;
  readonly bridge_nonce: string;
  readonly bridge_fingerprint: string;
  readonly device_jwk_thumbprint: string;
}

export type EnrollmentErrorPayload =
  | Readonly<{ code: "MESSAGE_TOO_LARGE" | "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "AUTH_BINDING_MISMATCH" | "VERSION_UNSUPPORTED" }>
  | Readonly<{ code: "RATE_LIMITED"; retry_after_seconds: string }>;

declare const verifiedEnrollmentChallengeBrand: unique symbol;
declare const verifiedEnrollmentCompleteBrand: unique symbol;
declare const verifiedEnrollmentErrorBrand: unique symbol;

export type EnrollmentBridgeAdmissionContext =
  | {
    readonly phase: "challenge";
    readonly expectedTicketDigest: string;
    readonly expectedChallenge: string;
    readonly qrPinnedBridgeFingerprint: string;
    readonly clock: Clock;
  }
  | {
    readonly phase: "pinned";
    readonly expectedTicketDigest: string;
    readonly pendingTranscript: PairingTranscript;
    readonly verifier: Verifier;
    readonly expectedKeyId: string;
    readonly clock: Clock;
  };

export interface ConnectMessageAdmissionContext {
  readonly verifier: Verifier;
  readonly expectedSignerRole: "device" | "bridge-command";
  readonly expectedKeyId: string;
  readonly expectedDeviceId: string;
  readonly expectedPairingGeneration: string;
  readonly clock: Clock;
}

declare const verifiedConnectHelloBrand: unique symbol;
declare const verifiedConnectWelcomeBrand: unique symbol;

export interface VerifiedConnectHello {
  readonly type: "connect_hello";
  readonly header: SignedHeader;
  readonly payload: Readonly<{
    client_nonce: string;
    supported_versions: readonly string[];
    last_manifest_generation: string | null;
    last_event_cursor: string | null;
  }>;
  readonly [verifiedConnectHelloBrand]: true;
}

export interface VerifiedConnectWelcome {
  readonly type: "connect_welcome";
  readonly header: SignedHeader;
  readonly payload: Readonly<{
    client_offer_digest: string;
    client_nonce: string;
    bridge_nonce: string;
    selected_protocol: string;
    bridge_time: string;
    command_key_set: Readonly<{ current: P256PublicJwk; next: P256PublicJwk | null }>;
    connection_generation: string;
  }>;
  readonly [verifiedConnectWelcomeBrand]: true;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const parseWire = (wire: Uint8Array): SignedWire => {
  let parsed: unknown;
  try {
    parsed = parseCanonicalJson(wire);
  } catch (error) {
    if (error instanceof Error && error.message === "MESSAGE_TOO_LARGE") throw error;
    throw new Error("SCHEMA_INVALID");
  }
  if (!isRecord(parsed) || !isRecord(parsed.header) || !isRecord(parsed.payload) || typeof parsed.signature !== "string") {
    throw new Error("SCHEMA_INVALID");
  }
  return parsed as unknown as SignedWire;
};

const validateAdmissionSchema = (schemaId: string, value: unknown): void => {
  try {
    validateSchema(schemaId, value);
  } catch {
    throw new Error("SCHEMA_INVALID");
  }
};

const isSorted = (values: readonly string[]): boolean =>
  values.every((value, index) => index === 0 || (values[index - 1] as string) < value);

const registryEntry = (messageType: string): MessageRegistryEntry => {
  const entry = lockedRegistry.messages.find((candidate) => candidate.message_type === messageType);
  if (!entry) throw new Error("SCHEMA_INVALID");
  return entry;
};

const assertRegistryTuple = (wire: SignedWire, entry: MessageRegistryEntry): void => {
  if (wire.header.message_type !== entry.message_type
    || wire.header.message_schema !== entry.schema_id
    || wire.header.direction !== entry.direction) throw new Error("SCHEMA_INVALID");
};

const equalText = (left: string, right: string): boolean => {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.byteLength === b.byteLength && timingSafeEqual(a, b);
};

const jwkThumbprint = (jwk: P256PublicJwk): string => sha256B64Url(canonicalBytes({
  crv: jwk.crv,
  kty: jwk.kty,
  x: jwk.x,
  y: jwk.y,
}));

const unsignedValue = (wire: SignedWire) => ({ header: wire.header, payload: wire.payload });

const assertDigest = (wire: SignedWire): void => {
  const actual = sha256B64Url(canonicalBytes(wire.payload));
  if (!equalText(actual, wire.header.payload_digest)) throw new Error("INTEGRITY_FAILED");
};

const assertNotExpired = (wire: SignedWire, clock: Clock): void => {
  const now = clock.wallNow().getTime();
  if (now >= Date.parse(wire.header.expires_at)) throw new Error("MESSAGE_EXPIRED");
};

const verifyWithPort = async (
  verifier: Verifier,
  role: SignerRole,
  keyId: string,
  wire: SignedWire,
  domain: string,
): Promise<void> => {
  if (wire.header.key_id !== keyId) throw new Error("AUTH_FAILED");
  const valid = await verifier.verify(role, keyId, signingPreimage(parseSignatureDomain(domain), unsignedValue(wire)), wire.signature);
  if (!valid) throw new Error("AUTH_FAILED");
};

export async function verifyEnrollmentBridgeMessage(
  rawWire: Uint8Array,
  context: EnrollmentBridgeAdmissionContext,
): Promise<VerifiedEnrollmentChallenge | VerifiedEnrollmentComplete | VerifiedEnrollmentError> {
  const wire = parseWire(rawWire);
  const allowedTypes = context.phase === "challenge" ? ["enrollment_challenge"] : ["enrollment_complete", "enrollment_error"];
  if (!allowedTypes.includes(wire.header.message_type)) throw new Error("SCHEMA_INVALID");
  validateAdmissionSchema("urn:open-android-intelligence:protocol:v1:envelope:enrollment_bridge_to_app", wire);
  if (wire.header.message_type === "enrollment_complete") {
    const scopes = wire.payload.enrollment_scope_ceiling;
    if (!Array.isArray(scopes) || !scopes.every((scope) => typeof scope === "string") || !isSorted(scopes)) {
      throw new Error("SCHEMA_INVALID");
    }
  }
  const entry = registryEntry(wire.header.message_type);
  assertRegistryTuple(wire, entry);

  if (context.phase === "challenge") {
    const payload = wire.payload as unknown as VerifiedEnrollmentChallenge["payload"];
    const thumbprint = jwkThumbprint(payload.bridge_command_public_jwk);
    if (!equalText(thumbprint, context.qrPinnedBridgeFingerprint)) {
      throw new Error("AUTH_BINDING_MISMATCH");
    }
    if (wire.header.key_id !== payload.bridge_command_public_jwk.kid
      || !verifyEs256(payload.bridge_command_public_jwk, signingPreimage(parseSignatureDomain(entry.signature_domain), unsignedValue(wire)), wire.signature)) {
      throw new Error("AUTH_FAILED");
    }
  } else {
    await verifyWithPort(context.verifier, "bridge-command", context.expectedKeyId, wire, entry.signature_domain);
  }

  assertDigest(wire);
  assertNotExpired(wire, context.clock);
  if (!wire.header.enrollment_ticket_digest
    || !equalText(wire.header.enrollment_ticket_digest, context.expectedTicketDigest)) {
    throw new Error("AUTH_BINDING_MISMATCH");
  }

  if (context.phase === "pinned"
    && !equalText(context.expectedTicketDigest, context.pendingTranscript.ticket_digest)) {
    throw new Error("AUTH_BINDING_MISMATCH");
  }

  if (context.phase === "challenge") {
    const payload = wire.payload as unknown as VerifiedEnrollmentChallenge["payload"];
    if (!equalText(payload.challenge, context.expectedChallenge)
      || !equalText(payload.bridge_fingerprint, context.qrPinnedBridgeFingerprint)) {
      throw new Error("AUTH_BINDING_MISMATCH");
    }
    for (const version of payload.supported_versions) parseProtocolVersion(version);
    return deepFreeze({ type: "enrollment_challenge", header: wire.header, payload }) as VerifiedEnrollmentChallenge;
  }

  if (wire.header.message_type === "enrollment_complete") {
    const payload = wire.payload as unknown as EnrollmentCompletePayload;
    const transcript = context.pendingTranscript;
    const bindings: readonly (readonly [unknown, string])[] = [
      [payload.client_nonce, transcript.client_nonce],
      [payload.bridge_nonce, transcript.bridge_nonce],
      [payload.bridge_fingerprint, transcript.bridge_fingerprint],
      [payload.device_jwk_thumbprint, transcript.device_jwk_thumbprint],
      [payload.selected_protocol, transcript.selected_protocol],
    ];
    if (bindings.some(([actual, expected]) => typeof actual !== "string" || !equalText(actual, expected))) {
      throw new Error("AUTH_BINDING_MISMATCH");
    }
    parseProtocolVersion(payload.selected_protocol);
    return deepFreeze({ type: "enrollment_complete", header: wire.header, payload }) as VerifiedEnrollmentComplete;
  }
  return deepFreeze({ type: "enrollment_error", header: wire.header, payload: wire.payload as unknown as EnrollmentErrorPayload }) as VerifiedEnrollmentError;
}

export function verifyConnectMessage(
  wire: Uint8Array,
  expectedType: "connect_hello",
  context: ConnectMessageAdmissionContext,
): Promise<VerifiedConnectHello>;
export function verifyConnectMessage(
  wire: Uint8Array,
  expectedType: "connect_welcome",
  context: ConnectMessageAdmissionContext,
): Promise<VerifiedConnectWelcome>;
export async function verifyConnectMessage(
  rawWire: Uint8Array,
  expectedType: "connect_hello" | "connect_welcome",
  context: ConnectMessageAdmissionContext,
): Promise<VerifiedConnectHello | VerifiedConnectWelcome> {
  const wire = parseWire(rawWire);
  if (wire.header.message_type !== expectedType) throw new Error("SCHEMA_INVALID");
  validateAdmissionSchema(`urn:open-android-intelligence:protocol:v1:envelope:${expectedType}`, wire);
  const entry = registryEntry(expectedType);
  assertRegistryTuple(wire, entry);
  const requiredRole = expectedType === "connect_hello" ? "device" : "bridge-command";
  if (context.expectedSignerRole !== requiredRole) throw new Error("AUTH_FAILED");
  if (expectedType === "connect_welcome") {
    const payload = wire.payload as unknown as VerifiedConnectWelcome["payload"];
    if (payload.command_key_set.current.kid !== wire.header.key_id
      || (payload.command_key_set.next !== null && payload.command_key_set.next.kid === payload.command_key_set.current.kid)) {
      throw new Error("SCHEMA_INVALID");
    }
  }
  await verifyWithPort(context.verifier, requiredRole, context.expectedKeyId, wire, entry.signature_domain);
  assertDigest(wire);
  assertNotExpired(wire, context.clock);
  if (expectedType === "connect_welcome") {
    const keys = (wire.payload as unknown as VerifiedConnectWelcome["payload"]).command_key_set;
    if (!isValidP256PublicJwk(keys.current)
      || (keys.next !== null && !isValidP256PublicJwk(keys.next))) {
      throw new Error("SCHEMA_INVALID");
    }
  }
  if (wire.header.device_id !== context.expectedDeviceId
    || wire.header.pairing_generation !== context.expectedPairingGeneration) {
    throw new Error("AUTH_BINDING_MISMATCH");
  }
  if (expectedType === "connect_hello") {
    const payload = wire.payload as unknown as VerifiedConnectHello["payload"];
    for (const version of payload.supported_versions) parseProtocolVersion(version);
    return deepFreeze({ type: "connect_hello", header: wire.header, payload }) as VerifiedConnectHello;
  }
  const payload = wire.payload as unknown as VerifiedConnectWelcome["payload"];
  parseProtocolVersion(payload.selected_protocol);
  return deepFreeze({ type: "connect_welcome", header: wire.header, payload }) as VerifiedConnectWelcome;
}
