import { describe, expect, it } from "vitest";
import {
  CONNECTION_FENCED,
  FakeBridge,
  createPairedBinding,
  type NotificationQueryRequest,
} from "../src/fake-bridge.js";

const binding = () => createPairedBinding({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  bridgeFingerprint: "bridge-a",
  pairingGeneration: 7n,
  policyAttestationRevision: 3n,
});

const request = (operationId: string, overrides: Partial<NotificationQueryRequest> = {}): NotificationQueryRequest => ({
  operationId,
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  agentInstanceId: "agent-a",
  workspaceId: "workspace-a",
  sessionId: "session-a",
  mode: "on_demand",
  ...overrides,
});

describe("FakeBridge paired binding and operation trace", () => {
  it("opens only from a paired binding and fences the old session on reconnect", async () => {
    const bridge = new FakeBridge();
    const first = await bridge.open(binding());
    expect(first.connectionGeneration).toBe(1n);
    expect("host" in first).toBe(false);
    expect("port" in first).toBe(false);

    const second = await bridge.reconnect(binding());
    expect(second.connectionGeneration).toBe(2n);
    await expect(first.sendControl("frame-1")).rejects.toMatchObject({ code: CONNECTION_FENCED });
    await expect(second.sendControl("frame-2")).resolves.toBeUndefined();
    expect(bridge.trace().map((entry) => entry.kind)).toEqual([
      "binding_accepted",
      "session_opened",
      "session_fenced",
      "session_opened",
      "control_sent",
    ]);
  });

  it("claims one operation for a duplicate retry and returns the original result", async () => {
    const bridge = new FakeBridge();
    await bridge.open(binding());
    let captures = 0;
    bridge.setOnDemandCapture(async () => {
      captures += 1;
      return [{
        kind: "upsert",
        recordId: "record-1",
        packageId: "com.example.mail",
        title: "untrusted title",
        content: null,
      }];
    });

    const first = await bridge.queryNotifications(request("op-1"));
    const retry = await bridge.queryNotifications(request("op-1"));
    expect(first).toEqual(retry);
    expect(captures).toBe(1);
    expect(bridge.operationClaims()).toEqual([{ operationId: "op-1", claims: 1 }]);
  });

  it("rejects an operation identity reused by a different session", async () => {
    const bridge = new FakeBridge();
    await bridge.open(binding());
    await bridge.queryNotifications(request("op-2"));
    await expect(bridge.queryNotifications(request("op-2", { sessionId: "session-other" })))
      .rejects.toMatchObject({ code: "OPERATION_IDENTITY_MISMATCH" });
  });

  it("applies the closed package/field filter before query results or auto-send events", async () => {
    const bridge = new FakeBridge();
    await bridge.open(binding());
    bridge.setOnDemandCapture(async () => [
      { kind: "upsert", recordId: "mail", packageId: "com.mail", title: "title", content: "body" },
      { kind: "upsert", recordId: "chat", packageId: "com.chat", title: "chat", content: "message" },
    ]);
    await expect(bridge.queryNotifications(request("filtered", {
      filter: { packages: ["com.mail"], fields: ["metadata"] },
    }))).resolves.toEqual([{ kind: "upsert", recordId: "mail", packageId: "com.mail", title: null, content: null }]);
    await expect(bridge.queryNotifications(request("bad-filter", {
      filter: { packages: ["com.mail", "com.chat"], fields: ["metadata"] },
    }))).rejects.toMatchObject({ code: "FILTER_INVALID" });
    await bridge.subscribe({
      subscriptionId: "sub-filtered", tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a",
      agentInstanceId: "agent-a", workspaceId: "workspace-a", sessionId: "session-a",
      filter: { packages: ["com.mail"], fields: ["content"] },
    });
    await expect(bridge.publishAutoSend("sub-filtered", { kind: "upsert", recordId: "mail", packageId: "com.mail", title: "title", content: "body" })).resolves.toEqual({ eventId: "event-1" });
  });
});
