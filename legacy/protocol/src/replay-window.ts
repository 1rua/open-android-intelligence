import { canonicalBytes, sha256B64Url } from "./encoding.js";
import { randomBytes } from "node:crypto";
import replayPoliciesFixture from "../registries/v1/replay-policies.json" with { type: "json" };
import { loadMessageRegistry, type MessageRegistryEntry } from "./message-registry.js";
import type { Clock, SignerRole } from "./ports.js";
import type { SignatureDomain } from "./profile.js";
import type { ConnectionLease } from "./connection-fence.js";
import type {
  AcceptedTransportFrame,
  AdapterCredentialLease,
  AuthenticatedBindingContext,
  ExactWireBytes,
  Task5MessageType,
  VerifiedSignedEnvelope,
} from "./control-envelope.js";
import { retainExactWireBytes } from "./control-envelope.js";

const UINT64_MAX = 18_446_744_073_709_551_615n;
const WINDOW_SIZE = 1024n;
const WINDOW_MASK = (1n << WINDOW_SIZE) - 1n;

const deepFreeze = <T>(value: T): T => {
  if (typeof value === "object" && value !== null && !Object.isFrozen(value)) {
    for (const member of Object.values(value)) deepFreeze(member);
    Object.freeze(value);
  }
  return value;
};

export interface ReplayWindowState {
  readonly highestSeen: bigint | null;
  readonly seenBitmap: bigint;
}

export type ReplayDecision =
  | { kind: "accept"; next: ReplayWindowState }
  | { kind: "reject"; error: "REPLAY_REJECTED" };

export function acceptSequence(state: ReplayWindowState, sequence: bigint): ReplayDecision {
  if (sequence < 0n || sequence > UINT64_MAX) return { kind: "reject", error: "REPLAY_REJECTED" };
  if (state.highestSeen === null) {
    return { kind: "accept", next: { highestSeen: sequence, seenBitmap: 1n } };
  }
  if (sequence > state.highestSeen) {
    const delta = sequence - state.highestSeen;
    const shifted = delta >= WINDOW_SIZE ? 0n : (state.seenBitmap << BigInt(Number(delta))) & WINDOW_MASK;
    return { kind: "accept", next: { highestSeen: sequence, seenBitmap: shifted | 1n } };
  }
  const offset = state.highestSeen - sequence;
  if (offset >= WINDOW_SIZE) return { kind: "reject", error: "REPLAY_REJECTED" };
  const bit = 1n << offset;
  if ((state.seenBitmap & bit) !== 0n) return { kind: "reject", error: "REPLAY_REJECTED" };
  return {
    kind: "accept",
    next: { highestSeen: state.highestSeen, seenBitmap: (state.seenBitmap | bit) & WINDOW_MASK },
  };
}

export type ReplaySpace =
  | { kind: "device"; credentialId: string; pairingGeneration: bigint; keyId: string; direction: "app-to-bridge" | "bridge-to-app" }
  | { kind: "adapter"; credentialId: string; adapterCredentialGeneration: bigint; keyId: string; direction: "adapter-to-bridge" | "bridge-to-adapter" };

export type ReplayRowStatus = "pending" | "finalized" | "abandoned";

const lockedReplayPolicyBrand: unique symbol = Symbol("locked-replay-policy");
export const REPLAY_POLICY_LITERALS = Object.freeze({
  task5Default: Object.freeze({
    class_id: "task5_default",
    retention_rule_id: "retain_until_max_expires_at_or_admitted_at_plus_86400_seconds_v1",
  } as const),
  operationSecurityLedger: Object.freeze({
    class_id: "operation_security_ledger",
    retention_rule_id: "retain_until_max_operation_expires_at_or_bridge_ack_at_plus_2592000_seconds_v1",
  } as const),
} as const);

export type PersistedReplayPolicy =
  | typeof REPLAY_POLICY_LITERALS.task5Default
  | typeof REPLAY_POLICY_LITERALS.operationSecurityLedger;

export const LOCKED_REPLAY_POLICY_DESCRIPTORS = Object.freeze({
  task5Default: Object.freeze({
    classId: REPLAY_POLICY_LITERALS.task5Default.class_id,
    receiptReservationBytes: 16384,
    retentionRuleId: REPLAY_POLICY_LITERALS.task5Default.retention_rule_id,
    intentMetadataCeilingBytes: null,
    tombstoneMetadataCeilingBytes: null,
  } as const),
  operationSecurityLedger: Object.freeze({
    classId: REPLAY_POLICY_LITERALS.operationSecurityLedger.class_id,
    receiptReservationBytes: 262144,
    retentionRuleId: REPLAY_POLICY_LITERALS.operationSecurityLedger.retention_rule_id,
    intentMetadataCeilingBytes: 65536,
    tombstoneMetadataCeilingBytes: 2048,
  } as const),
} as const);

type LockedReplayPolicyDescriptor =
  | typeof LOCKED_REPLAY_POLICY_DESCRIPTORS.task5Default
  | typeof LOCKED_REPLAY_POLICY_DESCRIPTORS.operationSecurityLedger;

export type LockedReplayPolicy = Readonly<LockedReplayPolicyDescriptor & {
  readonly [lockedReplayPolicyBrand]: true;
}>;

const TASK5_DEFAULT_POLICY: LockedReplayPolicy = Object.freeze({
  ...LOCKED_REPLAY_POLICY_DESCRIPTORS.task5Default,
  [lockedReplayPolicyBrand]: true as const,
});
const OPERATION_SECURITY_LEDGER_POLICY: LockedReplayPolicy = Object.freeze({
  ...LOCKED_REPLAY_POLICY_DESCRIPTORS.operationSecurityLedger,
  [lockedReplayPolicyBrand]: true as const,
});

const replayClaimBrand: unique symbol = Symbol("replay-claim");
export type ReplayClaim<TMessageType extends string = Task5MessageType> = Readonly<{
  readonly claimId: string;
  readonly space: ReplaySpace;
  readonly messageType: TMessageType;
  readonly messageId: string;
  readonly sequence: bigint;
  readonly envelopeDigest: string;
  readonly expiresAt: string;
  readonly retentionUntil: string;
  readonly replayPolicy: LockedReplayPolicy;
  readonly [replayClaimBrand]: true;
}>;

const replayClaimReferenceBrand: unique symbol = Symbol("replay-claim-reference");
/**
 * A minimal, non-reconstructible association that lets a downstream atomic
 * reducer retain the exact replay row which authorized a side effect.  The
 * full claim is deliberately not projected into the durable grant state.
 */
export type ReplayClaimReference<TMessageType extends string = Task5MessageType> = Readonly<{
  readonly claimId: string;
  readonly messageId: string;
  readonly messageType: TMessageType;
  readonly envelopeDigest: string;
  readonly [replayClaimReferenceBrand]: true;
}>;

export interface LockedReplayRegistryIdentity<TMessageType extends string = Task5MessageType> {
  readonly messageType: TMessageType;
  readonly messageSchemaId: string;
  readonly headerSchemaId: string;
  readonly envelopeSchemaId: string;
  readonly direction: ReplaySpace["direction"];
  readonly signatureDomain: SignatureDomain;
  readonly signerRole: SignerRole;
}

