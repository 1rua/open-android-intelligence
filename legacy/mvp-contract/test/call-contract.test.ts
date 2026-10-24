import { describe, expect, test } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { createCallRecordSchemaValidator, decodeCallRecordJson, encodeCallRecord, validateWireCallRecord } from "../src/wire-codec.ts";

const released = {
  kind: "upsert",
  record_id: "call:42",
  source_epoch: "7",
  record_revision: "1",
  cursor_started_at_epoch_ms: "1700000000000",
  cursor_provider_id: "42",
  captured_at_epoch_ms: "1700000000999",
  capture_revision: "9",
  policy_revision: "9",
  metadata: {
    direction: "incoming",
    started_at_epoch_ms: "1700000000000",
    ended_at_epoch_ms: "1700000060000",
    duration_seconds: "60",
    observed_at_epoch_ms: "1700000000999",
    number_presentation: "allowed",
  },
  counterparty_number: { state: "released", value: "+15551234567" },
} as const;

describe("call record v1", () => {
  test("accepts the frozen released record and deterministic encoder", () => {
    expect(validateWireCallRecord(released)).toBe(true);
    expect(encodeCallRecord(released)).toEqual(released);
  });

  test("validates both shared frozen fixtures", () => {
    const schema = JSON.parse(readFileSync(resolve(process.cwd(), "mvp-contract/schemas/v1/call-record.schema.json"), "utf8"));
    const validateSchema = createCallRecordSchemaValidator(schema);
    for (const fixture of ["call-record-released.json", "call-record-withheld.json"]) {
      const parsed: unknown = JSON.parse(readFileSync(resolve(process.cwd(), "mvp-contract/fixtures/v1", fixture), "utf8"));
      expect(validateWireCallRecord(parsed)).toBe(true);
      expect(validateSchema(parsed)).toBe(true);
    }
  });

  test("requires exact identity, positive epoch, closed enums and canonical decimal strings", () => {
    expect(validateWireCallRecord({ ...released, record_id: "call:01" })).toBe(false);
    expect(validateWireCallRecord({ ...released, source_epoch: "0" })).toBe(false);
    expect(validateWireCallRecord({ ...released, record_revision: "2" })).toBe(false);
    expect(validateWireCallRecord({ ...released, cursor_provider_id: "43" })).toBe(false);
    expect(validateWireCallRecord({ ...released, source_epoch: "18446744073709551616" })).toBe(false);
    expect(validateWireCallRecord({ ...released, source_epoch: "18446744073709551615" })).toBe(true);
    expect(validateWireCallRecord({ ...released, capture_revision: "18446744073709551615", policy_revision: "18446744073709551615" })).toBe(true);
    expect(validateWireCallRecord({ ...released, capture_revision: "18446744073709551616", policy_revision: "18446744073709551616" })).toBe(false);
    expect(validateWireCallRecord({ ...released, metadata: { ...released.metadata, direction: "blocked" } })).toBe(false);
    expect(validateWireCallRecord({ ...released, counterparty_number: { state: "withheld", value: "+1" } })).toBe(false);
  });

  test("enforces frozen objects, cross-field timestamps and byte-bounded numbers", () => {
    expect(validateWireCallRecord({ ...released, unexpected: true })).toBe(false);
    expect(validateWireCallRecord({ ...released, metadata: { ...released.metadata, observed_at_epoch_ms: "1" } })).toBe(false);
    expect(validateWireCallRecord({ ...released, metadata: { ...released.metadata, ended_at_epoch_ms: "1700000060001" } })).toBe(false);
    expect(validateWireCallRecord({ ...released, counterparty_number: { state: "released", value: "x".repeat(257) } })).toBe(false);
    expect(validateWireCallRecord({ ...released, counterparty_number: { state: "released", value: "😀".repeat(64) } })).toBe(true);
    expect(validateWireCallRecord({ ...released, counterparty_number: { state: "released", value: "😀".repeat(65) } })).toBe(false);
    expect(validateWireCallRecord({ ...released, counterparty_number: { state: "released", value: "\uD800" } })).toBe(false);
    expect(validateWireCallRecord({ ...released, counterparty_number: { state: "released", value: "\uDC00" } })).toBe(false);
    expect(validateWireCallRecord({ ...released, metadata: { ...released.metadata, started_at_epoch_ms: "9223372036854775808" } })).toBe(false);
    expect(validateWireCallRecord({ ...released, counterparty_number: { state: "withheld" } })).toBe(true);

    const atLongMax = {
      ...released,
      cursor_started_at_epoch_ms: "9223372036854775807",
      captured_at_epoch_ms: "9223372036854775807",
      metadata: { ...released.metadata, started_at_epoch_ms: "9223372036854775807", ended_at_epoch_ms: "9223372036854775807", duration_seconds: "0", observed_at_epoch_ms: "9223372036854775807" },
    };
    expect(validateWireCallRecord(atLongMax)).toBe(true);
    expect(validateWireCallRecord({
      ...atLongMax,
      cursor_started_at_epoch_ms: "9223372036854775808",
      captured_at_epoch_ms: "9223372036854775808",
      metadata: { ...atLongMax.metadata, started_at_epoch_ms: "9223372036854775808", ended_at_epoch_ms: "9223372036854775808", observed_at_epoch_ms: "9223372036854775808" },
    })).toBe(false);
  });

  test("strict decoder rejects duplicate keys and trailing material", () => {
    const encoded = new TextEncoder().encode(JSON.stringify(released));
    expect(decodeCallRecordJson(encoded)).toEqual(released);
    expect(() => decodeCallRecordJson(new TextEncoder().encode('{"kind":"upsert","kind":"upsert"}'))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => decodeCallRecordJson(new Uint8Array([...encoded, 0x20, 0x21]))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => decodeCallRecordJson(new Uint8Array([...encoded, 0x20]))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => decodeCallRecordJson(new TextEncoder().encode(JSON.stringify(released).replace('"kind":"upsert","record_id"', '"record_id":"call:42","kind":"upsert"')))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => decodeCallRecordJson(new Uint8Array([0xff]))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => decodeCallRecordJson(new TextEncoder().encode('{"kind":"upsert","record_id":"\\uD800"}'))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
  });

  test("preserves valid supplementary unicode in raw and escaped call numbers", () => {
    const raw = { ...released, counterparty_number: { state: "released" as const, value: "😀" } };
    expect(decodeCallRecordJson(new TextEncoder().encode(JSON.stringify(raw)))).toEqual(raw);
    const escaped = JSON.stringify(raw).replace("😀", "\\uD83D\\uDE00");
    expect(decodeCallRecordJson(new TextEncoder().encode(escaped))).toEqual(raw);
    expect(() => decodeCallRecordJson(new TextEncoder().encode(JSON.stringify(raw).replace("😀", "\\uD800")))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => decodeCallRecordJson(new TextEncoder().encode(JSON.stringify(raw).replace("😀", "\\uDC00")))).toThrow("WIRE_RECORD_UNREPRESENTABLE");
  });

  test("schema validator executes the UTF-8 byte limit extension", () => {
    const schema = JSON.parse(readFileSync(resolve(process.cwd(), "mvp-contract/schemas/v1/call-record.schema.json"), "utf8"));
    const validateSchema = createCallRecordSchemaValidator(schema);
    expect(validateSchema({ ...released, counterparty_number: { state: "released", value: "😀".repeat(64) } })).toBe(true);
    expect(validateSchema({ ...released, counterparty_number: { state: "released", value: "😀".repeat(65) } })).toBe(false);
    expect(validateSchema({ ...released, capture_revision: "18446744073709551615", policy_revision: "18446744073709551615" })).toBe(true);
    expect(validateSchema({ ...released, capture_revision: "18446744073709551616", policy_revision: "18446744073709551616" })).toBe(false);
  });

  test("rejects nested missing extra null and wrong-type values", () => {
    expect(validateWireCallRecord({ ...released, metadata: { ...released.metadata, extra: true } })).toBe(false);
    expect(validateWireCallRecord({ ...released, metadata: { direction: "incoming" } })).toBe(false);
    expect(validateWireCallRecord({ ...released, metadata: null })).toBe(false);
    expect(validateWireCallRecord({ ...released, metadata: { ...released.metadata, duration_seconds: 60 } })).toBe(false);
    expect(validateWireCallRecord({ ...released, counterparty_number: { state: "released" } })).toBe(false);
    const schemaText = readFileSync(resolve(process.cwd(), "mvp-contract/schemas/v1/call-record.schema.json"), "utf8");
    expect(schemaText).toContain('"x-open-android-intelligence-maxUtf8Bytes": 256');
  });
});
