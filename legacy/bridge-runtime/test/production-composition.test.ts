import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import type { DurableBridgeTransaction } from "../../bridge-contract/src/durable-store.js";
import type { PairingTicket } from "../../bridge-contract/src/pairing-service.js";
import {
  NODE_SQLITE_BRIDGE_DRIVER,
  SQLITE_BRIDGE_ADAPTER_PORT,
  type SqliteBridgeAdapterPort,
} from "../../bridge-contract/src/persistence.js";
import { BridgeServiceError, type BridgeSessionIdentity } from "../../bridge-contract/src/service-types.js";
import { createFencedDurableBridgeComposition } from "../src/composition.js";
import { FileBackedBridgeStore } from "../src/file-backed-store.js";
import {
  BRIDGE_LEASE_COORDINATOR_PORT,
  PAIRING_TICKET_VERIFIER_PORT,
  type BridgeLease,
  type BridgeLeaseCoordinatorPort,
  type PairingTicketVerifierPort,
} from "../src/production-ports.js";

const roots: string[] = [];

const makeRoot = async (): Promise<string> => {
  const root = await mkdtemp(join(tmpdir(), "open-android-intelligence-production-composition-"));
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

const verifier = (status: PairingTicketVerifierPort["status"] = "connected"): PairingTicketVerifierPort => ({
  port: PAIRING_TICKET_VERIFIER_PORT,
  status,
  verify: async (candidate) => candidate as PairingTicket,
});

const sqlitePort = (store: FileBackedBridgeStore): SqliteBridgeAdapterPort => ({
  port: SQLITE_BRIDGE_ADAPTER_PORT,
  backend: "sqlite",
  driver: NODE_SQLITE_BRIDGE_DRIVER,
  status: "connected",
  databasePath: "/external/bridge.sqlite",
  transact: store.transact.bind(store),
  schemaVersion: async () => 3,
  runMigration: async (_scope, _from, _to, work) => store.transact("test.migration", work),
  backup: async (destination) => ({ artifact: "backup", path: destination, schemaVersion: 3, digest: "sha256:test" }),
  restore: async () => ({ restored: true, schemaVersion: 3, digest: "sha256:test" }),
  recover: async () => ({ recovered: true, schemaVersion: 3, repaired: false, discardedArtifacts: [] }),
});

class TestLeaseCoordinator implements BridgeLeaseCoordinatorPort {
  readonly port = BRIDGE_LEASE_COORDINATOR_PORT;
  readonly status = "connected" as const;
  readonly #store: FileBackedBridgeStore;
  #token = 0n;
  #current: BridgeLease | null = null;

  constructor(store: FileBackedBridgeStore) {
    this.#store = store;
  }

  async acquire(input: Readonly<{ scope: string; ownerId: string; ttlMs: number }>): Promise<BridgeLease> {
    this.#current = Object.freeze({
      scope: input.scope,
      ownerId: input.ownerId,
      fencingToken: ++this.#token,
      expiresAtMs: 20_000,
    });
    return this.#current;
  }

  async renew(lease: BridgeLease): Promise<BridgeLease> {
    this.#assertCurrent(lease);
    return lease;
  }

  async transact<T>(
    lease: BridgeLease,
    scope: string,
    work: (transaction: DurableBridgeTransaction) => Promise<T> | T,
  ): Promise<T> {
    this.#assertCurrent(lease);
    return this.#store.transact(scope, work);
  }

  async release(lease: BridgeLease): Promise<void> {
    this.#assertCurrent(lease);
    this.#current = null;
  }

  async fenceWithAnotherOwner(): Promise<void> {
    await this.acquire({ scope: "bridge.runtime", ownerId: "worker-b", ttlMs: 5_000 });
  }

  #assertCurrent(lease: BridgeLease): void {
    if (!this.#current || lease.fencingToken !== this.#current.fencingToken || lease.ownerId !== this.#current.ownerId) {
      throw new BridgeServiceError("BRIDGE_LEASE_FENCED");
    }
  }
}

afterEach(async () => {
  const { rm } = await import("node:fs/promises");
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("fenced durable Bridge composition", () => {
  it("rejects the local file adapter as a production persistence dependency", async () => {
    const store = await FileBackedBridgeStore.open({ rootDir: await makeRoot() });
    const leases = new TestLeaseCoordinator(store);
    await expect(createFencedDurableBridgeComposition({
      persistence: store,
      leases,
      pairingVerifier: verifier(),
      ownerId: "worker-a",
      leaseTtlMs: 5_000,
    })).rejects.toMatchObject({ code: "SQLITE_BRIDGE_ADAPTER_REQUIRED" });
  });

  it("rejects an unconnected external ticket verifier", async () => {
    const store = await FileBackedBridgeStore.open({ rootDir: await makeRoot() });
    await expect(createFencedDurableBridgeComposition({
      persistence: sqlitePort(store),
      leases: new TestLeaseCoordinator(store),
      pairingVerifier: verifier("external-secret-store-required"),
      ownerId: "worker-a",
      leaseTtlMs: 5_000,
    })).rejects.toMatchObject({ code: "PAIRING_TICKET_VERIFIER_PENDING" });
  });

  it("routes every durable transition through the active fencing token", async () => {
    const store = await FileBackedBridgeStore.open({ rootDir: await makeRoot() });
    const leases = new TestLeaseCoordinator(store);
    const composition = await createFencedDurableBridgeComposition({
      persistence: sqlitePort(store),
      leases,
      pairingVerifier: verifier(),
      ownerId: "worker-a",
      leaseTtlMs: 5_000,
      clock: () => 2_000,
    });
    await expect(composition.pairing.accept(ticket())).resolves.toMatchObject({ pairingGeneration: 1n });
    expect(composition.durableNamespaces).toContain("operation.replay-associations");
    expect(composition.productionClaim).toBe("source-seam-only");

    await leases.fenceWithAnotherOwner();
    await expect(composition.state.notifications.append(session(), {
      kind: "loss_marker",
      recordId: "loss-a",
      packageId: null,
      title: null,
      content: null,
      sourceEpoch: 1n,
      cursor: 1n,
      captureRevision: 4n,
    })).rejects.toMatchObject({ code: "BRIDGE_LEASE_FENCED" });
  });
});