export type PersistedReplayBindingSnapshot =
  | Readonly<{
      adapter_credential_generation: null;
      agent_instance_id: null;
      agent_principal_id: null;
      connection_generation: string;
      credential_id: string;
      device_id: string;
      direction: "app-to-bridge" | "bridge-to-app";
      human_principal_id: string;
      kind: "device";
      pairing_generation: string;
      scope_ceiling: null;
      tenant_id: string;
      workspace_id: null;
    }>
  | Readonly<{
      adapter_credential_generation: string;
      agent_instance_id: string;
      agent_principal_id: string;
      connection_generation: null;
      credential_id: string;
      device_id: null;
      direction: "adapter-to-bridge" | "bridge-to-adapter";
      human_principal_id: string;
      kind: "adapter";
      pairing_generation: null;
      scope_ceiling: readonly string[];
      tenant_id: string;
      workspace_id: string;
    }>;

export type PersistedReplayLeaseRef =
  | Readonly<{ adapter_credential_lease_id: null; connection_lease_id: string; kind: "device_connection" }>
  | Readonly<{ adapter_credential_lease_id: string; connection_lease_id: null; kind: "adapter_credential" }>;

declare const replayLeasePersistenceIdBrand: unique symbol;
export type ReplayLeasePersistenceId = string & { readonly [replayLeasePersistenceIdBrand]: true };

export type PersistedReplayRegistryIdentity<TMessageType extends string = Task5MessageType> = Readonly<{
  direction: ReplaySpace["direction"];
  envelope_schema_id: string;
  header_schema_id: string;
  message_schema_id: string;
  message_type: TMessageType;
  signature_domain: SignatureDomain;
  signer_role: SignerRole;
}>;

export type PersistedReplaySpace =
  | Readonly<{ adapter_credential_generation: null; credential_id: string; direction: "app-to-bridge" | "bridge-to-app"; key_id: string; kind: "device"; pairing_generation: string }>
  | Readonly<{ adapter_credential_generation: string; credential_id: string; direction: "adapter-to-bridge" | "bridge-to-adapter"; key_id: string; kind: "adapter"; pairing_generation: null }>;

type PersistedReplayIntentMetadataBase<TMessageType extends string> = Readonly<{
  admitted_at: string;
  claim_id: string;
  registry_identity: PersistedReplayRegistryIdentity<TMessageType>;
  replay_policy: PersistedReplayPolicy;
  retention_until: string;
}>;

export type PersistedReplayIntentMetadata<TMessageType extends string = Task5MessageType> = Readonly<
  PersistedReplayIntentMetadataBase<TMessageType> & (
    | { binding_snapshot: Extract<PersistedReplayBindingSnapshot, { kind: "device" }>; lease_ref: Extract<PersistedReplayLeaseRef, { kind: "device_connection" }>; space: Extract<PersistedReplaySpace, { kind: "device" }> }
    | { binding_snapshot: Extract<PersistedReplayBindingSnapshot, { kind: "adapter" }>; lease_ref: Extract<PersistedReplayLeaseRef, { kind: "adapter_credential" }>; space: Extract<PersistedReplaySpace, { kind: "adapter" }> }
  )
>;

const replayIntentMetadataAuthorityBrand: unique symbol = Symbol("replay-intent-metadata-authority");
export type ReplayIntentMetadataAuthority<TMessageType extends string = Task5MessageType> = Readonly<{
  admittedAt: string;
  claimId: string;
  registryIdentity: LockedReplayRegistryIdentity<TMessageType>;
  replayPolicy: LockedReplayPolicy;
  retentionUntil: string;
  readonly [replayIntentMetadataAuthorityBrand]: true;
} & (
  | { adapterCredentialLease: null; adapterCredentialLeasePersistenceId: null; bindingSnapshot: Extract<AuthenticatedBindingContext, { kind: "device" }>; connectionLease: ConnectionLease; connectionLeasePersistenceId: ReplayLeasePersistenceId; space: Extract<ReplaySpace, { kind: "device" }> }
  | { adapterCredentialLease: AdapterCredentialLease; adapterCredentialLeasePersistenceId: ReplayLeasePersistenceId; bindingSnapshot: Extract<AuthenticatedBindingContext, { kind: "adapter" }>; connectionLease: null; connectionLeasePersistenceId: null; space: Extract<ReplaySpace, { kind: "adapter" }> }
)>;

const persistedPolicy = (policy: LockedReplayPolicy): PersistedReplayPolicy => {
  if (policy.classId === LOCKED_REPLAY_POLICY_DESCRIPTORS.task5Default.classId
    && policy.retentionRuleId === LOCKED_REPLAY_POLICY_DESCRIPTORS.task5Default.retentionRuleId) {
    return REPLAY_POLICY_LITERALS.task5Default;
  }
  if (policy.classId === LOCKED_REPLAY_POLICY_DESCRIPTORS.operationSecurityLedger.classId
    && policy.retentionRuleId === LOCKED_REPLAY_POLICY_DESCRIPTORS.operationSecurityLedger.retentionRuleId) {
    return REPLAY_POLICY_LITERALS.operationSecurityLedger;
  }
  throw new Error("INVALID_REPLAY_INTENT_METADATA");
};

const registryProjection = <TMessageType extends string>(identity: LockedReplayRegistryIdentity<TMessageType>): PersistedReplayRegistryIdentity<TMessageType> => ({
  direction: identity.direction,
  envelope_schema_id: identity.envelopeSchemaId,
  header_schema_id: identity.headerSchemaId,
  message_schema_id: identity.messageSchemaId,
  message_type: identity.messageType,
  signature_domain: identity.signatureDomain,
  signer_role: identity.signerRole,
});

export function projectPersistedReplayIntentMetadata<TMessageType extends string>(
  authority: ReplayIntentMetadataAuthority<TMessageType>,
): PersistedReplayIntentMetadata<TMessageType> {
  const binding = authority.bindingSnapshot;
  if (binding.direction !== authority.registryIdentity.direction
    || binding.direction !== authority.space.direction
    || binding.credentialId !== authority.space.credentialId) {
    throw new Error("INVALID_REPLAY_INTENT_METADATA");
  }
  const common = {
    admitted_at: authority.admittedAt,
    claim_id: authority.claimId,
    registry_identity: registryProjection(authority.registryIdentity),
    replay_policy: persistedPolicy(authority.replayPolicy),
    retention_until: authority.retentionUntil,
  };
  if (binding.kind === "device" && authority.space.kind === "device"
    && authority.connectionLeasePersistenceId !== null
    && binding.pairingGeneration === authority.space.pairingGeneration) {
    return deepFreeze({
      ...common,
      binding_snapshot: {
        adapter_credential_generation: null,
        agent_instance_id: null,
        agent_principal_id: null,
        connection_generation: binding.connectionGeneration.toString(10),
        credential_id: binding.credentialId,
        device_id: binding.deviceId,
        direction: binding.direction,
        human_principal_id: binding.humanPrincipalId,
        kind: "device" as const,
        pairing_generation: binding.pairingGeneration.toString(10),
        scope_ceiling: null,
        tenant_id: binding.tenantId,
        workspace_id: null,
      },
      lease_ref: {
        adapter_credential_lease_id: null,
        connection_lease_id: authority.connectionLeasePersistenceId,
        kind: "device_connection" as const,
      },
      space: {
        adapter_credential_generation: null,
        credential_id: authority.space.credentialId,
        direction: authority.space.direction,
        key_id: authority.space.keyId,
        kind: "device" as const,
        pairing_generation: authority.space.pairingGeneration.toString(10),
      },
    });
  }
  if (binding.kind === "adapter" && authority.space.kind === "adapter"
    && authority.adapterCredentialLeasePersistenceId !== null
    && binding.adapterCredentialGeneration === authority.space.adapterCredentialGeneration) {
    return deepFreeze({
      ...common,
      binding_snapshot: {
        adapter_credential_generation: binding.adapterCredentialGeneration.toString(10),
        agent_instance_id: binding.agentInstanceId,
        agent_principal_id: binding.agentPrincipalId,
        connection_generation: null,
        credential_id: binding.credentialId,
        device_id: null,
        direction: binding.direction,
        human_principal_id: binding.humanPrincipalId,
        kind: "adapter" as const,
        pairing_generation: null,
        scope_ceiling: [...binding.scopeCeiling],
        tenant_id: binding.tenantId,
        workspace_id: binding.workspaceId,
      },
      lease_ref: {
        adapter_credential_lease_id: authority.adapterCredentialLeasePersistenceId,
        connection_lease_id: null,
        kind: "adapter_credential" as const,
      },
      space: {
        adapter_credential_generation: authority.space.adapterCredentialGeneration.toString(10),
        credential_id: authority.space.credentialId,
        direction: authority.space.direction,
        key_id: authority.space.keyId,
        kind: "adapter" as const,
        pairing_generation: null,
      },
    });
  }
  throw new Error("INVALID_REPLAY_INTENT_METADATA");
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};
const isString = (value: unknown): value is string => typeof value === "string";
const isU64 = (value: unknown): value is string =>
  isString(value) && /^(0|[1-9][0-9]*)$/.test(value) && BigInt(value) <= UINT64_MAX;
