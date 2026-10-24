import {
  assertDurableBridgeStore,
  runDurableBridgeTransaction,
  type DurableBridgeStore,
  type DurableBridgeTransaction,
} from "../../bridge-contract/src/durable-store.js";
import {
  applyNotificationFilter,
  validateNotificationFilter,
  validateNotificationRecord,
} from "../../bridge-contract/src/notification-store.js";
import type { PairingTicket, PairingTicketInput } from "../../bridge-contract/src/pairing-service.js";
import {
  BridgeServiceError,
  equalIdentity,
  freezeRecord,
  identityKey,
  sessionKey,
  type BridgeIdentity,
  type BridgeSessionIdentity,
  type NotificationEventV1,
  type NotificationFilter,
  type NotificationRecordV1,
} from "../../bridge-contract/src/service-types.js";

type StoredSession = Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  deviceId: string;
  agentInstanceId: string;
  workspaceId: string;
  sessionId: string;
  jobId?: string;
  pairingGeneration: string;
  policyAttestationRevision: string;
}>;

type StoredPairingInput = Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  deviceId: string;
  bridgeFingerprint: string;
  pairingGeneration: string;
  policyAttestationRevision: string;
}>;

type StoredTicket = StoredPairingInput & Readonly<{
  ticketId: string;
  issuedAtMs: number;
  expiresAtMs: number;
}>;

type StoredNotification = Readonly<{
  kind: NotificationRecordV1["kind"];
  recordId: string;
  packageId: string | null;
  title: string | null;
  content: string | null;
  sourceEpoch: string;
  cursor: string;
  captureRevision: string;
}>;

type StoredSubscription = Readonly<{
  subscriptionId: string;
  session: StoredSession;
  filter?: NotificationFilter;
  lastSourceEpoch: string;
  lastCursor: string;
}>;

type StoredEvent = Readonly<{
  acknowledged: boolean;
  event: StoredNotification & Readonly<{
    eventId: string;
    subscriptionId: string;
    binding: StoredSession;
  }>;
}>;

const error = (code: string): BridgeServiceError => new BridgeServiceError(code);
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const exactKeys = (value: Record<string, unknown>, required: readonly string[], optional: readonly string[] = []): boolean => {
  const allowed = new Set([...required, ...optional]);
  return required.every((key) => Object.hasOwn(value, key))
    && Object.keys(value).every((key) => allowed.has(key));
};

const nonEmpty = (value: unknown, code: string): string => {
  if (typeof value !== "string" || value.length === 0) throw error(code);
  return value;
};

const decimal = (value: unknown, code: string, allowNegative = false): bigint => {
  if (typeof value !== "string" || !(allowNegative ? /^-?(?:0|[1-9][0-9]*)$/ : /^(?:0|[1-9][0-9]*)$/).test(value)) {
    throw error(code);
  }
  try {
    const parsed = BigInt(value);
    if (!allowNegative && parsed < 0n) throw error(code);
    return parsed;
  } catch (caught) {
    if (caught instanceof BridgeServiceError) throw caught;
    throw error(code);
  }
};

const assertSession = (value: BridgeSessionIdentity): void => {
  if (!isRecord(value)) throw error("DURABLE_SESSION_INVALID");
  for (const field of ["tenantId", "humanPrincipalId", "deviceId", "agentInstanceId", "workspaceId", "sessionId"] as const) {
    nonEmpty(value[field], "DURABLE_SESSION_INVALID");
  }
  if (value.jobId !== undefined) nonEmpty(value.jobId, "DURABLE_SESSION_INVALID");
  if (typeof value.pairingGeneration !== "bigint" || value.pairingGeneration < 0n
    || typeof value.policyAttestationRevision !== "bigint" || value.policyAttestationRevision < 0n) {
    throw error("DURABLE_SESSION_INVALID");
  }
};

