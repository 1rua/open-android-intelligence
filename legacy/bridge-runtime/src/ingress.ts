import {
  BridgeServiceError,
  PairingService,
  identityKey,
  type BridgeIdentity,
} from "../../bridge-contract/src/index.js";

/**
 * The adapter is intentionally smaller than node's net/http listener API.
 * A production adapter is expected to be backed by the locked tsnet userspace
 * core.  There is no host, URL, route, proxy, or generic dial/listen argument
 * here, so callers cannot accidentally turn this port into a public listener.
 */
export type TailscaleUserspaceListener = Readonly<{
  bind(options: Readonly<{ port: number; bridgeFingerprint: string }>): Promise<{
    close(): Promise<void>;
  }>;
}>;

export type TsnetDependencyState = "locked" | "pending";

export type IngressControlFrame = BridgeIdentity & Readonly<{
  bridgeFingerprint: string;
  pairingGeneration: bigint;
  connectionGeneration: bigint;
  messageId: string;
  payloadDigest: string;
  payload: Uint8Array;
}>;

export type IngressReceipt = Readonly<{
  status: "accepted" | "duplicate";
  receipt: Uint8Array;
}>;

export type ReplayLookup = Readonly<{
  status: "new" | "duplicate" | "digest_mismatch";
  receipt?: Uint8Array;
}>;

export interface ReplayAdmission {
  lookup(frame: IngressControlFrame): ReplayLookup;
  remember(frame: IngressControlFrame, receipt: Uint8Array): void;
}

/** A deterministic in-process replay seam for tests and local fixtures. */
export class MemoryReplayAdmission implements ReplayAdmission {
  readonly #entries = new Map<string, { digest: string; receipt: Uint8Array }>();

  lookup(frame: IngressControlFrame): ReplayLookup {
    const key = replayKey(frame);
    const existing = this.#entries.get(key);
    if (!existing) return { status: "new" };
    if (existing.digest !== frame.payloadDigest) return { status: "digest_mismatch" };
    return { status: "duplicate", receipt: new Uint8Array(existing.receipt) };
  }

  remember(frame: IngressControlFrame, receipt: Uint8Array): void {
    const key = replayKey(frame);
    const existing = this.#entries.get(key);
    if (existing && existing.digest !== frame.payloadDigest) {
      throw new BridgeServiceError("INGRESS_REPLAY_DIGEST_MISMATCH");
    }
    if (!existing) this.#entries.set(key, { digest: frame.payloadDigest, receipt: new Uint8Array(receipt) });
  }
}

const replayKey = (frame: IngressControlFrame): string =>
  [identityKey(frame), frame.pairingGeneration.toString(10), frame.connectionGeneration.toString(10), frame.messageId].join("\u0000");

/** Monotonic connection-generation fence shared by all ingress channels. */
export class ConnectionGenerationFence {
  readonly #generations = new Map<string, bigint>();

  open(bindingKey: string, generation: bigint): void {
    if (typeof bindingKey !== "string" || bindingKey.length === 0) throw new BridgeServiceError("INGRESS_BINDING_KEY_INVALID");
    if (typeof generation !== "bigint" || generation <= 0n) throw new BridgeServiceError("INGRESS_GENERATION_INVALID");
    const current = this.#generations.get(bindingKey);
    if (current === undefined && generation !== 1n) throw new BridgeServiceError("INGRESS_GENERATION_GAP");
    if (current !== undefined && generation !== current + 1n && generation !== current) {
      throw new BridgeServiceError(generation < current ? "INGRESS_CONNECTION_FENCED" : "INGRESS_GENERATION_GAP");
    }
    this.#generations.set(bindingKey, generation);
  }

  assertCurrent(bindingKey: string, generation: bigint): void {
    if (this.#generations.get(bindingKey) !== generation) throw new BridgeServiceError("INGRESS_CONNECTION_FENCED");
  }

  current(bindingKey: string): bigint | null {
    return this.#generations.get(bindingKey) ?? null;
  }
}

export type IngressStatus = Readonly<{
  /** Public lifecycle label; `state` is retained as a descriptive alias. */
  status: "pending" | "started" | "stopped";
  state: "pending" | "started" | "stopped";
  port: number;
  reason?: string;
}>;

export type BridgeIngressOptions = Readonly<{
  tsnetDependency: TsnetDependencyState;
  listener?: TailscaleUserspaceListener;
  port?: number;
  fingerprint: string;
  pairing?: PairingService;
  generations?: ConnectionGenerationFence;
  replay?: ReplayAdmission;
}>;

export type IngressDispatch = (frame: IngressControlFrame) => Uint8Array | Promise<Uint8Array>;

/**
 * HTTP/control ingress lifecycle and authorization seam.
 *
 * This source deliberately does not claim a real network implementation:
 * without the controller's immutable `MVP-DEP-TSNET` lock it remains pending
 * and never calls the listener.  The same class can be wired to a reviewed
 * userspace adapter once that lock is accepted.
 */
export class BridgeIngress {
  readonly #options: Required<Pick<BridgeIngressOptions, "port" | "fingerprint" | "tsnetDependency">> & BridgeIngressOptions;
  readonly #pairing: PairingService | null;
  readonly #generations: ConnectionGenerationFence;
  readonly #replay: ReplayAdmission;
  #bound: { close(): Promise<void> } | null = null;
  #status: IngressStatus;

