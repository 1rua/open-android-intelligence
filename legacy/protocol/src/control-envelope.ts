/// <reference types="node" />

import { randomBytes, timingSafeEqual } from "node:crypto";
import { isValidP256PublicJwk, verifyEs256 } from "./crypto.js";
import { canonicalBytes, parseCanonicalJson, sha256B64Url, signingPreimage } from "./encoding.js";
import {
  loadMessageRegistry,
  type MessageRegistryEntry,
  type P256PublicJwk,
} from "./message-registry.js";
import type { Clock, SignerRole } from "./ports.js";
import { parseSignatureDomain, type SignatureDomain } from "./profile.js";
import { validateSchema } from "./schema-validator.js";
import {
  DeterministicConnectionFenceStore,
  fenceConnection,
  type ConnectionAllocation,
  type DeterministicConnectionFenceSnapshot,
  type ConnectionFenceKey,
  type ConnectionFenceStore,
  type ConnectionLease,
  type ConnectionLeaseInspection,
} from "./connection-fence.js";
import type { KeyRecord, KeyRingId, KeyRingSnapshot, KeyRingState, KeyRingStore } from "./key-ring.js";
import {
  DeterministicReplayLedger,
  DeterministicTrustedReplayReconciler,
  TASK5_RECEIPT_BYTE_BUDGET,
  buildDeterministicDeviceReplayMetadata,
  buildDeterministicAdapterReplayMetadata,
  canonicalReplayIntentMetadataBytes,
  type DeterministicReplayLedgerSnapshot,
  type ReplayRowStatus,
  type LockedReplayRegistryIdentity,
  type PersistedReplayIntentMetadata,
  type TrustedReplayReconciler,
  AdapterReplayAdmissionRequest,
  DeviceReplayAdmissionRequest,
  OrdinaryReplayCapacityDenial,
  ReplayStore,
  SecurityReplayCapacityDenial,
  StoreReplayDecision,
} from "./replay-window.js";
import type { ReplayClaim } from "./replay-window.js";

export type Task5MessageType =
  | "device_ping" | "bridge_ping" | "device_presence"
  | "device_key_rotation" | "device_key_rotation_ack"
  | "bridge_key_rotation" | "bridge_key_rotation_ack"
  | "adapter_key_rotation" | "adapter_key_rotation_ack"
  | "device_event" | "event_ack";

/** Cumulative control union.  The historical Task5 name is retained for
 * source compatibility; Task9 extends it with the two event leaves. */
export type Task9MessageType = "device_event" | "event_ack";

export const TASK5_MAX_LIFETIME_SECONDS: Readonly<Record<Task5MessageType, number>> = Object.freeze({
  device_ping: 60,
  bridge_ping: 60,
  device_presence: 60,
  device_key_rotation: 300,
  device_key_rotation_ack: 300,
  bridge_key_rotation: 300,
  bridge_key_rotation_ack: 300,
  adapter_key_rotation: 300,
  adapter_key_rotation_ack: 300,
  device_event: 86_400,
  event_ack: 300,
});

export type Task5AdmissionError =
  | "MESSAGE_TOO_LARGE" | "SCHEMA_INVALID" | "AUTH_FAILED"
  | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "CONNECTION_FENCED"
  | "AUTH_BINDING_MISMATCH" | "ADAPTER_PRINCIPAL_MISSING"
  | "NOT_AUTHORIZED" | "REPLAY_REJECTED";

export interface DeviceCredentialRecord {
  readonly credentialId: string;
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
  readonly pairingGeneration: bigint;
  readonly active: boolean;
}

export interface AdapterCredentialRecord {
  readonly credentialId: string;
  readonly generation: bigint;
  readonly tenantId: string;
  readonly agentPrincipalId: string | null;
  readonly agentInstanceId: string;
  readonly workspaceId: string;
  readonly scopeCeiling: readonly string[];
  readonly active: boolean;
}

export interface AuthenticatedAdapterPrincipal {
  readonly humanPrincipalId: string | null;
  readonly agentPrincipalId: string | null;
}

export type AuthenticatedIngressKind = "device" | "adapter";
const authenticatedIngressHandleBrand: unique symbol = Symbol("authenticated-ingress-handle");
export type AuthenticatedIngressHandle<K extends AuthenticatedIngressKind = AuthenticatedIngressKind> = Readonly<{
  readonly kind: K;
  readonly handleId: string;
  readonly [authenticatedIngressHandleBrand]: true;
}>;

const adapterCredentialLeaseBrand: unique symbol = Symbol("adapter-credential-lease");
export type AdapterCredentialLease = Readonly<{ readonly [adapterCredentialLeaseBrand]: true }>;
export type AdapterCredentialLeaseInspection =
  | { kind: "current"; generation: bigint }
  | { kind: "fenced" };

const loadedTrustedBindingBrand: unique symbol = Symbol("loaded-trusted-binding");
export type LoadedTrustedBinding =
  | {
      readonly kind: "device";
      readonly transport: "https" | "wss";
      readonly transportProfileId: string;
      readonly connectionId: string;
      readonly allocatedConnectionGeneration: bigint;
      readonly connectionLease: ConnectionLease;
      readonly credential: DeviceCredentialRecord;
      readonly [loadedTrustedBindingBrand]: true;
    }
  | {
      readonly kind: "adapter";
      readonly connectionId: string;
      readonly credential: AdapterCredentialRecord;
      readonly principal: AuthenticatedAdapterPrincipal;
      readonly credentialLease: AdapterCredentialLease;
      readonly [loadedTrustedBindingBrand]: true;
    };

const authenticatedBindingContextBrand: unique symbol = Symbol("authenticated-binding-context");
export type AuthenticatedBindingContext =
  | Readonly<{
      kind: "device";
      credentialId: string;
      tenantId: string;
      humanPrincipalId: string;
      deviceId: string;
      pairingGeneration: bigint;
      connectionGeneration: bigint;
      direction: "app-to-bridge" | "bridge-to-app";
      readonly [authenticatedBindingContextBrand]: true;
    }>
  | Readonly<{
      kind: "adapter";
      credentialId: string;
      adapterCredentialGeneration: bigint;
      tenantId: string;
      humanPrincipalId: string;
      agentPrincipalId: string;
      agentInstanceId: string;
      workspaceId: string;
      scopeCeiling: readonly string[];
      direction: "adapter-to-bridge" | "bridge-to-adapter";
      readonly [authenticatedBindingContextBrand]: true;
    }>;

export interface EnvelopeAuthorizationGate {
  evaluate(messageType: string, payload: unknown, context: AuthenticatedBindingContext):
    | { allowed: true }
    | { allowed: false; denial: "PAIRING_INACTIVE" | "SCOPE_DENIED" | "REVISION_MISMATCH" | "POLICY_BLOCKED" };
}

export type PreReplayValidation =
  | { readonly allowed: true }
  | { readonly allowed: false; readonly error: "NOT_AUTHORIZED" | "INTEGRITY_FAILED"; readonly decisionReason: string };

/** Optional semantic gate shared by Task6/Task9.  It runs after signature,
 * binding and authorization checks but before replay read or mutation. */
export interface PreReplayIntegrityGate {
  evaluate(messageType: string, payload: unknown, context: AuthenticatedBindingContext): PreReplayValidation | Promise<PreReplayValidation>;
}

const deviceAdmissionBackendBrand: unique symbol = Symbol("device-admission-backend");
export interface DeviceReplayAdmissionStore<
  TMessageType extends string = Task5MessageType,
  TCapacityDenial extends OrdinaryReplayCapacityDenial | SecurityReplayCapacityDenial = OrdinaryReplayCapacityDenial,
> extends ReplayStore<TMessageType>, ConnectionFenceStore {
  readonly [deviceAdmissionBackendBrand]: true;
  loadCommittedDeviceBinding(handle: AuthenticatedIngressHandle<"device">): Promise<Extract<LoadedTrustedBinding, { kind: "device" }>>;
  admitDevice(request: DeviceReplayAdmissionRequest<TMessageType>): Promise<StoreReplayDecision<TMessageType, TCapacityDenial>>;
}

const adapterAdmissionBackendBrand: unique symbol = Symbol("adapter-admission-backend");
export interface AdapterReplayAdmissionStore<
  TMessageType extends string = Task5MessageType,
  TCapacityDenial extends OrdinaryReplayCapacityDenial | SecurityReplayCapacityDenial = OrdinaryReplayCapacityDenial,
> extends ReplayStore<TMessageType> {
  readonly [adapterAdmissionBackendBrand]: true;
  loadCommittedAdapterBinding(handle: AuthenticatedIngressHandle<"adapter">): Promise<Extract<LoadedTrustedBinding, { kind: "adapter" }>>;
  inspectAdapterLease(lease: AdapterCredentialLease): Promise<AdapterCredentialLeaseInspection>;
  admitAdapter(request: AdapterReplayAdmissionRequest<TMessageType>): Promise<StoreReplayDecision<TMessageType, TCapacityDenial>>;
}

const exactWireBytesBrand: unique symbol = Symbol("exact-wire-bytes");
export type ExactWireBytes = Readonly<{
  readonly byteLength: number;
  copy(): Uint8Array;
  readonly [exactWireBytesBrand]: true;
}>;

const verifiedSignedEnvelopeBrand: unique symbol = Symbol("verified-signed-envelope");
export interface VerifiedSignedEnvelope<TMessageType extends string = Task5MessageType> {
  readonly rawWire: ExactWireBytes;
  readonly messageType: TMessageType;
  readonly header: Readonly<Record<string, unknown>>;
  readonly payload: Readonly<Record<string, unknown>>;
  readonly registryEntry: MessageRegistryEntry;
  readonly signerRole: SignerRole;
  readonly envelopeDigest: string;
  readonly [verifiedSignedEnvelopeBrand]: true;
}

