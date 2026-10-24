/// <reference types="node" />

import { describe, expect, it } from "vitest";
import { validateSchema } from "../src/schema-validator.js";
import { canonicalBytes, sha256B64Url, signingPreimage } from "../src/encoding.js";
import { readFileSync } from "node:fs";
import { DeterministicDeviceSecurityBackend, verifyTransportFrame } from "../src/control-envelope.js";
import { signTestOnly } from "../src/crypto.js";
import { parseSignatureDomain } from "../src/profile.js";
import type { KeyRingState } from "../src/key-ring.js";
import {
  createTask9PreReplayIntegrityGate,
  createVerifiedCaptureAuthority,
  createVerifiedEventAckAuthority,
  createServerSubscriptionRouter,
  projectVerifiedDeviceEvent,
  projectVerifiedEventAck,
  validateDeviceEvent,
  validateEventAck,
  type EventAckStore,
  type VerifiedCaptureAuthority,
  type VerifiedEventAckAuthority,
} from "../src/event-contract.js";

const EPOCH = "018f4f9a-4444-4444-8444-444444444444";
const OCCURRENCE = "018f4f9a-4444-4444-8444-444444444445";
const B32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
type TestJwk = JsonWebKey & {
  alg: "ES256"; crv: "P-256"; kid: string; kty: "EC"; use: "sig"; x: string; y: string;
};
const readJwk = (name: string): TestJwk => JSON.parse(
  readFileSync(new URL(`../test-only/keys/${name}`, import.meta.url), "utf8"),
) as TestJwk;
const DEVICE_PRIVATE = readJwk("device-a-private.jwk.json");
const DEVICE_PUBLIC = readJwk("device-a-public.jwk.json");
const BRIDGE_PRIVATE = readJwk("bridge-command-private.jwk.json");
const BRIDGE_PUBLIC = readJwk("bridge-command-public.jwk.json");
const REVISION = {
  pairing_generation: "3",
  authorization_epoch: "7",
  scope_revisions: { "notifications.metadata": "4" },
};

const record = {
  schema_id: "urn:open-android-intelligence:mvp:v1:notification-record",
  source_epoch: EPOCH,
  cursor: "9",
  record_revision: "2",
  captured_at: "2026-08-11T00:00:00.000Z",
  event_kind: "upsert",
  metadata: { notification_id: "n1", package_name: "com.example", posted_at: "2026-08-11T00:00:00.000Z", category: null, is_ongoing: false, is_clearable: true },
  content: null,
  loss: null,
};

const validEvent = {
  source_epoch: EPOCH,
  occurrence_id: OCCURRENCE,
  record_key: "notification.n1",
  record_revision: "2",
  cursor: "9",
  captured_at: "2026-08-11T00:00:00.000Z",
  event_kind: "upsert",
  source_capability: "notifications.metadata",
  capture_revision: REVISION,
  record,
  loss: null,
};

const validDelete = { ...validEvent, event_kind: "delete_tombstone", record: null, loss: null };
const validLoss = {
  ...validEvent,
  event_kind: "loss_marker",
  record_key: null,
  record_revision: null,
  record: null,
  loss: { lost_from_cursor: "4", lost_to_cursor: "8", reason: "queue_limit" },
};

const authority = (overrides: Partial<Parameters<typeof createVerifiedCaptureAuthority>[0]> = {}) =>
  createVerifiedCaptureAuthority({
    tenantId: "tenant-a",
    humanPrincipalId: "human-a",
    deviceId: "device-a",
    sourceCapability: "notifications.metadata",
    sourceEpoch: EPOCH,
    revision: { pairingGeneration: 3n, authorizationEpoch: 7n, scopeRevisions: new Map([["notifications.metadata", 4n]]) },
    ...overrides,
  });

const ackAuthority = (overrides: Partial<Parameters<typeof createVerifiedEventAckAuthority>[0]> = {}) =>
  createVerifiedEventAckAuthority({
    tenantId: "tenant-a",
    humanPrincipalId: "human-a",
    deviceId: "device-a",
    sourceEpoch: EPOCH,
    sourceCapability: "notifications.metadata",
    highestContiguousCursor: 9n,
    ...overrides,
  });

