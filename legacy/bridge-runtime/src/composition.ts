import {
  assertDurableBridgeStore,
  DURABLE_BRIDGE_STORE_PORT,
  type DurableBridgeStore,
  type DurableBridgeTransaction,
} from "../../bridge-contract/src/durable-store.js";
import { assertConnectedSqliteBridgeAdapter, type SqliteBridgeAdapterPort } from "../../bridge-contract/src/persistence.js";
import { isNodeSqliteLeaseCoordinator, NodeSqliteBridgeAdapter } from "./node-sqlite-adapter.js";
import { LocalPairingTicketVerifier } from "./local-pairing-ticket-verifier.js";
import type { PairingTicket, PairingTicketInput } from "../../bridge-contract/src/pairing-service.js";
import {
  NotificationService,
  type NotificationServiceOptions,
} from "../../bridge-contract/src/notification-service.js";
import { PairingService } from "../../bridge-contract/src/pairing-service.js";
import { BridgeServiceError, type Authorize, type BridgeIdentity } from "../../bridge-contract/src/service-types.js";
import { NotificationStore } from "../../bridge-contract/src/notification-store.js";
import { SubscriptionStore } from "../../bridge-contract/src/subscription-store.js";
import {
  assertDurableOperationDispatcher,
  DurableOperationDispatcher,
  type DurableOperationDispatcher as DurableOperationDispatcherType,
} from "./durable-operation-dispatcher.js";
import { DurableBridgeStateRepositories } from "./durable-state-repositories.js";
import {
  assertBridgeLease,
  assertConnectedBridgeLeaseCoordinator,
  assertConnectedPairingTicketVerifier,
  type BridgeLease,
  type BridgeLeaseCoordinatorPort,
  type PairingTicketVerifierPort,
} from "./production-ports.js";

export type DurableBridgeCompositionOptions = Readonly<{
  /** Unknown is intentional: an unmarked process-local store must fail closed. */
  durableStore: unknown;
  pairing: PairingService;
  /** Omit to open a dispatcher from the supplied durable store. */
  operations?: unknown;
  /** These remain explicit process-local fixture seams until their adapters exist. */
  notificationStore?: NotificationStore;
  subscriptionStore?: SubscriptionStore;
  authorize?: Authorize;
}>;

export type DurableBridgeComposition = Readonly<{
  durableStore: DurableBridgeStore;
  operations: DurableOperationDispatcherType;
  notifications: NotificationService;
  /** The only service namespace persisted by this source/runtime slice. */
  durableNamespaces: readonly ["operation.claims"];
  /** Domains still process-local and therefore not production-ready. */
  processLocalNamespaces: readonly [
    "pairing.tickets",
    "pairing.bindings",
    "notification.records",
    "notification.positions",
    "subscription.bindings",
    "subscription.events",
  ];
}>;

/**
 * Explicit Bridge composition root for the currently durable service slice.
 *
 * The durable store and operation dispatcher are mandatory at this boundary;
 * passing `NotificationStore` or `OperationDispatcher` as either one fails
 * closed. Pairing/notification/subscription stores are accepted only as
 * explicitly named process-local fixture seams and are surfaced in the
 * returned metadata. This is not a production deployment claim.
 */
export const createDurableBridgeComposition = async (
  options: DurableBridgeCompositionOptions,
): Promise<DurableBridgeComposition> => {
  if (!options || typeof options !== "object") throw new BridgeServiceError("BRIDGE_COMPOSITION_OPTIONS_INVALID");
  const durableStore = assertDurableBridgeStore(options.durableStore);
  const operations = options.operations === undefined
    ? await DurableOperationDispatcher.open({ store: durableStore })
    : assertDurableOperationDispatcher(options.operations);
  const notificationOptions: NotificationServiceOptions = {
    pairing: options.pairing,
    operations,
    ...(options.notificationStore === undefined ? {} : { store: options.notificationStore }),
    ...(options.subscriptionStore === undefined ? {} : { subscriptions: options.subscriptionStore }),
    ...(options.authorize === undefined ? {} : { authorize: options.authorize }),
  };
  return Object.freeze({
    durableStore,
    operations,
    notifications: new NotificationService(notificationOptions),
    durableNamespaces: Object.freeze(["operation.claims"] as const),
    processLocalNamespaces: Object.freeze([
      "pairing.tickets",
      "pairing.bindings",
      "notification.records",
      "notification.positions",
      "subscription.bindings",
      "subscription.events",
    ] as const),
  });
};

const FENCED_COMPOSITION_SCOPE = "bridge.runtime" as const;

class FencedDurableBridgeStore implements DurableBridgeStore {
  readonly port = DURABLE_BRIDGE_STORE_PORT;
  readonly durability = "durable" as const;
  readonly #leases: BridgeLeaseCoordinatorPort;
  #lease: BridgeLease;