const isTimestamp = (value: unknown): value is string => {
  if (!isString(value) || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)) return false;
  const time = Date.parse(value);
  return Number.isFinite(time) && new Date(time).toISOString() === value;
};
const isLeaseId = (value: unknown): value is string =>
  isString(value) && /^[A-Za-z0-9_-]{43}$/.test(value)
  && Buffer.from(value, "base64url").byteLength === 32
  && Buffer.from(value, "base64url").toString("base64url") === value;
const isOpaqueId = (value: unknown): value is string =>
  isString(value) && /^[A-Za-z0-9._~-]{1,128}$/.test(value);
type ReplayClassification = Readonly<{
  message_type: string;
  class_id: PersistedReplayPolicy["class_id"];
  retention_rule_id: PersistedReplayPolicy["retention_rule_id"];
}>;
const replayClassifications = new Map<string, ReplayClassification>();
for (const row of replayPoliciesFixture.message_classification as readonly ReplayClassification[]) {
  if (replayClassifications.has(row.message_type)) throw new Error("INVALID_REPLAY_POLICY_REGISTRY");
  replayClassifications.set(row.message_type, row);
}
const replayMessageRegistry = new Map(loadMessageRegistry().messages
  .filter((entry) => replayClassifications.has(entry.message_type))
  .map((entry) => [entry.message_type, entry]));
const lockedPolicyForMessageType = (messageType: string): LockedReplayPolicy | null => {
  const classification = replayClassifications.get(messageType);
  if (classification?.class_id === REPLAY_POLICY_LITERALS.task5Default.class_id
    && classification.retention_rule_id === REPLAY_POLICY_LITERALS.task5Default.retention_rule_id) {
    return TASK5_DEFAULT_POLICY;
  }
  if (classification?.class_id === REPLAY_POLICY_LITERALS.operationSecurityLedger.class_id
    && classification.retention_rule_id === REPLAY_POLICY_LITERALS.operationSecurityLedger.retention_rule_id) {
    return OPERATION_SECURITY_LEDGER_POLICY;
  }
  return null;
};
const validRegistryIdentity = (registry: Record<string, unknown>): boolean => {
  if (!Object.values(registry).every(isString)) return false;
  const row = replayMessageRegistry.get(registry.message_type as string);
  if (!row) return false;
  const signerRole = row.direction === "app-to-bridge" ? "device"
    : row.direction === "adapter-to-bridge" ? "adapter" : "bridge-command";
  return registry.direction === row.direction
    && registry.message_schema_id === row.schema_id
    && registry.header_schema_id === `urn:open-android-intelligence:protocol:v1:header:${row.message_type}`
    && registry.envelope_schema_id === `urn:open-android-intelligence:protocol:v1:envelope:${row.message_type}`
    && registry.signature_domain === row.signature_domain
    && registry.signer_role === signerRole;
};
const sortedUniqueScopes = (value: unknown): value is string[] =>
  Array.isArray(value) && value.every((scope, index) =>
    isString(scope) && /^[a-z][a-z0-9._-]{0,127}$/.test(scope)
    && (index === 0 || (value[index - 1] as string) < scope));

const validateMetadata = (metadata: unknown): metadata is PersistedReplayIntentMetadata<string> => {
  if (!isRecord(metadata) || !exactKeys(metadata, ["admitted_at", "binding_snapshot", "claim_id", "lease_ref", "registry_identity", "replay_policy", "retention_until", "space"])) return false;
  if (!isTimestamp(metadata.admitted_at) || !isTimestamp(metadata.retention_until) || !isOpaqueId(metadata.claim_id)) return false;
  const binding = metadata.binding_snapshot;
  const lease = metadata.lease_ref;
  const registry = metadata.registry_identity;
  const policy = metadata.replay_policy;
  const space = metadata.space;
  if (!isRecord(binding) || !isRecord(lease) || !isRecord(registry) || !isRecord(policy) || !isRecord(space)) return false;
  if (!exactKeys(registry, ["direction", "envelope_schema_id", "header_schema_id", "message_schema_id", "message_type", "signature_domain", "signer_role"])
    || !validRegistryIdentity(registry)) return false;
  if (!exactKeys(policy, ["class_id", "retention_rule_id"])) return false;
  const validPolicy = Object.values(REPLAY_POLICY_LITERALS).some((candidate) =>
    policy.class_id === candidate.class_id && policy.retention_rule_id === candidate.retention_rule_id);
  const classification = replayClassifications.get(registry.message_type as string);
  if (!validPolicy || !classification
    || policy.class_id !== classification.class_id || policy.retention_rule_id !== classification.retention_rule_id
    || !exactKeys(binding, ["adapter_credential_generation", "agent_instance_id", "agent_principal_id", "connection_generation", "credential_id", "device_id", "direction", "human_principal_id", "kind", "pairing_generation", "scope_ceiling", "tenant_id", "workspace_id"])
    || !exactKeys(lease, ["adapter_credential_lease_id", "connection_lease_id", "kind"])
    || !exactKeys(space, ["adapter_credential_generation", "credential_id", "direction", "key_id", "kind", "pairing_generation"])) return false;
  if (binding.kind === "device" && lease.kind === "device_connection" && space.kind === "device") {
    return binding.adapter_credential_generation === null
      && binding.agent_instance_id === null && binding.agent_principal_id === null
      && isU64(binding.connection_generation) && isOpaqueId(binding.credential_id)
      && isOpaqueId(binding.device_id) && (binding.direction === "app-to-bridge" || binding.direction === "bridge-to-app")
      && isOpaqueId(binding.human_principal_id) && isU64(binding.pairing_generation)
      && binding.scope_ceiling === null && isOpaqueId(binding.tenant_id) && binding.workspace_id === null
      && lease.adapter_credential_lease_id === null && isLeaseId(lease.connection_lease_id)
      && space.adapter_credential_generation === null && isOpaqueId(space.credential_id)
      && (space.direction === "app-to-bridge" || space.direction === "bridge-to-app")
      && isOpaqueId(space.key_id) && isU64(space.pairing_generation)
      && binding.direction === registry.direction && space.direction === registry.direction
      && binding.credential_id === space.credential_id && binding.pairing_generation === space.pairing_generation;
  }
  if (binding.kind === "adapter" && lease.kind === "adapter_credential" && space.kind === "adapter") {
    return isU64(binding.adapter_credential_generation) && isOpaqueId(binding.agent_instance_id)
      && isOpaqueId(binding.agent_principal_id) && binding.connection_generation === null
      && isOpaqueId(binding.credential_id) && binding.device_id === null
      && (binding.direction === "adapter-to-bridge" || binding.direction === "bridge-to-adapter")
      && isOpaqueId(binding.human_principal_id) && binding.pairing_generation === null
      && sortedUniqueScopes(binding.scope_ceiling)
      && isOpaqueId(binding.tenant_id) && isOpaqueId(binding.workspace_id)
      && isLeaseId(lease.adapter_credential_lease_id) && lease.connection_lease_id === null
      && isU64(space.adapter_credential_generation) && isOpaqueId(space.credential_id)
      && (space.direction === "adapter-to-bridge" || space.direction === "bridge-to-adapter")
      && isOpaqueId(space.key_id) && space.pairing_generation === null
      && binding.direction === registry.direction && space.direction === registry.direction
      && binding.credential_id === space.credential_id
      && binding.adapter_credential_generation === space.adapter_credential_generation;
  }
  return false;
};

