/** Verified data-query grant primitives.
 *
 * Grant verification is deliberately independent from the device's current
 * Android permission/backend state.  The signed update carries declarations
 * of the five-part lineage key, but only the authenticated transport context
 * and an opaque same-human expansion authority can establish authority.
 */

import { timingSafeEqual } from "node:crypto";
import { canonicalBytes, sha256B64Url } from "./encoding.js";
import {
  capabilityFilterHash,
  validateCapabilityFilter,
  type CapabilityRegistry,
} from "./capability-manifest.js";
import { isReplayClaimReference, type ReplayClaimReference } from "./replay-window.js";

const verifiedGrantUpdateBrand: unique symbol = Symbol("verified-grant-update");
const verifiedGrantAckBrand: unique symbol = Symbol("verified-grant-ack");
const grantExpansionAuthorityBrand: unique symbol = Symbol("grant-expansion-authority");
const expansionAuthorities = new WeakSet<object>();

export type DataQueryGrantKey = Readonly<{
  readonly tenantId: string;
  readonly humanPrincipalId: string;
  readonly deviceId: string;
  readonly capability: string;
  readonly filterHash: string;
}>;

export type BridgeGrantState = "effective" | "revoked";
export type DeviceGrantUiState = "effective" | "grant_pending_ack" | "revoked_pending_ack" | "revoked";

export interface DataQueryGrantState {
  readonly key: DataQueryGrantKey;
  readonly grantRevision: bigint;
  readonly bridgeEffectiveState: BridgeGrantState;
  readonly deviceUiState: DeviceGrantUiState;
  readonly lastProposalDigest: string | null;
}

export type GrantExpansionAuthority = Readonly<{
  readonly key: DataQueryGrantKey;
  readonly humanPrincipalId: string;
  readonly authenticatedSessionId: string;
  readonly origin: "authenticated_device" | "authenticated_agent_session";
  readonly [grantExpansionAuthorityBrand]: true;
}>;

export type VerifiedGrantUpdate = Readonly<{
  readonly frame: unknown;
  readonly key: DataQueryGrantKey;
  readonly proposalDigest: string;
  readonly proposedState: BridgeGrantState;
  readonly grantRevision: bigint;
  readonly expansion: GrantExpansionAuthority | null;
  readonly [verifiedGrantUpdateBrand]: true;
}>;

const replayBoundGrantUpdateBrand: unique symbol = Symbol("replay-bound-grant-update");
export type ReplayBoundGrantUpdate = Readonly<VerifiedGrantUpdate & {
  readonly replay: ReplayClaimReference<"data_query_grant_update">;
  readonly [replayBoundGrantUpdateBrand]: true;
}>;

export type VerifiedGrantAck = Readonly<{
  readonly frame: unknown;
  readonly key: DataQueryGrantKey;
  readonly proposalDigest: string;
  readonly bridgeEffectiveState: BridgeGrantState;
  readonly grantRevision: bigint;
  readonly [verifiedGrantAckBrand]: true;
}>;

export type GrantAckOutboxEnvelope = Readonly<{
  readonly outboxId: string;
  readonly messageType: "data_query_grant_ack";
  readonly grantKey: DataQueryGrantKey;
  readonly grantRevision: bigint;
  readonly proposalDigest: string;
  readonly bridgeEffectiveState: BridgeGrantState;
  readonly rawWire: Readonly<{ readonly byteLength: number; copy(): Uint8Array }>;
}>;

export interface DataQueryGrantStore {
  applyBridgeUpdateAtomically(update: ReplayBoundGrantUpdate): Promise<
    | { readonly kind: "applied"; readonly state: DataQueryGrantState; readonly ackOutboxId: string }
    | { readonly kind: "same"; readonly state: DataQueryGrantState; readonly ackOutboxId: string }
    | { readonly kind: "rejected"; readonly error: "AUTH_FAILED" | "INVALID_STATE_TRANSITION" }
  >;
  loadGrantReplayAssociation(key: DataQueryGrantKey, proposalDigest: string): Promise<ReplayClaimReference<"data_query_grant_update"> | null>;
  loadAckOutbox(id: string): Promise<GrantAckOutboxEnvelope | null>;
  markAckSent(id: string): Promise<void>;
  recoverUnsentAcks(): AsyncIterable<GrantAckOutboxEnvelope>;
}

