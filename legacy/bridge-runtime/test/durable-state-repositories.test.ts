import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import type { PairingTicket } from "../../bridge-contract/src/pairing-service.js";
import type {
  BridgeSessionIdentity,
  NotificationRecordV1,
} from "../../bridge-contract/src/service-types.js";
import { runDurableBridgeTransaction } from "../../bridge-contract/src/durable-store.js";
import { DurableBridgeStateRepositories } from "../src/durable-state-repositories.js";
import { FileBackedBridgeStore } from "../src/file-backed-store.js";

const roots: string[] = [];

const makeRoot = async (): Promise<string> => {
  const root = await mkdtemp(join(tmpdir(), "open-android-intelligence-durable-state-"));
  roots.push(root);
  return root;
};

const ticket = (): PairingTicket => ({
  ticketId: "ticket-a",
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  bridgeFingerprint: "sha256:bridge-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 4n,
  issuedAtMs: 1_000,
  expiresAtMs: 10_000,
});

const session = (): BridgeSessionIdentity => ({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  agentInstanceId: "agent-a",
  workspaceId: "workspace-a",
  sessionId: "session-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 4n,
});

const record = (cursor = 1n): NotificationRecordV1 => ({
  kind: "upsert",
  recordId: `mail-${cursor}`,
  packageId: "com.example.mail",
  title: "title",
  content: "body",
  sourceEpoch: 1n,
  cursor,
  captureRevision: 4n,
});

afterEach(async () => {
  const { rm } = await import("node:fs/promises");
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("DurableBridgeStateRepositories", () => {
  it("atomically consumes a verified ticket and reopens the fenced pairing binding", async () => {
    const root = await makeRoot();
    const firstStore = await FileBackedBridgeStore.open({ rootDir: root });
    const first = await DurableBridgeStateRepositories.open({ store: firstStore, clock: () => 2_000 });

    await expect(first.pairing.acceptVerified(ticket())).resolves.toMatchObject({
      bridgeFingerprint: "sha256:bridge-a",
      pairingGeneration: 1n,
      policyAttestationRevision: 4n,
    });

    const reopenedStore = await FileBackedBridgeStore.open({ rootDir: root });
    const reopened = await DurableBridgeStateRepositories.open({ store: reopenedStore, clock: () => 2_000 });
    await expect(reopened.pairing.current(session())).resolves.toMatchObject({
      tenantId: "tenant-a",
      humanPrincipalId: "human-a",
      deviceId: "device-a",
      pairingGeneration: 1n,
    });
    await expect(reopened.pairing.acceptVerified(ticket()))
      .rejects.toMatchObject({ code: "PAIRING_TICKET_REPLAY" });
  });

  it("commits a notification record with its identity-scoped cursor and rejects rollback after reopen", async () => {
    const root = await makeRoot();
    const first = await DurableBridgeStateRepositories.open({
      store: await FileBackedBridgeStore.open({ rootDir: root }),
    });
    await expect(first.notifications.append(session(), record(2n))).resolves.toBe(true);

    const reopened = await DurableBridgeStateRepositories.open({
      store: await FileBackedBridgeStore.open({ rootDir: root }),
    });
    await expect(reopened.notifications.read(session(), 10, { fields: ["content"] }))
      .resolves.toMatchObject([{ recordId: "mail-2", cursor: 2n, content: "body" }]);
    await expect(reopened.notifications.append(session(), record(1n)))
      .rejects.toMatchObject({ code: "NOTIFICATION_CURSOR_REPLAY" });
  });

  it("reopens pending subscription events and atomically persists their ACK", async () => {
    const root = await makeRoot();
    const first = await DurableBridgeStateRepositories.open({
      store: await FileBackedBridgeStore.open({ rootDir: root }),
    });
    await first.subscriptions.subscribe({
      subscriptionId: "subscription-a",
      session: session(),
      filter: { packages: ["com.example.mail"], fields: ["metadata"] },
    });
    const event = await first.subscriptions.publish("subscription-a", session(), record(1n));
    expect(event).toMatchObject({
      eventId: "event-1-1",
      subscriptionId: "subscription-a",
      title: null,
      content: null,
    });

    const reopened = await DurableBridgeStateRepositories.open({
      store: await FileBackedBridgeStore.open({ rootDir: root }),
    });
    await expect(reopened.subscriptions.pending("subscription-a", session()))
      .resolves.toMatchObject([{ eventId: "event-1-1", cursor: 1n }]);
    await reopened.subscriptions.acknowledge({
      subscriptionId: "subscription-a",
      eventId: "event-1-1",
      session: session(),
      sourceEpoch: 1n,
      cursor: 1n,
    });

    const afterAck = await DurableBridgeStateRepositories.open({
      store: await FileBackedBridgeStore.open({ rootDir: root }),
    });
    await expect(afterAck.subscriptions.pending("subscription-a", session())).resolves.toEqual([]);
  });

  it("fails closed when reopened state contains an orphan subscription event", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    await runDurableBridgeTransaction(store, "test.seed", (transaction) => transaction.write(
      "subscription.events",
      "orphan-event",
      {
        acknowledged: false,
        event: {
          kind: "upsert",
          recordId: "mail-1",
          packageId: "com.example.mail",
          title: null,
          content: null,
          sourceEpoch: "1",
          cursor: "1",
          captureRevision: "4",
          eventId: "orphan-event",
          subscriptionId: "missing-subscription",
          binding: {
            tenantId: "tenant-a",
            humanPrincipalId: "human-a",
            deviceId: "device-a",
            agentInstanceId: "agent-a",
            workspaceId: "workspace-a",
            sessionId: "session-a",
            pairingGeneration: "1",
            policyAttestationRevision: "4",
          },
        },
      },
    ));

    await expect(DurableBridgeStateRepositories.open({ store }))
      .rejects.toMatchObject({ code: "DURABLE_SUBSCRIPTION_STATE_INVALID" });
  });

  it("fails closed when a consumed pairing ticket has no binding", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const candidate = ticket();
    await runDurableBridgeTransaction(store, "test.seed", (transaction) => transaction.write(
      "pairing.tickets",
      candidate.ticketId,
      {
        consumed: true,
        ticket: {
          ...candidate,
          pairingGeneration: "1",
          policyAttestationRevision: "4",
        },
      },
    ));

    await expect(DurableBridgeStateRepositories.open({ store }))
      .rejects.toMatchObject({ code: "DURABLE_PAIRING_STATE_INVALID" });
  });

  it("atomically removes a subscription and all of its pending events", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const repositories = await DurableBridgeStateRepositories.open({ store });
    await repositories.subscriptions.subscribe({ subscriptionId: "subscription-a", session: session() });
    await repositories.subscriptions.publish("subscription-a", session(), record(1n));

    await expect(repositories.subscriptions.unsubscribe("subscription-a", session())).resolves.toBe(true);
    await expect(store.transact("test.scan", (transaction) => transaction.scan("subscription.events")))
      .resolves.toEqual([]);
    await expect(repositories.subscriptions.pending("subscription-a", session()))
      .rejects.toMatchObject({ code: "SUBSCRIPTION_NOT_FOUND" });
  });
});
