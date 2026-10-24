import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { canonicalBytes, sha256B64Url } from "../src/encoding.js";
import { validateWireOperationState } from "../src/operation-machine.js";

const readJson = (path: string): unknown => JSON.parse(
  readFileSync(new URL(`../${path}`, import.meta.url), "utf8"),
);

const stateVectors = readJson("test-only/operation/v1/operation-state-vectors.json") as {
  vectors: readonly { id: string; state: unknown; valid: boolean }[];
};
const digestVectors = readJson("test-only/operation/v1/result-digest-vectors.json") as {
  vectors: readonly { id: string; result: unknown; result_digest: string | null; result_present: boolean }[];
};
const reconciliationVectors = readJson("test-only/operation/v1/reconciliation-evidence-vectors.json") as {
  vectors: readonly { id: string; evidence: unknown; evidence_digest: string }[];
};

describe("Task 7 immutable operation vectors", () => {
  it("covers every closed pending/terminal state branch and rejects invalid cross-products", () => {
    expect(Array.isArray(stateVectors.vectors)).toBe(true);
    expect(stateVectors.vectors.length).toBeGreaterThanOrEqual(14);
    expect(new Set(stateVectors.vectors.map((vector) => vector.id)).size).toBe(stateVectors.vectors.length);
    for (const vector of stateVectors.vectors) {
      expect(validateWireOperationState(vector.state)).toBe(vector.valid);
    }
  });

  it("recomputes result digests from canonical result bytes and preserves null-vs-absent semantics", () => {
    expect(digestVectors.vectors.length).toBeGreaterThanOrEqual(3);
    for (const vector of digestVectors.vectors) {
      if (!vector.result_present) {
        expect(vector.result).toBeNull();
        expect(vector.result_digest).toBeNull();
      } else {
        expect(typeof vector.result_digest).toBe("string");
        expect(vector.result_digest).toBe(sha256B64Url(canonicalBytes(vector.result)));
      }
    }
  });

  it("recomputes reconciliation evidence digests over the complete closed descriptor", () => {
    expect(reconciliationVectors.vectors.length).toBeGreaterThanOrEqual(2);
    for (const vector of reconciliationVectors.vectors) {
      expect(vector.evidence_digest).toBe(sha256B64Url(canonicalBytes(vector.evidence)));
    }
  });
});
