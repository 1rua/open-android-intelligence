import { describe, expect, it } from "vitest";
// The Hermes adapter of this name is the legacy TypeScript fixture; the product
// Hermes plugin is the native Python package and does not implement these
// shared provider operations.
import { createHermesAdapter, HERMES_PLUGIN_MANIFEST } from "../../legacy/integrations/hermes-v1/adapter.js";
import { createOpenClawAdapter, OPENCLAW_PLUGIN_MANIFEST } from "../openclaw/adapter.js";
import {
  FROZEN_SMS_TOOLS,
  FROZEN_PROVIDER_TOOLS,
  createFakeAdapter,
  fixtureBinding,
  fixtureContext,
  fixtureZeroRetentionEvidence,
  type SmsRecord,
} from "./adapter.js";

const smsRecord = (overrides: Partial<SmsRecord> = {}): SmsRecord => ({
  recordId: "sms:42",
  senderAddress: "+8613800000000",
  threadId: "9",
  messageAtEpochMs: 1_700_000_000_000n,
  observedAtEpochMs: 1_700_000_000_100n,
  read: false,
  subscriptionId: 1,
  body: "first line\nsecond line",
  sourceEpoch: 1n,
  cursorProviderId: 42n,
  captureRevision: 7n,
  policyRevision: 7n,
  ...overrides,
});

const MAX_SMS_PROVIDER_ID = 9_223_372_036_854_775_807n;

const bytes = (value: unknown): string => JSON.stringify(value, (_key, current) =>
  typeof current === "bigint" ? current.toString() : current);

