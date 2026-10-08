import { describe, expect, it } from "vitest";
// The Hermes adapter of this name is the legacy TypeScript fixture; the product
// Hermes plugin is the native Python package and does not implement these
// shared provider operations.
import { createHermesAdapter } from "../../legacy/integrations/hermes-v1/adapter.js";
import { fixtureBinding, fixtureContext, fixtureZeroRetentionEvidence } from "./fixtures.js";

describe("legacy Hermes TypeScript notification fixture", () => {
  it("returns a bounded notification result and accepted assistant message", async () => {
    const options = { context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence(), onDemand: async () => [{
      kind: "upsert" as const,
      recordId: "notice-1",
      packageId: "com.example.mail",
      title: null,
      content: null,
    }] };
    const hermes = createHermesAdapter(options);
    await hermes.pair(fixtureBinding());
    await expect(hermes.queryNotifications({ toolCallId: "call-1", deviceId: "device-a", mode: "on_demand", limit: 1 }))
      .resolves.toEqual([{ kind: "upsert", recordId: "notice-1", packageId: "com.example.mail", title: null, content: null }]);
    await expect(hermes.sendAssistantMessage({ messageId: "message-1", text: "hello" }))
      .resolves.toMatchObject({ messageId: "message-1", status: "accepted" });
  });

  it("does not route a notification event across workspace, session, or job", async () => {
    const adapter = createHermesAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await adapter.pair(fixtureBinding());
    const { subscriptionId } = await adapter.subscribeNotifications({ deviceId: "device-a" });
    const event = {
      eventId: "event-1",
      subscriptionId,
      binding: { ...fixtureContext() },
      record: { kind: "loss_marker" as const, recordId: "gap-1", packageId: null, title: null, content: null },
    };
    await expect(adapter.receiveNotificationEvent({ ...event, binding: { ...event.binding, jobId: "job-other" } }))
      .rejects.toMatchObject({ code: "EVENT_BINDING_MISMATCH" });
    await expect(adapter.receiveNotificationEvent(event))
      .rejects.toMatchObject({ code: "EVENT_NOT_FOUND" });
  });
});