const ID = /^[^\u0000-\u001f\u007f]{1,512}$/u;
const DIGEST = /^[A-Za-z0-9_-]{43}$/u;
const U64 = /^(0|[1-9][0-9]*)$/u;
const MAX_U64 = 18_446_744_073_709_551_615n;

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};
const validId = (value: unknown): value is string => typeof value === "string" && ID.test(value);
const parseU64 = (value: unknown): bigint | null => {
  if (typeof value !== "string" || !U64.test(value)) return null;
  const parsed = BigInt(value);
  return parsed <= MAX_U64 ? parsed : null;
};
const equalDigest = (left: string, right: string): boolean => {
  if (!DIGEST.test(left) || !DIGEST.test(right)) return false;
  const a = Buffer.from(left, "base64url");
  const b = Buffer.from(right, "base64url");
  return a.byteLength === 32 && b.byteLength === 32 && timingSafeEqual(a, b);
};

const keyToWire = (key: DataQueryGrantKey): Readonly<Record<string, string>> => ({
  tenant_id: key.tenantId,
  human_principal_id: key.humanPrincipalId,
  device_id: key.deviceId,
  capability: key.capability,
  filter_hash: key.filterHash,
});

const parseWireKey = (value: unknown): DataQueryGrantKey => {
  if (!isRecord(value) || !exactKeys(value, ["tenant_id", "human_principal_id", "device_id", "capability", "filter_hash"]) || !validId(value.tenant_id) || !validId(value.human_principal_id) || !validId(value.device_id) || !validId(value.capability) || typeof value.filter_hash !== "string" || !DIGEST.test(value.filter_hash)) throw new Error("SCHEMA_INVALID");
  const decoded = Buffer.from(value.filter_hash, "base64url");
  if (decoded.byteLength !== 32 || decoded.toString("base64url") !== value.filter_hash) throw new Error("SCHEMA_INVALID");
  return Object.freeze({ tenantId: value.tenant_id, humanPrincipalId: value.human_principal_id, deviceId: value.device_id, capability: value.capability, filterHash: value.filter_hash });
};

const sameKey = (left: DataQueryGrantKey, right: DataQueryGrantKey): boolean =>
  left.tenantId === right.tenantId && left.humanPrincipalId === right.humanPrincipalId && left.deviceId === right.deviceId && left.capability === right.capability && equalDigest(left.filterHash, right.filterHash);
const validKey = (key: DataQueryGrantKey): boolean => validId(key.tenantId) && validId(key.humanPrincipalId) && validId(key.deviceId) && validId(key.capability) && typeof key.filterHash === "string" && DIGEST.test(key.filterHash) && (() => { const bytes = Buffer.from(key.filterHash, "base64url"); return bytes.byteLength === 32 && bytes.toString("base64url") === key.filterHash; })();

const frameParts = (frame: unknown): Readonly<{ messageType: string; payload: Record<string, unknown>; context: Record<string, unknown> }> => {
  if (!isRecord(frame) || !isRecord(frame.envelope) || !isRecord(frame.envelope.payload) || !isRecord(frame.context) || typeof frame.envelope.messageType !== "string") throw new Error("AUTH_FAILED");
  return { messageType: frame.envelope.messageType, payload: frame.envelope.payload, context: frame.context };
};

const validateFilterForGrant = (capability: string, filter: unknown, registry: CapabilityRegistry): void => {
  const resolved = registry.resolve(capability);
  if (resolved === null) throw new Error("SCOPE_DENIED");
  if (resolved.filterSchema === "none") {
    // Every grant still has a filter.  Capabilities without a selector use a
    // closed, explicit all-records branch rather than an omitted field.
    if (!isRecord(filter) || !exactKeys(filter, ["kind"]) || filter.kind !== "all") throw new Error("SCHEMA_INVALID");
    return;
  }
  if (!validateCapabilityFilter(capability, filter, registry).ok) throw new Error("SCHEMA_INVALID");
};

