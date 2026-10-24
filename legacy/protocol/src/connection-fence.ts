export interface ConnectionFenceKey {
  readonly credentialId: string;
  readonly pairingGeneration: bigint;
}

const connectionLeaseBrand: unique symbol = Symbol("connection-lease");
export type ConnectionLease = Readonly<{
  readonly [connectionLeaseBrand]: true;
}>;

export interface ConnectionAllocation {
  readonly generation: bigint;
  readonly fenceRevision: bigint;
  readonly lease: ConnectionLease;
}

export type ConnectionLeaseInspection =
  | { kind: "current"; generation: bigint; fenceRevision: bigint }
  | { kind: "fenced" };

export interface ConnectionFenceStore {
  allocateNext(
    key: ConnectionFenceKey,
    connectionId: string,
    transportProfileId: string,
  ): Promise<
    | { kind: "allocated"; allocation: ConnectionAllocation }
    | { kind: "exhausted" }
  >;
  inspect(lease: ConnectionLease): Promise<ConnectionLeaseInspection>;
}

export interface DeterministicConnectionFenceSnapshot {
  readonly generation: bigint;
  readonly fenceRevision: bigint;
  readonly connectionId: string | null;
  readonly transportProfileId: string | null;
  readonly leasePersistenceId?: string | null;
}

type OwnedLease = ConnectionLease;

const validateLeasePersistenceId = (value: string): string => {
  const decoded = Buffer.from(value, "base64url");
  if (decoded.byteLength !== 32 || Buffer.from(decoded).toString("base64url") !== value) throw new Error("INTEGRITY_FAILED");
  return value;
};

/** A deterministic, restartable reference implementation used to execute the
 * fencing contract without pretending to be a production database. */
export class DeterministicConnectionFenceStore implements ConnectionFenceStore {
  readonly #key: ConnectionFenceKey;
  #state: DeterministicConnectionFenceSnapshot;
  readonly #leases = new WeakMap<object, DeterministicConnectionFenceSnapshot>();
  readonly #leaseIds = new WeakMap<object, string>();
  readonly #leaseIdSource: () => string;

  constructor(key: ConnectionFenceKey, snapshot: DeterministicConnectionFenceSnapshot, options: Readonly<{ leaseIdSource?: (() => string) | undefined }> = {}) {
    this.#key = Object.freeze({ ...key });
    this.#state = Object.freeze({ ...snapshot });
    this.#leaseIdSource = options.leaseIdSource ?? (() => randomBytes(32).toString("base64url"));
  }

  snapshot(): DeterministicConnectionFenceSnapshot {
    return Object.freeze({ ...this.#state });
  }

  async allocateNext(
    key: ConnectionFenceKey,
    connectionId: string,
    transportProfileId: string,
  ): Promise<{ kind: "allocated"; allocation: ConnectionAllocation } | { kind: "exhausted" }> {
    if (key.credentialId !== this.#key.credentialId
      || key.pairingGeneration !== this.#key.pairingGeneration
      || this.#state.generation === 18_446_744_073_709_551_615n
      || this.#state.fenceRevision === 18_446_744_073_709_551_615n) {
      return { kind: "exhausted" };
    }
    const leasePersistenceId = validateLeasePersistenceId(this.#leaseIdSource());
    const next = Object.freeze({
      generation: this.#state.generation + 1n,
      fenceRevision: this.#state.fenceRevision + 1n,
      connectionId,
      transportProfileId,
      leasePersistenceId,
    });
    this.#state = next;
    const lease = this.#mintLease(next);
    this.#leases.set(lease, next);
    return {
      kind: "allocated",
      allocation: Object.freeze({ generation: next.generation, fenceRevision: next.fenceRevision, lease }),
    };
  }

  #mintLease(snapshot: DeterministicConnectionFenceSnapshot): OwnedLease {
    const lease: OwnedLease = Object.freeze({ [connectionLeaseBrand]: true as const });
    this.#leases.set(lease, snapshot);
    const persistedId = validateLeasePersistenceId(snapshot.leasePersistenceId ?? this.#leaseIdSource());
    this.#leaseIds.set(lease, persistedId);
    return lease;
  }

  restoreCurrentLease(): ConnectionLease {
    if (this.#state.connectionId === null || this.#state.transportProfileId === null) throw new Error("CONNECTION_FENCED");
    return this.#mintLease(this.#state);
  }

  persistenceId(lease: ConnectionLease): string | null {
    return this.#leaseIds.get(lease) ?? null;
  }

  async inspect(lease: ConnectionLease): Promise<ConnectionLeaseInspection> {
    const allocation = this.#leases.get(lease);
    return allocation !== undefined
      && allocation.generation === this.#state.generation
      && allocation.fenceRevision === this.#state.fenceRevision
      && allocation.connectionId === this.#state.connectionId
      && allocation.transportProfileId === this.#state.transportProfileId
      ? { kind: "current", generation: allocation.generation, fenceRevision: allocation.fenceRevision }
      : { kind: "fenced" };
  }
}

export async function allocateConnectionGeneration(
  store: ConnectionFenceStore,
  key: ConnectionFenceKey,
  connectionId: string,
  transportProfileId: string,
): Promise<
  | { ok: true; allocation: ConnectionAllocation }
  | { ok: false; error: "CONNECTION_FENCED" }
> {
  const decision = await store.allocateNext(key, connectionId, transportProfileId);
  return decision.kind === "allocated"
    ? { ok: true, allocation: decision.allocation }
    : { ok: false, error: "CONNECTION_FENCED" };
}

export function fenceConnection(
  inspection: ConnectionLeaseInspection,
  allocatedGeneration: bigint,
  headerGeneration: bigint,
): { ok: true } | { ok: false; error: "CONNECTION_FENCED" } {
  if (inspection.kind !== "current"
    || inspection.generation !== allocatedGeneration
    || headerGeneration !== allocatedGeneration) {
    return { ok: false, error: "CONNECTION_FENCED" };
  }
  return { ok: true };
}
/// <reference types="node" />

import { randomBytes } from "node:crypto";
