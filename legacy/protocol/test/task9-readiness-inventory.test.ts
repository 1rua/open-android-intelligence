/// <reference types="node" />

import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

type Gap = {
  id: string;
  state: "BLOCKED";
  required_artifacts: string[];
};

type Inventory = {
  artifact_set: string;
  status: string;
  decision_status: Record<string, string>;
  present: string[];
  missing: string[];
  blocked_contracts: Gap[];
  non_claims: string[];
};

const readInventory = (): Inventory => JSON.parse(
  readFileSync(new URL("../test-only/task9-readiness-gap-inventory-v1.json", import.meta.url), "utf8"),
) as Inventory;

describe("Task 9 readiness gap inventory", () => {
  it("records approved lifetime/replay decisions while keeping unimplemented runtime claims explicit", () => {
    const inventory = readInventory();

    expect(Object.keys(inventory).sort()).toEqual([
      "artifact_set", "blocked_contracts", "decision_status", "missing", "non_claims", "present", "status",
    ]);
    expect(inventory.artifact_set).toBe("task9-event-readiness-v1");
    expect(inventory.status).toBe("CONTRACT_IMPLEMENTATION_IN_PROGRESS");
    expect(inventory.decision_status).toEqual({
      event_lifetime: "APPROVED_PRODUCT_SECURITY_OWNER",
      event_replay_policy: "APPROVED_PRODUCT_SECURITY_OWNER",
    });
    expect(inventory.blocked_contracts.map((gap) => gap.id)).toEqual([
      "T9-G1-TRANSPORT_ADMISSION",
      "T9-G2-REPLAY_CLASSIFICATION",
      "T9-G3-CLOSED_WIRE_CONTRACT",
      "T9-G4-CAPTURE_AUTHORITY",
      "T9-G5-ACK_DURABILITY",
      "T9-G6-SERVER_ROUTING",
      "T9-G7-PRE_REPLAY_VECTORS",
    ]);
    expect(inventory.blocked_contracts.every((gap) => gap.state === "BLOCKED" && gap.required_artifacts.length > 0)).toBe(true);
    expect(new Set([...inventory.present, ...inventory.missing]).size).toBe(inventory.present.length + inventory.missing.length);
    expect(inventory.present.some((path) => path.startsWith(".superpowers/"))).toBe(false);
    for (const path of inventory.present) expect(existsSync(new URL(`../../${path}`, import.meta.url))).toBe(true);
    for (const path of inventory.missing) expect(existsSync(new URL(`../../${path}`, import.meta.url))).toBe(false);
    expect(inventory.non_claims.join("\n")).toMatch(/not a production event implementation/i);
    expect(inventory.non_claims.join("\n")).toMatch(/approved lifetime/i);
    expect(inventory.non_claims.join("\n")).toMatch(/task5_default/i);
  });
});
