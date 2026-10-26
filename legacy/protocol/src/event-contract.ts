/**
 * Task 9 device-event reference contract.
 *
 * This module validates the closed event branches and exposes opaque facts
 * minted by the authenticated device/Bridge authorities. It intentionally
 * does not implement cursor buffering, durable subscription storage, or an
 * Android collector.
 */

import type {
  AuthenticatedBindingContext,
  PreReplayIntegrityGate,
} from "./control-envelope.js";

const authorityBrand: unique symbol = Symbol("verified-capture-authority");
const ackAuthorityBrand: unique symbol = Symbol("verified-event-ack-authority");
const eventBrand: unique symbol = Symbol("verified-device-event");
const ackFactBrand: unique symbol = Symbol("verified-event-ack");

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const DECIMAL_U64 = /^(0|[1-9][0-9]*)$/;
const SCOPE = /^[A-Za-z][A-Za-z0-9_.-]{0,127}$/;
const OCCURRENCE = UUID;
const authorityObjects = new WeakSet<object>();
const ackAuthorityObjects = new WeakSet<object>();
const eventObjects = new WeakSet<object>();
const ackFactObjects = new WeakSet<object>();

type EventKind = "upsert" | "delete_tombstone" | "loss_marker";
type RevisionSnapshotInput = Readonly<{
  pairing_generation: string;
  authorization_epoch: string;
  scope_revisions: Readonly<Record<string, string>>;
}>;

export type VerifiedCaptureAuthority = Readonly<{
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
  readonly sourceCapability: string;
  readonly sourceEpoch: string;
  readonly revision: Readonly<{
    readonly pairingGeneration: bigint;
    readonly authorizationEpoch: bigint;
    readonly scopeRevisions: ReadonlyMap<string, bigint>;
  }>;
  readonly [authorityBrand]: true;
}>;

export type VerifiedDeviceEvent = Readonly<{
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
  readonly sourceEpoch: string;
  readonly sourceCapability: string;
  readonly cursor: bigint;
  readonly occurrenceId: string;
  readonly eventKind: EventKind;
  readonly pairingGeneration: bigint;
  readonly authorizationEpoch: bigint;
  readonly scopeRevisions: ReadonlyMap<string, bigint>;
  readonly routeByServerSubscriptionOnly: true;
  readonly [eventBrand]: true;
}>;

export type VerifiedEventAckAuthority = Readonly<{
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
  readonly sourceEpoch: string;
  readonly sourceCapability: string;
  readonly highestContiguousCursor: bigint;
  readonly [ackAuthorityBrand]: true;
}>;

export type VerifiedEventAck = Readonly<{
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
  readonly sourceEpoch: string;
  readonly sourceCapability: string;
  readonly highestContiguousCursor: bigint;
  readonly [ackFactBrand]: true;
}>;

export type DurableDeviceEventProjection = Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  deviceId: string;
  sourceEpoch: string;
  sourceCapability: string;
  cursor: bigint;
  occurrenceId: string;
  eventKind: EventKind;
  pairingGeneration: bigint;
  authorizationEpoch: bigint;
  scopeRevisions: ReadonlyMap<string, bigint>;
  routeByServerSubscriptionOnly: true;
}>;

export type DurableEventAckProjection = Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  deviceId: string;
  sourceEpoch: string;
  sourceCapability: string;
  highestContiguousCursor: bigint;
}>;

export interface EventAckStore {
  persistBeforeSign(fact: VerifiedEventAck): Promise<{ kind: "committed" } | { kind: "rejected" }>;
}

type DeviceBindingContext = Extract<AuthenticatedBindingContext, { kind: "device" }>;

export interface Task9PreReplayAuthorityResolver {
  loadCaptureAuthority(input: Readonly<{
    context: DeviceBindingContext;
    sourceCapability: string;
  }>): VerifiedCaptureAuthority | null | Promise<VerifiedCaptureAuthority | null>;
  loadEventAckAuthority(input: Readonly<{
    context: DeviceBindingContext;
    sourceCapability: string;
  }>): VerifiedEventAckAuthority | null | Promise<VerifiedEventAckAuthority | null>;
}