export function canonicalReplayIntentMetadataBytes<TMessageType extends string>(
  metadata: PersistedReplayIntentMetadata<TMessageType>,
): Uint8Array {
  if (!validateMetadata(metadata)) throw new Error("INVALID_REPLAY_INTENT_METADATA");
  return canonicalBytes(metadata);
}

export function buildDeterministicDeviceReplayMetadata<TMessageType extends string>(input: Readonly<{
  claim: ReplayClaim<TMessageType>;
  registryIdentity: LockedReplayRegistryIdentity<TMessageType>;
  bindingSnapshot: Extract<AuthenticatedBindingContext, { kind: "device" }>;
  connectionLease: ConnectionLease;
  connectionLeasePersistenceId: string;
  admittedAt: string;
}>): PersistedReplayIntentMetadata<TMessageType> {
  const decoded = Buffer.from(input.connectionLeasePersistenceId, "base64url");
  if (decoded.byteLength !== 32 || Buffer.from(decoded).toString("base64url") !== input.connectionLeasePersistenceId) {
    throw new Error("INVALID_REPLAY_INTENT_METADATA");
  }
  const authority: ReplayIntentMetadataAuthority<TMessageType> = {
    admittedAt: input.admittedAt,
    claimId: input.claim.claimId,
    registryIdentity: input.registryIdentity,
    replayPolicy: input.claim.replayPolicy,
    retentionUntil: input.claim.retentionUntil,
    adapterCredentialLease: null,
    adapterCredentialLeasePersistenceId: null,
    bindingSnapshot: input.bindingSnapshot,
    connectionLease: input.connectionLease,
    connectionLeasePersistenceId: input.connectionLeasePersistenceId as ReplayLeasePersistenceId,
    space: input.claim.space.kind === "device" ? input.claim.space : (() => { throw new Error("INVALID_REPLAY_INTENT_METADATA"); })(),
    [replayIntentMetadataAuthorityBrand]: true,
  };
  return projectPersistedReplayIntentMetadata(authority);
}

export function buildDeterministicAdapterReplayMetadata<TMessageType extends string>(input: Readonly<{
  claim: ReplayClaim<TMessageType>;
  registryIdentity: LockedReplayRegistryIdentity<TMessageType>;
  bindingSnapshot: Extract<AuthenticatedBindingContext, { kind: "adapter" }>;
  adapterCredentialLease: AdapterCredentialLease;
  adapterCredentialLeasePersistenceId: string;
  admittedAt: string;
}>): PersistedReplayIntentMetadata<TMessageType> {
  const decoded = Buffer.from(input.adapterCredentialLeasePersistenceId, "base64url");
  if (decoded.byteLength !== 32 || Buffer.from(decoded).toString("base64url") !== input.adapterCredentialLeasePersistenceId) {
    throw new Error("INVALID_REPLAY_INTENT_METADATA");
  }
  if (input.claim.space.kind !== "adapter") throw new Error("INVALID_REPLAY_INTENT_METADATA");
  const authority: ReplayIntentMetadataAuthority<TMessageType> = {
    admittedAt: input.admittedAt,
    claimId: input.claim.claimId,
    registryIdentity: input.registryIdentity,
    replayPolicy: input.claim.replayPolicy,
    retentionUntil: input.claim.retentionUntil,
    adapterCredentialLease: input.adapterCredentialLease,
    adapterCredentialLeasePersistenceId: input.adapterCredentialLeasePersistenceId as ReplayLeasePersistenceId,
    bindingSnapshot: input.bindingSnapshot,
    connectionLease: null,
    connectionLeasePersistenceId: null,
    space: input.claim.space,
    [replayIntentMetadataAuthorityBrand]: true,
  };
  return projectPersistedReplayIntentMetadata(authority);
}

declare const replayRecoveryIntentBrand: unique symbol;
export type ReplayRecoveryIntent<TMessageType extends string = Task5MessageType> =
  | Readonly<{ claimId: string; rawWire: ExactWireBytes; registryIdentity: LockedReplayRegistryIdentity<TMessageType>; replayPolicy: LockedReplayPolicy; bindingSnapshot: Extract<AuthenticatedBindingContext, { kind: "device" }>; connectionLease: ConnectionLease; adapterCredentialLease: null; admittedAt: string; persistedMetadata: Extract<PersistedReplayIntentMetadata<TMessageType>, { binding_snapshot: { kind: "device" } }>; readonly [replayRecoveryIntentBrand]: true }>
  | Readonly<{ claimId: string; rawWire: ExactWireBytes; registryIdentity: LockedReplayRegistryIdentity<TMessageType>; replayPolicy: LockedReplayPolicy; bindingSnapshot: Extract<AuthenticatedBindingContext, { kind: "adapter" }>; connectionLease: null; adapterCredentialLease: AdapterCredentialLease; admittedAt: string; persistedMetadata: Extract<PersistedReplayIntentMetadata<TMessageType>, { binding_snapshot: { kind: "adapter" } }>; readonly [replayRecoveryIntentBrand]: true }>;

export interface ReplayRow<TMessageType extends string = Task5MessageType> {
  readonly claimId: string;
  readonly space: ReplaySpace;
  readonly messageType: TMessageType;
  readonly messageId: string;
  readonly sequence: bigint;
  readonly envelopeDigest: string;
  readonly expiresAt: string;
  readonly retentionUntil: string;
  readonly status: ReplayRowStatus;
  readonly replayPolicy: LockedReplayPolicy;
  readonly receiptReservationBytes: LockedReplayPolicy["receiptReservationBytes"];
  readonly inboundRawByteLength: number;
  readonly intentMetadataByteLength: number;
  readonly rawWire: ExactWireBytes;
  readonly receipt: ExactWireBytes | null;
  readonly admittedAt: string;
  readonly recoveryIntent: ReplayRecoveryIntent<TMessageType>;
  readonly createdAt: string;
  readonly updatedAt: string;
}

