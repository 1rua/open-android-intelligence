import { canonicalBytes } from "./encoding.js";

/** Logical Task 7 security-ledger accounting. Physical DB overhead is excluded. */
export const SECURITY_LEDGER_LIMITS = Object.freeze({
  maxRows: 16_384n,
  inboundRawWireBytes: 262_144n,
  receiptReservationBytes: 262_144n,
  intentMetadataBytes: 65_536n,
  tombstoneBytes: 2_048n,
  maxChargedBytes: 9_663_676_416n,
} as const);

export const SecurityQuotaError = Object.freeze({
  RAW_WIRE_TOO_LARGE: "RAW_WIRE_TOO_LARGE",
  RECEIPT_TOO_LARGE: "RECEIPT_TOO_LARGE",
  INTENT_METADATA_TOO_LARGE: "INTENT_METADATA_TOO_LARGE",
  TOMBSTONE_TOO_LARGE: "TOMBSTONE_TOO_LARGE",
  ROWS_FULL: "SECURITY_LEDGER_FULL",
  BYTES_FULL: "SECURITY_LEDGER_FULL",
  INVALID_CHARGE: "INVALID_SECURITY_CHARGE",
} as const);

export type SecurityRowPhase = "pending" | "abandoned" | "finalized" | "tombstone";

export type SecurityQuotaState = Readonly<{
  rows: bigint;
  chargedBytes: bigint;
}>;

export type SecurityTombstone = Readonly<{
  envelope_digest: string;
  message_id: string;
  message_type: string;
  sequence: string;
  space: Readonly<Record<string, unknown>>;
  status: "compacted";
}>;

const isNonNegative = (value: bigint): boolean => typeof value === "bigint" && value >= 0n;
const isPositive = (value: bigint): boolean => typeof value === "bigint" && value > 0n;
const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};

export const chargeSecurityRow = (input: Readonly<{
  phase: SecurityRowPhase;
  rawWireBytes?: bigint;
  receiptBytes?: bigint;
  intentMetadataBytes?: bigint;
  tombstoneBytes?: bigint;
}>): bigint => {
  const raw = input.rawWireBytes ?? 0n;
  const metadata = input.intentMetadataBytes ?? 0n;
  if (input.phase !== "tombstone" && (!isPositive(raw) || raw > SECURITY_LEDGER_LIMITS.inboundRawWireBytes)) {
    throw new Error(raw > SECURITY_LEDGER_LIMITS.inboundRawWireBytes ? SecurityQuotaError.RAW_WIRE_TOO_LARGE : SecurityQuotaError.INVALID_CHARGE);
  }
  if (!isNonNegative(metadata) || metadata > SECURITY_LEDGER_LIMITS.intentMetadataBytes) throw new Error(SecurityQuotaError.INTENT_METADATA_TOO_LARGE);
  if (input.phase === "pending" || input.phase === "abandoned") {
    if (input.receiptBytes !== undefined) throw new Error(SecurityQuotaError.INVALID_CHARGE);
    return raw + SECURITY_LEDGER_LIMITS.receiptReservationBytes + metadata;
  }
  if (input.phase === "finalized") {
    const receipt = input.receiptBytes ?? 0n;
    if (!isPositive(receipt) || receipt > SECURITY_LEDGER_LIMITS.receiptReservationBytes) throw new Error(receipt > SECURITY_LEDGER_LIMITS.receiptReservationBytes ? SecurityQuotaError.RECEIPT_TOO_LARGE : SecurityQuotaError.INVALID_CHARGE);
    return raw + receipt + metadata;
  }
  const tombstone = input.tombstoneBytes ?? 0n;
  if (!isPositive(tombstone) || tombstone > SECURITY_LEDGER_LIMITS.tombstoneBytes) throw new Error(tombstone > SECURITY_LEDGER_LIMITS.tombstoneBytes ? SecurityQuotaError.TOMBSTONE_TOO_LARGE : SecurityQuotaError.INVALID_CHARGE);
  if (raw !== 0n || metadata !== 0n || input.receiptBytes !== undefined) throw new Error(SecurityQuotaError.INVALID_CHARGE);
  return tombstone;
};

export const admitSecurityCharge = (state: SecurityQuotaState, charge: bigint): SecurityQuotaState => {
  if (!isNonNegative(state.rows) || !isNonNegative(state.chargedBytes) || !isNonNegative(charge)) throw new Error(SecurityQuotaError.INVALID_CHARGE);
  if (state.rows >= SECURITY_LEDGER_LIMITS.maxRows) throw new Error(SecurityQuotaError.ROWS_FULL);
  if (state.chargedBytes > SECURITY_LEDGER_LIMITS.maxChargedBytes - charge) throw new Error(SecurityQuotaError.BYTES_FULL);
  return Object.freeze({ rows: state.rows + 1n, chargedBytes: state.chargedBytes + charge });
};

export const createSecurityTombstone = (input: Readonly<{
  envelope_digest: string;
  message_id: string;
  message_type: string;
  sequence: string;
  space: Readonly<Record<string, unknown>>;
}>): SecurityTombstone => {
  if (!input.envelope_digest || !input.message_id || !input.message_type || !/^(0|[1-9][0-9]*)$/.test(input.sequence)
    || !isRecord(input.space) || Object.keys(input.space).length === 0) throw new Error("INVALID_SECURITY_TOMBSTONE");
  const tombstone: SecurityTombstone = Object.freeze({
    envelope_digest: input.envelope_digest,
    message_id: input.message_id,
    message_type: input.message_type,
    sequence: input.sequence,
    space: Object.freeze({ ...input.space }),
    status: "compacted",
  });
  const length = securityTombstoneByteLength(tombstone);
  if (length > SECURITY_LEDGER_LIMITS.tombstoneBytes) throw new Error(SecurityQuotaError.TOMBSTONE_TOO_LARGE);
  return tombstone;
};

export const securityTombstoneByteLength = (tombstone: SecurityTombstone): bigint => {
  if (!isRecord(tombstone) || !exactKeys(tombstone, ["envelope_digest", "message_id", "message_type", "sequence", "space", "status"]) || tombstone.status !== "compacted") throw new Error("INVALID_SECURITY_TOMBSTONE");
  return BigInt(canonicalBytes(tombstone).byteLength);
};
