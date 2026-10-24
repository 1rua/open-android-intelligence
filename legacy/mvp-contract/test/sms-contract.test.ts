import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { Ajv2020 } from "ajv/dist/2020.js";
import {
  encodeSmsQuery,
  encodeSmsRecord,
  encodeSmsSubscribe,
  encodeSmsUnsubscribe,
  validateWireSmsOperation,
  validateWireSmsRecord,
} from "../src/wire-codec.ts";

const MAX_U64 = 18_446_744_073_709_551_615n;
const MAX_SMS_PROVIDER_ID = 9_223_372_036_854_775_807n;

const recordInput = {
  recordId: "sms:42",
  sourceEpoch: 1n,
  recordRevision: 1n,
  cursorMessageAtEpochMs: 1_700_000_000_000n,
  cursorProviderId: 42n,
  capturedAtEpochMs: 1_700_000_000_100n,
  captureRevision: 7n,
  policyRevision: 7n,
  senderAddress: "+8613800000000",
  threadId: "9",
  messageAtEpochMs: 1_700_000_000_000n,
  observedAtEpochMs: 1_700_000_000_100n,
  read: false,
  subscriptionId: 1,
  body: "",
} as const;

describe("closed SMS wire contract", () => {
  it("encodes and round-trips an exact frozen SMS record", () => {
    const wire = encodeSmsRecord(recordInput);
    expect(Object.keys(wire).sort()).toEqual([
      "capture_revision", "captured_at_epoch_ms", "content", "cursor_message_at_epoch_ms", "cursor_provider_id",
      "kind", "metadata", "policy_revision", "record_id", "record_revision", "source_epoch",
    ]);
    expect(wire).toEqual({
      kind: "upsert", record_id: "sms:42", source_epoch: "1", record_revision: "1",
      cursor_message_at_epoch_ms: "1700000000000", cursor_provider_id: "42",
      captured_at_epoch_ms: "1700000000100", capture_revision: "7", policy_revision: "7",
      metadata: {
        sender_address: "+8613800000000", thread_id: "9", message_at_epoch_ms: "1700000000000",
        observed_at_epoch_ms: "1700000000100", read: false, subscription_id: 1,
      },
      content: { body: "" },
    });
    expect(Object.isFrozen(wire)).toBe(true);
    expect(Object.isFrozen(wire.metadata)).toBe(true);
    expect(Object.isFrozen(wire.content)).toBe(true);
    expect(validateWireSmsRecord(wire)).toBe(true);
  });

  it("accepts decimal-u64 boundaries and rejects malformed numeric inputs", () => {
    const maximum = encodeSmsRecord({ ...recordInput, sourceEpoch: MAX_U64 });
    expect(maximum.source_epoch).toBe("18446744073709551615");
    expect(validateWireSmsRecord({ ...maximum, source_epoch: "18446744073709551616" })).toBe(false);
    expect(validateWireSmsRecord({ ...maximum, source_epoch: "01" })).toBe(false);
    expect(validateWireSmsRecord({ ...maximum, source_epoch: -1 })).toBe(false);
    expect(validateWireSmsRecord({ ...maximum, metadata: { ...maximum.metadata, subscription_id: 2 ** 53 } })).toBe(false);
    expect(() => encodeSmsRecord({ ...recordInput, capturedAtEpochMs: -1n })).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => encodeSmsRecord({ ...recordInput, subscriptionId: 2 ** 53 })).toThrow("WIRE_RECORD_UNREPRESENTABLE");
  });

  it("rejects identity, endpoint, MMS, and arbitrary capability fields", () => {
    const wire = encodeSmsRecord(recordInput);
    for (const forbidden of [
      "agent_principal_id", "session_id", "tenant_id", "workspace_id", "job_id",
      "endpoint", "socket", "vpn", "shell", "mms_parts", "capability",
    ]) {
      expect(validateWireSmsRecord({ ...wire, [forbidden]: "forged" })).toBe(false);
    }
    expect(validateWireSmsRecord({ ...wire, metadata: { ...wire.metadata, endpoint: "forged" } })).toBe(false);
  });

  it("rejects non-SMS IDs and cursor fields that disagree with the record", () => {
    const wire = encodeSmsRecord(recordInput);
    for (const recordId of ["mms:42", "42", "sms:0", "sms:01"]) {
      expect(validateWireSmsRecord({ ...wire, record_id: recordId })).toBe(false);
      expect(() => encodeSmsRecord({ ...recordInput, recordId })).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    }
    expect(validateWireSmsRecord({ ...wire, cursor_provider_id: "43" })).toBe(false);
    expect(validateWireSmsRecord({ ...wire, cursor_message_at_epoch_ms: "1700000000001" })).toBe(false);
    expect(() => encodeSmsRecord({ ...recordInput, cursorProviderId: 43n })).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => encodeSmsRecord({ ...recordInput, cursorMessageAtEpochMs: 1_700_000_000_001n }))
      .toThrow("WIRE_RECORD_UNREPRESENTABLE");
  });

  it("limits only SMS provider IDs to Long.MAX_VALUE while preserving other u64 fields", () => {
    const maximum = encodeSmsRecord({
      ...recordInput,
      recordId: `sms:${MAX_SMS_PROVIDER_ID}`,
      cursorProviderId: MAX_SMS_PROVIDER_ID,
      sourceEpoch: MAX_U64,
      cursorMessageAtEpochMs: MAX_U64,
      messageAtEpochMs: MAX_U64,
      observedAtEpochMs: MAX_U64,
      capturedAtEpochMs: MAX_U64,
      captureRevision: MAX_U64,
      policyRevision: MAX_U64,
    });
    expect(validateWireSmsRecord(maximum)).toBe(true);

    const tooLargeProviderId = MAX_SMS_PROVIDER_ID + 1n;
    expect(validateWireSmsRecord({
      ...maximum,
      record_id: `sms:${tooLargeProviderId}`,
      cursor_provider_id: tooLargeProviderId.toString(),
    })).toBe(false);
    expect(() => encodeSmsRecord({
      ...recordInput,
      recordId: `sms:${tooLargeProviderId}`,
      cursorProviderId: tooLargeProviderId,
    })).toThrow("WIRE_RECORD_UNREPRESENTABLE");
  });

  it("applies the positive SMS record ID rule in the JSON Schema", () => {
    const schema = JSON.parse(readFileSync(resolve(process.cwd(), "mvp-contract/schemas/v1/sms-record.schema.json"), "utf8"));
    const validateSchema = new Ajv2020({ strict: false }).compile(schema);
    const wire = encodeSmsRecord(recordInput);
    expect(validateSchema(wire)).toBe(true);
    for (const recordId of ["mms:42", "42", "sms:0", "sms:01"]) {
      expect(validateSchema({ ...wire, record_id: recordId })).toBe(false);
    }
    expect(validateSchema({
      ...wire,
      record_id: "sms:9223372036854775807",
      cursor_provider_id: "9223372036854775807",
    })).toBe(true);
    expect(validateSchema({
      ...wire,
      record_id: "sms:9223372036854775808",
      cursor_provider_id: "9223372036854775808",
    })).toBe(false);
  });

  it("binds SMS query and subscription lifecycle operations to the policy revision", () => {
    const query = encodeSmsQuery({ operationId: "op-1", policyRevision: 7n, limit: 10_000 });
    const subscribe = encodeSmsSubscribe({ subscriptionId: "sub-1", policyRevision: 7n });
    const unsubscribe = encodeSmsUnsubscribe({ subscriptionId: "sub-1", policyRevision: 7n });
    expect(validateWireSmsOperation(query)).toBe(true);
    expect(validateWireSmsOperation(subscribe)).toBe(true);
    expect(validateWireSmsOperation(unsubscribe)).toBe(true);
    expect(validateWireSmsOperation({ ...query, limit: 0 })).toBe(false);
    expect(validateWireSmsOperation({ ...query, limit: 10_001 })).toBe(false);
    expect(validateWireSmsOperation({ ...subscribe, policy_revision: "8", endpoint: "forged" })).toBe(false);
  });
});
