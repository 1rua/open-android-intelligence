import { describe, expect, it } from "vitest";
import { Ajv2020 } from "ajv/dist/2020.js";
import addFormatsImport from "ajv-formats";

import {
  schemaFor,
  validateGatewayValue,
  type GatewaySchemaName,
} from "../src/schema-registry.js";

const hexDigest = "a".repeat(64);
const prefixedDigest = `sha256:${hexDigest}`;
const publicKey = "A".repeat(43);
const signature = "A".repeat(86);
const addFormats = addFormatsImport as unknown as (ajv: Ajv2020) => Ajv2020;

const validValues: Partial<Record<GatewaySchemaName, unknown>> = {
  "negotiate.request": {
    negotiationId: "neg_1",
    protocol: { major: 2, minor: 1 },
    client: {
      installationId: "install_1",
      appVersion: "2.1.0",
      platform: "android",
      platformApi: 35,
    },
    features: {
      auth: ["password", "account-invitation", "refresh", "device-key"],
      messages: ["chat-v1"],
      attachments: ["staged-sha256-v1"],
      events: ["sse-cursor-v1"],
      deviceRequests: ["risk-queue-v1"],
    },
    schemaHashes: { core: prefixedDigest },
  },
  "negotiate.response": {
    protocol: { major: 2, minor: 1 },
    features: {
      auth: ["password", "account-invitation", "refresh", "device-key"],
      messages: "chat-v1",
      attachments: "staged-sha256-v1",
      events: "sse-cursor-v1",
      deviceRequests: "risk-queue-v1",
    },
    limits: {
      attachmentTtlSeconds: 3_600,
      eventRetentionSeconds: 86_400,
      maxClockSkewSeconds: 120,
    },
    gatewayIdentity: {
      deploymentId: "deploy_1",
      tlsSpkiSha256: prefixedDigest,
    },
  },
  "session.password": {
    negotiationId: "neg_1",
    username: "alice",
    password: "user-entered-secret",
    installation: {
      installationId: "install_1",
      displayName: "Alice's phone",
      devicePublicKey: publicKey,
    },
  },
  "session.refresh": {
    negotiationId: "neg_1",
    accountId: "account_1",
    installationId: "install_1",
    deviceId: "device_1",
    refreshCredential: "refresh-secret",
  },
  "session.device": {
    negotiationId: "neg_1",
    accountId: "account_1",
    installationId: "install_1",
    deviceId: "device_1",
    challenge: "challenge_1",
    signature,
  },
  "conversation.create": {
    clientConversationId: "conversation_1",
    title: "A conversation",
  },
  "message.create": {
    clientMessageId: "message_1",
    text: "Summarize the attachment",
    attachments: [{ attachmentId: "attachment_1" }],
  },
  "attachment.create": {
    clientAttachmentId: "attachment_1",
    filename: "report.pdf",
    mediaType: "application/pdf",
    sizeBytes: 102_400,
    sha256: hexDigest,
  },
  event: {
    correlationId: "correlation_1",
    occurredAt: "2026-08-24T12:00:00.000Z",
    payload: {},
  },
  "device.request": {
    requestId: "device_request_1",
    capability: { id: "org.openandroidintelligence.sms.query", version: "1.0.0" },
    provider: {
      pluginId: "org.openandroidintelligence.sms",
      authorKeyId: prefixedDigest,
    },
    parameters: {},
    risk: "read",
    grantRevision: 7,
    createdAt: "2026-08-24T12:00:00.000Z",
    expiresAt: "2026-08-25T12:00:00.000Z",
    requiresForegroundConfirmation: false,
  },
  "response.success": {
    requestId: "request_1",
    correlationId: "correlation_1",
    protocol: "2.1",
    data: { arbitrary: true },
  },
  "response.failure": {
    requestId: "request_1",
    correlationId: "correlation_1",
    protocol: "2.1",
    error: {
      code: "CURSOR_EXPIRED",
      message: "expired",
      retryable: true,
      retryAfterSeconds: null,
      details: { arbitrary: true },
    },
  },
};

const clone = <T>(value: T): T => structuredClone(value);

const asRecord = (value: unknown): Record<string, unknown> =>
  value as Record<string, unknown>;