const trustedReplayReconcilerBrand: unique symbol = Symbol("trusted-replay-reconciler");
export type TrustedReplayReconciler = Readonly<{ reconcilerId: string; readonly [trustedReplayReconcilerBrand]: true }>;

export class DeterministicTrustedReplayReconciler implements TrustedReplayReconciler {
  readonly [trustedReplayReconcilerBrand] = true as const;
  constructor(readonly reconcilerId: string) {
    Object.freeze(this);
  }
}

export interface ReplayCapacityLimits {
  readonly maxRowsPerSpace: 4096;
  readonly maxRetainedBytesPerSpace: 67108864;
}
export const TASK5_REPLAY_LIMITS: ReplayCapacityLimits = Object.freeze({ maxRowsPerSpace: 4096, maxRetainedBytesPerSpace: 67108864 });
export const TASK5_RECEIPT_BYTE_BUDGET = LOCKED_REPLAY_POLICY_DESCRIPTORS.task5Default.receiptReservationBytes;

export interface ReplayAdmissionCompactionOptions {
  /** The stage-7 wall-clock sample already captured by the admission path. */
  readonly now?: Date | undefined;
  /** Durable references (journals, key evidence, outbox/operation rows) that
   * make a claim ineligible for deletion. The callback is fail-closed when a
   * store has not installed one. */
  readonly canRemove?: ((claimId: string) => boolean) | undefined;
}

interface ReplayAdmissionBase {
  readonly envelope: VerifiedSignedEnvelope<string>;
  readonly admittedAt: string;
}
export type DeviceReplayAdmissionRequest<TMessageType extends string = Task5MessageType> = ReplayAdmissionBase & {
  readonly envelope: VerifiedSignedEnvelope<TMessageType>;
  readonly context: Extract<AuthenticatedBindingContext, { kind: "device" }>;
  readonly connectionLease: ConnectionLease;
};
export type AdapterReplayAdmissionRequest<TMessageType extends string = Task5MessageType> = ReplayAdmissionBase & {
  readonly envelope: VerifiedSignedEnvelope<TMessageType>;
  readonly context: Extract<AuthenticatedBindingContext, { kind: "adapter" }>;
  readonly adapterCredentialLease: AdapterCredentialLease;
};

export type OrdinaryReplayCapacityDenial = { kind: "rejected"; error: "REPLAY_REJECTED"; denial: "CAPACITY_EXHAUSTED" };
export type SecurityReplayCapacityDenial = { kind: "rejected"; error: "SECURITY_LEDGER_FULL"; denial: "SECURITY_PARTITION_EXHAUSTED" };
export type StoreReplayDecision<TMessageType extends string = Task5MessageType, TCapacityDenial extends OrdinaryReplayCapacityDenial | SecurityReplayCapacityDenial = OrdinaryReplayCapacityDenial> =
  | { kind: "accepted"; frame: AcceptedTransportFrame<TMessageType> }
  | { kind: "duplicate"; cachedReceipt: ExactWireBytes }
  | { kind: "rejected"; error: "CONNECTION_FENCED"; denial: "DEVICE_LEASE_STALE" | "ADAPTER_LEASE_STALE" }
  | { kind: "rejected"; error: "INTEGRITY_FAILED"; denial: "MESSAGE_ID_CONFLICT" }
  | { kind: "rejected"; error: "REPLAY_REJECTED"; denial: "PENDING" | "WINDOW_REJECTED" }
  | TCapacityDenial;

export interface ReplayStore<TMessageType extends string = Task5MessageType> {
  finalize(claim: ReplayClaim<TMessageType>, receipt: ExactWireBytes): Promise<{ kind: "stored" | "same" } | { kind: "rejected"; error: "INTEGRITY_FAILED" }>;
  loadPending(reconciler: TrustedReplayReconciler, claimId: string): Promise<{ kind: "pending"; frame: AcceptedTransportFrame<TMessageType> } | { kind: "not_found" | "not_pending" }>;
  resumePending(reconciler: TrustedReplayReconciler, claimId: string): Promise<{ kind: "resumed"; frame: AcceptedTransportFrame<TMessageType> } | { kind: "not_found" | "not_pending" }>;
  abandonPending(reconciler: TrustedReplayReconciler, claimId: string): Promise<{ kind: "abandoned" | "same" } | { kind: "not_found" | "already_finalized" }>;
  compact(reconciler: TrustedReplayReconciler, space: ReplaySpace, clock: Clock): Promise<{ readonly removedRows: bigint; readonly removedRetainedBytes: bigint }>;
}

export type DeterministicReplayLedgerDecision<TMessageType extends string = Task5MessageType> =
  | { kind: "accepted"; claim: ReplayClaim<TMessageType> }
  | { kind: "duplicate"; claim: ReplayClaim<TMessageType>; receipt: ExactWireBytes | null }
  | { kind: "rejected"; error: "INTEGRITY_FAILED"; denial: "MESSAGE_ID_CONFLICT" | "CLAIM_ID_CONFLICT" }
  | { kind: "rejected"; error: "REPLAY_REJECTED"; denial: "PENDING" | "WINDOW_REJECTED" | "CAPACITY_EXHAUSTED" };

interface DeterministicReplayLedgerRow<TMessageType extends string> {
  readonly claim: ReplayClaim<TMessageType>;
  status: ReplayRowStatus;
  receipt: ExactWireBytes | null;
  retainedBytes: number;
}

export interface DeterministicReplayLedgerRowSnapshot<TMessageType extends string = Task5MessageType> {
  readonly claim: Readonly<{
    claimId: string;
    space: ReplaySpace;
    messageType: TMessageType;
    messageId: string;
    sequence: bigint;
    envelopeDigest: string;
    expiresAt: string;
    retentionUntil: string;
  }>;
  readonly status: ReplayRowStatus;
  readonly receipt: Uint8Array | null;
  readonly retainedBytes: number;
}

export interface DeterministicReplayLedgerSnapshot<TMessageType extends string = Task5MessageType> {
  readonly windows: readonly Readonly<{ spaceKey: string; state: ReplayWindowState }>[];
  readonly rows: readonly DeterministicReplayLedgerRowSnapshot<TMessageType>[];
  readonly capacity: readonly Readonly<{ spaceKey: string; rows: number; retainedBytes: number }>[];
}

const replaySpaceKey = (space: ReplaySpace): string => space.kind === "device"
  ? `device\u0000${space.credentialId}\u0000${space.pairingGeneration}\u0000${space.keyId}\u0000${space.direction}`
  : `adapter\u0000${space.credentialId}\u0000${space.adapterCredentialGeneration}\u0000${space.keyId}\u0000${space.direction}`;

const validateOpaquePersistenceId = (value: string): string => {
  const decoded = Buffer.from(value, "base64url");
  if (decoded.byteLength !== 32 || Buffer.from(decoded).toString("base64url") !== value) throw new Error("INTEGRITY_FAILED");
  return value;
};

const isOpaquePersistenceId = (value: unknown): value is string => {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(value)) return false;
  const decoded = Buffer.from(value, "base64url");
  return decoded.byteLength === 32 && Buffer.from(decoded).toString("base64url") === value;
};

