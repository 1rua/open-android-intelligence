/**
 * Task 6 authorization revision reducer.
 *
 * This module is intentionally a small, deterministic reference model.  It
 * does not trust a wire event as proof that a person unlocked the device: a
 * resume event must carry an opaque fact minted by the local authenticator in
 * this module.  Every returned state is a fresh immutable snapshot, which
 * makes stale revision objects straightforward to reject at call sites.
 */

export type DeviceLifecycle = "active" | "paused" | "revoked";

export interface RevisionSnapshot {
  readonly pairingGeneration: bigint;
  readonly authorizationEpoch: bigint;
  readonly scopeRevisions: ReadonlyMap<string, bigint>;
}

export interface AuthenticatedLocalUserTransition {
  readonly deviceId: string;
  readonly pairingGeneration: bigint;
  readonly transitionId: string;
  readonly kind: "resume";
  readonly authenticatedAt: string;
  readonly [authenticatedLocalUserTransitionBrand]: true;
}

export interface RotationActivationFact {
  readonly owner: "device-installation" | "bridge-command" | "adapter";
  readonly pairingGeneration: bigint | null;
  readonly adapterCredentialGeneration: bigint | null;
  readonly factId: string;
  readonly credentialId: string;
}

export type RevisionEvent =
  | { readonly kind: "new_pairing"; readonly nextPairingGeneration: bigint }
  | { readonly kind: "pause" | "emergency_stop" | "system_permission_change" | "backend_credential_change" }
  | { readonly kind: "resume"; readonly localUser: AuthenticatedLocalUserTransition }
  | { readonly kind: "scope_policy_revoke" | "scope_policy_regrant"; readonly capability: string }
  | { readonly kind: "bridge_command_key_activated"; readonly fact: RotationActivationFact };

export interface AuthorizationRevisionState extends RevisionSnapshot {
  readonly deviceId: string;
  readonly lifecycle: DeviceLifecycle;
  readonly activePairing: boolean;
  readonly enrollmentScopeCeiling: readonly string[];
  readonly localConsent: ReadonlySet<string>;
  readonly systemPermission: ReadonlySet<string>;
  readonly backendAvailable: ReadonlySet<string>;
  readonly usedLocalTransitionIds: ReadonlySet<string>;
  readonly boundBridgeCredentialId: string | null;
}

const authenticatedLocalUserTransitionBrand: unique symbol = Symbol("authenticated-local-user-transition");
const authenticatedTransitions = new WeakSet<object>();

const text = (value: unknown): value is string => typeof value === "string" && value.length > 0 && value.length <= 512 && !/[\u0000-\u001f\u007f]/u.test(value);
const instant = (value: unknown): value is string => typeof value === "string" && Number.isFinite(Date.parse(value)) && value === new Date(value).toISOString();

