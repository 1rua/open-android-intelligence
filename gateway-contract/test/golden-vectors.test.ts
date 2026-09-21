import { createHash } from "node:crypto";

import { Ajv2020 } from "ajv/dist/2020.js";
import addFormatsImport from "ajv-formats";
import canonicalize from "canonicalize";
import { describe, expect, it } from "vitest";

import authSessionsDocument from "../vectors/auth-sessions.json" with { type: "json" };
import attachmentsDocument from "../vectors/attachments.json" with { type: "json" };
import deviceRequestsDocument from "../vectors/device-requests.json" with { type: "json" };
import fixtureMetaSchema from "../vectors/dispatched-schema-fixtures-1.0.0.schema.json" with { type: "json" };
import fixtureRegistry from "../vectors/dispatched-schema-fixtures.json" with { type: "json" };
import protocolNegotiationDocument from "../vectors/protocol-negotiation.json" with { type: "json" };
import requestSignaturesDocument from "../vectors/request-signatures.json" with { type: "json" };
import sseEventsDocument from "../vectors/sse-events.json" with { type: "json" };
import vectorMetaSchema from "../vectors/vector-set-1.0.0.schema.json" with { type: "json" };
import {
  canonicalRequestSignatureInput,
  canonicalRequestTarget,
  type SignedRequestInput,
} from "../src/request-signature.js";
import {
  createGatewayDispatchedValidator,
  gatewaySubschemaSha256,
  type GatewayDispatchedValidator,
  type GatewayLogicalSubschemaKey,
  type GatewaySubschemaCatalogEntry,
  type GatewaySubschemaKey,
  type TrustedGatewayDispatch,
  type VerifiedSchemaBindingSet,
} from "../src/dispatched-schema-validator.js";
import {
  maximumDeviceRequestQueueSeconds,
  nextAttachmentState,
  nextDeviceRequestState,
  type AttachmentEvent,
  type AttachmentState,
  type DeviceRequestEvent,
  type DeviceRequestState,
  type DeviceRequestRisk,
} from "../src/state-machines.js";
import {
  validateGatewayValue,
  type GatewaySchemaName,
} from "../src/schema-registry.js";

const addFormats = addFormatsImport as unknown as (ajv: Ajv2020) => Ajv2020;
const bindingSetId = "gateway-core-fixtures-v1";

type VectorOperation =
  | "request.target"
  | "request.signature"
  | "schema.validate"
  | "schema.validate_dispatched"
  | "attachment.transition"
  | "device.transition"
  | "device.maximum_queue_seconds";

type VectorErrorCode = "SCHEMA_INVALID" | "NON_CANONICAL_TARGET" | "INVALID_STATE_TRANSITION";

type VectorExpected =
  | { outcome: "value"; value: Record<string, unknown> }
  | { outcome: "error"; code: VectorErrorCode };

type VectorCase = {
  id: string;
  operation: VectorOperation;
  input: Record<string, unknown>;
  expected: VectorExpected;
};

type VectorDocument = {
  formatVersion: string;
  protocolVersion: string;
  vectorSet: string;
  cases: VectorCase[];
};

type VectorFile = Readonly<{
  fileName: string;
  vectorSet: string;
  allowedOperations: readonly VectorOperation[];
  document: VectorDocument;
}>;

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
const asVectorDocument = (value: unknown): VectorDocument => value as VectorDocument;
const asRecord = (value: unknown, label: string): Record<string, unknown> => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`INVALID_VECTOR_RECORD:${label}`);
  }
  return value as Record<string, unknown>;
};
const stringField = (value: Record<string, unknown>, field: string): string => {
  const result = value[field];
  if (typeof result !== "string") throw new Error(`INVALID_VECTOR_STRING:${field}`);
  return result;
};
const vectorFiles: readonly VectorFile[] = [
  {
    fileName: "request-signatures.json",
    vectorSet: "request-signatures",
    allowedOperations: ["request.target", "request.signature"],
    document: asVectorDocument(requestSignaturesDocument),
  },
  {
    fileName: "protocol-negotiation.json",
    vectorSet: "protocol-negotiation",
    allowedOperations: ["schema.validate"],
    document: asVectorDocument(protocolNegotiationDocument),
  },
  {
    fileName: "auth-sessions.json",
    vectorSet: "auth-sessions",
    allowedOperations: ["schema.validate"],
    document: asVectorDocument(authSessionsDocument),
  },
  {
    fileName: "attachments.json",
    vectorSet: "attachments",
    allowedOperations: ["schema.validate", "attachment.transition"],
    document: asVectorDocument(attachmentsDocument),
  },
  {
    fileName: "sse-events.json",
    vectorSet: "sse-events",
    allowedOperations: ["schema.validate_dispatched"],
    document: asVectorDocument(sseEventsDocument),
  },
  {
    fileName: "device-requests.json",
    vectorSet: "device-requests",
    allowedOperations: [
      "schema.validate_dispatched",
      "device.transition",
      "device.maximum_queue_seconds",
    ],
    document: asVectorDocument(deviceRequestsDocument),
  },
];

