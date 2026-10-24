import {
  retainExactWireBytes,
  TASK5_MAX_LIFETIME_SECONDS,
  type Task5MessageType,
  type AuthenticatedBindingContext,
  type ExactWireBytes,
} from "./control-envelope.js";
import { verifyEs256 } from "./crypto.js";
import { canonicalBytes, parseCanonicalJson, sha256B64Url, signingPreimage } from "./encoding.js";
import { loadMessageRegistry } from "./message-registry.js";
import { parseSignatureDomain } from "./profile.js";
import { validateSchema } from "./schema-validator.js";
import type { KeyRecord } from "./key-ring.js";
import type { Clock, Signer, SignerRole } from "./ports.js";
import type {
  ReplaySpace,
} from "./replay-window.js";

export type OrdinaryTask5MessageType = "device_ping" | "bridge_ping" | "device_presence" | "event_ack";
export type RotationTask5MessageType = Exclude<Task5MessageType, OrdinaryTask5MessageType | "device_event">;

const sendableOutboundEnvelopeBrand: unique symbol = Symbol("sendable-outbound-envelope");
export type SendableOutboundEnvelope = Readonly<{
  readonly messageType: Task5MessageType;
  readonly messageId: string;
  readonly sequence: bigint;
  readonly space: ReplaySpace;
  readonly issuedAt: string;
  readonly expiresAt: string;
  readonly envelopeDigest: string;
  readonly rawWire: ExactWireBytes;
  readonly [sendableOutboundEnvelopeBrand]: true;
}>;

const preparedRotationHandleBrand: unique symbol = Symbol("prepared-rotation-handle");
const rotationHandleOwners = new WeakMap<object, object>();
export type PreparedRotationHandle = Readonly<{
  readonly handleId: string;
  readonly messageType: RotationTask5MessageType;
  readonly messageId: string;
  readonly sequence: bigint;
  readonly space: ReplaySpace;
  readonly envelopeDigest: string;
  readonly [preparedRotationHandleBrand]: true;
}>;

export interface OutboundPreparationInput {
  readonly messageType: Task5MessageType;
  readonly payload: unknown;
  readonly messageId: string;
  readonly issuedAt: string;
  readonly expiresAt: string;
}