/** Projects a real backend-issued claim without exposing its mutable/private
 * replay-row representation. Structural objects cast by callers are
 * rejected because the claim brand is private to this module. */
export function referenceReplayClaim<TMessageType extends string>(
  claim: ReplayClaim<TMessageType>,
): ReplayClaimReference<TMessageType> {
  const value = claim as unknown as Record<PropertyKey, unknown>;
  if (value[replayClaimBrand] !== true
    || !isOpaquePersistenceId(claim.claimId)
    || !isOpaquePersistenceId(claim.envelopeDigest)
    || typeof claim.messageId !== "string" || claim.messageId.length < 1 || claim.messageId.length > 128) {
    throw new Error("AUTH_FAILED");
  }
  return deepFreeze({
    claimId: claim.claimId,
    messageId: claim.messageId,
    messageType: claim.messageType,
    envelopeDigest: claim.envelopeDigest,
    [replayClaimReferenceBrand]: true as const,
  });
}

export function isReplayClaimReference(value: unknown): value is ReplayClaimReference<string> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const candidate = value as Record<PropertyKey, unknown>;
  return candidate[replayClaimReferenceBrand] === true
    && isOpaquePersistenceId(candidate.claimId)
    && isOpaquePersistenceId(candidate.envelopeDigest)
    && typeof candidate.messageType === "string"
    && typeof candidate.messageId === "string"
    && candidate.messageId.length >= 1
    && candidate.messageId.length <= 128;
}

const isTimestampString = (value: unknown): value is string => {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)) return false;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) && new Date(parsed).toISOString() === value;
};

/** Serializable in-memory reference ledger. The durable backends own an
 * instance and snapshot its plain state; claims minted by another ledger are
 * never recognized by the owning backend. */
export class DeterministicReplayLedger<TMessageType extends string = Task5MessageType> {
  readonly #windows = new Map<string, ReplayWindowState>();
  readonly #byMessage = new Map<string, DeterministicReplayLedgerRow<TMessageType>>();
  readonly #byClaimId = new Map<string, DeterministicReplayLedgerRow<TMessageType>>();
  readonly #byClaim = new WeakMap<object, DeterministicReplayLedgerRow<TMessageType>>();
  readonly #capacity = new Map<string, { rows: number; retainedBytes: number }>();
  readonly #claimIdSource: () => string;

  constructor(options: Readonly<{ claimIdSource?: (() => string) | undefined }> = {}) {
    this.#claimIdSource = options.claimIdSource ?? (() => randomBytes(32).toString("base64url"));
  }

  previewClaim(
    space: ReplaySpace,
    envelope: VerifiedSignedEnvelope<TMessageType>,
    admittedAt: string,
  ): ReplayClaim<TMessageType> | null {
    const messageId = envelope.header.message_id;
    const sequenceText = envelope.header.sequence;
    const expiresAt = envelope.header.expires_at;
    if (typeof messageId !== "string" || typeof sequenceText !== "string"
      || !/^(0|[1-9][0-9]*)$/.test(sequenceText) || typeof expiresAt !== "string") return null;
    const sequence = BigInt(sequenceText);
    const key = replaySpaceKey(space);
    const replayPolicy = lockedPolicyForMessageType(envelope.messageType);
    if (!replayPolicy) return null;
    let retentionBase: number;
    if (replayPolicy.classId === REPLAY_POLICY_LITERALS.operationSecurityLedger.class_id) {
      const operationExpiresAt = envelope.payload.operation_expires_at;
      if (!isTimestampString(operationExpiresAt)) return null;
      retentionBase = Date.parse(operationExpiresAt);
    } else {
      retentionBase = Math.max(Date.parse(expiresAt), Date.parse(admittedAt) + 86_400_000);
    }
    return deepFreeze({
      claimId: sha256B64Url(canonicalBytes({ envelope_digest: envelope.envelopeDigest, message_id: messageId, replay_space: key })),
      space: deepFreeze({ ...space }),
      messageType: envelope.messageType,
      messageId,
      sequence,
      envelopeDigest: envelope.envelopeDigest,
      expiresAt,
      retentionUntil: new Date(retentionBase).toISOString(),
      replayPolicy,
      [replayClaimBrand]: true,
    });
  }