const allOperations: readonly VectorOperation[] = [
  "request.target",
  "request.signature",
  "schema.validate",
  "schema.validate_dispatched",
  "attachment.transition",
  "device.transition",
  "device.maximum_queue_seconds",
];

const vectorMetaValidator = addFormats(new Ajv2020({ strict: true })).compile(vectorMetaSchema);

const logicalKeyOf = (key: GatewaySubschemaKey): GatewayLogicalSubschemaKey => {
  const { schemaSha256: _schemaSha256, ...logicalKey } = key;
  return logicalKey as GatewayLogicalSubschemaKey;
};

const loadSharedFixtureRegistry = (
  candidate: FixtureRegistry,
): { entries: GatewaySubschemaCatalogEntry[]; bindings: FixtureBinding[] } => {
  const validate = addFormats(new Ajv2020({ strict: true })).compile(fixtureMetaSchema);
  if (!validate(candidate)) throw new Error("INVALID_FIXTURE_REGISTRY");
  if (
    candidate.formatVersion !== "1.0.0" ||
    candidate.catalogEntries.length !== 7 ||
    candidate.bindingSets.length !== 1 ||
    candidate.bindingSets[0]?.id !== bindingSetId
  ) {
    throw new Error("INVALID_FIXTURE_REGISTRY");
  }

  const bindings = candidate.bindingSets[0]!.bindings;
  if (bindings.length !== 7) throw new Error("INVALID_FIXTURE_REGISTRY");

  for (const entry of candidate.catalogEntries) {
    const canonical = canonicalize(entry.schema);
    if (canonical === undefined) throw new Error("INVALID_FIXTURE_REGISTRY");
    const digest = `sha256:${createHash("sha256").update(canonical, "utf8").digest("hex")}`;
    if (digest !== entry.key.schemaSha256 || gatewaySubschemaSha256(entry.schema) !== digest) {
      throw new Error("INVALID_FIXTURE_REGISTRY");
    }
    const binding = bindings.find(
      (candidateBinding) => canonicalize(candidateBinding.key) === canonicalize(logicalKeyOf(entry.key)),
    );
    if (binding === undefined || binding.schemaSha256 !== digest) {
      throw new Error("INVALID_FIXTURE_REGISTRY");
    }
  }

  return {
    entries: candidate.catalogEntries.map(({ key, schema }) => ({ key, schema })),
    bindings,
  };
};

const sharedFixture = loadSharedFixtureRegistry(registry);
const sharedDispatchedValidator = (() => {
  const core = sharedFixture.bindings
    .filter((binding) => binding.key.kind !== "device.request")
    .map((binding) => ({ ...binding.key, schemaSha256: binding.schemaSha256 }));
  const device = sharedFixture.bindings
    .filter((binding) => binding.key.kind === "device.request")
    .map((binding) => ({ ...binding.key, schemaSha256: binding.schemaSha256 }));
  const bindings: VerifiedSchemaBindingSet = {
    core: core as VerifiedSchemaBindingSet["core"],
    device: device as VerifiedSchemaBindingSet["device"],
  };
  return createGatewayDispatchedValidator(sharedFixture.entries, bindings);
})();

const decodeBodyHex = (value: unknown): Uint8Array => {
  const hex = stringField(asRecord({ value }, "bodyHex"), "value");
  if (!/^(?:[0-9a-f]{2})*$/.test(hex)) throw new Error("INVALID_VECTOR_HEX");
  const bytes = Buffer.from(hex, "hex");
  if (bytes.length * 2 !== hex.length) throw new Error("INVALID_VECTOR_HEX");
  return Uint8Array.from(bytes);
};

