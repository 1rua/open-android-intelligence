import { createHash, createPublicKey, verify } from "node:crypto";
import { lstat, readFile } from "node:fs/promises";
import type { KeyObject } from "node:crypto";
import type { PairingTicket } from "../../bridge-contract/src/pairing-service.js";
import { BridgeServiceError } from "../../bridge-contract/src/service-types.js";
import { PAIRING_TICKET_VERIFIER_PORT, type PairingTicketVerifierPort } from "./production-ports.js";

export const PAIRING_TICKET_ENVELOPE = "open-android-intelligence.pairing-ticket/v1" as const;

export type LocalPairingTicketVerifierOptions = Readonly<{
  publicPath: string;
  clock?: () => number;
}>;

type Envelope = Readonly<{
  envelope: typeof PAIRING_TICKET_ENVELOPE;
  keyId: string;
  payload: string;
  signature: string;
}>;

const error = (code: string): BridgeServiceError => new BridgeServiceError(code);
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const decimal = (value: unknown): bigint => {
  if (typeof value !== "string" || !/^(?:0|[1-9][0-9]*)$/.test(value)) throw error("PAIRING_TICKET_INVALID");
  return BigInt(value);
};

const nonEmpty = (value: unknown): string => {
  if (typeof value !== "string" || value.length === 0) throw error("PAIRING_TICKET_INVALID");
  return value;
};

const canonical = (value: Record<string, unknown>): string => JSON.stringify({
  ticketId: value.ticketId,
  tenantId: value.tenantId,
  humanPrincipalId: value.humanPrincipalId,
  deviceId: value.deviceId,
  bridgeFingerprint: value.bridgeFingerprint,
  pairingGeneration: value.pairingGeneration,
  policyAttestationRevision: value.policyAttestationRevision,
  issuedAtMs: value.issuedAtMs,
  expiresAtMs: value.expiresAtMs,
});

const parseTicket = (payload: string, now: number): PairingTicket => {
  let decoded: unknown;
  try {
    const bytes = Buffer.from(payload, "base64url");
    if (bytes.byteLength === 0 || bytes.byteLength > 65_536) throw error("PAIRING_TICKET_INVALID");
    decoded = JSON.parse(bytes.toString("utf8"));
  } catch (caught) {
    if (caught instanceof BridgeServiceError) throw caught;
    throw error("PAIRING_TICKET_INVALID");
  }
  if (!isRecord(decoded) || Object.keys(decoded).length !== 9) throw error("PAIRING_TICKET_INVALID");
  const ticket: PairingTicket = Object.freeze({
    ticketId: nonEmpty(decoded.ticketId),
    tenantId: nonEmpty(decoded.tenantId),
    humanPrincipalId: nonEmpty(decoded.humanPrincipalId),
    deviceId: nonEmpty(decoded.deviceId),
    bridgeFingerprint: nonEmpty(decoded.bridgeFingerprint),
    pairingGeneration: decimal(decoded.pairingGeneration),
    policyAttestationRevision: decimal(decoded.policyAttestationRevision),
    issuedAtMs: nonEmptyNumber(decoded.issuedAtMs),
    expiresAtMs: nonEmptyNumber(decoded.expiresAtMs),
  });
  if (Buffer.from(canonical(decoded), "utf8").toString("base64url") !== payload) {
    throw error("PAIRING_TICKET_INVALID");
  }
  if (ticket.issuedAtMs > ticket.expiresAtMs || now < ticket.issuedAtMs || now >= ticket.expiresAtMs) {
    throw error("PAIRING_TICKET_EXPIRED");
  }
  return ticket;
};

const nonEmptyNumber = (value: unknown): number => {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw error("PAIRING_TICKET_INVALID");
  return value;
};

export class LocalPairingTicketVerifier implements PairingTicketVerifierPort {
  readonly port = PAIRING_TICKET_VERIFIER_PORT;
  readonly status = "connected" as const;
  readonly keyId: string;
  readonly #key: KeyObject;
  readonly #clock: () => number;

  private constructor(key: KeyObject, keyId: string, clock: () => number) {
    this.#key = key;
    this.keyId = keyId;
    this.#clock = clock;
  }

  static async open(options: LocalPairingTicketVerifierOptions): Promise<LocalPairingTicketVerifier> {
    if (!options || typeof options.publicPath !== "string" || options.publicPath.length === 0) {
      throw error("SECRET_STORE_PATH_INVALID");
    }
    const stat = await lstat(options.publicPath);
    if (!stat.isFile() || (stat.mode & 0o222) !== 0) {
      throw error(stat.isFile() ? "SECRET_STORE_PERMISSION_INVALID" : "SECRET_STORE_PATH_INVALID");
    }
    const key = createPublicKey(await readFile(options.publicPath, "utf8"));
    if (key.asymmetricKeyType !== "ed25519") throw error("SECRET_STORE_KEY_INVALID");
    const der = key.export({ format: "der", type: "spki" });
    if (!(der instanceof Uint8Array)) throw error("SECRET_STORE_KEY_INVALID");
    const keyId = `sha256:${createHash("sha256").update(der).digest("hex")}`;
    return new LocalPairingTicketVerifier(key, keyId, options.clock ?? (() => Date.now()));
  }

  async verify(candidate: unknown): Promise<PairingTicket> {
    if (!isRecord(candidate) || Object.keys(candidate).length !== 4
      || candidate.envelope !== PAIRING_TICKET_ENVELOPE
      || typeof candidate.keyId !== "string" || candidate.keyId !== this.keyId
      || typeof candidate.payload !== "string" || typeof candidate.signature !== "string") {
      throw error("PAIRING_TICKET_INVALID");
    }
    const envelope = candidate as Envelope;
    const signed = Buffer.from(`${PAIRING_TICKET_ENVELOPE}\n${this.keyId}\n${envelope.payload}`, "utf8");
    const signature = Buffer.from(envelope.signature, "base64url");
    if (!verify(null, signed, this.#key, signature)) throw error("PAIRING_TICKET_TAMPERED");
    return parseTicket(envelope.payload, this.#clock());
  }
}

export const openLocalPairingTicketVerifier = LocalPairingTicketVerifier.open;