  admit(
    space: ReplaySpace,
    envelope: VerifiedSignedEnvelope<TMessageType>,
    admittedAt: string,
    retainedBytes = 0,
    compaction: ReplayAdmissionCompactionOptions = {},
  ): DeterministicReplayLedgerDecision<TMessageType> {
    const messageId = envelope.header.message_id;
    const sequenceText = envelope.header.sequence;
    const expiresAt = envelope.header.expires_at;
    if (typeof messageId !== "string" || typeof sequenceText !== "string"
      || !/^(0|[1-9][0-9]*)$/.test(sequenceText) || typeof expiresAt !== "string") {
      return { kind: "rejected", error: "REPLAY_REJECTED", denial: "WINDOW_REJECTED" };
    }
    const sequence = BigInt(sequenceText);
    const key = replaySpaceKey(space);
    const messageKey = messageId;
    const previous = this.#byMessage.get(messageKey);
    if (previous) {
      if (previous.claim.envelopeDigest !== envelope.envelopeDigest
        || replaySpaceKey(previous.claim.space) !== key) {
        return { kind: "rejected", error: "INTEGRITY_FAILED", denial: "MESSAGE_ID_CONFLICT" };
      }
      if (previous.status === "pending") {
        return { kind: "rejected", error: "REPLAY_REJECTED", denial: "PENDING" };
      }
      return { kind: "duplicate", claim: previous.claim, receipt: previous.receipt };
    }
    const window = this.#windows.get(key) ?? { highestSeen: null, seenBitmap: 0n };
    const accepted = acceptSequence(window, sequence);
    if (accepted.kind === "reject") {
      return { kind: "rejected", error: "REPLAY_REJECTED", denial: "WINDOW_REJECTED" };
    }
    if (!Number.isSafeInteger(retainedBytes) || retainedBytes < 0) {
      return { kind: "rejected", error: "REPLAY_REJECTED", denial: "CAPACITY_EXHAUSTED" };
    }
    // Allocate and reserve the opaque claim ID before any admission-triggered
    // compaction. A source collision is an integrity failure and must leave
    // the sequence window, capacity counters and rows unchanged.
    const claimId = validateOpaquePersistenceId(this.#claimIdSource());
    if (this.#byClaimId.has(claimId)) {
      return { kind: "rejected", error: "INTEGRITY_FAILED", denial: "CLAIM_ID_CONFLICT" };
    }
    let capacity = this.#capacity.get(key) ?? { rows: 0, retainedBytes: 0 };
    const overCapacity = () => capacity.rows + 1 > TASK5_REPLAY_LIMITS.maxRowsPerSpace
      || capacity.retainedBytes + retainedBytes > TASK5_REPLAY_LIMITS.maxRetainedBytesPerSpace;
    if (overCapacity()) {
      const now = compaction.now ?? new Date(Date.parse(admittedAt));
      // Admission-triggered compaction is part of the same serialized ledger
      // operation. It uses the already-sampled admission time and the owning
      // backend's reference predicate; pending/current-window rows remain
      // untouched and a missing predicate is fail-closed.
      this.compact(space, now, compaction.canRemove ?? (() => false));
      capacity = this.#capacity.get(key) ?? { rows: 0, retainedBytes: 0 };
    }
    if (overCapacity()) {
      return { kind: "rejected", error: "REPLAY_REJECTED", denial: "CAPACITY_EXHAUSTED" };
    }
    const claim = this.previewClaim(space, envelope, admittedAt);
    if (!claim) return { kind: "rejected", error: "REPLAY_REJECTED", denial: "WINDOW_REJECTED" };
    const unguessableClaim: ReplayClaim<TMessageType> = deepFreeze({
      ...claim,
      claimId,
      [replayClaimBrand]: true as const,
    });
    const row: DeterministicReplayLedgerRow<TMessageType> = { claim: unguessableClaim, status: "pending", receipt: null, retainedBytes };
    this.#windows.set(key, accepted.next);
    this.#capacity.set(key, { rows: capacity.rows + 1, retainedBytes: capacity.retainedBytes + retainedBytes });
    this.#byMessage.set(messageKey, row);
    this.#byClaimId.set(claimId, row);
    this.#byClaim.set(unguessableClaim, row);
    return { kind: "accepted", claim: unguessableClaim };
  }

  owns(claim: ReplayClaim<TMessageType>): boolean {
    return this.#byClaim.has(claim);
  }

  finalize(claim: ReplayClaim<TMessageType>, receipt: ExactWireBytes): "stored" | "same" | "rejected" {
    const row = this.#byClaim.get(claim);
    if (!row || !Number.isSafeInteger(receipt.byteLength)
      || receipt.byteLength < 1 || receipt.byteLength > TASK5_RECEIPT_BYTE_BUDGET) return "rejected";
    let retainedReceipt: ExactWireBytes;
    try {
      const copied = receipt.copy();
      if (!(copied instanceof Uint8Array) || copied.byteLength !== receipt.byteLength) return "rejected";
      retainedReceipt = retainExactWireBytes(copied);
    } catch {
      return "rejected";
    }
    if (row.status !== "pending" && row.receipt === null) return "rejected";
    if (row.receipt !== null) {
      const left = row.receipt.copy();
      const right = retainedReceipt.copy();
      return left.byteLength === right.byteLength && left.every((byte, index) => byte === right[index]) ? "same" : "rejected";
    }
    row.receipt = retainedReceipt;
    row.status = "finalized";
    const key = replaySpaceKey(row.claim.space);
    const capacity = this.#capacity.get(key);
    if (capacity) {
      capacity.retainedBytes = capacity.retainedBytes - TASK5_RECEIPT_BYTE_BUDGET + retainedReceipt.byteLength;
      row.retainedBytes = row.retainedBytes - TASK5_RECEIPT_BYTE_BUDGET + retainedReceipt.byteLength;
    }
    return "stored";
  }

  capacity(space: ReplaySpace): Readonly<{ rows: number; retainedBytes: number }> {
    const value = this.#capacity.get(replaySpaceKey(space)) ?? { rows: 0, retainedBytes: 0 };
    return Object.freeze({ ...value });
  }

  restorePending(claimSnapshot: Readonly<{
    claimId: string;
    space: ReplaySpace;
    messageType: TMessageType;
    messageId: string;
    sequence: bigint;
    envelopeDigest: string;
    expiresAt: string;
    retentionUntil: string;
  }>, retainedBytes = 0): ReplayClaim<TMessageType> {
    validateOpaquePersistenceId(claimSnapshot.claimId);
    const claim: ReplayClaim<TMessageType> = deepFreeze({
      ...claimSnapshot,
      space: deepFreeze({ ...claimSnapshot.space }),
      replayPolicy: TASK5_DEFAULT_POLICY,
      [replayClaimBrand]: true,
    });
    const key = replaySpaceKey(claim.space);
    const messageKey = claim.messageId;
    if (this.#byMessage.has(messageKey) || this.#byClaimId.has(claim.claimId)) throw new Error("INTEGRITY_FAILED");
    const window = this.#windows.get(key) ?? { highestSeen: null, seenBitmap: 0n };
    const accepted = acceptSequence(window, claim.sequence);
    if (accepted.kind === "reject") throw new Error("INTEGRITY_FAILED");
    const capacity = this.#capacity.get(key) ?? { rows: 0, retainedBytes: 0 };
    if (capacity.rows + 1 > TASK5_REPLAY_LIMITS.maxRowsPerSpace
      || capacity.retainedBytes + retainedBytes > TASK5_REPLAY_LIMITS.maxRetainedBytesPerSpace) throw new Error("INTEGRITY_FAILED");
    const row: DeterministicReplayLedgerRow<TMessageType> = { claim, status: "pending", receipt: null, retainedBytes };
    this.#windows.set(key, accepted.next);
    this.#capacity.set(key, { rows: capacity.rows + 1, retainedBytes: capacity.retainedBytes + retainedBytes });
    this.#byMessage.set(messageKey, row);
    this.#byClaimId.set(claim.claimId, row);
    this.#byClaim.set(claim, row);
    return claim;
  }

  snapshot(): DeterministicReplayLedgerSnapshot<TMessageType> {
    return Object.freeze({
      windows: Object.freeze([...this.#windows].map(([spaceKey, state]) => Object.freeze({
        spaceKey, state: Object.freeze({ ...state }),
      }))),
      rows: Object.freeze([...this.#byMessage.values()].map((row) => Object.freeze({
        claim: Object.freeze({ ...row.claim, space: Object.freeze({ ...row.claim.space }) }),
        status: row.status,
        receipt: row.receipt?.copy() ?? null,
        retainedBytes: row.retainedBytes,
      }))),
      capacity: Object.freeze([...this.#capacity].map(([spaceKey, value]) => Object.freeze({ spaceKey, ...value }))),
    });
  }

