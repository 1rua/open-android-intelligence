import {
  BridgeServiceError,
  equalIdentity,
  freezeRecord,
  type AuthorizationDecision,
  type Authorize,
  type BridgeSessionIdentity,
  type NotificationEventV1,
  type NotificationFilter,
  type NotificationRecordV1,
} from "./service-types.js";
import {
  PairingService,
  type PairingTicket,
} from "./pairing-service.js";
import { NotificationStore } from "./notification-store.js";
import { OperationDispatcher, type OperationDispatcherPort } from "./operation-dispatch.js";
import { SubscriptionStore } from "./subscription-store.js";

export type { BridgeSessionIdentity, NotificationEventV1, NotificationFilter, NotificationRecordV1 } from "./service-types.js";

export type NotificationServiceOptions = Readonly<{
  pairing: PairingService;
  operations?: OperationDispatcherPort;
  store?: NotificationStore;
  subscriptions?: SubscriptionStore;
  authorize?: Authorize;
}>;

export type NotificationQueryRequest = Readonly<{
  operationId: string;
  session: BridgeSessionIdentity;
  mode: "on_demand" | "auto_send";
  limit: number;
  policyRevision: bigint;
  filter?: NotificationFilter;
}>;

export type NotificationSubscribeRequest = Readonly<{
  subscriptionId: string;
  session: BridgeSessionIdentity;
  policyRevision: bigint;
  filter?: NotificationFilter;
}>;

/**
 * WP-06 notification API/event routing seam. All state is process-local and
 * intentionally replaceable by a durable Bridge runtime implementation.
 */
export class NotificationService {
  readonly #pairing: PairingService;
  readonly #operations: OperationDispatcherPort;
  readonly #store: NotificationStore;
  readonly #subscriptions: SubscriptionStore;
  readonly #authorize: Authorize;
  #active = new Map<string, BridgeSessionIdentity>();

  constructor(options: NotificationServiceOptions) {
    this.#pairing = options.pairing;
    this.#operations = options.operations ?? new OperationDispatcher();
    this.#store = options.store ?? new NotificationStore();
    this.#subscriptions = options.subscriptions ?? new SubscriptionStore();
    this.#authorize = options.authorize ?? (() => ({ allowed: false, policyRevision: 0n, reason: "NO_AUTHORIZER" }));
  }

  pair(ticket: PairingTicket, sessionOverrides: Partial<BridgeSessionIdentity> = {}): BridgeSessionIdentity {
    const accepted = this.#pairing.acceptTicket(ticket);
    const session = freezeRecord({
      tenantId: accepted.tenantId,
      humanPrincipalId: accepted.humanPrincipalId,
      deviceId: accepted.deviceId,
      agentInstanceId: sessionOverrides.agentInstanceId ?? "agent-a",
      workspaceId: sessionOverrides.workspaceId ?? "workspace-a",
      sessionId: sessionOverrides.sessionId ?? "session-a",
      ...(sessionOverrides.jobId === undefined ? {} : { jobId: sessionOverrides.jobId }),
      pairingGeneration: accepted.pairingGeneration,
      policyAttestationRevision: accepted.policyAttestationRevision,
    });
    this.#active.set(this.#baseKey(session), session);
    return session;
  }