export type ServerSubscriptionTarget = Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  deviceId: string;
  sourceCapability: string;
  subscriptionId: string;
}>;

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};
const text = (value: unknown): value is string => typeof value === "string" && value.length > 0 && value.length <= 512 && !/[\u0000-\u001f\u007f]/u.test(value);
const u64 = (value: unknown): value is string => typeof value === "string" && DECIMAL_U64.test(value) && BigInt(value) <= 18_446_744_073_709_551_615n;
const uuid = (value: unknown): value is string => typeof value === "string" && UUID.test(value);
const scope = (value: unknown): value is string => typeof value === "string" && SCOPE.test(value);

const parseRevision = (value: unknown): Readonly<{
  pairingGeneration: bigint;
  authorizationEpoch: bigint;
  scopeRevisions: ReadonlyMap<string, bigint>;
}> => {
  if (!isObject(value) || !exactKeys(value, ["pairing_generation", "authorization_epoch", "scope_revisions"])
    || !u64(value.pairing_generation) || !u64(value.authorization_epoch) || !isObject(value.scope_revisions)) throw new Error("SCHEMA_INVALID");
  const entries: [string, bigint][] = [];
  for (const [key, revision] of Object.entries(value.scope_revisions)) {
    if (!scope(key) || !u64(revision)) throw new Error("SCHEMA_INVALID");
    entries.push([key, BigInt(revision)]);
  }
  return Object.freeze({ pairingGeneration: BigInt(value.pairing_generation), authorizationEpoch: BigInt(value.authorization_epoch), scopeRevisions: new Map(entries) });
};

const payloadShape = (payload: unknown): payload is Record<string, unknown> => {
  if (!isObject(payload) || !exactKeys(payload, ["source_epoch", "occurrence_id", "record_key", "record_revision", "cursor", "captured_at", "event_kind", "source_capability", "capture_revision", "record", "loss"])) return false;
  if (!uuid(payload.source_epoch) || !OCCURRENCE.test(String(payload.occurrence_id)) || !u64(payload.cursor)
    || !text(payload.captured_at) || !scope(payload.source_capability)) return false;
  if (payload.event_kind === "upsert" || payload.event_kind === "delete_tombstone") {
    if (!text(payload.record_key) || !u64(payload.record_revision) || payload.loss !== null) return false;
    if (payload.event_kind === "upsert") return isObject(payload.record);
    return payload.record === null;
  }
  if (payload.event_kind === "loss_marker") {
    if (payload.record_key !== null || payload.record_revision !== null || payload.record !== null || !isObject(payload.loss)) return false;
    return exactKeys(payload.loss, ["lost_from_cursor", "lost_to_cursor", "reason"])
      && u64(payload.loss.lost_from_cursor) && u64(payload.loss.lost_to_cursor)
      && (payload.loss.reason === "queue_limit" || payload.loss.reason === "listener_gap")
      && BigInt(String(payload.loss.lost_from_cursor)) <= BigInt(String(payload.loss.lost_to_cursor));
  }
  return false;
};

export function createVerifiedCaptureAuthority(input: Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  deviceId: string;
  sourceCapability: string;
  sourceEpoch: string;
  revision: Readonly<{ pairingGeneration: bigint; authorizationEpoch: bigint; scopeRevisions: ReadonlyMap<string, bigint> }>;
}>): VerifiedCaptureAuthority {
  if (!text(input.tenantId) || !text(input.humanPrincipalId) || !text(input.deviceId) || !scope(input.sourceCapability) || !uuid(input.sourceEpoch)
    || typeof input.revision?.pairingGeneration !== "bigint" || typeof input.revision.authorizationEpoch !== "bigint" || !(input.revision.scopeRevisions instanceof Map)) throw new Error("SCHEMA_INVALID");
  const fact = Object.freeze({
    tenantId: input.tenantId, humanPrincipalId: input.humanPrincipalId, deviceId: input.deviceId,
    sourceCapability: input.sourceCapability, sourceEpoch: input.sourceEpoch,
    revision: Object.freeze({ pairingGeneration: input.revision.pairingGeneration, authorizationEpoch: input.revision.authorizationEpoch, scopeRevisions: new Map(input.revision.scopeRevisions) }),
    [authorityBrand]: true as const,
  });
  authorityObjects.add(fact);
  return fact;
}

