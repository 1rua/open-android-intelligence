import {
  assertDurableBridgeStore,
  runDurableBridgeTransaction,
  type DurableBridgeStore,
  type DurableBridgeTransaction,
} from "../../bridge-contract/src/durable-store.js";
import {
  BridgeServiceError,
  compareCodePoints,
} from "../../bridge-contract/src/service-types.js";
import type {
  DurableTask9EventPathPort,
  Task9CaptureAuthorityRecord,
  Task9CursorState,
  Task9IngestResult,
  Task9PendingAck,
  Task9PendingRoute,
  Task9ServerDestination,
  Task9ServerSubscription,
  Task9SourceIdentity,
} from "../../bridge-contract/src/task9-events.js";
import {
  createVerifiedCaptureAuthority,
  createVerifiedEventAckAuthority,
  projectVerifiedDeviceEvent,
  projectVerifiedEventAck,
  type Task9PreReplayAuthorityResolver,
  type VerifiedCaptureAuthority,
  type VerifiedDeviceEvent,
  type VerifiedEventAck,
  type VerifiedEventAckAuthority,
} from "../../protocol/src/event-contract.js";
import {
  isReplayClaimReference,
  type ReplayClaimReference,
} from "../../protocol/src/replay-window.js";

export type {
  Task9CaptureAuthorityRecord,
  Task9CursorState,
  Task9IngestResult,
  Task9PendingAck,
  Task9PendingRoute,
  Task9ServerDestination,
  Task9ServerSubscription,
  Task9SourceIdentity,
} from "../../bridge-contract/src/task9-events.js";

const AUTHORITY_NAMESPACE = "authorization.revisions" as const;
const EVENT_NAMESPACE = "notification.records" as const;
const POSITION_NAMESPACE = "notification.positions" as const;
const SUBSCRIPTION_NAMESPACE = "subscription.bindings" as const;
const ROUTE_NAMESPACE = "subscription.events" as const;

const AUTHORITY_PREFIX = "task9/authority/" as const;
const EVENT_PREFIX = "task9/event/" as const;
const SOURCE_PREFIX = "task9/source/" as const;
const ACK_PREFIX = "task9/ack/" as const;
const SUBSCRIPTION_PREFIX = "task9/subscription/" as const;
const ROUTE_PREFIX = "task9/route/" as const;

const U64_MAX = 18_446_744_073_709_551_615n;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SCOPE = /^[A-Za-z][A-Za-z0-9_.-]{0,127}$/;
const DECIMAL_U64 = /^(0|[1-9][0-9]*)$/;
const SAFE_ID = /^[A-Za-z0-9_.:@/-]{1,256}$/;

type PersistedSource = Readonly<{
  tenant_id: string;
  human_principal_id: string;
  device_id: string;
  source_epoch: string;
  source_capability: string;
}>;

type PersistedAuthority = PersistedSource & Readonly<{
  pairing_generation: string;
  authorization_epoch: string;
  scope_revisions: readonly Readonly<{ scope: string; revision: string }>[];
}>;

type PersistedReplayReference = Readonly<{
  claim_id: string;
  message_id: string;
  message_type: "device_event";
  envelope_digest: string;
}>;

type PersistedEvent = PersistedSource & Readonly<{
  cursor: string;
  occurrence_id: string;
  event_kind: "upsert" | "delete_tombstone" | "loss_marker";
  pairing_generation: string;
  authorization_epoch: string;
  scope_revisions: readonly Readonly<{ scope: string; revision: string }>[];
  route_by_server_subscription_only: true;
  replay: PersistedReplayReference;
}>;

type PersistedSourceState = PersistedSource & Readonly<{
  highest_contiguous_cursor: string;
  buffered_cursors: readonly string[];
}>;

type PersistedAck = PersistedSource & Readonly<{
  highest_contiguous_cursor: string;
  status: "pending" | "sent";
}>;

type PersistedDestination = Readonly<{
  agent_principal_id: string;
  agent_instance_id: string;
  workspace_id: string;
  session_id: string;
  job_id: string | null;
}>;

type PersistedSubscription = PersistedSource & Readonly<{
  subscription_id: string;
  destination: PersistedDestination;
}>;

type PersistedRoute = Readonly<{
  subscription_id: string;
  source: PersistedSource;
  cursor: string;
  occurrence_id: string;
  event_kind: "upsert" | "delete_tombstone" | "loss_marker";
  destination: PersistedDestination;
  status: "pending";
}>;

const task9Error = (code: string): BridgeServiceError => new BridgeServiceError(code);

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort(compareCodePoints);
  const expected = [...keys].sort(compareCodePoints);
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};

const isText = (value: unknown): value is string =>
  typeof value === "string"
  && value.length >= 1
  && value.length <= 512
  && !/[\u0000-\u001f\u007f]/u.test(value);

const isU64Text = (value: unknown): value is string =>
  typeof value === "string" && DECIMAL_U64.test(value) && BigInt(value) <= U64_MAX;

const assertU64 = (value: bigint, code: string, allowZero = true): void => {
  if (typeof value !== "bigint" || value < (allowZero ? 0n : 1n) || value > U64_MAX) throw task9Error(code);
};

