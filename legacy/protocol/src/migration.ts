/// <reference types="node" />

import type { JsonWebKey } from "node:crypto";
import { canonicalBytes, sha256B64Url, signingPreimage } from "./encoding.js";
import { isLowS, verifyEs256 } from "./crypto.js";
import { validateSchema } from "./schema-validator.js";
import { parseSignatureDomain } from "./profile.js";

const LEGACY_SCHEMA_ID = "urn:open-android-intelligence:protocol:v0.9:pending-operation" as const;
const TARGET_SCHEMA_ID = "urn:open-android-intelligence:protocol:v1:operation" as const;
const DIGEST = /^[A-Za-z0-9_-]{43}$/;
const ID = /^[A-Za-z0-9._~-]{1,128}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

type JsonRecord = Record<string, unknown>;

export type LegacySignedPendingRecord = Readonly<{
  readonly header: Readonly<{
    readonly message_id: string;
    readonly sequence: string;
    readonly expires_at: string;
  }>;
  readonly payload: Readonly<{
    readonly message_schema: typeof LEGACY_SCHEMA_ID;
    readonly operation_id: string;
    readonly parameters_digest: string;
    readonly state: "pending";
    readonly expires_at: string;
    readonly offline_policy: "WAIT_READ" | "FAIL_OFFLINE";
    readonly binding: Readonly<{
      readonly tenant_id: string;
      readonly human_principal_id: string;
      readonly agent_principal_id: string;
      readonly agent_instance_id: string;
      readonly workspace_id: string;
      readonly session_or_job: Readonly<{ kind: "session"; session_id: string; job_id: null } | { kind: "job"; session_id: null; job_id: string }>;
      readonly device_id: string;
      readonly capability: string;
    }>;
  }>;
  readonly signature: string;
}>;

export type MigrationInput = Readonly<{
  readonly sourceSchemaId: string;
  readonly source: LegacySignedPendingRecord;
  readonly sourcePublicJwk: JsonWebKey;
  /** Supplied by the trusted key-ring lookup, never derived from the source. */
  readonly expectedSourceKeyId: string;
  readonly migrationId: string;
  readonly migratedAt: string;
}>;

export type V1MigratedOperation = Readonly<{
  readonly operation_id: string;
  readonly binding: Readonly<{
    readonly tenant_id: string;
    readonly human_principal_id: string;
    readonly agent_principal_id: string;
    readonly agent_instance_id: string;
    readonly workspace_id: string;
    readonly session_or_job: Readonly<{ kind: "session"; session_id: string; job_id: null } | { kind: "job"; session_id: null; job_id: string }>;
    readonly device_id: string;
    readonly operation_id: string;
    readonly capability: string;
    readonly parameters_digest: string;
  }>;
  readonly operation_expires_at: string;
  readonly offline_policy: "WAIT_READ" | "FAIL_OFFLINE";
  readonly state_revision: "0";
  readonly state: Readonly<{ request_status: "created"; terminal_outcome: null; operation_reason: null }>;
  readonly reconciliation: null;
}>;

export type MigrationReceipt = Readonly<{
  readonly migration_id: string;
  readonly source_schema_id: string;
  readonly source_record_digest: string;
  readonly source_signature: string;
  readonly target_schema_id: typeof TARGET_SCHEMA_ID;
  readonly target_record_digest: string;
  readonly target_record_id: string;
  readonly migrated_at: string;
}>;

export type MigratedSignedRecord = Readonly<{
  readonly target: V1MigratedOperation;
  readonly receipt: MigrationReceipt;
  readonly source: LegacySignedPendingRecord;
}>;

const isRecord = (value: unknown): value is JsonRecord => typeof value === "object" && value !== null && !Array.isArray(value);
const exactKeys = (value: JsonRecord, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};
const validInstant = (value: unknown): value is string => typeof value === "string"
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)
  && Number.isFinite(Date.parse(value)) && new Date(value).toISOString() === value;
const failIntegrity = (): never => { throw new Error("INTEGRITY_FAILED"); };

