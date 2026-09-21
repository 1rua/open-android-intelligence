import { createHash } from "node:crypto";

import { Ajv2020 } from "ajv/dist/2020.js";
import addFormatsImport from "ajv-formats";
import { describe, expect, it } from "vitest";
import canonicalize from "canonicalize";

import fixtureMetaSchema from "../vectors/dispatched-schema-fixtures-1.0.0.schema.json" with { type: "json" };
import fixtureRegistry from "../vectors/dispatched-schema-fixtures.json" with { type: "json" };
import {
  createGatewayDispatchedValidator,
  gatewaySubschemaSha256,
  type GatewayDispatchedValidator,
  type GatewayLogicalSubschemaKey,
  type GatewaySubschemaCatalogEntry,
  type TrustedGatewayDispatch,
  type VerifiedSchemaBindingSet,
} from "../src/dispatched-schema-validator.js";

const bindingSetId = "gateway-core-fixtures-v1";
const addFormats = addFormatsImport as unknown as (ajv: Ajv2020) => Ajv2020;

type FixtureCatalogEntry = GatewaySubschemaCatalogEntry & { fixtureId: string };
type FixtureBinding = {
  key: GatewayLogicalSubschemaKey;
  schemaSha256: string;
};
type FixtureRegistry = {
  formatVersion: string;
  catalogEntries: FixtureCatalogEntry[];
  bindingSets: Array<{ id: string; bindings: FixtureBinding[] }>;
};

const registry = fixtureRegistry as unknown as FixtureRegistry;

const expectedFixtureIds = [
  "event.gateway-notice.v1",
  "device.sms-query.v1",
  "response.conversation-create.v1",
  "error.cursor-expired.v1",
  "event.conversation-command-result.v1",
  "event.conversation-approval-requested.v1",
  "event.conversation-approval-resolved.v1",
] as const;

const expectedFixtureDigests = [
  "sha256:597cd548512a66963ae944e75af529fddd0c40cdf8fc59b3fe3cab5287b6c725",
  "sha256:2b97b44496ebe4e20884dcf12391bc272783513c0ed5a5f459ab062eca6c37ac",
  "sha256:2719284ed50fba05b945c58eb5146dd53becdcc48b0a57228d8e97a407a0b87e",
  "sha256:7cb06a94b19b83c6aba2ed82832bcfd3c103345f4fb6b5f106739cfe40d5fce9",
  "sha256:df7548ff7d994373e2a2f204b389145c6040196be309df0f322ef418be48b1ce",
  "sha256:bce36a217163c4626595ffef4b0ac4456ae95bee57646b0a586ea4413b59d663",
  "sha256:2f455f22f1e50d730a80e22a2e9f93e1259a6920747f527041942451bd377916",
] as const;

const expectedFixtureLogicalKeys: GatewayLogicalSubschemaKey[] = [
  { kind: "event", eventType: "gateway.notice" },
  {
    kind: "device.request",
    pluginId: "org.openandroidintelligence.sms",
    authorKeyId: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    capabilityId: "org.openandroidintelligence.sms.query",
    capabilityVersion: "1.0.0",
  },
  { kind: "response.success", operation: "conversation.create", status: 201 },
  { kind: "response.failure", errorCode: "CURSOR_EXPIRED" },
  { kind: "event", eventType: "conversation.command.result" },
  { kind: "event", eventType: "conversation.approval.requested" },
  { kind: "event", eventType: "conversation.approval.resolved" },
];

