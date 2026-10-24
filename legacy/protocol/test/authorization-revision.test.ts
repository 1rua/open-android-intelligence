import { describe, expect, it } from "vitest";
import {
  applyRevisionEvent,
  createAuthorizationRevisionState,
  mintAuthenticatedLocalUserTransition,
  type AuthorizationRevisionState,
} from "../src/authorization-revision.js";

const initial = (): AuthorizationRevisionState => createAuthorizationRevisionState({
  deviceId: "device",
  pairingGeneration: 7n,
  authorizationEpoch: 10n,
  lifecycle: "active",
  activePairing: true,
  scopeRevisions: { "notifications.content": 3n },
});

describe("authorization revision reducer", () => {
  it("advances epoch for pause and only an authenticated local fact can resume", () => {
    const paused = applyRevisionEvent(initial(), { kind: "pause" });
    expect(paused).toMatchObject({ lifecycle: "paused", activePairing: false, authorizationEpoch: 11n });
    expect(() => applyRevisionEvent(paused, { kind: "resume", localUser: { deviceId: "device", pairingGeneration: 7n, transitionId: "forged", kind: "resume", authenticatedAt: "2026-01-01T00:00:00.000Z" } as never })).toThrowError("AUTH_FAILED");
    const local = mintAuthenticatedLocalUserTransition({ deviceId: "device", pairingGeneration: 7n, transitionId: "resume-1", authenticatedAt: "2026-01-01T00:00:00.000Z" });
    const resumed = applyRevisionEvent(paused, { kind: "resume", localUser: local });
    expect(resumed).toMatchObject({ lifecycle: "active", activePairing: true, authorizationEpoch: 12n });
    expect(() => applyRevisionEvent(resumed, { kind: "resume", localUser: local })).toThrowError("AUTH_FAILED");
  });

  it("changes only the named scope revision for a policy transition", () => {
    const next = applyRevisionEvent(initial(), { kind: "scope_policy_revoke", capability: "notifications.content" });
    expect(next.authorizationEpoch).toBe(11n);
    expect(next.scopeRevisions.get("notifications.content")).toBe(4n);
    expect(next.scopeRevisions.get("notifications.metadata")).toBeUndefined();
  });

  it("requires a new pairing to recover a revoked device", () => {
    const revoked = applyRevisionEvent(initial(), { kind: "emergency_stop" });
    const permanentlyRevoked = applyRevisionEvent(revoked, { kind: "new_pairing", nextPairingGeneration: 8n });
    expect(permanentlyRevoked).toMatchObject({ pairingGeneration: 8n, lifecycle: "active", activePairing: true });
    expect(() => applyRevisionEvent(permanentlyRevoked, { kind: "new_pairing", nextPairingGeneration: 10n })).toThrowError("INVALID_STATE_TRANSITION");
  });
});