const validateLegacy = (input: MigrationInput): void => {
  if (input.sourceSchemaId !== LEGACY_SCHEMA_ID || !ID.test(input.migrationId) || !validInstant(input.migratedAt)
    || !ID.test(input.expectedSourceKeyId)
    || (input.sourcePublicJwk as { kid?: unknown }).kid !== input.expectedSourceKeyId) failIntegrity();
  const source = input.source;
  if (!isRecord(source) || !exactKeys(source, ["header", "payload", "signature"]) || typeof source.signature !== "string" || !isLowS(source.signature)) failIntegrity();
  if (!isRecord(source.header) || !exactKeys(source.header, ["message_id", "sequence", "expires_at"])
    || !UUID.test(String(source.header.message_id)) || !/^(0|[1-9][0-9]*)$/.test(String(source.header.sequence))
    || !validInstant(source.header.expires_at)) failIntegrity();
  if (!isRecord(source.payload) || !exactKeys(source.payload, ["message_schema", "operation_id", "parameters_digest", "state", "expires_at", "offline_policy", "binding"])
    || source.payload.message_schema !== LEGACY_SCHEMA_ID || typeof source.payload.operation_id !== "string" || !ID.test(source.payload.operation_id)
    || typeof source.payload.parameters_digest !== "string" || !DIGEST.test(source.payload.parameters_digest)
    || source.payload.state !== "pending" || !validInstant(source.payload.expires_at)
    || source.payload.expires_at !== source.header.expires_at
    || (source.payload.offline_policy !== "WAIT_READ" && source.payload.offline_policy !== "FAIL_OFFLINE")) failIntegrity();
  const binding = source.payload.binding;
  const bindingRecord = binding as unknown as JsonRecord;
  if (!isRecord(binding) || !exactKeys(binding, ["tenant_id", "human_principal_id", "agent_principal_id", "agent_instance_id", "workspace_id", "session_or_job", "device_id", "capability"])
    || ["tenant_id", "human_principal_id", "agent_principal_id", "agent_instance_id", "workspace_id", "device_id", "capability"].some((key) => typeof bindingRecord[key] !== "string" || !ID.test(bindingRecord[key] as string))) failIntegrity();
  const sessionOrJob = binding.session_or_job;
  if (!isRecord(sessionOrJob) || typeof sessionOrJob.kind !== "string"
    || sessionOrJob.kind === "session" && (!exactKeys(sessionOrJob, ["kind", "session_id", "job_id"]) || typeof sessionOrJob.session_id !== "string" || !ID.test(sessionOrJob.session_id) || sessionOrJob.job_id !== null)
    || sessionOrJob.kind === "job" && (!exactKeys(sessionOrJob, ["kind", "session_id", "job_id"]) || sessionOrJob.session_id !== null || typeof sessionOrJob.job_id !== "string" || !ID.test(sessionOrJob.job_id))) failIntegrity();
  if (source.header.expires_at !== source.payload.expires_at) failIntegrity();
  if (!verifyEs256(input.sourcePublicJwk, signingPreimage(parseSignatureDomain("migration/bridge"), { header: source.header, payload: source.payload }), source.signature)) failIntegrity();
};

const freeze = <T>(value: T): T => {
  if (typeof value === "object" && value !== null && !Object.isFrozen(value)) {
    for (const child of Object.values(value as object)) freeze(child);
    Object.freeze(value);
  }
  return value;
};

/**
 * Migrates only the locked v0.9 pending-operation fixture shape. The source
 * signed envelope is retained verbatim for digest/receipt purposes; the v1
 * target is independently validated and never re-signs or rewrites source
 * facts. This is a conformance reducer, not a version-negotiation path.
 */
export function migrateSignedRecord(input: MigrationInput, targetVersion: string): MigratedSignedRecord {
  if (targetVersion !== "1.0") throw new Error("VERSION_UNSUPPORTED");
  validateLegacy(input);
  const source = input.source;
  const payload = source.payload;
  const target: V1MigratedOperation = {
    operation_id: payload.operation_id,
    binding: {
      ...payload.binding,
      operation_id: payload.operation_id,
      parameters_digest: payload.parameters_digest,
    },
    operation_expires_at: payload.expires_at,
    offline_policy: payload.offline_policy,
    state_revision: "0",
    state: { request_status: "created", terminal_outcome: null, operation_reason: null },
    reconciliation: null,
  };
  try {
    validateSchema(TARGET_SCHEMA_ID, target);
  } catch {
    failIntegrity();
  }
  const sourceRecordDigest = sha256B64Url(canonicalBytes(source));
  const targetRecordDigest = sha256B64Url(canonicalBytes(target));
  const receipt: MigrationReceipt = {
    migration_id: input.migrationId,
    source_schema_id: input.sourceSchemaId,
    source_record_digest: sourceRecordDigest,
    source_signature: source.signature,
    target_schema_id: TARGET_SCHEMA_ID,
    target_record_digest: targetRecordDigest,
    target_record_id: target.operation_id,
    migrated_at: input.migratedAt,
  };
  try {
    validateSchema("urn:open-android-intelligence:protocol:v1:migration-receipt", receipt);
  } catch {
    failIntegrity();
  }
  return freeze({ target, receipt, source });
}

export { LEGACY_SCHEMA_ID, TARGET_SCHEMA_ID };