const encodeSession = (value: BridgeSessionIdentity): StoredSession => {
  assertSession(value);
  return Object.freeze({
    tenantId: value.tenantId,
    humanPrincipalId: value.humanPrincipalId,
    deviceId: value.deviceId,
    agentInstanceId: value.agentInstanceId,
    workspaceId: value.workspaceId,
    sessionId: value.sessionId,
    ...(value.jobId === undefined ? {} : { jobId: value.jobId }),
    pairingGeneration: value.pairingGeneration.toString(10),
    policyAttestationRevision: value.policyAttestationRevision.toString(10),
  });
};

const decodeSession = (value: unknown, code = "DURABLE_SESSION_STATE_INVALID"): BridgeSessionIdentity => {
  if (!isRecord(value) || !exactKeys(value, [
    "tenantId", "humanPrincipalId", "deviceId", "agentInstanceId", "workspaceId", "sessionId",
    "pairingGeneration", "policyAttestationRevision",
  ], ["jobId"])) throw error(code);
  const decoded: BridgeSessionIdentity = {
    tenantId: nonEmpty(value.tenantId, code),
    humanPrincipalId: nonEmpty(value.humanPrincipalId, code),
    deviceId: nonEmpty(value.deviceId, code),
    agentInstanceId: nonEmpty(value.agentInstanceId, code),
    workspaceId: nonEmpty(value.workspaceId, code),
    sessionId: nonEmpty(value.sessionId, code),
    ...(value.jobId === undefined ? {} : { jobId: nonEmpty(value.jobId, code) }),
    pairingGeneration: decimal(value.pairingGeneration, code),
    policyAttestationRevision: decimal(value.policyAttestationRevision, code),
  };
  return freezeRecord(decoded);
};

const validateTicket = (value: PairingTicket): void => {
  if (!isRecord(value)) throw error("PAIRING_TICKET_INVALID");
  for (const field of ["ticketId", "tenantId", "humanPrincipalId", "deviceId", "bridgeFingerprint"] as const) {
    nonEmpty(value[field], "PAIRING_TICKET_INVALID");
  }
  if (typeof value.pairingGeneration !== "bigint" || value.pairingGeneration < 0n
    || typeof value.policyAttestationRevision !== "bigint" || value.policyAttestationRevision < 0n
    || typeof value.issuedAtMs !== "number" || !Number.isFinite(value.issuedAtMs)
    || typeof value.expiresAtMs !== "number" || !Number.isFinite(value.expiresAtMs)
    || value.expiresAtMs <= value.issuedAtMs) throw error("PAIRING_TICKET_INVALID");
};

const encodePairingInput = (value: PairingTicketInput): StoredPairingInput => Object.freeze({
  tenantId: value.tenantId,
  humanPrincipalId: value.humanPrincipalId,
  deviceId: value.deviceId,
  bridgeFingerprint: value.bridgeFingerprint,
  pairingGeneration: value.pairingGeneration.toString(10),
  policyAttestationRevision: value.policyAttestationRevision.toString(10),
});

const decodePairingInput = (value: unknown): PairingTicketInput => {
  const code = "DURABLE_PAIRING_STATE_INVALID";
  if (!isRecord(value) || !exactKeys(value, [
    "tenantId", "humanPrincipalId", "deviceId", "bridgeFingerprint", "pairingGeneration", "policyAttestationRevision",
  ])) throw error(code);
  return freezeRecord({
    tenantId: nonEmpty(value.tenantId, code),
    humanPrincipalId: nonEmpty(value.humanPrincipalId, code),
    deviceId: nonEmpty(value.deviceId, code),
    bridgeFingerprint: nonEmpty(value.bridgeFingerprint, code),
    pairingGeneration: decimal(value.pairingGeneration, code),
    policyAttestationRevision: decimal(value.policyAttestationRevision, code),
  });
};

const encodeTicket = (value: PairingTicket): StoredTicket => {
  validateTicket(value);
  return Object.freeze({ ...encodePairingInput(value), ticketId: value.ticketId, issuedAtMs: value.issuedAtMs, expiresAtMs: value.expiresAtMs });
};

