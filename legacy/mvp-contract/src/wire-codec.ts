/**
 * Closed MVP wire codec.
 *
 * Bridge/Android internals use ergonomic camel-case value objects. This module
 * is the only conversion seam to the versioned snake-case JSON contracts. It
 * deliberately rejects unknown fields before a payload crosses the boundary;
 * identity and authorization facts are never encoded into these records.
 */

import { Ajv2020 } from "ajv/dist/2020.js";

const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const MAX_U64 = 18_446_744_073_709_551_615n;
const MAX_SMS_PROVIDER_ID = 9_223_372_036_854_775_807n;
const DECIMAL_U64 = /^(0|[1-9][0-9]*)$/;
const isDecimalU64 = (value: unknown): value is string =>
  typeof value === "string" && value.length <= 20 && DECIMAL_U64.test(value) && BigInt(value) <= MAX_U64;
const isPositiveU64 = (value: unknown): value is string =>
  isDecimalU64(value) && value !== "0";
const isPositiveSmsProviderId = (value: unknown): value is string =>
  isPositiveU64(value) && BigInt(value) <= MAX_SMS_PROVIDER_ID;
const SHA256 = /^[A-Fa-f0-9]{64}$/;
const MEDIA_TYPES = new Set(["image/jpeg", "image/png", "image/webp", "application/pdf", "text/plain"]);
const AUDIO_MAX_BYTES = 10 * 1024 * 1024;
const AUDIO_MAX_DURATION_MS = 120000;
const ASSISTANT_MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024;
const ARTIFACT_ID = /^[A-Za-z0-9._~-]{1,128}$/;

type RuntimeNotificationRecord = Readonly<{
  kind: "upsert" | "delete_tombstone" | "loss_marker";
  recordId: string;
  packageId: string | null;
  title: string | null;
  content: string | null;
  sourceEpoch: bigint;
  cursor: bigint;
  captureRevision: bigint;
}>;

type NotificationWireOptions = Readonly<{
  capturedAtEpochMs?: bigint;
  recordRevision?: bigint;
  appLabel?: string | null;
  channelId?: string | null;
  postedAtEpochMs?: bigint;
  loss?: Readonly<{ lostFromCursor: bigint; lostToCursor: bigint; reason: string }>;
}>;

export type WireNotificationRecord = Readonly<{
  kind: RuntimeNotificationRecord["kind"];
  source_epoch: string;
  occurrence_id: string;
  record_key: string;
  record_revision: string;
  cursor: string;
  captured_at_epoch_ms: string;
  capture_revision: string;
  metadata: Readonly<{ package_name: string; app_label: string | null; channel_id: string | null; posted_at_epoch_ms: string }> | null;
  content: Readonly<{ title: string | null; body: string | null }> | null;
  loss: Readonly<{ lost_from_cursor: string; lost_to_cursor: string; reason: string }> | null;
}>;

export type RuntimeAssistantAttachment =
  | Readonly<{ kind: "image" | "file"; artifactId: string; filename: string; mimeType: string; sizeBytes: number; sha256: string }>
  | Readonly<{ kind: "audio"; artifactId: string; filename: string; mimeType: "audio/mp4"; sizeBytes: number; sha256: string; durationMs: number }>;

export type WireAssistantMessage = Readonly<Record<string, unknown>>;

const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};

