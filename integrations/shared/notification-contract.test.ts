import { describe, expect, it } from "vitest";
// The Hermes adapter of this name is the legacy TypeScript fixture; the product
// Hermes plugin is the native Python package and does not implement these
// shared provider operations.
import { createHermesAdapter } from "../../legacy/integrations/hermes-v1/adapter.js";
import { createOpenClawAdapter } from "../openclaw/adapter.js";
import { fixtureBinding, fixtureContext, fixtureZeroRetentionEvidence } from "./fixtures.js";

const normalized = (value: unknown): string => JSON.stringify(value);

describe("Hermes/OpenClaw normalized notification and assistant contract", () => {
  it("keeps on-demand query and text response byte-equivalent", async () => {
    const options = { context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence(), onDemand: async () => [{
      kind: "upsert" as const,
      recordId: "notice-1",
      packageId: "com.example.mail",
      title: null,
      content: null,
    }] };
    const hermes = createHermesAdapter(options);
    const openclaw = createOpenClawAdapter(options);
    await hermes.pair(fixtureBinding());
    await openclaw.pair(fixtureBinding());
    expect(normalized(await hermes.queryNotifications({ toolCallId: "call-1", deviceId: "device-a", mode: "on_demand", limit: 1 })))
      .toBe(normalized(await openclaw.queryNotifications({ toolCallId: "call-1", deviceId: "device-a", mode: "on_demand", limit: 1 })));
    expect(normalized(await hermes.sendAssistantMessage({ messageId: "message-1", text: "hello" })))
      .toBe(normalized(await openclaw.sendAssistantMessage({ messageId: "message-1", text: "hello" })));
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
