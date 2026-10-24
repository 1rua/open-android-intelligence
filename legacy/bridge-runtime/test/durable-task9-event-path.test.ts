import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
  createVerifiedCaptureAuthority,
  validateDeviceEvent,
  validateEventAck,
} from "../../protocol/src/event-contract.js";
import {
  DeterministicReplayLedger,
  referenceReplayClaim,
  type ReplayClaimReference,
  type ReplaySpace,
} from "../../protocol/src/replay-window.js";
import {
  DurableTask9EventPath,
  type Task9CaptureAuthorityRecord,
  type Task9SourceIdentity,
} from "../src/durable-task9-event-path.js";
import type { DurableBridgeStore } from "../../bridge-contract/src/durable-store.js";
import { FileBackedBridgeStore } from "../src/file-backed-store.js";

const roots: string[] = [];
const SOURCE_EPOCH = "018f4f9a-4444-4444-8444-444444444444";
const SOURCE: Task9SourceIdentity = {
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  sourceEpoch: SOURCE_EPOCH,
  sourceCapability: "notifications.metadata",
};
const AUTHORITY: Task9CaptureAuthorityRecord = {
  ...SOURCE,
  pairingGeneration: 3n,
  authorizationEpoch: 7n,
  scopeRevisions: new Map([["notifications.metadata", 4n]]),
};
const SUBSCRIPTION = {
  subscriptionId: "subscription-a",
  ...SOURCE,
  destination: {
    agentPrincipalId: "agent-principal-a",
    agentInstanceId: "agent-instance-a",
    workspaceId: "workspace-a",
    sessionId: "session-a",
    jobId: null,
  },
};

const makeRoot = async (): Promise<string> => {
  const root = await mkdtemp(join(tmpdir(), "open-android-intelligence-task9-"));
  roots.push(root);
  return root;
};

