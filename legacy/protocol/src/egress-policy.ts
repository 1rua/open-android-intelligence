/** Signed zero-retention deployment evidence and body-egress gate. */

import { canonicalBytes } from "./encoding.js";
import type { Clock } from "./ports.js";

export type ProviderObjectObservation = "none" | "returned" | "unknown";

export interface ExpectedEgressDeployment {
  readonly providerDestination: string;
  readonly accountProfileId: string;
  readonly contractConfigDigest: string;
  readonly evidenceRevision: bigint;
}

export interface ZeroRetentionVerifier {
  verify(keyId: string, preimage: Uint8Array, signature: string): boolean;
}

export interface ZeroRetentionEvidenceInput {
  readonly provider_destination: string;
  readonly account_profile_id: string;
  readonly evidence_revision: string;
  readonly contract_config_digest: string;
  readonly verified_at: string;
  readonly expires_at: string;
  readonly key_id: string;
  readonly signature_domain: "zero-retention/bridge";
  readonly retention_statement: ZeroRetentionStatement;
  readonly signature: string;
}

export type RetentionFlags = Readonly<{ log: false; training: false; review: false; cache: false; backup: false }>;
export type ZeroRetentionStatement = Readonly<{ request: RetentionFlags; response: RetentionFlags; attachment: RetentionFlags; tool_payload: RetentionFlags }>;

const verifiedZeroRetentionEvidenceBrand: unique symbol = Symbol("verified-zero-retention-evidence");
const evidenceAuthority = new WeakSet<object>();

export type VerifiedZeroRetentionEvidence = Readonly<{
  readonly providerDestination: string;
  readonly accountProfileId: string;
  readonly evidenceRevision: bigint;
  readonly contractConfigDigest: string;
  readonly verifiedAt: string;
  readonly expiresAt: string;
  readonly [verifiedZeroRetentionEvidenceBrand]: true;
}>;

const U64 = /^(0|[1-9][0-9]*)$/u;
const ID = /^[^\u0000-\u001f\u007f]{1,4096}$/u;
const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};
const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const validInstant = (value: unknown): value is string => typeof value === "string" && Number.isFinite(Date.parse(value)) && value === new Date(value).toISOString();
const parseU64 = (value: unknown): bigint | null => {
  if (typeof value !== "string" || !U64.test(value)) return null;
  const parsed = BigInt(value);
  return parsed <= 18_446_744_073_709_551_615n ? parsed : null;
};

const flagKeys = ["log", "training", "review", "cache", "backup"] as const;
const statementKeys = ["request", "response", "attachment", "tool_payload"] as const;
const validateFalseMatrix = (value: unknown): value is ZeroRetentionStatement => {
  if (!isRecord(value) || !exactKeys(value, statementKeys)) return false;
  for (const member of statementKeys) {
    const row = value[member];
    if (!isRecord(row) || !exactKeys(row, flagKeys) || flagKeys.some((flag) => row[flag] !== false)) return false;
  }
  return true;
};

const withoutSignature = (value: ZeroRetentionEvidenceInput): Readonly<Record<string, unknown>> => ({
  provider_destination: value.provider_destination,
  account_profile_id: value.account_profile_id,
  evidence_revision: value.evidence_revision,
  contract_config_digest: value.contract_config_digest,
  verified_at: value.verified_at,
  expires_at: value.expires_at,
  key_id: value.key_id,
  signature_domain: value.signature_domain,
  retention_statement: value.retention_statement,
});

export function zeroRetentionEvidencePreimage(evidence: unknown): Uint8Array {
  if (!isRecord(evidence) || !exactKeys(evidence, ["provider_destination", "account_profile_id", "evidence_revision", "contract_config_digest", "verified_at", "expires_at", "key_id", "signature_domain", "retention_statement", "signature"])) throw new Error("SCHEMA_INVALID");
  const noSignature = { ...evidence };
  delete noSignature.signature;
  const canonical = canonicalBytes(noSignature);
  const prefix = new TextEncoder().encode("zero-retention/bridge\u0000");
  const length = new Uint8Array(4);
  new DataView(length.buffer).setUint32(0, canonical.byteLength, false);
  const result = new Uint8Array(prefix.byteLength + length.byteLength + canonical.byteLength);
  result.set(prefix, 0); result.set(length, prefix.byteLength); result.set(canonical, prefix.byteLength + length.byteLength);
  return result;
}