const decodeTicket = (value: unknown): PairingTicket => {
  const code = "DURABLE_PAIRING_STATE_INVALID";
  if (!isRecord(value) || !exactKeys(value, [
    "tenantId", "humanPrincipalId", "deviceId", "bridgeFingerprint", "pairingGeneration", "policyAttestationRevision",
    "ticketId", "issuedAtMs", "expiresAtMs",
  ])) throw error(code);
  const input = decodePairingInput({
    tenantId: value.tenantId,
    humanPrincipalId: value.humanPrincipalId,
    deviceId: value.deviceId,
    bridgeFingerprint: value.bridgeFingerprint,
    pairingGeneration: value.pairingGeneration,
    policyAttestationRevision: value.policyAttestationRevision,
  });
  if (typeof value.issuedAtMs !== "number" || !Number.isFinite(value.issuedAtMs)
    || typeof value.expiresAtMs !== "number" || !Number.isFinite(value.expiresAtMs)
    || value.expiresAtMs <= value.issuedAtMs) throw error(code);
  return freezeRecord({ ...input, ticketId: nonEmpty(value.ticketId, code), issuedAtMs: value.issuedAtMs, expiresAtMs: value.expiresAtMs });
};

const encodeNotification = (record: NotificationRecordV1): StoredNotification => {
  validateNotificationRecord(record);
  return Object.freeze({
    ...record,
    sourceEpoch: record.sourceEpoch.toString(10),
    cursor: record.cursor.toString(10),
    captureRevision: record.captureRevision.toString(10),
  });
};

const decodeNotification = (value: unknown, code = "DURABLE_NOTIFICATION_STATE_INVALID"): NotificationRecordV1 => {
  if (!isRecord(value) || !exactKeys(value, [
    "kind", "recordId", "packageId", "title", "content", "sourceEpoch", "cursor", "captureRevision",
  ])) throw error(code);
  const decoded = {
    kind: value.kind,
    recordId: value.recordId,
    packageId: value.packageId,
    title: value.title,
    content: value.content,
    sourceEpoch: decimal(value.sourceEpoch, code),
    cursor: decimal(value.cursor, code),
    captureRevision: decimal(value.captureRevision, code),
  } as NotificationRecordV1;
  try {
    validateNotificationRecord(decoded);
  } catch {
    throw error(code);
  }
  return freezeRecord(decoded);
};

const comparePosition = (left: NotificationRecordV1, right: NotificationRecordV1): number =>
  left.sourceEpoch === right.sourceEpoch
    ? (left.cursor < right.cursor ? -1 : left.cursor > right.cursor ? 1 : 0)
    : (left.sourceEpoch < right.sourceEpoch ? -1 : 1);

const sameJson = (left: unknown, right: unknown): boolean => JSON.stringify(left) === JSON.stringify(right);
const notificationKey = (session: BridgeSessionIdentity, recordId: string): string => `${sessionKey(session)}\u0000${recordId}`;

export type DurableBridgeStateRepositoriesOptions = Readonly<{
  store: DurableBridgeStore;
  clock?: () => number;
}>;

export class DurablePairingRepository {
  readonly #store: DurableBridgeStore;
  readonly #clock: () => number;

  constructor(store: DurableBridgeStore, clock: () => number) {
    this.#store = store;
    this.#clock = clock;
  }