  static restart<TMessageType extends string>(
    snapshot: DeterministicReplayLedgerSnapshot<TMessageType>,
    options: Readonly<{ claimIdSource?: (() => string) | undefined }> = {},
  ): DeterministicReplayLedger<TMessageType> {
    if (!Array.isArray(snapshot.windows) || !Array.isArray(snapshot.rows) || !Array.isArray(snapshot.capacity)) {
      throw new Error("INTEGRITY_FAILED");
    }
    const ledger = new DeterministicReplayLedger<TMessageType>(options);
    const expectedCapacity = new Map<string, { rows: number; retainedBytes: number }>();
    const expectedSequences = new Map<string, bigint[]>();
    const seenClaimIds = new Set<string>();
    const seenSpaces = new Set<string>();
    for (const item of snapshot.windows) {
      if (typeof item.spaceKey !== "string" || seenSpaces.has(item.spaceKey)
        || item.state.highestSeen !== null && (item.state.highestSeen < 0n || item.state.highestSeen > UINT64_MAX)
        || item.state.seenBitmap < 0n || item.state.seenBitmap > WINDOW_MASK
        || item.state.highestSeen === null && item.state.seenBitmap !== 0n) {
        throw new Error("INTEGRITY_FAILED");
      }
      seenSpaces.add(item.spaceKey);
      ledger.#windows.set(item.spaceKey, Object.freeze({ ...item.state }));
    }
    for (const item of snapshot.rows) {
      if (!item || !item.claim || !item.claim.space || typeof item.claim.messageId !== "string"
        || item.claim.messageId.length === 0 || !isOpaquePersistenceId(item.claim.claimId)
        || !isOpaquePersistenceId(item.claim.envelopeDigest)
        || !/^(0|[1-9][0-9]*)$/.test(item.claim.messageId) && item.claim.messageId.length > 128
        || item.claim.sequence < 0n || item.claim.sequence > UINT64_MAX
        || !isTimestampString(item.claim.expiresAt) || !isTimestampString(item.claim.retentionUntil)
        || (item.status !== "pending" && item.status !== "finalized" && item.status !== "abandoned")
        || !Number.isSafeInteger(item.retainedBytes) || item.retainedBytes < 0
        || item.status === "pending" && item.receipt !== null
        || item.status === "abandoned" && item.receipt !== null
        || item.status === "finalized" && (item.receipt === null
          || item.receipt.byteLength < 1 || item.receipt.byteLength > TASK5_RECEIPT_BYTE_BUDGET)) {
        throw new Error("INTEGRITY_FAILED");
      }
      validateOpaquePersistenceId(item.claim.claimId);
      if (seenClaimIds.has(item.claim.claimId)
        || !isRecord(item.claim.space)
        || (item.claim.space.kind !== "device" && item.claim.space.kind !== "adapter")
        || typeof item.claim.space.credentialId !== "string"
        || typeof item.claim.space.keyId !== "string"
        || typeof item.claim.space.direction !== "string") {
        throw new Error("INTEGRITY_FAILED");
      }
      seenClaimIds.add(item.claim.claimId);
      const claim = deepFreeze({
        ...item.claim,
        space: deepFreeze({ ...item.claim.space }),
        replayPolicy: TASK5_DEFAULT_POLICY,
        [replayClaimBrand]: true as const,
      });
      if (ledger.#byMessage.has(claim.messageId)) throw new Error("INTEGRITY_FAILED");
      const key = replaySpaceKey(claim.space);
      const totals = expectedCapacity.get(key) ?? { rows: 0, retainedBytes: 0 };
      totals.rows += 1;
      totals.retainedBytes += item.retainedBytes;
      if (totals.rows > TASK5_REPLAY_LIMITS.maxRowsPerSpace
        || totals.retainedBytes > TASK5_REPLAY_LIMITS.maxRetainedBytesPerSpace) throw new Error("INTEGRITY_FAILED");
      expectedCapacity.set(key, totals);
      const sequences = expectedSequences.get(key) ?? [];
      sequences.push(claim.sequence);
      expectedSequences.set(key, sequences);
      const row: DeterministicReplayLedgerRow<TMessageType> = {
        claim, status: item.status, receipt: item.receipt ? retainExactWireBytes(item.receipt) : null, retainedBytes: item.retainedBytes,
      };
      ledger.#byMessage.set(claim.messageId, row);
      ledger.#byClaimId.set(claim.claimId, row);
      ledger.#byClaim.set(claim, row);
    }
    const actualCapacity = new Map<string, { rows: number; retainedBytes: number }>();
    for (const item of snapshot.capacity) {
      if (typeof item.spaceKey !== "string" || actualCapacity.has(item.spaceKey)
        || !Number.isSafeInteger(item.rows) || item.rows < 0
        || !Number.isSafeInteger(item.retainedBytes) || item.retainedBytes < 0
        || item.rows > TASK5_REPLAY_LIMITS.maxRowsPerSpace
        || item.retainedBytes > TASK5_REPLAY_LIMITS.maxRetainedBytesPerSpace) {
        throw new Error("INTEGRITY_FAILED");
      }
      actualCapacity.set(item.spaceKey, { rows: item.rows, retainedBytes: item.retainedBytes });
    }
    if (actualCapacity.size !== expectedCapacity.size
      || [...expectedCapacity].some(([key, expected]) => {
        const actual = actualCapacity.get(key);
        return !actual || actual.rows !== expected.rows || actual.retainedBytes !== expected.retainedBytes;
      })) throw new Error("INTEGRITY_FAILED");
    for (const [key, value] of actualCapacity) ledger.#capacity.set(key, value);
    const expectedWindows = new Map<string, ReplayWindowState>();
    for (const [key, sequences] of expectedSequences) {
      const highestSeen = sequences.reduce((max, sequence) => sequence > max ? sequence : max, 0n);
      const seenBitmap = sequences.reduce((bits, sequence) => {
        const offset = highestSeen - sequence;
        return offset < WINDOW_SIZE ? bits | (1n << offset) : bits;
      }, 0n);
      expectedWindows.set(key, { highestSeen, seenBitmap });
    }
    // A persisted window is authoritative for history that may already have
    // been compacted, but every retained row must agree with it. Otherwise a
    // crash cut could silently reopen an old sequence or mark a row as seen
    // without a durable row behind the bit.
    for (const [key, expected] of expectedWindows) {
      const actual = ledger.#windows.get(key);
      if (!actual || actual.highestSeen !== expected.highestSeen
        || (actual.seenBitmap & ((1n << WINDOW_SIZE) - 1n)) !== (expected.seenBitmap & ((1n << WINDOW_SIZE) - 1n))) {
        throw new Error("INTEGRITY_FAILED");
      }
    }
    for (const [key] of expectedCapacity) {
      if (!ledger.#windows.has(key)) throw new Error("INTEGRITY_FAILED");
    }
    return ledger;
  }

  abandon(claim: ReplayClaim<TMessageType>): "abandoned" | "same" | "rejected" {
    const row = this.#byClaim.get(claim);
    if (!row) return "rejected";
    if (row.status === "abandoned") return "same";
    if (row.status !== "pending") return "rejected";
    row.status = "abandoned";
    return "abandoned";
  }

  compact(space: ReplaySpace, now: Date, canRemove: (claimId: string) => boolean = () => true): { removedRows: bigint; removedRetainedBytes: bigint } {
    const key = replaySpaceKey(space);
    const window = this.#windows.get(key);
    if (!window || window.highestSeen === null) return { removedRows: 0n, removedRetainedBytes: 0n };
    let removedRows = 0n;
    let removedRetainedBytes = 0n;
    for (const [messageId, row] of this.#byMessage) {
      if (replaySpaceKey(row.claim.space) !== key || row.status === "pending") continue;
      const outsideWindow = window.highestSeen - row.claim.sequence >= WINDOW_SIZE;
      if (!outsideWindow || Date.parse(row.claim.retentionUntil) > now.getTime()) continue;
      if (!canRemove(row.claim.claimId)) continue;
      this.#byMessage.delete(messageId);
      this.#byClaimId.delete(row.claim.claimId);
      this.#byClaim.delete(row.claim);
      const capacity = this.#capacity.get(key);
      if (capacity) {
        capacity.rows -= 1;
        capacity.retainedBytes -= row.retainedBytes;
      }
      removedRows += 1n;
      removedRetainedBytes += BigInt(row.retainedBytes);
    }
    return { removedRows, removedRetainedBytes };
  }

  row(claim: ReplayClaim<TMessageType>): Readonly<{ status: ReplayRowStatus; receipt: Uint8Array | null; retainedBytes: number }> | null {
    const row = this.#byClaim.get(claim);
    return row ? Object.freeze({ status: row.status, receipt: row.receipt?.copy() ?? null, retainedBytes: row.retainedBytes }) : null;
  }

  findClaim(messageId: string): ReplayClaim<TMessageType> | null {
    return this.#byMessage.get(messageId)?.claim ?? null;
  }
}

export type { MessageRegistryEntry };