const acceptedTransportFrameBrand: unique symbol = Symbol("accepted-transport-frame");
export type AcceptedTransportFrame<TMessageType extends string = Task5MessageType> =
  | Readonly<{
      readonly envelope: VerifiedSignedEnvelope<TMessageType>;
      readonly context: Extract<AuthenticatedBindingContext, { kind: "device" }>;
      readonly claim: ReplayClaim<TMessageType>;
      readonly connectionLease: ConnectionLease;
      readonly [acceptedTransportFrameBrand]: true;
    }>
  | Readonly<{
      readonly envelope: VerifiedSignedEnvelope<TMessageType>;
      readonly context: Extract<AuthenticatedBindingContext, { kind: "adapter" }>;
      readonly claim: ReplayClaim<TMessageType>;
      readonly connectionLease: null;
      readonly adapterCredentialLease: AdapterCredentialLease;
      readonly [acceptedTransportFrameBrand]: true;
    }>;

export type SignedEnvelopeVerificationResult<TMessageType extends string = Task5MessageType> =
  | { ok: true; envelope: VerifiedSignedEnvelope<TMessageType> }
  | { ok: false; error: Extract<Task5AdmissionError, "MESSAGE_TOO_LARGE" | "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED"> };

export type BindingVerificationResult =
  | { ok: true; context: AuthenticatedBindingContext }
  | { ok: false; error: "CONNECTION_FENCED" | "AUTH_BINDING_MISMATCH" | "ADAPTER_PRINCIPAL_MISSING" };

export type TransportFrameDecision =
  | { ok: true; kind: "accepted"; frame: AcceptedTransportFrame }
  | { ok: true; kind: "duplicate"; cachedReceipt: ExactWireBytes }
  | { ok: false; error: Task5AdmissionError };

interface ParsedWire {
  readonly header: Record<string, unknown>;
  readonly payload: Record<string, unknown>;
  readonly signature: string;
}

const TASK5_MESSAGE_TYPES = new Set<string>(Object.keys(TASK5_MAX_LIFETIME_SECONDS));
const registry = loadMessageRegistry();
const ingressOwners = new WeakMap<object, object>();
const bindingOwners = new WeakMap<object, object>();
const contextOwners = new WeakMap<object, object>();
const backendOwners = new WeakMap<object, Readonly<{ kind: AuthenticatedIngressKind; token: object }>>();
const reconcilerOwners = new WeakMap<object, object>();

