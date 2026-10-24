import type { DurableBridgeTransaction } from "../../bridge-contract/src/durable-store.js";
import type { PairingTicket } from "../../bridge-contract/src/pairing-service.js";
import { BridgeServiceError } from "../../bridge-contract/src/service-types.js";

export const BRIDGE_LEASE_COORDINATOR_PORT = "open-android-intelligence.bridge-lease-coordinator.v1" as const;
export const PAIRING_TICKET_VERIFIER_PORT = "open-android-intelligence.pairing-ticket-verifier.v1" as const;

export type BridgeLease = Readonly<{
  scope: string;
  ownerId: string;
  /** Adapter-issued, monotonically increasing token for this scope. */
  fencingToken: bigint;
  expiresAtMs: number;
  /** Retention hint used by renew implementations; optional for legacy port fakes. */
  ttlMs?: number;
}>;

/**
 * External multi-process lease boundary.
 *
 * `transact` must validate the lease and execute the callback in the same
 * database transaction. A stale token must never reach `work`. This package
 * intentionally supplies no implementation because a process-local mutex or
 * filesystem lock cannot satisfy this contract.
 */
export interface BridgeLeaseCoordinatorPort {
  readonly port: typeof BRIDGE_LEASE_COORDINATOR_PORT;
  readonly status: "external-coordinator-required" | "connected";
  acquire(input: Readonly<{ scope: string; ownerId: string; ttlMs: number }>): Promise<BridgeLease>;
  renew(lease: BridgeLease): Promise<BridgeLease>;
  transact<T>(
    lease: BridgeLease,
    scope: string,
    work: (transaction: DurableBridgeTransaction) => Promise<T> | T,
  ): Promise<T>;
  release(lease: BridgeLease): Promise<void>;
}

/** Authenticated ticket verification boundary backed by the future secret store. */
export interface PairingTicketVerifierPort {
  readonly port: typeof PAIRING_TICKET_VERIFIER_PORT;
  readonly status: "external-secret-store-required" | "connected";
  verify(candidate: unknown): Promise<PairingTicket>;
}

const callable = (value: unknown): value is (...args: never[]) => unknown => typeof value === "function";

export const assertConnectedBridgeLeaseCoordinator = (value: unknown): BridgeLeaseCoordinatorPort => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new BridgeServiceError("BRIDGE_LEASE_COORDINATOR_REQUIRED");
  const candidate = value as Partial<BridgeLeaseCoordinatorPort>;
  if (candidate.port !== BRIDGE_LEASE_COORDINATOR_PORT || candidate.status !== "connected"
    || !callable(candidate.acquire) || !callable(candidate.renew)
    || !callable(candidate.transact) || !callable(candidate.release)) {
    throw new BridgeServiceError(candidate.port === BRIDGE_LEASE_COORDINATOR_PORT
      ? "BRIDGE_LEASE_COORDINATOR_PENDING"
      : "BRIDGE_LEASE_COORDINATOR_REQUIRED");
  }
  return candidate as BridgeLeaseCoordinatorPort;
};

export const assertConnectedPairingTicketVerifier = (value: unknown): PairingTicketVerifierPort => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new BridgeServiceError("PAIRING_TICKET_VERIFIER_REQUIRED");
  const candidate = value as Partial<PairingTicketVerifierPort>;
  if (candidate.port !== PAIRING_TICKET_VERIFIER_PORT || !callable(candidate.verify)) {
    throw new BridgeServiceError("PAIRING_TICKET_VERIFIER_REQUIRED");
  }
  if (candidate.status !== "connected") throw new BridgeServiceError("PAIRING_TICKET_VERIFIER_PENDING");
  return candidate as PairingTicketVerifierPort;
};

export const assertBridgeLease = (value: unknown, expected: Readonly<{ scope: string; ownerId: string }>): BridgeLease => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new BridgeServiceError("BRIDGE_LEASE_INVALID");
  const lease = value as Partial<BridgeLease>;
  if (lease.scope !== expected.scope || lease.ownerId !== expected.ownerId
    || typeof lease.fencingToken !== "bigint" || lease.fencingToken < 1n
    || typeof lease.expiresAtMs !== "number" || !Number.isFinite(lease.expiresAtMs)
    || (lease.ttlMs !== undefined && (!Number.isSafeInteger(lease.ttlMs) || lease.ttlMs < 1))) {
    throw new BridgeServiceError("BRIDGE_LEASE_INVALID");
  }
  return Object.freeze({
    scope: lease.scope,
    ownerId: lease.ownerId,
    fencingToken: lease.fencingToken,
    expiresAtMs: lease.expiresAtMs,
    ...(lease.ttlMs === undefined ? {} : { ttlMs: lease.ttlMs }),
  });
};
