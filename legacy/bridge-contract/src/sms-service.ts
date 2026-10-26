import {
  BridgeServiceError,
  equalIdentity,
  freezeRecord,
  identityKey,
  type AuthorizationDecision,
  type Authorize,
  type BridgeSessionIdentity,
  type CapabilityName,
  type SmsEventV1,
  type SmsRecordV1,
} from "./service-types.js";
import { PairingService, type PairingTicket } from "./pairing-service.js";
import { OperationDispatcher, type OperationDispatcherPort } from "./operation-dispatch.js";
import { SmsStore, validateSmsRecord } from "./sms-store.js";
import { SmsSubscriptionStore } from "./sms-subscription-store.js";

export type SmsServiceOptions = Readonly<{
  pairing: PairingService;
  operations?: OperationDispatcherPort;
  store?: SmsStore;
  subscriptions?: SmsSubscriptionStore;
  authorize?: Authorize;
}>;

export type SmsQueryRequest = Readonly<{
  operationId: string;
  session: BridgeSessionIdentity;
  limit: number;
  policyRevision: bigint;
}>;

export type SmsSubscribeRequest = Readonly<{
  subscriptionId: string;
  session: BridgeSessionIdentity;
  policyRevision: bigint;
}>;

/** Paired, capability-closed SMS service with no endpoint or generic execution surface. */
export class SmsService {
  readonly #pairing: PairingService;
  readonly #operations: OperationDispatcherPort;
  readonly #store: SmsStore;
  readonly #subscriptions: SmsSubscriptionStore;
  readonly #authorize: Authorize;
  #active = new Map<string, BridgeSessionIdentity>();

  constructor(options: SmsServiceOptions) {
    this.#pairing = options.pairing;
    this.#operations = options.operations ?? new OperationDispatcher();
    this.#store = options.store ?? new SmsStore();
    this.#subscriptions = options.subscriptions ?? new SmsSubscriptionStore();
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

  async query(request: SmsQueryRequest): Promise<readonly SmsRecordV1[]> {
    this.#assertSession(request.session);
    await this.#assertAuthorized("mobile.sms.query", request.session, request.policyRevision);
    this.#assertSession(request.session);
    const result = await this.#operations.execute({
      operationId: request.operationId,
      session: request.session,
      parameters: { limit: request.limit, policyRevision: request.policyRevision },
    }, () => this.#store.read(identityKey(request.session), request.limit));
    this.#assertSession(request.session);
    return result;
  }

  async subscribe(request: SmsSubscribeRequest): Promise<Readonly<{ subscriptionId: string }>> {
    this.#assertSession(request.session);
    await this.#assertAuthorized("mobile.sms.subscribe", request.session, request.policyRevision);
    this.#assertSession(request.session);
    return this.#subscriptions.subscribe({ subscriptionId: request.subscriptionId, session: request.session });
  }

  async unsubscribe(subscriptionId: string, session: BridgeSessionIdentity, policyRevision: bigint): Promise<Readonly<{ subscriptionId: string; removed: boolean }>> {
    this.#assertSession(session);
    await this.#assertAuthorized("mobile.sms.unsubscribe", session, policyRevision);
    this.#assertSession(session);
    return freezeRecord({ subscriptionId, removed: this.#subscriptions.unsubscribe(subscriptionId, session) });
  }

  ingest(session: BridgeSessionIdentity, record: SmsRecordV1): boolean {
    this.#assertSession(session);
    return this.#store.append(identityKey(session), record);
  }

  async publish(subscriptionId: string, session: BridgeSessionIdentity, record: SmsRecordV1, policyRevision?: bigint): Promise<SmsEventV1> {
    if (policyRevision === undefined) throw new BridgeServiceError("AUTHORIZATION_REQUIRED");
    return this.publishAuthorized(subscriptionId, session, record, policyRevision);
  }

  async publishAuthorized(subscriptionId: string, session: BridgeSessionIdentity, record: SmsRecordV1, policyRevision: bigint): Promise<SmsEventV1> {
    this.#assertSession(session);
    validateSmsRecord(record);
    if (record.policyRevision !== policyRevision) throw new BridgeServiceError("SMS_POLICY_REVISION_MISMATCH");
    await this.#assertAuthorized("mobile.sms.subscribe", session, policyRevision);
    // Pairing and policy may change while authorization is pending. This is the
    // final synchronous check before the event enters the egress ledger.
    this.#assertSession(session);
    return this.#subscriptions.publish(subscriptionId, session, record);
  }

  async acknowledge(input: Readonly<{
    subscriptionId: string;
    eventId: string;
    session: BridgeSessionIdentity;
    sourceEpoch: bigint;
    messageAtEpochMs: bigint;
    cursorProviderId: bigint;
  }>): Promise<SmsEventV1> {
    this.#assertSession(input.session);
    return this.#subscriptions.acknowledge(input);
  }

  pendingEvents(subscriptionId: string, session: BridgeSessionIdentity): readonly SmsEventV1[] {
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

  async #assertAuthorized(capability: Extract<CapabilityName, `mobile.sms.${string}`>, session: BridgeSessionIdentity, policyRevision: bigint): Promise<void> {
    if (policyRevision !== session.policyAttestationRevision) throw new BridgeServiceError("AUTHORIZATION_REVISION_STALE");
    const decision: AuthorizationDecision = await this.#authorize({ capability, session, policyRevision });
    if (decision.policyRevision !== policyRevision) throw new BridgeServiceError("AUTHORIZATION_REVISION_STALE");
    if (!decision.allowed) throw new BridgeServiceError(decision.reason ?? "NOT_AUTHORIZED");
  }

  #baseKey(session: BridgeSessionIdentity): string {
    return [session.tenantId, session.humanPrincipalId, session.deviceId, session.agentInstanceId, session.workspaceId, session.sessionId, session.jobId ?? ""].join("\u0000");
  }
}

export type { BridgeSessionIdentity, SmsEventV1, SmsRecordV1 } from "./service-types.js";
export type { PairingTicket } from "./pairing-service.js";