export interface OutboundEnvelopeStore {
  prepareOrdinaryAtomically(
    input: OutboundPreparationInput & { readonly messageType: OrdinaryTask5MessageType },
    context: AuthenticatedBindingContext,
  ): Promise<
    | { kind: "prepared" | "same"; envelope: SendableOutboundEnvelope }
    | { kind: "rejected"; error: "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "SEQUENCE_EXHAUSTED" }
  >;
  prepareRotationAtomically(
    input: OutboundPreparationInput & { readonly messageType: RotationTask5MessageType },
    context: AuthenticatedBindingContext,
  ): Promise<
    | { kind: "prepared" | "same"; handle: PreparedRotationHandle }
    | { kind: "rejected"; error: "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "SEQUENCE_EXHAUSTED" }
  >;
  loadOrdinarySendable(space: ReplaySpace, messageId: string): Promise<SendableOutboundEnvelope | null>;
  loadRotationHandle(space: ReplaySpace, messageId: string): Promise<PreparedRotationHandle | null>;
}

type OutboundError = "SCHEMA_INVALID" | "AUTH_FAILED" | "INTEGRITY_FAILED" | "MESSAGE_EXPIRED" | "SEQUENCE_EXHAUSTED";

export async function prepareSignedEnvelope(
  input: OutboundPreparationInput & { readonly messageType: OrdinaryTask5MessageType },
  context: AuthenticatedBindingContext,
  store: OutboundEnvelopeStore,
): Promise<
  | { ok: true; kind: "prepared" | "same"; envelope: SendableOutboundEnvelope }
  | { ok: false; error: OutboundError }
> {
  const decision = await store.prepareOrdinaryAtomically(input, context);
  return decision.kind === "rejected"
    ? { ok: false, error: decision.error }
    : { ok: true, kind: decision.kind, envelope: decision.envelope };
}

export async function prepareRotationEnvelope(
  input: OutboundPreparationInput & { readonly messageType: RotationTask5MessageType },
  context: AuthenticatedBindingContext,
  store: OutboundEnvelopeStore,
): Promise<
  | { ok: true; kind: "prepared" | "same"; handle: PreparedRotationHandle }
  | { ok: false; error: OutboundError }
> {
  const decision = await store.prepareRotationAtomically(input, context);
  return decision.kind === "rejected"
    ? { ok: false, error: decision.error }
    : { ok: true, kind: decision.kind, handle: decision.handle };
}

export interface DeterministicOutboundRowSnapshot {
  readonly kind: "ordinary" | "rotation";
  readonly semanticDigest: string;
  readonly messageType: Task5MessageType;
  readonly messageId: string;
  readonly sequence: bigint;
  readonly space: ReplaySpace;
  readonly issuedAt: string;
  readonly expiresAt: string;
  readonly envelopeDigest: string;
  readonly rawWire: Uint8Array;
}

export interface DeterministicOutboundSnapshot {
  readonly nextSequences: readonly Readonly<{ spaceKey: string; next: bigint }>[];
  readonly rows: readonly DeterministicOutboundRowSnapshot[];
}

export type OutboundPreparationBarrierStage =
  | "before_reservation" | "after_reservation" | "after_signing"
  | "after_self_verification" | "after_retention" | "before_commit";

export interface DeterministicOutboundDependencies {
  readonly signer: Signer;
  readonly keyRecord: KeyRecord;
  /** Resolve the signing record at commit time so rotation/revocation cannot
   * leave this durable writer holding a stale authority object. */
  readonly keyRecordFor?: ((context: AuthenticatedBindingContext) => KeyRecord | null) | undefined;
  readonly clock: Clock;
  readonly barrier?: ((stage: OutboundPreparationBarrierStage) => Promise<void>) | undefined;
}

const outboundRegistry = loadMessageRegistry();
const U64_MAX = 18_446_744_073_709_551_615n;

const signerRoleFor = (direction: ReplaySpace["direction"]): SignerRole =>
  direction === "app-to-bridge" ? "device"
    : direction === "adapter-to-bridge" ? "adapter" : "bridge-command";

const outboundSpaceKey = (space: ReplaySpace): string => space.kind === "device"
  ? `device\u0000${space.credentialId}\u0000${space.pairingGeneration}\u0000${space.keyId}\u0000${space.direction}`
  : `adapter\u0000${space.credentialId}\u0000${space.adapterCredentialGeneration}\u0000${space.keyId}\u0000${space.direction}`;

const cloneSpace = (space: ReplaySpace): ReplaySpace => Object.freeze({ ...space });

const sameBytes = (left: Uint8Array, right: Uint8Array): boolean =>
  left.byteLength === right.byteLength && left.every((byte, index) => byte === right[index]);

/** Deterministic durable reference model for reservation/sign/verify/retention.
 * Its snapshot contains only owned immutable bytes and scalar state. */
export class DeterministicOutboundEnvelopeStore implements OutboundEnvelopeStore {
  readonly #dependencies: DeterministicOutboundDependencies;
  readonly #token = Object.freeze({});
  readonly #next = new Map<string, bigint>();
  readonly #rows = new Map<string, DeterministicOutboundRowSnapshot>();
  #serialTail: Promise<void> = Promise.resolve();

  constructor(dependencies: DeterministicOutboundDependencies, snapshot?: DeterministicOutboundSnapshot) {
    this.#dependencies = dependencies;
    for (const item of snapshot?.nextSequences ?? []) this.#next.set(item.spaceKey, item.next);
    for (const row of snapshot?.rows ?? []) {
      const copy = this.#cloneRow(row);
      this.#rows.set(`${outboundSpaceKey(copy.space)}\u0000${copy.messageId}`, copy);
    }
  }

  static restart(snapshot: DeterministicOutboundSnapshot, dependencies: DeterministicOutboundDependencies) {
    return new DeterministicOutboundEnvelopeStore(dependencies, snapshot);
  }

  #cloneRow(row: DeterministicOutboundRowSnapshot): DeterministicOutboundRowSnapshot {
    return Object.freeze({ ...row, space: cloneSpace(row.space), rawWire: Uint8Array.from(row.rawWire) });
  }

  snapshot(): DeterministicOutboundSnapshot {
    return Object.freeze({
      nextSequences: Object.freeze([...this.#next].map(([spaceKey, next]) => Object.freeze({ spaceKey, next }))),
      rows: Object.freeze([...this.#rows.values()].map((row) => this.#cloneRow(row))),
    });
  }

  #sendable(row: DeterministicOutboundRowSnapshot): SendableOutboundEnvelope {
    return Object.freeze({
      messageType: row.messageType,
      messageId: row.messageId,
      sequence: row.sequence,
      space: cloneSpace(row.space),
      issuedAt: row.issuedAt,
      expiresAt: row.expiresAt,
      envelopeDigest: row.envelopeDigest,
      rawWire: retainExactWireBytes(row.rawWire),
      [sendableOutboundEnvelopeBrand]: true as const,
    });
  }

  #handle(row: DeterministicOutboundRowSnapshot): PreparedRotationHandle {
    const handle: PreparedRotationHandle = Object.freeze({
      handleId: sha256B64Url(canonicalBytes({ envelope_digest: row.envelopeDigest, message_id: row.messageId })),
      messageType: row.messageType as RotationTask5MessageType,
      messageId: row.messageId,
      sequence: row.sequence,
      space: cloneSpace(row.space),
      envelopeDigest: row.envelopeDigest,
      [preparedRotationHandleBrand]: true as const,
    });
    rotationHandleOwners.set(handle, this.#token);
    return handle;
  }

  async #withSerial<T>(operation: () => Promise<T>): Promise<T> {
    const previous = this.#serialTail;
    let release!: () => void;
    this.#serialTail = new Promise<void>((resolve) => { release = resolve; });
    await previous;
    try {
      return await operation();
    } finally {
      release();
    }
  }

  async #prepareUnlocked(
    kind: "ordinary" | "rotation",
    input: OutboundPreparationInput,
    context: AuthenticatedBindingContext,
  ): Promise<{ kind: "prepared" | "same"; row: DeterministicOutboundRowSnapshot } | { kind: "rejected"; error: OutboundError }> {
    const now = this.#dependencies.clock.wallNow().getTime();
    const entries = outboundRegistry.messages.filter((candidate) => candidate.message_type === input.messageType);
    if (entries.length !== 1) return { kind: "rejected", error: "SCHEMA_INVALID" };
    const entry = entries[0];
    if (!entry || entry.direction !== context.direction) return { kind: "rejected", error: "AUTH_FAILED" };
    try {
      validateSchema(entry.schema_id, input.payload);
    } catch {
      return { kind: "rejected", error: "SCHEMA_INVALID" };
    }
    const issued = Date.parse(input.issuedAt);
    const expires = Date.parse(input.expiresAt);
    if (!Number.isFinite(issued) || !Number.isFinite(expires) || expires <= issued || issued > now + 60_000
      || expires <= now || expires - issued > TASK5_MAX_LIFETIME_SECONDS[input.messageType] * 1000) {
      return { kind: "rejected", error: "MESSAGE_EXPIRED" };
    }
    const role = signerRoleFor(entry.direction);
    const key = this.#dependencies.keyRecordFor?.(context) ?? this.#dependencies.keyRecord;
    if (key.lifecycle !== "active" || this.#dependencies.signer.keyId !== key.keyId
      || this.#dependencies.signer.role !== role || key.publicJwk.kid !== key.keyId) {
      return { kind: "rejected", error: "AUTH_FAILED" };
    }
    const space: ReplaySpace = context.kind === "device"
      ? Object.freeze({ kind: "device", credentialId: context.credentialId, pairingGeneration: context.pairingGeneration, keyId: key.keyId, direction: context.direction })
      : Object.freeze({ kind: "adapter", credentialId: context.credentialId, adapterCredentialGeneration: context.adapterCredentialGeneration, keyId: key.keyId, direction: context.direction });
    const spaceKey = outboundSpaceKey(space);
    const rowKey = `${spaceKey}\u0000${input.messageId}`;
    const semanticDigest = sha256B64Url(canonicalBytes({
      context: context.kind === "device"
        ? { credential_id: context.credentialId, device_id: context.deviceId, pairing_generation: context.pairingGeneration.toString(), direction: context.direction }
        : { credential_id: context.credentialId, adapter_credential_generation: context.adapterCredentialGeneration.toString(), direction: context.direction },
      expires_at: input.expiresAt, issued_at: input.issuedAt, message_type: input.messageType, payload: input.payload,
    }));
    const previous = this.#rows.get(rowKey);
    if (previous) return previous.semanticDigest === semanticDigest && previous.kind === kind
      ? { kind: "same", row: previous }
      : { kind: "rejected", error: "INTEGRITY_FAILED" };
    await this.#dependencies.barrier?.("before_reservation");
    const sequence = this.#next.get(spaceKey) ?? 0n;
    if (sequence > U64_MAX) return { kind: "rejected", error: "SEQUENCE_EXHAUSTED" };
    const reservedNext = sequence === U64_MAX ? U64_MAX + 1n : sequence + 1n;
    await this.#dependencies.barrier?.("after_reservation");
    const header = {
      protocol_version: "1.0",
      message_schema: entry.schema_id,
      message_type: input.messageType,
      message_id: input.messageId,
      key_id: key.keyId,
      direction: entry.direction,
      sequence: sequence.toString(),
      issued_at: input.issuedAt,
      expires_at: input.expiresAt,
      payload_digest: sha256B64Url(canonicalBytes(input.payload)),
      ...(context.kind === "device"
        ? { device_id: context.deviceId, pairing_generation: context.pairingGeneration.toString(), connection_generation: context.connectionGeneration.toString() }
        : { adapter_credential_id: context.credentialId, adapter_credential_generation: context.adapterCredentialGeneration.toString() }),
    };
    const signature = await this.#dependencies.signer.sign(signingPreimage(parseSignatureDomain(entry.signature_domain), { header, payload: input.payload }));
    await this.#dependencies.barrier?.("after_signing");
    if (!verifyEs256(key.publicJwk, signingPreimage(parseSignatureDomain(entry.signature_domain), { header, payload: input.payload }), signature)) {
      return { kind: "rejected", error: "INTEGRITY_FAILED" };
    }
    await this.#dependencies.barrier?.("after_self_verification");
    const rawWire = canonicalBytes({ header, payload: input.payload, signature });
    let reparsed: unknown;
    try {
      reparsed = parseCanonicalJson(rawWire);
      validateSchema(`urn:open-android-intelligence:protocol:v1:envelope:${input.messageType}`, reparsed);
    } catch {
      return { kind: "rejected", error: "INTEGRITY_FAILED" };
    }
    const row = this.#cloneRow({
      kind, semanticDigest, messageType: input.messageType, messageId: input.messageId,
      sequence, space, issuedAt: input.issuedAt, expiresAt: input.expiresAt,
      envelopeDigest: sha256B64Url(rawWire), rawWire,
    });
    await this.#dependencies.barrier?.("after_retention");
    const commitKey = this.#dependencies.keyRecordFor?.(context) ?? this.#dependencies.keyRecord;
    if (commitKey.lifecycle !== "active" || commitKey.keyId !== key.keyId
      || commitKey.publicJwk.kid !== key.publicJwk.kid) {
      return { kind: "rejected", error: "AUTH_FAILED" };
    }
    await this.#dependencies.barrier?.("before_commit");
    const finalKey = this.#dependencies.keyRecordFor?.(context) ?? this.#dependencies.keyRecord;
    if (finalKey.lifecycle !== "active" || finalKey.keyId !== key.keyId
      || finalKey.publicJwk.kid !== key.publicJwk.kid) {
      return { kind: "rejected", error: "AUTH_FAILED" };
    }
    this.#next.set(spaceKey, reservedNext);
    this.#rows.set(rowKey, row);
    return { kind: "prepared", row };
  }