const loadSharedFixtureRegistry = (
  candidate: FixtureRegistry,
): { entries: GatewaySubschemaCatalogEntry[]; bindings: FixtureBinding[] } => {
  const ajv = addFormats(new Ajv2020({ strict: true }));
  const validate = ajv.compile(fixtureMetaSchema);
  if (!validate(candidate)) throw new Error("INVALID_FIXTURE_REGISTRY");
  if (candidate.formatVersion !== "1.0.0") throw new Error("INVALID_FIXTURE_REGISTRY");
  if (candidate.catalogEntries.length !== expectedFixtureIds.length) {
    throw new Error("INVALID_FIXTURE_REGISTRY");
  }
  if (candidate.bindingSets.length !== 1 || candidate.bindingSets[0]?.id !== bindingSetId) {
    throw new Error("INVALID_FIXTURE_REGISTRY");
  }

  const fixtureBindings = candidate.bindingSets[0]!.bindings;
  if (fixtureBindings.length !== expectedFixtureIds.length) {
    throw new Error("INVALID_FIXTURE_REGISTRY");
  }
  for (const [index, entry] of candidate.catalogEntries.entries()) {
    if (entry.fixtureId !== expectedFixtureIds[index]) throw new Error("INVALID_FIXTURE_REGISTRY");
    if (entry.key.schemaSha256 !== expectedFixtureDigests[index]) {
      throw new Error("INVALID_FIXTURE_REGISTRY");
    }
    const { schemaSha256: _entryDigest, ...entryLogicalKey } = entry.key;
    if (canonicalize(entryLogicalKey) !== canonicalize(expectedFixtureLogicalKeys[index])) {
      throw new Error("INVALID_FIXTURE_REGISTRY");
    }
    const binding = fixtureBindings[index]!;
    if (canonicalize(binding.key) !== canonicalize(expectedFixtureLogicalKeys[index])) {
      throw new Error("INVALID_FIXTURE_REGISTRY");
    }
    if (binding.schemaSha256 !== entry.key.schemaSha256) {
      throw new Error("INVALID_FIXTURE_REGISTRY");
    }
    if (gatewaySubschemaSha256(entry.schema) !== entry.key.schemaSha256) {
      throw new Error("INVALID_FIXTURE_REGISTRY");
    }
  }

  return {
    entries: candidate.catalogEntries.map(({ key, schema }) => ({ key, schema })),
    bindings: fixtureBindings,
  };
};

const loadedFixture = loadSharedFixtureRegistry(registry);
const fixtureCatalogEntries = registry.catalogEntries;
const catalogEntries = loadedFixture.entries;
const bindings = loadedFixture.bindings;
const bindingSet = registry.bindingSets[0]!;

const createFixtureValidator = (): GatewayDispatchedValidator => {
  const core = bindings
    .filter((binding) => binding.key.kind !== "device.request")
    .map((binding) => ({ ...binding.key, schemaSha256: binding.schemaSha256 }));
  const device = bindings
    .filter((binding) => binding.key.kind === "device.request")
    .map((binding) => ({ ...binding.key, schemaSha256: binding.schemaSha256 }));
  const verifiedBindings: VerifiedSchemaBindingSet = {
    core: core as VerifiedSchemaBindingSet["core"],
    device: device as VerifiedSchemaBindingSet["device"],
  };
  return createGatewayDispatchedValidator(catalogEntries, verifiedBindings);
};

const validEvent = {
  correlationId: "correlation_1",
  occurredAt: "2026-08-27T00:00:00.000Z",
  payload: { noticeCode: "maintenance" },
};

const validDeviceRequest = {
  requestId: "device_request_1",
  capability: { id: "org.openandroidintelligence.sms.query", version: "1.0.0" },
  provider: {
    pluginId: "org.openandroidintelligence.sms",
    authorKeyId: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  },
  parameters: { query: "from:alice" },
  risk: "read",
  grantRevision: 7,
  createdAt: "2026-08-27T00:00:00.000Z",
  expiresAt: "2026-08-28T00:00:00.000Z",
  requiresForegroundConfirmation: false,
};

const validSuccess = {
  requestId: "request_1",
  correlationId: "correlation_1",
  protocol: "2.0",
  data: { conversationId: "conversation_1" },
};

const validFailure = {
  requestId: "request_1",
  correlationId: "correlation_1",
  protocol: "2.0",
  error: {
    code: "CURSOR_EXPIRED",
    message: "expired",
    retryable: true,
    retryAfterSeconds: 1,
    details: { recoverableResources: ["conversations"] },
  },
};