const frameParts = (frame: unknown): { context: Record<string, unknown>; envelope: Record<string, unknown>; payload: Record<string, unknown> } => {
  if (!isObject(frame) || !isObject(frame.context) || !isObject(frame.envelope) || !isObject(frame.envelope.payload)) throw new Error("SCHEMA_INVALID");
  return { context: frame.context, envelope: frame.envelope, payload: frame.envelope.payload };
};

export function validateDeviceEvent(frame: unknown, authority: VerifiedCaptureAuthority): VerifiedDeviceEvent {
  if (!isObject(authority) || !authorityObjects.has(authority) || authority[authorityBrand] !== true) throw new Error("AUTH_FAILED");
  const { context, envelope, payload } = frameParts(frame);
  if (envelope.messageType !== "device_event" || context.kind !== "device") throw new Error("AUTH_BINDING_MISMATCH");
  if (context.tenantId !== authority.tenantId || context.humanPrincipalId !== authority.humanPrincipalId || context.deviceId !== authority.deviceId) throw new Error("AUTH_BINDING_MISMATCH");
  if (!payloadShape(payload) || payload.source_epoch !== authority.sourceEpoch || payload.source_capability !== authority.sourceCapability) throw new Error("AUTH_BINDING_MISMATCH");
  const revision = parseRevision(payload.capture_revision);
  if (revision.pairingGeneration !== authority.revision.pairingGeneration || revision.authorizationEpoch !== authority.revision.authorizationEpoch) throw new Error("AUTH_BINDING_MISMATCH");
  for (const [key, value] of authority.revision.scopeRevisions) if (revision.scopeRevisions.get(key) !== value) throw new Error("AUTH_BINDING_MISMATCH");
  const event = Object.freeze({
    tenantId: authority.tenantId, humanPrincipalId: authority.humanPrincipalId, deviceId: authority.deviceId,
    sourceEpoch: authority.sourceEpoch, sourceCapability: authority.sourceCapability, cursor: BigInt(String(payload.cursor)),
    occurrenceId: String(payload.occurrence_id), eventKind: payload.event_kind as EventKind,
    pairingGeneration: revision.pairingGeneration, authorizationEpoch: revision.authorizationEpoch,
    scopeRevisions: new Map(revision.scopeRevisions), routeByServerSubscriptionOnly: true,
    [eventBrand]: true as const,
  });
  eventObjects.add(event);
  return event;
}

export function projectVerifiedDeviceEvent(event: VerifiedDeviceEvent): DurableDeviceEventProjection {
  if (!isObject(event) || !eventObjects.has(event) || event[eventBrand] !== true) throw new Error("AUTH_FAILED");
  return Object.freeze({
    tenantId: event.tenantId,
    humanPrincipalId: event.humanPrincipalId,
    deviceId: event.deviceId,
    sourceEpoch: event.sourceEpoch,
    sourceCapability: event.sourceCapability,
    cursor: event.cursor,
    occurrenceId: event.occurrenceId,
    eventKind: event.eventKind,
    pairingGeneration: event.pairingGeneration,
    authorizationEpoch: event.authorizationEpoch,
    scopeRevisions: new Map(event.scopeRevisions),
    routeByServerSubscriptionOnly: true,
  });
}

export function createVerifiedEventAckAuthority(input: Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  deviceId: string;
  sourceEpoch: string;
  sourceCapability: string;
  highestContiguousCursor: bigint;
}>): VerifiedEventAckAuthority {
  if (!text(input.tenantId) || !text(input.humanPrincipalId) || !text(input.deviceId) || !uuid(input.sourceEpoch) || !scope(input.sourceCapability) || typeof input.highestContiguousCursor !== "bigint" || input.highestContiguousCursor < 0n) throw new Error("SCHEMA_INVALID");
  const fact = Object.freeze({ ...input, [ackAuthorityBrand]: true as const });
  ackAuthorityObjects.add(fact);
  return fact;
}