const validateSourceValues = (value: Record<string, unknown>): boolean =>
  isText(value.tenant_id)
  && isText(value.human_principal_id)
  && isText(value.device_id)
  && typeof value.source_epoch === "string"
  && UUID.test(value.source_epoch)
  && typeof value.source_capability === "string"
  && SCOPE.test(value.source_capability);

const isPersistedSource = (value: unknown): value is PersistedSource =>
  isObject(value)
  && exactKeys(value, ["tenant_id", "human_principal_id", "device_id", "source_epoch", "source_capability"])
  && validateSourceValues(value);

const sourceToPersisted = (source: Task9SourceIdentity): PersistedSource => Object.freeze({
  tenant_id: source.tenantId,
  human_principal_id: source.humanPrincipalId,
  device_id: source.deviceId,
  source_epoch: source.sourceEpoch,
  source_capability: source.sourceCapability,
});

const sourceFromPersisted = (source: PersistedSource): Task9SourceIdentity => Object.freeze({
  tenantId: source.tenant_id,
  humanPrincipalId: source.human_principal_id,
  deviceId: source.device_id,
  sourceEpoch: source.source_epoch,
  sourceCapability: source.source_capability,
});

const sourceEquals = (left: PersistedSource, right: PersistedSource): boolean =>
  left.tenant_id === right.tenant_id
  && left.human_principal_id === right.human_principal_id
  && left.device_id === right.device_id
  && left.source_epoch === right.source_epoch
  && left.source_capability === right.source_capability;

const inputSourceToPersisted = (source: Task9SourceIdentity, code: string): PersistedSource => {
  if (!isObject(source) || !exactKeys(source, ["tenantId", "humanPrincipalId", "deviceId", "sourceEpoch", "sourceCapability"])) {
    throw task9Error(code);
  }
  const persisted = sourceToPersisted(source);
  if (!validateSourceValues(persisted as unknown as Record<string, unknown>)) throw task9Error(code);
  return persisted;
};

const segment = (value: string): string => Buffer.from(value, "utf8").toString("base64url");
const sourceKey = (source: PersistedSource): string => [
  source.tenant_id,
  source.human_principal_id,
  source.device_id,
  source.source_epoch,
  source.source_capability,
].map(segment).join("/");
const authorityKey = (source: PersistedSource): string => `${AUTHORITY_PREFIX}${sourceKey(source)}`;
const eventKey = (source: PersistedSource, cursor: bigint | string): string => `${EVENT_PREFIX}${sourceKey(source)}/${String(cursor)}`;
const sourceStateKey = (source: PersistedSource): string => `${SOURCE_PREFIX}${sourceKey(source)}`;
const ackKey = (source: PersistedSource): string => `${ACK_PREFIX}${sourceKey(source)}`;
const subscriptionKey = (subscriptionId: string): string => `${SUBSCRIPTION_PREFIX}${segment(subscriptionId)}`;
const routeKey = (subscriptionId: string, cursor: bigint | string): string => `${ROUTE_PREFIX}${segment(subscriptionId)}/${String(cursor)}`;

const revisionEntries = (value: ReadonlyMap<string, bigint>, code: string): readonly Readonly<{ scope: string; revision: string }>[] => {
  if (!(value instanceof Map) || value.size === 0) throw task9Error(code);
  const entries = [...value].map(([scope, revision]) => {
    if (!SCOPE.test(scope)) throw task9Error(code);
    assertU64(revision, code);
    return Object.freeze({ scope, revision: revision.toString(10) });
  }).sort((left, right) => compareCodePoints(left.scope, right.scope));
  return Object.freeze(entries);
};

const parseRevisionEntries = (value: unknown): readonly Readonly<{ scope: string; revision: string }>[] => {
  if (!Array.isArray(value) || value.length === 0) throw task9Error("TASK9_STATE_INVALID");
  const seen = new Set<string>();
  const entries = value.map((entry) => {
    if (!isObject(entry) || !exactKeys(entry, ["scope", "revision"])
      || typeof entry.scope !== "string" || !SCOPE.test(entry.scope) || !isU64Text(entry.revision)
      || seen.has(entry.scope)) throw task9Error("TASK9_STATE_INVALID");
    seen.add(entry.scope);
    return Object.freeze({ scope: entry.scope, revision: entry.revision });
  });
  const sorted = [...entries].sort((left, right) => compareCodePoints(left.scope, right.scope));
  if (entries.some((entry, index) => entry.scope !== sorted[index]!.scope)) throw task9Error("TASK9_STATE_INVALID");
  return Object.freeze(entries);
};

const revisionsEqual = (
  left: readonly Readonly<{ scope: string; revision: string }>[],
  right: readonly Readonly<{ scope: string; revision: string }>[],
): boolean => left.length === right.length
  && left.every((entry, index) => entry.scope === right[index]?.scope && entry.revision === right[index]?.revision);

const parseAuthority = (key: string, value: unknown): PersistedAuthority => {
  if (!isObject(value)
    || !exactKeys(value, ["tenant_id", "human_principal_id", "device_id", "source_epoch", "source_capability", "pairing_generation", "authorization_epoch", "scope_revisions"])
    || !validateSourceValues(value)
    || !isU64Text(value.pairing_generation)
    || !isU64Text(value.authorization_epoch)) throw task9Error("TASK9_STATE_INVALID");
  const authority = Object.freeze({
    tenant_id: value.tenant_id as string,
    human_principal_id: value.human_principal_id as string,
    device_id: value.device_id as string,
    source_epoch: value.source_epoch as string,
    source_capability: value.source_capability as string,
    pairing_generation: value.pairing_generation,
    authorization_epoch: value.authorization_epoch,
    scope_revisions: parseRevisionEntries(value.scope_revisions),
  });
  if (key !== authorityKey(authority)) throw task9Error("TASK9_STATE_INVALID");
  return authority;
};

