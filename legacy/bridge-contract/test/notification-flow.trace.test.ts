import { describe, expect, it } from "vitest";
import {
  FakeBridge,
  createPairedBinding,
  type NotificationRecord,
} from "../src/fake-bridge.js";

const alice = createPairedBinding({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  bridgeFingerprint: "bridge-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 1n,
});
const bob = createPairedBinding({
  tenantId: "tenant-a",
  humanPrincipalId: "human-b",
  deviceId: "device-b",
  bridgeFingerprint: "bridge-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 1n,
});

const record: NotificationRecord = {
  kind: "upsert",
  recordId: "mail-1",
  packageId: "com.example.mail",
  title: "SECRET_TITLE",
  content: "SECRET_BODY",
};

describe("deterministic notification trace", () => {
  it("redelivers an unacknowledged auto-send event after restart, then stops after ACK", async () => {
    const bridge = new FakeBridge();
    await bridge.open(alice);
    const subscription = await bridge.subscribe({
      subscriptionId: "sub-a",
      tenantId: "tenant-a",
      humanPrincipalId: "human-a",
      deviceId: "device-a",
      agentInstanceId: "agent-a",
      workspaceId: "workspace-a",
      sessionId: "session-a",
    });

    const first = await bridge.publishAutoSend(subscription.subscriptionId, record);
    expect(first.eventId).toBe("event-1");
    bridge.dropNextAcknowledgement();
    expect(await bridge.acknowledgeEvent(subscription.subscriptionId, first.eventId)).toBe(false);
    const restarted = bridge.restart();
    const replay = await restarted.recoverUnacknowledged(subscription.subscriptionId);
    expect(replay).toEqual([first]);
    expect(await restarted.acknowledgeEvent(subscription.subscriptionId, first.eventId)).toBe(true);
    expect(await restarted.recoverUnacknowledged(subscription.subscriptionId)).toEqual([]);
  });

  it("rejects cross-user subscription and records only content-free diagnostics", async () => {
    const bridge = new FakeBridge();
    await bridge.open(alice);
    await expect(bridge.subscribe({
      subscriptionId: "sub-bad",
      tenantId: "tenant-a",
      humanPrincipalId: "human-b",
      deviceId: "device-b",
      agentInstanceId: "agent-a",
      workspaceId: "workspace-a",
      sessionId: "session-b",
    })).rejects.toMatchObject({ code: "SUBSCRIPTION_BINDING_MISMATCH" });

    await bridge.subscribe({
      subscriptionId: "sub-a",
      tenantId: "tenant-a",
      humanPrincipalId: "human-a",
      deviceId: "device-a",
      agentInstanceId: "agent-a",
      workspaceId: "workspace-a",
      sessionId: "session-a",
    });
    await bridge.publishAutoSend("sub-a", record);
    bridge.revokePolicy("device-a");
    await expect(bridge.publishAutoSend("sub-a", record)).rejects.toMatchObject({ code: "POLICY_REVOKED" });

    const diagnostics = JSON.stringify(bridge.trace());
    expect(diagnostics).not.toContain("SECRET_TITLE");
    expect(diagnostics).not.toContain("SECRET_BODY");
    expect(diagnostics).not.toContain("content");
    expect(diagnostics).not.toContain("title");
    expect(bridge.trace().some((entry) => entry.kind === "policy_revoked")).toBe(true);
  });

  it("does not claim a new operation when on-demand collection is retried after reconnect", async () => {
    const bridge = new FakeBridge();
    await bridge.open(alice);
    let captures = 0;
    bridge.setOnDemandCapture(async () => {
      captures += 1;
      return [record];
    });
    const first = await bridge.queryNotifications({
      operationId: "op-reconnect",
      tenantId: "tenant-a",
      humanPrincipalId: "human-a",
      deviceId: "device-a",
      agentInstanceId: "agent-a",
      workspaceId: "workspace-a",
      sessionId: "session-a",
      mode: "on_demand",
    });
    await bridge.reconnect(alice);
    const retry = await bridge.queryNotifications({
      operationId: "op-reconnect",
      tenantId: "tenant-a",
      humanPrincipalId: "human-a",
      deviceId: "device-a",
      agentInstanceId: "agent-a",
      workspaceId: "workspace-a",
      sessionId: "session-a",
      mode: "on_demand",
    });
    expect(retry).toEqual(first);
    expect(captures).toBe(1);
    expect(bridge.operationClaims()).toEqual([{ operationId: "op-reconnect", claims: 1 }]);
  });
});
