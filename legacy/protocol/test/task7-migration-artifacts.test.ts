/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { Ajv2020 } from "ajv/dist/2020.js";
import addFormatsImport from "ajv-formats";
import { validateSchema } from "../src/schema-validator.js";

const addFormats = addFormatsImport as unknown as (ajv: Ajv2020) => Ajv2020;
const readJson = (path: string): Record<string, unknown> => JSON.parse(
  readFileSync(new URL(`../${path}`, import.meta.url), "utf8"),
) as Record<string, unknown>;

const profile = readJson("test-only/migration/v0.9/profile.json");
const legacySchema = readJson("test-only/migration/v0.9/pending-operation.schema.json");
const legacyPayload = readJson("test-only/migration/v0.9/pending-operation.json");
const legacySignature = readJson("test-only/migration/v0.9/pending-operation-signature.json");
const errors = readJson("registries/v1/errors.json");

const ajv = addFormats(new Ajv2020({ strict: true }));
ajv.addFormat("rfc3339-utc-milliseconds", {
  type: "string",
  validate: (value: string) => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value,
});
ajv.addFormat("base64url-64", { type: "string", validate: (value: string) => /^[A-Za-z0-9_-]{86}$/.test(value) && Buffer.from(value, "base64url").byteLength === 64 && Buffer.from(value, "base64url").toString("base64url") === value });
const validateLegacy = ajv.compile(legacySchema);

describe("Task 7 migration and error independent artifacts", () => {
  it("keeps the v0.9 fixture conformance-only and non-negotiable", () => {
    expect(profile).toEqual({
      protocol_version: "0.9",
      profile_id: "open-android-intelligence-json-es256/0.9-fixture",
      negotiable: false,
      fixture_owner: "Task7",
      pending_operation_schema: "urn:open-android-intelligence:protocol:v0.9:pending-operation",
    });
    expect(validateLegacy(legacyPayload)).toBe(true);
    expect(validateLegacy({ ...legacyPayload, unexpected: true })).toBe(false);
    expect(validateLegacy({ ...legacyPayload, state: "succeeded" })).toBe(false);
    expect(Object.keys(legacySignature).sort()).toEqual(["key_id", "signature", "signing_domain"]);
  });

  it("validates the closed v1 migration receipt without enabling migration code", () => {
    const receipt = {
      migration_id: "migration-1",
      source_schema_id: "urn:open-android-intelligence:protocol:v0.9:pending-operation",
      source_record_digest: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      source_signature: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      target_schema_id: "urn:open-android-intelligence:protocol:v1:operation",
      target_record_digest: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      target_record_id: "operation-1",
      migrated_at: "2026-08-11T00:00:00.000Z",
    };
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:migration-receipt", receipt)).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:migration-receipt", { ...receipt, extra: true })).toThrowError("SCHEMA_INVALID");
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:migration-receipt", { ...receipt, source_schema_id: "urn:open-android-intelligence:protocol:v1:operation" })).toThrowError("SCHEMA_INVALID");
  });

  it("keeps the independent error registry closed and unique", () => {
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:errors-registry", errors)).not.toThrow();
    const rows = errors.errors as Array<Record<string, unknown>>;
    expect(new Set(rows.map((row) => row.code)).size).toBe(rows.length);
    expect(rows.filter((row) => row.retryable).map((row) => row.code).sort()).toEqual(["FLOW_CONTROL_VIOLATION", "RATE_LIMITED", "SECURITY_LEDGER_FULL"]);
  });
});