const task9Gate = (input: Readonly<{
  capture?: VerifiedCaptureAuthority | null;
  ack?: VerifiedEventAckAuthority | null;
  calls?: { capture: number; ack: number };
}> = {}) => createTask9PreReplayIntegrityGate({
  loadCaptureAuthority: async () => {
    if (input.calls) input.calls.capture += 1;
    return input.capture === undefined ? authority() : input.capture;
  },
  loadEventAckAuthority: async () => {
    if (input.calls) input.calls.ack += 1;
    return input.ack === undefined ? ackAuthority() : input.ack;
  },
});

const ringState = (publicJwk: TestJwk): KeyRingState => ({
  activeKeyId: publicJwk.kid,
  bindingGeneration: 3n,
  keys: new Map([[publicJwk.kid, {
    keyId: publicJwk.kid, publicJwk, lifecycle: "active",
    activatedAt: "2026-08-10T00:00:00.000Z", signingNotAfter: null, liveVerifyUntil: null,
  }]]),
  pending: null,
  activationOutbox: new Map(),
});

const securityBackend = () => new DeterministicDeviceSecurityBackend({
  credential: {
    credentialId: "credential", tenantId: "tenant-a", humanPrincipalId: "human-a",
    deviceId: "device-a", pairingGeneration: 3n, active: true,
  },
  initialConnection: { generation: 0n, fenceRevision: 0n },
  keyRings: [
    { id: { owner: "device-installation", credentialId: "credential" }, snapshot: { storeRevision: 1n, state: ringState(DEVICE_PUBLIC) } },
    { id: { owner: "bridge-command", credentialId: "credential" }, snapshot: { storeRevision: 1n, state: ringState(BRIDGE_PUBLIC) } },
  ],
});

const task9Wire = (
  messageType: "device_event" | "event_ack" | "device_ping",
  payload: Record<string, unknown>,
  options: Readonly<{ connectionGeneration?: string; privateJwk?: TestJwk }> = {},
): Uint8Array => {
  const bridge = messageType === "event_ack";
  const direction = bridge ? "bridge-to-app" : "app-to-bridge";
  const publicJwk = bridge ? BRIDGE_PUBLIC : DEVICE_PUBLIC;
  const privateJwk = options.privateJwk ?? (bridge ? BRIDGE_PRIVATE : DEVICE_PRIVATE);
  const header = {
    protocol_version: "1.0",
    message_schema: `urn:open-android-intelligence:protocol:v1:message:${messageType}`,
    message_type: messageType,
    message_id: bridge ? "018f4f9a-4444-4444-8444-444444444448"
      : messageType === "device_ping" ? "018f4f9a-4444-4444-8444-444444444449"
        : "018f4f9a-4444-4444-8444-444444444447",
    key_id: publicJwk.kid,
    direction,
    sequence: "1",
    issued_at: "2026-08-11T00:00:00.000Z",
    expires_at: bridge ? "2026-08-11T00:05:00.000Z"
      : messageType === "device_ping" ? "2026-08-11T00:01:00.000Z"
        : "2026-08-11T01:00:00.000Z",
    payload_digest: sha256B64Url(canonicalBytes(payload)),
    device_id: "device-a",
    pairing_generation: "3",
    connection_generation: options.connectionGeneration ?? "1",
  };
  const signature = signTestOnly(privateJwk, signingPreimage(parseSignatureDomain(`control/${direction}`), { header, payload }));
  return canonicalBytes({ header, payload, signature });
};