const parseReplay = (value: unknown): PersistedReplayReference => {
  if (!isObject(value) || !exactKeys(value, ["claim_id", "message_id", "message_type", "envelope_digest"])
    || typeof value.claim_id !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(value.claim_id)
    || !isText(value.message_id) || value.message_type !== "device_event"
    || typeof value.envelope_digest !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(value.envelope_digest)) {
    throw task9Error("TASK9_STATE_INVALID");
  }
  return Object.freeze({
    claim_id: value.claim_id,
    message_id: value.message_id,
    message_type: "device_event",
    envelope_digest: value.envelope_digest,
  });
};

const parseEvent = (key: string, value: unknown): PersistedEvent => {
  if (!isObject(value)
    || !exactKeys(value, ["tenant_id", "human_principal_id", "device_id", "source_epoch", "source_capability", "cursor", "occurrence_id", "event_kind", "pairing_generation", "authorization_epoch", "scope_revisions", "route_by_server_subscription_only", "replay"])
    || !validateSourceValues(value)
    || !isU64Text(value.cursor) || value.cursor === "0"
    || typeof value.occurrence_id !== "string" || !UUID.test(value.occurrence_id)
    || (value.event_kind !== "upsert" && value.event_kind !== "delete_tombstone" && value.event_kind !== "loss_marker")
    || !isU64Text(value.pairing_generation) || !isU64Text(value.authorization_epoch)
    || value.route_by_server_subscription_only !== true) throw task9Error("TASK9_STATE_INVALID");
  const event: PersistedEvent = Object.freeze({
    tenant_id: value.tenant_id as string,
    human_principal_id: value.human_principal_id as string,
    device_id: value.device_id as string,
    source_epoch: value.source_epoch as string,
    source_capability: value.source_capability as string,
    cursor: value.cursor,
    occurrence_id: value.occurrence_id,
    event_kind: value.event_kind,
    pairing_generation: value.pairing_generation,
    authorization_epoch: value.authorization_epoch,
    scope_revisions: parseRevisionEntries(value.scope_revisions),
    route_by_server_subscription_only: true,
    replay: parseReplay(value.replay),
  });
  if (key !== eventKey(event, event.cursor)) throw task9Error("TASK9_STATE_INVALID");
  return event;
};

const parseSourceState = (key: string, value: unknown): PersistedSourceState => {
  if (!isObject(value)
    || !exactKeys(value, ["tenant_id", "human_principal_id", "device_id", "source_epoch", "source_capability", "highest_contiguous_cursor", "buffered_cursors"])
    || !validateSourceValues(value) || !isU64Text(value.highest_contiguous_cursor) || !Array.isArray(value.buffered_cursors)) {
    throw task9Error("TASK9_STATE_INVALID");
  }
  const highest = BigInt(value.highest_contiguous_cursor);
  const cursors: string[] = [];
  let previous = highest;
  for (const cursor of value.buffered_cursors) {
    if (!isU64Text(cursor) || cursor === "0" || BigInt(cursor) <= previous) throw task9Error("TASK9_STATE_INVALID");
    cursors.push(cursor);
    previous = BigInt(cursor);
  }
  const state: PersistedSourceState = Object.freeze({
    tenant_id: value.tenant_id as string,
    human_principal_id: value.human_principal_id as string,
    device_id: value.device_id as string,
    source_epoch: value.source_epoch as string,
    source_capability: value.source_capability as string,
    highest_contiguous_cursor: value.highest_contiguous_cursor,
    buffered_cursors: Object.freeze(cursors),
  });
  if (key !== sourceStateKey(state)) throw task9Error("TASK9_STATE_INVALID");
  return state;
};

const parseAck = (key: string, value: unknown): PersistedAck => {
  if (!isObject(value)
    || !exactKeys(value, ["tenant_id", "human_principal_id", "device_id", "source_epoch", "source_capability", "highest_contiguous_cursor", "status"])
    || !validateSourceValues(value) || !isU64Text(value.highest_contiguous_cursor) || value.highest_contiguous_cursor === "0"
    || (value.status !== "pending" && value.status !== "sent")) throw task9Error("TASK9_STATE_INVALID");
  const ack: PersistedAck = Object.freeze({
    tenant_id: value.tenant_id as string,
    human_principal_id: value.human_principal_id as string,
    device_id: value.device_id as string,
    source_epoch: value.source_epoch as string,
    source_capability: value.source_capability as string,
    highest_contiguous_cursor: value.highest_contiguous_cursor,
    status: value.status,
  });
  if (key !== ackKey(ack)) throw task9Error("TASK9_STATE_INVALID");
  return ack;
};

