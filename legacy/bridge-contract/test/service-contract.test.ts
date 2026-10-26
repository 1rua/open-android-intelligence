import { describe, expect, it } from "vitest";
import {
  PairingService,
  type PairingTicketInput,
} from "../src/pairing-service.js";
import {
  NotificationStore,
  validateNotificationRecord,
  type NotificationRecordV1,
} from "../src/notification-store.js";
import { OperationDispatcher } from "../src/operation-dispatch.js";
import { SubscriptionStore } from "../src/subscription-store.js";
import {
  NotificationService,
  type BridgeSessionIdentity,
} from "../src/notification-service.js";
import {
  AssistantChatService,
  ZERO_RETENTION_UNAVAILABLE,
  type AssistantArtifactCommitment,
  type ZeroRetentionEvidence,
} from "../src/assistant-chat-service.js";
import { InMemoryAssistantReplyEventStore } from "../src/assistant-reply-events.js";

const ticketInput = (overrides: Partial<PairingTicketInput> = {}): PairingTicketInput => ({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  bridgeFingerprint: "bridge-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 3n,
  ...overrides,
});

const session = (overrides: Partial<BridgeSessionIdentity> = {}): BridgeSessionIdentity => ({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  agentInstanceId: "agent-a",
  workspaceId: "workspace-a",
  sessionId: "session-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 3n,
  ...overrides,
});

const record = (overrides: Partial<NotificationRecordV1> = {}): NotificationRecordV1 => ({
  kind: "upsert",
  recordId: "record-1",
  packageId: "com.example.mail",
  title: "title",
  content: "body",
  sourceEpoch: 1n,
  cursor: 1n,
  captureRevision: 3n,
  ...overrides,
});

const zeroRetention = (overrides: Partial<ZeroRetentionEvidence> = {}): ZeroRetentionEvidence => ({
  provider: "fixture-provider",
  profileId: "fixture-zero-v1",
  revision: "2026-08-11.1",
  expiresAt: "2099-01-01T00:00:00.000Z",
  providerObjectRetention: "none",
  requestResponseLoggingDisabled: true,
  trainingDisabled: true,
  humanReviewDisabled: true,
  ...overrides,
});

const committedAudio = (current: BridgeSessionIdentity): AssistantArtifactCommitment => ({
  artifactId: "artifact-audio", status: "message_committed" as const,
  session: current, pairingGeneration: current.pairingGeneration,
  connectionGeneration: 1n, policyRevision: current.policyAttestationRevision,
  kind: "audio" as const, mimeType: "audio/mp4" as const,
  sizeBytes: 512, sha256: "c".repeat(64), durationMs: 5000,
});

const audioAttachment = () => ({
  kind: "audio" as const, artifactId: "artifact-audio", filename: "voice.m4a",
  mimeType: "audio/mp4" as const, sizeBytes: 512, sha256: "c".repeat(64), durationMs: 5000,
});

describe("in-memory Bridge pairing seam", () => {
  it("accepts a ticket once, rejects replay/expiry, and preserves generation continuity", () => {
    let now = 1000;
    const pairing = new PairingService({ clock: () => now, ticketTtlMs: 100 });
    const ticket = pairing.issueTicket(ticketInput());
    const accepted = pairing.acceptTicket(ticket);
    expect(accepted).toMatchObject({ tenantId: "tenant-a", deviceId: "device-a", pairingGeneration: 1n });
    expect(() => pairing.acceptTicket(ticket)).toThrowError(/PAIRING_TICKET_REPLAY/);

    const nextTicket = pairing.issueTicket(ticketInput({ pairingGeneration: 2n }));
    expect(pairing.acceptTicket(nextTicket).pairingGeneration).toBe(2n);
    const skipped = pairing.issueTicket(ticketInput({ pairingGeneration: 4n }));
    expect(() => pairing.acceptTicket(skipped)).toThrowError(/PAIRING_GENERATION_GAP/);

    const expired = pairing.issueTicket(ticketInput({ deviceId: "device-b" }));
    now += 101;
    expect(() => pairing.acceptTicket(expired)).toThrowError(/PAIRING_TICKET_EXPIRED/);
  });

  it("rejects a ticket whose binding fields were changed by the caller", () => {
    const pairing = new PairingService({ clock: () => 1000 });
    const ticket = pairing.issueTicket(ticketInput());
    expect(() => pairing.acceptTicket({ ...ticket, deviceId: "device-b" })).toThrowError(/PAIRING_TICKET_TAMPERED/);
  });
});