const captureAuthorityMatches = (
  authority: VerifiedCaptureAuthority,
  context: DeviceBindingContext,
  payload: Record<string, unknown>,
): boolean => {
  if (authority.tenantId !== context.tenantId || authority.humanPrincipalId !== context.humanPrincipalId
    || authority.deviceId !== context.deviceId || authority.sourceEpoch !== payload.source_epoch
    || authority.sourceCapability !== payload.source_capability) return false;
  let revision;
  try {
    revision = parseRevision(payload.capture_revision);
  } catch {
    return false;
  }
  if (revision.pairingGeneration !== authority.revision.pairingGeneration
    || revision.authorizationEpoch !== authority.revision.authorizationEpoch
    || revision.scopeRevisions.size !== authority.revision.scopeRevisions.size) return false;
  for (const [key, value] of authority.revision.scopeRevisions) {
    if (revision.scopeRevisions.get(key) !== value) return false;
  }
  return true;
};

const ackAuthorityMatches = (
  authority: VerifiedEventAckAuthority,
  context: DeviceBindingContext,
  payload: Record<string, unknown>,
): boolean => authority.tenantId === context.tenantId
  && authority.humanPrincipalId === context.humanPrincipalId
  && authority.deviceId === context.deviceId
  && authority.sourceEpoch === payload.source_epoch
  && authority.sourceCapability === payload.source_capability
  && u64(payload.highest_contiguous_cursor)
  && authority.highestContiguousCursor === BigInt(payload.highest_contiguous_cursor);

/**
 * Adapts the opaque Task 9 source facts to the shared transport gate. The
 * verifier invokes this gate after signature/binding/authorization and before
 * replay admission, so a stale source fact cannot consume replay state.
 */
export function createTask9PreReplayIntegrityGate(
  resolver: Task9PreReplayAuthorityResolver,
): PreReplayIntegrityGate {
  return Object.freeze({
    async evaluate(messageType: string, payload: unknown, context: AuthenticatedBindingContext) {
      if (messageType !== "device_event" && messageType !== "event_ack") return { allowed: true as const };
      if (context.kind !== "device" || !isObject(payload)) {
        return { allowed: false as const, error: "INTEGRITY_FAILED" as const, decisionReason: "TASK9_BINDING_INVALID" };
      }
      const sourceCapability = payload.source_capability;
      if (!scope(sourceCapability)) {
        return { allowed: false as const, error: "INTEGRITY_FAILED" as const, decisionReason: "TASK9_PAYLOAD_INVALID" };
      }
      if (messageType === "device_event") {
        if (!payloadShape(payload)) {
          return { allowed: false as const, error: "INTEGRITY_FAILED" as const, decisionReason: "TASK9_PAYLOAD_INVALID" };
        }
        const authority = await resolver.loadCaptureAuthority(Object.freeze({ context, sourceCapability }));
        if (authority === null) {
          return { allowed: false as const, error: "NOT_AUTHORIZED" as const, decisionReason: "CAPTURE_AUTHORITY_UNAVAILABLE" };
        }
        if (!isObject(authority) || !authorityObjects.has(authority) || authority[authorityBrand] !== true) {
          return { allowed: false as const, error: "INTEGRITY_FAILED" as const, decisionReason: "CAPTURE_AUTHORITY_INVALID" };
        }
        return captureAuthorityMatches(authority, context, payload)
          ? { allowed: true as const }
          : { allowed: false as const, error: "NOT_AUTHORIZED" as const, decisionReason: "CAPTURE_AUTHORITY_MISMATCH" };
      }
      if (!exactKeys(payload, ["source_epoch", "source_capability", "highest_contiguous_cursor"])
        || !uuid(payload.source_epoch) || !u64(payload.highest_contiguous_cursor)) {
        return { allowed: false as const, error: "INTEGRITY_FAILED" as const, decisionReason: "TASK9_PAYLOAD_INVALID" };
      }
      const authority = await resolver.loadEventAckAuthority(Object.freeze({ context, sourceCapability }));
      if (authority === null) {
        return { allowed: false as const, error: "NOT_AUTHORIZED" as const, decisionReason: "ACK_AUTHORITY_UNAVAILABLE" };
      }
      if (!isObject(authority) || !ackAuthorityObjects.has(authority) || authority[ackAuthorityBrand] !== true) {
        return { allowed: false as const, error: "INTEGRITY_FAILED" as const, decisionReason: "ACK_AUTHORITY_INVALID" };
      }
      if (!ackAuthorityMatches(authority, context, payload)) {
        return { allowed: false as const, error: "INTEGRITY_FAILED" as const, decisionReason: "ACK_AUTHORITY_MISMATCH" };
      }
      return { allowed: true as const };
    },
  });
}

