import type {
  AuthenticatedBindingContext,
  EnvelopeAuthorizationGate,
} from "./control-envelope.js";
import {
  loadCapabilityRegistry,
  validateCapabilityFilter,
  type CapabilityRegistry,
  type CapabilityBinding,
} from "./capability-manifest.js";
import type { Clock } from "./ports.js";
import type { RevisionSnapshot } from "./authorization-revision.js";
import type { DataQueryGrantKey, DataQueryGrantState } from "./data-query-grant.js";

export type DeviceLifecycle = "active" | "paused" | "revoked";

export interface CurrentDeviceAuthorization {
  readonly lifecycle: DeviceLifecycle;
  readonly activePairing: boolean;
  readonly enrollmentScopeCeiling: readonly string[];
  readonly localConsent: ReadonlySet<string>;
  readonly systemPermission: ReadonlySet<string>;
  readonly backendAvailable: ReadonlySet<string>;
  readonly revision: RevisionSnapshot;
}

export interface ServerAgentSessionBinding {
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly agentPrincipalId: string;
  readonly agentInstanceId: string;
  readonly workspaceId: string;
  readonly sessionId: string;
  readonly deviceId: string;
  readonly agentScopeCeiling: readonly string[];
}

export interface DeviceAccessInput {
  readonly binding: Extract<AuthenticatedBindingContext, { kind: "device" }>;
  readonly requester: ServerAgentSessionBinding;
  readonly capability: string;
  readonly requiredRevision: RevisionSnapshot;
  readonly current: CurrentDeviceAuthorization;
  readonly registry?: CapabilityRegistry | undefined;
}

export interface StoredRecordLineage {
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
  readonly capability: string;
  readonly filterHash: string;
  readonly sourceId: string;
  readonly expiresAt: string;
  readonly tombstoned: boolean;
}

export interface StoredQueryInput {
  readonly requester: ServerAgentSessionBinding;
  readonly key: DataQueryGrantKey;
  readonly grant: DataQueryGrantState;
  readonly record: StoredRecordLineage;
  readonly clock: Clock;
}

export type AuthorizationDecision =
  | { readonly allowed: true }
  | { readonly allowed: false; readonly reason:
      | "PAIRING_INACTIVE" | "BINDING_MISMATCH" | "SCOPE_DENIED"
      | "SYSTEM_PERMISSION_MISSING" | "BACKEND_UNAVAILABLE" | "REVISION_MISMATCH"
      | "GRANT_REVOKED" | "LINEAGE_MISMATCH" | "RECORD_EXPIRED" | "RECORD_DELETED" };

export interface EnvelopeAuthorizationState {
  readonly scopeCeiling: readonly string[];
  readonly registry?: CapabilityRegistry | undefined;
  readonly binding?: CapabilityBinding | undefined;
  readonly activePairing?: boolean | undefined;
}

const payloadRecord = (value: unknown): Record<string, unknown> | null =>
  typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown> : null;

const requestedScope = (messageType: string, payload: unknown): string | null => {
  const value = payloadRecord(payload);
  const explicit = value?.capability ?? value?.scope ?? value?.required_scope;
  if (typeof explicit === "string") return explicit;
  if (messageType === "device_ping" || messageType === "device_presence") return "device.status";
  return null;
};

const contextMatches = (context: AuthenticatedBindingContext, binding: CapabilityBinding | undefined): boolean =>
  binding === undefined
    || context.tenantId === binding.tenantId
    && context.humanPrincipalId === binding.humanPrincipalId
    && (context.kind !== "device" || context.deviceId === binding.deviceId);

/**
 * Builds the Task 5 authorization gate from the immutable Task 6 registry.
 * Unknown scopes never become an allow decision; an unavailable or malformed
 * registry fails during construction through loadCapabilityRegistry().
 */
export function createEnvelopeAuthorizationGate(state: EnvelopeAuthorizationState): EnvelopeAuthorizationGate {
  const registry = state.registry ?? loadCapabilityRegistry();
  const ceiling = Object.freeze([...state.scopeCeiling]);
  const ceilingDecision = registry.validateScopeCeiling(ceiling);
  return Object.freeze({
    evaluate(messageType: string, payload: unknown, context: AuthenticatedBindingContext) {
      if (state.activePairing === false) return { allowed: false as const, denial: "PAIRING_INACTIVE" as const };
      if (!contextMatches(context, state.binding)) return { allowed: false as const, denial: "PAIRING_INACTIVE" as const };
      if (!ceilingDecision.ok) return { allowed: false as const, denial: "SCOPE_DENIED" as const };
      const scope = requestedScope(messageType, payload);
      const capability = scope === null ? null : registry.resolve(scope);
      if (scope === null || capability === null || !ceiling.includes(scope)) {
        return { allowed: false as const, denial: "SCOPE_DENIED" as const };
      }
      const body = payloadRecord(payload);
      const filterDecision = validateCapabilityFilter(scope, body?.filter, registry);
      if (!filterDecision.ok) return { allowed: false as const, denial: "SCOPE_DENIED" as const };
      return { allowed: true as const };
    },
  });
}