  constructor(leases: BridgeLeaseCoordinatorPort, lease: BridgeLease) {
    this.#leases = leases;
    this.#lease = lease;
  }

  transact<T>(scope: string, work: (transaction: DurableBridgeTransaction) => Promise<T> | T): Promise<T> {
    return this.#leases.transact(this.#lease, scope, work);
  }

  async renew(): Promise<BridgeLease> {
    const renewed = await this.#leases.renew(this.#lease);
    this.#lease = assertBridgeLease(renewed, { scope: this.#lease.scope, ownerId: this.#lease.ownerId });
    return this.#lease;
  }

  close(): Promise<void> {
    return this.#leases.release(this.#lease);
  }
}

export type FencedDurableBridgeCompositionOptions = Readonly<{
  persistence: unknown;
  leases: unknown;
  pairingVerifier: unknown;
  ownerId: string;
  leaseTtlMs: number;
  clock?: () => number;
}>;

export type FencedDurableBridgeComposition = Readonly<{
  persistence: SqliteBridgeAdapterPort;
  state: DurableBridgeStateRepositories;
  operations: DurableOperationDispatcherType;
  pairing: Readonly<{
    accept(candidate: unknown): Promise<PairingTicketInput>;
    current(identity: BridgeIdentity): Promise<PairingTicketInput | null>;
  }>;
  durableNamespaces: readonly [
    "pairing.tickets",
    "pairing.bindings",
    "notification.records",
    "notification.positions",
    "subscription.bindings",
    "subscription.events",
    "operation.claims",
    "operation.replay-associations",
  ];
  productionClaim: "single-host-production" | "source-seam-only";
  pendingDependencyLocks: readonly string[];
  renewLease(): Promise<BridgeLease>;
  close(): Promise<void>;
}>;

/**
 * Production-shaped, fail-closed composition boundary.
 *
 * Construction requires externally connected SQLite, ticket-verification and
 * lease ports. The repository does not ship any of those adapters and this
 * function therefore remains source-level composition evidence only.
 */
export const createFencedDurableBridgeComposition = async (
  options: FencedDurableBridgeCompositionOptions,
): Promise<FencedDurableBridgeComposition> => {
  if (!options || typeof options !== "object") throw new BridgeServiceError("BRIDGE_COMPOSITION_OPTIONS_INVALID");
  const persistence = assertConnectedSqliteBridgeAdapter(options.persistence);
  const leases = assertConnectedBridgeLeaseCoordinator(options.leases);
  const pairingVerifier: PairingTicketVerifierPort = assertConnectedPairingTicketVerifier(options.pairingVerifier);
  if (typeof options.ownerId !== "string" || options.ownerId.length === 0) throw new BridgeServiceError("BRIDGE_LEASE_OWNER_INVALID");
  if (!Number.isSafeInteger(options.leaseTtlMs) || options.leaseTtlMs < 1) throw new BridgeServiceError("BRIDGE_LEASE_TTL_INVALID");
  const productionStack = persistence instanceof NodeSqliteBridgeAdapter
    && pairingVerifier instanceof LocalPairingTicketVerifier
    && isNodeSqliteLeaseCoordinator(leases);
  const acquired = assertBridgeLease(await leases.acquire({
    scope: FENCED_COMPOSITION_SCOPE,
    ownerId: options.ownerId,
    ttlMs: options.leaseTtlMs,
  }), { scope: FENCED_COMPOSITION_SCOPE, ownerId: options.ownerId });
  const fencedStore = new FencedDurableBridgeStore(leases, acquired);
  try {
    const state = await DurableBridgeStateRepositories.open({
      store: fencedStore,
      ...(options.clock === undefined ? {} : { clock: options.clock }),
    });
    const operations = await DurableOperationDispatcher.open({ store: fencedStore });
    return Object.freeze({
      persistence,
      state,
      operations,
      pairing: Object.freeze({
        accept: async (candidate: unknown): Promise<PairingTicketInput> => {
          const verified: PairingTicket = await pairingVerifier.verify(candidate);
          return state.pairing.acceptVerified(verified);
        },
        current: state.pairing.current.bind(state.pairing),
      }),
      durableNamespaces: Object.freeze([
        "pairing.tickets",
        "pairing.bindings",
        "notification.records",
        "notification.positions",
        "subscription.bindings",
        "subscription.events",
        "operation.claims",
        "operation.replay-associations",
      ] as const),
      productionClaim: productionStack ? "single-host-production" as const : "source-seam-only" as const,
      pendingDependencyLocks: Object.freeze(productionStack ? [] : ["MVP-DEP-BRIDGE"] as const),
      renewLease: fencedStore.renew.bind(fencedStore),
      close: fencedStore.close.bind(fencedStore),
    });
  } catch (caught) {
    await fencedStore.close().catch(() => undefined);
    throw caught;
  }
};