const withUnknownAt = (value: unknown, path: readonly (string | number)[]): unknown => {
  const mutated = clone(value);
  let current: unknown = mutated;
  for (const segment of path) {
    current = Array.isArray(current)
      ? current[segment as number]
      : asRecord(current)[segment as string];
  }
  asRecord(current).unexpected = true;
  return mutated;
};

describe("Gateway Protocol v2 Schema registry", () => {
  it.each(Object.entries(validValues) as [GatewaySchemaName, unknown][])(
    "accepts a valid %s value",
    (name, value) => {
      expect(validateGatewayValue(name, value)).toEqual({ ok: true });
    },
  );

  it.each(Object.entries(validValues) as [GatewaySchemaName, unknown][])(
    "returns a self-contained %s Schema that a fresh strict Ajv 2020 can compile and execute",
    (name, value) => {
      const standaloneAjv = addFormats(new Ajv2020({ strict: true }));
      const validate = standaloneAjv.compile(schemaFor(name));

      expect(validate(value)).toBe(true);
    },
  );

  it("accepts negotiation without attachment byte or media type limits", () => {
    const response = clone(validValues["negotiate.response"]) as {
      limits: Record<string, unknown>;
    };
    expect(validateGatewayValue("negotiate.response", response)).toEqual({ ok: true });

    for (const [field, value] of [
      ["maxSingleAttachmentBytes", 1],
      ["maxMessageAttachmentBytes", 1],
      ["allowedMediaTypes", ["image/png"]],
    ] as const) {
      expect(validateGatewayValue("negotiate.response", {
        ...response,
        limits: { ...response.limits, [field]: value },
      })).toMatchObject({ ok: false });
    }
  });

  it("rejects unknown fields at top-level and nested static objects", () => {
    const topLevel = {
      ...(clone(validValues["negotiate.request"]) as Record<string, unknown>),
      unexpected: true,
    };
    const nested = clone(validValues["negotiate.request"]) as {
      client: Record<string, unknown>;
    };
    nested.client.unexpected = true;

    expect(validateGatewayValue("negotiate.request", topLevel)).toMatchObject({ ok: false });
    expect(validateGatewayValue("negotiate.request", nested)).toMatchObject({ ok: false });
  });

  it.each(["accountId", "deviceId", "principalId"])(
    "rejects caller-supplied %s in conversation, message, and attachment business bodies",
    (identityField) => {
      for (const name of [
        "conversation.create",
        "message.create",
        "attachment.create",
      ] as const) {
        const value = {
          ...(clone(validValues[name]) as Record<string, unknown>),
          [identityField]: "attacker",
        };
        expect(validateGatewayValue(name, value)).toMatchObject({ ok: false });
      }
    },
  );

  it("rejects identity overrides nested inside static message attachment references", () => {
    const value = clone(validValues["message.create"]) as {
      attachments: Array<Record<string, unknown>>;
    };
    value.attachments[0]!.accountId = "attacker";

    expect(validateGatewayValue("message.create", value)).toMatchObject({ ok: false });
  });

  it("enforces the visible wire ID alphabet and length", () => {
    const validateId = (clientConversationId: string) =>
      validateGatewayValue("conversation.create", { clientConversationId });

    expect(validateId("")).toMatchObject({ ok: false });
    expect(validateId("a")).toEqual({ ok: true });
    expect(validateId("a".repeat(128))).toEqual({ ok: true });
    expect(validateId("a".repeat(129))).toMatchObject({ ok: false });
    for (const invalidId of [
      "contains space",
      "contains\thtab",
      "line\nbreak",
      "carriage\rreturn",
      "nul\0byte",
      "delete\u007fbyte",
      "comma,value",
      "slash/value",
      "percent%value",
      "设备",
    ]) {
      expect(validateId(invalidId)).toMatchObject({ ok: false });
    }
    expect(validateId("A-z_0.~-")).toEqual({ ok: true });
  });

  it("validates the public success and failure response shells", () => {
    expect(
      validateGatewayValue("response.success", {
        requestId: "request_1",
        correlationId: "correlation_1",
        protocol: "2.1",
        data: { arbitrary: true },
      }),
    ).toEqual({ ok: true });
    expect(
      validateGatewayValue("response.failure", {
        requestId: "request_1",
        correlationId: "correlation_1",
        protocol: "2.1",
        error: {
          code: "CURSOR_EXPIRED",
          message: "expired",
          retryable: true,
          retryAfterSeconds: 1,
          details: { arbitrary: true },
        },
      }),
    ).toEqual({ ok: true });

    expect(
      validateGatewayValue("response.success", {
        requestId: "request_1",
        correlationId: "correlation_1",
        protocol: "2.1",
        data: {},
        unexpected: true,
      }),
    ).toMatchObject({ ok: false });
    expect(
      validateGatewayValue("response.failure", {
        requestId: "request_1",
        correlationId: "correlation_1",
        protocol: "2.1",
        error: {
          code: "CURSOR_EXPIRED",
          message: "expired",
          retryable: true,
          retryAfterSeconds: null,
          details: {},
          unexpected: true,
        },
      }),
    ).toMatchObject({ ok: false });
  });

  it.each([
    ["wrong decoded length", "A".repeat(42)],
    ["base64url length modulo four equals one", "A".repeat(41)],
    ["non-canonical tail bits", `${"A".repeat(42)}B`],
    ["padding", `${"A".repeat(43)}=`],
  ])("rejects a device public key with %s", (_reason, devicePublicKey) => {
    const value = clone(validValues["session.password"]) as {
      installation: Record<string, unknown>;
    };
    value.installation.devicePublicKey = devicePublicKey;

    expect(validateGatewayValue("session.password", value)).toMatchObject({ ok: false });
  });

  it.each([
    ["wrong decoded length", "A".repeat(85)],
    ["base64url length modulo four equals one", "A".repeat(81)],
    ["non-canonical tail bits", `${"A".repeat(85)}B`],
    ["padding", `${"A".repeat(86)}=`],
  ])("rejects a device signature with %s", (_reason, invalidSignature) => {
    const value = {
      ...(clone(validValues["session.device"]) as Record<string, unknown>),
      signature: invalidSignature,
    };

    expect(validateGatewayValue("session.device", value)).toMatchObject({ ok: false });
  });

  describe("canonical base64url tail bits", () => {
    const allFfPublicKey = `${"_".repeat(42)}8`;
    const allFfSignature = `${"_".repeat(85)}w`;

    it("accepts the canonical encoding of a 32-byte all-0xff Ed25519 public key", () => {
      const value = clone(validValues["session.password"]) as {
        installation: Record<string, unknown>;
      };
      value.installation.devicePublicKey = allFfPublicKey;

      expect(validateGatewayValue("session.password", value)).toEqual({ ok: true });
    });

    it("accepts the canonical encoding of a 64-byte all-0xff Ed25519 signature", () => {
      const value = {
        ...(clone(validValues["session.device"]) as Record<string, unknown>),
        signature: allFfSignature,
      };

      expect(validateGatewayValue("session.device", value)).toEqual({ ok: true });
    });

    it("rejects a 64-byte signature tail that satisfies mod4 but not mod16", () => {
      const value = {
        ...(clone(validValues["session.device"]) as Record<string, unknown>),
        signature: `${"_".repeat(85)}E`,
      };

      expect(validateGatewayValue("session.device", value)).toMatchObject({ ok: false });
    });

    it("rejects a non-canonical 32-byte public-key tail", () => {
      const value = clone(validValues["session.password"]) as {
        installation: Record<string, unknown>;
      };
      value.installation.devicePublicKey = `${"_".repeat(42)}9`;

      expect(validateGatewayValue("session.password", value)).toMatchObject({ ok: false });
    });
  });

  it.each([
    ["negotiate.request", "negotiationId", (value: Record<string, unknown>) => {
      asRecord(value.protocol).major = 3;
    }],
    ["negotiate.response", "protocol", (value: Record<string, unknown>) => {
      asRecord(value.features).messages = "unknown-message-v2";
    }],
    ["session.password", "password", (value: Record<string, unknown>) => {
      asRecord(value.installation).devicePublicKey = "A";
    }],
    ["session.refresh", "refreshCredential", (value: Record<string, unknown>) => {
      value.refreshCredential = "";
    }],
    ["session.device", "challenge", (value: Record<string, unknown>) => {
      value.signature = "A";
    }],
    ["conversation.create", "clientConversationId", (value: Record<string, unknown>) => {
      value.clientConversationId = "设备";
    }],
    ["message.create", "clientMessageId", (value: Record<string, unknown>) => {
      value.clientMessageId = "";
    }],
    ["attachment.create", "clientAttachmentId", (value: Record<string, unknown>) => {
      value.sha256 = hexDigest.toUpperCase();
    }],
    ["event", "correlationId", (value: Record<string, unknown>) => {
      value.occurredAt = "2026-08-24T12:00:00Z";
    }],
    ["device.request", "requestId", (value: Record<string, unknown>) => {
      value.risk = "unknown-risk";
    }],
  ] as const)(
    "rejects missing required, unknown top-level, and critical invalid fields for %s",
    (name, requiredKey, makeCriticalInvalid) => {
      const missing = clone(validValues[name]) as Record<string, unknown>;
      delete missing[requiredKey];
      const unknown = {
        ...(clone(validValues[name]) as Record<string, unknown>),
        unexpected: true,
      };
      const criticalInvalid = clone(validValues[name]) as Record<string, unknown>;
      makeCriticalInvalid(criticalInvalid);

      expect(validateGatewayValue(name, missing)).toMatchObject({ ok: false });
      expect(validateGatewayValue(name, unknown)).toMatchObject({ ok: false });
      expect(validateGatewayValue(name, criticalInvalid)).toMatchObject({ ok: false });
    },
  );

  it.each([
    ["negotiate.request", ["protocol"]],
    ["negotiate.request", ["client"]],
    ["negotiate.request", ["features"]],
    ["negotiate.request", ["schemaHashes"]],
    ["negotiate.response", ["protocol"]],
    ["negotiate.response", ["features"]],
    ["negotiate.response", ["limits"]],
    ["negotiate.response", ["gatewayIdentity"]],
    ["session.password", ["installation"]],
    ["message.create", ["attachments", 0]],
    ["device.request", ["capability"]],
    ["device.request", ["provider"]],
  ] as const)("rejects unknown fields in the static nested object %s:%s", (name, path) => {
    expect(validateGatewayValue(name, withUnknownAt(validValues[name], path))).toMatchObject({
      ok: false,
    });
  });

  it("requires session.device to contain exactly one account selector", () => {
    const accountSelected = clone(validValues["session.device"]) as Record<string, unknown>;
    const both = { ...accountSelected, username: "alice" };
    const neither = { ...accountSelected };
    delete neither.accountId;
    const usernameSelected = { ...neither, username: "alice" };

    expect(validateGatewayValue("session.device", accountSelected)).toEqual({ ok: true });
    expect(validateGatewayValue("session.device", usernameSelected)).toEqual({ ok: true });
    expect(validateGatewayValue("session.device", both)).toMatchObject({ ok: false });
    expect(validateGatewayValue("session.device", neither)).toMatchObject({ ok: false });
  });

  it("accepts only RFC 3339 UTC timestamps with exactly millisecond precision", () => {
    const value = clone(validValues.event) as Record<string, unknown>;

    for (const occurredAt of [
      "2026-08-24T12:00:00Z",
      "2026-08-24T12:00:00.00Z",
      "2026-08-24T12:00:00.0000Z",
      "2026-08-24T20:00:00.000+08:00",
      "2026-08-24 12:00:00.000Z",
    ]) {
      expect(validateGatewayValue("event", { ...value, occurredAt })).toMatchObject({ ok: false });
    }
  });

  it("distinguishes lowercase bare and sha256-prefixed digests", () => {
    const attachment = clone(validValues["attachment.create"]) as Record<string, unknown>;
    expect(validateGatewayValue("attachment.create", attachment)).toEqual({ ok: true });
    expect(
      validateGatewayValue("attachment.create", { ...attachment, sha256: prefixedDigest }),
    ).toMatchObject({ ok: false });
    expect(
      validateGatewayValue("attachment.create", { ...attachment, sha256: hexDigest.toUpperCase() }),
    ).toMatchObject({ ok: false });

    const negotiation = clone(validValues["negotiate.request"]) as {
      schemaHashes: Record<string, unknown>;
    };
    expect(
      validateGatewayValue("negotiate.request", {
        ...negotiation,
        schemaHashes: { core: hexDigest },
      }),
    ).toMatchObject({ ok: false });
    expect(
      validateGatewayValue("negotiate.request", {
        ...negotiation,
        schemaHashes: { core: `sha256:${hexDigest.toUpperCase()}` },
      }),
    ).toMatchObject({ ok: false });
  });

  it("rejects unknown negotiated feature names and values", () => {
    const value = clone(validValues["negotiate.request"]) as {
      features: Record<string, unknown>;
    };

    expect(
      validateGatewayValue("negotiate.request", {
        ...value,
        features: { ...value.features, dangerousFutureFeature: ["enabled"] },
      }),
    ).toMatchObject({ ok: false });
    expect(
      validateGatewayValue("negotiate.request", {
        ...value,
        features: { ...value.features, deviceRequests: ["unknown-risk-v2"] },
      }),
    ).toMatchObject({ ok: false });
  });

  it("does not coerce types, remove unknown fields, or inject defaults", () => {
    const wrongType = {
      ...(clone(validValues["attachment.create"]) as Record<string, unknown>),
      sizeBytes: "102400",
    };
    const withUnknown = {
      ...(clone(validValues["conversation.create"]) as Record<string, unknown>),
      unknown: true,
    };
    const withoutOptionalTitle = { clientConversationId: "conversation_1" };

    expect(validateGatewayValue("attachment.create", wrongType)).toMatchObject({ ok: false });
    expect(wrongType.sizeBytes).toBe("102400");
    expect(validateGatewayValue("conversation.create", withUnknown)).toMatchObject({ ok: false });
    expect(withUnknown).toHaveProperty("unknown", true);
    expect(validateGatewayValue("conversation.create", withoutOptionalTitle)).toEqual({ ok: true });
    expect(withoutOptionalTitle).not.toHaveProperty("title");
  });

  it("returns stable frozen validation error arrays", () => {
    const first = validateGatewayValue("conversation.create", {});
    const second = validateGatewayValue("conversation.create", {});

    expect(first).toEqual(second);
    expect(first).toMatchObject({ ok: false });
    if (!first.ok) {
      expect(first.errors.length).toBeGreaterThan(0);
      expect(first.errors.every((error) => typeof error === "string")).toBe(true);
      expect(Object.isFrozen(first.errors)).toBe(true);
    }
  });

  it("returns defensive Schema copies that cannot pollute validation or the registry", () => {
    const exposed = schemaFor("conversation.create") as Record<string, unknown>;
    exposed.additionalProperties = true;

    expect(schemaFor("conversation.create")).toMatchObject({ additionalProperties: false });
    expect(
      validateGatewayValue("conversation.create", {
        clientConversationId: "conversation_1",
        accountId: "attacker",
      }),
    ).toMatchObject({ ok: false });
  });

  it("keeps event.payload as an object dispatch point requiring event-type sub-Schema validation", () => {
    const value = clone(validValues.event) as Record<string, unknown>;
    value.payload = { eventSpecificField: true, accountId: "untrusted-payload-data" };

    expect(validateGatewayValue("event", value)).toEqual({ ok: true });
    expect(
      validateGatewayValue("event", { ...value, payload: ["not", "an", "object"] }),
    ).toMatchObject({ ok: false });
  });

  it("keeps device.request.parameters as an object dispatch point requiring capability sub-Schema validation", () => {
    const value = clone(validValues["device.request"]) as Record<string, unknown>;
    value.parameters = { capabilitySpecificField: true, principalId: "untrusted-parameter-data" };

    expect(validateGatewayValue("device.request", value)).toEqual({ ok: true });
    expect(
      validateGatewayValue("device.request", { ...value, parameters: "not-an-object" }),
    ).toMatchObject({ ok: false });
  });

  it("requires all static object Schemas to reject additional properties", () => {
    const assertStrictObjects = (schema: unknown, dynamic = false): void => {
      if (typeof schema !== "object" || schema === null || Array.isArray(schema)) return;
      const record = schema as Record<string, unknown>;
      if (record.type === "object") {
        if (dynamic) {
          expect(record.additionalProperties).not.toBe(false);
        } else {
          expect(record.additionalProperties).toBe(false);
        }
      }
      const properties = record.properties;
      if (typeof properties === "object" && properties !== null && !Array.isArray(properties)) {
        for (const [key, child] of Object.entries(properties)) {
          assertStrictObjects(
            child,
            key === "payload" || key === "parameters" || key === "data" || key === "details",
          );
        }
      }
      if (record.items !== undefined) assertStrictObjects(record.items);
      for (const keyword of ["allOf", "anyOf", "oneOf"] as const) {
        const children = record[keyword];
        if (Array.isArray(children)) children.forEach((child) => assertStrictObjects(child));
      }
    };

    for (const name of Object.keys(validValues) as GatewaySchemaName[]) {
      assertStrictObjects(schemaFor(name));
    }
  });
});