const parseDestination = (value: unknown, code = "TASK9_STATE_INVALID"): PersistedDestination => {
  if (!isObject(value)
    || !exactKeys(value, ["agent_principal_id", "agent_instance_id", "workspace_id", "session_id", "job_id"])
    || !isText(value.agent_principal_id) || !isText(value.agent_instance_id)
    || !isText(value.workspace_id) || !isText(value.session_id)
    || (value.job_id !== null && !isText(value.job_id))) throw task9Error(code);
  return Object.freeze({
    agent_principal_id: value.agent_principal_id,
    agent_instance_id: value.agent_instance_id,
    workspace_id: value.workspace_id,
    session_id: value.session_id,
    job_id: value.job_id,
  });
};

const destinationToPersisted = (value: Task9ServerDestination): PersistedDestination => parseDestination({
  agent_principal_id: value.agentPrincipalId,
  agent_instance_id: value.agentInstanceId,
  workspace_id: value.workspaceId,
  session_id: value.sessionId,
  job_id: value.jobId,
}, "TASK9_SUBSCRIPTION_INVALID");

const destinationFromPersisted = (value: PersistedDestination): Task9ServerDestination => Object.freeze({
  agentPrincipalId: value.agent_principal_id,
  agentInstanceId: value.agent_instance_id,
  workspaceId: value.workspace_id,
  sessionId: value.session_id,
  jobId: value.job_id,
});

const destinationEquals = (left: PersistedDestination, right: PersistedDestination): boolean =>
  left.agent_principal_id === right.agent_principal_id
  && left.agent_instance_id === right.agent_instance_id
  && left.workspace_id === right.workspace_id
  && left.session_id === right.session_id
  && left.job_id === right.job_id;

const parseSubscription = (key: string, value: unknown): PersistedSubscription => {
  if (!isObject(value)
    || !exactKeys(value, ["tenant_id", "human_principal_id", "device_id", "source_epoch", "source_capability", "subscription_id", "destination"])
    || !validateSourceValues(value) || typeof value.subscription_id !== "string" || !SAFE_ID.test(value.subscription_id)) {
    throw task9Error("TASK9_STATE_INVALID");
  }
  const subscription: PersistedSubscription = Object.freeze({
    tenant_id: value.tenant_id as string,
    human_principal_id: value.human_principal_id as string,
    device_id: value.device_id as string,
    source_epoch: value.source_epoch as string,
    source_capability: value.source_capability as string,
    subscription_id: value.subscription_id,
    destination: parseDestination(value.destination),
  });
  if (key !== subscriptionKey(subscription.subscription_id)) throw task9Error("TASK9_STATE_INVALID");
  return subscription;
};

const parseRoute = (key: string, value: unknown): PersistedRoute => {
  if (!isObject(value)
    || !exactKeys(value, ["subscription_id", "source", "cursor", "occurrence_id", "event_kind", "destination", "status"])
    || typeof value.subscription_id !== "string" || !SAFE_ID.test(value.subscription_id)
    || !isPersistedSource(value.source) || !isU64Text(value.cursor) || value.cursor === "0"
    || typeof value.occurrence_id !== "string" || !UUID.test(value.occurrence_id)
    || (value.event_kind !== "upsert" && value.event_kind !== "delete_tombstone" && value.event_kind !== "loss_marker")
    || value.status !== "pending") throw task9Error("TASK9_STATE_INVALID");
  const route: PersistedRoute = Object.freeze({
    subscription_id: value.subscription_id,
    source: value.source,
    cursor: value.cursor,
    occurrence_id: value.occurrence_id,
    event_kind: value.event_kind,
    destination: parseDestination(value.destination),
    status: "pending",
  });
  if (key !== routeKey(route.subscription_id, route.cursor)) throw task9Error("TASK9_STATE_INVALID");
  return route;
};

const persistedAuthorityFromInput = (input: Task9CaptureAuthorityRecord): PersistedAuthority => {
  if (!isObject(input)
    || !exactKeys(input, ["tenantId", "humanPrincipalId", "deviceId", "sourceEpoch", "sourceCapability", "pairingGeneration", "authorizationEpoch", "scopeRevisions"])) {
    throw task9Error("TASK9_AUTHORITY_INVALID");
  }
  const source = sourceToPersisted(input);
  if (!validateSourceValues(source as unknown as Record<string, unknown>)) throw task9Error("TASK9_AUTHORITY_INVALID");
  assertU64(input.pairingGeneration, "TASK9_AUTHORITY_INVALID");
  assertU64(input.authorizationEpoch, "TASK9_AUTHORITY_INVALID");
  return Object.freeze({
    ...source,
    pairing_generation: input.pairingGeneration.toString(10),
    authorization_epoch: input.authorizationEpoch.toString(10),
    scope_revisions: revisionEntries(input.scopeRevisions, "TASK9_AUTHORITY_INVALID"),
  });
};

const authorityToFact = (authority: PersistedAuthority): VerifiedCaptureAuthority => createVerifiedCaptureAuthority({
  ...sourceFromPersisted(authority),
  revision: {
    pairingGeneration: BigInt(authority.pairing_generation),
    authorizationEpoch: BigInt(authority.authorization_epoch),
    scopeRevisions: new Map(authority.scope_revisions.map((entry) => [entry.scope, BigInt(entry.revision)])),
  },
});

const isAuthorityMonotonic = (previous: PersistedAuthority, next: PersistedAuthority): boolean => {
  if (BigInt(next.pairing_generation) < BigInt(previous.pairing_generation)
    || BigInt(next.authorization_epoch) < BigInt(previous.authorization_epoch)) return false;
  const previousScopes = new Map(previous.scope_revisions.map((entry) => [entry.scope, BigInt(entry.revision)]));
  return next.scope_revisions.every((entry) => BigInt(entry.revision) >= (previousScopes.get(entry.scope) ?? 0n));
};

