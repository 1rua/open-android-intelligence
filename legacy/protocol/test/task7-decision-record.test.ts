/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { canonicalBytes } from "../src/encoding.js";

const readText = (path: string): string => readFileSync(new URL(path, import.meta.url), "utf8");
const readJson = (path: string): Record<string, unknown> => JSON.parse(readText(path)) as Record<string, unknown>;

describe("Task 7 approved decision/reference artifacts", () => {
  it("records the four user-confirmed decisions without claiming a backend", () => {
    const record = readText("../../.superpowers/sdd/2026-08-08-p0a-protocol-security-model/task-7-product-decisions.md");
    expect(record).toMatch(/Status:\s*`APPROVED_USER_CONFIRMED`/);
    expect(record).toMatch(/D1[\s\S]*selected_option_or_acceptance:\s*A/);
    expect(record).toMatch(/D2[\s\S]*selected_option_or_acceptance:\s*ACCEPT/);
    expect(record).toMatch(/D3[\s\S]*selected_option_or_acceptance:\s*ACCEPT/);
    expect(record).toMatch(/D4[\s\S]*selected_option_or_acceptance:\s*ACCEPT/);
    expect(record).toMatch(/approver_role:\s*product owner \(user\)/);
    expect(record).toMatch(/implementation_status:\s*reference artifacts approved; runtime backend integration pending/);
  });

  it("freezes the exact quota arithmetic, retention equality and recovery outcome", () => {
    const operations = readText("../../docs/protocol/v1/operations.md");
    expect(operations).toMatch(/16,384\s*×\s*\(262,144\s*\+\s*262,144\s*\+\s*65,536\)\s*=\s*9,663,676,416/);
    expect(operations).toMatch(/retention_until\s*=\s*max\(operation_expires_at,\s*bridge_ack_at \+ 30 days\)/);
    expect(operations).toMatch(/now == retention_until.*admit/);
    expect(operations).toMatch(/result_unknown/);
    expect(operations).toMatch(/never auto-reexecutes/);
    expect(operations).toMatch(/bounded polling/);
  });

  it("reconstructs every checked-in compaction vector from its complete JCS projection", () => {
    const fixture = readJson("../test-only/replay/v1/compaction-recovery-vectors.json");
    expect(fixture).toMatchObject({
      artifact_set: "task7-compaction-recovery-v1",
      encoding: "RFC8785-JCS-UTF8",
      base64: "RFC4648-standard-padded",
    });
    const vectors = fixture.vectors as Array<Record<string, unknown>>;
    expect(vectors.length).toBeGreaterThanOrEqual(16);
    const ids = new Set(vectors.map((vector) => vector.vector_id));
    for (const id of [
      "security-row-count-16383", "security-row-count-16384", "security-row-count-16385",
      "security-inbound-262143", "security-inbound-262144", "security-inbound-262145",
      "security-receipt-262143", "security-receipt-262144", "security-receipt-262145",
      "security-metadata-65535", "security-metadata-65536", "security-metadata-65537",
      "security-total-9663676415", "security-total-9663676416", "security-total-9663676417",
      "tombstone-2047", "tombstone-2048", "tombstone-2049",
      "compact-before-ack-retention", "compact-at-ack-retention", "compact-after-ack-retention",
      "compact-before-operation-expiry", "compact-at-operation-expiry", "compact-after-operation-expiry",
      "compact-before-replace", "compact-after-tombstone-index-before-counter",
      "compact-after-counter-before-payload-delete", "compact-after-payload-delete",
      "ack-first", "ack-idempotent-retry", "ack-conflict", "ack-clock-rollback",
      "ack-before-commit", "ack-after-retention-before-metadata", "ack-after-metadata-before-counter",
      "ack-after-counter-before-delivery",
      "restart-active", "restart-finalized", "restart-abandoned", "restart-tombstone",
      "restart-counter-mismatch", "restart-metadata-mismatch", "restart-index-mismatch",
      "restart-policy-registry-mismatch",
      "duplicate-after-tombstone", "changed-digest-after-tombstone", "duplicate-after-restart",
    ]) expect(ids.has(id)).toBe(true);
    for (const vector of vectors) {
      expect(Object.keys(vector).sort()).toEqual([
        "expected_decision", "expected_state", "metadata_jcs_b64", "metadata_jcs_byte_length",
        "persisted_projection", "semantic_input", "vector_id",
      ]);
      const projection = vector.persisted_projection;
      const bytes = canonicalBytes(projection);
      const expected = Buffer.from(vector.metadata_jcs_b64 as string, "base64");
      expect(Buffer.from(bytes)).toEqual(expected);
      expect(expected.toString("base64")).toBe(vector.metadata_jcs_b64);
      expect(String(bytes.byteLength)).toBe(vector.metadata_jcs_byte_length);
      expect(vector.metadata_jcs_b64).toMatch(/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/);
    }
  });

  it("keeps replay policy classification total and quota descriptors exact", () => {
    const registry = readJson("../registries/v1/replay-policies.json");
    const classes = registry.policy_classes as Array<Record<string, unknown>>;
    const classification = registry.message_classification as Array<Record<string, unknown>>;
    expect(classes.map((entry) => entry.class_id).sort()).toEqual(["operation_security_ledger", "task5_default"]);
    // Task 7 freezes 27 cumulative rows; the approved Task 9 event pair then
    // extends the same shared task5_default classification to 29.
    expect(classification).toHaveLength(29);
    expect(new Set(classification.map((entry) => entry.message_type)).size).toBe(29);
    const security = classes.find((entry) => entry.class_id === "operation_security_ledger");
    expect(security).toMatchObject({
      retention_rule_id: "retain_until_max_operation_expires_at_or_bridge_ack_at_plus_2592000_seconds_v1",
      max_rows_per_space: "16384",
      max_retained_bytes_per_space: "9663676416",
      receipt_reservation_bytes: "262144",
      intent_metadata_ceiling_bytes: "65536",
      tombstone_metadata_ceiling_bytes: "2048",
    });
    expect((security?.message_types as string[]).sort()).toEqual(
      classification.filter((entry) => entry.class_id === "operation_security_ledger").map((entry) => entry.message_type).sort(),
    );
    const ordinary = classes.find((entry) => entry.class_id === "task5_default");
    expect(ordinary).toMatchObject({
      retention_rule_id: "retain_until_max_expires_at_or_admitted_at_plus_86400_seconds_v1",
      max_rows_per_space: "4096",
      max_retained_bytes_per_space: "67108864",
      receipt_reservation_bytes: "16384",
      intent_metadata_ceiling_bytes: null,
      tombstone_metadata_ceiling_bytes: null,
    });
    expect((ordinary?.message_types as string[]).sort()).toEqual(
      classification.filter((entry) => entry.class_id === "task5_default").map((entry) => entry.message_type).sort(),
    );
  });
});
