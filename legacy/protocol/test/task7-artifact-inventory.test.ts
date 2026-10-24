/// <reference types="node" />

import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

type Inventory = {
  artifact_set: string;
  status: string;
  decision_status: Record<string, string>;
  present: string[];
  pending: string[];
  non_claims: string[];
};

const readInventory = (): Inventory => JSON.parse(
  readFileSync(new URL("../test-only/task7-independent-artifacts-v1.json", import.meta.url), "utf8"),
) as Inventory;

describe("Task 7 independent artifact inventory", () => {
  it("is a closed, honest inventory and only marks checked-in artifacts present", () => {
    const inventory = readInventory();
    expect(Object.keys(inventory).sort()).toEqual([
      "artifact_set", "decision_status", "non_claims", "pending", "present", "status",
    ]);
    expect(inventory.artifact_set).toBe("task7-independent-v1");
    expect(inventory.status).toBe("APPROVED_REFERENCE_ARTIFACTS");
    expect(Object.keys(inventory.decision_status).sort()).toEqual(["D1", "D2", "D3", "D4"]);
    expect(Object.values(inventory.decision_status).every((value) => value === "APPROVED_USER_CONFIRMED")).toBe(true);
    expect(new Set([...inventory.present, ...inventory.pending]).size).toBe(inventory.present.length + inventory.pending.length);
    expect(inventory.pending).toEqual([]);
    for (const path of inventory.present) expect(existsSync(new URL(`../../${path}`, import.meta.url))).toBe(true);
    for (const path of inventory.pending.filter((value) => !value.includes("#"))) {
      expect(existsSync(new URL(`../../${path}`, import.meta.url))).toBe(false);
    }
    expect(inventory.non_claims.length).toBeGreaterThanOrEqual(3);
    expect(inventory.non_claims.join("\n")).toMatch(/D1-D4/);
    expect(inventory.non_claims.join("\n")).toMatch(/replay-policy/);
    expect(inventory.present).toContain(".superpowers/sdd/2026-08-08-p0a-protocol-security-model/task-7-product-decisions.md");
    expect(inventory.present).toContain("docs/protocol/v1/operations.md");
    expect(inventory.present).toContain("protocol/schemas/v1/replay-policies-registry.schema.json");
    expect(inventory.present).toContain("protocol/registries/v1/replay-policies.json");
    expect(inventory.present).toContain("protocol/test-only/replay/v1/compaction-recovery-vectors.json");
    expect(inventory.present).toEqual(expect.arrayContaining([
      "protocol/test-only/replay/v1/intent-metadata-device-v1.json",
      "protocol/test-only/replay/v1/intent-metadata-adapter-v1.json",
      "protocol/test-only/replay/v1/intent-metadata-adapter-empty-scope-v1.json",
      "protocol/test-only/operation/v1/receipt-replay-vectors.json",
      "protocol/registries/v1/messages.json#Task7-13-rows",
    ]));
  });
});