  async acceptVerified(candidate: PairingTicket): Promise<PairingTicketInput> {
    validateTicket(candidate);
    const now = this.#clock();
    if (!Number.isFinite(now)) throw error("PAIRING_CLOCK_INVALID");
    return runDurableBridgeTransaction(this.#store, "pairing.accept", async (transaction) => {
      const rawTicket = await transaction.read("pairing.tickets", candidate.ticketId);
      if (rawTicket !== null) {
        if (!isRecord(rawTicket) || !exactKeys(rawTicket, ["consumed", "ticket"]) || typeof rawTicket.consumed !== "boolean") {
          throw error("DURABLE_PAIRING_STATE_INVALID");
        }
        const stored = decodeTicket(rawTicket.ticket);
        if (!sameJson(encodeTicket(stored), encodeTicket(candidate))) throw error("PAIRING_TICKET_TAMPERED");
        if (rawTicket.consumed) throw error("PAIRING_TICKET_REPLAY");
      }
      if (now >= candidate.expiresAtMs) throw error("PAIRING_TICKET_EXPIRED");
      const key = identityKey(candidate);
      const rawBinding = await transaction.read("pairing.bindings", key);
      const previous = rawBinding === null ? null : decodePairingInput(rawBinding);
      if (previous && previous.bridgeFingerprint !== candidate.bridgeFingerprint) throw error("PAIRING_BINDING_MISMATCH");
      if (previous && candidate.pairingGeneration < previous.pairingGeneration) throw error("PAIRING_GENERATION_ROLLBACK");
      if (previous && candidate.pairingGeneration > previous.pairingGeneration + 1n) throw error("PAIRING_GENERATION_GAP");
      const accepted: PairingTicketInput = freezeRecord({
        tenantId: candidate.tenantId,
        humanPrincipalId: candidate.humanPrincipalId,
        deviceId: candidate.deviceId,
        bridgeFingerprint: candidate.bridgeFingerprint,
        pairingGeneration: candidate.pairingGeneration,
        policyAttestationRevision: candidate.policyAttestationRevision,
      });
      await transaction.write("pairing.tickets", candidate.ticketId, { ticket: encodeTicket(candidate), consumed: true });
      await transaction.write("pairing.bindings", key, encodePairingInput(accepted));
      return accepted;
    });
  }

  current(identity: BridgeIdentity): Promise<PairingTicketInput | null> {
    return runDurableBridgeTransaction(this.#store, "pairing.current", async (transaction) => {
      const value = await transaction.read("pairing.bindings", identityKey(identity));
      return value === null ? null : decodePairingInput(value);
    });
  }
}

export class DurableNotificationRepository {
  readonly #store: DurableBridgeStore;

  constructor(store: DurableBridgeStore) {
    this.#store = store;
  }

  async append(session: BridgeSessionIdentity, record: NotificationRecordV1): Promise<boolean> {
    assertSession(session);
    const encoded = encodeNotification(record);
    const binding = sessionKey(session);
    return runDurableBridgeTransaction(this.#store, "notification.append", async (transaction) => {
      const key = notificationKey(session, record.recordId);
      const rawExisting = await transaction.read("notification.records", key);
      if (rawExisting !== null) {
        if (!isRecord(rawExisting) || !exactKeys(rawExisting, ["record", "sessionKey"]) || rawExisting.sessionKey !== binding) {
          throw error("DURABLE_NOTIFICATION_STATE_INVALID");
        }
        if (!sameJson(rawExisting.record, encoded)) throw error("NOTIFICATION_RECORD_CONFLICT");
        return false;
      }
      const rawPosition = await transaction.read("notification.positions", binding);
      if (rawPosition !== null) {
        if (!isRecord(rawPosition) || !exactKeys(rawPosition, ["cursor", "recordId", "sessionKey", "sourceEpoch"])
          || rawPosition.sessionKey !== binding) throw error("DURABLE_NOTIFICATION_STATE_INVALID");
        const previous = {
          sourceEpoch: decimal(rawPosition.sourceEpoch, "DURABLE_NOTIFICATION_STATE_INVALID"),
          cursor: decimal(rawPosition.cursor, "DURABLE_NOTIFICATION_STATE_INVALID"),
        };
        if (record.sourceEpoch < previous.sourceEpoch || (record.sourceEpoch === previous.sourceEpoch && record.cursor <= previous.cursor)) {
          throw error("NOTIFICATION_CURSOR_REPLAY");
        }
      }
      await transaction.write("notification.records", key, { sessionKey: binding, record: encoded });
      await transaction.write("notification.positions", binding, {
        sessionKey: binding,
        recordId: record.recordId,
        sourceEpoch: encoded.sourceEpoch,
        cursor: encoded.cursor,
      });
      return true;
    });
  }

  async read(session: BridgeSessionIdentity, limit: number, filter?: NotificationFilter): Promise<readonly NotificationRecordV1[]> {
    assertSession(session);
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) throw error("LIMIT_INVALID");
    validateNotificationFilter(filter);
    const binding = sessionKey(session);
    return runDurableBridgeTransaction(this.#store, "notification.read", async (transaction) => {
      const output: NotificationRecordV1[] = [];
      for (const entry of await transaction.scan("notification.records")) {
        if (!isRecord(entry.value) || !exactKeys(entry.value, ["record", "sessionKey"])) throw error("DURABLE_NOTIFICATION_STATE_INVALID");
        if (entry.value.sessionKey !== binding) continue;
        const filtered = applyNotificationFilter(decodeNotification(entry.value.record), filter);
        if (filtered) output.push(filtered);
      }
      output.sort(comparePosition);
      return Object.freeze(output.slice(0, limit));
    });
  }
}