describe("Task 9 closed event payloads", () => {
  it("accepts all three event branches and rejects cross-branch fields", () => {
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:device_event", validEvent)).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:device_event", validDelete)).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:device_event", validLoss)).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:device_event", { ...validDelete, record_key: null })).toThrowError("SCHEMA_INVALID");
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:device_event", { ...validLoss, loss: { ...validLoss.loss, lost_from_cursor: "9" } })).toThrowError("SCHEMA_INVALID");
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:device_event", { ...validEvent, agent_principal_id: "agent-b" })).toThrowError("SCHEMA_INVALID");
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:device_event", { ...validEvent, unknown_field: true })).toThrowError("SCHEMA_INVALID");
  });

  it("accepts only the exact ACK shape", () => {
    const ack = { source_epoch: EPOCH, source_capability: "notifications.metadata", highest_contiguous_cursor: "9" };
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:event_ack", ack)).not.toThrow();
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:event_ack", { ...ack, agent_principal_id: "agent-b" })).toThrowError("SCHEMA_INVALID");
    expect(() => validateSchema("urn:open-android-intelligence:protocol:v1:message:event_ack", { ...ack, highest_contiguous_cursor: "09" })).toThrowError("SCHEMA_INVALID");
  });
});

describe("Task 9 verified authority and routing", () => {
  it("binds source epoch/capability/revision to an opaque authenticated device authority", () => {
    const current = authority();
    const frame = { context: { kind: "device", tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a" }, envelope: { messageType: "device_event", payload: validEvent } } as never;
    expect(validateDeviceEvent(frame, current)).toMatchObject({
      tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a",
      sourceEpoch: EPOCH, cursor: 9n, routeByServerSubscriptionOnly: true,
    });
    expect(() => validateDeviceEvent(frame, authority({ sourceEpoch: "018f4f9a-4444-4444-8444-444444444446" }))).toThrowError("AUTH_BINDING_MISMATCH");
    expect(() => validateDeviceEvent(frame, { authorityId: "forged" } as unknown as VerifiedCaptureAuthority)).toThrowError("AUTH_FAILED");
  });

  it("projects only genuinely verified events into the durable state boundary", () => {
    const event = validateDeviceEvent({
      context: { kind: "device", tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a" },
      envelope: { messageType: "device_event", payload: validEvent },
    } as never, authority());
    expect(projectVerifiedDeviceEvent(event)).toEqual({
      tenantId: "tenant-a",
      humanPrincipalId: "human-a",
      deviceId: "device-a",
      sourceEpoch: EPOCH,
      sourceCapability: "notifications.metadata",
      cursor: 9n,
      occurrenceId: OCCURRENCE,
      eventKind: "upsert",
      pairingGeneration: 3n,
      authorizationEpoch: 7n,
      scopeRevisions: new Map([["notifications.metadata", 4n]]),
      routeByServerSubscriptionOnly: true,
    });
    expect(() => projectVerifiedDeviceEvent({ ...event } as never)).toThrowError("AUTH_FAILED");
  });

  it("returns only server-owned subscription targets for the exact lookup key", async () => {
    const router = createServerSubscriptionRouter([
      { tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a", sourceCapability: "notifications.metadata", subscriptionId: "subscription-a" },
      { tenantId: "tenant-b", humanPrincipalId: "human-a", deviceId: "device-a", sourceCapability: "notifications.metadata", subscriptionId: "subscription-b" },
    ]);
    const event = validateDeviceEvent({ context: { kind: "device", tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a" }, envelope: { messageType: "device_event", payload: validEvent } } as never, authority());
    await expect(router.route(event)).resolves.toEqual([{ subscriptionId: "subscription-a" }]);
    expect((event as unknown as Record<string, unknown>).agentPrincipalId).toBeUndefined();
  });
});

describe("Task 9 ACK validation and durable-before-delete boundary", () => {
  it("requires a verified bridge-to-app frame and matching source state", async () => {
    const ack = { source_epoch: EPOCH, source_capability: "notifications.metadata", highest_contiguous_cursor: "9" };
    const frame = { context: { kind: "device", tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a", direction: "bridge-to-app" }, envelope: { messageType: "event_ack", payload: ack } } as never;
    const fact = validateEventAck(frame, createVerifiedEventAckAuthority({ tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a", sourceEpoch: EPOCH, sourceCapability: "notifications.metadata", highestContiguousCursor: 9n }));
    expect(fact).toMatchObject({ sourceEpoch: EPOCH, highestContiguousCursor: 9n });
    expect(projectVerifiedEventAck(fact)).toEqual({
      tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a",
      sourceEpoch: EPOCH, sourceCapability: "notifications.metadata", highestContiguousCursor: 9n,
    });
    expect(() => projectVerifiedEventAck({ ...fact } as never)).toThrowError("AUTH_FAILED");
    const wrongDirectionFrame = { ...(frame as unknown as Record<string, unknown>), context: { ...(frame as unknown as { context: Record<string, unknown> }).context, direction: "app-to-bridge" } };
    expect(() => validateEventAck(wrongDirectionFrame, createVerifiedEventAckAuthority({ tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a", sourceEpoch: EPOCH, sourceCapability: "notifications.metadata", highestContiguousCursor: 9n }))).toThrowError("AUTH_BINDING_MISMATCH");
    const store: EventAckStore = { persistBeforeSign: async () => ({ kind: "committed" }) };
    await expect(store.persistBeforeSign(fact)).resolves.toEqual({ kind: "committed" });
  });

  it("reconstructs the checked-in event and ACK JCS vectors exactly", () => {
    const vectors = JSON.parse(readFileSync(new URL("../test-only/event/v1/event-ack-vectors.json", import.meta.url), "utf8")) as {
      vectors: Array<{ id: string; message_type: "device_event" | "event_ack"; payload: Record<string, unknown>; jcs_base64: string; sha256_base64url: string; utf8_bytes: string }>;
    };
    expect(vectors.vectors.map((vector) => vector.id)).toEqual([
      "device_event_upsert_metadata_cursor_1",
      "event_ack_basic_metadata_cursor",
    ]);
    for (const vector of vectors.vectors) {
      const bytes = canonicalBytes(vector.payload);
      expect(Buffer.from(bytes).toString("base64")).toBe(vector.jcs_base64);
      expect(sha256B64Url(bytes)).toBe(vector.sha256_base64url);
      expect(String(bytes.byteLength)).toBe(vector.utf8_bytes);
      expect(() => validateSchema(`urn:open-android-intelligence:protocol:v1:message:${vector.message_type}`, vector.payload)).not.toThrow();
    }
  });
});

describe("Task 9 shared pre-replay integrity gate", () => {
  it.each([
    ["tenant", validEvent, authority({ tenantId: "tenant-b" })],
    ["human", validEvent, authority({ humanPrincipalId: "human-b" })],
    ["device", validEvent, authority({ deviceId: "device-b" })],
    ["source epoch", { ...validEvent, source_epoch: "018f4f9a-4444-4444-8444-444444444446" }, authority()],
    ["source capability", { ...validEvent, source_capability: "notifications.content" }, authority()],
    ["pairing generation", { ...validEvent, capture_revision: { ...REVISION, pairing_generation: "4" } }, authority()],
    ["authorization epoch", { ...validEvent, capture_revision: { ...REVISION, authorization_epoch: "8" } }, authority()],
    ["missing scope revision", { ...validEvent, capture_revision: { ...REVISION, scope_revisions: {} } }, authority()],
    ["extra scope revision", { ...validEvent, capture_revision: { ...REVISION, scope_revisions: { ...REVISION.scope_revisions, "notifications.content": "1" } } }, authority()],
    ["scope revision key", { ...validEvent, capture_revision: { ...REVISION, scope_revisions: { "notifications.content": "4" } } }, authority()],
    ["scope revision value", { ...validEvent, capture_revision: { ...REVISION, scope_revisions: { "notifications.metadata": "5" } } }, authority()],
  ] as const)("rejects a device-event %s mismatch before replay lookup or mutation", async (_case, payload, capture) => {
    const backend = securityBackend();
    const session = await backend.authenticateDevice({
      handleId: "task9-device-event-mismatch", transport: "https", connectionId: "connection-1", transportProfileId: "tailnet",
    });
    const calls = { capture: 0, ack: 0 };
    await expect(verifyTransportFrame(task9Wire("device_event", payload), session.ingress, {
      clock: { wallNow: () => new Date("2026-08-11T00:00:00.000Z"), monotonicNowMs: () => 0n },
      keyRings: backend, admission: backend,
      authorization: { evaluate: () => ({ allowed: true }) },
      preReplay: task9Gate({ capture, calls }),
    })).resolves.toEqual({ ok: false, error: "NOT_AUTHORIZED" });
    expect(calls).toEqual({ capture: 1, ack: 0 });
    expect(backend.diagnostics()).toEqual({ replayLookups: 0n, replayMutations: 0n });
  });

  it.each([
    ["tenant", { source_epoch: EPOCH, source_capability: "notifications.metadata", highest_contiguous_cursor: "9" }, ackAuthority({ tenantId: "tenant-b" })],
    ["human", { source_epoch: EPOCH, source_capability: "notifications.metadata", highest_contiguous_cursor: "9" }, ackAuthority({ humanPrincipalId: "human-b" })],
    ["device", { source_epoch: EPOCH, source_capability: "notifications.metadata", highest_contiguous_cursor: "9" }, ackAuthority({ deviceId: "device-b" })],
    ["source epoch", { source_epoch: "018f4f9a-4444-4444-8444-444444444446", source_capability: "notifications.metadata", highest_contiguous_cursor: "9" }, ackAuthority()],
    ["source capability", { source_epoch: EPOCH, source_capability: "notifications.content", highest_contiguous_cursor: "9" }, ackAuthority()],
    ["cursor", { source_epoch: EPOCH, source_capability: "notifications.metadata", highest_contiguous_cursor: "8" }, ackAuthority()],
  ] as const)("rejects an ACK %s mismatch before replay lookup or mutation", async (_case, payload, ack) => {
    const backend = securityBackend();
    const session = await backend.authenticateDevice({
      handleId: "task9-ack-mismatch", transport: "https", connectionId: "connection-1", transportProfileId: "tailnet",
    });
    const calls = { capture: 0, ack: 0 };
    await expect(verifyTransportFrame(task9Wire("event_ack", payload), session.ingress, {
      clock: { wallNow: () => new Date("2026-08-11T00:00:00.000Z"), monotonicNowMs: () => 0n },
      keyRings: backend, admission: backend,
      authorization: { evaluate: () => ({ allowed: true }) },
      preReplay: task9Gate({ ack, calls }),
    })).resolves.toEqual({ ok: false, error: "INTEGRITY_FAILED" });
    expect(calls).toEqual({ capture: 0, ack: 1 });
    expect(backend.diagnostics()).toEqual({ replayLookups: 0n, replayMutations: 0n });
  });

  it.each([
    ["signature", { privateJwk: BRIDGE_PRIVATE }, true, "AUTH_FAILED"],
    ["binding", { connectionGeneration: "2" }, true, "CONNECTION_FENCED"],
    ["authorization", {}, false, "NOT_AUTHORIZED"],
  ] as const)("returns an earlier %s denial without consulting Task 9 authority", async (_case, wireOptions, allowed, error) => {
    const backend = securityBackend();
    const session = await backend.authenticateDevice({
      handleId: "task9-precedence", transport: "https", connectionId: "connection-1", transportProfileId: "tailnet",
    });
    const calls = { capture: 0, ack: 0 };
    await expect(verifyTransportFrame(task9Wire("device_event", validEvent, wireOptions), session.ingress, {
      clock: { wallNow: () => new Date("2026-08-11T00:00:00.000Z"), monotonicNowMs: () => 0n },
      keyRings: backend, admission: backend,
      authorization: { evaluate: () => allowed ? { allowed: true } : { allowed: false, denial: "POLICY_BLOCKED" } },
      preReplay: task9Gate({ calls }),
    })).resolves.toEqual({ ok: false, error });
    expect(calls).toEqual({ capture: 0, ack: 0 });
    expect(backend.diagnostics()).toEqual({ replayLookups: 0n, replayMutations: 0n });
  });

  it.each([
    ["device_event", validEvent, { capture: 1, ack: 0 }],
    ["event_ack", { source_epoch: EPOCH, source_capability: "notifications.metadata", highest_contiguous_cursor: "9" }, { capture: 0, ack: 1 }],
  ] as const)("admits a real signed %s when its opaque authority matches", async (messageType, payload, expectedCalls) => {
    const backend = securityBackend();
    const session = await backend.authenticateDevice({
      handleId: `task9-allow-${messageType}`, transport: "https", connectionId: "connection-1", transportProfileId: "tailnet",
    });
    const calls = { capture: 0, ack: 0 };
    const result = await verifyTransportFrame(task9Wire(messageType, payload), session.ingress, {
      clock: { wallNow: () => new Date("2026-08-11T00:00:00.000Z"), monotonicNowMs: () => 0n },
      keyRings: backend, admission: backend,
      authorization: { evaluate: () => ({ allowed: true }) },
      preReplay: task9Gate({ calls }),
    });
    expect(result).toMatchObject({ ok: true, kind: "accepted", frame: { envelope: { messageType } } });
    expect(calls).toEqual(expectedCalls);
    expect(backend.diagnostics()).toEqual({ replayLookups: 1n, replayMutations: 1n });
  });

  it("passes an unknown message type through without loading Task 9 authority", async () => {
    const backend = securityBackend();
    const session = await backend.authenticateDevice({
      handleId: "task9-unrelated", transport: "https", connectionId: "connection-1", transportProfileId: "tailnet",
    });
    const admitted = await verifyTransportFrame(task9Wire("device_ping", { challenge: B32 }), session.ingress, {
      clock: { wallNow: () => new Date("2026-08-11T00:00:00.000Z"), monotonicNowMs: () => 0n },
      keyRings: backend, admission: backend,
      authorization: { evaluate: () => ({ allowed: true }) },
      preReplay: task9Gate(),
    });
    if (!admitted.ok || admitted.kind !== "accepted") throw new Error("expected accepted device ping");
    const calls = { capture: 0, ack: 0 };
    await expect(task9Gate({ calls }).evaluate("future_control_message", {}, admitted.frame.context))
      .resolves.toEqual({ allowed: true });
    expect(calls).toEqual({ capture: 0, ack: 0 });
  });

  it("distinguishes a forged ACK authority from a valid authority mismatch", async () => {
    const backend = securityBackend();
    const session = await backend.authenticateDevice({
      handleId: "task9-ack-context", transport: "https", connectionId: "connection-1", transportProfileId: "tailnet",
    });
    const payload = { source_epoch: EPOCH, source_capability: "notifications.metadata", highest_contiguous_cursor: "9" };
    const admitted = await verifyTransportFrame(task9Wire("event_ack", payload), session.ingress, {
      clock: { wallNow: () => new Date("2026-08-11T00:00:00.000Z"), monotonicNowMs: () => 0n },
      keyRings: backend, admission: backend,
      authorization: { evaluate: () => ({ allowed: true }) },
      preReplay: task9Gate(),
    });
    if (!admitted.ok || admitted.kind !== "accepted") throw new Error("expected accepted ACK");
    const forged = { authorityId: "forged" } as unknown as VerifiedEventAckAuthority;
    await expect(task9Gate({ ack: forged }).evaluate("event_ack", payload, admitted.frame.context)).resolves.toEqual({
      allowed: false, error: "INTEGRITY_FAILED", decisionReason: "ACK_AUTHORITY_INVALID",
    });
    await expect(task9Gate({ ack: ackAuthority({ highestContiguousCursor: 8n }) })
      .evaluate("event_ack", payload, admitted.frame.context)).resolves.toEqual({
      allowed: false, error: "INTEGRITY_FAILED", decisionReason: "ACK_AUTHORITY_MISMATCH",
    });
  });
});

void B32;
