import { describe, expect, it } from "vitest";
import { createEnvelopeAuthorizationGate } from "../src/authorization.js";
import { evaluateDeviceAccess, evaluateStoredQuery } from "../src/authorization.js";

const context = {
  kind: "device", credentialId: "credential", tenantId: "tenant", humanPrincipalId: "human",
  deviceId: "device", pairingGeneration: 1n, connectionGeneration: 1n,
  direction: "app-to-bridge",
} as never;

describe("capability registry authorization boundary", () => {
  it("allows a known capability only when the immutable ceiling contains it", () => {
    const gate = createEnvelopeAuthorizationGate({ scopeCeiling: ["notifications.metadata"] });
    expect(gate.evaluate("notification_query", { capability: "notifications.metadata", filter: { fields: ["metadata"] } }, context)).toEqual({ allowed: true });
    expect(gate.evaluate("notification_query", { capability: "notifications.content" }, context)).toEqual({ allowed: false, denial: "SCOPE_DENIED" });
  });

  it("fails closed for syntactically valid but unknown capability names before policy evaluation", () => {
    const gate = createEnvelopeAuthorizationGate({ scopeCeiling: ["notifications.unknown"] });
    expect(gate.evaluate("notification_query", { capability: "notifications.unknown" }, context)).toEqual({ allowed: false, denial: "SCOPE_DENIED" });
  });

  it("binds notification grants to the closed package/field filter schema", () => {
    const gate = createEnvelopeAuthorizationGate({ scopeCeiling: ["notifications.metadata", "notifications.content"] });
    expect(gate.evaluate("notification_query", {
      capability: "notifications.metadata",
      filter: { packages: ["com.example.mail"], fields: ["metadata"] },
    }, context)).toEqual({ allowed: true });
    expect(gate.evaluate("notification_query", {
      capability: "notifications.metadata",
      filter: { packages: ["com.example.mail", "com.example.chat"], fields: ["metadata"] },
    }, context)).toEqual({ allowed: false, denial: "SCOPE_DENIED" });
    expect(gate.evaluate("notification_query", {
      capability: "notifications.content",
      filter: { fields: ["content"] },
    }, context)).toEqual({ allowed: true });
  });

  it("checks the real-time device conjunction and exact revision", () => {
    const current = {
      lifecycle: "active" as const, activePairing: true, enrollmentScopeCeiling: ["notifications.metadata"],
      localConsent: new Set(["notifications.metadata"]), systemPermission: new Set(["notifications.metadata"]),
      backendAvailable: new Set(["B0"]), revision: { pairingGeneration: 1n, authorizationEpoch: 2n, scopeRevisions: new Map([["notifications.metadata", 3n]]) },
    };
    const requester = { tenantId: "tenant", humanPrincipalId: "human", agentPrincipalId: "agent", agentInstanceId: "instance", workspaceId: "workspace", sessionId: "session", deviceId: "device", agentScopeCeiling: ["notifications.metadata"] };
    expect(evaluateDeviceAccess({ binding: context, requester, capability: "notifications.metadata", requiredRevision: current.revision, current })).toEqual({ allowed: true });
    expect(evaluateDeviceAccess({ binding: context, requester, capability: "notifications.metadata", requiredRevision: { ...current.revision, authorizationEpoch: 1n }, current })).toEqual({ allowed: false, reason: "REVISION_MISMATCH" });
  });

  it("evaluates stored records independently from current device permission/backend", () => {
    const filterHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    const key = { tenantId: "tenant", humanPrincipalId: "human", deviceId: "device", capability: "notifications.metadata", filterHash };
    const requester = { tenantId: "tenant", humanPrincipalId: "human", agentPrincipalId: "agent", agentInstanceId: "instance", workspaceId: "workspace", sessionId: "session", deviceId: "device", agentScopeCeiling: [] };
    const grant = { key, grantRevision: 1n, bridgeEffectiveState: "effective" as const, deviceUiState: "effective" as const, lastProposalDigest: null };
    const record = { ...key, sourceId: "record", expiresAt: "2026-01-02T00:00:00.000Z", tombstoned: false };
    expect(evaluateStoredQuery({ requester, key, grant, record, clock: { wallNow: () => new Date("2026-01-01T00:00:00.000Z"), monotonicNowMs: () => 0n } })).toEqual({ allowed: true });
  });
});
