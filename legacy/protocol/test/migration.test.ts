/// <reference types="node" />

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { canonicalBytes, sha256B64Url, signingPreimage } from "../src/encoding.js";
import { signTestOnly } from "../src/crypto.js";
import { migrateSignedRecord } from "../src/migration.js";
import type { LegacySignedPendingRecord } from "../src/migration.js";
import { parseSignatureDomain } from "../src/profile.js";

const readJson = (path: string): any => JSON.parse(readFileSync(new URL(`../${path}`, import.meta.url), "utf8"));
const privateJwk = readJson("test-only/keys/bridge-command-private.jwk.json");
const publicJwk = readJson("test-only/keys/bridge-command-public.jwk.json");

const legacyPayload = {
  message_schema: "urn:open-android-intelligence:protocol:v0.9:pending-operation",
  operation_id: "legacy-operation-1",
  parameters_digest: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  state: "pending",
  expires_at: "2026-08-11T00:15:00.000Z",
  offline_policy: "FAIL_OFFLINE",
  binding: {
    tenant_id: "tenant-1",
    human_principal_id: "human-1",
    agent_principal_id: "agent-1",
    agent_instance_id: "agent-instance-1",
    workspace_id: "workspace-1",
    session_or_job: { kind: "session", session_id: "session-1", job_id: null },
    device_id: "device-1",
    capability: "mobile.notifications",
  },
} as const;

const legacyHeader = {
  message_id: "018f4f9a-1111-4111-8111-111111111111",
  sequence: "1",
  expires_at: "2026-08-11T00:15:00.000Z",
} as const;

const signedLegacy = (): LegacySignedPendingRecord => ({
  header: legacyHeader,
  payload: legacyPayload,
  signature: signTestOnly(privateJwk, signingPreimage(parseSignatureDomain("migration/bridge"), { header: legacyHeader, payload: legacyPayload })),
});

describe("Task 7 signed legacy migration", () => {
  it("verifies the locked legacy signed record and emits a v1 target plus receipt", () => {
    const source = signedLegacy();
    const result = migrateSignedRecord({
      sourceSchemaId: legacyPayload.message_schema,
      source,
      sourcePublicJwk: publicJwk,
      expectedSourceKeyId: "test-bridge-command-current",
      migrationId: "migration-1",
      migratedAt: "2026-08-11T00:00:00.000Z",
    }, "1.0");

    const sourceDigest = sha256B64Url(canonicalBytes(source));
    expect(result.target.operation_id).toBe(legacyPayload.operation_id);
    expect(result.target.state).toEqual({ request_status: "created", terminal_outcome: null, operation_reason: null });
    expect(result.receipt).toMatchObject({
      source_schema_id: legacyPayload.message_schema,
      source_record_digest: sourceDigest,
      source_signature: source.signature,
      target_schema_id: "urn:open-android-intelligence:protocol:v1:operation",
      target_record_id: legacyPayload.operation_id,
    });
    expect(result.receipt.target_record_digest).toBe(sha256B64Url(canonicalBytes(result.target)));
  });

  it("rejects source mutations, wrong signer/domain and rollback targets before producing a record", () => {
    const source = signedLegacy();
    expect(() => migrateSignedRecord({
      sourceSchemaId: legacyPayload.message_schema,
      source: { ...source, payload: { ...source.payload, operation_id: "legacy-operation-2" } },
      sourcePublicJwk: publicJwk,
      expectedSourceKeyId: "test-bridge-command-current",
      migrationId: "migration-1",
      migratedAt: "2026-08-11T00:00:00.000Z",
    }, "1.0")).toThrowError("INTEGRITY_FAILED");
    expect(() => migrateSignedRecord({
      sourceSchemaId: legacyPayload.message_schema,
      source,
      sourcePublicJwk: { ...publicJwk, kid: "wrong-key" },
      expectedSourceKeyId: "test-bridge-command-current",
      migrationId: "migration-1",
      migratedAt: "2026-08-11T00:00:00.000Z",
    }, "1.0")).toThrowError("INTEGRITY_FAILED");
    expect(() => migrateSignedRecord({
      sourceSchemaId: legacyPayload.message_schema,
      source,
      sourcePublicJwk: publicJwk,
      expectedSourceKeyId: "test-bridge-command-current",
      migrationId: "migration-1",
      migratedAt: "2026-08-11T00:00:00.000Z",
    }, "0.9")).toThrowError("VERSION_UNSUPPORTED");
  });
});