  async query(request: NotificationQueryRequest): Promise<readonly NotificationRecordV1[]> {
    this.#assertSession(request.session);
    await this.#assertAuthorized("mobile.notifications.query", request.session, request.policyRevision, request.filter);
    return this.#operations.execute({
      operationId: request.operationId,
      session: request.session,
      parameters: { mode: request.mode, limit: request.limit, filter: request.filter, policyRevision: request.policyRevision },
    }, () => request.mode === "on_demand"
      ? this.#store.read(request.session.deviceId, request.limit, request.filter)
      : Object.freeze([]));
  }

  async subscribe(request: NotificationSubscribeRequest): Promise<Readonly<{ subscriptionId: string }>> {
    this.#assertSession(request.session);
    await this.#assertAuthorized("mobile.notifications.subscribe", request.session, request.policyRevision, request.filter);
    return this.#subscriptions.subscribe({ subscriptionId: request.subscriptionId, session: request.session, filter: request.filter });
  }

  async unsubscribe(subscriptionId: string, session: BridgeSessionIdentity, policyRevision: bigint): Promise<Readonly<{ subscriptionId: string; removed: boolean }>> {
    this.#assertSession(session);
    await this.#assertAuthorized("mobile.notifications.unsubscribe", session, policyRevision);
    return freezeRecord({ subscriptionId, removed: this.#subscriptions.unsubscribe(subscriptionId, session) });
  }

  ingest(session: BridgeSessionIdentity, record: NotificationRecordV1): boolean {
    this.#assertSession(session);
    return this.#store.append(session.deviceId, record);
  }

  async publish(subscriptionId: string, session: BridgeSessionIdentity, record: NotificationRecordV1, policyRevision?: bigint): Promise<NotificationEventV1 | null> {
    if (policyRevision === undefined) throw new BridgeServiceError("AUTHORIZATION_REQUIRED");
    return this.publishAuthorized(subscriptionId, session, record, policyRevision);
  }

  /** Recheck a current Task-6 grant before forwarding a device event. */
  async publishAuthorized(subscriptionId: string, session: BridgeSessionIdentity, record: NotificationRecordV1, policyRevision: bigint): Promise<NotificationEventV1 | null> {
    this.#assertSession(session);
    await this.#assertAuthorized("mobile.notifications.subscribe", session, policyRevision);
    // Authorization is asynchronous; the pairing may be revoked or fenced
    // while it is pending. Revalidate immediately before egress.
    this.#assertSession(session);
    return this.#subscriptions.publish(subscriptionId, session, record);
  }

  async acknowledge(input: Readonly<{
    subscriptionId: string;
    eventId: string;
    session: BridgeSessionIdentity;
    sourceEpoch: bigint;
    cursor: bigint;
  }>): Promise<NotificationEventV1> {
    this.#assertSession(input.session);
    return this.#subscriptions.acknowledge(input);
  }

  pendingEvents(subscriptionId: string, session: BridgeSessionIdentity): readonly NotificationEventV1[] {
    this.#assertSession(session);
    return this.#subscriptions.pending(subscriptionId, session);
  }

  operationClaims(): readonly Readonly<{ operationId: string; claims: number }>[] {
    return this.#operations.claims();
  }

  #assertSession(session: BridgeSessionIdentity): void {
    const active = this.#active.get(this.#baseKey(session));
    if (!active || !equalIdentity(active, session)
      || active.pairingGeneration !== session.pairingGeneration
      || active.policyAttestationRevision !== session.policyAttestationRevision) throw new BridgeServiceError("CONNECTION_FENCED");
    const paired = this.#pairing.current(session);
    if (!paired || paired.pairingGeneration !== session.pairingGeneration
      || paired.policyAttestationRevision !== session.policyAttestationRevision) throw new BridgeServiceError("CONNECTION_FENCED");
  }

  async #assertAuthorized(capability: "mobile.notifications.query" | "mobile.notifications.subscribe" | "mobile.notifications.unsubscribe", session: BridgeSessionIdentity, policyRevision: bigint, filter?: NotificationFilter): Promise<void> {
    if (policyRevision !== session.policyAttestationRevision) throw new BridgeServiceError("AUTHORIZATION_REVISION_STALE");
    const decision: AuthorizationDecision = await this.#authorize({ capability, session, policyRevision, ...(filter === undefined ? {} : { filter }) });
    if (decision.policyRevision !== policyRevision) throw new BridgeServiceError("AUTHORIZATION_REVISION_STALE");
    if (!decision.allowed) throw new BridgeServiceError(decision.reason ?? "NOT_AUTHORIZED");
  }

  #baseKey(session: BridgeSessionIdentity): string {
    return [session.tenantId, session.humanPrincipalId, session.deviceId, session.agentInstanceId, session.workspaceId, session.sessionId, session.jobId ?? ""].join("\u0000");
  }
}

export type { PairingTicket } from "./pairing-service.js";