export function verifyZeroRetentionEvidence(
  evidence: unknown,
  expected: ExpectedEgressDeployment,
  verifier: ZeroRetentionVerifier,
): VerifiedZeroRetentionEvidence {
  if (!isRecord(evidence) || !exactKeys(evidence, ["provider_destination", "account_profile_id", "evidence_revision", "contract_config_digest", "verified_at", "expires_at", "key_id", "signature_domain", "retention_statement", "signature"])) throw new Error("SCHEMA_INVALID");
  if (typeof evidence.provider_destination !== "string" || !ID.test(evidence.provider_destination) || typeof evidence.account_profile_id !== "string" || !ID.test(evidence.account_profile_id) || typeof evidence.contract_config_digest !== "string" || !ID.test(evidence.contract_config_digest) || typeof evidence.key_id !== "string" || !ID.test(evidence.key_id) || typeof evidence.signature !== "string" || !ID.test(evidence.signature) || evidence.signature_domain !== "zero-retention/bridge" || !validInstant(evidence.verified_at) || !validInstant(evidence.expires_at) || Date.parse(evidence.expires_at) <= Date.parse(evidence.verified_at) || !validateFalseMatrix(evidence.retention_statement)) throw new Error("SCHEMA_INVALID");
  const revision = parseU64(evidence.evidence_revision);
  if (revision === null || evidence.provider_destination !== expected.providerDestination || evidence.account_profile_id !== expected.accountProfileId || evidence.contract_config_digest !== expected.contractConfigDigest || revision !== expected.evidenceRevision) throw new Error("INTEGRITY_FAILED");
  let valid = false;
  try { valid = verifier.verify(evidence.key_id as string, zeroRetentionEvidencePreimage(evidence), evidence.signature); } catch { valid = false; }
  if (!valid) throw new Error("INTEGRITY_FAILED");
  const result = Object.freeze({ providerDestination: evidence.provider_destination, accountProfileId: evidence.account_profile_id, evidenceRevision: revision, contractConfigDigest: evidence.contract_config_digest, verifiedAt: evidence.verified_at, expiresAt: evidence.expires_at, [verifiedZeroRetentionEvidenceBrand]: true as const });
  evidenceAuthority.add(result);
  return result;
}

export function evaluateEgressProfile(input: Readonly<{
  readonly evidence: VerifiedZeroRetentionEvidence | null;
  readonly expected: ExpectedEgressDeployment;
  readonly clock: Clock;
  readonly providerObject: ProviderObjectObservation;
}>): { readonly allowed: true } | { readonly allowed: false; readonly reason: "POLICY_BLOCKED" } {
  const now = input.clock.wallNow();
  const evidence = input.evidence;
  if (!evidence || !evidenceAuthority.has(evidence as object) || evidence[verifiedZeroRetentionEvidenceBrand] !== true || input.providerObject !== "none" || !Number.isFinite(now.getTime())) return { allowed: false, reason: "POLICY_BLOCKED" };
  if (evidence.providerDestination !== input.expected.providerDestination || evidence.accountProfileId !== input.expected.accountProfileId || evidence.contractConfigDigest !== input.expected.contractConfigDigest || evidence.evidenceRevision !== input.expected.evidenceRevision) return { allowed: false, reason: "POLICY_BLOCKED" };
  const verifiedAt = Date.parse(evidence.verifiedAt); const expiresAt = Date.parse(evidence.expiresAt);
  if (!Number.isFinite(verifiedAt) || !Number.isFinite(expiresAt) || verifiedAt > now.getTime() || now.getTime() >= expiresAt) return { allowed: false, reason: "POLICY_BLOCKED" };
  return { allowed: true };
}