const decodeStoredSubscription = (value: unknown): StoredSubscription => {
  const code = "DURABLE_SUBSCRIPTION_STATE_INVALID";
  if (!isRecord(value) || !exactKeys(value, ["lastCursor", "lastSourceEpoch", "session", "subscriptionId"], ["filter"])) throw error(code);
  let filter: NotificationFilter | undefined;
  try {
    filter = validateNotificationFilter(value.filter as NotificationFilter | undefined);
  } catch {
    throw error(code);
  }
  return Object.freeze({
    subscriptionId: nonEmpty(value.subscriptionId, code),
    session: encodeSession(decodeSession(value.session, code)),
    ...(filter === undefined ? {} : { filter }),
    lastSourceEpoch: decimal(value.lastSourceEpoch, code).toString(10),
    lastCursor: decimal(value.lastCursor, code, true).toString(10),
  });
};

const decodeStoredEvent = (value: unknown): StoredEvent => {
  const code = "DURABLE_SUBSCRIPTION_STATE_INVALID";
  if (!isRecord(value) || !exactKeys(value, ["acknowledged", "event"]) || typeof value.acknowledged !== "boolean" || !isRecord(value.event)
    || !exactKeys(value.event, [
      "kind", "recordId", "packageId", "title", "content", "sourceEpoch", "cursor", "captureRevision",
      "eventId", "subscriptionId", "binding",
    ])) throw error(code);
  const notification = encodeNotification(decodeNotification({
    kind: value.event.kind,
    recordId: value.event.recordId,
    packageId: value.event.packageId,
    title: value.event.title,
    content: value.event.content,
    sourceEpoch: value.event.sourceEpoch,
    cursor: value.event.cursor,
    captureRevision: value.event.captureRevision,
  }, code));
  return Object.freeze({
    acknowledged: value.acknowledged,
    event: Object.freeze({
      ...notification,
      eventId: nonEmpty(value.event.eventId, code),
      subscriptionId: nonEmpty(value.event.subscriptionId, code),
      binding: encodeSession(decodeSession(value.event.binding, code)),
    }),
  });
};

const decodeEvent = (stored: StoredEvent): NotificationEventV1 => freezeRecord({
  ...decodeNotification({
    kind: stored.event.kind,
    recordId: stored.event.recordId,
    packageId: stored.event.packageId,
    title: stored.event.title,
    content: stored.event.content,
    sourceEpoch: stored.event.sourceEpoch,
    cursor: stored.event.cursor,
    captureRevision: stored.event.captureRevision,
  }, "DURABLE_SUBSCRIPTION_STATE_INVALID"),
  eventId: stored.event.eventId,
  subscriptionId: stored.event.subscriptionId,
  binding: decodeSession(stored.event.binding, "DURABLE_SUBSCRIPTION_STATE_INVALID"),
});

export class DurableSubscriptionRepository {
  readonly #store: DurableBridgeStore;

  constructor(store: DurableBridgeStore) {
    this.#store = store;
  }