const persistedEventFromProjection = (
  event: ReturnType<typeof projectVerifiedDeviceEvent>,
  replay: ReplayClaimReference<"device_event">,
): PersistedEvent => {
  assertU64(event.cursor, "TASK9_EVENT_INVALID", false);
  assertU64(event.pairingGeneration, "TASK9_EVENT_INVALID");
  assertU64(event.authorizationEpoch, "TASK9_EVENT_INVALID");
  const source = sourceToPersisted(event);
  const persisted: PersistedEvent = Object.freeze({
    ...source,
    cursor: event.cursor.toString(10),
    occurrence_id: event.occurrenceId,
    event_kind: event.eventKind,
    pairing_generation: event.pairingGeneration.toString(10),
    authorization_epoch: event.authorizationEpoch.toString(10),
    scope_revisions: revisionEntries(event.scopeRevisions, "TASK9_EVENT_INVALID"),
    route_by_server_subscription_only: true,
    replay: Object.freeze({
      claim_id: replay.claimId,
      message_id: replay.messageId,
      message_type: "device_event",
      envelope_digest: replay.envelopeDigest,
    }),
  });
  return persisted;
};

const stableEquals = (left: unknown, right: unknown): boolean => JSON.stringify(left) === JSON.stringify(right);

const cursorStateProjection = (state: PersistedSourceState): Task9CursorState => Object.freeze({
  highestContiguousCursor: BigInt(state.highest_contiguous_cursor),
  bufferedCursors: Object.freeze(state.buffered_cursors.map(BigInt)),
});

const emptySourceState = (source: PersistedSource): PersistedSourceState => Object.freeze({
  ...source,
  highest_contiguous_cursor: "0",
  buffered_cursors: Object.freeze([]),
});

const matchesResolverIdentity = (
  source: PersistedSource,
  input: Parameters<Task9PreReplayAuthorityResolver["loadCaptureAuthority"]>[0],
): boolean => input.context.kind === "device"
  && source.tenant_id === input.context.tenantId
  && source.human_principal_id === input.context.humanPrincipalId
  && source.device_id === input.context.deviceId
  && source.source_capability === input.sourceCapability;

/**
 * Atomic, durable Task 9 reducer/outbox over the reviewed Bridge store port.
 * It supplies no socket, database driver, signer, or production deployment;
 * those remain explicit composition blockers outside this network-neutral path.
 */
export class DurableTask9EventPath implements DurableTask9EventPathPort {
  readonly #store: DurableBridgeStore;

  private constructor(store: DurableBridgeStore) {
    this.#store = assertDurableBridgeStore(store);
  }

  static async open(options: Readonly<{ store: DurableBridgeStore }>): Promise<DurableTask9EventPath> {
    if (!isObject(options)) throw task9Error("TASK9_OPTIONS_INVALID");
    const path = new DurableTask9EventPath(options.store);
    await path.#validateSnapshot();
    return path;
  }