afterEach(async () => {
  const { rm } = await import("node:fs/promises");
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

const authority = () => createVerifiedCaptureAuthority({
  tenantId: AUTHORITY.tenantId,
  humanPrincipalId: AUTHORITY.humanPrincipalId,
  deviceId: AUTHORITY.deviceId,
  sourceCapability: AUTHORITY.sourceCapability,
  sourceEpoch: AUTHORITY.sourceEpoch,
  revision: {
    pairingGeneration: AUTHORITY.pairingGeneration,
    authorizationEpoch: AUTHORITY.authorizationEpoch,
    scopeRevisions: AUTHORITY.scopeRevisions,
  },
});

const occurrenceFor = (cursor: bigint): string =>
  `018f4f9a-4444-4444-8444-${cursor.toString(16).padStart(12, "0")}`;

const payloadFor = (cursor: bigint, occurrenceId = occurrenceFor(cursor)) => ({
      source_epoch: SOURCE.sourceEpoch,
      occurrence_id: occurrenceId,
      record_key: `notification.${cursor}`,
      record_revision: "1",
      cursor: cursor.toString(10),
      captured_at: "2026-08-11T00:00:00.000Z",
      event_kind: "upsert",
      source_capability: SOURCE.sourceCapability,
      capture_revision: {
        pairing_generation: "3",
        authorization_epoch: "7",
        scope_revisions: { "notifications.metadata": "4" },
      },
      record: { record_id: `record-${cursor}` },
      loss: null,
});

const frameFor = (cursor: bigint, occurrenceId = occurrenceFor(cursor)) => ({
  context: {
    kind: "device",
    tenantId: SOURCE.tenantId,
    humanPrincipalId: SOURCE.humanPrincipalId,
    deviceId: SOURCE.deviceId,
  },
  envelope: {
    messageType: "device_event",
    payload: payloadFor(cursor, occurrenceId),
  },
});

const verifiedEvent = (cursor: bigint, occurrenceId = occurrenceFor(cursor)) =>
  validateDeviceEvent(frameFor(cursor, occurrenceId) as never, authority());

const REPLAY_SPACE: ReplaySpace = {
  kind: "device",
  credentialId: "credential-a",
  pairingGeneration: 3n,
  keyId: "device-key-a",
  direction: "app-to-bridge",
};

const replayFor = (cursor: bigint, variant = 0): ReplayClaimReference<"device_event"> => {
  const byte = Number(cursor) + variant;
  const ledger = new DeterministicReplayLedger<"device_event">({
    claimIdSource: () => Buffer.alloc(32, byte).toString("base64url"),
  });
  const decision = ledger.admit(REPLAY_SPACE, {
    rawWire: { byteLength: 1, copy: () => Uint8Array.from([byte]) },
    messageType: "device_event",
    header: {
      message_id: `018f4f9a-5555-4555-8555-${byte.toString(16).padStart(12, "0")}`,
      sequence: cursor.toString(10),
      expires_at: "2026-08-12T00:00:00.000Z",
    },
    payload: {},
    registryEntry: {},
    signerRole: "device",
    envelopeDigest: Buffer.alloc(32, byte + 32).toString("base64url"),
  } as never, "2026-08-11T00:00:00.000Z", 1);
  if (decision.kind !== "accepted") throw new Error("expected replay admission");
  return referenceReplayClaim(decision.claim);
};

describe("DurableTask9EventPath", () => {
  it("buffers out of order, advances only a contiguous cursor, and recovers routes and ACKs after reopen", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const path = await DurableTask9EventPath.open({ store });
    await path.putCaptureAuthority(AUTHORITY);
    await path.putServerSubscription(SUBSCRIPTION);

    const beforeGap = await store.manifest();
    await expect(path.ingestEvent(verifiedEvent(2n), replayFor(2n))).resolves.toEqual({
      kind: "buffered",
      highestContiguousCursor: 0n,
      bufferedCursors: [2n],
      routed: [],
    });
    await expect(store.manifest()).resolves.toMatchObject({ generation: beforeGap.generation + 1 });
    await expect(path.loadCursorState(SOURCE)).resolves.toEqual({
      highestContiguousCursor: 0n,
      bufferedCursors: [2n],
    });
    await expect(path.recoverPendingRoutes("subscription-a")).resolves.toEqual([]);

    const reopenedStore = await FileBackedBridgeStore.open({ rootDir: root });
    const reopened = await DurableTask9EventPath.open({ store: reopenedStore });
    const beforeFill = await reopenedStore.manifest();
    await expect(reopened.ingestEvent(verifiedEvent(1n), replayFor(1n))).resolves.toEqual({
      kind: "committed",
      highestContiguousCursor: 2n,
      bufferedCursors: [],
      routed: [
        { subscriptionId: "subscription-a", cursor: 1n },
        { subscriptionId: "subscription-a", cursor: 2n },
      ],
    });
    await expect(reopenedStore.manifest()).resolves.toMatchObject({ generation: beforeFill.generation + 1 });
    await expect(reopened.recoverPendingRoutes("subscription-a")).resolves.toEqual([
      {
        subscriptionId: "subscription-a",
        cursor: 1n,
        occurrenceId: occurrenceFor(1n),
        eventKind: "upsert",
        destination: SUBSCRIPTION.destination,
      },
      {
        subscriptionId: "subscription-a",
        cursor: 2n,
        occurrenceId: occurrenceFor(2n),
        eventKind: "upsert",
        destination: SUBSCRIPTION.destination,
      },
    ]);
    await expect(reopened.recoverPendingAcks()).resolves.toEqual([{
      ...SOURCE,
      highestContiguousCursor: 2n,
    }]);
    await expect(reopened.loadEventAckAuthority({
      context: { kind: "device", tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a" },
      sourceCapability: SOURCE.sourceCapability,
    } as never)).resolves.toMatchObject({ highestContiguousCursor: 2n, sourceEpoch: SOURCE_EPOCH });

    await reopened.markAckSent(SOURCE, 2n);
    await expect(reopened.recoverPendingAcks()).resolves.toEqual([]);

    const ackAuthority = await reopened.loadEventAckAuthority({
      context: { kind: "device", tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a" },
      sourceCapability: SOURCE.sourceCapability,
    } as never);
    const ack = validateEventAck({
      context: {
        kind: "device",
        direction: "bridge-to-app",
        tenantId: "tenant-a",
        humanPrincipalId: "human-a",
        deviceId: "device-a",
      },
      envelope: {
        messageType: "event_ack",
        payload: {
          source_epoch: SOURCE.sourceEpoch,
          source_capability: SOURCE.sourceCapability,
          highest_contiguous_cursor: "2",
        },
      },
    }, ackAuthority!);
    await expect(reopened.persistBeforeSign(ack)).resolves.toEqual({ kind: "committed" });
    await expect(reopened.recoverPendingAcks()).resolves.toEqual([{ ...SOURCE, highestContiguousCursor: 2n }]);
  });

  it("makes exact duplicates idempotent and rejects a conflicting cursor without publishing any state", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const path = await DurableTask9EventPath.open({ store });
    await path.putCaptureAuthority(AUTHORITY);

    const event = verifiedEvent(1n);
    const replay = replayFor(1n);
    await expect(path.ingestEvent(event, replay)).resolves.toMatchObject({ kind: "committed", highestContiguousCursor: 1n });
    const committed = await store.manifest();
    await expect(path.ingestEvent(event, replay)).resolves.toMatchObject({ kind: "duplicate", highestContiguousCursor: 1n });
    await expect(store.manifest()).resolves.toMatchObject({ generation: committed.generation });

    await expect(path.ingestEvent(
      verifiedEvent(1n, "018f4f9a-6666-4666-8666-666666666666"),
      replayFor(1n, 10),
    )).rejects.toMatchObject({ code: "TASK9_EVENT_CURSOR_CONFLICT" });
    await expect(store.manifest()).resolves.toMatchObject({ generation: committed.generation });
    await expect(path.loadCursorState(SOURCE)).resolves.toEqual({ highestContiguousCursor: 1n, bufferedCursors: [] });
  });

  it("loads only the exact durable capture authority and rejects forged event/replay inputs before mutation", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const path = await DurableTask9EventPath.open({ store });
    await path.putCaptureAuthority(AUTHORITY);

    const loaded = await path.loadCaptureAuthority({
      context: { kind: "device", tenantId: "tenant-a", humanPrincipalId: "human-a", deviceId: "device-a" },
      sourceCapability: SOURCE.sourceCapability,
    } as never);
    expect(loaded).not.toBeNull();
    expect(validateDeviceEvent(frameFor(1n) as never, loaded!)).toMatchObject({
      sourceEpoch: SOURCE_EPOCH,
      cursor: 1n,
    });
    await expect(path.loadCaptureAuthority({
      context: { kind: "device", tenantId: "tenant-a", humanPrincipalId: "human-b", deviceId: "device-a" },
      sourceCapability: SOURCE.sourceCapability,
    } as never)).resolves.toBeNull();

    const before = await store.manifest();
    await expect(path.ingestEvent({ event: "forged" } as never, replayFor(1n)))
      .rejects.toThrowError("AUTH_FAILED");
    await expect(path.ingestEvent(verifiedEvent(1n), { claimId: "forged" } as never))
      .rejects.toThrowError("AUTH_FAILED");
    await expect(store.manifest()).resolves.toMatchObject({ generation: before.generation });

    await path.putCaptureAuthority({ ...AUTHORITY, authorizationEpoch: 8n });
    const afterRevisionAdvance = await store.manifest();
    await expect(path.ingestEvent(verifiedEvent(1n), replayFor(1n)))
      .rejects.toMatchObject({ code: "TASK9_CAPTURE_AUTHORITY_STALE" });
    await expect(store.manifest()).resolves.toMatchObject({ generation: afterRevisionAdvance.generation });
  });

  it("rolls back every staged event/ACK/cursor write when the durable transaction reports a database failure", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const setup = await DurableTask9EventPath.open({ store });
    await setup.putCaptureAuthority(AUTHORITY);
    const before = await store.manifest();
    const failingStore: DurableBridgeStore = {
      port: store.port,
      durability: "durable",
      transact: (scope, work) => store.transact(scope, (transaction) => {
        let writes = 0;
        return work({
          ...transaction,
          write: async (namespace, key, value) => {
            writes += 1;
            if (scope === "task9.event.ingest" && writes === 2) throw new Error("injected database failure");
            await transaction.write(namespace, key, value);
          },
        });
      }),
    };
    const path = await DurableTask9EventPath.open({ store: failingStore });

    await expect(path.ingestEvent(verifiedEvent(1n), replayFor(1n))).rejects.toThrow("injected database failure");
    await expect(store.manifest()).resolves.toMatchObject({ generation: before.generation });
    await expect(setup.loadCursorState(SOURCE)).resolves.toBeNull();
    await expect(setup.recoverPendingAcks()).resolves.toEqual([]);
  });

  it("routes only from closed server-owned subscriptions and rejects injected destination fields", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const path = await DurableTask9EventPath.open({ store });
    await path.putCaptureAuthority(AUTHORITY);
    await expect(path.putServerSubscription({
      ...SUBSCRIPTION,
      device_supplied_agent_principal_id: "attacker",
    } as never)).rejects.toMatchObject({ code: "TASK9_SUBSCRIPTION_INVALID" });
    await path.putServerSubscription(SUBSCRIPTION);
    await path.ingestEvent(verifiedEvent(1n), replayFor(1n));
    const [route] = await path.recoverPendingRoutes("subscription-a");
    expect(route?.destination).toEqual(SUBSCRIPTION.destination);
    expect(route).not.toHaveProperty("deviceSuppliedAgentPrincipalId");
  });

  it("fails closed when a reopened durable Task 9 row is malformed", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    await store.transact("task9.test.corrupt", (transaction) => transaction.write(
      "notification.positions",
      "task9/source/corrupt",
      { highest_contiguous_cursor: "01" },
    ));
    await expect(DurableTask9EventPath.open({ store })).rejects.toMatchObject({ code: "TASK9_STATE_INVALID" });
  });
});