const eventDispatch: TrustedGatewayDispatch = {
  kind: "event",
  eventType: "gateway.notice",
};
const deviceDispatch: TrustedGatewayDispatch = { kind: "device.request" };
const successDispatch: TrustedGatewayDispatch = {
  kind: "response.success",
  operation: "conversation.create",
  status: 201,
};
const failureDispatch: TrustedGatewayDispatch = { kind: "response.failure" };

describe("Gateway Protocol v2 dispatched Schema validation", () => {
  it("validates the shared fixture registry with its closed format 1.0.0 meta-schema", () => {
    const ajv = addFormats(new Ajv2020({ strict: true }));
    const validate = ajv.compile(fixtureMetaSchema);

    expect(validate(fixtureRegistry)).toBe(true);
    expect(validate({ ...registry, unexpected: true })).toBe(false);

    const withUnknownCatalogField = structuredClone(registry);
    (withUnknownCatalogField.catalogEntries as Array<Record<string, unknown>>)[0]!.unexpected = true;
    expect(validate(withUnknownCatalogField)).toBe(false);

    const withUnknownBindingField = structuredClone(registry);
    const firstBindingSet = (withUnknownBindingField.bindingSets as Array<Record<string, unknown>>)[0]!;
    const firstBinding = (firstBindingSet.bindings as Array<Record<string, unknown>>)[0]!;
    firstBinding.unexpected = true;
    expect(validate(withUnknownBindingField)).toBe(false);
  });

  it("loads the single shared fixture registry and its seven canonical schemas", () => {
    expect(registry.formatVersion).toBe("1.0.0");
    expect(registry.catalogEntries).toHaveLength(7);
    expect(registry.bindingSets).toHaveLength(1);
    expect(bindingSet?.id).toBe(bindingSetId);
    expect(bindings).toHaveLength(7);

    expect(fixtureCatalogEntries.map((entry) => entry.fixtureId)).toEqual([
      "event.gateway-notice.v1",
      "device.sms-query.v1",
      "response.conversation-create.v1",
      "error.cursor-expired.v1",
      "event.conversation-command-result.v1",
      "event.conversation-approval-requested.v1",
      "event.conversation-approval-resolved.v1",
    ]);
    expect(bindings.map((binding) => binding.key)).toEqual([
      { kind: "event", eventType: "gateway.notice" },
      {
        kind: "device.request",
        pluginId: "org.openandroidintelligence.sms",
        authorKeyId: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        capabilityId: "org.openandroidintelligence.sms.query",
        capabilityVersion: "1.0.0",
      },
      { kind: "response.success", operation: "conversation.create", status: 201 },
      { kind: "response.failure", errorCode: "CURSOR_EXPIRED" },
      { kind: "event", eventType: "conversation.command.result" },
      { kind: "event", eventType: "conversation.approval.requested" },
      { kind: "event", eventType: "conversation.approval.resolved" },
    ]);

    for (const entry of fixtureCatalogEntries) {
      const canonical = canonicalize(entry.schema);
      expect(canonical).toBeTypeOf("string");
      const digest = createHash("sha256").update(canonical!, "utf8").digest("hex");
      expect(entry.key.schemaSha256).toBe(`sha256:${digest}`);
      expect(gatewaySubschemaSha256(entry.schema)).toBe(entry.key.schemaSha256);
    }

    expect(bindings.map((binding) => binding.schemaSha256)).toEqual(
      fixtureCatalogEntries.map((entry) => entry.key.schemaSha256),
    );
  });

  it("rejects missing, extra, reordered, tampered, or alternate fixture registry facts", () => {
    const missingEntry = structuredClone(registry);
    (missingEntry.catalogEntries as unknown[]).pop();
    expect(() => loadSharedFixtureRegistry(missingEntry)).toThrow();

    const extraEntry = structuredClone(registry);
    (extraEntry.catalogEntries as unknown[]).push(structuredClone(extraEntry.catalogEntries[0]));
    expect(() => loadSharedFixtureRegistry(extraEntry)).toThrow();

    const alternateBindingSet = structuredClone(registry);
    (alternateBindingSet.bindingSets[0] as Record<string, unknown>).id = "other-fixtures";
    expect(() => loadSharedFixtureRegistry(alternateBindingSet)).toThrow();

    const reordered = structuredClone(registry);
    reordered.catalogEntries.reverse();
    expect(() => loadSharedFixtureRegistry(reordered)).toThrow();

    const tampered = structuredClone(registry);
    const tamperedEntry = tampered.catalogEntries[0]!;
    (tamperedEntry.schema as Record<string, unknown>).properties = {};
    expect(() => loadSharedFixtureRegistry(tampered)).toThrow();
  });

  it("accepts known event, device, success, and failure dispatched values", () => {
    const validator = createFixtureValidator();

    expect(validator.validate(eventDispatch, validEvent)).toEqual({ ok: true });
    expect(validator.validate(deviceDispatch, validDeviceRequest)).toEqual({ ok: true });
    expect(validator.validate(successDispatch, validSuccess)).toEqual({ ok: true });
    expect(validator.validate(failureDispatch, validFailure)).toEqual({ ok: true });
  });

  it("rejects a dynamic payload even when its outer object is valid", () => {
    const validator = createFixtureValidator();
    const value = {
      ...validEvent,
      payload: { noticeCode: "maintenance", unexpected: true },
    };

    expect(validator.validate(eventDispatch, value)).toMatchObject({ ok: false });
  });

  it("applies the dispatched Schema to device parameters, success data, and failure details", () => {
    const validator = createFixtureValidator();

    expect(
      validator.validate(deviceDispatch, { ...validDeviceRequest, parameters: {} }),
    ).toMatchObject({ ok: false });
    expect(
      validator.validate(successDispatch, { ...validSuccess, data: {} }),
    ).toMatchObject({ ok: false });
    expect(
      validator.validate(failureDispatch, {
        ...validFailure,
        error: { ...validFailure.error, details: { recoverableResources: ["unknown"] } },
      }),
    ).toMatchObject({ ok: false });
  });

  it("does not let payload fields select a different event binding", () => {
    const validator = createFixtureValidator();
    const value = {
      ...validEvent,
      payload: {
        noticeCode: "maintenance",
        type: "some-other-event",
        digest: "sha256:" + "0".repeat(64),
      },
    };

    expect(validator.validate(eventDispatch, value)).toMatchObject({ ok: false });
  });

  it("fails closed when a known logical key has no binding", () => {
    const validator = createGatewayDispatchedValidator(catalogEntries, { core: [], device: [] });

    const result = validator.validate(eventDispatch, validEvent);

    expect(result).toMatchObject({ ok: false });
    if (!result.ok) expect(result.errors[0]).toContain("dispatch");
  });

  it("uses the prescribed four-field format for dispatch diagnostics", () => {
    const validator = createFixtureValidator();
    const result = validator.validate(
      { kind: "event", eventType: "gateway.unknown" },
      validEvent,
    );

    expect(result).toMatchObject({ ok: false });
    if (!result.ok) {
      expect(result.errors).toHaveLength(1);
      const fields = result.errors[0]!.split("\t");
      expect(fields[0]).toBe("");
      expect(fields[1]).toBe("");
      expect(fields[2]).toBe("dispatch");
      expect(canonicalize(JSON.parse(fields[3]!))).toBe(fields[3]);
    }
  });

  it("rejects an unknown event type and never falls back to object validation", () => {
    const validator = createFixtureValidator();
    const result = validator.validate(
      { kind: "event", eventType: "gateway.unknown" },
      validEvent,
    );

    expect(result).toMatchObject({ ok: false });
    if (!result.ok) expect(result.errors.some((error) => error.includes("dispatch"))).toBe(true);
  });

  it("rejects device identity and capability binding mismatches", () => {
    const validator = createFixtureValidator();
    const mismatch = {
      ...validDeviceRequest,
      capability: { id: "org.openandroidintelligence.sms.query", version: "2.0.0" },
    };

    expect(validator.validate(deviceDispatch, mismatch)).toMatchObject({ ok: false });
  });

  it("rejects success operation/status mismatches and unknown failure codes", () => {
    const validator = createFixtureValidator();

    expect(
      validator.validate(
        { kind: "response.success", operation: "conversation.create", status: 200 },
        validSuccess,
      ),
    ).toMatchObject({ ok: false });
    expect(
      validator.validate(failureDispatch, {
        ...validFailure,
        error: { ...validFailure.error, code: "UNKNOWN_CODE" },
      }),
    ).toMatchObject({ ok: false });
  });

  it("selects failure details by code rather than mutable error message", () => {
    const validator = createFixtureValidator();
    const changedMessage = {
      ...validFailure,
      error: { ...validFailure.error, message: "a different localized message" },
    };

    expect(validator.validate(failureDispatch, changedMessage)).toEqual({ ok: true });
  });

  it("returns outer errors without extracting a dispatch key", () => {
    const validator = createFixtureValidator();
    const invalidOuter = { ...validEvent, correlationId: "" };

    const result = validator.validate(eventDispatch, invalidOuter);

    expect(result).toMatchObject({ ok: false });
    if (!result.ok) expect(result.errors.some((error) => error.includes("dispatch"))).toBe(false);
  });

  it("validates the outer shell before rejecting a malformed dispatch", () => {
    const validator = createFixtureValidator();
    const invalidOuter = { ...validEvent, correlationId: "" };
    const malformedDispatch = {
      ...eventDispatch,
      unexpected: true,
    } as unknown as TrustedGatewayDispatch;

    const result = validator.validate(malformedDispatch, invalidOuter);

    expect(result).toMatchObject({ ok: false });
    if (!result.ok) {
      expect(result.errors.some((error) => error.includes("dispatch"))).toBe(false);
      expect(result.errors.some((error) => error.includes("minLength"))).toBe(true);
    }
  });

  it("rejects request-level digest, schema, resolver, validator, and binding injection", () => {
    const validator = createFixtureValidator();
    const injectedDispatch = {
      ...eventDispatch,
      schemaSha256: "sha256:" + "0".repeat(64),
      schema: { type: "object", additionalProperties: true },
      resolver: () => ({ ok: true }),
      validator: () => true,
      binding: { schemaSha256: "sha256:" + "0".repeat(64) },
    } as unknown as TrustedGatewayDispatch;

    expect(validator.validate(injectedDispatch, validEvent)).toMatchObject({ ok: false });
  });

  it("rejects malformed or inconsistent catalogs and bindings at construction", () => {
    const entry = catalogEntries[0]!;
    const logicalKey = {
      kind: "event",
      eventType: "gateway.notice",
    } as const;
    const entryFor = (schemaSha256: string): GatewaySubschemaCatalogEntry => ({
      key: { ...logicalKey, schemaSha256 },
      schema: entry.schema,
    });
    const bindingFor = (schemaSha256: string) => ({ ...logicalKey, schemaSha256 });

    expect(() =>
      createGatewayDispatchedValidator(
        [entryFor(entry.key.schemaSha256), entryFor(entry.key.schemaSha256)],
        {
          core: [bindingFor(entry.key.schemaSha256), bindingFor(entry.key.schemaSha256)],
          device: [],
        },
      ),
    ).toThrow();
    expect(() =>
      createGatewayDispatchedValidator(
        [entryFor(entry.key.schemaSha256)],
        { core: [bindingFor("sha256:" + "0".repeat(64))], device: [] },
      ),
    ).toThrow();

    const secondSchema = {
      type: "object",
      additionalProperties: false,
      properties: { other: { type: "boolean" } },
    };
    const secondDigest = gatewaySubschemaSha256(secondSchema);
    expect(() =>
      createGatewayDispatchedValidator(
        [
          entryFor(entry.key.schemaSha256),
          { key: { ...logicalKey, schemaSha256: secondDigest }, schema: secondSchema },
        ],
        { core: [bindingFor(entry.key.schemaSha256)], device: [] },
      ),
    ).toThrow();

    const mismatchedKey = { ...entry.key, schemaSha256: "sha256:" + "0".repeat(64) };
    expect(() =>
      createGatewayDispatchedValidator(
        [{ key: mismatchedKey, schema: entry.schema }],
        { core: [{ ...logicalKey, schemaSha256: mismatchedKey.schemaSha256 }], device: [] },
      ),
    ).toThrow();
  });

  it("rejects schemas outside the closed catalog subset", () => {
    const logicalKey = { kind: "event", eventType: "test" } as const;
    const schema = { type: "object", additionalProperties: false, not: {} };
    const digest = gatewaySubschemaSha256(schema);

    expect(() =>
      createGatewayDispatchedValidator(
        [{ key: { ...logicalKey, schemaSha256: digest }, schema }],
        { core: [{ ...logicalKey, schemaSha256: digest }], device: [] },
      ),
    ).toThrow();
  });

  it("does not change after callers mutate source schemas or bindings", () => {
    const schema = {
      type: "object",
      additionalProperties: false,
      required: ["value"],
      properties: { value: { type: "string" } },
    };
    const logicalKey = { kind: "event", eventType: "mutable" } as const;
    const digest = gatewaySubschemaSha256(schema);
    const entry: GatewaySubschemaCatalogEntry = {
      key: { ...logicalKey, schemaSha256: digest },
      schema,
    };
    const binding = { ...logicalKey, schemaSha256: digest };
    const validator = createGatewayDispatchedValidator(
      [entry],
      { core: [binding], device: [] },
    );

    expect(Object.isFrozen(validator)).toBe(true);
    schema.properties.value = { type: "number" };
    binding.schemaSha256 = "sha256:" + "0".repeat(64);
    const eventValue = {
      correlationId: "correlation_1",
      occurredAt: "2026-08-27T00:00:00.000Z",
      payload: { value: "ok" },
    };
    expect(validator.validate({ kind: "event", eventType: "mutable" }, eventValue)).toEqual({
      ok: true,
    });
    expect(
      validator.validate(
        { kind: "event", eventType: "mutable" },
        { ...eventValue, payload: { value: 1 } },
      ),
    ).toMatchObject({ ok: false });
  });

  it("freezes failed results and canonicalizes duplicate diagnostics", () => {
    const validator = createFixtureValidator();
    const first = validator.validate(eventDispatch, {
      ...validEvent,
      payload: { unexpected: true },
    });
    const second = validator.validate(eventDispatch, {
      ...validEvent,
      payload: { unexpected: true },
    });

    expect(first).toEqual(second);
    expect(Object.isFrozen(first)).toBe(true);
    if (!first.ok) expect(Object.isFrozen(first.errors)).toBe(true);
  });

  it("uses the prescribed four-field JCS diagnostic format and UTF-8 ordering", () => {
    const validator = createFixtureValidator();
    const result = validator.validate(eventDispatch, {
      ...validEvent,
      payload: { unexpected: true },
    });

    expect(result).toMatchObject({ ok: false });
    if (!result.ok) {
      expect(new Set(result.errors).size).toBe(result.errors.length);
      expect(result.errors).toEqual(
        [...result.errors].sort((left, right) =>
          Buffer.from(left, "utf8").compare(Buffer.from(right, "utf8")),
        ),
      );
      for (const error of result.errors) {
        const fields = error.split("\t");
        expect(fields).toHaveLength(4);
        const params = JSON.parse(fields[3]!);
        expect(canonicalize(params)).toBe(fields[3]);
      }
    }
  });

  it("accepts local $defs references, array items, composition, and recursive references", () => {
    const schema = {
      type: "object",
      additionalProperties: false,
      required: ["notice"],
      properties: {
        notice: { $ref: "#/$defs/notice" },
        tags: { type: "array", items: { $ref: "#/$defs/tag" } },
        choice: { anyOf: [{ type: "string" }, { type: "number" }] },
      },
      $defs: {
        notice: {
          type: "object",
          additionalProperties: false,
          required: ["code"],
          properties: { code: { type: "string" } },
        },
        tag: { type: "string" },
        recursive: {
          type: "object",
          additionalProperties: false,
          properties: { child: { $ref: "#/$defs/recursive" } },
        },
      },
    };
    const digest = gatewaySubschemaSha256(schema);
    const validator = createGatewayDispatchedValidator(
      [{ key: { kind: "event", eventType: "custom", schemaSha256: digest }, schema }],
      { core: [{ kind: "event", eventType: "custom", schemaSha256: digest }], device: [] },
    );

    expect(
      validator.validate(
        { kind: "event", eventType: "custom" },
        { ...validEvent, payload: { notice: { code: "ok" }, tags: ["one"], choice: 1 } },
      ),
    ).toEqual({ ok: true });
  });

  it.each([
    ["root ref", { $ref: "#/$defs/root", $defs: { root: { type: "object", additionalProperties: false } } }],
    ["external ref", { type: "object", additionalProperties: false, properties: { value: { $ref: "https://example.invalid/schema" } } }],
    ["unresolved ref", { type: "object", additionalProperties: false, properties: { value: { $ref: "#/$defs/missing" } } }],
    ["non-closed nested object", { type: "object", additionalProperties: false, properties: { value: { type: "object" } } }],
    ["patternProperties", { type: "object", additionalProperties: false, patternProperties: {} }],
    ["unevaluatedProperties", { type: "object", additionalProperties: false, unevaluatedProperties: false }],
    ["dynamic ref", { type: "object", additionalProperties: false, $dynamicRef: "#value" }],
    ["dynamic anchor", { type: "object", additionalProperties: false, $dynamicAnchor: "value" }],
    ["not", { type: "object", additionalProperties: false, not: {} }],
    ["conditional", { type: "object", additionalProperties: false, if: {}, then: {} }],
    ["dependent schemas", { type: "object", additionalProperties: false, dependentSchemas: {} }],
    ["contains", { type: "object", additionalProperties: false, contains: {} }],
    ["property names", { type: "object", additionalProperties: false, propertyNames: {} }],
    ["prefix items", { type: "object", additionalProperties: false, prefixItems: [] }],
    ["items array", { type: "object", additionalProperties: false, properties: { values: { type: "array", items: [] } } }],
    ["unsupported composition shape", { type: "object", additionalProperties: false, anyOf: {} }],
  ] as const)("rejects %s from the catalog Schema subset", (_name, schema) => {
    const digest = gatewaySubschemaSha256(schema);
    expect(() =>
      createGatewayDispatchedValidator(
        [{ key: { kind: "event", eventType: "invalid", schemaSha256: digest }, schema }],
        { core: [{ kind: "event", eventType: "invalid", schemaSha256: digest }], device: [] },
      ),
    ).toThrow();
  });

  it("rejects strict-Ajv-invalid catalog schemas after subset checks", () => {
    const schema = {
      type: "object",
      additionalProperties: false,
      properties: { value: { type: "string", format: "not-a-real-format" } },
    };
    const digest = gatewaySubschemaSha256(schema);

    expect(() =>
      createGatewayDispatchedValidator(
        [{ key: { kind: "event", eventType: "invalid-format", schemaSha256: digest }, schema }],
        {
          core: [{ kind: "event", eventType: "invalid-format", schemaSha256: digest }],
          device: [],
        },
      ),
    ).toThrow();
  });
});
