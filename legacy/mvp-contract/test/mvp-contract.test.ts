import { describe, expect, it } from "vitest";
import { Ajv2020 } from "ajv/dist/2020.js";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  encodeAssistantRequest,
  encodeAssistantEvent,
  encodeAssistantResponse,
  encodeNotificationRecord,
  encodeNotificationQuery,
  encodeNotificationSubscribe,
  encodeNotificationUnsubscribe,
  validateWireAssistantMessage,
  validateWireNotificationRecord,
  validateWireNotificationOperation,
} from "../src/wire-codec.ts";

const schemaRoot = resolve(process.cwd(), "mvp-contract/schemas/v1");
const load = (name: string): Record<string, unknown> =>
  JSON.parse(readFileSync(resolve(schemaRoot, name), "utf8")) as Record<string, unknown>;

const exactKeys = (value: Record<string, unknown>, keys: readonly string[]) =>
  Object.keys(value).sort().join("\u0000") === [...keys].sort().join("\u0000");

const codePointCompare = (left: string, right: string): number => {
  const a = Array.from(left).map((value) => value.codePointAt(0) ?? 0);
  const b = Array.from(right).map((value) => value.codePointAt(0) ?? 0);
  for (let i = 0; i < Math.min(a.length, b.length); i += 1) {
    if (a[i] !== b[i]) return (a[i] ?? 0) - (b[i] ?? 0);
  }
  return a.length - b.length;
};

const MAX_U64 = 18_446_744_073_709_551_615n;
const validU64 = (value: unknown, positive = false): value is string =>
  typeof value === "string" && value.length <= 20 && /^(0|[1-9][0-9]*)$/.test(value)
  && BigInt(value) <= MAX_U64 && (!positive || value !== "0");

const validPolicy = (value: unknown): boolean => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const policy = value as Record<string, unknown>;
  if (!exactKeys(policy, ["mode", "package_ids", "field_access", "policy_revision"])) return false;
  if (policy.mode !== "allowlist" && policy.mode !== "denylist") return false;
  if (policy.field_access !== "metadata" && policy.field_access !== "content") return false;
  if (!Array.isArray(policy.package_ids) || !policy.package_ids.every((item) => typeof item === "string")) return false;
  const packages = policy.package_ids as string[];
  if (new Set(packages).size !== packages.length) return false;
  if (packages.some((item, index) => index > 0 && codePointCompare(packages[index - 1]!, item) >= 0)) return false;
  return validU64(policy.policy_revision);
};

const validRecord = (value: unknown): boolean => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  const common = ["kind", "source_epoch", "occurrence_id", "record_key", "record_revision", "cursor", "captured_at_epoch_ms", "capture_revision"];
  if (!common.every((key) => Object.hasOwn(record, key))) return false;
  if (!validU64(record.source_epoch) || !validU64(record.record_revision, true)
    || !validU64(record.cursor, true) || !validU64(record.captured_at_epoch_ms)
    || !validU64(record.capture_revision)) return false;
  if (!["upsert", "delete_tombstone", "loss_marker"].includes(String(record.kind))) return false;
  if (["agent_principal_id", "session_id", "workspace_id", "job_id"].some((key) => Object.hasOwn(record, key))) return false;
  if (record.kind === "upsert") {
    return Object.hasOwn(record, "metadata") && Object.hasOwn(record, "content") && !Object.hasOwn(record, "loss")
      && record.metadata !== null && (record.content === null || typeof record.content === "object");
  }
  if (record.kind === "delete_tombstone") {
    return Object.hasOwn(record, "metadata") && record.metadata !== null && record.content === null && record.loss === null;
  }
  return Object.hasOwn(record, "loss") && record.loss !== null && record.metadata === null && record.content === null
    && typeof record.loss === "object" && record.loss !== null
    && validU64((record.loss as Record<string, unknown>).lost_from_cursor, true)
    && validU64((record.loss as Record<string, unknown>).lost_to_cursor, true);
};