const runWithVectorError = (
  operation: () => Record<string, unknown>,
): VectorExpected => {
  try {
    return { outcome: "value", value: operation() };
  } catch (cause) {
    const code = cause instanceof Error ? cause.message : "";
    if (
      code !== "SCHEMA_INVALID" &&
      code !== "NON_CANONICAL_TARGET" &&
      code !== "INVALID_STATE_TRANSITION"
    ) {
      throw cause;
    }
    return { outcome: "error", code };
  }
};

const runVectorCase = (
  vectorCase: VectorCase,
  consumedOperations: Set<VectorOperation>,
): VectorExpected => {
  consumedOperations.add(vectorCase.operation);
  const input = vectorCase.input;
  switch (vectorCase.operation) {
    case "request.target":
      return runWithVectorError(() => ({
        canonicalTarget: canonicalRequestTarget(stringField(input, "target")),
      }));
    case "request.signature": {
      const bodyHex = stringField(input, "bodyHex");
      const signedInput: SignedRequestInput = {
        method: stringField(input, "method") as SignedRequestInput["method"],
        target: stringField(input, "target"),
        accountId: stringField(input, "accountId"),
        deviceId: stringField(input, "deviceId"),
        sessionId: stringField(input, "sessionId"),
        requestId: stringField(input, "requestId"),
        timestamp: stringField(input, "timestamp"),
        nonce: stringField(input, "nonce"),
        body: decodeBodyHex(bodyHex),
      };
      return runWithVectorError(() => ({
        preimageHex: Buffer.from(canonicalRequestSignatureInput(signedInput)).toString("hex"),
      }));
    }
    case "schema.validate": {
      const schemaName = stringField(input, "schemaName") as GatewaySchemaName;
      const result = validateGatewayValue(schemaName, input.value);
      return result.ok
        ? { outcome: "value", value: { valid: true } }
        : { outcome: "error", code: "SCHEMA_INVALID" };
    }
    case "schema.validate_dispatched": {
      if (stringField(input, "fixtureBindingSetId") !== bindingSetId) {
        throw new Error("INVALID_FIXTURE_BINDING_SET");
      }
      const result = sharedDispatchedValidator.validate(
        input.dispatch as TrustedGatewayDispatch,
        input.value,
      );
      return result.ok
        ? { outcome: "value", value: { valid: true } }
        : { outcome: "error", code: "SCHEMA_INVALID" };
    }
    case "attachment.transition":
      return runWithVectorError(() => ({
        nextState: nextAttachmentState(
          stringField(input, "current") as AttachmentState,
          stringField(input, "event") as AttachmentEvent,
        ),
      }));
    case "device.transition":
      return runWithVectorError(() => ({
        nextState: nextDeviceRequestState(
          stringField(input, "current") as DeviceRequestState,
          stringField(input, "event") as DeviceRequestEvent,
        ),
      }));
    case "device.maximum_queue_seconds":
      return runWithVectorError(() => ({
        seconds: maximumDeviceRequestQueueSeconds(
          stringField(input, "risk") as DeviceRequestRisk,
        ),
      }));
  }
};