describe("in-memory notification contract service", () => {
  it("rejects tombstones that cannot be represented by the closed wire schema", () => {
    expect(() => validateNotificationRecord(record({ kind: "delete_tombstone", title: null, content: null }))).not.toThrow();
    expect(() => validateNotificationRecord(record({ kind: "delete_tombstone", packageId: null, title: "stale", content: null })))
      .toThrowError(/NOTIFICATION_RECORD_INVALID/);
    expect(() => validateNotificationRecord(record({ kind: "delete_tombstone", title: null, content: "stale" })))
      .toThrowError(/NOTIFICATION_RECORD_INVALID/);
  });

  const createService = (now = 1000) => {
    const pairing = new PairingService({ clock: () => now });
    const operations = new OperationDispatcher();
    const store = new NotificationStore();
    const subscriptions = new SubscriptionStore();
    const service = new NotificationService({
      pairing,
      operations,
      store,
      subscriptions,
      authorize: ({ policyRevision, session: current }) => ({
        allowed: policyRevision === current.policyAttestationRevision,
        policyRevision: current.policyAttestationRevision,
      }),
    });
    const ticket = pairing.issueTicket(ticketInput());
    const paired = service.pair(ticket);
    return { service, store, subscriptions, paired };
  };

  it("queries a captured record once, applies closed package/field filters, and reuses the result on retry", async () => {
    const { service, store, paired } = createService();
    store.append(paired.deviceId, record());
    store.append(paired.deviceId, record({ recordId: "chat", packageId: "com.example.chat", cursor: 2n }));
    const request = {
      operationId: "op-1",
      session: paired,
      mode: "on_demand" as const,
      limit: 100,
      policyRevision: 3n,
      filter: { packages: ["com.example.mail"], fields: ["metadata"] as const },
    };
    const first = await service.query(request);
    const retry = await service.query(request);
    expect(first).toEqual(retry);
    expect(first).toEqual([expect.objectContaining({ recordId: "record-1", title: null, content: null })]);
    expect(service.operationClaims()).toEqual([{ operationId: "op-1", claims: 1 }]);
    await expect(service.query({ ...request, session: session({ sessionId: "other" }) }))
      .rejects.toMatchObject({ code: "CONNECTION_FENCED" });
  });

  it("fails closed on an authorization revision mismatch and isolates another session", async () => {
    const { service, paired } = createService();
    await expect(service.query({ operationId: "bad-revision", session: paired, mode: "on_demand", limit: 1, policyRevision: 2n }))
      .rejects.toThrowError(/AUTHORIZATION_REVISION_STALE/);
    await expect(service.query({ operationId: "other-user", session: session({ humanPrincipalId: "human-b" }), mode: "on_demand", limit: 1, policyRevision: 3n }))
      .rejects.toThrowError(/CONNECTION_FENCED/);
    await expect(service.query({ operationId: "forged-revision", session: { ...paired, policyAttestationRevision: 99n }, mode: "on_demand", limit: 1, policyRevision: 99n }))
      .rejects.toThrowError(/CONNECTION_FENCED/);
  });

  it("routes only through a bound subscription and validates cursor/source epoch on ACK", async () => {
    const { service, store, paired } = createService();
    const subscription = await service.subscribe({
      subscriptionId: "sub-1",
      session: paired,
      policyRevision: 3n,
      filter: { packages: ["com.example.mail"], fields: ["content"] },
    });
    store.append(paired.deviceId, record());
    const event = await service.publishAuthorized(subscription.subscriptionId, paired, record(), 3n);
    expect(event).toMatchObject({ eventId: "event-1", sourceEpoch: 1n, cursor: 1n });
    expect(service.pendingEvents(subscription.subscriptionId, paired)).toHaveLength(1);
    await expect(service.acknowledge({
      subscriptionId: subscription.subscriptionId,
      eventId: event.eventId,
      session: paired,
      sourceEpoch: 1n,
      cursor: 2n,
    })).rejects.toMatchObject({ code: "EVENT_ACK_INVALID" });
    await expect(service.acknowledge({
      subscriptionId: subscription.subscriptionId,
      eventId: event.eventId,
      session: paired,
      sourceEpoch: 1n,
      cursor: 1n,
    })).resolves.toMatchObject({ eventId: "event-1" });
    await expect(service.acknowledge({
      subscriptionId: subscription.subscriptionId,
      eventId: event.eventId,
      session: session({ workspaceId: "other" }),
      sourceEpoch: 1n,
      cursor: 1n,
    })).rejects.toThrowError(/CONNECTION_FENCED/);
  });

  it("rechecks the Task-6 subscription grant before egress", async () => {
    let allowed = true;
    const pairing = new PairingService({ clock: () => 1000 });
    const operations = new OperationDispatcher();
    const service = new NotificationService({
      pairing,
      operations,
      store: new NotificationStore(),
      subscriptions: new SubscriptionStore(),
      authorize: ({ policyRevision }) => ({ allowed, policyRevision }),
    });
    const ticket = pairing.issueTicket(ticketInput());
    const paired = service.pair(ticket);
    const subscription = await service.subscribe({ subscriptionId: "sub-revoked", session: paired, policyRevision: 3n });
    allowed = false;
    await expect(service.publishAuthorized(subscription.subscriptionId, paired, record(), 3n))
      .rejects.toMatchObject({ code: "NOT_AUTHORIZED" });
  });

  it("fences a generation change that occurs while authorization is pending", async () => {
    const pairing = new PairingService({ clock: () => 1000 });
    const ticket = pairing.issueTicket(ticketInput());
    const operations = new OperationDispatcher();
    let resolveAuthorization!: (value: { allowed: boolean; policyRevision: bigint }) => void;
    const authorization = new Promise<{ allowed: boolean; policyRevision: bigint }>((resolve) => { resolveAuthorization = resolve; });
    let gate = false;
    const service = new NotificationService({
      pairing, operations, store: new NotificationStore(), subscriptions: new SubscriptionStore(),
      authorize: async () => gate ? authorization : { allowed: true, policyRevision: 3n },
    });
    const paired = service.pair(ticket);
    await service.subscribe({ subscriptionId: "sub-race", session: paired, policyRevision: 3n });
    gate = true;
    const publish = service.publishAuthorized("sub-race", paired, record(), 3n);
    pairing.acceptTicket(pairing.issueTicket(ticketInput({ pairingGeneration: 2n })));
    resolveAuthorization({ allowed: true, policyRevision: 3n });
    await expect(publish).rejects.toThrowError(/CONNECTION_FENCED/);
  });

  it("does not expose an unauthorised publish shortcut", async () => {
    const { service, paired } = createService();
    await expect(service.publish("missing-auth", paired, record()))
      .rejects.toMatchObject({ code: "AUTHORIZATION_REQUIRED" });
  });
});

