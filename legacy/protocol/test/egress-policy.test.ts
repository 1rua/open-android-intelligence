import { describe, expect, it } from "vitest";
import { canonicalBytes, sha256B64Url } from "../src/encoding.js";
import {
  evaluateEgressProfile,
  verifyZeroRetentionEvidence,
  type ExpectedEgressDeployment,
} from "../src/egress-policy.js";

const expected: ExpectedEgressDeployment = {
  providerDestination: "provider-prod", accountProfileId: "account-1",
  contractConfigDigest: "config-digest", evidenceRevision: 4n,
};
const matrix = { request: { log: false, training: false, review: false, cache: false, backup: false }, response: { log: false, training: false, review: false, cache: false, backup: false }, attachment: { log: false, training: false, review: false, cache: false, backup: false }, tool_payload: { log: false, training: false, review: false, cache: false, backup: false } } as const;
const base = { provider_destination: expected.providerDestination, account_profile_id: expected.accountProfileId, evidence_revision: "4", contract_config_digest: expected.contractConfigDigest, verified_at: "2026-01-01T00:00:00.000Z", expires_at: "2026-01-02T00:00:00.000Z", key_id: "bridge-key", signature_domain: "zero-retention/bridge", retention_statement: matrix };
const verifier = { verify: () => true };

describe("zero-retention body egress", () => {
  it("mints evidence only when the signed closed false matrix matches deployment", () => {
    const evidence = verifyZeroRetentionEvidence({ ...base, signature: "sig" }, expected, verifier);
    expect(evaluateEgressProfile({ evidence, expected, clock: { wallNow: () => new Date("2026-01-01T12:00:00.000Z"), monotonicNowMs: () => 0n }, providerObject: "none" })).toEqual({ allowed: true });
  });

  it.each(["returned", "unknown"] as const)("fails closed when provider returns %s object", (providerObject) => {
    const evidence = verifyZeroRetentionEvidence({ ...base, signature: "sig" }, expected, verifier);
    expect(evaluateEgressProfile({ evidence, expected, clock: { wallNow: () => new Date("2026-01-01T12:00:00.000Z"), monotonicNowMs: () => 0n }, providerObject })).toEqual({ allowed: false, reason: "POLICY_BLOCKED" });
  });

  it("uses exact canonical evidence without signature for its digest preimage", () => {
    let received: Uint8Array | undefined;
    const evidence = { ...base, signature: "sig" };
    verifyZeroRetentionEvidence(evidence, expected, { verify: (_key, preimage) => { received = preimage; return true; } });
    expect(received).toBeInstanceOf(Uint8Array);
    expect(sha256B64Url(canonicalBytes({ ...base }))).toHaveLength(43);
  });
});