export function mintGrantExpansionAuthority(input: Readonly<{
  readonly key: DataQueryGrantKey;
  readonly humanPrincipalId: string;
  readonly authenticatedSessionId: string;
  readonly origin: "authenticated_device" | "authenticated_agent_session";
}>): GrantExpansionAuthority {
  if (!validKey(input.key) || input.key.humanPrincipalId !== input.humanPrincipalId || !validId(input.authenticatedSessionId)) throw new Error("AUTH_FAILED");
  const value = Object.freeze({ ...input, [grantExpansionAuthorityBrand]: true as const });
  expansionAuthorities.add(value);
  return value;
}

export function dataQueryGrantProposalDigest(input: Readonly<{ filter: unknown; key: DataQueryGrantKey; grantRevision: bigint; proposedState: BridgeGrantState }>): string {
  if (input.grantRevision < 0n || input.grantRevision > MAX_U64) throw new Error("SCHEMA_INVALID");
  return sha256B64Url(canonicalBytes({ filter: input.filter, grant_key: keyToWire(input.key), grant_revision: input.grantRevision.toString(), proposed_state: input.proposedState }));
}

export function createDataQueryGrantState(input: Readonly<{
  readonly key: DataQueryGrantKey;
  readonly grantRevision?: bigint;
  readonly bridgeEffectiveState?: BridgeGrantState;
  readonly deviceUiState?: DeviceGrantUiState;
  readonly lastProposalDigest?: string | null;
}>): DataQueryGrantState {
  if (!validKey(input.key)) throw new Error("SCHEMA_INVALID");
  const revision = input.grantRevision ?? 0n;
  if (revision < 0n || revision > MAX_U64) throw new Error("SCHEMA_INVALID");
  const bridge = input.bridgeEffectiveState ?? "revoked";
  const ui = input.deviceUiState ?? bridge;
  if (bridge !== "effective" && bridge !== "revoked") throw new Error("SCHEMA_INVALID");
  if (!["effective", "grant_pending_ack", "revoked_pending_ack", "revoked"].includes(ui)) throw new Error("SCHEMA_INVALID");
  if (input.lastProposalDigest !== null && input.lastProposalDigest !== undefined && !DIGEST.test(input.lastProposalDigest)) throw new Error("SCHEMA_INVALID");
  return Object.freeze({ key: Object.freeze({ ...input.key }), grantRevision: revision, bridgeEffectiveState: bridge, deviceUiState: ui, lastProposalDigest: input.lastProposalDigest ?? null });
}

export function verifyDataQueryGrantUpdate(frame: unknown, registry: CapabilityRegistry, expansion: GrantExpansionAuthority | null): VerifiedGrantUpdate {
  const { messageType, payload, context } = frameParts(frame);
  if (messageType !== "data_query_grant_update" || !exactKeys(payload, ["grant_key", "filter", "grant_revision", "proposed_state", "proposal_digest"])) throw new Error("SCHEMA_INVALID");
  if (context.kind !== "device" || context.direction !== "app-to-bridge" || !validId(context.tenantId) || !validId(context.humanPrincipalId) || !validId(context.deviceId)) throw new Error("AUTH_FAILED");
  const key = parseWireKey(payload.grant_key);
  if (key.tenantId !== context.tenantId || key.humanPrincipalId !== context.humanPrincipalId || key.deviceId !== context.deviceId) throw new Error("AUTH_FAILED");
  const revision = parseU64(payload.grant_revision);
  if (revision === null || (payload.proposed_state !== "effective" && payload.proposed_state !== "revoked") || typeof payload.proposal_digest !== "string" || !DIGEST.test(payload.proposal_digest)) throw new Error("SCHEMA_INVALID");
  validateFilterForGrant(key.capability, payload.filter, registry);
  const computedFilterHash = capabilityFilterHash(key.capability, payload.filter, registry);
  if (!equalDigest(computedFilterHash, key.filterHash)) throw new Error("INTEGRITY_FAILED");
  const computedProposal = dataQueryGrantProposalDigest({ filter: payload.filter, key, grantRevision: revision, proposedState: payload.proposed_state });
  if (!equalDigest(computedProposal, payload.proposal_digest)) throw new Error("INTEGRITY_FAILED");
  if (payload.proposed_state === "effective") {
    if (expansion === null || !expansionAuthorities.has(expansion as object) || expansion.humanPrincipalId !== key.humanPrincipalId || !sameKey(expansion.key, key)) throw new Error("AUTH_FAILED");
  } else if (expansion !== null && (!expansionAuthorities.has(expansion as object) || expansion.humanPrincipalId !== key.humanPrincipalId)) {
    throw new Error("AUTH_FAILED");
  }
  return Object.freeze({ frame, key, proposalDigest: payload.proposal_digest, proposedState: payload.proposed_state, grantRevision: revision, expansion, [verifiedGrantUpdateBrand]: true as const });
}

