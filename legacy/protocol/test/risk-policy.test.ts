import { describe, expect, it } from "vitest";
import { evaluateRiskPolicy } from "../src/risk-policy.js";

describe("risk policy", () => {
  it("never lowers SMS per-item confirmation", () => {
    expect(evaluateRiskPolicy({ capability: "sms.send", userOverride: "continuous" })).toMatchObject({ allowed: true, approval: "per_operation_on_device" });
  });
  it("denies L4 and B1 without an enhanced session", () => {
    expect(evaluateRiskPolicy({ capability: "ui.control.typed", deterministicRisk: "L4", selectedBackend: "B0" })).toEqual({ allowed: false, reason: "POLICY_BLOCKED" });
    expect(evaluateRiskPolicy({ capability: "root.actions.foo", selectedBackend: "B1", enhancedBackendSessionActive: false })).toEqual({ allowed: false, reason: "POLICY_BLOCKED" });
  });
});