const recordObject = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const stringValue = (value: unknown): value is string => typeof value === "string";
const compareCodePoints = (left: string, right: string): number => {
  const a = [...left].map((value) => value.codePointAt(0) ?? 0);
  const b = [...right].map((value) => value.codePointAt(0) ?? 0);
  for (let index = 0; index < Math.min(a.length, b.length); index += 1) if (a[index] !== b[index]) return a[index]! - b[index]!;
  return a.length - b.length;
};
const bigintString = (value: bigint, positive = false): string => {
  if (value < 0n || value > MAX_U64 || (positive && value === 0n)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  return value.toString(10);
};

export const encodeNotificationRecord = (
  record: RuntimeNotificationRecord,
  options: NotificationWireOptions = {},
): WireNotificationRecord => {
  if (record.kind === "loss_marker") {
    const loss = options.loss;
    if (!loss || typeof loss.reason !== "string" || loss.reason.length === 0) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
    const encodedLoss = {
      lost_from_cursor: bigintString(loss.lostFromCursor),
      lost_to_cursor: bigintString(loss.lostToCursor),
      reason: loss.reason,
    };
    const wire = Object.freeze({
      kind: record.kind,
      source_epoch: bigintString(record.sourceEpoch),
      occurrence_id: record.recordId,
      record_key: record.recordId,
      record_revision: bigintString(options.recordRevision ?? (record.captureRevision || 1n), true),
      cursor: bigintString(record.cursor, true),
      captured_at_epoch_ms: bigintString(options.capturedAtEpochMs ?? 0n),
      capture_revision: bigintString(record.captureRevision),
      metadata: null,
      content: null,
      loss: encodedLoss,
    });
    if (!validateWireNotificationRecord(wire)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
    return wire;
  }
  if (!record.packageId || !PACKAGE_NAME.test(record.packageId)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  if (record.kind === "delete_tombstone" && (record.title !== null || record.content !== null)) {
    throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  }
  const metadata = {
    package_name: record.packageId,
    app_label: options.appLabel ?? null,
    channel_id: options.channelId ?? null,
    posted_at_epoch_ms: bigintString(options.postedAtEpochMs ?? options.capturedAtEpochMs ?? 0n),
  };
  const wire = Object.freeze({
    kind: record.kind,
    source_epoch: bigintString(record.sourceEpoch),
    occurrence_id: record.recordId,
    record_key: record.recordId,
    record_revision: bigintString(options.recordRevision ?? (record.captureRevision || 1n), true),
    cursor: bigintString(record.cursor, true),
    captured_at_epoch_ms: bigintString(options.capturedAtEpochMs ?? 0n),
    capture_revision: bigintString(record.captureRevision),
    metadata,
    content: record.kind === "upsert" && (record.title !== null || record.content !== null)
      ? { title: record.title, body: record.content }
      : null,
    loss: null,
  });
  if (!validateWireNotificationRecord(wire)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  return wire;
};

const validMetadata = (value: unknown): boolean => recordObject(value)
  && exactKeys(value, ["package_name", "app_label", "channel_id", "posted_at_epoch_ms"])
  && stringValue(value.package_name) && PACKAGE_NAME.test(value.package_name)
  && (value.app_label === null || stringValue(value.app_label))
  && (value.channel_id === null || stringValue(value.channel_id))
  && isDecimalU64(value.posted_at_epoch_ms);

const validContent = (value: unknown): boolean => recordObject(value)
  && exactKeys(value, ["title", "body"])
  && (value.title === null || stringValue(value.title))
  && (value.body === null || stringValue(value.body));

const validLoss = (value: unknown): boolean => recordObject(value)
  && exactKeys(value, ["lost_from_cursor", "lost_to_cursor", "reason"])
  && isPositiveU64(value.lost_from_cursor)
  && isPositiveU64(value.lost_to_cursor)
  && stringValue(value.reason) && value.reason.length > 0;

export const validateWireNotificationRecord = (value: unknown): value is WireNotificationRecord => {
  if (!recordObject(value) || !exactKeys(value, ["kind", "source_epoch", "occurrence_id", "record_key", "record_revision", "cursor", "captured_at_epoch_ms", "capture_revision", "metadata", "content", "loss"])) return false;
  if (!(value.kind === "upsert" || value.kind === "delete_tombstone" || value.kind === "loss_marker")) return false;
  if (!isDecimalU64(value.source_epoch)
    || !stringValue(value.occurrence_id) || value.occurrence_id.length === 0
    || !stringValue(value.record_key) || value.record_key.length === 0
    || !isPositiveU64(value.record_revision)
    || !isPositiveU64(value.cursor)
    || !isDecimalU64(value.captured_at_epoch_ms)
    || !isDecimalU64(value.capture_revision)) return false;
  if (value.kind === "loss_marker") return value.metadata === null && value.content === null && validLoss(value.loss);
  return validMetadata(value.metadata) && (value.content === null || validContent(value.content)) && value.loss === null
    && (value.kind === "upsert" || value.content === null);
};

type NotificationFilterWireInput = Readonly<{ packages?: readonly string[]; content?: "metadata" | "content" }>;

const validPackages = (value: unknown): value is readonly string[] => Array.isArray(value)
  && value.length > 0
  && value.every((item) => stringValue(item) && PACKAGE_NAME.test(item))
  && new Set(value).size === value.length
  && value.every((item, index, all) => index === 0 || compareCodePoints(all[index - 1] as string, item) < 0);

const filterFields = (input: NotificationFilterWireInput): Record<string, unknown> => ({
  ...(input.packages === undefined ? {} : { packages: Object.freeze([...input.packages]) }),
  ...(input.content === undefined ? {} : { content: input.content }),
});

export const encodeNotificationQuery = (input: Readonly<{
  operationId: string;
  mode: "on_demand" | "auto_send";
  policyRevision: bigint;
  limit: number;
} & NotificationFilterWireInput>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.notifications.query", mode: input.mode, operation_id: input.operationId,
    policy_revision: bigintString(input.policyRevision), limit: input.limit, ...filterFields(input),
  });
  if (!validateWireNotificationOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const encodeNotificationSubscribe = (input: Readonly<{
  subscriptionId: string;
  policyRevision: bigint;
} & NotificationFilterWireInput>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.notifications.subscribe", subscription_id: input.subscriptionId,
    policy_revision: bigintString(input.policyRevision), ...filterFields(input),
  });
  if (!validateWireNotificationOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const encodeNotificationUnsubscribe = (input: Readonly<{ subscriptionId: string; policyRevision: bigint }>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.notifications.unsubscribe", subscription_id: input.subscriptionId,
    policy_revision: bigintString(input.policyRevision),
  });
  if (!validateWireNotificationOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const validateWireNotificationOperation = (value: unknown): boolean => {
  if (!recordObject(value) || typeof value.operation !== "string") return false;
  const commonFilter = (keys: readonly string[]): boolean => {
    if (!exactKeys(value, keys)) return false;
    if (!isDecimalU64(value.policy_revision)) return false;
    if (Object.hasOwn(value, "packages") && !validPackages(value.packages)) return false;
    if (Object.hasOwn(value, "content") && value.content !== "metadata" && value.content !== "content") return false;
    return true;
  };
  if (value.operation === "mobile.notifications.query") {
    return commonFilter(["operation", "mode", "operation_id", "policy_revision", "limit", ...(Object.hasOwn(value, "packages") ? ["packages"] : []), ...(Object.hasOwn(value, "content") ? ["content"] : [])])
      && (value.mode === "on_demand" || value.mode === "auto_send")
      && stringValue(value.operation_id) && value.operation_id.length > 0
      && typeof value.limit === "number" && Number.isSafeInteger(value.limit) && value.limit >= 1 && value.limit <= 100;
  }
  if (value.operation === "mobile.notifications.subscribe") {
    return commonFilter(["operation", "subscription_id", "policy_revision", ...(Object.hasOwn(value, "packages") ? ["packages"] : []), ...(Object.hasOwn(value, "content") ? ["content"] : [])])
      && stringValue(value.subscription_id) && value.subscription_id.length > 0;
  }
  if (value.operation === "mobile.notifications.unsubscribe") {
    return exactKeys(value, ["operation", "subscription_id", "policy_revision"])
      && stringValue(value.subscription_id) && value.subscription_id.length > 0
      && isDecimalU64(value.policy_revision);
  }
  return false;
};

type RuntimeSmsRecord = Readonly<{
  recordId: string;
  sourceEpoch: bigint;
  recordRevision: bigint;
  cursorMessageAtEpochMs: bigint;
  cursorProviderId: bigint;
  capturedAtEpochMs: bigint;
  captureRevision: bigint;
  policyRevision: bigint;
  senderAddress: string | null;
  threadId: string | null;
  messageAtEpochMs: bigint;
  observedAtEpochMs: bigint;
  read: boolean;
  subscriptionId: number | null;
  body: string;
}>;

export type WireSmsRecord = Readonly<{
  kind: "upsert";
  record_id: string;
  source_epoch: string;
  record_revision: string;
  cursor_message_at_epoch_ms: string;
  cursor_provider_id: string;
  captured_at_epoch_ms: string;
  capture_revision: string;
  policy_revision: string;
  metadata: Readonly<{
    sender_address: string | null;
    thread_id: string | null;
    message_at_epoch_ms: string;
    observed_at_epoch_ms: string;
    read: boolean;
    subscription_id: number | null;
  }>;
  content: Readonly<{ body: string }>;
}>;

const validSmsSubscriptionId = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= 0 && value <= 2_147_483_647;

const validSmsRecordId = (value: unknown): value is string =>
  typeof value === "string" && value.startsWith("sms:") && isPositiveSmsProviderId(value.slice(4));

const validSmsMetadata = (value: unknown): boolean => recordObject(value)
  && exactKeys(value, ["sender_address", "thread_id", "message_at_epoch_ms", "observed_at_epoch_ms", "read", "subscription_id"])
  && (value.sender_address === null || stringValue(value.sender_address))
  && (value.thread_id === null || stringValue(value.thread_id))
  && isDecimalU64(value.message_at_epoch_ms)
  && isDecimalU64(value.observed_at_epoch_ms)
  && typeof value.read === "boolean"
  && (value.subscription_id === null || validSmsSubscriptionId(value.subscription_id));

const validSmsContent = (value: unknown): boolean => recordObject(value)
  && exactKeys(value, ["body"])
  && stringValue(value.body);

export const validateWireSmsRecord = (value: unknown): value is WireSmsRecord => recordObject(value)
  && exactKeys(value, [
    "kind", "record_id", "source_epoch", "record_revision", "cursor_message_at_epoch_ms", "cursor_provider_id",
    "captured_at_epoch_ms", "capture_revision", "policy_revision", "metadata", "content",
  ])
  && value.kind === "upsert"
  && validSmsRecordId(value.record_id)
  && isDecimalU64(value.source_epoch)
  && isPositiveU64(value.record_revision)
  && isDecimalU64(value.cursor_message_at_epoch_ms)
  && isPositiveSmsProviderId(value.cursor_provider_id)
  && isDecimalU64(value.captured_at_epoch_ms)
  && isDecimalU64(value.capture_revision)
  && isDecimalU64(value.policy_revision)
  && validSmsMetadata(value.metadata)
  && recordObject(value.metadata)
  && value.record_id === `sms:${value.cursor_provider_id}`
  && value.cursor_message_at_epoch_ms === value.metadata.message_at_epoch_ms
  && validSmsContent(value.content);

export const encodeSmsRecord = (record: RuntimeSmsRecord): WireSmsRecord => {
  const metadata = Object.freeze({
    sender_address: record.senderAddress,
    thread_id: record.threadId,
    message_at_epoch_ms: bigintString(record.messageAtEpochMs),
    observed_at_epoch_ms: bigintString(record.observedAtEpochMs),
    read: record.read,
    subscription_id: record.subscriptionId,
  });
  const content = Object.freeze({ body: record.body });
  const wire = Object.freeze({
    kind: "upsert" as const,
    record_id: record.recordId,
    source_epoch: bigintString(record.sourceEpoch),
    record_revision: bigintString(record.recordRevision, true),
    cursor_message_at_epoch_ms: bigintString(record.cursorMessageAtEpochMs),
    cursor_provider_id: bigintString(record.cursorProviderId),
    captured_at_epoch_ms: bigintString(record.capturedAtEpochMs),
    capture_revision: bigintString(record.captureRevision),
    policy_revision: bigintString(record.policyRevision),
    metadata,
    content,
  });
  if (!validateWireSmsRecord(wire)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  return wire;
};

export const encodeSmsQuery = (input: Readonly<{ operationId: string; policyRevision: bigint; limit: number }>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.sms.query", operation_id: input.operationId,
    policy_revision: bigintString(input.policyRevision), limit: input.limit,
  });
  if (!validateWireSmsOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const encodeSmsSubscribe = (input: Readonly<{ subscriptionId: string; policyRevision: bigint }>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.sms.subscribe", subscription_id: input.subscriptionId,
    policy_revision: bigintString(input.policyRevision),
  });
  if (!validateWireSmsOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const encodeSmsUnsubscribe = (input: Readonly<{ subscriptionId: string; policyRevision: bigint }>): WireAssistantMessage => {
  const wire = Object.freeze({
    operation: "mobile.sms.unsubscribe", subscription_id: input.subscriptionId,
    policy_revision: bigintString(input.policyRevision),
  });
  if (!validateWireSmsOperation(wire)) throw new Error("WIRE_OPERATION_UNREPRESENTABLE");
  return wire;
};

export const validateWireSmsOperation = (value: unknown): boolean => {
  if (!recordObject(value) || typeof value.operation !== "string") return false;
  if (value.operation === "mobile.sms.query") {
    return exactKeys(value, ["operation", "operation_id", "policy_revision", "limit"])
      && stringValue(value.operation_id) && value.operation_id.length > 0
      && isDecimalU64(value.policy_revision)
      && typeof value.limit === "number" && Number.isSafeInteger(value.limit) && value.limit >= 1 && value.limit <= 10_000;
  }
  if (value.operation === "mobile.sms.subscribe" || value.operation === "mobile.sms.unsubscribe") {
    return exactKeys(value, ["operation", "subscription_id", "policy_revision"])
      && stringValue(value.subscription_id) && value.subscription_id.length > 0
      && isDecimalU64(value.policy_revision);
  }
  return false;
};

export const encodeAssistantRequest = (input: Readonly<{ operationId: string; text: string; attachments: readonly RuntimeAssistantAttachment[] }>): WireAssistantMessage => {
  const wire = Object.freeze({
    kind: "request",
    operation_id: input.operationId,
    text: input.text,
    attachments: Object.freeze(input.attachments.map((attachment) => Object.freeze({
      kind: attachment.kind,
      artifact_id: attachment.artifactId,
      media_type: attachment.mimeType,
      byte_length: attachment.sizeBytes,
      sha256: attachment.sha256.toLowerCase(),
      display_name: attachment.filename,
      ...(attachment.kind === "audio" ? { duration_ms: attachment.durationMs } : {}),
    }))),
  });
  if (!validateWireAssistantMessage(wire)) throw new Error("WIRE_ASSISTANT_UNREPRESENTABLE");
  return wire;
};

export const encodeAssistantResponse = (input: Readonly<{ operationId: string; reply: string; error?: string | null }>): WireAssistantMessage => {
  const wire = Object.freeze({
    kind: "response", operation_id: input.operationId, text: input.reply, status: input.error ? "failed" : "complete", ...(input.error === undefined ? {} : { error: input.error }),
  });
  if (!validateWireAssistantMessage(wire)) throw new Error("WIRE_ASSISTANT_UNREPRESENTABLE");
  return wire;
};

export const encodeAssistantEvent = (input: Readonly<{
  operationId: string;
  messageId: string;
  sequence: bigint;
  event: "delta" | "complete" | "failed";
  text: string;
  error?: string;
}>): WireAssistantMessage => {
  const wire = Object.freeze({
    kind: "event", operation_id: input.operationId, message_id: input.messageId,
    sequence: bigintString(input.sequence, true), event: input.event, text: input.text,
    ...(input.error === undefined ? {} : { error: input.error }),
  });
  if (!validateWireAssistantMessage(wire)) throw new Error("WIRE_ASSISTANT_UNREPRESENTABLE");
  return wire;
};

const validWireAttachment = (value: unknown): boolean => recordObject(value)
  && stringValue(value.artifact_id) && ARTIFACT_ID.test(value.artifact_id)
  && ((exactKeys(value, ["kind", "artifact_id", "media_type", "byte_length", "sha256", "display_name"])
    && (value.kind === "image" || value.kind === "file")
    && stringValue(value.media_type) && MEDIA_TYPES.has(value.media_type)
    && (value.kind !== "image" || value.media_type.startsWith("image/"))
    && (value.kind !== "file" || !value.media_type.startsWith("image/")))
    || (exactKeys(value, ["kind", "artifact_id", "media_type", "byte_length", "sha256", "display_name", "duration_ms"])
      && value.kind === "audio" && value.media_type === "audio/mp4"
      && Number.isSafeInteger(value.duration_ms) && (value.duration_ms as number) >= 1 && (value.duration_ms as number) <= AUDIO_MAX_DURATION_MS))
  && Number.isSafeInteger(value.byte_length) && (value.byte_length as number) >= 0 && (value.byte_length as number) <= 26_214_400
  && (value.kind !== "audio" || (value.byte_length as number) <= AUDIO_MAX_BYTES)
  && stringValue(value.sha256) && SHA256.test(value.sha256)
  && stringValue(value.display_name) && value.display_name.length > 0 && value.display_name.length <= 255
  && !value.display_name.includes("/") && !value.display_name.includes("\\")
;

export const validateWireAssistantMessage = (value: unknown): value is WireAssistantMessage => {
  if (!recordObject(value) || typeof value.kind !== "string") return false;
  if (value.kind === "request") return exactKeys(value, ["kind", "operation_id", "text", "attachments"])
    && stringValue(value.operation_id) && value.operation_id.length > 0
    && stringValue(value.text) && value.text.length <= 50_000
    && Array.isArray(value.attachments) && value.attachments.length <= 4
    && value.attachments.every(validWireAttachment)
    && value.attachments.reduce((total, attachment) => total + (attachment as Record<string, unknown>).byte_length as number, 0) <= ASSISTANT_MAX_ATTACHMENT_BYTES;
  if (value.kind === "response") return (exactKeys(value, ["kind", "operation_id", "text", "status"]) || exactKeys(value, ["kind", "operation_id", "text", "status", "error"]))
    && stringValue(value.operation_id) && value.operation_id.length > 0
    && stringValue(value.text) && value.text.length <= 50_000
    && (value.status === "complete" || value.status === "failed")
    && (!Object.hasOwn(value, "error") || value.error === null || stringValue(value.error));
  if (value.kind === "event") return (exactKeys(value, ["kind", "operation_id", "message_id", "sequence", "event", "text"])
    || exactKeys(value, ["kind", "operation_id", "message_id", "sequence", "event", "text", "error"]))
    && stringValue(value.operation_id) && value.operation_id.length > 0
    && stringValue(value.message_id) && value.message_id.length > 0
    && isPositiveU64(value.sequence)
    && (value.event === "delta" || value.event === "complete" || value.event === "failed")
    && stringValue(value.text) && value.text.length <= 50_000
    && (value.event === "failed" ? stringValue(value.error) && value.error.length > 0 : !Object.hasOwn(value, "error"));
  return false;
};

export type WireCallRecord = Readonly<{
  kind: "upsert";
  record_id: string;
  source_epoch: string;
  record_revision: "1";
  cursor_started_at_epoch_ms: string;
  cursor_provider_id: string;
  captured_at_epoch_ms: string;
  capture_revision: string;
  policy_revision: string;
  metadata: Readonly<{
    direction: "incoming" | "outgoing" | "missed" | "rejected";
    started_at_epoch_ms: string;
    ended_at_epoch_ms: string;
    duration_seconds: string;
    observed_at_epoch_ms: string;
    number_presentation: "allowed" | "restricted" | "unknown" | "payphone" | "unavailable";
  }>;
  counterparty_number: Readonly<{ state: "withheld" }> | Readonly<{ state: "released"; value: string }>;
}>;

const MAX_I64 = 9_223_372_036_854_775_807n;
const CALL_DIRECTIONS = new Set(["incoming", "outgoing", "missed", "rejected"]);
const CALL_PRESENTATIONS = new Set(["allowed", "restricted", "unknown", "payphone", "unavailable"]);
const validCallProviderId = (value: unknown): value is string => isPositiveU64(value) && BigInt(value) <= MAX_I64;
const validNonNegativeI64 = (value: unknown): value is string => isDecimalU64(value) && BigInt(value) <= MAX_I64;
const validUnicodeScalars = (value: string): boolean => {
  for (let index = 0; index < value.length; index += 1) {
    const unit = value.charCodeAt(index);
    if (unit >= 0xD800 && unit <= 0xDBFF) {
      const low = value.charCodeAt(index + 1);
      if (!Number.isInteger(low) || low < 0xDC00 || low > 0xDFFF) return false;
      index += 1;
    } else if (unit >= 0xDC00 && unit <= 0xDFFF) return false;
  }
  return true;
};

/**
 * Compiles a published call-record schema with its registered executable
 * UTF-8-byte vocabulary. Generic JSON Schema validators do not interpret this
 * contract extension, so callers must use this factory at the schema boundary.
 */
export const createCallRecordSchemaValidator = (schema: object) => {
  const ajv = new Ajv2020({ strict: false });
  ajv.addKeyword({
    keyword: "x-open-android-intelligence-maxUtf8Bytes",
    type: "string",
    schemaType: "number",
    validate: (limit: unknown, value: unknown): boolean =>
      typeof limit === "number" && Number.isSafeInteger(limit) && limit >= 0
      && typeof value === "string" && validUnicodeScalars(value)
      && new TextEncoder().encode(value).byteLength <= limit,
  });
  return ajv.compile(schema);
};
const validCallRecordId = (value: unknown): value is string =>
  typeof value === "string" && value.startsWith("call:") && validCallProviderId(value.slice(5));
const validCallMetadata = (value: unknown): value is WireCallRecord["metadata"] => recordObject(value)
  && exactKeys(value, ["direction", "started_at_epoch_ms", "ended_at_epoch_ms", "duration_seconds", "observed_at_epoch_ms", "number_presentation"])
  && stringValue(value.direction) && CALL_DIRECTIONS.has(value.direction)
  && validNonNegativeI64(value.started_at_epoch_ms) && validNonNegativeI64(value.ended_at_epoch_ms)
  && validNonNegativeI64(value.duration_seconds) && validNonNegativeI64(value.observed_at_epoch_ms)
  && stringValue(value.number_presentation) && CALL_PRESENTATIONS.has(value.number_presentation)
  && BigInt(value.ended_at_epoch_ms) === BigInt(value.started_at_epoch_ms) + BigInt(value.duration_seconds) * 1000n;
const validCallCounterparty = (value: unknown, presentation: unknown): value is WireCallRecord["counterparty_number"] => {
  if (!recordObject(value)) return false;
  if (value.state === "withheld") return exactKeys(value, ["state"]);
  return exactKeys(value, ["state", "value"])
    && value.state === "released" && presentation === "allowed"
    && stringValue(value.value) && validUnicodeScalars(value.value) && value.value.length > 0 && new TextEncoder().encode(value.value).byteLength <= 256;
};

/** Validates the published call-record v1 object as well as its recovery invariants. */
export const validateWireCallRecord = (value: unknown): value is WireCallRecord => {
  if (!recordObject(value) || !exactKeys(value, [
    "kind", "record_id", "source_epoch", "record_revision", "cursor_started_at_epoch_ms", "cursor_provider_id",
    "captured_at_epoch_ms", "capture_revision", "policy_revision", "metadata", "counterparty_number",
  ])) return false;
  if (value.kind !== "upsert" || !validCallRecordId(value.record_id) || !isPositiveU64(value.source_epoch)
    || value.record_revision !== "1" || !validNonNegativeI64(value.cursor_started_at_epoch_ms)
    || !validCallProviderId(value.cursor_provider_id) || !validNonNegativeI64(value.captured_at_epoch_ms)
    || !isDecimalU64(value.capture_revision) || !isDecimalU64(value.policy_revision) || !validCallMetadata(value.metadata)) return false;
  return value.record_id === `call:${value.cursor_provider_id}`
    && value.cursor_started_at_epoch_ms === value.metadata.started_at_epoch_ms
    && value.captured_at_epoch_ms === value.metadata.observed_at_epoch_ms
    && value.capture_revision === value.policy_revision
    && validCallCounterparty(value.counterparty_number, value.metadata.number_presentation);
};

/** Emits a frozen plain object in the schema's fixed field order. */
export const encodeCallRecord = (record: WireCallRecord): WireCallRecord => {
  const wire = Object.freeze({
    kind: record.kind, record_id: record.record_id, source_epoch: record.source_epoch, record_revision: record.record_revision,
    cursor_started_at_epoch_ms: record.cursor_started_at_epoch_ms, cursor_provider_id: record.cursor_provider_id,
    captured_at_epoch_ms: record.captured_at_epoch_ms, capture_revision: record.capture_revision, policy_revision: record.policy_revision,
    metadata: Object.freeze({
      direction: record.metadata.direction, started_at_epoch_ms: record.metadata.started_at_epoch_ms,
      ended_at_epoch_ms: record.metadata.ended_at_epoch_ms, duration_seconds: record.metadata.duration_seconds,
      observed_at_epoch_ms: record.metadata.observed_at_epoch_ms, number_presentation: record.metadata.number_presentation,
    }),
    counterparty_number: record.counterparty_number.state === "withheld"
      ? Object.freeze({ state: "withheld" as const })
      : Object.freeze({ state: "released" as const, value: record.counterparty_number.value }),
  });
  if (!validateWireCallRecord(wire)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  return wire;
};

class StrictJsonReader {
  private index = 0;
  private readonly objectOrders = new WeakMap<Record<string, unknown>, readonly string[]>();
  constructor(private readonly text: string) {}

  readDocument(): unknown {
    this.skipWhitespace();
    const value = this.readValue();
    if (this.index !== this.text.length) this.fail();
    return value;
  }

  hasObjectOrder(value: unknown, expected: readonly string[]): boolean {
    if (!recordObject(value)) return false;
    const order = this.objectOrders.get(value);
    return order !== undefined && order.length === expected.length && order.every((key, index) => key === expected[index]);
  }

  private readValue(): unknown {
    const current = this.text[this.index];
    if (current === "{") return this.readObject();
    if (current === "[") return this.readArray();
    if (current === '"') return this.readString();
    if (current === "t" && this.take("true")) return true;
    if (current === "f" && this.take("false")) return false;
    if (current === "n" && this.take("null")) return null;
    if (current === "-" || (current !== undefined && current >= "0" && current <= "9")) return this.readNumber();
    this.fail();
  }

  private readObject(): Record<string, unknown> {
    this.expect("{"); this.skipWhitespace();
    const result: Record<string, unknown> = {};
    const keys = new Set<string>();
    if (this.text[this.index] === "}") { this.index += 1; this.objectOrders.set(result, []); return result; }
    while (true) {
      if (this.text[this.index] !== '"') this.fail();
      const key = this.readString();
      if (keys.has(key)) this.fail();
      keys.add(key); this.skipWhitespace(); this.expect(":"); this.skipWhitespace();
      result[key] = this.readValue(); this.skipWhitespace();
      if (this.text[this.index] === "}") { this.index += 1; this.objectOrders.set(result, [...keys]); return result; }
      this.expect(","); this.skipWhitespace();
    }
  }

  private readArray(): unknown[] {
    this.expect("["); this.skipWhitespace(); const result: unknown[] = [];
    if (this.text[this.index] === "]") { this.index += 1; return result; }
    while (true) {
      result.push(this.readValue()); this.skipWhitespace();
      if (this.text[this.index] === "]") { this.index += 1; return result; }
      this.expect(","); this.skipWhitespace();
    }
  }

  private readString(): string {
    this.expect('"'); let result = "";
    while (true) {
      const value = this.text[this.index++];
      if (value === undefined) this.fail();
      if (value === '"') return result;
      if (value === "\\") {
        const escape = this.text[this.index++];
        if (escape === '"' || escape === "\\" || escape === "/") { result += escape; continue; }
        if (escape === "b") { result += "\b"; continue; }
        if (escape === "f") { result += "\f"; continue; }
        if (escape === "n") { result += "\n"; continue; }
        if (escape === "r") { result += "\r"; continue; }
        if (escape === "t") { result += "\t"; continue; }
        if (escape !== "u") this.fail();
        const code = this.readHex();
        if (code >= 0xD800 && code <= 0xDBFF) {
          if (this.text[this.index++] !== "\\" || this.text[this.index++] !== "u") this.fail();
          const low = this.readHex(); if (low < 0xDC00 || low > 0xDFFF) this.fail();
          result += String.fromCodePoint(0x10000 + (code - 0xD800) * 0x400 + low - 0xDC00);
        } else { if (code >= 0xDC00 && code <= 0xDFFF) this.fail(); result += String.fromCharCode(code); }
        continue;
      }
      if (value.charCodeAt(0) < 0x20) this.fail();
      const unit = value.charCodeAt(0);
      if (unit >= 0xD800 && unit <= 0xDBFF) {
        const low = this.text[this.index];
        if (low === undefined || low.charCodeAt(0) < 0xDC00 || low.charCodeAt(0) > 0xDFFF) this.fail();
        result += value + low; this.index += 1; continue;
      }
      if (unit >= 0xDC00 && unit <= 0xDFFF) this.fail();
      result += value;
    }
  }

  private readNumber(): number {
    const start = this.index;
    if (this.text[this.index] === "-") this.index += 1;
    if (this.text[this.index] === "0") this.index += 1;
    else { if (!this.digit()) this.fail(); while (this.digit()) this.index += 1; }
    if (this.text[this.index] === ".") { this.index += 1; if (!this.digit()) this.fail(); while (this.digit()) this.index += 1; }
    if (this.text[this.index] === "e" || this.text[this.index] === "E") { this.index += 1; if (this.text[this.index] === "+" || this.text[this.index] === "-") this.index += 1; if (!this.digit()) this.fail(); while (this.digit()) this.index += 1; }
    return Number(this.text.slice(start, this.index));
  }
  private readHex(): number { const raw = this.text.slice(this.index, this.index + 4); if (!/^[0-9a-fA-F]{4}$/.test(raw)) this.fail(); this.index += 4; return Number.parseInt(raw, 16); }
  private digit(): boolean { const current = this.text[this.index]; return current !== undefined && current >= "0" && current <= "9"; }
  private take(value: string): boolean { if (!this.text.startsWith(value, this.index)) return false; this.index += value.length; return true; }
  private expect(value: string): void { if (this.text[this.index] !== value) this.fail(); this.index += 1; }
  private skipWhitespace(): void { while (this.text[this.index] === " " || this.text[this.index] === "\n" || this.text[this.index] === "\r" || this.text[this.index] === "\t") this.index += 1; }
  private fail(): never { throw new Error("WIRE_RECORD_UNREPRESENTABLE"); }
}

/** Decodes JSON only after a recursive byte-safe duplicate-key scan. */
export const decodeCallRecordJson = (wire: Uint8Array): WireCallRecord => {
  let decoded: unknown;
  let reader: StrictJsonReader;
  try { reader = new StrictJsonReader(new TextDecoder("utf-8", { fatal: true }).decode(wire)); decoded = reader.readDocument(); }
  catch (_error) { throw new Error("WIRE_RECORD_UNREPRESENTABLE"); }
  if (!validateWireCallRecord(decoded)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  const expectedRoot = ["kind", "record_id", "source_epoch", "record_revision", "cursor_started_at_epoch_ms", "cursor_provider_id", "captured_at_epoch_ms", "capture_revision", "policy_revision", "metadata", "counterparty_number"];
  const expectedMetadata = ["direction", "started_at_epoch_ms", "ended_at_epoch_ms", "duration_seconds", "observed_at_epoch_ms", "number_presentation"];
  const expectedCounterparty = decoded.counterparty_number.state === "withheld" ? ["state"] : ["state", "value"];
  if (!reader.hasObjectOrder(decoded, expectedRoot) || !reader.hasObjectOrder(decoded.metadata, expectedMetadata)
    || !reader.hasObjectOrder(decoded.counterparty_number, expectedCounterparty)) throw new Error("WIRE_RECORD_UNREPRESENTABLE");
  return encodeCallRecord(decoded);
};