  async subscribe(input: Readonly<{ subscriptionId: string; session: BridgeSessionIdentity; filter?: NotificationFilter }>): Promise<Readonly<{ subscriptionId: string }>> {
    nonEmpty(input.subscriptionId, "SUBSCRIPTION_ID_INVALID");
    assertSession(input.session);
    const filter = validateNotificationFilter(input.filter);
    const stored: StoredSubscription = Object.freeze({
      subscriptionId: input.subscriptionId,
      session: encodeSession(input.session),
      ...(filter === undefined ? {} : { filter }),
      lastSourceEpoch: "0",
      lastCursor: "-1",
    });
    return runDurableBridgeTransaction(this.#store, "subscription.subscribe", async (transaction) => {
      const existing = await transaction.read("subscription.bindings", input.subscriptionId);
      if (existing !== null) {
        const decoded = decodeStoredSubscription(existing);
        const existingSession = decodeSession(decoded.session, "DURABLE_SUBSCRIPTION_STATE_INVALID");
        if (!equalIdentity(existingSession, input.session)) throw error("SUBSCRIPTION_BINDING_MISMATCH");
        if (!sameJson(decoded.filter, filter)) throw error("SUBSCRIPTION_STATE_CONFLICT");
        return freezeRecord({ subscriptionId: input.subscriptionId });
      }
      await transaction.write("subscription.bindings", input.subscriptionId, stored);
      return freezeRecord({ subscriptionId: input.subscriptionId });
    });
  }

  async unsubscribe(subscriptionId: string, session: BridgeSessionIdentity): Promise<boolean> {
    nonEmpty(subscriptionId, "SUBSCRIPTION_ID_INVALID");
    assertSession(session);
    return runDurableBridgeTransaction(this.#store, "subscription.unsubscribe", async (transaction) => {
      const raw = await transaction.read("subscription.bindings", subscriptionId);
      if (raw === null) return false;
      const subscription = decodeStoredSubscription(raw);
      if (!equalIdentity(decodeSession(subscription.session, "DURABLE_SUBSCRIPTION_STATE_INVALID"), session)) {
        throw error("SUBSCRIPTION_BINDING_MISMATCH");
      }
      for (const entry of await transaction.scan("subscription.events")) {
        const stored = decodeStoredEvent(entry.value);
        if (stored.event.subscriptionId === subscriptionId) await transaction.remove("subscription.events", entry.key);
      }
      await transaction.remove("subscription.bindings", subscriptionId);
      return true;
    });
  }

  async publish(subscriptionId: string, session: BridgeSessionIdentity, record: NotificationRecordV1): Promise<NotificationEventV1 | null> {
    assertSession(session);
    validateNotificationRecord(record);
    return runDurableBridgeTransaction(this.#store, "subscription.publish", async (transaction) => {
      const raw = await transaction.read("subscription.bindings", subscriptionId);
      if (raw === null) throw error("SUBSCRIPTION_NOT_FOUND");
      const subscription = decodeStoredSubscription(raw);
      const boundSession = decodeSession(subscription.session, "DURABLE_SUBSCRIPTION_STATE_INVALID");
      if (!equalIdentity(boundSession, session)) throw error("SUBSCRIPTION_BINDING_MISMATCH");
      const lastSourceEpoch = decimal(subscription.lastSourceEpoch, "DURABLE_SUBSCRIPTION_STATE_INVALID");
      const lastCursor = decimal(subscription.lastCursor, "DURABLE_SUBSCRIPTION_STATE_INVALID", true);
      if (record.sourceEpoch < lastSourceEpoch || (record.sourceEpoch === lastSourceEpoch && record.cursor <= lastCursor)) {
        throw error(record.sourceEpoch < lastSourceEpoch ? "EVENT_SOURCE_EPOCH_STALE" : "EVENT_CURSOR_REPLAY");
      }
      const nextBinding: StoredSubscription = Object.freeze({
        ...subscription,
        lastSourceEpoch: record.sourceEpoch.toString(10),
        lastCursor: record.cursor.toString(10),
      });
      const filtered = applyNotificationFilter(record, subscription.filter);
      await transaction.write("subscription.bindings", subscriptionId, nextBinding);
      if (filtered === null) return null;
      const eventId = `event-${record.sourceEpoch.toString(10)}-${record.cursor.toString(10)}`;
      const event: NotificationEventV1 = freezeRecord({
        ...filtered,
        eventId,
        subscriptionId,
        binding: freezeRecord({ ...boundSession }),
      });
      const storedEvent: StoredEvent = Object.freeze({
        acknowledged: false,
        event: Object.freeze({ ...encodeNotification(filtered), eventId, subscriptionId, binding: encodeSession(boundSession) }),
      });
      const eventKey = `${subscriptionId}\u0000${eventId}`;
      const existing = await transaction.read("subscription.events", eventKey);
      if (existing !== null && !sameJson(decodeStoredEvent(existing), storedEvent)) throw error("EVENT_STATE_CONFLICT");
      await transaction.write("subscription.events", eventKey, storedEvent);
      return event;
    });
  }

  async acknowledge(input: Readonly<{
    subscriptionId: string;
    eventId: string;
    session: BridgeSessionIdentity;
    sourceEpoch: bigint;
    cursor: bigint;
  }>): Promise<NotificationEventV1> {
    assertSession(input.session);
    return runDurableBridgeTransaction(this.#store, "subscription.acknowledge", async (transaction) => {
      const rawBinding = await transaction.read("subscription.bindings", input.subscriptionId);
      if (rawBinding === null) throw error("SUBSCRIPTION_NOT_FOUND");
      const subscription = decodeStoredSubscription(rawBinding);
      if (!equalIdentity(decodeSession(subscription.session, "DURABLE_SUBSCRIPTION_STATE_INVALID"), input.session)) {
        throw error("SUBSCRIPTION_BINDING_MISMATCH");
      }
      const eventKey = `${input.subscriptionId}\u0000${input.eventId}`;
      const rawEvent = await transaction.read("subscription.events", eventKey);
      if (rawEvent === null) throw error("EVENT_NOT_FOUND");
      const stored = decodeStoredEvent(rawEvent);
      const event = decodeEvent(stored);
      if (event.subscriptionId !== input.subscriptionId || !equalIdentity(event.binding, input.session)) throw error("EVENT_NOT_FOUND");
      if (event.sourceEpoch !== input.sourceEpoch || event.cursor !== input.cursor) throw error("EVENT_ACK_INVALID");
      if (!stored.acknowledged) await transaction.write("subscription.events", eventKey, { ...stored, acknowledged: true });
      return event;
    });
  }

  async pending(subscriptionId: string, session: BridgeSessionIdentity): Promise<readonly NotificationEventV1[]> {
    assertSession(session);
    return runDurableBridgeTransaction(this.#store, "subscription.pending", async (transaction) => {
      const rawBinding = await transaction.read("subscription.bindings", subscriptionId);
      if (rawBinding === null) throw error("SUBSCRIPTION_NOT_FOUND");
      const subscription = decodeStoredSubscription(rawBinding);
      if (!equalIdentity(decodeSession(subscription.session, "DURABLE_SUBSCRIPTION_STATE_INVALID"), session)) {
        throw error("SUBSCRIPTION_BINDING_MISMATCH");
      }
      const output: NotificationEventV1[] = [];
      for (const entry of await transaction.scan("subscription.events")) {
        const stored = decodeStoredEvent(entry.value);
        if (!stored.acknowledged && stored.event.subscriptionId === subscriptionId) output.push(decodeEvent(stored));
      }
      output.sort(comparePosition);
      return Object.freeze(output);
    });
  }
}