describe("Task-7-shaped operation claim seam", () => {
  it("retains completed results across a simulated restart and releases a crash-cut claim", async () => {
    const first = new OperationDispatcher();
    const current = session();
    const request = { operationId: "crash-cut", session: current, parameters: { mode: "on_demand" } };
    expect(first.begin(request)).toMatchObject({ existing: false });
    const recovered = first.restart();
    let calls = 0;
    await expect(recovered.execute(request, async () => { calls += 1; return { records: [] }; })).resolves.toEqual({ records: [] });
    expect(calls).toBe(1);
    const afterCompleted = recovered.restart();
    await expect(afterCompleted.execute(request, async () => { calls += 1; return { records: ["unexpected"] }; })).resolves.toEqual({ records: [] });
    expect(calls).toBe(1);
  });
});

describe("in-memory assistant operation service", () => {
  it("requires a verified bound session before a permissive authorizer can run", async () => {
    const service = new AssistantChatService({ authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }) });
    await expect(service.send({
      operationId: "unbound-chat", messageId: "unbound-message", session: session(), text: "hello", zeroRetention: zeroRetention(),
    })).rejects.toThrowError(/CONNECTION_FENCED/);
  });

  it("fences an assistant session after pairing generation advances", async () => {
    const pairing = new PairingService({ clock: () => 1000 });
    pairing.acceptTicket(pairing.issueTicket(ticketInput()));
    const current = session();
    const service = new AssistantChatService({ pairing, boundSession: current, authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }) });
    pairing.acceptTicket(pairing.issueTicket(ticketInput({ pairingGeneration: 2n })));
    await expect(service.send({
      operationId: "stale-chat", messageId: "stale-message", session: current, text: "hello", zeroRetention: zeroRetention(),
    })).rejects.toThrowError(/CONNECTION_FENCED/);
  });

  it("requires current zero-retention evidence and keeps only metadata", async () => {
    const operations = new OperationDispatcher();
    const current = session();
    const image = { kind: "image" as const, artifactId: "artifact-1", filename: "photo.png", mimeType: "image/png" as const, sizeBytes: 5, sha256: "a".repeat(64) };
    const service = new AssistantChatService({
      boundSession: current, operations, boundConnectionGeneration: 1n,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      resolveArtifact: async () => ({
        artifactId: image.artifactId, status: "message_committed" as const, session: current,
        pairingGeneration: current.pairingGeneration, connectionGeneration: 1n,
        policyRevision: current.policyAttestationRevision, kind: image.kind, mimeType: image.mimeType,
        sizeBytes: image.sizeBytes, sha256: image.sha256,
      }),
      respond: async () => "fixture-reply",
    });
    const result = await service.send({
      operationId: "chat-op-1",
      messageId: "message-1",
      session: current,
      text: "secret body",
      zeroRetention: zeroRetention(),
      attachments: [image],
    });
    expect(result).toEqual({ operationId: "chat-op-1", messageId: "message-1", status: "accepted", reply: "fixture-reply" });
    expect(service.metadata()).toEqual({
      operationId: "chat-op-1",
      messageId: "message-1",
      attachments: [{ kind: "image", filename: "photo.png", mimeType: "image/png", sizeBytes: 5, sha256: "a".repeat(64) }],
    });
    expect(JSON.stringify(service.diagnostics())).not.toContain("secret body");
    await expect(service.send({
      operationId: "chat-op-stale",
      messageId: "message-stale",
      session: current,
      text: "x",
      zeroRetention: zeroRetention({ expiresAt: "2020-01-01T00:00:00.000Z" }),
    })).rejects.toThrowError(new RegExp(ZERO_RETENTION_UNAVAILABLE));
  });

  it("reuses a completed chat operation and rejects retained providers", async () => {
    let calls = 0;
    const current = session();
    const service = new AssistantChatService({ boundSession: current, operations: new OperationDispatcher(), authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }), respond: async () => { calls += 1; return "reply"; } });
    const input = { operationId: "chat-op-2", messageId: "message-2", session: current, text: "hello", zeroRetention: zeroRetention() };
    expect(await service.send(input)).toEqual(await service.send(input));
    expect(calls).toBe(1);
    await expect(service.send({ ...input, text: "changed" })).rejects.toMatchObject({ code: "OPERATION_PARAMETERS_MISMATCH" });
    await expect(service.send({ ...input, operationId: "chat-retained", zeroRetention: zeroRetention({ providerObjectRetention: "provider_retains" }) }))
      .rejects.toThrowError(/ZERO_RETENTION_UNAVAILABLE/);
  });

  it("fails closed when no Task-6 assistant grant is supplied", async () => {
    const current = session();
    const service = new AssistantChatService({ boundSession: current, operations: new OperationDispatcher() });
    await expect(service.send({ operationId: "chat-no-grant", messageId: "message-no-grant", session: current, text: "hello", zeroRetention: zeroRetention() }))
      .rejects.toMatchObject({ code: "NOT_AUTHORIZED" });
  });

  it("accepts an exactly committed audio artifact and forwards its metadata to the responder", async () => {
    const current = session();
    let received: readonly unknown[] = [];
    const service = new AssistantChatService({
      boundSession: current, boundConnectionGeneration: 1n,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      resolveArtifact: async ({ session: requested }) => committedAudio(requested),
      respond: async (_text, attachments) => { received = attachments; return "audio reply"; },
    });

    await expect(service.send({
      operationId: "audio-send", messageId: "audio-message", session: current, text: "listen",
      zeroRetention: zeroRetention(), attachments: [audioAttachment()],
    })).resolves.toMatchObject({ reply: "audio reply" });
    expect(received).toEqual([audioAttachment()]);
  });

  it("accepts only normalized closed attachment metadata at the Bridge ingress", async () => {
    const current = session();
    let resolved: unknown;
    let received: unknown;
    const service = new AssistantChatService({
      boundSession: current, boundConnectionGeneration: 1n,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      resolveArtifact: async ({ attachment }) => {
        resolved = attachment;
        return committedAudio(current);
      },
      respond: async (_text, attachments) => { received = attachments; return "audio reply"; },
    });
    const uppercaseDigestAttachment = { ...audioAttachment(), sha256: "C".repeat(64) };

    await expect(service.send({
      operationId: "normalized-audio", messageId: "normalized-message", session: current, text: "listen",
      zeroRetention: zeroRetention(), attachments: [uppercaseDigestAttachment],
    })).resolves.toMatchObject({ reply: "audio reply" });
    expect(resolved).toEqual(audioAttachment());
    expect(received).toEqual([audioAttachment()]);
    expect(service.metadata()).toEqual({
      operationId: "normalized-audio", messageId: "normalized-message",
      attachments: [{ kind: "audio", filename: "voice.m4a", mimeType: "audio/mp4", sizeBytes: 512, sha256: "c".repeat(64), durationMs: 5000 }],
    });
  });

  it.each([
    ["an unknown transport field", { ...audioAttachment(), uri: "file:///private/voice.m4a" }],
    ["an artifact ID outside the opaque ID alphabet", { ...audioAttachment(), artifactId: "artifact/audio" }],
    ["a non-string artifact ID", { ...audioAttachment(), artifactId: 42 }],
    ["a non-string filename", { ...audioAttachment(), filename: 42 }],
  ])("rejects %s before resolving or responding", async (_description, attachment) => {
    const current = session();
    let resolverCalls = 0;
    let responderCalls = 0;
    const service = new AssistantChatService({
      boundSession: current, boundConnectionGeneration: 1n,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      resolveArtifact: async () => { resolverCalls += 1; return committedAudio(current); },
      respond: async () => { responderCalls += 1; return "unexpected"; },
    });

    await expect(service.send({
      operationId: `invalid-attachment-${_description}`, messageId: "invalid-attachment-message", session: current, text: "listen",
      zeroRetention: zeroRetention(), attachments: [attachment] as never,
    })).rejects.toMatchObject({ code: "ATTACHMENT_INVALID" });
    expect({ resolverCalls, responderCalls }).toEqual({ resolverCalls: 0, responderCalls: 0 });
  });

  it("rejects an unresolved artifact before calling the responder", async () => {
    const current = session();
    let responses = 0;
    const service = new AssistantChatService({
      boundSession: current, boundConnectionGeneration: 1n,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      resolveArtifact: async () => null,
      respond: async () => { responses += 1; return "unexpected"; },
    });

    await expect(service.send({
      operationId: "missing-artifact", messageId: "missing-message", session: current, text: "listen",
      zeroRetention: zeroRetention(), attachments: [audioAttachment()],
    })).rejects.toMatchObject({ code: "ARTIFACT_NOT_COMMITTED" });
    expect(responses).toBe(0);
  });

  it.each([
    ["session", (current: BridgeSessionIdentity) => ({ ...committedAudio(current), session: session({ sessionId: "other" }) })],
    ["pairing generation", (current: BridgeSessionIdentity) => ({ ...committedAudio(current), pairingGeneration: 2n })],
    ["bound connection generation", (current: BridgeSessionIdentity) => ({ ...committedAudio(current), connectionGeneration: 2n })],
    ["artifact ID", (current: BridgeSessionIdentity) => ({ ...committedAudio(current), artifactId: "artifact-other" })],
    ["kind", (current: BridgeSessionIdentity) => ({ ...committedAudio(current), kind: "file" as const })],
    ["MIME type", (current: BridgeSessionIdentity) => ({ ...committedAudio(current), mimeType: "image/png" as const })],
    ["size", (current: BridgeSessionIdentity) => ({ ...committedAudio(current), sizeBytes: 511 })],
    ["digest", (current: BridgeSessionIdentity) => ({ ...committedAudio(current), sha256: "d".repeat(64) })],
    ["duration", (current: BridgeSessionIdentity) => ({ ...committedAudio(current), durationMs: 4999 })],
    ["policy revision", (current: BridgeSessionIdentity) => ({ ...committedAudio(current), policyRevision: 4n })],
  ])("rejects a committed audio artifact with a mismatched %s", async (_field, commitment) => {
    const current = session();
    const service = new AssistantChatService({
      boundSession: current, boundConnectionGeneration: 1n,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      resolveArtifact: async () => commitment(current),
    });

    await expect(service.send({
      operationId: `mismatch-${_field}`, messageId: "mismatch-message", session: current, text: "listen",
      zeroRetention: zeroRetention(), attachments: [audioAttachment()],
    })).rejects.toMatchObject({ code: "ARTIFACT_FENCE_MISMATCH" });
  });

  it("streams ordered reply events, then reuses a completed operation without replaying it", async () => {
    const current = session();
    const events = new InMemoryAssistantReplyEventStore();
    const delivered: unknown[] = [];
    const service = new AssistantChatService({
      boundSession: current, eventStore: events,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      respondStream: async function* () { yield "reply delta"; },
    });
    const request = { operationId: "stream-op", messageId: "stream-message", session: current, text: "hello", zeroRetention: zeroRetention() };

    await expect(service.stream(request, (event) => { delivered.push(event); })).resolves.toEqual({
      operationId: "stream-op", messageId: "stream-message", status: "accepted", reply: "reply delta",
    });
    expect(delivered).toEqual([
      { kind: "delta", operationId: "stream-op", messageId: "stream-message", sequence: 1n, text: "reply delta" },
      { kind: "complete", operationId: "stream-op", messageId: "stream-message", sequence: 2n, text: "reply delta" },
    ]);
    expect(events.replay("stream-op", 0n)).toEqual(delivered);
    let retryDeliveries = 0;
    await expect(service.stream(request, () => { retryDeliveries += 1; })).resolves.toEqual({
      operationId: "stream-op", messageId: "stream-message", status: "accepted", reply: "reply delta",
    });
    expect(retryDeliveries).toBe(0);
    await expect(service.stream({ ...request, text: "changed" }, () => {}))
      .rejects.toMatchObject({ code: "OPERATION_PARAMETERS_MISMATCH" });
  });

  it("keeps a persisted completion as the only terminal event when its sink throws", async () => {
    const current = session();
    const events = new InMemoryAssistantReplyEventStore();
    const service = new AssistantChatService({
      boundSession: current, eventStore: events,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      respondStream: async function* () { yield "complete despite sink"; },
    });

    await expect(service.stream({
      operationId: "complete-sink-failure", messageId: "complete-sink-message", session: current, text: "hello", zeroRetention: zeroRetention(),
    }, (event) => { if (event.kind === "complete") throw new Error("sink unavailable"); })).resolves.toMatchObject({ reply: "complete despite sink" });
    expect(events.replay("complete-sink-failure", 0n).map((event) => event.kind)).toEqual(["delta", "complete"]);
  });

  it("persists one failed terminal event when a delta sink throws", async () => {
    const current = session();
    const events = new InMemoryAssistantReplyEventStore();
    const service = new AssistantChatService({
      boundSession: current, eventStore: events,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      respondStream: async function* () { yield "delta that cannot deliver"; },
    });

    await expect(service.stream({
      operationId: "delta-sink-failure", messageId: "delta-sink-message", session: current, text: "hello", zeroRetention: zeroRetention(),
    }, (event) => { if (event.kind === "delta") throw new Error("sink unavailable"); })).rejects.toMatchObject({ code: "ASSISTANT_REPLY_FAILED" });
    expect(events.replay("delta-sink-failure", 0n).map((event) => [event.kind, event.sequence]))
      .toEqual([["delta", 1n], ["failed", 2n]]);
  });

  it("emits one closed failed event without artifact data when streaming fails", async () => {
    const current = session();
    const delivered: unknown[] = [];
    const service = new AssistantChatService({
      boundSession: current,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      respondStream: async function* () { throw new Error("provider message must not escape"); },
    });

    await expect(service.stream({
      operationId: "failed-stream", messageId: "failed-message", session: current, text: "hello", zeroRetention: zeroRetention(),
    }, (event) => { delivered.push(event); })).rejects.toMatchObject({ code: "ASSISTANT_REPLY_FAILED" });
    expect(delivered).toEqual([
      { kind: "failed", operationId: "failed-stream", messageId: "failed-message", sequence: 1n, text: "", error: "ASSISTANT_REPLY_FAILED" },
    ]);
    expect(JSON.stringify(delivered, (_key, value) => typeof value === "bigint" ? value.toString() : value)).not.toContain("provider message must not escape");
  });

  it("rejects a retry after a failed stream without changing its replay", async () => {
    const current = session();
    const events = new InMemoryAssistantReplyEventStore();
    let responderCalls = 0;
    const service = new AssistantChatService({
      boundSession: current, eventStore: events,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      respondStream: async function* () {
        responderCalls += 1;
        throw new Error("transient provider failure");
      },
    });
    const request = { operationId: "retry-stream", messageId: "retry-message", session: current, text: "hello", zeroRetention: zeroRetention() };

    await expect(service.stream(request, () => {})).rejects.toMatchObject({ code: "ASSISTANT_REPLY_FAILED" });
    const replay = events.replay("retry-stream", 0n);
    await expect(service.stream(request, () => {})).rejects.toMatchObject({ code: "ASSISTANT_EVENT_TERMINAL" });
    expect(events.replay("retry-stream", 0n).map((event) => [event.kind, event.sequence]))
      .toEqual([["failed", 1n]]);
    expect(events.replay("retry-stream", 0n)).toEqual(replay);
    expect(responderCalls).toBe(1);
  });

  it("returns a completed retry before invoking a resolver that later changes", async () => {
    const current = session();
    let resolve = true;
    let resolverCalls = 0;
    let replies = 0;
    const service = new AssistantChatService({
      boundSession: current, boundConnectionGeneration: 1n,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
      resolveArtifact: async ({ session: requested }) => {
        resolverCalls += 1;
        return resolve ? committedAudio(requested) : null;
      },
      respond: async () => { replies += 1; return "stable reply"; },
    });
    const request = {
      operationId: "resolver-retry", messageId: "resolver-message", session: current, text: "listen",
      zeroRetention: zeroRetention(), attachments: [audioAttachment()],
    };

    await expect(service.send(request)).resolves.toMatchObject({ reply: "stable reply" });
    resolve = false;
    await expect(service.send(request)).resolves.toMatchObject({ reply: "stable reply" });
    expect({ resolverCalls, replies }).toEqual({ resolverCalls: 1, replies: 1 });
  });

  it("rejects invalid reply-event sequences and malformed failed event errors", () => {
    const events = new InMemoryAssistantReplyEventStore();
    expect(() => events.append({ kind: "complete", operationId: "event-op", messageId: "event-message", sequence: 0n, text: "reply" }))
      .toThrowError(/ASSISTANT_EVENT_SEQUENCE_INVALID/);
    expect(() => events.append({ kind: "failed", operationId: "event-op", messageId: "event-message", sequence: 1n, text: "", error: "" }))
      .toThrowError(/ASSISTANT_EVENT_INVALID/);
  });

  it("binds each reply-event operation to one message and one terminal event", () => {
    const events = new InMemoryAssistantReplyEventStore();
    events.append({ kind: "delta", operationId: "event-op", messageId: "event-message", sequence: 1n, text: "first" });
    expect(() => events.append({ kind: "delta", operationId: "event-op", messageId: "other-message", sequence: 2n, text: "other" }))
      .toThrowError(/ASSISTANT_EVENT_MESSAGE_MISMATCH/);
    events.append({ kind: "complete", operationId: "event-op", messageId: "event-message", sequence: 2n, text: "reply" });
    expect(() => events.append({ kind: "failed", operationId: "event-op", messageId: "event-message", sequence: 3n, text: "", error: "ASSISTANT_REPLY_FAILED" }))
      .toThrowError(/ASSISTANT_EVENT_TERMINAL/);
  });
});
