import { loadCapabilityRegistry, type CapabilityDefinition, type CapabilityRisk, type CapabilitySensitivity } from "./capability-manifest.js";

export type PolicyApproval = "continuous" | "per_operation" | "per_operation_on_device";
export interface SmsConfirmationBinding {
  readonly recipient: string;
  readonly body: string;
  readonly subscriptionId: string;
  readonly path: "direct" | "system_ui";
  readonly operationId: string;
  readonly expiresAt: string;
  readonly revision: unknown;
}
export type PolicyDecision =
  | { readonly allowed: true; readonly approval: PolicyApproval; readonly confirmationBinding?: SmsConfirmationBinding }
  | { readonly allowed: false; readonly reason: "POLICY_BLOCKED" };

export interface RiskPolicyInput {
  readonly capability: string | CapabilityDefinition;
  readonly deterministicRisk?: CapabilityRisk;
  readonly dataSensitivityCorrection?: CapabilitySensitivity | "sensitive" | "control" | "none";
  readonly userOverride?: PolicyApproval;
  readonly selectedBackend?: string;
  readonly enhancedBackendSessionActive?: boolean;
  readonly sms?: SmsConfirmationBinding;
}

const risks: readonly CapabilityRisk[] = ["L0", "L1", "L2", "L3", "L4"];
const approvals: readonly PolicyApproval[] = ["continuous", "per_operation", "per_operation_on_device"];
const rank = <T>(values: readonly T[], value: T): number => values.indexOf(value);
const correctedRisk = (value: RiskPolicyInput["dataSensitivityCorrection"]): CapabilityRisk => value === "control" ? "L4" : value === "sensitive" ? "L3" : value === "content" ? "L2" : value === "metadata" ? "L1" : "L0";

export function evaluateRiskPolicy(input: RiskPolicyInput): PolicyDecision {
  const registry = loadCapabilityRegistry();
  const capability = typeof input.capability === "string" ? registry.resolve(input.capability) : input.capability;
  if (capability === null || capability === undefined) return { allowed: false, reason: "POLICY_BLOCKED" };
  const risk = Math.max(rank(risks, capability.riskFloor), input.deterministicRisk === undefined ? 0 : rank(risks, input.deterministicRisk), rank(risks, correctedRisk(input.dataSensitivityCorrection)));
  if (risk >= rank(risks, "L4")) return { allowed: false, reason: "POLICY_BLOCKED" };
  if (input.selectedBackend === "B1" && input.enhancedBackendSessionActive !== true) return { allowed: false, reason: "POLICY_BLOCKED" };
  const minimum = capability.minimumApproval === "per_operation_on_device" ? "per_operation_on_device" : capability.minimumApproval === "per_operation" ? "per_operation" : "continuous";
  const override = input.userOverride ?? "continuous";
  const approval = approvals[Math.max(rank(approvals, minimum), rank(approvals, override))] ?? minimum;
  if (capability.scope === "sms.send") {
    return input.sms === undefined
      ? { allowed: true, approval: "per_operation_on_device" }
      : { allowed: true, approval: "per_operation_on_device", confirmationBinding: Object.freeze({ ...input.sms }) };
  }
  return { allowed: true, approval };
}