export class DurableBridgeStateRepositories {
  readonly store: DurableBridgeStore;
  readonly pairing: DurablePairingRepository;
  readonly notifications: DurableNotificationRepository;
  readonly subscriptions: DurableSubscriptionRepository;

  private constructor(store: DurableBridgeStore, clock: () => number) {
    this.store = store;
    this.pairing = new DurablePairingRepository(store, clock);
    this.notifications = new DurableNotificationRepository(store);
    this.subscriptions = new DurableSubscriptionRepository(store);
  }

  static async open(options: DurableBridgeStateRepositoriesOptions): Promise<DurableBridgeStateRepositories> {
    const store = assertDurableBridgeStore(options.store);
    const repositories = new DurableBridgeStateRepositories(store, options.clock ?? (() => Date.now()));
    await repositories.#validateReopenedState();
    return repositories;
  }

  async #validateReopenedState(): Promise<void> {
    await runDurableBridgeTransaction(this.store, "bridge.state.validate", async (transaction) => {
      const consumedTickets: PairingTicket[] = [];
      const pairingBindings = new Map<string, PairingTicketInput>();
      const bindings = new Map<string, StoredSubscription>();
      for (const entry of await transaction.scan("pairing.tickets")) {
        if (!isRecord(entry.value) || !exactKeys(entry.value, ["consumed", "ticket"]) || typeof entry.value.consumed !== "boolean") {
          throw error("DURABLE_PAIRING_STATE_INVALID");
        }
        const ticket = decodeTicket(entry.value.ticket);
        if (ticket.ticketId !== entry.key) throw error("DURABLE_PAIRING_STATE_INVALID");
        if (entry.value.consumed) consumedTickets.push(ticket);
      }
      for (const entry of await transaction.scan("pairing.bindings")) {
        const binding = decodePairingInput(entry.value);
        if (identityKey(binding) !== entry.key) throw error("DURABLE_PAIRING_STATE_INVALID");
        pairingBindings.set(entry.key, binding);
      }
      for (const ticket of consumedTickets) {
        const binding = pairingBindings.get(identityKey(ticket));
        if (!binding || binding.bridgeFingerprint !== ticket.bridgeFingerprint
          || binding.pairingGeneration < ticket.pairingGeneration
          || (binding.pairingGeneration === ticket.pairingGeneration
            && binding.policyAttestationRevision !== ticket.policyAttestationRevision)) {
          throw error("DURABLE_PAIRING_STATE_INVALID");
        }
      }
      const notifications = new Map<string, NotificationRecordV1>();
      for (const entry of await transaction.scan("notification.records")) {
        if (!isRecord(entry.value) || !exactKeys(entry.value, ["record", "sessionKey"]) || typeof entry.value.sessionKey !== "string") {
          throw error("DURABLE_NOTIFICATION_STATE_INVALID");
        }
        const record = decodeNotification(entry.value.record);
        if (entry.key !== `${entry.value.sessionKey}\u0000${record.recordId}`) throw error("DURABLE_NOTIFICATION_STATE_INVALID");
        notifications.set(entry.key, record);
      }
      for (const entry of await transaction.scan("notification.positions")) {
        if (!isRecord(entry.value) || !exactKeys(entry.value, ["cursor", "recordId", "sessionKey", "sourceEpoch"])
          || entry.value.sessionKey !== entry.key || typeof entry.value.recordId !== "string") throw error("DURABLE_NOTIFICATION_STATE_INVALID");
        const pointed = notifications.get(`${entry.key}\u0000${entry.value.recordId}`);
        if (!pointed || pointed.sourceEpoch !== decimal(entry.value.sourceEpoch, "DURABLE_NOTIFICATION_STATE_INVALID")
          || pointed.cursor !== decimal(entry.value.cursor, "DURABLE_NOTIFICATION_STATE_INVALID")) {
          throw error("DURABLE_NOTIFICATION_STATE_INVALID");
        }
      }
      for (const entry of await transaction.scan("subscription.bindings")) {
        const binding = decodeStoredSubscription(entry.value);
        if (binding.subscriptionId !== entry.key) throw error("DURABLE_SUBSCRIPTION_STATE_INVALID");
        bindings.set(entry.key, binding);
      }
      for (const entry of await transaction.scan("subscription.events")) {
        const stored = decodeStoredEvent(entry.value);
        const binding = bindings.get(stored.event.subscriptionId);
        if (!binding || entry.key !== `${stored.event.subscriptionId}\u0000${stored.event.eventId}`
          || !equalIdentity(
            decodeSession(binding.session, "DURABLE_SUBSCRIPTION_STATE_INVALID"),
            decodeSession(stored.event.binding, "DURABLE_SUBSCRIPTION_STATE_INVALID"),
          )) throw error("DURABLE_SUBSCRIPTION_STATE_INVALID");
      }
    });
  }
}
