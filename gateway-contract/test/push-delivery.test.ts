import { describe, expect, it } from "vitest";
import { validateGatewayValue } from "../src/schema-registry.js";

const route = {
  coreInstanceId: `core_${"a".repeat(32)}`,
  accountScope: `acct_${"b".repeat(32)}`,
  endpointId: `endpoint_${"c".repeat(32)}`,
  generation: 1,
};

describe("device declarations and authenticated push routes", () => {
  it("accepts untrusted display declarations and rejects empty or identity override fields", () => {
    expect(validateGatewayValue("device.caller", { agentName: "Hermes", purpose: "读取已选范围" }).ok).toBe(true);
    for (const caller of [
      { agentName: " ", purpose: "read" },
      { agentName: "Hermes", purpose: "\n\t" },
      { agentName: "Hermes" },
      { agentName: "Hermes", purpose: "read", accountId: "another-account" },
    ]) {
      expect(validateGatewayValue("device.caller", caller).ok).toBe(false);
    }
  });

  it("binds a route to Core, account scope, server endpoint and positive generation", () => {
    expect(validateGatewayValue("push.route", route).ok).toBe(true);
    expect(validateGatewayValue("push.route", { ...route, generation: 0 }).ok).toBe(false);
    expect(validateGatewayValue("push.route", { ...route, coreInstanceId: "Hermes" }).ok).toBe(false);
    expect(validateGatewayValue("push.route", { ...route, label: "Trusted Agent" }).ok).toBe(false);
  });

  it("has bounded endpoint leases and a closed event delivery envelope", () => {
    expect(validateGatewayValue("push.registration", { registrationKey: "receiver-main", label: "Hermes", ttlSeconds: 60 }).ok).toBe(true);
    expect(validateGatewayValue("push.registration", { registrationKey: "receiver-main", label: "Hermes", ttlSeconds: 86401 }).ok).toBe(false);
    const delivery = { eventId: "event_1", route, eventType: "notification", occurredAt: "2026-11-01T10:00:00.000Z", expiresAt: "2026-11-04T10:00:00.000Z", payload: { text: "test" } };
    expect(validateGatewayValue("push.delivery", delivery).ok).toBe(true);
    expect(validateGatewayValue("push.delivery", { ...delivery, accountId: "another-account" }).ok).toBe(false);
    expect(validateGatewayValue("push.ack", { eventId: "event_1" }).ok).toBe(true);
    expect(validateGatewayValue("push.ack", { eventId: "event_1", payload: {} }).ok).toBe(false);
  });

  it("only accepts Android's local progress states, never a Core grant", () => {
    for (const state of ["awaiting_confirmation", "executing"]) {
      expect(validateGatewayValue("device.authorizationProgress", { state }).ok).toBe(true);
    }
    expect(validateGatewayValue("device.authorizationProgress", { state: "approved" }).ok).toBe(false);
    expect(validateGatewayValue("device.authorizationProgress", { state: "executing", grant: true }).ok).toBe(false);
  });

  it("selects bounded unique event IDs for revocation and keeps explicit whole-route removal", () => {
    expect(validateGatewayValue("push.revoke", { route, eventIds: ["event_sms_1", "event_notification_2"] }).ok).toBe(true);
    expect(validateGatewayValue("push.revoke", { route }).ok).toBe(true);
    expect(validateGatewayValue("push.revoke", { route, eventIds: Array.from({ length: 128 }, (_, i) => `event_${i}`) }).ok).toBe(true);
    for (const eventIds of [[], ["event_1", "event_1"], [""], ["contains space"], ["x".repeat(129)], [42], null,
      Array.from({ length: 129 }, (_, i) => `event_${i}`)]) {
      expect(validateGatewayValue("push.revoke", { route, eventIds }).ok).toBe(false);
    }
  });
});