const scopeMatches = (ceilingScope: string, requested: string): boolean => {
  const left = ceilingScope.split(".");
  const right = requested.split(".");
  if (left.length !== right.length) return false;
  return left.every((part, index) => part.startsWith("<") && part.endsWith(">") ? /^[a-z][a-z0-9_-]{0,127}$/u.test(right[index] ?? "") : part === right[index]);
};

const ceilingAllows = (ceiling: readonly string[], capability: string): boolean => ceiling.some((entry) => entry === capability || scopeMatches(entry, capability));
const identityMatches = (binding: Extract<AuthenticatedBindingContext, { kind: "device" }>, requester: ServerAgentSessionBinding): boolean =>
  binding.tenantId === requester.tenantId && binding.humanPrincipalId === requester.humanPrincipalId && binding.deviceId === requester.deviceId;
const revisionFor = (snapshot: RevisionSnapshot, capability: string): bigint | undefined => snapshot.scopeRevisions instanceof Map
  ? snapshot.scopeRevisions.get(capability)
  : (snapshot.scopeRevisions as unknown as Readonly<Record<string, bigint>>)[capability];

/** Real-time device authorization.  This is intentionally deny-first: no
 * manifest, model-supplied user id, or transport metadata is consulted. */
export function evaluateDeviceAccess(input: DeviceAccessInput): AuthorizationDecision {
  const registry = input.registry ?? loadCapabilityRegistry();
  if (!input.current.activePairing || input.current.lifecycle !== "active") return { allowed: false, reason: "PAIRING_INACTIVE" };
  if (!identityMatches(input.binding, input.requester)) return { allowed: false, reason: "BINDING_MISMATCH" };
  const capability = registry.resolve(input.capability);
  if (capability === null || !ceilingAllows(input.requester.agentScopeCeiling, input.capability) || !ceilingAllows(input.current.enrollmentScopeCeiling, input.capability) || !input.current.localConsent.has(input.capability)) return { allowed: false, reason: "SCOPE_DENIED" };
  if (!input.current.systemPermission.has(input.capability)) return { allowed: false, reason: "SYSTEM_PERMISSION_MISSING" };
  if (!capability.allowedBackends.some((backend) => input.current.backendAvailable.has(backend))) return { allowed: false, reason: "BACKEND_UNAVAILABLE" };
  if (input.requiredRevision.pairingGeneration !== input.current.revision.pairingGeneration || input.requiredRevision.authorizationEpoch !== input.current.revision.authorizationEpoch || revisionFor(input.requiredRevision, input.capability) !== revisionFor(input.current.revision, input.capability)) return { allowed: false, reason: "REVISION_MISMATCH" };
  return { allowed: true };
}

/** Stored-query authorization is a separate domain.  It deliberately does
 * not read Android permission, backend availability, lifecycle or device
 * authorization epoch; a durable grant and immutable record lineage are the
 * only authorities for already stored data. */
export function evaluateStoredQuery(input: StoredQueryInput): AuthorizationDecision {
  const { requester, key, grant, record } = input;
  if (grant.bridgeEffectiveState !== "effective") return { allowed: false, reason: "GRANT_REVOKED" };
  if (key.tenantId !== requester.tenantId || key.humanPrincipalId !== requester.humanPrincipalId || key.deviceId !== requester.deviceId) return { allowed: false, reason: "LINEAGE_MISMATCH" };
  if (grant.key.tenantId !== key.tenantId || grant.key.humanPrincipalId !== key.humanPrincipalId || grant.key.deviceId !== key.deviceId || grant.key.capability !== key.capability || grant.key.filterHash !== key.filterHash) return { allowed: false, reason: "LINEAGE_MISMATCH" };
  if (record.tenantId !== key.tenantId || record.humanPrincipalId !== key.humanPrincipalId || record.deviceId !== key.deviceId || record.capability !== key.capability || record.filterHash !== key.filterHash) return { allowed: false, reason: "LINEAGE_MISMATCH" };
  if (record.tombstoned) return { allowed: false, reason: "RECORD_DELETED" };
  const now = input.clock.wallNow();
  if (!Number.isFinite(now.getTime()) || !Number.isFinite(Date.parse(record.expiresAt)) || now.getTime() >= Date.parse(record.expiresAt)) return { allowed: false, reason: "RECORD_EXPIRED" };
  return { allowed: true };
}