describe("Gateway Protocol v2 golden vector contract", () => {
  it("validates all six files as closed 1.0.0 documents with file and operation ownership", () => {
    const ids = new Set<string>();
    for (const vectorFile of vectorFiles) {
      expect(vectorMetaValidator(vectorFile.document), vectorFile.fileName).toBe(true);
      expect(Object.keys(vectorFile.document).sort()).toEqual([
        "cases",
        "formatVersion",
        "protocolVersion",
        "vectorSet",
      ]);
      expect(vectorFile.document.vectorSet).toBe(vectorFile.vectorSet);
      expect(vectorFile.document.cases.length).toBeGreaterThan(1);

      let valueCount = 0;
      let errorCount = 0;
      for (const vectorCase of vectorFile.document.cases) {
        expect(Object.keys(vectorCase).sort()).toEqual(["expected", "id", "input", "operation"]);
        expect(ids.has(vectorCase.id), `duplicate vector id: ${vectorCase.id}`).toBe(false);
        ids.add(vectorCase.id);
        expect(vectorFile.allowedOperations).toContain(vectorCase.operation);
        if (vectorCase.expected.outcome === "value") valueCount += 1;
        else errorCount += 1;
      }
      expect(valueCount, `${vectorFile.fileName} value cases`).toBeGreaterThan(0);
      expect(errorCount, `${vectorFile.fileName} error cases`).toBeGreaterThan(0);
    }
  });

  it("rejects unknown fields and operation-specific union violations", () => {
    const document = vectorFiles[1]!.document;
    expect(vectorMetaValidator({ ...document, unexpected: true })).toBe(false);

    const withUnknownCase = structuredClone(document);
    (withUnknownCase.cases[0] as Record<string, unknown>).unexpected = true;
    expect(vectorMetaValidator(withUnknownCase)).toBe(false);

    const withWrongErrorCode = structuredClone(document);
    withWrongErrorCode.cases[0]!.expected = {
      outcome: "error",
      code: "INVALID_STATE_TRANSITION",
    };
    expect(vectorMetaValidator(withWrongErrorCode)).toBe(false);

    const withWrongVectorSetOperation = structuredClone(document);
    withWrongVectorSetOperation.cases[0]!.operation = "request.target";
    expect(vectorMetaValidator(withWrongVectorSetOperation)).toBe(false);
  });

  it("uses every operation through a real production consumer and compares each result", () => {
    const consumedOperations = new Set<VectorOperation>();
    for (const vectorFile of vectorFiles) {
      for (const vectorCase of vectorFile.document.cases) {
        const actual = runVectorCase(vectorCase, consumedOperations);
        expect(actual, `${vectorFile.fileName}:${vectorCase.id}`).toEqual(vectorCase.expected);
      }
    }
    expect([...consumedOperations].sort()).toEqual([...allOperations].sort());
  });

  it("keeps request signatures on preimageHex and enforces binary/time formats", () => {
    const signatureCases = vectorFiles[0]!.document.cases.filter(
      (vectorCase) => vectorCase.operation === "request.signature",
    );
    for (const vectorCase of signatureCases) {
      const input = vectorCase.input;
      expect(stringField(input, "bodyHex")).toMatch(/^(?:[0-9a-f]{2})*$/);
      expect(stringField(input, "timestamp")).toMatch(
        /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$/,
      );
      if (vectorCase.expected.outcome === "value") {
        expect(vectorCase.expected.value.preimageHex).toMatch(/^(?:[0-9a-f]{2})+$/);
        expect(Object.hasOwn(vectorCase.expected, "expectedHex")).toBe(false);
        expect(Object.hasOwn(vectorCase.expected.value, "expectedHex")).toBe(false);
        expect(Object.hasOwn(vectorCase.expected.value, "preimageHex")).toBe(true);
      }
    }
  });

  it("requires the one shared dispatched fixture registry before constructing its validator", () => {
    expect(registry.formatVersion).toBe("1.0.0");
    expect(registry.catalogEntries).toHaveLength(7);
    expect(registry.bindingSets).toHaveLength(1);
    expect(registry.bindingSets[0]?.id).toBe(bindingSetId);
    expect(sharedFixture.entries).toHaveLength(7);
    expect(sharedFixture.bindings).toHaveLength(7);
    expect(Object.isFrozen(sharedDispatchedValidator)).toBe(true);

    const validateFixtureRegistry = addFormats(new Ajv2020({ strict: true })).compile(fixtureMetaSchema);
    expect(validateFixtureRegistry(fixtureRegistry)).toBe(true);
    expect(validateFixtureRegistry({ ...registry, unexpected: true })).toBe(false);

    for (const vectorFile of vectorFiles) {
      for (const vectorCase of vectorFile.document.cases) {
        if (vectorCase.operation !== "schema.validate_dispatched") continue;
        expect(vectorCase.input.fixtureBindingSetId).toBe(bindingSetId);
        expect(Object.keys(vectorCase.input).sort()).toEqual([
          "dispatch",
          "fixtureBindingSetId",
          "value",
        ]);
        const dispatch = asRecord(vectorCase.input.dispatch, `${vectorCase.id}:dispatch`);
        expect(Object.keys(dispatch)).not.toContain("schemaSha256");
        expect(Object.keys(dispatch)).not.toContain("schema");
        expect(Object.keys(dispatch)).not.toContain("binding");
        expect(Object.keys(dispatch)).not.toContain("resolver");
        expect(Object.keys(dispatch)).not.toContain("validator");
      }
    }
  });
});