export function validateEventAck(frame: unknown, authority: VerifiedEventAckAuthority): VerifiedEventAck {
  if (!isObject(authority) || !ackAuthorityObjects.has(authority) || authority[ackAuthorityBrand] !== true) throw new Error("AUTH_FAILED");
  const { context, envelope, payload } = frameParts(frame);
  if (envelope.messageType !== "event_ack" || context.kind !== "device" || context.direction !== "bridge-to-app") throw new Error("AUTH_BINDING_MISMATCH");
  if (context.tenantId !== authority.tenantId || context.humanPrincipalId !== authority.humanPrincipalId || context.deviceId !== authority.deviceId
    || !exactKeys(payload, ["source_epoch", "source_capability", "highest_contiguous_cursor"])
    || payload.source_epoch !== authority.sourceEpoch || payload.source_capability !== authority.sourceCapability || !u64(payload.highest_contiguous_cursor)
    || BigInt(payload.highest_contiguous_cursor) !== authority.highestContiguousCursor) throw new Error("AUTH_BINDING_MISMATCH");
  const fact = Object.freeze({ tenantId: authority.tenantId, humanPrincipalId: authority.humanPrincipalId, deviceId: authority.deviceId,
    sourceEpoch: authority.sourceEpoch, sourceCapability: authority.sourceCapability,
    highestContiguousCursor: authority.highestContiguousCursor, [ackFactBrand]: true as const });
  ackFactObjects.add(fact);
  return fact;
}

export function projectVerifiedEventAck(fact: VerifiedEventAck): DurableEventAckProjection {
  if (!isObject(fact) || !ackFactObjects.has(fact) || fact[ackFactBrand] !== true) throw new Error("AUTH_FAILED");
  return Object.freeze({
    tenantId: fact.tenantId,
    humanPrincipalId: fact.humanPrincipalId,
    deviceId: fact.deviceId,
    sourceEpoch: fact.sourceEpoch,
    sourceCapability: fact.sourceCapability,
    highestContiguousCursor: fact.highestContiguousCursor,
  });
}

export function createServerSubscriptionRouter(targets: readonly ServerSubscriptionTarget[]): Readonly<{ route(event: VerifiedDeviceEvent): Promise<readonly [{ subscriptionId: string }, ...{ subscriptionId: string }[]]> }> {
  const copy = targets.map((target) => {
    if (!text(target.tenantId) || !text(target.humanPrincipalId) || !text(target.deviceId) || !scope(target.sourceCapability) || !text(target.subscriptionId)) throw new Error("SCHEMA_INVALID");
    return Object.freeze({ ...target });
  });
  return Object.freeze({
    route: async (event: VerifiedDeviceEvent) => {
      if (!isObject(event) || event[eventBrand] !== true) throw new Error("AUTH_FAILED");
      const matches = copy.filter((target) => target.tenantId === event.tenantId && target.humanPrincipalId === event.humanPrincipalId && target.deviceId === event.deviceId && target.sourceCapability === event.sourceCapability)
        .map((target) => Object.freeze({ subscriptionId: target.subscriptionId }));
      return Object.freeze(matches) as readonly [{ subscriptionId: string }, ...{ subscriptionId: string }[]];
    },
  });
}

export type Task9EventMessageType = "device_event" | "event_ack";
export const TASK9_EVENT_MAX_LIFETIME_SECONDS = Object.freeze({ device_event: 86_400, event_ack: 300 } as const);
export const TASK9_EVENT_REPLAY_POLICY = "task5_default" as const;