  async putCaptureAuthority(input: Task9CaptureAuthorityRecord): Promise<void> {
    const authority = persistedAuthorityFromInput(input);
    await runDurableBridgeTransaction(this.#store, "task9.authority.put", async (transaction) => {
      const key = authorityKey(authority);
      const raw = await transaction.read(AUTHORITY_NAMESPACE, key);
      if (raw !== null) {
        const previous = parseAuthority(key, raw);
        if (stableEquals(previous, authority)) return;
        if (!isAuthorityMonotonic(previous, authority)) throw task9Error("TASK9_AUTHORITY_ROLLBACK");
      }
      await transaction.write(AUTHORITY_NAMESPACE, key, authority);
    });
  }

  async putServerSubscription(input: Task9ServerSubscription): Promise<void> {
    if (!isObject(input)
      || !exactKeys(input, ["tenantId", "humanPrincipalId", "deviceId", "sourceEpoch", "sourceCapability", "subscriptionId", "destination"])
      || typeof input.subscriptionId !== "string" || !SAFE_ID.test(input.subscriptionId)
      || !isObject(input.destination)
      || !exactKeys(input.destination, ["agentPrincipalId", "agentInstanceId", "workspaceId", "sessionId", "jobId"])) {
      throw task9Error("TASK9_SUBSCRIPTION_INVALID");
    }
    const source = sourceToPersisted(input);
    if (!validateSourceValues(source as unknown as Record<string, unknown>)) throw task9Error("TASK9_SUBSCRIPTION_INVALID");
    const subscription: PersistedSubscription = Object.freeze({
      ...source,
      subscription_id: input.subscriptionId,
      destination: destinationToPersisted(input.destination),
    });
    await runDurableBridgeTransaction(this.#store, "task9.subscription.put", async (transaction) => {
      const key = subscriptionKey(subscription.subscription_id);
      const raw = await transaction.read(SUBSCRIPTION_NAMESPACE, key);
      if (raw !== null) {
        const previous = parseSubscription(key, raw);
        if (stableEquals(previous, subscription)) return;
        throw task9Error("TASK9_SUBSCRIPTION_CONFLICT");
      }
      await transaction.write(SUBSCRIPTION_NAMESPACE, key, subscription);
    });
  }

  async ingestEvent(event: VerifiedDeviceEvent, replay: ReplayClaimReference<"device_event">): Promise<Task9IngestResult> {
    const projection = projectVerifiedDeviceEvent(event);
    if (!isReplayClaimReference(replay) || replay.messageType !== "device_event") throw new Error("AUTH_FAILED");
    const persisted = persistedEventFromProjection(projection, replay);
    return runDurableBridgeTransaction(this.#store, "task9.event.ingest", async (transaction) => {
      const rawAuthority = await transaction.read(AUTHORITY_NAMESPACE, authorityKey(persisted));
      if (rawAuthority === null) throw task9Error("TASK9_CAPTURE_AUTHORITY_UNAVAILABLE");
      const authority = parseAuthority(authorityKey(persisted), rawAuthority);
      if (authority.pairing_generation !== persisted.pairing_generation
        || authority.authorization_epoch !== persisted.authorization_epoch
        || !revisionsEqual(authority.scope_revisions, persisted.scope_revisions)) {
        throw task9Error("TASK9_CAPTURE_AUTHORITY_STALE");
      }

      const key = eventKey(persisted, persisted.cursor);
      const rawExisting = await transaction.read(EVENT_NAMESPACE, key);
      const rawState = await transaction.read(POSITION_NAMESPACE, sourceStateKey(persisted));
      const previousState = rawState === null ? emptySourceState(persisted) : parseSourceState(sourceStateKey(persisted), rawState);
      if (rawExisting !== null) {
        const existing = parseEvent(key, rawExisting);
        if (!stableEquals(existing, persisted)) throw task9Error("TASK9_EVENT_CURSOR_CONFLICT");
        return Object.freeze({
          kind: "duplicate" as const,
          ...cursorStateProjection(previousState),
          routed: Object.freeze([]),
        });
      }

      const allEvents = new Map<bigint, PersistedEvent>();
      for (const entry of await transaction.scan(EVENT_NAMESPACE)) {
        if (!entry.key.startsWith(EVENT_PREFIX)) continue;
        const candidate = parseEvent(entry.key, entry.value);
        if (sourceEquals(candidate, persisted)) allEvents.set(BigInt(candidate.cursor), candidate);
      }
      allEvents.set(BigInt(persisted.cursor), persisted);

      let highest = BigInt(previousState.highest_contiguous_cursor);
      const advanced: PersistedEvent[] = [];
      while (allEvents.has(highest + 1n)) {
        highest += 1n;
        advanced.push(allEvents.get(highest)!);
      }
      const buffered = [...allEvents.keys()]
        .filter((cursor) => cursor > highest)
        .sort((left, right) => left < right ? -1 : left > right ? 1 : 0);
      const nextState: PersistedSourceState = Object.freeze({
        ...sourceToPersisted(projection),
        highest_contiguous_cursor: highest.toString(10),
        buffered_cursors: Object.freeze(buffered.map((cursor) => cursor.toString(10))),
      });

      const subscriptions: PersistedSubscription[] = [];
      if (advanced.length > 0) {
        for (const entry of await transaction.scan(SUBSCRIPTION_NAMESPACE)) {
          if (!entry.key.startsWith(SUBSCRIPTION_PREFIX)) continue;
          const subscription = parseSubscription(entry.key, entry.value);
          if (sourceEquals(subscription, persisted)) subscriptions.push(subscription);
        }
        subscriptions.sort((left, right) => compareCodePoints(left.subscription_id, right.subscription_id));
      }

      const routed: Readonly<{ subscriptionId: string; cursor: bigint }>[] = [];
      await transaction.write(EVENT_NAMESPACE, key, persisted);
      await transaction.write(POSITION_NAMESPACE, sourceStateKey(persisted), nextState);
      if (advanced.length > 0) {
        const ack: PersistedAck = Object.freeze({
          ...sourceToPersisted(projection),
          highest_contiguous_cursor: highest.toString(10),
          status: "pending",
        });
        await transaction.write(POSITION_NAMESPACE, ackKey(persisted), ack);
        for (const committed of advanced) {
          for (const subscription of subscriptions) {
            const route: PersistedRoute = Object.freeze({
              subscription_id: subscription.subscription_id,
              source: sourceToPersisted(projection),
              cursor: committed.cursor,
              occurrence_id: committed.occurrence_id,
              event_kind: committed.event_kind,
              destination: subscription.destination,
              status: "pending",
            });
            const routeStorageKey = routeKey(route.subscription_id, route.cursor);
            const rawRoute = await transaction.read(ROUTE_NAMESPACE, routeStorageKey);
            if (rawRoute !== null && !stableEquals(parseRoute(routeStorageKey, rawRoute), route)) {
              throw task9Error("TASK9_ROUTE_CONFLICT");
            }
            if (rawRoute === null) await transaction.write(ROUTE_NAMESPACE, routeStorageKey, route);
            routed.push(Object.freeze({ subscriptionId: route.subscription_id, cursor: BigInt(route.cursor) }));
          }
        }
      }
      return Object.freeze({
        kind: advanced.length === 0 ? "buffered" as const : "committed" as const,
        ...cursorStateProjection(nextState),
        routed: Object.freeze(routed),
      });
    });
  }

  async loadCaptureAuthority(
    input: Parameters<Task9PreReplayAuthorityResolver["loadCaptureAuthority"]>[0],
  ): Promise<VerifiedCaptureAuthority | null> {
    if (!isObject(input) || !isObject(input.context) || input.context.kind !== "device" || !SCOPE.test(input.sourceCapability)) return null;
    return runDurableBridgeTransaction(this.#store, "task9.authority.load", async (transaction) => {
      const matches: PersistedAuthority[] = [];
      for (const entry of await transaction.scan(AUTHORITY_NAMESPACE)) {
        if (!entry.key.startsWith(AUTHORITY_PREFIX)) continue;
        const authority = parseAuthority(entry.key, entry.value);
        if (matchesResolverIdentity(authority, input)) matches.push(authority);
      }
      return matches.length === 1 ? authorityToFact(matches[0]!) : null;
    });
  }

  async loadEventAckAuthority(
    input: Parameters<Task9PreReplayAuthorityResolver["loadEventAckAuthority"]>[0],
  ): Promise<VerifiedEventAckAuthority | null> {
    if (!isObject(input) || !isObject(input.context) || input.context.kind !== "device" || !SCOPE.test(input.sourceCapability)) return null;
    return runDurableBridgeTransaction(this.#store, "task9.ack.authority.load", async (transaction) => {
      const matches: PersistedSourceState[] = [];
      for (const entry of await transaction.scan(POSITION_NAMESPACE)) {
        if (!entry.key.startsWith(SOURCE_PREFIX)) continue;
        const state = parseSourceState(entry.key, entry.value);
        if (matchesResolverIdentity(state, input) && state.highest_contiguous_cursor !== "0") matches.push(state);
      }
      if (matches.length !== 1) return null;
      const state = matches[0]!;
      return createVerifiedEventAckAuthority({
        ...sourceFromPersisted(state),
        highestContiguousCursor: BigInt(state.highest_contiguous_cursor),
      });
    });
  }

  async persistBeforeSign(fact: VerifiedEventAck): Promise<{ kind: "committed" } | { kind: "rejected" }> {
    const projection = projectVerifiedEventAck(fact);
    const source = sourceToPersisted(projection);
    assertU64(projection.highestContiguousCursor, "TASK9_ACK_INVALID", false);
    return runDurableBridgeTransaction(this.#store, "task9.ack.persist", async (transaction) => {
      const rawState = await transaction.read(POSITION_NAMESPACE, sourceStateKey(source));
      if (rawState === null) return Object.freeze({ kind: "rejected" as const });
      const state = parseSourceState(sourceStateKey(source), rawState);
      if (state.highest_contiguous_cursor !== projection.highestContiguousCursor.toString(10)) {
        return Object.freeze({ kind: "rejected" as const });
      }
      const next: PersistedAck = Object.freeze({
        ...source,
        highest_contiguous_cursor: projection.highestContiguousCursor.toString(10),
        status: "pending",
      });
      const raw = await transaction.read(POSITION_NAMESPACE, ackKey(source));
      if (raw === null || !stableEquals(parseAck(ackKey(source), raw), next)) {
        await transaction.write(POSITION_NAMESPACE, ackKey(source), next);
      }
      return Object.freeze({ kind: "committed" as const });
    });
  }

  async loadCursorState(input: Task9SourceIdentity): Promise<Task9CursorState | null> {
    const source = inputSourceToPersisted(input, "TASK9_SOURCE_INVALID");
    return runDurableBridgeTransaction(this.#store, "task9.cursor.load", async (transaction) => {
      const raw = await transaction.read(POSITION_NAMESPACE, sourceStateKey(source));
      return raw === null ? null : cursorStateProjection(parseSourceState(sourceStateKey(source), raw));
    });
  }

  async recoverPendingAcks(): Promise<readonly Task9PendingAck[]> {
    return runDurableBridgeTransaction(this.#store, "task9.ack.recover", async (transaction) => {
      const pending: Task9PendingAck[] = [];
      for (const entry of await transaction.scan(POSITION_NAMESPACE)) {
        if (!entry.key.startsWith(ACK_PREFIX)) continue;
        const ack = parseAck(entry.key, entry.value);
        if (ack.status === "pending") pending.push(Object.freeze({
          ...sourceFromPersisted(ack),
          highestContiguousCursor: BigInt(ack.highest_contiguous_cursor),
        }));
      }
      pending.sort((left, right) => compareCodePoints(sourceKey(sourceToPersisted(left)), sourceKey(sourceToPersisted(right))));
      return Object.freeze(pending);
    });
  }

  async markAckSent(input: Task9SourceIdentity, highestContiguousCursor: bigint): Promise<void> {
    const source = inputSourceToPersisted(input, "TASK9_SOURCE_INVALID");
    assertU64(highestContiguousCursor, "TASK9_ACK_INVALID", false);
    await runDurableBridgeTransaction(this.#store, "task9.ack.sent", async (transaction) => {
      const key = ackKey(source);
      const raw = await transaction.read(POSITION_NAMESPACE, key);
      if (raw === null) throw task9Error("TASK9_ACK_MISSING");
      const ack = parseAck(key, raw);
      if (ack.highest_contiguous_cursor !== highestContiguousCursor.toString(10)) throw task9Error("TASK9_ACK_CURSOR_MISMATCH");
      if (ack.status === "sent") return;
      await transaction.write(POSITION_NAMESPACE, key, Object.freeze({ ...ack, status: "sent" as const }));
    });
  }

  async recoverPendingRoutes(subscriptionId: string): Promise<readonly Task9PendingRoute[]> {
    if (typeof subscriptionId !== "string" || !SAFE_ID.test(subscriptionId)) throw task9Error("TASK9_SUBSCRIPTION_INVALID");
    return runDurableBridgeTransaction(this.#store, "task9.route.recover", async (transaction) => {
      const rawSubscription = await transaction.read(SUBSCRIPTION_NAMESPACE, subscriptionKey(subscriptionId));
      if (rawSubscription === null) return Object.freeze([]);
      const subscription = parseSubscription(subscriptionKey(subscriptionId), rawSubscription);
      const pending: Task9PendingRoute[] = [];
      for (const entry of await transaction.scan(ROUTE_NAMESPACE)) {
        if (!entry.key.startsWith(`${ROUTE_PREFIX}${segment(subscriptionId)}/`)) continue;
        const route = parseRoute(entry.key, entry.value);
        if (!sourceEquals(route.source, subscription) || !destinationEquals(route.destination, subscription.destination)) {
          throw task9Error("TASK9_STATE_INVALID");
        }
        pending.push(Object.freeze({
          subscriptionId: route.subscription_id,
          cursor: BigInt(route.cursor),
          occurrenceId: route.occurrence_id,
          eventKind: route.event_kind,
          destination: destinationFromPersisted(route.destination),
        }));
      }
      pending.sort((left, right) => left.cursor < right.cursor ? -1 : left.cursor > right.cursor ? 1 : 0);
      return Object.freeze(pending);
    });
  }

  async #validateSnapshot(): Promise<void> {
    await runDurableBridgeTransaction(this.#store, "task9.state.validate", async (transaction) => {
      const authorities = await this.#parseAll(transaction, AUTHORITY_NAMESPACE, AUTHORITY_PREFIX, parseAuthority);
      const events = await this.#parseAll(transaction, EVENT_NAMESPACE, EVENT_PREFIX, parseEvent);
      const states = await this.#parseAll(transaction, POSITION_NAMESPACE, SOURCE_PREFIX, parseSourceState);
      const acks = await this.#parseAll(transaction, POSITION_NAMESPACE, ACK_PREFIX, parseAck);
      const subscriptions = await this.#parseAll(transaction, SUBSCRIPTION_NAMESPACE, SUBSCRIPTION_PREFIX, parseSubscription);
      const routes = await this.#parseAll(transaction, ROUTE_NAMESPACE, ROUTE_PREFIX, parseRoute);

      for (const state of states) {
        if (!authorities.some((authority) => sourceEquals(authority, state))) throw task9Error("TASK9_STATE_INVALID");
        const sourceEvents = events.filter((event) => sourceEquals(event, state));
        const cursors = new Set(sourceEvents.map((event) => event.cursor));
        const highest = BigInt(state.highest_contiguous_cursor);
        for (let cursor = 1n; cursor <= highest; cursor += 1n) {
          if (!cursors.has(cursor.toString(10))) throw task9Error("TASK9_STATE_INVALID");
        }
        const expectedBuffered = sourceEvents.map((event) => BigInt(event.cursor))
          .filter((cursor) => cursor > highest)
          .sort((left, right) => left < right ? -1 : left > right ? 1 : 0)
          .map(String);
        if (expectedBuffered.length !== state.buffered_cursors.length
          || expectedBuffered.some((cursor, index) => cursor !== state.buffered_cursors[index])) throw task9Error("TASK9_STATE_INVALID");
      }
      for (const event of events) {
        if (!states.some((state) => sourceEquals(state, event))) throw task9Error("TASK9_STATE_INVALID");
      }
      for (const ack of acks) {
        const state = states.find((candidate) => sourceEquals(candidate, ack));
        if (state === undefined || BigInt(ack.highest_contiguous_cursor) > BigInt(state.highest_contiguous_cursor)) {
          throw task9Error("TASK9_STATE_INVALID");
        }
      }
      for (const route of routes) {
        const subscription = subscriptions.find((candidate) => candidate.subscription_id === route.subscription_id);
        const event = events.find((candidate) => sourceEquals(candidate, route.source) && candidate.cursor === route.cursor);
        const state = states.find((candidate) => sourceEquals(candidate, route.source));
        if (subscription === undefined || event === undefined || state === undefined
          || !sourceEquals(subscription, route.source)
          || !destinationEquals(subscription.destination, route.destination)
          || event.occurrence_id !== route.occurrence_id || event.event_kind !== route.event_kind
          || BigInt(route.cursor) > BigInt(state.highest_contiguous_cursor)) throw task9Error("TASK9_STATE_INVALID");
      }
    }).catch((error: unknown) => {
      if (error instanceof BridgeServiceError && error.code === "TASK9_STATE_INVALID") throw error;
      throw error;
    });
  }

  async #parseAll<T>(
    transaction: DurableBridgeTransaction,
    namespace: Parameters<DurableBridgeTransaction["scan"]>[0],
    prefix: string,
    parser: (key: string, value: unknown) => T,
  ): Promise<readonly T[]> {
    const parsed: T[] = [];
    for (const entry of await transaction.scan(namespace)) {
      if (entry.key.startsWith(prefix)) parsed.push(parser(entry.key, entry.value));
    }
    return parsed;
  }
}