  async #prepare(
    kind: "ordinary" | "rotation",
    input: OutboundPreparationInput,
    context: AuthenticatedBindingContext,
  ) {
    return this.#withSerial(() => this.#prepareUnlocked(kind, input, context));
  }

  async prepareOrdinaryAtomically(input: OutboundPreparationInput & { readonly messageType: OrdinaryTask5MessageType }, context: AuthenticatedBindingContext) {
    const result = await this.#prepare("ordinary", input, context);
    return result.kind === "rejected" ? result : { kind: result.kind, envelope: this.#sendable(result.row) };
  }

  async prepareRotationAtomically(input: OutboundPreparationInput & { readonly messageType: RotationTask5MessageType }, context: AuthenticatedBindingContext) {
    const result = await this.#prepare("rotation", input, context);
    return result.kind === "rejected" ? result : { kind: result.kind, handle: this.#handle(result.row) };
  }

  async loadOrdinarySendable(space: ReplaySpace, messageId: string): Promise<SendableOutboundEnvelope | null> {
    const row = this.#rows.get(`${outboundSpaceKey(space)}\u0000${messageId}`);
    return row?.kind === "ordinary" ? this.#sendable(row) : null;
  }

  async loadRotationHandle(space: ReplaySpace, messageId: string): Promise<PreparedRotationHandle | null> {
    const row = this.#rows.get(`${outboundSpaceKey(space)}\u0000${messageId}`);
    return row?.kind === "rotation" ? this.#handle(row) : null;
  }

  ownsRotationHandle(handle: PreparedRotationHandle): boolean {
    return rotationHandleOwners.get(handle) === this.#token;
  }

  /** Returns only a defensive candidate copy for journal validation.  The
   * sendable capability is minted by commitRotationHandle after the journal
   * transaction has recorded its intent. */
  rotationEnvelopeBytes(handle: PreparedRotationHandle): Uint8Array | null {
    if (!this.ownsRotationHandle(handle)) return null;
    const row = this.#rows.get(`${outboundSpaceKey(handle.space)}\u0000${handle.messageId}`);
    return row?.kind === "rotation" && row.envelopeDigest === handle.envelopeDigest ? Uint8Array.from(row.rawWire) : null;
  }

  commitRotationHandle(handle: PreparedRotationHandle): SendableOutboundEnvelope | null {
    if (!this.ownsRotationHandle(handle)) return null;
    const row = this.#rows.get(`${outboundSpaceKey(handle.space)}\u0000${handle.messageId}`);
    return row?.kind === "rotation" && row.envelopeDigest === handle.envelopeDigest ? this.#sendable(row) : null;
  }
}

export interface TransportSender {
  send(envelope: SendableOutboundEnvelope): Promise<void>;
}
