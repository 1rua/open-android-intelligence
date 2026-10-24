import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { OperationDispatcher } from "../../bridge-contract/src/operation-dispatch.js";
import { PairingService } from "../../bridge-contract/src/pairing-service.js";
import { NotificationStore } from "../../bridge-contract/src/notification-store.js";
import { sessionKey, type BridgeSessionIdentity } from "../../bridge-contract/src/service-types.js";
import { runDurableBridgeTransaction } from "../../bridge-contract/src/durable-store.js";
import { createDurableBridgeComposition } from "../src/composition.js";
import { DurableOperationDispatcher } from "../src/durable-operation-dispatcher.js";
import { FileBackedBridgeStore } from "../src/file-backed-store.js";

const roots: string[] = [];

const makeRoot = async (): Promise<string> => {
  const root = await mkdtemp(join(tmpdir(), "open-android-intelligence-durable-operations-"));
  roots.push(root);
  return root;
};

const session = (): BridgeSessionIdentity => ({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  agentInstanceId: "agent-a",
  workspaceId: "workspace-a",
  sessionId: "session-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 1n,
});

afterEach(async () => {
  const { rm } = await import("node:fs/promises");
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("DurableOperationDispatcher", () => {
  it("retains a completed claim and result after the dispatcher is reopened", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const first = await DurableOperationDispatcher.open({ store });
    const request = { operationId: "op-reopen", session: session(), parameters: { mode: "on_demand" } };
    let calls = 0;

    await expect(first.execute(request, async () => {
      calls += 1;
      return { records: [] };
    })).resolves.toEqual({ records: [] });
    expect(calls).toBe(1);

    const reopened = await DurableOperationDispatcher.open({ store });
    await expect(reopened.execute(request, async () => {
      calls += 1;
      return { records: ["must-not-run"] };
    })).resolves.toEqual({ records: [] });
    expect(calls).toBe(1);
    expect(reopened.claims()).toEqual([{ operationId: "op-reopen", claims: 1 }]);
  });

  it("removes a pending claim after an action failure so a retry can run", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const dispatcher = await DurableOperationDispatcher.open({ store });
    const request = { operationId: "op-retry", session: session() };

    await expect(dispatcher.execute(request, async () => {
      throw new Error("simulated action failure");
    })).rejects.toThrow("simulated action failure");
    await expect(dispatcher.execute(request, async () => ({ ok: true }))).resolves.toEqual({ ok: true });
  });

  it("releases a pending claim left by a crash when the dispatcher is reopened", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const current = session();
    await runDurableBridgeTransaction(store, "test.seed", async (transaction) => {
      await transaction.write("operation.claims", "op-crash", {
        sessionKey: sessionKey(current),
        parametersDigest: "null",
        status: "pending",
        claims: 1,
      });
    });

    const reopened = await DurableOperationDispatcher.open({ store });
    await expect(reopened.execute({ operationId: "op-crash", session: current }, async () => ({ recovered: true })))
      .resolves.toEqual({ recovered: true });
  });

  it("rejects a process-local operation dispatcher at the durable composition root", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const pairing = new PairingService({ clock: () => 1000 });

    await expect(createDurableBridgeComposition({
      durableStore: store,
      operations: new OperationDispatcher(),
      pairing,
    })).rejects.toThrowError(/DURABLE_OPERATION_DISPATCHER_REQUIRED/);
  });

  it("wires the durable operation ledger into NotificationService", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const request = {
      operationId: "notification-op",
      mode: "auto_send" as const,
      limit: 1,
      policyRevision: 1n,
    };
    const createPairing = () => new PairingService({ clock: () => 1000 });
    const firstPairing = createPairing();
    const first = await createDurableBridgeComposition({
      durableStore: store,
      pairing: firstPairing,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
    });
    const firstSession = first.notifications.pair(firstPairing.issueTicket({
      tenantId: "tenant-a",
      humanPrincipalId: "human-a",
      deviceId: "device-a",
      bridgeFingerprint: "bridge-a",
      pairingGeneration: 1n,
      policyAttestationRevision: 1n,
    }));
    await expect(first.notifications.query({ ...request, session: firstSession })).resolves.toEqual([]);

    const secondPairing = createPairing();
    const second = await createDurableBridgeComposition({
      durableStore: store,
      pairing: secondPairing,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
    });
    const secondSession = second.notifications.pair(secondPairing.issueTicket({
      tenantId: "tenant-a",
      humanPrincipalId: "human-a",
      deviceId: "device-a",
      bridgeFingerprint: "bridge-a",
      pairingGeneration: 1n,
      policyAttestationRevision: 1n,
    }));
    await expect(second.notifications.query({ ...request, session: secondSession })).resolves.toEqual([]);
    expect(second.notifications.operationClaims()).toEqual([{ operationId: "notification-op", claims: 1 }]);

    expect(first.durableNamespaces).toEqual(["operation.claims"]);
    expect(first.processLocalNamespaces).toContain("notification.records");
  });

  it("persists closed notification records with bigint cursors", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const pairing = new PairingService({ clock: () => 1000 });
    const notificationStore = new NotificationStore();
    const composition = await createDurableBridgeComposition({
      durableStore: store,
      pairing,
      notificationStore,
      authorize: ({ policyRevision }) => ({ allowed: true, policyRevision }),
    });
    const session = composition.notifications.pair(pairing.issueTicket({
      tenantId: "tenant-a",
      humanPrincipalId: "human-a",
      deviceId: "device-a",
      bridgeFingerprint: "bridge-a",
      pairingGeneration: 1n,
      policyAttestationRevision: 1n,
    }));
    composition.notifications.ingest(session, {
      kind: "upsert",
      recordId: "mail-1",
      packageId: "com.example.mail",
      title: "title",
      content: null,
      sourceEpoch: 1n,
      cursor: 1n,
      captureRevision: 1n,
    });

    const query = {
      operationId: "notification-bigint",
      session,
      mode: "on_demand" as const,
      limit: 1,
      policyRevision: 1n,
    };
    await expect(composition.notifications.query(query))
      .resolves.toMatchObject([{ recordId: "mail-1", sourceEpoch: 1n, cursor: 1n }]);

    const reopened = await DurableOperationDispatcher.open({ store });
    await expect(reopened.execute({
      operationId: query.operationId,
      session,
      parameters: { mode: query.mode, limit: query.limit, filter: undefined, policyRevision: query.policyRevision },
    }, async () => [{ sourceEpoch: 999n }]))
      .resolves.toMatchObject([{ recordId: "mail-1", sourceEpoch: 1n, cursor: 1n }]);
  });

  it("atomically associates a replay key with its operation claim across reopen", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    const request = { operationId: "op-associated", session: session(), parameters: { kind: "device_event" } };
    const association = { replayKey: "device-event:key-a", payloadDigest: "sha256:payload-a" };
    const first = await DurableOperationDispatcher.open({ store });
    let calls = 0;
    await expect(first.executeWithReplay(request, association, async () => {
      calls += 1;
      return { accepted: true };
    })).resolves.toEqual({ accepted: true });

    const reopened = await DurableOperationDispatcher.open({ store });
    await expect(reopened.executeWithReplay(request, association, async () => {
      calls += 1;
      return { accepted: false };
    })).resolves.toEqual({ accepted: true });
    expect(calls).toBe(1);
    await expect(reopened.executeWithReplay(
      { ...request, operationId: "op-other" },
      association,
      async () => ({ accepted: false }),
    )).rejects.toMatchObject({ code: "REPLAY_ASSOCIATION_OPERATION_MISMATCH" });
    await expect(reopened.executeWithReplay(
      request,
      { ...association, payloadDigest: "sha256:payload-other" },
      async () => ({ accepted: false }),
    )).rejects.toMatchObject({ code: "REPLAY_ASSOCIATION_DIGEST_MISMATCH" });
  });

  it("fails closed when a reopened replay association is incomplete", async () => {
    const root = await makeRoot();
    const store = await FileBackedBridgeStore.open({ rootDir: root });
    await runDurableBridgeTransaction(store, "test.seed", (transaction) => transaction.write(
      "operation.replay-associations" as never,
      "device-event:key-a",
      { operationId: "op-associated" },
    ));

    await expect(DurableOperationDispatcher.open({ store }))
      .rejects.toMatchObject({ code: "DURABLE_REPLAY_ASSOCIATION_STATE_INVALID" });
  });
});