/** Binds a verified grant mutation to the exact replay row admitted for its
 * signed update. The replay projection is minted only by replay-window from a
 * branded backend claim; callers cannot construct it structurally. */
export function attachGrantReplayAssociation(
  update: VerifiedGrantUpdate,
  replay: ReplayClaimReference<string>,
): ReplayBoundGrantUpdate {
  if ((update as Record<PropertyKey, unknown>)[verifiedGrantUpdateBrand] !== true
    || !isReplayClaimReference(replay)
    || replay.messageType !== "data_query_grant_update") throw new Error("AUTH_FAILED");
  const candidate = update.frame;
  if (isRecord(candidate) && isRecord(candidate.envelope)) {
    const envelope = candidate.envelope;
    if (isRecord(envelope.header) && typeof envelope.header.message_id === "string"
      && envelope.header.message_id !== replay.messageId) throw new Error("INTEGRITY_FAILED");
    if (typeof envelope.envelopeDigest === "string" && envelope.envelopeDigest !== replay.envelopeDigest) {
      throw new Error("INTEGRITY_FAILED");
    }
  }
  return Object.freeze({
    ...update,
    replay: replay as ReplayClaimReference<"data_query_grant_update">,
    [replayBoundGrantUpdateBrand]: true as const,
  });
}

export function verifyDataQueryGrantAck(frame: unknown): VerifiedGrantAck {
  const { messageType, payload, context } = frameParts(frame);
  if (messageType !== "data_query_grant_ack" || !exactKeys(payload, ["grant_key", "grant_revision", "proposal_digest", "bridge_effective_state"])) throw new Error("SCHEMA_INVALID");
  if (context.kind !== "device" || context.direction !== "bridge-to-app") throw new Error("AUTH_FAILED");
  const key = parseWireKey(payload.grant_key);
  const revision = parseU64(payload.grant_revision);
  if (revision === null || typeof payload.proposal_digest !== "string" || !DIGEST.test(payload.proposal_digest) || (payload.bridge_effective_state !== "effective" && payload.bridge_effective_state !== "revoked")) throw new Error("SCHEMA_INVALID");
  return Object.freeze({ frame, key, proposalDigest: payload.proposal_digest, bridgeEffectiveState: payload.bridge_effective_state, grantRevision: revision, [verifiedGrantAckBrand]: true as const });
}

