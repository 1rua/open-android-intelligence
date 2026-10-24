import {
  BridgeServiceError,
  freezeRecord,
  type SmsRecordV1,
} from "./service-types.js";

export type { SmsRecordV1 } from "./service-types.js";

const MAX_U64 = 18_446_744_073_709_551_615n;
const MAX_SMS_PROVIDER_ID = 9_223_372_036_854_775_807n;
const MAX_SUBSCRIPTION_ID = 2_147_483_647;
const SMS_RECORD_ID = /^sms:[1-9][0-9]*$/;
const SMS_RECORD_KEYS = new Set([
  "recordId", "senderAddress", "threadId", "messageAtEpochMs", "observedAtEpochMs", "read",
  "subscriptionId", "body", "sourceEpoch", "cursorProviderId", "captureRevision", "policyRevision",
]);

const clone = (record: SmsRecordV1): SmsRecordV1 => freezeRecord({ ...record });

const isU64 = (value: unknown): value is bigint =>
  typeof value === "bigint" && value >= 0n && value <= MAX_U64;
const isSmsProviderId = (value: unknown): value is bigint =>
  typeof value === "bigint" && value > 0n && value <= MAX_SMS_PROVIDER_ID;
const isSmsRecordId = (value: unknown): value is string =>
  typeof value === "string" && SMS_RECORD_ID.test(value) && BigInt(value.slice(4)) <= MAX_SMS_PROVIDER_ID;

/** Runtime closed-record check shared by SMS inbox ingress and event egress. */
export const validateSmsRecord = (record: SmsRecordV1): void => {
  if (typeof record !== "object" || record === null || Array.isArray(record)) throw new BridgeServiceError("SMS_RECORD_INVALID");
  const keys = Object.keys(record);
  if (keys.length !== SMS_RECORD_KEYS.size || keys.some((key) => !SMS_RECORD_KEYS.has(key))) throw new BridgeServiceError("SMS_RECORD_INVALID");
  if (!isSmsRecordId(record.recordId)) throw new BridgeServiceError("SMS_RECORD_INVALID");
  if ((record.senderAddress !== null && typeof record.senderAddress !== "string")
    || (record.threadId !== null && typeof record.threadId !== "string")) throw new BridgeServiceError("SMS_RECORD_INVALID");
  if (![record.messageAtEpochMs, record.observedAtEpochMs, record.sourceEpoch, record.captureRevision, record.policyRevision].every(isU64)
    || !isSmsProviderId(record.cursorProviderId)) {
    throw new BridgeServiceError("SMS_RECORD_INVALID");
  }
  if (record.recordId !== `sms:${record.cursorProviderId}`) throw new BridgeServiceError("SMS_RECORD_INVALID");
  if (typeof record.read !== "boolean" || typeof record.body !== "string") throw new BridgeServiceError("SMS_RECORD_INVALID");
  if (record.subscriptionId !== null
    && (!Number.isSafeInteger(record.subscriptionId) || record.subscriptionId < 0 || record.subscriptionId > MAX_SUBSCRIPTION_ID)) {
    throw new BridgeServiceError("SMS_RECORD_INVALID");
  }
};

const compareCursor = (left: SmsRecordV1, right: SmsRecordV1): number => {
  if (left.messageAtEpochMs !== right.messageAtEpochMs) return left.messageAtEpochMs < right.messageAtEpochMs ? -1 : 1;
  if (left.cursorProviderId !== right.cursorProviderId) return left.cursorProviderId < right.cursorProviderId ? -1 : 1;
  return 0;
};

const recordDigest = (record: SmsRecordV1): string => JSON.stringify({
  recordId: record.recordId,
  senderAddress: record.senderAddress,
  threadId: record.threadId,
  messageAtEpochMs: record.messageAtEpochMs.toString(),
  observedAtEpochMs: record.observedAtEpochMs.toString(),
  read: record.read,
  subscriptionId: record.subscriptionId,
  body: record.body,
  sourceEpoch: record.sourceEpoch.toString(),
  cursorProviderId: record.cursorProviderId.toString(),
  captureRevision: record.captureRevision.toString(),
  policyRevision: record.policyRevision.toString(),
});

/** Process-local, capability-specific SMS ledger with a replaceable durable seam. */
export class SmsStore {
  readonly #records = new Map<string, Map<string, SmsRecordV1>>();
  readonly #positions = new Map<string, SmsRecordV1>();

  append(ownerKey: string, record: SmsRecordV1): boolean {
    validateSmsRecord(record);
    const records = this.#records.get(ownerKey) ?? new Map<string, SmsRecordV1>();
    const existing = records.get(record.recordId);
    if (existing) {
      if (recordDigest(existing) !== recordDigest(record)) throw new BridgeServiceError("SMS_RECORD_CONFLICT");
      return false;
    }
    const previous = this.#positions.get(ownerKey);
    if (previous && compareCursor(record, previous) <= 0) throw new BridgeServiceError("SMS_CURSOR_REPLAY");
    const retained = clone(record);
    records.set(record.recordId, retained);
    this.#records.set(ownerKey, records);
    this.#positions.set(ownerKey, retained);
    return true;
  }

  read(ownerKey: string, limit: number): readonly SmsRecordV1[] {
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 10_000) throw new BridgeServiceError("LIMIT_INVALID");
    return Object.freeze([...(this.#records.get(ownerKey)?.values() ?? [])]
      .sort(compareCursor)
      .slice(0, limit)
      .map(clone));
  }

  latest(ownerKey: string): SmsRecordV1 | null {
    const record = this.#positions.get(ownerKey);
    return record ? clone(record) : null;
  }
}