class ImmutableSet<T> implements ReadonlySet<T> {
  readonly #set: Set<T>;
  constructor(values: Iterable<T>) { this.#set = new Set(values); Object.freeze(this); }
  get size(): number { return this.#set.size; }
  has(value: T): boolean { return this.#set.has(value); }
  entries(): SetIterator<[T, T]> { return this.#set.entries(); }
  keys(): SetIterator<T> { return this.#set.keys(); }
  values(): SetIterator<T> { return this.#set.values(); }
  [Symbol.iterator](): SetIterator<T> { return this.#set[Symbol.iterator](); }
  forEach(callbackfn: (value: T, value2: T, set: ReadonlySet<T>) => void, thisArg?: unknown): void { this.#set.forEach((value) => callbackfn.call(thisArg, value, value, this)); }
}

class ImmutableMap<K, V> implements ReadonlyMap<K, V> {
  readonly #map: Map<K, V>;
  constructor(values: Iterable<readonly [K, V]>) { this.#map = new Map(values); Object.freeze(this); }
  get size(): number { return this.#map.size; }
  get(key: K): V | undefined { return this.#map.get(key); }
  has(key: K): boolean { return this.#map.has(key); }
  entries(): MapIterator<[K, V]> { return this.#map.entries(); }
  keys(): MapIterator<K> { return this.#map.keys(); }
  values(): MapIterator<V> { return this.#map.values(); }
  [Symbol.iterator](): MapIterator<[K, V]> { return this.#map[Symbol.iterator](); }
  forEach(callbackfn: (value: V, key: K, map: ReadonlyMap<K, V>) => void, thisArg?: unknown): void { this.#map.forEach((value, key) => callbackfn.call(thisArg, value, key, this)); }
}

const freezeSet = (values: Iterable<string>): ReadonlySet<string> => new ImmutableSet(values);
const freezeMap = <K, V>(values: Iterable<readonly [K, V]>): ReadonlyMap<K, V> => new ImmutableMap(values);

const freezeState = (state: AuthorizationRevisionState): AuthorizationRevisionState => Object.freeze({
  deviceId: state.deviceId,
  lifecycle: state.lifecycle,
  activePairing: state.activePairing,
  enrollmentScopeCeiling: Object.freeze([...state.enrollmentScopeCeiling]),
  localConsent: freezeSet(state.localConsent),
  systemPermission: freezeSet(state.systemPermission),
  backendAvailable: freezeSet(state.backendAvailable),
  usedLocalTransitionIds: freezeSet(state.usedLocalTransitionIds),
  boundBridgeCredentialId: state.boundBridgeCredentialId,
  pairingGeneration: state.pairingGeneration,
  authorizationEpoch: state.authorizationEpoch,
  scopeRevisions: freezeMap(state.scopeRevisions),
});

const ensureU64 = (value: bigint, name: string): void => {
  if (typeof value !== "bigint" || value < 0n || value > 18_446_744_073_709_551_615n) throw new Error("SCHEMA_INVALID");
  void name;
};

const next = (value: bigint): bigint => {
  ensureU64(value, "revision");
  if (value === 18_446_744_073_709_551_615n) throw new Error("INVALID_STATE_TRANSITION");
  return value + 1n;
};

const cloneScopeRevisions = (value: ReadonlyMap<string, bigint> | Readonly<Record<string, bigint>> | undefined): Map<string, bigint> => {
  const result = new Map<string, bigint>();
  if (value instanceof Map) {
    for (const [scope, revision] of value) {
      if (!text(scope)) throw new Error("SCHEMA_INVALID");
      ensureU64(revision, "scope_revision");
      result.set(scope, revision);
    }
  } else if (value !== undefined) {
    for (const [scope, revision] of Object.entries(value)) {
      if (!text(scope)) throw new Error("SCHEMA_INVALID");
      ensureU64(revision, "scope_revision");
      result.set(scope, revision);
    }
  }
  return result;
};

export function createAuthorizationRevisionState(input: Readonly<{
  readonly deviceId: string;
  readonly pairingGeneration: bigint;
  readonly authorizationEpoch?: bigint;
  readonly lifecycle?: DeviceLifecycle;
  readonly activePairing?: boolean;
  readonly enrollmentScopeCeiling?: readonly string[];
  readonly localConsent?: Iterable<string>;
  readonly systemPermission?: Iterable<string>;
  readonly backendAvailable?: Iterable<string>;
  readonly scopeRevisions?: ReadonlyMap<string, bigint> | Readonly<Record<string, bigint>>;
  readonly boundBridgeCredentialId?: string | null;
}>): AuthorizationRevisionState {
  if (!text(input.deviceId)) throw new Error("SCHEMA_INVALID");
  ensureU64(input.pairingGeneration, "pairing_generation");
  const epoch = input.authorizationEpoch ?? 0n;
  ensureU64(epoch, "authorization_epoch");
  const lifecycle = input.lifecycle ?? "active";
  if (lifecycle !== "active" && lifecycle !== "paused" && lifecycle !== "revoked") throw new Error("SCHEMA_INVALID");
  const state: AuthorizationRevisionState = {
    deviceId: input.deviceId,
    lifecycle,
    activePairing: input.activePairing ?? lifecycle === "active",
    enrollmentScopeCeiling: Object.freeze([...(input.enrollmentScopeCeiling ?? [])]),
    localConsent: freezeSet(input.localConsent ?? []),
    systemPermission: freezeSet(input.systemPermission ?? []),
    backendAvailable: freezeSet(input.backendAvailable ?? []),
    usedLocalTransitionIds: freezeSet([]),
    boundBridgeCredentialId: input.boundBridgeCredentialId ?? null,
    pairingGeneration: input.pairingGeneration,
    authorizationEpoch: epoch,
    scopeRevisions: freezeMap(cloneScopeRevisions(input.scopeRevisions)),
  };
  return freezeState(state);
}

export function mintAuthenticatedLocalUserTransition(input: Readonly<{
  readonly deviceId: string;
  readonly pairingGeneration: bigint;
  readonly transitionId: string;
  readonly authenticatedAt: string;
}>): AuthenticatedLocalUserTransition {
  if (!text(input.deviceId) || !text(input.transitionId) || !instant(input.authenticatedAt)) throw new Error("SCHEMA_INVALID");
  ensureU64(input.pairingGeneration, "pairing_generation");
  const fact = Object.freeze({
    deviceId: input.deviceId,
    pairingGeneration: input.pairingGeneration,
    transitionId: input.transitionId,
    kind: "resume" as const,
    authenticatedAt: input.authenticatedAt,
    [authenticatedLocalUserTransitionBrand]: true as const,
  });
  authenticatedTransitions.add(fact);
  return fact;
}

const copyState = (state: AuthorizationRevisionState): AuthorizationRevisionState => {
  if (!state || typeof state !== "object") throw new Error("SCHEMA_INVALID");
  return freezeState({
    ...state,
    enrollmentScopeCeiling: [...state.enrollmentScopeCeiling],
    localConsent: freezeSet(state.localConsent),
    systemPermission: freezeSet(state.systemPermission),
    backendAvailable: freezeSet(state.backendAvailable),
    usedLocalTransitionIds: freezeSet(state.usedLocalTransitionIds),
    scopeRevisions: freezeMap(state.scopeRevisions),
  });
};

export function applyRevisionEvent(state: AuthorizationRevisionState, event: RevisionEvent): AuthorizationRevisionState {
  const current = copyState(state);
  if (!event || typeof event !== "object" || typeof event.kind !== "string") throw new Error("SCHEMA_INVALID");
  if (event.kind === "new_pairing") {
    ensureU64(event.nextPairingGeneration, "pairing_generation");
    if (current.lifecycle !== "revoked" || event.nextPairingGeneration !== current.pairingGeneration + 1n) throw new Error("INVALID_STATE_TRANSITION");
    return freezeState({ ...current, pairingGeneration: event.nextPairingGeneration, authorizationEpoch: 0n, lifecycle: "active", activePairing: true, usedLocalTransitionIds: freezeSet([]), scopeRevisions: freezeMap([]) });
  }
  if (event.kind === "resume") {
    const fact = event.localUser;
    if (!fact || typeof fact !== "object" || !authenticatedTransitions.has(fact as object)
      || fact[authenticatedLocalUserTransitionBrand] !== true || current.lifecycle !== "paused"
      || fact.deviceId !== current.deviceId || fact.pairingGeneration !== current.pairingGeneration
      || current.usedLocalTransitionIds.has(fact.transitionId)) throw new Error("AUTH_FAILED");
    const used = new Set(current.usedLocalTransitionIds);
    used.add(fact.transitionId);
    return freezeState({ ...current, lifecycle: "active", activePairing: true, authorizationEpoch: next(current.authorizationEpoch), usedLocalTransitionIds: used });
  }
  if (event.kind === "pause" || event.kind === "emergency_stop") {
    return freezeState({ ...current, lifecycle: event.kind === "emergency_stop" ? "revoked" : "paused", activePairing: false, authorizationEpoch: next(current.authorizationEpoch) });
  }
  if (event.kind === "scope_policy_revoke" || event.kind === "scope_policy_regrant") {
    if (!text(event.capability)) throw new Error("SCHEMA_INVALID");
    const scopes = new Map(current.scopeRevisions);
    scopes.set(event.capability, next(scopes.get(event.capability) ?? 0n));
    return freezeState({ ...current, authorizationEpoch: next(current.authorizationEpoch), scopeRevisions: freezeMap(scopes) });
  }
  if (event.kind === "bridge_command_key_activated") {
    const fact = event.fact;
    if (!fact || fact.owner !== "bridge-command" || fact.pairingGeneration !== current.pairingGeneration
      || fact.adapterCredentialGeneration !== null || !text(fact.factId) || !text(fact.credentialId)
      || (current.boundBridgeCredentialId !== null && fact.credentialId !== current.boundBridgeCredentialId)) throw new Error("AUTH_FAILED");
    return freezeState({ ...current, boundBridgeCredentialId: fact.credentialId, authorizationEpoch: next(current.authorizationEpoch) });
  }
  if (event.kind === "system_permission_change" || event.kind === "backend_credential_change") {
    return freezeState({ ...current, authorizationEpoch: next(current.authorizationEpoch) });
  }
  throw new Error("SCHEMA_INVALID");
}

export function revisionSnapshot(state: AuthorizationRevisionState): RevisionSnapshot {
  return Object.freeze({ pairingGeneration: state.pairingGeneration, authorizationEpoch: state.authorizationEpoch, scopeRevisions: freezeMap(state.scopeRevisions) });
}

export function revisionsEqual(left: RevisionSnapshot, right: RevisionSnapshot): boolean {
  if (left.pairingGeneration !== right.pairingGeneration || left.authorizationEpoch !== right.authorizationEpoch || left.scopeRevisions.size !== right.scopeRevisions.size) return false;
  for (const [scope, revision] of left.scopeRevisions) if (right.scopeRevisions.get(scope) !== revision) return false;
  return true;
}