export function reduceDataQueryGrant(state: DataQueryGrantState, event: VerifiedGrantUpdate | VerifiedGrantAck): DataQueryGrantState {
  if (!state || !event || typeof event !== "object") throw new Error("SCHEMA_INVALID");
  if ((event as Record<PropertyKey, unknown>)[verifiedGrantUpdateBrand] === true) {
    const update = event as VerifiedGrantUpdate;
    if (!sameKey(update.key, state.key)) throw new Error("AUTH_FAILED");
    if (update.grantRevision < state.grantRevision) throw new Error("INVALID_STATE_TRANSITION");
    if (update.grantRevision === state.grantRevision) {
      if (state.lastProposalDigest === update.proposalDigest) return state;
      throw new Error("INVALID_STATE_TRANSITION");
    }
    return Object.freeze({ ...state, grantRevision: update.grantRevision, deviceUiState: update.proposedState === "effective" ? "grant_pending_ack" : "revoked_pending_ack", lastProposalDigest: update.proposalDigest });
  }
  if ((event as Record<PropertyKey, unknown>)[verifiedGrantAckBrand] === true) {
    const ack = event as VerifiedGrantAck;
    if (!sameKey(ack.key, state.key) || state.lastProposalDigest === null || !equalDigest(ack.proposalDigest, state.lastProposalDigest)) throw new Error("AUTH_FAILED");
    if (ack.grantRevision !== state.grantRevision) throw new Error("INVALID_STATE_TRANSITION");
    const expected = state.deviceUiState === "grant_pending_ack" ? "effective" : state.deviceUiState === "revoked_pending_ack" ? "revoked" : null;
    if (expected === null || ack.bridgeEffectiveState !== expected) throw new Error("INVALID_STATE_TRANSITION");
    return Object.freeze({ ...state, bridgeEffectiveState: ack.bridgeEffectiveState, deviceUiState: ack.bridgeEffectiveState, lastProposalDigest: null });
  }
  throw new Error("AUTH_FAILED");
}

export function grantKeyToWire(key: DataQueryGrantKey): Readonly<Record<string, string>> { return keyToWire(key); }

const grantStorageKey = (key: DataQueryGrantKey): string => sha256B64Url(canonicalBytes(keyToWire(key)));
const retained = (bytes: Uint8Array): Readonly<{ readonly byteLength: number; copy(): Uint8Array }> => {
  const copy = Uint8Array.from(bytes);
  return Object.freeze({ byteLength: copy.byteLength, copy: () => Uint8Array.from(copy) });
};

/**
 * Deterministic reference transaction for the Bridge grant path.  A
 * production adapter may replace the maps with a database transaction, but
 * must preserve this ordering: state, ACK outbox and replay association are
 * committed together; sending is deliberately outside the transaction.
 */
export class MemoryDataQueryGrantStore implements DataQueryGrantStore {
  readonly #states = new Map<string, DataQueryGrantState>();
  readonly #outbox = new Map<string, GrantAckOutboxEnvelope>();
  readonly #sent = new Set<string>();
  readonly #replayByProposal = new Map<string, ReplayClaimReference<"data_query_grant_update">>();
  readonly #proposalByReplayClaim = new Map<string, string>();

  constructor(initial: readonly DataQueryGrantState[] = []) {
    for (const state of initial) this.#states.set(grantStorageKey(state.key), createDataQueryGrantState(state));
  }

  private ackFor(update: VerifiedGrantUpdate, state: DataQueryGrantState): GrantAckOutboxEnvelope {
    const wirePayload = {
      grant_key: keyToWire(state.key),
      grant_revision: state.grantRevision.toString(),
      proposal_digest: update.proposalDigest,
      bridge_effective_state: state.bridgeEffectiveState,
    } as const;
    const raw = canonicalBytes(wirePayload);
    const outboxId = sha256B64Url(canonicalBytes({ message_type: "data_query_grant_ack", payload: wirePayload }));
    const existing = this.#outbox.get(outboxId);
    if (existing) return existing;
    const envelope: GrantAckOutboxEnvelope = Object.freeze({
      outboxId,
      messageType: "data_query_grant_ack",
      grantKey: state.key,
      grantRevision: state.grantRevision,
      proposalDigest: update.proposalDigest,
      bridgeEffectiveState: state.bridgeEffectiveState,
      rawWire: retained(raw),
    });
    return envelope;
  }