describe("shared Hermes/OpenClaw SMS contract", () => {
  it("preserves notification discovery while adding exactly three frozen SMS tools", () => {
    const expectedProviderTools = [
      "mobile.notifications.query",
      "mobile.notifications.subscribe",
      "mobile.notifications.unsubscribe",
      "mobile.sms.query",
      "mobile.sms.subscribe",
      "mobile.sms.unsubscribe",
    ];
    expect(FROZEN_SMS_TOOLS).toEqual([
      "mobile.sms.query",
      "mobile.sms.subscribe",
      "mobile.sms.unsubscribe",
    ]);
    expect(Object.isFrozen(FROZEN_SMS_TOOLS)).toBe(true);
    expect(FROZEN_PROVIDER_TOOLS).toEqual(expectedProviderTools);
    expect(Object.isFrozen(FROZEN_PROVIDER_TOOLS)).toBe(true);
    expect(HERMES_PLUGIN_MANIFEST.tools).toEqual(expectedProviderTools);
    expect(OPENCLAW_PLUGIN_MANIFEST.tools).toEqual(expectedProviderTools);
    expect(HERMES_PLUGIN_MANIFEST.tools.filter((name) => name.startsWith("mobile.sms."))).toEqual(FROZEN_SMS_TOOLS);
    expect(OPENCLAW_PLUGIN_MANIFEST.tools.filter((name) => name.startsWith("mobile.sms."))).toEqual(FROZEN_SMS_TOOLS);
  });

  it("returns byte-equivalent complete SMS query records for Hermes and OpenClaw", async () => {
    const options = {
      context: fixtureContext(),
      zeroRetention: fixtureZeroRetentionEvidence(),
      onDemandSms: async () => [smsRecord({ body: "" }), smsRecord({
        recordId: "sms:43",
        cursorProviderId: 43n,
        messageAtEpochMs: 1_700_000_000_001n,
        body: "complete untruncated body\nwith a second line",
      })],
    };
    const hermes = createHermesAdapter(options);
    const openclaw = createOpenClawAdapter(options);
    await hermes.pair(fixtureBinding());
    await openclaw.pair(fixtureBinding());

    const input = { toolCallId: "sms-call-1", deviceId: "device-a", limit: 10_000 };
    const hermesResult = await hermes.querySms(input);
    const openclawResult = await openclaw.querySms(input);
    expect(bytes(hermesResult)).toBe(bytes(openclawResult));
    expect(hermesResult.map((entry) => entry.body)).toEqual(["", "complete untruncated body\nwith a second line"]);
    expect(Object.keys(hermesResult[0]!).sort()).toEqual([
      "body", "captureRevision", "cursorProviderId", "messageAtEpochMs", "observedAtEpochMs",
      "policyRevision", "read", "recordId", "senderAddress", "sourceEpoch", "subscriptionId", "threadId",
    ]);
  });

  it("uses runtime tool-call identity and rejects operation, identity, model, capability, and MMS input", async () => {
    const adapter = createFakeAdapter({ context: fixtureContext(), zeroRetention: fixtureZeroRetentionEvidence() });
    await adapter.pair(fixtureBinding());

    for (const forbidden of [
      { operationId: "model-operation" },
      { tenantId: "tenant-other" },
      { sessionId: "session-other" },
      { modelId: "model-other" },
    ]) {
      await expect(adapter.querySms({ toolCallId: "sms-forged", deviceId: "device-a", limit: 1, ...forbidden } as never))
        .rejects.toMatchObject({ code: "MODEL_IDENTITY_FIELD" });
    }
    await expect(adapter.querySms({ toolCallId: "sms-capability", deviceId: "device-a", limit: 1, capability: "shell" } as never))
      .rejects.toMatchObject({ code: "REQUEST_FIELDS_INVALID" });
    await expect(adapter.querySms({ toolCallId: "sms-mms", deviceId: "device-a", limit: 1, attachments: [] } as never))
      .rejects.toMatchObject({ code: "REQUEST_FIELDS_INVALID" });
    await expect(adapter.invokeTool("mobile.sms.shell", {})).rejects.toMatchObject({ code: "UNKNOWN_TOOL" });
    await expect(adapter.querySms({ toolCallId: "sms-too-many", deviceId: "device-a", limit: 10_001 }))
      .rejects.toMatchObject({ code: "LIMIT_INVALID" });
  });

  it("rejects non-SMS IDs and cursor fields that disagree with adapter records", async () => {
    let supplied = smsRecord();
    const adapter = createFakeAdapter({
      context: fixtureContext(),
      zeroRetention: fixtureZeroRetentionEvidence(),
      onDemandSms: async () => [supplied],
    });
    await adapter.pair(fixtureBinding());
    const malformed = [
      smsRecord({ recordId: "mms:42" }),
      smsRecord({ recordId: "42" }),
      smsRecord({ recordId: "sms:0", cursorProviderId: 0n }),
      smsRecord({ recordId: "sms:01", cursorProviderId: 1n }),
      smsRecord({ cursorProviderId: 43n }),
      smsRecord({ recordId: "sms:9223372036854775808", cursorProviderId: 9_223_372_036_854_775_808n }),
    ];
    for (const [index, candidate] of malformed.entries()) {
      supplied = candidate;
      await expect(adapter.querySms({ toolCallId: `sms-malformed-${index}`, deviceId: "device-a", limit: 1 }))
        .rejects.toMatchObject({ code: "SMS_RECORD_INVALID" });
    }
  });

  it("accepts Long.MAX_VALUE SMS provider IDs without narrowing independent u64 fields", async () => {
    const adapter = createFakeAdapter({
      context: fixtureContext(),
      zeroRetention: fixtureZeroRetentionEvidence(),
      onDemandSms: async () => [smsRecord({
        recordId: `sms:${MAX_SMS_PROVIDER_ID}`,
        cursorProviderId: MAX_SMS_PROVIDER_ID,
        sourceEpoch: 18_446_744_073_709_551_615n,
        messageAtEpochMs: 18_446_744_073_709_551_615n,
        observedAtEpochMs: 18_446_744_073_709_551_615n,
        captureRevision: 18_446_744_073_709_551_615n,
        policyRevision: 18_446_744_073_709_551_615n,
      })],
    });
    await adapter.pair(fixtureBinding());

    await expect(adapter.querySms({ toolCallId: "sms-long-max", deviceId: "device-a", limit: 1 }))
      .resolves.toEqual([expect.objectContaining({ recordId: `sms:${MAX_SMS_PROVIDER_ID}` })]);
  });

  it("keeps SMS query idempotent and binds auto-send delivery to its paired session", async () => {
    let reads = 0;
    const adapter = createFakeAdapter({
      context: fixtureContext(),
      zeroRetention: fixtureZeroRetentionEvidence(),
      onDemandSms: async () => { reads += 1; return [smsRecord()]; },
    });
    await adapter.pair(fixtureBinding());
    const input = { toolCallId: "sms-retry", deviceId: "device-a", limit: 1 };
    expect(await adapter.querySms(input)).toEqual(await adapter.querySms(input));
    expect(reads).toBe(1);

    const { subscriptionId } = await adapter.subscribeSms({ deviceId: "device-a" });
    const event = adapter.emitSmsAutoSend(smsRecord({ body: "complete event body" }));
    await expect(adapter.receiveSmsEvent({ ...event, binding: { ...event.binding, sessionId: "session-other" } }))
      .rejects.toMatchObject({ code: "EVENT_BINDING_MISMATCH" });
    await expect(adapter.receiveSmsEvent(event)).resolves.toMatchObject({
      subscriptionId,
      record: { recordId: "sms:42", body: "complete event body" },
    });
    await expect(adapter.unsubscribeSms()).resolves.toEqual({ removed: true });
  });
});