describe("MVP closed schemas and deterministic fixtures", () => {
  it("ships exactly the six versioned closed schemas", () => {
    for (const name of ["notification-policy.schema.json", "notification-record.schema.json", "notification-api.schema.json", "assistant-chat.schema.json", "sms-record.schema.json", "sms-api.schema.json"]) {
      const schema = load(name);
      expect(schema.$schema).toBe("https://json-schema.org/draft/2020-12/schema");
      expect(schema.additionalProperties === false || schema.unevaluatedProperties === false).toBe(true);
    }
  });

  it("accepts empty-default and both policy modes while rejecting order, duplicates, and injection", () => {
    expect(validPolicy({ mode: "allowlist", package_ids: [], field_access: "metadata", policy_revision: "0" })).toBe(true);
    expect(validPolicy({ mode: "denylist", package_ids: ["com.a", "com.😀"], field_access: "content", policy_revision: "1" })).toBe(true);
    expect(validPolicy({ mode: "allowlist", package_ids: ["com.😀", "com.a"], field_access: "metadata", policy_revision: "2" })).toBe(false);
    expect(validPolicy({ mode: "allowlist", package_ids: ["com.a", "com.a"], field_access: "metadata", policy_revision: "2" })).toBe(false);
    expect(validPolicy({ mode: "allowlist", package_ids: [], field_access: "metadata", policy_revision: "0", agent_principal_id: "model" })).toBe(false);
  });

  it("keeps all three record variants closed and strips content in metadata mode", () => {
    const common = { source_epoch: "1", occurrence_id: "o1", record_key: "k1", record_revision: "1", cursor: "1", captured_at_epoch_ms: "10", capture_revision: "1" };
    expect(validRecord({ ...common, kind: "upsert", metadata: { package_name: "com.mail", app_label: null, channel_id: null, posted_at_epoch_ms: "10" }, content: null, loss: null })).toBe(false);
    expect(validRecord({ ...common, kind: "upsert", metadata: { package_name: "com.mail", app_label: null, channel_id: null, posted_at_epoch_ms: "10" }, content: { title: "t", body: "b" } })).toBe(true);
    expect(validRecord({ ...common, kind: "delete_tombstone", metadata: { package_name: "com.mail", app_label: null, channel_id: null, posted_at_epoch_ms: "10" }, content: null, loss: null })).toBe(true);
    expect(validRecord({ ...common, kind: "loss_marker", metadata: null, content: null, loss: { lost_from_cursor: "2", lost_to_cursor: "3", reason: "QUEUE_OVERFLOW" } })).toBe(true);
    expect(validRecord({ ...common, kind: "upsert", metadata: {}, content: null, loss: null, session_id: "model" })).toBe(false);
  });

  it("closes query/subscribe responses and all response states, including limit boundaries", () => {
    const api = load("notification-api.schema.json");
    expect(JSON.stringify(api)).toContain("on_demand");
    expect(JSON.stringify(api)).toContain("auto_send");
    expect(JSON.stringify(api)).toContain("waiting_device");
    expect(JSON.stringify(api)).toContain("failed");
    expect(JSON.stringify(api)).toContain("mobile.notifications.query");
    expect(JSON.stringify(api)).toContain("packages");
    expect(JSON.stringify(api)).toContain("metadata");
    expect(JSON.stringify(api)).toContain("content");
    for (const limit of [0, 1, 100, 101]) {
      const valid = Number.isInteger(limit) && limit >= 1 && limit <= 100;
      expect(valid).toBe(limit >= 1 && limit <= 100);
    }
  });

  it("keeps assistant chat closed and bounded for text plus audio while rejecting model-supplied invocation context", () => {
    const chat = load("assistant-chat.schema.json");
    expect(JSON.stringify(chat)).toContain("text");
    expect(JSON.stringify(chat)).not.toContain("upload_url");
    expect(JSON.stringify(chat)).not.toContain("agent_principal_id");
  });

  it("round-trips runtime records through the exact closed wire field names", () => {
    const wire = encodeNotificationRecord({
      kind: "upsert", recordId: "record-1", packageId: "com.example.mail", title: "title", content: "body",
      sourceEpoch: 1n, cursor: 2n, captureRevision: 3n,
    }, { capturedAtEpochMs: 10n });
    expect(Object.keys(wire).sort()).toEqual(["capture_revision", "captured_at_epoch_ms", "content", "cursor", "kind", "loss", "metadata", "occurrence_id", "record_key", "record_revision", "source_epoch"]);
    expect(validateWireNotificationRecord(wire)).toBe(true);
    expect(validateWireNotificationRecord({ ...wire, recordId: "forged" })).toBe(false);
  });

  it("does not silently rewrite malformed delete tombstones", () => {
    const malformed = {
      kind: "delete_tombstone" as const,
      recordId: "deleted-1",
      packageId: "com.example.mail",
      title: "must-not-cross-boundary",
      content: null,
      sourceEpoch: 1n,
      cursor: 1n,
      captureRevision: 1n,
    };
    expect(() => encodeNotificationRecord(malformed)).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(() => encodeNotificationRecord({ ...malformed, title: null, content: "must-not-cross-boundary" }))
      .toThrow("WIRE_RECORD_UNREPRESENTABLE");

    const valid = { ...malformed, title: null };
    const wire = encodeNotificationRecord(valid);
    expect(wire.metadata?.package_name).toBe("com.example.mail");
    expect(wire.content).toBeNull();
    expect(validateWireNotificationRecord(wire)).toBe(true);
  });

  it("encodes assistant attachments without leaking internal camel-case fields", () => {
    const request = encodeAssistantRequest({
      operationId: "op-1", text: "hello", attachments: [{ kind: "image", artifactId: "artifact-image-1", filename: "photo.png", mimeType: "image/png", sizeBytes: 3, sha256: "a".repeat(64) }],
    });
    const attachments = request.attachments as readonly Record<string, unknown>[];
    expect(Object.keys(attachments[0]!).sort()).toEqual(["artifact_id", "byte_length", "display_name", "kind", "media_type", "sha256"]);
    expect(validateWireAssistantMessage(request)).toBe(true);
    const response = encodeAssistantResponse({ operationId: "op-1", reply: "done" });
    expect(validateWireAssistantMessage(response)).toBe(true);
    expect(validateWireAssistantMessage({ ...request, session: "model" })).toBe(false);
  });

  it("rejects path-shaped display names and canonicalizes accepted SHA-256 digests", () => {
    const schemaValidator = new Ajv2020({ strict: false }).compile(load("assistant-chat.schema.json"));
    const attachment = {
      kind: "image", artifact_id: "artifact-image-1", media_type: "image/png",
      byte_length: 1, sha256: "A".repeat(64), display_name: "photo.png",
    };
    const request = (display_name: string) => ({
      kind: "request", operation_id: "op", text: "x", attachments: [{ ...attachment, display_name }],
    });

    expect(schemaValidator(request("photos/secret.png"))).toBe(false);
    expect(schemaValidator(request("photos\\secret.png"))).toBe(false);
    expect(validateWireAssistantMessage(request("photos/secret.png"))).toBe(false);
    expect(validateWireAssistantMessage(request("photos\\secret.png"))).toBe(false);
    expect(schemaValidator(request("photo.png"))).toBe(true);
    expect(validateWireAssistantMessage(request("photo.png"))).toBe(true);
    expect(encodeAssistantRequest({
      operationId: "op", text: "x", attachments: [{
        kind: "image", artifactId: "artifact-image-1", filename: "photo.png", mimeType: "image/png",
        sizeBytes: 1, sha256: "A".repeat(64),
      }],
    }).attachments).toMatchObject([{ sha256: "a".repeat(64) }]);
  });

  it("encodes a committed audio artifact with a bounded duration", () => {
    const wire = encodeAssistantRequest({
      operationId: "op-audio",
      text: "please transcribe",
      attachments: [{
        kind: "audio",
        artifactId: "artifact-audio-1",
        filename: "voice.m4a",
        mimeType: "audio/mp4",
        sizeBytes: 10485760,
        sha256: "a".repeat(64),
        durationMs: 120000,
      }],
    });
    expect(wire.attachments).toEqual([{
      kind: "audio", artifact_id: "artifact-audio-1", media_type: "audio/mp4",
      byte_length: 10485760, sha256: "a".repeat(64), display_name: "voice.m4a", duration_ms: 120000,
    }]);
    expect(validateWireAssistantMessage(wire)).toBe(true);
  });

  it("rejects audio outside the closed size, duration, and field rules", () => {
    const valid = {
      kind: "audio", artifact_id: "artifact-audio-1", media_type: "audio/mp4",
      byte_length: 1, sha256: "a".repeat(64), display_name: "voice.m4a", duration_ms: 1,
    };
    expect(validateWireAssistantMessage({
      kind: "request", operation_id: "op", text: "x", attachments: [{ ...valid, duration_ms: 120001 }],
    })).toBe(false);
    expect(validateWireAssistantMessage({
      kind: "request", operation_id: "op", text: "x", attachments: [{ ...valid, byte_length: 10485761 }],
    })).toBe(false);
    expect(validateWireAssistantMessage({
      kind: "request", operation_id: "op", text: "x", attachments: [{ ...valid, uri: "content://forbidden" }],
    })).toBe(false);
  });

  it("enforces the 50 MiB aggregate attachment limit across mixed media", () => {
    const image = { kind: "image", artifact_id: "artifact-image-1", media_type: "image/png", byte_length: 25 * 1024 * 1024, sha256: "a".repeat(64), display_name: "image.png" };
    const file = { kind: "file", artifact_id: "artifact-file-1", media_type: "application/pdf", byte_length: 15 * 1024 * 1024, sha256: "b".repeat(64), display_name: "file.pdf" };
    const audio = { kind: "audio", artifact_id: "artifact-audio-1", media_type: "audio/mp4", byte_length: 10 * 1024 * 1024, sha256: "c".repeat(64), display_name: "voice.m4a", duration_ms: 120000 };
    const request = (attachments: unknown[]) => ({ kind: "request", operation_id: "op", text: "x", attachments });
    expect(validateWireAssistantMessage(request([image, file, audio]))).toBe(true);
    expect(validateWireAssistantMessage(request([{ ...image, byte_length: 25 * 1024 * 1024 }, { ...file, byte_length: 15 * 1024 * 1024 + 1 }, audio]))).toBe(false);
  });

  it("keeps image and file MIME boundaries aligned with the closed codec rules", () => {
    const attachment = (kind: string, mediaType: string) => ({
      kind, artifact_id: "artifact-1", media_type: mediaType, byte_length: 1, sha256: "a".repeat(64), display_name: "attachment",
    });
    const request = (value: unknown) => ({ kind: "request", operation_id: "op", text: "x", attachments: [value] });
    expect(validateWireAssistantMessage(request(attachment("image", "image/png")))).toBe(true);
    expect(validateWireAssistantMessage(request(attachment("file", "application/pdf")))).toBe(true);
    expect(validateWireAssistantMessage(request(attachment("image", "application/pdf")))).toBe(false);
    expect(validateWireAssistantMessage(request(attachment("file", "image/png")))).toBe(false);
    expect(validateWireAssistantMessage(request(attachment("file", "application/octet-stream")))).toBe(false);
  });

  it("encodes ordered delta, complete, and failed reply events", () => {
    expect(encodeAssistantEvent({ operationId: "op", messageId: "m", sequence: 1n, event: "delta", text: "hel" }))
      .toEqual({ kind: "event", operation_id: "op", message_id: "m", sequence: "1", event: "delta", text: "hel" });
    expect(validateWireAssistantMessage({
      kind: "event", operation_id: "op", message_id: "m", sequence: "2", event: "failed", text: "", error: "CONNECTION_FENCED",
    })).toBe(true);
    expect(validateWireAssistantMessage({
      kind: "event", operation_id: "op", message_id: "m", sequence: "0", event: "delta", text: "x",
    })).toBe(false);
  });

  it("keeps event sequence at positive u64 boundaries", () => {
    const base = { kind: "event", operation_id: "op", message_id: "m", event: "delta", text: "x" };
    expect(validateWireAssistantMessage({ ...base, sequence: "18446744073709551615" })).toBe(true);
    expect(validateWireAssistantMessage({ ...base, sequence: "18446744073709551616" })).toBe(false);
    expect(() => encodeAssistantEvent({ operationId: "op", messageId: "m", sequence: 18_446_744_073_709_551_616n, event: "delta", text: "x" })).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    const sequencePattern = (load("assistant-chat.schema.json").$defs as Record<string, unknown>);
    const event = sequencePattern.event as Record<string, unknown>;
    const properties = event.properties as Record<string, unknown>;
    expect(new RegExp((properties.sequence as Record<string, unknown>).pattern as string).test("10000000000000000000")).toBe(true);
    expect(new RegExp((properties.sequence as Record<string, unknown>).pattern as string).test("18446744073709551615")).toBe(true);
    expect(new RegExp((properties.sequence as Record<string, unknown>).pattern as string).test("18446744073709551616")).toBe(false);
  });

  it("binds notification subscription lifecycle wire requests to a policy revision", () => {
    const query = encodeNotificationQuery({ operationId: "op-1", mode: "on_demand", policyRevision: 3n, limit: 10, packages: ["com.example.mail"], content: "metadata" });
    const subscribe = encodeNotificationSubscribe({ subscriptionId: "sub-1", policyRevision: 3n, packages: ["com.example.mail"], content: "content" });
    const unsubscribe = encodeNotificationUnsubscribe({ subscriptionId: "sub-1", policyRevision: 3n });
    expect(validateWireNotificationOperation(query)).toBe(true);
    expect(validateWireNotificationOperation(subscribe)).toBe(true);
    expect(validateWireNotificationOperation(unsubscribe)).toBe(true);
    expect(validateWireNotificationOperation({ ...subscribe, policy_revision: "4", extra: true })).toBe(false);
  });

  it("enforces the decimal u64 ceiling at the wire boundary", () => {
    const aboveU64 = "18446744073709551616";
    expect(validateWireNotificationOperation({
      operation: "mobile.notifications.query", mode: "on_demand", operation_id: "op-1",
      policy_revision: aboveU64, limit: 1,
    })).toBe(false);
    expect(() => encodeNotificationQuery({ operationId: "op-1", mode: "on_demand", policyRevision: 18_446_744_073_709_551_616n, limit: 1 })).toThrow("WIRE_RECORD_UNREPRESENTABLE");
    expect(validateWireNotificationRecord({
      kind: "upsert", source_epoch: aboveU64, occurrence_id: "o1", record_key: "k1", record_revision: "1", cursor: "1",
      captured_at_epoch_ms: "0", capture_revision: "0",
      metadata: { package_name: "com.example.mail", app_label: null, channel_id: null, posted_at_epoch_ms: "0" },
      content: null, loss: null,
    })).toBe(false);
  });
});