const deepFreeze = <T>(value: T): T => {
  if (typeof value === "object" && value !== null && !Object.isFrozen(value)) {
    for (const member of Object.values(value)) deepFreeze(member);
    Object.freeze(value);
  }
  return value;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const retainedBytes = (source: Uint8Array): ExactWireBytes => {
  const bytes = Uint8Array.from(source);
  return Object.freeze({
    byteLength: bytes.byteLength,
    copy: (): Uint8Array => Uint8Array.from(bytes),
    [exactWireBytesBrand]: true as const,
  });
};

export const retainExactWireBytes = (source: Uint8Array): ExactWireBytes => retainedBytes(source);

const equalBytes = (left: Uint8Array, right: Uint8Array): boolean =>
  left.byteLength === right.byteLength && timingSafeEqual(left, right);

const parseU64 = (value: unknown): bigint | null => {
  if (typeof value !== "string" || !/^(0|[1-9][0-9]*)$/.test(value)) return null;
  const parsed = BigInt(value);
  return parsed <= 18_446_744_073_709_551_615n ? parsed : null;
};

const signerRoleForDirection = (direction: MessageRegistryEntry["direction"]): SignerRole => {
  if (direction === "app-to-bridge") return "device";
  if (direction === "adapter-to-bridge") return "adapter";
  return "bridge-command";
};

const replayRegistryIdentityFor = <TMessageType extends string>(
  messageType: TMessageType,
  entry: MessageRegistryEntry,
): LockedReplayRegistryIdentity<TMessageType> => Object.freeze({
  messageType,
  messageSchemaId: entry.schema_id,
  headerSchemaId: `urn:open-android-intelligence:protocol:v1:header:${messageType}`,
  envelopeSchemaId: `urn:open-android-intelligence:protocol:v1:envelope:${messageType}`,
  direction: entry.direction,
  signatureDomain: parseSignatureDomain(entry.signature_domain),
  signerRole: signerRoleForDirection(entry.direction),
});

const keyRingIdFor = (trusted: LoadedTrustedBinding, role: SignerRole): KeyRingId => ({
  owner: role === "device" ? "device-installation" : role,
  credentialId: trusted.credential.credentialId,
});

const schemaCheck = (schemaId: string, value: unknown): boolean => {
  try {
    validateSchema(schemaId, value);
    return true;
  } catch {
    return false;
  }
};

const parseFamily = (rawWire: Uint8Array, kind: AuthenticatedIngressKind): ParsedWire | null => {
  let value: unknown;
  try {
    value = parseCanonicalJson(rawWire);
  } catch {
    return null;
  }
  const familySchema = kind === "device"
    ? "urn:open-android-intelligence:protocol:v1:control-envelope#/$defs/paired_device_family_envelope"
    : "urn:open-android-intelligence:protocol:v1:key-rotation#/$defs/adapter_family_envelope";
  if (!schemaCheck(familySchema, value) || !isRecord(value)
    || !isRecord(value.header) || !isRecord(value.payload) || typeof value.signature !== "string") return null;
  return { header: value.header, payload: value.payload, signature: value.signature };
};

const ownsIngress = (
  admission: DeviceReplayAdmissionStore | AdapterReplayAdmissionStore,
  ingress: AuthenticatedIngressHandle,
): boolean => {
  const backend = backendOwners.get(admission);
  return backend !== undefined && backend.kind === ingress.kind && ingressOwners.get(ingress) === backend.token;
};

const isDeviceAdmission = (
  admission: DeviceReplayAdmissionStore | AdapterReplayAdmissionStore,
): admission is DeviceReplayAdmissionStore => backendOwners.get(admission)?.kind === "device";

const isAdapterAdmission = (
  admission: DeviceReplayAdmissionStore | AdapterReplayAdmissionStore,
): admission is AdapterReplayAdmissionStore => backendOwners.get(admission)?.kind === "adapter";

const isDeviceIngress = (
  ingress: AuthenticatedIngressHandle,
): ingress is AuthenticatedIngressHandle<"device"> => ingress.kind === "device";

const isAdapterIngress = (
  ingress: AuthenticatedIngressHandle,
): ingress is AuthenticatedIngressHandle<"adapter"> => ingress.kind === "adapter";

const liveKeyFor = (record: KeyRecord | undefined, issuedAt: number, now: number): boolean => {
  if (!record || !isValidP256PublicJwk(record.publicJwk)) return false;
  const activatedAt = record.activatedAt === null ? null : Date.parse(record.activatedAt);
  const signingNotAfter = record.signingNotAfter === null ? null : Date.parse(record.signingNotAfter);
  if (record.lifecycle === "active") {
    if (activatedAt !== null && issuedAt < activatedAt) return false;
    return signingNotAfter === null || issuedAt <= signingNotAfter;
  }
  if (record.lifecycle !== "grace_verify_only" || activatedAt === null || record.liveVerifyUntil === null) return false;
  return issuedAt <= activatedAt && now < Date.parse(record.liveVerifyUntil);
};

const validateRotationProposal = (
  messageType: Task5MessageType,
  header: Record<string, unknown>,
  payload: Record<string, unknown>,
  ring: KeyRingState,
  proposalDigest: string,
): boolean => {
  if (!messageType.endsWith("key_rotation")) return true;
  const jwk = payload.new_public_jwk;
  if (!isRecord(jwk) || !isValidP256PublicJwk(jwk)) return false;
  const newKeyId = jwk.kid;
  if (typeof newKeyId !== "string" || payload.old_key_id !== header.key_id
    || payload.old_key_id === newKeyId) return false;
  const thumbprint = sha256B64Url(canonicalBytes({ crv: jwk.crv, kty: jwk.kty, x: jwk.x, y: jwk.y }));
  if (payload.new_key_thumbprint !== thumbprint) return false;
  const retained = ring.keys.get(newKeyId);
  if (!retained) return true;
  const transcript = ring.pending?.transcript;
  return retained.lifecycle === "pending" && transcript !== undefined
    && transcript.rotationId === payload.rotation_id
    && transcript.oldKeyId === payload.old_key_id
    && equalBytes(canonicalBytes(transcript.newPublicJwk), canonicalBytes(jwk))
    && transcript.newKeyThumbprint === payload.new_key_thumbprint
    && transcript.challenge === payload.challenge
    && transcript.proposalDigest === proposalDigest
    && transcript.pairingGeneration === parseU64(header.pairing_generation);
};

type InternalVerification =
  | { ok: true; envelope: VerifiedSignedEnvelope; trusted: LoadedTrustedBinding }
  | { ok: false; error: "MESSAGE_TOO_LARGE" | "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" };

const verifySignedInternal = async (
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle,
  admission: DeviceReplayAdmissionStore | AdapterReplayAdmissionStore,
  keyRings: KeyRingStore,
  now: Date,
): Promise<InternalVerification> => {
  if (rawWire.byteLength > 262_144) return { ok: false, error: "MESSAGE_TOO_LARGE" };
  const wire = parseFamily(rawWire, ingress.kind);
  if (!wire) return { ok: false, error: "SCHEMA_INVALID" };
  const messageType = wire.header.message_type;
  if (typeof messageType !== "string" || !TASK5_MESSAGE_TYPES.has(messageType)) return { ok: false, error: "SCHEMA_INVALID" };
  const entries = registry.messages.filter((entry) => entry.message_type === messageType);
  if (entries.length !== 1) return { ok: false, error: "SCHEMA_INVALID" };
  const entry = entries[0] as MessageRegistryEntry;
  const role = signerRoleForDirection(entry.direction);
  try {
    parseSignatureDomain(entry.signature_domain);
  } catch {
    return { ok: false, error: "SCHEMA_INVALID" };
  }
  const branchDirection = ingress.kind === "device"
    ? entry.direction === "app-to-bridge" || entry.direction === "bridge-to-app"
    : entry.direction === "adapter-to-bridge" || entry.direction === "bridge-to-adapter";
  if (!branchDirection || wire.header.message_schema !== entry.schema_id
    || wire.header.direction !== entry.direction) return { ok: false, error: "SCHEMA_INVALID" };
  if (!schemaCheck(`urn:open-android-intelligence:protocol:v1:envelope:${messageType}`, wire)) {
    return { ok: false, error: "SCHEMA_INVALID" };
  }

  if (!ownsIngress(admission, ingress)) return { ok: false, error: "AUTH_FAILED" };
  if (!Object.is(keyRings, admission)) return { ok: false, error: "AUTH_FAILED" };

  let trusted: LoadedTrustedBinding;
  try {
    if (isDeviceIngress(ingress) && isDeviceAdmission(admission)) {
      trusted = await admission.loadCommittedDeviceBinding(ingress);
    } else if (isAdapterIngress(ingress) && isAdapterAdmission(admission)) {
      trusted = await admission.loadCommittedAdapterBinding(ingress);
    } else {
      return { ok: false, error: "AUTH_FAILED" };
    }
  } catch {
    return { ok: false, error: "AUTH_FAILED" };
  }
  const issuedAt = Date.parse(wire.header.issued_at as string);
  let snapshot;
  try {
    snapshot = await keyRings.load(keyRingIdFor(trusted, role));
  } catch {
    return { ok: false, error: "AUTH_FAILED" };
  }
  const keyId = wire.header.key_id;
  if (typeof keyId !== "string") return { ok: false, error: "AUTH_FAILED" };
  const record = snapshot.state.keys.get(keyId);
  if (!record || record.keyId !== keyId || !liveKeyFor(record, issuedAt, now.getTime())) {
    return { ok: false, error: "AUTH_FAILED" };
  }
  const domain = parseSignatureDomain(entry.signature_domain);
  if (!verifyEs256(record.publicJwk, signingPreimage(domain, { header: wire.header, payload: wire.payload }), wire.signature)) {
    return { ok: false, error: "AUTH_FAILED" };
  }
  const expectedDigest = Buffer.from(sha256B64Url(canonicalBytes(wire.payload)), "base64url");
  const suppliedDigest = typeof wire.header.payload_digest === "string"
    ? Buffer.from(wire.header.payload_digest, "base64url") : new Uint8Array();
  if (!equalBytes(expectedDigest, suppliedDigest)
    || !validateRotationProposal(messageType as Task5MessageType, wire.header, wire.payload, snapshot.state, sha256B64Url(rawWire))) {
    return { ok: false, error: "INTEGRITY_FAILED" };
  }
  const expiresAt = Date.parse(wire.header.expires_at as string);
  const lifetimeMs = TASK5_MAX_LIFETIME_SECONDS[messageType as Task5MessageType] * 1000;
  const nowMs = now.getTime();
  if (!Number.isFinite(issuedAt) || !Number.isFinite(expiresAt)
    || expiresAt <= issuedAt || issuedAt > nowMs + 60_000 || expiresAt <= nowMs
    || expiresAt - issuedAt > lifetimeMs) return { ok: false, error: "MESSAGE_EXPIRED" };

  const header = deepFreeze(wire.header);
  const payload = deepFreeze(wire.payload);
  const envelope = deepFreeze({
    rawWire: retainedBytes(rawWire),
    messageType: messageType as Task5MessageType,
    header,
    payload,
    registryEntry: entry,
    signerRole: role,
    envelopeDigest: sha256B64Url(rawWire),
    [verifiedSignedEnvelopeBrand]: true as const,
  });
  return { ok: true, envelope, trusted };
};

export function verifySignedEnvelope(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle<"device">,
  dependencies: Readonly<{ admission: DeviceReplayAdmissionStore; keyRings: KeyRingStore; clock: Clock }>,
): Promise<SignedEnvelopeVerificationResult>;
export function verifySignedEnvelope(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle<"adapter">,
  dependencies: Readonly<{ admission: AdapterReplayAdmissionStore; keyRings: KeyRingStore; clock: Clock }>,
): Promise<SignedEnvelopeVerificationResult>;
export async function verifySignedEnvelope(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle,
  dependencies: Readonly<{ admission: DeviceReplayAdmissionStore | AdapterReplayAdmissionStore; keyRings: KeyRingStore; clock: Clock }>,
): Promise<SignedEnvelopeVerificationResult> {
  const now = dependencies.clock.wallNow();
  const decision = await verifySignedInternal(rawWire, ingress, dependencies.admission, dependencies.keyRings, now);
  return decision.ok ? { ok: true, envelope: decision.envelope } : decision;
}

export function verifyAuthenticatedBinding(
  envelope: VerifiedSignedEnvelope,
  trusted: Extract<LoadedTrustedBinding, { kind: "device" }>,
  inspection: ConnectionLeaseInspection,
): BindingVerificationResult {
  const headerGeneration = parseU64(envelope.header.connection_generation);
  if (headerGeneration === null
    || !fenceConnection(inspection, trusted.allocatedConnectionGeneration, headerGeneration).ok) {
    return { ok: false, error: "CONNECTION_FENCED" };
  }
  const credential = trusted.credential;
  const pairingGeneration = parseU64(envelope.header.pairing_generation);
  const direction = envelope.header.direction;
  if (!credential.active || envelope.header.device_id !== credential.deviceId
    || pairingGeneration !== credential.pairingGeneration
    || (direction !== "app-to-bridge" && direction !== "bridge-to-app")) {
    return { ok: false, error: "AUTH_BINDING_MISMATCH" };
  }
  const context: Extract<AuthenticatedBindingContext, { kind: "device" }> = deepFreeze({
    kind: "device",
    credentialId: credential.credentialId,
    tenantId: credential.tenantId,
    humanPrincipalId: credential.humanPrincipalId,
    deviceId: credential.deviceId,
    pairingGeneration: credential.pairingGeneration,
    connectionGeneration: trusted.allocatedConnectionGeneration,
    direction,
    [authenticatedBindingContextBrand]: true,
  });
  const owner = bindingOwners.get(trusted);
  if (owner !== undefined) contextOwners.set(context, owner);
  return { ok: true, context };
}

const validScope = (scope: string): boolean => /^[a-z][a-z0-9._-]{0,127}$/.test(scope);

export function verifyAdapterAdmission(
  envelope: VerifiedSignedEnvelope,
  trusted: Extract<LoadedTrustedBinding, { kind: "adapter" }>,
): BindingVerificationResult {
  const generation = parseU64(envelope.header.adapter_credential_generation);
  if (generation === null || generation !== trusted.credential.generation) {
    return { ok: false, error: "CONNECTION_FENCED" };
  }
  const credential = trusted.credential;
  const principal = trusted.principal;
  if (principal.humanPrincipalId === null || principal.agentPrincipalId === null) {
    return { ok: false, error: "ADAPTER_PRINCIPAL_MISSING" };
  }
  const direction = envelope.header.direction;
  const scopes = [...credential.scopeCeiling];
  if (!credential.active || credential.agentPrincipalId === null
    || principal.agentPrincipalId !== credential.agentPrincipalId
    || envelope.header.adapter_credential_id !== credential.credentialId
    || (direction !== "adapter-to-bridge" && direction !== "bridge-to-adapter")
    || !scopes.every(validScope)) return { ok: false, error: "AUTH_BINDING_MISMATCH" };
  scopes.sort();
  if (scopes.some((scope, index) => index > 0 && scope === scopes[index - 1])) {
    return { ok: false, error: "AUTH_BINDING_MISMATCH" };
  }
  Object.freeze(scopes);
  const context: Extract<AuthenticatedBindingContext, { kind: "adapter" }> = deepFreeze({
    kind: "adapter",
    credentialId: credential.credentialId,
    adapterCredentialGeneration: credential.generation,
    tenantId: credential.tenantId,
    humanPrincipalId: principal.humanPrincipalId,
    agentPrincipalId: principal.agentPrincipalId,
    agentInstanceId: credential.agentInstanceId,
    workspaceId: credential.workspaceId,
    scopeCeiling: scopes,
    direction,
    [authenticatedBindingContextBrand]: true,
  });
  const owner = bindingOwners.get(trusted);
  if (owner !== undefined) contextOwners.set(context, owner);
  return { ok: true, context };
}

export function verifyTransportFrame(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle<"device">,
  dependencies: Readonly<{ clock: Clock; keyRings: KeyRingStore; admission: DeviceReplayAdmissionStore; authorization: EnvelopeAuthorizationGate; preReplay?: PreReplayIntegrityGate }>,
): Promise<TransportFrameDecision>;
export function verifyTransportFrame(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle<"adapter">,
  dependencies: Readonly<{ clock: Clock; keyRings: KeyRingStore; admission: AdapterReplayAdmissionStore; authorization: EnvelopeAuthorizationGate; preReplay?: PreReplayIntegrityGate }>,
): Promise<TransportFrameDecision>;
export async function verifyTransportFrame(
  rawWire: Uint8Array,
  ingress: AuthenticatedIngressHandle,
  dependencies: Readonly<{ clock: Clock; keyRings: KeyRingStore; admission: DeviceReplayAdmissionStore | AdapterReplayAdmissionStore; authorization: EnvelopeAuthorizationGate; preReplay?: PreReplayIntegrityGate }>,
): Promise<TransportFrameDecision> {
  const now = dependencies.clock.wallNow();
  const verified = await verifySignedInternal(rawWire, ingress, dependencies.admission, dependencies.keyRings, now);
  if (!verified.ok) return verified;

  const admittedAt = now.toISOString();
  let replay: StoreReplayDecision;
  if (ingress.kind === "device" && verified.trusted.kind === "device" && isDeviceAdmission(dependencies.admission)) {
    const inspection = await dependencies.admission.inspect(verified.trusted.connectionLease);
    const binding = verifyAuthenticatedBinding(verified.envelope, verified.trusted, inspection);
    if (!binding.ok) return binding;
    if (binding.context.kind !== "device") return { ok: false, error: "AUTH_BINDING_MISMATCH" };
    if (!dependencies.authorization.evaluate(verified.envelope.messageType, verified.envelope.payload, binding.context).allowed) {
      return { ok: false, error: "NOT_AUTHORIZED" };
    }
    if (dependencies.preReplay) {
      const semantic = await dependencies.preReplay.evaluate(verified.envelope.messageType, verified.envelope.payload, binding.context);
      if (!semantic.allowed) return { ok: false, error: semantic.error };
    }
    replay = await dependencies.admission.admitDevice({
      envelope: verified.envelope,
      context: binding.context,
      connectionLease: verified.trusted.connectionLease,
      admittedAt,
    });
  } else if (ingress.kind === "adapter" && verified.trusted.kind === "adapter" && isAdapterAdmission(dependencies.admission)) {
    const inspection = await dependencies.admission.inspectAdapterLease(verified.trusted.credentialLease);
    if (inspection.kind !== "current" || inspection.generation !== verified.trusted.credential.generation) {
      return { ok: false, error: "CONNECTION_FENCED" };
    }
    const binding = verifyAdapterAdmission(verified.envelope, verified.trusted);
    if (!binding.ok) return binding;
    if (binding.context.kind !== "adapter") return { ok: false, error: "AUTH_BINDING_MISMATCH" };
    if (!dependencies.authorization.evaluate(verified.envelope.messageType, verified.envelope.payload, binding.context).allowed) {
      return { ok: false, error: "NOT_AUTHORIZED" };
    }
    if (dependencies.preReplay) {
      const semantic = await dependencies.preReplay.evaluate(verified.envelope.messageType, verified.envelope.payload, binding.context);
      if (!semantic.allowed) return { ok: false, error: semantic.error };
    }
    replay = await dependencies.admission.admitAdapter({
      envelope: verified.envelope,
      context: binding.context,
      adapterCredentialLease: verified.trusted.credentialLease,
      admittedAt,
    });
  } else {
    return { ok: false, error: "AUTH_BINDING_MISMATCH" };
  }
  if (replay.kind === "accepted") return { ok: true, kind: "accepted", frame: replay.frame };
  if (replay.kind === "duplicate") return { ok: true, kind: "duplicate", cachedReceipt: replay.cachedReceipt };
  return { ok: false, error: replay.error };
}

export interface DeterministicDeviceSecurityBackendOptions {
  readonly credential: DeviceCredentialRecord;
  readonly initialConnection: Readonly<{
    generation: bigint;
    fenceRevision: bigint;
    connectionId?: string | null;
    transportProfileId?: string | null;
    leasePersistenceId?: string | null;
  }>;
  readonly keyRings: readonly Readonly<{ id: KeyRingId; snapshot: KeyRingSnapshot }>[];
  readonly beforeReplayCommit?: (() => Promise<void>) | undefined;
  readonly claimIdSource?: (() => string) | undefined;
  readonly leaseIdSource?: (() => string) | undefined;
  readonly replayReferenceCheck?: ((claimId: string) => boolean) | undefined;
}

export interface DeterministicDevicePendingSnapshot {
  readonly rawWire: Uint8Array;
  readonly admittedAt: string;
  readonly transport: "https" | "wss";
  readonly transportProfileId: string;
  readonly connectionId: string;
  readonly context: Readonly<{
    credentialId: string; tenantId: string; humanPrincipalId: string; deviceId: string;
    pairingGeneration: bigint; connectionGeneration: bigint;
    direction: "app-to-bridge" | "bridge-to-app";
  }>;
  readonly claim: Readonly<{
    claimId: string; space: import("./replay-window.js").ReplaySpace; messageType: Task5MessageType;
    messageId: string; sequence: bigint; envelopeDigest: string; expiresAt: string; retentionUntil: string;
  }>;
  readonly persistedMetadata: PersistedReplayIntentMetadata<Task5MessageType>;
  readonly intentMetadataBytes: Uint8Array;
  readonly status?: ReplayRowStatus;
  readonly receipt?: Uint8Array | null;
  readonly retainedBytes?: number;
}

export interface DeterministicDeviceSecuritySnapshot {
  readonly credential: DeviceCredentialRecord;
  readonly connection: DeterministicConnectionFenceSnapshot;
  readonly keyRings: readonly Readonly<{ id: KeyRingId; snapshot: KeyRingSnapshot }>[];
  readonly pendingRows: readonly DeterministicDevicePendingSnapshot[];
  readonly replayRows?: readonly DeterministicDevicePendingSnapshot[];
  readonly replayLedger?: DeterministicReplayLedgerSnapshot<Task5MessageType>;
}

export interface AuthenticateDeterministicDeviceInput {
  readonly handleId: string;
  readonly transport: "https" | "wss";
  readonly connectionId: string;
  readonly transportProfileId: string;
}

/** Executable reference backend for the device branch. It deliberately owns
 * every ingress, binding, context, fence lease, replay claim, and key-ring
 * snapshot that it accepts, making cross-instance substitution fail closed. */
export class DeterministicDeviceSecurityBackend implements DeviceReplayAdmissionStore, KeyRingStore {
  readonly [deviceAdmissionBackendBrand] = true as const;
  readonly #token = Object.freeze({});
  readonly #credential: DeviceCredentialRecord;
  readonly #fence: DeterministicConnectionFenceStore;
  readonly #bindings = new WeakMap<object, Extract<LoadedTrustedBinding, { kind: "device" }>>();
  readonly #leaseBindings = new WeakMap<object, Extract<LoadedTrustedBinding, { kind: "device" }>>();
  readonly #rings = new Map<string, KeyRingSnapshot>();
  readonly #ringIds = new Map<string, KeyRingId>();
  #ledger: DeterministicReplayLedger<Task5MessageType>;
  readonly #frames = new WeakMap<object, AcceptedTransportFrame<Task5MessageType>>();
  readonly #pendingFrames = new Map<string, Extract<AcceptedTransportFrame<Task5MessageType>, { context: { kind: "device" } }>>();
  readonly #admittedAt = new Map<string, string>();
  #replayLookups = 0n;
  #replayMutations = 0n;
  readonly #beforeReplayCommit: (() => Promise<void>) | undefined;
  #replayReferenceCheck: (claimId: string) => boolean = () => false;

  constructor(options: DeterministicDeviceSecurityBackendOptions) {
    this.#credential = deepFreeze({ ...options.credential });
    const fenceKey: ConnectionFenceKey = {
      credentialId: this.#credential.credentialId,
      pairingGeneration: this.#credential.pairingGeneration,
    };
    this.#fence = new DeterministicConnectionFenceStore(fenceKey, {
      generation: options.initialConnection.generation,
      fenceRevision: options.initialConnection.fenceRevision,
      connectionId: options.initialConnection.connectionId ?? null,
      transportProfileId: options.initialConnection.transportProfileId ?? null,
      leasePersistenceId: options.initialConnection.leasePersistenceId ?? null,
    }, { leaseIdSource: options.leaseIdSource });
    this.#ledger = new DeterministicReplayLedger<Task5MessageType>({ claimIdSource: options.claimIdSource });
    for (const { id, snapshot } of options.keyRings) {
      this.#rings.set(this.#ringKey(id), snapshot);
      this.#ringIds.set(this.#ringKey(id), Object.freeze({ ...id }));
    }
    this.#beforeReplayCommit = options.beforeReplayCommit;
    this.#replayReferenceCheck = options.replayReferenceCheck ?? (() => false);
    backendOwners.set(this, { kind: "device", token: this.#token });
  }

  static restart(snapshot: DeterministicDeviceSecuritySnapshot): DeterministicDeviceSecurityBackend {
    const backend = new DeterministicDeviceSecurityBackend({
      credential: snapshot.credential,
      initialConnection: snapshot.connection,
      keyRings: snapshot.keyRings,
    });
    if (snapshot.replayLedger) backend.#ledger = DeterministicReplayLedger.restart(snapshot.replayLedger);
    const replayRows = snapshot.replayRows ?? snapshot.pendingRows;
    const claimIds = new Set<string>();
    for (const pending of replayRows) {
      if (claimIds.has(pending.claim.claimId)) throw new Error("INTEGRITY_FAILED");
      claimIds.add(pending.claim.claimId);
      backend.#restorePending(pending);
    }
    if (snapshot.replayLedger) {
      const expected = new Set(snapshot.replayLedger.rows.map((row) => row.claim.claimId));
      const restored = new Set(replayRows.map((row) => row.claim.claimId));
      if (expected.size !== restored.size || [...expected].some((claimId) => !restored.has(claimId))) {
        throw new Error("INTEGRITY_FAILED");
      }
    }
    return backend;
  }

  #restorePending(pending: DeterministicDevicePendingSnapshot): void {
    const parsed = parseCanonicalJson(pending.rawWire);
    if (!isRecord(parsed) || !isRecord(parsed.header) || !isRecord(parsed.payload) || typeof parsed.signature !== "string") {
      throw new Error("INTEGRITY_FAILED");
    }
    const entry = registry.messages.find((candidate) => candidate.message_type === pending.claim.messageType);
    if (!entry || sha256B64Url(pending.rawWire) !== pending.claim.envelopeDigest) throw new Error("INTEGRITY_FAILED");
    validateSchema(`urn:open-android-intelligence:protocol:v1:envelope:${pending.claim.messageType}`, parsed);
    const lease = this.#fence.restoreCurrentLease();
    const context: Extract<AuthenticatedBindingContext, { kind: "device" }> = deepFreeze({
      kind: "device",
      ...pending.context,
      [authenticatedBindingContextBrand]: true as const,
    });
    contextOwners.set(context, this.#token);
    const envelope: VerifiedSignedEnvelope<Task5MessageType> = deepFreeze({
      rawWire: retainedBytes(pending.rawWire),
      messageType: pending.claim.messageType,
      header: parsed.header,
      payload: parsed.payload,
      registryEntry: entry,
      signerRole: signerRoleForDirection(entry.direction),
      envelopeDigest: pending.claim.envelopeDigest,
      [verifiedSignedEnvelopeBrand]: true as const,
    });
    const retainedCharge = pending.retainedBytes
      ?? pending.rawWire.byteLength + pending.intentMetadataBytes.byteLength + TASK5_RECEIPT_BYTE_BUDGET;
    let claim = this.#ledger.findClaim(pending.claim.messageId);
    if (claim === null) {
      claim = this.#ledger.restorePending(pending.claim, retainedCharge);
      if (pending.status === "finalized" && pending.receipt) {
        if (this.#ledger.finalize(claim, retainedBytes(pending.receipt)) === "rejected") throw new Error("INTEGRITY_FAILED");
      } else if (pending.status === "abandoned" && this.#ledger.abandon(claim) === "rejected") {
        throw new Error("INTEGRITY_FAILED");
      }
    }
    if (claim.claimId !== pending.claim.claimId
      || claim.envelopeDigest !== pending.claim.envelopeDigest
      || claim.sequence !== pending.claim.sequence
      || claim.messageType !== pending.claim.messageType
      || claim.space.kind !== pending.claim.space.kind) throw new Error("INTEGRITY_FAILED");
    const leasePersistenceId = this.#fence.persistenceId(lease);
    if (leasePersistenceId === null) throw new Error("INTEGRITY_FAILED");
    const rebuiltMetadata = buildDeterministicDeviceReplayMetadata({
      claim,
      registryIdentity: replayRegistryIdentityFor(pending.claim.messageType, entry),
      bindingSnapshot: context,
      connectionLease: lease,
      connectionLeasePersistenceId: leasePersistenceId,
      admittedAt: pending.admittedAt,
    });
    const rebuiltMetadataBytes = canonicalReplayIntentMetadataBytes(rebuiltMetadata);
    const persistedMetadataBytes = canonicalReplayIntentMetadataBytes(pending.persistedMetadata);
    if (!equalBytes(rebuiltMetadataBytes, pending.intentMetadataBytes)
      || !equalBytes(persistedMetadataBytes, pending.intentMetadataBytes)) {
      throw new Error("INTEGRITY_FAILED");
    }
    const frame: Extract<AcceptedTransportFrame<Task5MessageType>, { context: { kind: "device" } }> = deepFreeze({
      envelope, context, claim, connectionLease: lease, [acceptedTransportFrameBrand]: true as const,
    });
    const trusted: Extract<LoadedTrustedBinding, { kind: "device" }> = deepFreeze({
      kind: "device",
      transport: pending.transport,
      transportProfileId: pending.transportProfileId,
      connectionId: pending.connectionId,
      allocatedConnectionGeneration: pending.context.connectionGeneration,
      connectionLease: lease,
      credential: this.#credential,
      [loadedTrustedBindingBrand]: true as const,
    });
    this.#leaseBindings.set(lease, trusted);
    this.#frames.set(claim, frame);
    const row = this.#ledger.row(claim);
    if (!row || (pending.status !== undefined && row.status !== pending.status)
      || (pending.receipt !== undefined && ((row.receipt === null) !== (pending.receipt === null)
        || (row.receipt !== null && pending.receipt !== null && !equalBytes(row.receipt, pending.receipt))))) {
      throw new Error("INTEGRITY_FAILED");
    }
    this.#pendingFrames.set(claim.claimId, frame);
    this.#admittedAt.set(claim.claimId, pending.admittedAt);
  }

  snapshot(): DeterministicDeviceSecuritySnapshot {
    const replayRows = [...this.#pendingFrames.values()].map((frame): DeterministicDevicePendingSnapshot => {
      const trusted = this.#leaseBindings.get(frame.connectionLease);
      if (!trusted) throw new Error("INTEGRITY_FAILED");
      const admittedAt = this.#admittedAt.get(frame.claim.claimId);
      const leasePersistenceId = this.#fence.persistenceId(frame.connectionLease);
      if (!admittedAt || !leasePersistenceId) throw new Error("INTEGRITY_FAILED");
      const persistedMetadata = buildDeterministicDeviceReplayMetadata({
        claim: frame.claim,
        registryIdentity: replayRegistryIdentityFor(frame.envelope.messageType, frame.envelope.registryEntry),
        bindingSnapshot: frame.context,
        connectionLease: frame.connectionLease,
        connectionLeasePersistenceId: leasePersistenceId,
        admittedAt,
      });
      const ledgerRow = this.#ledger.row(frame.claim);
      if (!ledgerRow) throw new Error("INTEGRITY_FAILED");
      return Object.freeze({
        rawWire: frame.envelope.rawWire.copy(),
        admittedAt,
        transport: trusted.transport,
        transportProfileId: trusted.transportProfileId,
        connectionId: trusted.connectionId,
        context: Object.freeze({
          credentialId: frame.context.credentialId,
          tenantId: frame.context.tenantId,
          humanPrincipalId: frame.context.humanPrincipalId,
          deviceId: frame.context.deviceId,
          pairingGeneration: frame.context.pairingGeneration,
          connectionGeneration: frame.context.connectionGeneration,
          direction: frame.context.direction,
        }),
        claim: Object.freeze({
          claimId: frame.claim.claimId,
          space: Object.freeze({ ...frame.claim.space }),
          messageType: frame.claim.messageType,
          messageId: frame.claim.messageId,
          sequence: frame.claim.sequence,
          envelopeDigest: frame.claim.envelopeDigest,
          expiresAt: frame.claim.expiresAt,
          retentionUntil: frame.claim.retentionUntil,
        }),
        persistedMetadata,
        intentMetadataBytes: canonicalReplayIntentMetadataBytes(persistedMetadata),
        status: ledgerRow.status,
        receipt: ledgerRow.receipt ? Uint8Array.from(ledgerRow.receipt) : null,
        retainedBytes: ledgerRow.retainedBytes,
      });
    });
    return Object.freeze({
      credential: this.#credential,
      connection: this.#fence.snapshot(),
      keyRings: Object.freeze([...this.#rings].map(([key, ring]) => Object.freeze({ id: this.#ringIds.get(key)!, snapshot: ring }))),
      pendingRows: Object.freeze(replayRows.filter((row) => row.status === "pending")),
      replayRows: Object.freeze(replayRows),
      replayLedger: this.#ledger.snapshot(),
    });
  }

  #ringKey(id: KeyRingId): string {
    return `${id.owner}\u0000${id.credentialId}`;
  }

  async authenticateDevice(input: AuthenticateDeterministicDeviceInput): Promise<Readonly<{
    ingress: AuthenticatedIngressHandle<"device">;
    allocation: ConnectionAllocation;
  }>> {
    const allocated = await this.allocateNext({
      credentialId: this.#credential.credentialId,
      pairingGeneration: this.#credential.pairingGeneration,
    }, input.connectionId, input.transportProfileId);
    if (allocated.kind === "exhausted") throw new Error("CONNECTION_FENCED");
    const ingress: AuthenticatedIngressHandle<"device"> = Object.freeze({
      kind: "device",
      handleId: input.handleId,
      [authenticatedIngressHandleBrand]: true as const,
    });
    const trusted: Extract<LoadedTrustedBinding, { kind: "device" }> = deepFreeze({
      kind: "device",
      transport: input.transport,
      transportProfileId: input.transportProfileId,
      connectionId: input.connectionId,
      allocatedConnectionGeneration: allocated.allocation.generation,
      connectionLease: allocated.allocation.lease,
      credential: this.#credential,
      [loadedTrustedBindingBrand]: true,
    });
    ingressOwners.set(ingress, this.#token);
    bindingOwners.set(trusted, this.#token);
    this.#bindings.set(ingress, trusted);
    this.#leaseBindings.set(allocated.allocation.lease, trusted);
    return Object.freeze({ ingress, allocation: allocated.allocation });
  }

  async loadCommittedDeviceBinding(
    handle: AuthenticatedIngressHandle<"device">,
  ): Promise<Extract<LoadedTrustedBinding, { kind: "device" }>> {
    if (ingressOwners.get(handle) !== this.#token) throw new Error("AUTH_FAILED");
    const binding = this.#bindings.get(handle);
    if (!binding) throw new Error("AUTH_FAILED");
    return binding;
  }

  allocateNext(key: ConnectionFenceKey, connectionId: string, transportProfileId: string) {
    return this.#fence.allocateNext(key, connectionId, transportProfileId);
  }

  inspect(lease: ConnectionLease) {
    return this.#fence.inspect(lease);
  }

  async load(id: KeyRingId): Promise<KeyRingSnapshot> {
    const snapshot = this.#rings.get(this.#ringKey(id));
    if (!snapshot) throw new Error("AUTH_FAILED");
    return snapshot;
  }

  protected referenceOwnsContext(context: AuthenticatedBindingContext): boolean {
    return contextOwners.get(context) === this.#token;
  }

  /** Rotation recovery may inspect a claim only through the backend-owned
   * reconciler. It receives the retained frame/status, never caller-supplied
   * wire bytes or an unbranded claim. */
  protected replayClaimForReconciler(reconciler: TrustedReplayReconciler, claimId: string): Readonly<{
    frame: Extract<AcceptedTransportFrame<Task5MessageType>, { context: { kind: "device" } }>;
    status: ReplayRowStatus;
    receipt: ExactWireBytes | null;
  }> | null {
    if (reconcilerOwners.get(reconciler) !== this.#token) return null;
    const frame = this.#pendingFrames.get(claimId);
    if (!frame) return null;
    const row = this.#ledger.row(frame.claim);
    return row ? Object.freeze({ frame, status: row.status, receipt: row.receipt ? retainExactWireBytes(row.receipt) : null }) : null;
  }

  protected ownsTrustedReconciler(reconciler: TrustedReplayReconciler): boolean {
    return reconcilerOwners.get(reconciler) === this.#token;
  }

  protected referenceReplaceKeyRing(id: KeyRingId, snapshot: KeyRingSnapshot): void {
    this.#rings.set(this.#ringKey(id), snapshot);
  }

  diagnostics(): Readonly<{ replayLookups: bigint; replayMutations: bigint }> {
    return Object.freeze({ replayLookups: this.#replayLookups, replayMutations: this.#replayMutations });
  }

  capacityDiagnostics(space: import("./replay-window.js").ReplaySpace) {
    return this.#ledger.capacity(space);
  }

  setReplayReferenceCheck(check: (claimId: string) => boolean): void {
    this.#replayReferenceCheck = check;
  }

  async admitDevice(
    request: DeviceReplayAdmissionRequest<Task5MessageType>,
  ): Promise<StoreReplayDecision<Task5MessageType>> {
    if (contextOwners.get(request.context) !== this.#token) {
      return { kind: "rejected", error: "CONNECTION_FENCED", denial: "DEVICE_LEASE_STALE" };
    }
    const inspection = await this.#fence.inspect(request.connectionLease);
    if (inspection.kind !== "current" || inspection.generation !== request.context.connectionGeneration) {
      return { kind: "rejected", error: "CONNECTION_FENCED", denial: "DEVICE_LEASE_STALE" };
    }
    await this.#beforeReplayCommit?.();
    const commitInspection = await this.#fence.inspect(request.connectionLease);
    if (commitInspection.kind !== "current" || commitInspection.generation !== request.context.connectionGeneration) {
      return { kind: "rejected", error: "CONNECTION_FENCED", denial: "DEVICE_LEASE_STALE" };
    }
    const space = Object.freeze({
      kind: "device" as const,
      credentialId: request.context.credentialId,
      pairingGeneration: request.context.pairingGeneration,
      keyId: String(request.envelope.header.key_id),
      direction: request.context.direction,
    });
    const preview = this.#ledger.previewClaim(space, request.envelope, request.admittedAt);
    const leasePersistenceId = this.#fence.persistenceId(request.connectionLease);
    if (!preview || !leasePersistenceId) {
      return { kind: "rejected", error: "INTEGRITY_FAILED", denial: "MESSAGE_ID_CONFLICT" };
    }
    const previewMetadata = buildDeterministicDeviceReplayMetadata({
      claim: preview,
      registryIdentity: replayRegistryIdentityFor(request.envelope.messageType, request.envelope.registryEntry),
      bindingSnapshot: request.context,
      connectionLease: request.connectionLease,
      connectionLeasePersistenceId: leasePersistenceId,
      admittedAt: request.admittedAt,
    });
    const retainedByteCharge = request.envelope.rawWire.byteLength
      + canonicalReplayIntentMetadataBytes(previewMetadata).byteLength
      + TASK5_RECEIPT_BYTE_BUDGET;
    this.#replayLookups += 1n;
    const decision = this.#ledger.admit(space, request.envelope, request.admittedAt, retainedByteCharge, {
      now: new Date(request.admittedAt),
      canRemove: (claimId) => !this.#replayReferenceCheck(claimId),
    });
    if (decision.kind !== "accepted") {
      if (decision.kind === "duplicate" && decision.receipt !== null) {
        return { kind: "duplicate", cachedReceipt: decision.receipt };
      }
      return decision.kind === "duplicate"
        ? { kind: "rejected", error: "REPLAY_REJECTED", denial: "PENDING" }
        : decision.error === "INTEGRITY_FAILED"
          ? { kind: "rejected", error: "INTEGRITY_FAILED", denial: "MESSAGE_ID_CONFLICT" }
          : decision;
    }
    const frame: Extract<AcceptedTransportFrame<Task5MessageType>, { context: { kind: "device" } }> = deepFreeze({
      envelope: request.envelope,
      context: request.context,
      claim: decision.claim,
      connectionLease: request.connectionLease,
      [acceptedTransportFrameBrand]: true,
    });
    this.#replayMutations += 1n;
    this.#frames.set(decision.claim, frame);
    this.#pendingFrames.set(decision.claim.claimId, frame);
    this.#admittedAt.set(decision.claim.claimId, request.admittedAt);
    return { kind: "accepted", frame };
  }

  async finalize(claim: ReplayClaim<Task5MessageType>, receipt: ExactWireBytes) {
    const result = this.#ledger.finalize(claim, receipt);
    return result === "rejected"
      ? { kind: "rejected" as const, error: "INTEGRITY_FAILED" as const }
      : { kind: result };
  }

  createReconciler(reconcilerId: string): TrustedReplayReconciler {
    const reconciler = new DeterministicTrustedReplayReconciler(reconcilerId);
    reconcilerOwners.set(reconciler, this.#token);
    return reconciler;
  }

  async loadPending(reconciler: TrustedReplayReconciler, claimId: string) {
    if (reconcilerOwners.get(reconciler) !== this.#token) return { kind: "not_found" as const };
    const frame = this.#pendingFrames.get(claimId);
    if (frame && this.#ledger.row(frame.claim)?.status === "pending") return { kind: "pending" as const, frame };
    return { kind: "not_found" as const };
  }

  async resumePending(reconciler: TrustedReplayReconciler, claimId: string) {
    const loaded = await this.loadPending(reconciler, claimId);
    return loaded.kind === "pending"
      ? { kind: "resumed" as const, frame: loaded.frame }
      : loaded;
  }

  async abandonPending(reconciler: TrustedReplayReconciler, claimId: string) {
    if (reconcilerOwners.get(reconciler) !== this.#token) return { kind: "not_found" as const };
    const frame = this.#pendingFrames.get(claimId);
    if (!frame) return { kind: "not_found" as const };
    const result = this.#ledger.abandon(frame.claim);
    return result === "abandoned" || result === "same"
      ? { kind: result as "abandoned" | "same" }
      : { kind: "already_finalized" as const };
  }

  async compact(reconciler: TrustedReplayReconciler, space: import("./replay-window.js").ReplaySpace, clock: Clock) {
    if (reconcilerOwners.get(reconciler) !== this.#token) return { removedRows: 0n, removedRetainedBytes: 0n };
    const result = this.#ledger.compact(space, clock.wallNow(), (claimId) => !this.#replayReferenceCheck(claimId));
    for (const [claimId, frame] of this.#pendingFrames) {
      if (this.#ledger.findClaim(frame.claim.messageId) === null) this.#pendingFrames.delete(claimId);
    }
    return result;
  }

}

export interface DeterministicAdapterSecurityBackendOptions {
  readonly credential: AdapterCredentialRecord;
  readonly principal: AuthenticatedAdapterPrincipal;
  readonly keyRings: readonly Readonly<{ id: KeyRingId; snapshot: KeyRingSnapshot }>[];
  readonly beforeReplayCommit?: (() => Promise<void>) | undefined;
  readonly claimIdSource?: (() => string) | undefined;
  readonly leaseIdSource?: (() => string) | undefined;
  readonly replayReferenceCheck?: ((claimId: string) => boolean) | undefined;
  readonly initialCredentialLeasePersistenceId?: string | null;
}

export interface DeterministicAdapterPendingSnapshot {
  readonly rawWire: Uint8Array;
  readonly admittedAt: string;
  readonly connectionId: string;
  readonly context: Readonly<{
    credentialId: string; tenantId: string; humanPrincipalId: string;
    agentPrincipalId: string; agentInstanceId: string; workspaceId: string;
    adapterCredentialGeneration: bigint;
    scopeCeiling: readonly string[];
    direction: "adapter-to-bridge" | "bridge-to-adapter";
  }>;
  readonly claim: Readonly<{
    claimId: string; space: import("./replay-window.js").ReplaySpace; messageType: Task5MessageType;
    messageId: string; sequence: bigint; envelopeDigest: string; expiresAt: string; retentionUntil: string;
  }>;
  readonly persistedMetadata: PersistedReplayIntentMetadata<Task5MessageType>;
  readonly intentMetadataBytes: Uint8Array;
  readonly status?: ReplayRowStatus;
  readonly receipt?: Uint8Array | null;
  readonly retainedBytes?: number;
}

export interface DeterministicAdapterSecuritySnapshot {
  readonly credential: AdapterCredentialRecord;
  readonly principal: AuthenticatedAdapterPrincipal;
  readonly connectionId: string;
  readonly credentialLeasePersistenceId: string | null;
  readonly keyRings: readonly Readonly<{ id: KeyRingId; snapshot: KeyRingSnapshot }>[];
  readonly replayRows: readonly DeterministicAdapterPendingSnapshot[];
  readonly replayLedger?: DeterministicReplayLedgerSnapshot<Task5MessageType>;
}

export class DeterministicAdapterSecurityBackend implements AdapterReplayAdmissionStore, KeyRingStore {
  readonly [adapterAdmissionBackendBrand] = true as const;
  readonly #token = Object.freeze({});
  #credential: AdapterCredentialRecord;
  readonly #principal: AuthenticatedAdapterPrincipal;
  readonly #bindings = new WeakMap<object, Extract<LoadedTrustedBinding, { kind: "adapter" }>>();
  readonly #leases = new WeakMap<object, bigint>();
  readonly #leaseIds = new WeakMap<object, string>();
  readonly #leaseBindings = new WeakMap<object, Extract<LoadedTrustedBinding, { kind: "adapter" }>>();
  readonly #rings = new Map<string, KeyRingSnapshot>();
  readonly #ringIds = new Map<string, KeyRingId>();
  #ledger: DeterministicReplayLedger<Task5MessageType>;
  readonly #frames = new Map<string, Extract<AcceptedTransportFrame<Task5MessageType>, { context: { kind: "adapter" } }>>();
  readonly #admittedAt = new Map<string, string>();
  readonly #leaseIdSource: () => string;
  #restoredLeasePersistenceId: string | null;
  readonly #beforeReplayCommit: (() => Promise<void>) | undefined;
  #replayReferenceCheck: (claimId: string) => boolean = () => false;
  #replayLookups = 0n;
  #replayMutations = 0n;
  /**
   * Credential-generation changes are deliberately two-step.  Rotation
   * backends may enter the constructor-private transaction seam, but an
   * arbitrary caller cannot advance the authoritative generation by invoking
   * a public/protected mutator directly.  The depth guard also makes a crash
   * cut observable: no generation is changed until the owning rotation
   * journal has reached its commit point.
   */
  #credentialRotationDepth = 0;

  constructor(options: DeterministicAdapterSecurityBackendOptions) {
    const scopes = [...options.credential.scopeCeiling];
    if (!scopes.every(validScope)) throw new Error("AUTH_BINDING_MISMATCH");
    scopes.sort();
    if (scopes.some((scope, index) => index > 0 && scope === scopes[index - 1])) throw new Error("AUTH_BINDING_MISMATCH");
    this.#credential = deepFreeze({ ...options.credential, scopeCeiling: Object.freeze(scopes) });
    this.#principal = deepFreeze({ ...options.principal });
    this.#leaseIdSource = options.leaseIdSource ?? (() => randomBytes(32).toString("base64url"));
    this.#restoredLeasePersistenceId = options.initialCredentialLeasePersistenceId ?? null;
    this.#ledger = new DeterministicReplayLedger<Task5MessageType>({ claimIdSource: options.claimIdSource });
    this.#beforeReplayCommit = options.beforeReplayCommit;
    this.#replayReferenceCheck = options.replayReferenceCheck ?? (() => false);
    for (const { id, snapshot } of options.keyRings) {
      this.#rings.set(`${id.owner}\u0000${id.credentialId}`, snapshot);
      this.#ringIds.set(`${id.owner}\u0000${id.credentialId}`, Object.freeze({ ...id }));
    }
    backendOwners.set(this, { kind: "adapter", token: this.#token });
  }

  static restart(snapshot: DeterministicAdapterSecuritySnapshot): DeterministicAdapterSecurityBackend {
    const backend = new DeterministicAdapterSecurityBackend({
      credential: snapshot.credential,
      principal: snapshot.principal,
      keyRings: snapshot.keyRings,
      initialCredentialLeasePersistenceId: snapshot.credentialLeasePersistenceId,
    });
    if (snapshot.replayLedger) backend.#ledger = DeterministicReplayLedger.restart(snapshot.replayLedger);
    const claimIds = new Set<string>();
    for (const row of snapshot.replayRows) {
      if (claimIds.has(row.claim.claimId)) throw new Error("INTEGRITY_FAILED");
      claimIds.add(row.claim.claimId);
      backend.#restoreRow(row);
    }
    if (snapshot.replayLedger) {
      const expected = new Set(snapshot.replayLedger.rows.map((row) => row.claim.claimId));
      const restored = new Set(snapshot.replayRows.map((row) => row.claim.claimId));
      if (expected.size !== restored.size || [...expected].some((claimId) => !restored.has(claimId))) {
        throw new Error("INTEGRITY_FAILED");
      }
    }
    return backend;
  }

  #mintLease(persistedId?: string): AdapterCredentialLease {
    const lease: AdapterCredentialLease = Object.freeze({ [adapterCredentialLeaseBrand]: true as const });
    const id = persistedId ?? this.#leaseIdSource();
    const decoded = Buffer.from(id, "base64url");
    if (decoded.byteLength !== 32 || Buffer.from(decoded).toString("base64url") !== id) throw new Error("INTEGRITY_FAILED");
    this.#leases.set(lease, this.#credential.generation);
    this.#leaseIds.set(lease, id);
    return lease;
  }

  persistenceId(lease: AdapterCredentialLease): string | null {
    return this.#leaseIds.get(lease) ?? null;
  }

  #restoreRow(row: DeterministicAdapterPendingSnapshot): void {
    const parsed = parseCanonicalJson(row.rawWire);
    if (!isRecord(parsed) || !isRecord(parsed.header) || !isRecord(parsed.payload) || typeof parsed.signature !== "string") throw new Error("INTEGRITY_FAILED");
    const entry = registry.messages.find((candidate) => candidate.message_type === row.claim.messageType);
    if (!entry || sha256B64Url(row.rawWire) !== row.claim.envelopeDigest) throw new Error("INTEGRITY_FAILED");
    validateSchema(`urn:open-android-intelligence:protocol:v1:envelope:${row.claim.messageType}`, parsed);
    const claim = this.#ledger.findClaim(row.claim.messageId);
    if (!claim || claim.claimId !== row.claim.claimId || claim.envelopeDigest !== row.claim.envelopeDigest) throw new Error("INTEGRITY_FAILED");
    const leaseIdFromSnapshot = row.persistedMetadata.lease_ref.kind === "adapter_credential"
      ? row.persistedMetadata.lease_ref.adapter_credential_lease_id : null;
    if (!leaseIdFromSnapshot) throw new Error("INTEGRITY_FAILED");
    const lease = this.#mintLease(leaseIdFromSnapshot);
    const leaseId = this.persistenceId(lease);
    if (!leaseId) throw new Error("INTEGRITY_FAILED");
    const context: Extract<AuthenticatedBindingContext, { kind: "adapter" }> = deepFreeze({
      kind: "adapter", ...row.context, [authenticatedBindingContextBrand]: true as const,
    });
    contextOwners.set(context, this.#token);
    const envelope: VerifiedSignedEnvelope<Task5MessageType> = deepFreeze({
      rawWire: retainedBytes(row.rawWire), messageType: row.claim.messageType, header: parsed.header,
      payload: parsed.payload, registryEntry: entry, signerRole: signerRoleForDirection(entry.direction),
      envelopeDigest: row.claim.envelopeDigest, [verifiedSignedEnvelopeBrand]: true as const,
    });
    const rebuiltMetadata = buildDeterministicAdapterReplayMetadata({
      claim, registryIdentity: replayRegistryIdentityFor(row.claim.messageType, entry),
      bindingSnapshot: context, adapterCredentialLease: lease,
      adapterCredentialLeasePersistenceId: leaseId, admittedAt: row.admittedAt,
    });
    if (!equalBytes(canonicalReplayIntentMetadataBytes(rebuiltMetadata), row.intentMetadataBytes)
      || !equalBytes(canonicalReplayIntentMetadataBytes(row.persistedMetadata), row.intentMetadataBytes)) throw new Error("INTEGRITY_FAILED");
    const frame: Extract<AcceptedTransportFrame<Task5MessageType>, { context: { kind: "adapter" } }> = deepFreeze({
      envelope, context, claim, connectionLease: null, adapterCredentialLease: lease, [acceptedTransportFrameBrand]: true as const,
    });
    const trusted: Extract<LoadedTrustedBinding, { kind: "adapter" }> = deepFreeze({
      kind: "adapter", connectionId: row.connectionId, credential: this.#credential,
      principal: this.#principal, credentialLease: lease, [loadedTrustedBindingBrand]: true as const,
    });
    bindingOwners.set(trusted, this.#token);
    this.#leaseBindings.set(lease, trusted);
    this.#frames.set(claim.claimId, frame);
    this.#admittedAt.set(claim.claimId, row.admittedAt);
    const ledgerRow = this.#ledger.row(claim);
    if (!ledgerRow || (row.status !== undefined && ledgerRow.status !== row.status)
      || (row.receipt !== undefined && ((ledgerRow.receipt === null) !== (row.receipt === null)
        || (ledgerRow.receipt !== null && row.receipt !== null && !equalBytes(ledgerRow.receipt, row.receipt))))) {
      throw new Error("INTEGRITY_FAILED");
    }
  }

  snapshot(): DeterministicAdapterSecuritySnapshot {
    const replayRows = [...this.#frames.values()].map((frame): DeterministicAdapterPendingSnapshot => {
      const admittedAt = this.#admittedAt.get(frame.claim.claimId);
      const leaseId = this.persistenceId(frame.adapterCredentialLease);
      if (!admittedAt || !leaseId) throw new Error("INTEGRITY_FAILED");
      const persistedMetadata = buildDeterministicAdapterReplayMetadata({
        claim: frame.claim, registryIdentity: replayRegistryIdentityFor(frame.envelope.messageType, frame.envelope.registryEntry),
        bindingSnapshot: frame.context, adapterCredentialLease: frame.adapterCredentialLease,
        adapterCredentialLeasePersistenceId: leaseId, admittedAt,
      });
      const ledgerRow = this.#ledger.row(frame.claim);
      if (!ledgerRow) throw new Error("INTEGRITY_FAILED");
      return Object.freeze({
        rawWire: frame.envelope.rawWire.copy(), admittedAt,
        connectionId: this.#leaseBindings.get(frame.adapterCredentialLease)?.connectionId ?? "adapter-connection",
        context: Object.freeze({
          credentialId: frame.context.credentialId, tenantId: frame.context.tenantId,
          humanPrincipalId: frame.context.humanPrincipalId, agentPrincipalId: frame.context.agentPrincipalId,
          agentInstanceId: frame.context.agentInstanceId, workspaceId: frame.context.workspaceId,
          adapterCredentialGeneration: frame.context.adapterCredentialGeneration,
          scopeCeiling: [...frame.context.scopeCeiling], direction: frame.context.direction,
        }),
        claim: Object.freeze({ ...frame.claim, space: Object.freeze({ ...frame.claim.space }) }),
        persistedMetadata, intentMetadataBytes: canonicalReplayIntentMetadataBytes(persistedMetadata),
        status: ledgerRow.status, receipt: ledgerRow.receipt ? Uint8Array.from(ledgerRow.receipt) : null, retainedBytes: ledgerRow.retainedBytes,
      });
    });
    const first = replayRows[0];
    return Object.freeze({
      credential: this.#credential, principal: this.#principal,
      connectionId: first?.connectionId ?? "adapter-connection",
      credentialLeasePersistenceId: first ? first.persistedMetadata.lease_ref.adapter_credential_lease_id : null,
      keyRings: Object.freeze([...this.#rings].map(([key, snapshot]) => Object.freeze({ id: this.#ringIds.get(key)!, snapshot }))),
      replayRows: Object.freeze(replayRows), replayLedger: this.#ledger.snapshot(),
    });
  }

  async authenticateAdapter(input: Readonly<{ handleId: string; connectionId: string }>): Promise<Readonly<{
    ingress: AuthenticatedIngressHandle<"adapter">;
    credentialLease: AdapterCredentialLease;
  }>> {
    const ingress: AuthenticatedIngressHandle<"adapter"> = Object.freeze({
      kind: "adapter",
      handleId: input.handleId,
      [authenticatedIngressHandleBrand]: true as const,
    });
    const lease = this.#mintLease(this.#restoredLeasePersistenceId ?? undefined);
    this.#restoredLeasePersistenceId = null;
    const trusted: Extract<LoadedTrustedBinding, { kind: "adapter" }> = deepFreeze({
      kind: "adapter",
      connectionId: input.connectionId,
      credential: this.#credential,
      principal: this.#principal,
      credentialLease: lease,
      [loadedTrustedBindingBrand]: true as const,
    });
    ingressOwners.set(ingress, this.#token);
    bindingOwners.set(trusted, this.#token);
    this.#bindings.set(ingress, trusted);
    this.#leaseBindings.set(lease, trusted);
    return Object.freeze({ ingress, credentialLease: lease });
  }

  async loadCommittedAdapterBinding(handle: AuthenticatedIngressHandle<"adapter">) {
    if (ingressOwners.get(handle) !== this.#token) throw new Error("AUTH_FAILED");
    const binding = this.#bindings.get(handle);
    if (!binding) throw new Error("AUTH_FAILED");
    return binding;
  }

  async inspectAdapterLease(lease: AdapterCredentialLease): Promise<AdapterCredentialLeaseInspection> {
    return this.#leases.get(lease) === this.#credential.generation
      ? { kind: "current", generation: this.#credential.generation }
      : { kind: "fenced" };
  }

  async load(id: KeyRingId): Promise<KeyRingSnapshot> {
    if (id.owner !== "adapter" || id.credentialId !== this.#credential.credentialId) throw new Error("AUTH_FAILED");
    const snapshot = this.#rings.get(`${id.owner}\u0000${id.credentialId}`);
    if (!snapshot) throw new Error("AUTH_FAILED");
    return snapshot;
  }

  /** Rotation backends extend this reference implementation and must use the
   * same authenticated context and ring tables as adapter admission. */
  protected referenceOwnsContext(context: AuthenticatedBindingContext): boolean {
    return contextOwners.get(context) === this.#token;
  }

  protected referenceReplaceKeyRing(id: KeyRingId, snapshot: KeyRingSnapshot): void {
    if (id.owner !== "adapter" || id.credentialId !== this.#credential.credentialId) throw new Error("AUTH_FAILED");
    this.#rings.set(`${id.owner}\u0000${id.credentialId}`, snapshot);
  }

  protected replayClaimForReconciler(reconciler: TrustedReplayReconciler, claimId: string): Readonly<{
    frame: Extract<AcceptedTransportFrame<Task5MessageType>, { context: { kind: "adapter" } }>;
    status: ReplayRowStatus;
    receipt: ExactWireBytes | null;
  }> | null {
    if (reconcilerOwners.get(reconciler) !== this.#token) return null;
    const frame = this.#frames.get(claimId);
    if (!frame) return null;
    const row = this.#ledger.row(frame.claim);
    return row ? Object.freeze({ frame, status: row.status, receipt: row.receipt ? retainExactWireBytes(row.receipt) : null }) : null;
  }

  protected ownsTrustedReconciler(reconciler: TrustedReplayReconciler): boolean {
    return reconcilerOwners.get(reconciler) === this.#token;
  }

  diagnostics() {
    return Object.freeze({ replayLookups: this.#replayLookups, replayMutations: this.#replayMutations });
  }

  async admitAdapter(request: AdapterReplayAdmissionRequest<Task5MessageType>): Promise<StoreReplayDecision<Task5MessageType>> {
    if (contextOwners.get(request.context) !== this.#token
      || (await this.inspectAdapterLease(request.adapterCredentialLease)).kind !== "current") {
      return { kind: "rejected", error: "CONNECTION_FENCED", denial: "ADAPTER_LEASE_STALE" };
    }
    await this.#beforeReplayCommit?.();
    const commitLease = await this.inspectAdapterLease(request.adapterCredentialLease);
    if (commitLease.kind !== "current" || commitLease.generation !== request.context.adapterCredentialGeneration) {
      return { kind: "rejected", error: "CONNECTION_FENCED", denial: "ADAPTER_LEASE_STALE" };
    }
    const space = Object.freeze({
      kind: "adapter" as const,
      credentialId: request.context.credentialId,
      adapterCredentialGeneration: request.context.adapterCredentialGeneration,
      keyId: String(request.envelope.header.key_id),
      direction: request.context.direction,
    });
    const preview = this.#ledger.previewClaim(space, request.envelope, request.admittedAt);
    const leaseId = this.persistenceId(request.adapterCredentialLease);
    if (!preview || !leaseId) return { kind: "rejected", error: "INTEGRITY_FAILED", denial: "MESSAGE_ID_CONFLICT" };
    const previewMetadata = buildDeterministicAdapterReplayMetadata({
      claim: preview, registryIdentity: replayRegistryIdentityFor(request.envelope.messageType, request.envelope.registryEntry),
      bindingSnapshot: request.context, adapterCredentialLease: request.adapterCredentialLease,
      adapterCredentialLeasePersistenceId: leaseId, admittedAt: request.admittedAt,
    });
    const retainedByteCharge = request.envelope.rawWire.byteLength
      + canonicalReplayIntentMetadataBytes(previewMetadata).byteLength + TASK5_RECEIPT_BYTE_BUDGET;
    this.#replayLookups += 1n;
    const decision = this.#ledger.admit(space, request.envelope, request.admittedAt, retainedByteCharge, {
      now: new Date(request.admittedAt),
      canRemove: (claimId) => !this.#replayReferenceCheck(claimId),
    });
    if (decision.kind !== "accepted") {
      if (decision.kind === "duplicate" && decision.receipt !== null) return { kind: "duplicate", cachedReceipt: decision.receipt };
      return decision.kind === "duplicate"
        ? { kind: "rejected", error: "REPLAY_REJECTED", denial: "PENDING" }
        : decision.error === "INTEGRITY_FAILED"
          ? { kind: "rejected", error: "INTEGRITY_FAILED", denial: "MESSAGE_ID_CONFLICT" }
          : decision;
    }
    const frame: Extract<AcceptedTransportFrame<Task5MessageType>, { context: { kind: "adapter" } }> = deepFreeze({
      envelope: request.envelope,
      context: request.context,
      claim: decision.claim,
      connectionLease: null,
      adapterCredentialLease: request.adapterCredentialLease,
      [acceptedTransportFrameBrand]: true as const,
    });
    this.#frames.set(decision.claim.claimId, frame);
    this.#admittedAt.set(decision.claim.claimId, request.admittedAt);
    this.#replayMutations += 1n;
    return { kind: "accepted", frame };
  }

  protected runCredentialRotationTransaction<T>(operation: () => T): T {
    if (this.#credentialRotationDepth !== 0) throw new Error("INVALID_STATE_TRANSITION");
    this.#credentialRotationDepth = 1;
    try {
      return operation();
    } finally {
      this.#credentialRotationDepth = 0;
    }
  }

  protected commitCredentialGeneration(nextGeneration: bigint): void {
    if (this.#credentialRotationDepth !== 1) throw new Error("AUTH_FAILED");
    if (nextGeneration !== this.#credential.generation + 1n) throw new Error("INVALID_STATE_TRANSITION");
    this.#credential = deepFreeze({ ...this.#credential, generation: nextGeneration });
  }

  /** Read-only view for a branch-owned rotation recovery transaction. */
  protected currentCredentialGeneration(): bigint {
    return this.#credential.generation;
  }

  capacityDiagnostics(space: import("./replay-window.js").ReplaySpace) {
    return this.#ledger.capacity(space);
  }

  setReplayReferenceCheck(check: (claimId: string) => boolean): void {
    this.#replayReferenceCheck = check;
  }

  async finalize(claim: ReplayClaim<Task5MessageType>, receipt: ExactWireBytes) {
    const result = this.#ledger.finalize(claim, receipt);
    return result === "rejected"
      ? { kind: "rejected" as const, error: "INTEGRITY_FAILED" as const }
      : { kind: result };
  }
  createReconciler(reconcilerId: string): TrustedReplayReconciler {
    const reconciler = new DeterministicTrustedReplayReconciler(reconcilerId);
    reconcilerOwners.set(reconciler, this.#token);
    return reconciler;
  }

  async loadPending(reconciler: TrustedReplayReconciler, claimId: string) {
    if (reconcilerOwners.get(reconciler) !== this.#token) return { kind: "not_found" as const };
    const frame = this.#frames.get(claimId);
    return frame && this.#ledger.row(frame.claim)?.status === "pending"
      ? { kind: "pending" as const, frame } : { kind: "not_found" as const };
  }

  async resumePending(reconciler: TrustedReplayReconciler, claimId: string) {
    const loaded = await this.loadPending(reconciler, claimId);
    return loaded.kind === "pending" ? { kind: "resumed" as const, frame: loaded.frame } : loaded;
  }

  async abandonPending(reconciler: TrustedReplayReconciler, claimId: string) {
    if (reconcilerOwners.get(reconciler) !== this.#token) return { kind: "not_found" as const };
    const frame = this.#frames.get(claimId);
    if (!frame) return { kind: "not_found" as const };
    const result = this.#ledger.abandon(frame.claim);
    return result === "abandoned" || result === "same"
      ? { kind: result as "abandoned" | "same" } : { kind: "already_finalized" as const };
  }

  async compact(reconciler: TrustedReplayReconciler, space: import("./replay-window.js").ReplaySpace, clock: Clock) {
    if (reconcilerOwners.get(reconciler) !== this.#token) return { removedRows: 0n, removedRetainedBytes: 0n };
    const result = this.#ledger.compact(space, clock.wallNow(), (claimId) => !this.#replayReferenceCheck(claimId));
    for (const [claimId, frame] of this.#frames) if (!this.#ledger.findClaim(frame.claim.messageId)) this.#frames.delete(claimId);
    return result;
  }
}

export type { ConnectionLease, ConnectionLeaseInspection, KeyRingStore };
export type {
  AdapterReplayAdmissionRequest,
  DeviceReplayAdmissionRequest,
  ReplayClaim,
  ReplayStore,
  StoreReplayDecision,
} from "./replay-window.js";
export { allocateConnectionGeneration, fenceConnection } from "./connection-fence.js";