  async applyBridgeUpdateAtomically(update: ReplayBoundGrantUpdate): Promise<
    | { readonly kind: "applied"; readonly state: DataQueryGrantState; readonly ackOutboxId: string }
    | { readonly kind: "same"; readonly state: DataQueryGrantState; readonly ackOutboxId: string }
    | { readonly kind: "rejected"; readonly error: "AUTH_FAILED" | "INVALID_STATE_TRANSITION" }
  > {
    if ((update as Record<PropertyKey, unknown>)[verifiedGrantUpdateBrand] !== true
      || (update as Record<PropertyKey, unknown>)[replayBoundGrantUpdateBrand] !== true
      || !isReplayClaimReference((update as ReplayBoundGrantUpdate).replay)) return { kind: "rejected", error: "AUTH_FAILED" };
    const bound = update as ReplayBoundGrantUpdate;
    const storageKey = grantStorageKey(update.key);
    const proposalKey = `${storageKey}\u0000${update.proposalDigest}`;
    const priorReplay = this.#replayByProposal.get(proposalKey);
    if (priorReplay && priorReplay.claimId !== bound.replay.claimId) return { kind: "rejected", error: "AUTH_FAILED" };
    if (priorReplay && (priorReplay.messageId !== bound.replay.messageId || priorReplay.envelopeDigest !== bound.replay.envelopeDigest)) {
      return { kind: "rejected", error: "AUTH_FAILED" };
    }
    const priorProposal = this.#proposalByReplayClaim.get(bound.replay.claimId);
    if (priorProposal !== undefined && priorProposal !== proposalKey) return { kind: "rejected", error: "AUTH_FAILED" };
    const prior = this.#states.get(storageKey) ?? createDataQueryGrantState({ key: update.key });
    if (!sameKey(prior.key, update.key)) return { kind: "rejected", error: "AUTH_FAILED" };
    if (update.grantRevision < prior.grantRevision) return { kind: "rejected", error: "INVALID_STATE_TRANSITION" };
    if (update.grantRevision === prior.grantRevision) {
      if (prior.lastProposalDigest !== update.proposalDigest) return { kind: "rejected", error: "INVALID_STATE_TRANSITION" };
      const ack = this.ackFor(update, prior);
      if (!this.#outbox.has(ack.outboxId)) this.#outbox.set(ack.outboxId, ack);
      this.#replayByProposal.set(proposalKey, bound.replay);
      this.#proposalByReplayClaim.set(bound.replay.claimId, proposalKey);
      return { kind: "same", state: prior, ackOutboxId: ack.outboxId };
    }
    // The Bridge effective state changes in the same logical commit as the
    // immutable ACK.  No network send occurs before both maps are updated.
    const nextState = createDataQueryGrantState({ key: prior.key, grantRevision: update.grantRevision, bridgeEffectiveState: update.proposedState, deviceUiState: update.proposedState, lastProposalDigest: update.proposalDigest });
    const ack = this.ackFor(update, nextState);
    this.#outbox.set(ack.outboxId, ack);
    this.#states.set(storageKey, nextState);
    this.#replayByProposal.set(proposalKey, bound.replay);
    this.#proposalByReplayClaim.set(bound.replay.claimId, proposalKey);
    return { kind: "applied", state: nextState, ackOutboxId: ack.outboxId };
  }

  async loadGrantReplayAssociation(key: DataQueryGrantKey, proposalDigest: string): Promise<ReplayClaimReference<"data_query_grant_update"> | null> {
    return this.#replayByProposal.get(`${grantStorageKey(key)}\u0000${proposalDigest}`) ?? null;
  }

  async loadAckOutbox(id: string): Promise<GrantAckOutboxEnvelope | null> { return this.#outbox.get(id) ?? null; }
  async markAckSent(id: string): Promise<void> { if (!this.#outbox.has(id)) throw new Error("NOT_FOUND"); this.#sent.add(id); }
  async *recoverUnsentAcks(): AsyncIterable<GrantAckOutboxEnvelope> {
    for (const [id, envelope] of this.#outbox) if (!this.#sent.has(id)) yield envelope;
  }
  async load(key: DataQueryGrantKey): Promise<DataQueryGrantState | null> { return this.#states.get(grantStorageKey(key)) ?? null; }
}