  constructor(options: BridgeIngressOptions) {
    if (!options || typeof options !== "object") throw new BridgeServiceError("INGRESS_OPTIONS_INVALID");
    const port = options.port ?? 443;
    if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) throw new BridgeServiceError("INGRESS_PORT_INVALID");
    if (typeof options.fingerprint !== "string" || options.fingerprint.length === 0) throw new BridgeServiceError("INGRESS_FINGERPRINT_INVALID");
    this.#options = { ...options, port, fingerprint: options.fingerprint, tsnetDependency: options.tsnetDependency };
    this.#pairing = options.pairing ?? null;
    this.#generations = options.generations ?? new ConnectionGenerationFence();
    this.#replay = options.replay ?? new MemoryReplayAdmission();
    this.#status = Object.freeze({ status: "pending", state: "pending", port, reason: "MVP-DEP-TSNET_PENDING" });
  }

  status(): IngressStatus {
    return this.#status;
  }

  async start(): Promise<IngressStatus> {
    if (this.#status.state === "started") return this.#status;
    if (this.#options.tsnetDependency !== "locked") {
      this.#status = Object.freeze({ status: "pending", state: "pending", port: this.#options.port, reason: "MVP-DEP-TSNET_PENDING" });
      return this.#status;
    }
    if (!this.#options.listener) {
      this.#status = Object.freeze({ status: "pending", state: "pending", port: this.#options.port, reason: "INGRESS_TSNET_ADAPTER_REQUIRED" });
      throw new BridgeServiceError("INGRESS_TSNET_ADAPTER_REQUIRED");
    }
    this.#bound = await this.#options.listener.bind({
      port: this.#options.port,
      bridgeFingerprint: this.#options.fingerprint,
    });
    this.#status = Object.freeze({ status: "started", state: "started", port: this.#options.port });
    return this.#status;
  }

  async stop(): Promise<void> {
    const bound = this.#bound;
    this.#bound = null;
    if (bound) await bound.close();
    this.#status = Object.freeze({ status: "stopped", state: "stopped", port: this.#options.port });
  }

  async handle(frame: IngressControlFrame, dispatch: IngressDispatch): Promise<IngressReceipt> {
    this.#assertFrame(frame);
    if (this.#status.state !== "started") throw new BridgeServiceError("INGRESS_NOT_READY");
    if (!this.#pairing) throw new BridgeServiceError("INGRESS_PAIRING_VERIFIER_REQUIRED");
    const current = this.#pairing.current(frame);
    if (!current || current.bridgeFingerprint !== frame.bridgeFingerprint || current.bridgeFingerprint !== this.#options.fingerprint) {
      throw new BridgeServiceError("INGRESS_FINGERPRINT_MISMATCH");
    }
    if (current.pairingGeneration !== frame.pairingGeneration) throw new BridgeServiceError("INGRESS_PAIRING_GENERATION_FENCED");
    this.#generations.assertCurrent(identityKey(frame), frame.connectionGeneration);

    const replay = this.#replay.lookup(frame);
    if (replay.status === "digest_mismatch") throw new BridgeServiceError("INGRESS_REPLAY_DIGEST_MISMATCH");
    if (replay.status === "duplicate") return { status: "duplicate", receipt: new Uint8Array(replay.receipt!) };
    if (typeof dispatch !== "function") throw new BridgeServiceError("INGRESS_DISPATCH_INVALID");
    const receipt = await dispatch({ ...frame, payload: new Uint8Array(frame.payload) });
    if (!(receipt instanceof Uint8Array)) throw new BridgeServiceError("INGRESS_RECEIPT_INVALID");
    this.#replay.remember(frame, receipt);
    return { status: "accepted", receipt: new Uint8Array(receipt) };
  }

  #assertFrame(frame: IngressControlFrame): void {
    if (!frame || typeof frame !== "object") throw new BridgeServiceError("INGRESS_FRAME_INVALID");
    for (const value of [frame.tenantId, frame.humanPrincipalId, frame.deviceId, frame.bridgeFingerprint, frame.messageId, frame.payloadDigest]) {
      if (typeof value !== "string" || value.length === 0) throw new BridgeServiceError("INGRESS_FRAME_INVALID");
    }
    if (typeof frame.pairingGeneration !== "bigint" || frame.pairingGeneration < 0n
      || typeof frame.connectionGeneration !== "bigint" || frame.connectionGeneration <= 0n
      || !(frame.payload instanceof Uint8Array)) throw new BridgeServiceError("INGRESS_FRAME_INVALID");
  }
}
