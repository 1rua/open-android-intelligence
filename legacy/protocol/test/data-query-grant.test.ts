import { describe, expect, it } from "vitest";
import {
  capabilityFilterHash,
  loadCapabilityRegistry,
} from "../src/capability-manifest.js";
import {
  DeterministicReplayLedger,
  referenceReplayClaim,
  type ReplaySpace,
} from "../src/replay-window.js";
import {
  attachGrantReplayAssociation,
  createDataQueryGrantState,
  dataQueryGrantProposalDigest,
  MemoryDataQueryGrantStore,
  mintGrantExpansionAuthority,
  reduceDataQueryGrant,
  verifyDataQueryGrantAck,
  verifyDataQueryGrantUpdate,
  type DataQueryGrantKey,
} from "../src/data-query-grant.js";

const key: DataQueryGrantKey = {
  tenantId: "tenant", humanPrincipalId: "human", deviceId: "device",
  capability: "notifications.metadata", filterHash: capabilityFilterHash("notifications.metadata", { packages: ["com.example.mail"], fields: ["metadata"] }),
};
const secondKey: DataQueryGrantKey = {
  tenantId: "tenant", humanPrincipalId: "human", deviceId: "device",
  capability: "notifications.content", filterHash: capabilityFilterHash("notifications.content", { fields: ["content"] }),
};
const frame = (payload: Record<string, unknown>, messageType = "data_query_grant_update") => ({
  envelope: { messageType, payload },
  context: { kind: "device", tenantId: "tenant", humanPrincipalId: "human", deviceId: "device", direction: messageType === "data_query_grant_ack" ? "bridge-to-app" : "app-to-bridge" },
});

const replaySpace: ReplaySpace = {
  kind: "device", credentialId: "credential", pairingGeneration: 1n,
  keyId: "key", direction: "app-to-bridge",
};

const replayFor = (messageId = "018f4f9a-4444-4444-8444-444444444444") => {
  const ledger = new DeterministicReplayLedger<"data_query_grant_update">({
    claimIdSource: () => "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  });
  const accepted = ledger.admit(replaySpace, {
    rawWire: { byteLength: 1, copy: () => Uint8Array.from([1]) },
    messageType: "data_query_grant_update",
    header: { message_id: messageId, sequence: "1", expires_at: "2026-08-11T00:01:00.000Z" },
    payload: {}, registryEntry: {}, signerRole: "device",
    envelopeDigest: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  } as never, "2026-08-11T00:00:00.000Z", 1);
  if (accepted.kind !== "accepted") throw new Error("expected replay claim");
  return referenceReplayClaim(accepted.claim);
};

describe("data-query grants", () => {
  it("binds the exact closed filter hash and rejects a wrong digest before a grant is created", () => {
    const filter = { packages: ["com.example.mail"], fields: ["metadata"] };
    const payload = {
      grant_key: { tenant_id: "tenant", human_principal_id: "human", device_id: "device", capability: key.capability, filter_hash: key.filterHash },
      filter, grant_revision: "1", proposed_state: "effective",
      proposal_digest: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    };
    expect(() => verifyDataQueryGrantUpdate(frame(payload), loadCapabilityRegistry(), null)).toThrowError("INTEGRITY_FAILED");
    const digestPayload = { ...payload, proposal_digest: undefined };
    void digestPayload;
  });

  it("keeps device UI pending until the matching bridge ACK", () => {
    const state = createDataQueryGrantState({ key, grantRevision: 0n, bridgeEffectiveState: "revoked", deviceUiState: "revoked" });
    const filter = { packages: ["com.example.mail"], fields: ["metadata"] };
    const proposalDigest = dataQueryGrantProposalDigest({ filter, key, grantRevision: 1n, proposedState: "effective" });
    const update = verifyDataQueryGrantUpdate(frame({
      grant_key: { tenant_id: "tenant", human_principal_id: "human", device_id: "device", capability: key.capability, filter_hash: key.filterHash },
      filter, grant_revision: "1", proposed_state: "effective", proposal_digest: proposalDigest,
    }), loadCapabilityRegistry(), mintGrantExpansionAuthority({
      key, humanPrincipalId: "human", authenticatedSessionId: "session", origin: "authenticated_device",
    }));
    const pending = reduceDataQueryGrant(state, update);
    expect(pending.deviceUiState).toBe("grant_pending_ack");
    expect(pending.bridgeEffectiveState).toBe("revoked");
    const ack = verifyDataQueryGrantAck(frame({
      grant_key: { tenant_id: "tenant", human_principal_id: "human", device_id: "device", capability: key.capability, filter_hash: key.filterHash },
      grant_revision: "1", proposal_digest: proposalDigest, bridge_effective_state: "effective",
    }, "data_query_grant_ack"));
    const effective = reduceDataQueryGrant(pending, ack);
    expect(effective).toMatchObject({ grantRevision: 1n, bridgeEffectiveState: "effective", deviceUiState: "effective" });
  });

  it("commits revoke/effective state and an idempotent exact ACK outbox", async () => {
    const filter = { packages: ["com.example.mail"], fields: ["metadata"] };
    const proposalDigest = dataQueryGrantProposalDigest({ filter, key, grantRevision: 1n, proposedState: "effective" });
    const update = verifyDataQueryGrantUpdate(frame({
      grant_key: { tenant_id: "tenant", human_principal_id: "human", device_id: "device", capability: key.capability, filter_hash: key.filterHash },
      filter, grant_revision: "1", proposed_state: "effective", proposal_digest: proposalDigest,
    }), loadCapabilityRegistry(), mintGrantExpansionAuthority({ key, humanPrincipalId: "human", authenticatedSessionId: "session", origin: "authenticated_device" }));
    const store = new MemoryDataQueryGrantStore();
    const replay = replayFor();
    const bound = attachGrantReplayAssociation(update, replay);
    const applied = await store.applyBridgeUpdateAtomically(bound);
    expect(applied.kind).toBe("applied");
    if (applied.kind !== "applied") return;
    const duplicate = await store.applyBridgeUpdateAtomically(bound);
    expect(duplicate).toMatchObject({ kind: "same", ackOutboxId: applied.ackOutboxId });
    await expect(store.loadGrantReplayAssociation(key, proposalDigest)).resolves.toEqual(replay);
    const conflictingReplay = attachGrantReplayAssociation(update, replayFor("018f4f9a-7777-4777-8777-777777777777"));
    await expect(store.applyBridgeUpdateAtomically(conflictingReplay)).resolves.toEqual({ kind: "rejected", error: "AUTH_FAILED" });
    await expect(store.loadGrantReplayAssociation(key, proposalDigest)).resolves.toEqual(replay);
    const outbox = await store.loadAckOutbox(applied.ackOutboxId);
    expect(outbox?.rawWire.copy()).toEqual((await store.loadAckOutbox(applied.ackOutboxId))?.rawWire.copy());
    await store.markAckSent(applied.ackOutboxId);
    const recovered: unknown[] = [];
    for await (const item of store.recoverUnsentAcks()) recovered.push(item);
    expect(recovered).toHaveLength(0);
  });

  it("rejects an unassociated grant update before state, ACK or replay mutation", async () => {
    const filter = { packages: ["com.example.mail"], fields: ["metadata"] };
    const proposalDigest = dataQueryGrantProposalDigest({ filter, key, grantRevision: 1n, proposedState: "effective" });
    const update = verifyDataQueryGrantUpdate(frame({
      grant_key: { tenant_id: "tenant", human_principal_id: "human", device_id: "device", capability: key.capability, filter_hash: key.filterHash },
      filter, grant_revision: "1", proposed_state: "effective", proposal_digest: proposalDigest,
    }), loadCapabilityRegistry(), mintGrantExpansionAuthority({ key, humanPrincipalId: "human", authenticatedSessionId: "session", origin: "authenticated_device" }));
    const store = new MemoryDataQueryGrantStore();
    await expect(store.applyBridgeUpdateAtomically(update as never)).resolves.toEqual({ kind: "rejected", error: "AUTH_FAILED" });
    await expect(store.load(key)).resolves.toBeNull();
    const unsent: unknown[] = [];
    for await (const item of store.recoverUnsentAcks()) unsent.push(item);
    expect(unsent).toHaveLength(0);
  });

  it("binds a replay reference to the exact accepted message identity", () => {
    const filter = { packages: ["com.example.mail"], fields: ["metadata"] };
    const proposalDigest = dataQueryGrantProposalDigest({ filter, key, grantRevision: 1n, proposedState: "effective" });
    const update = verifyDataQueryGrantUpdate(frame({
      grant_key: { tenant_id: "tenant", human_principal_id: "human", device_id: "device", capability: key.capability, filter_hash: key.filterHash },
      filter, grant_revision: "1", proposed_state: "effective", proposal_digest: proposalDigest,
    }), loadCapabilityRegistry(), mintGrantExpansionAuthority({ key, humanPrincipalId: "human", authenticatedSessionId: "session", origin: "authenticated_device" }));
    expect(() => attachGrantReplayAssociation(update, replayFor("018f4f9a-5555-4555-8555-555555555555"))).not.toThrow();
    expect(() => attachGrantReplayAssociation({
      ...update,
      frame: { ...frame(update.frame as never), envelope: { ...frame(update.frame as never).envelope, header: { message_id: "018f4f9a-6666-4666-8666-666666666666" } } },
    } as never, replayFor())).toThrowError("INTEGRITY_FAILED");
  });

  it("does not let one accepted replay claim authorize two different grant proposals", async () => {
    const firstFilter = { packages: ["com.example.mail"], fields: ["metadata"] };
    const firstDigest = dataQueryGrantProposalDigest({ filter: firstFilter, key, grantRevision: 1n, proposedState: "effective" });
    const first = verifyDataQueryGrantUpdate(frame({
      grant_key: { tenant_id: "tenant", human_principal_id: "human", device_id: "device", capability: key.capability, filter_hash: key.filterHash },
      filter: firstFilter, grant_revision: "1", proposed_state: "effective", proposal_digest: firstDigest,
    }), loadCapabilityRegistry(), mintGrantExpansionAuthority({ key, humanPrincipalId: "human", authenticatedSessionId: "session", origin: "authenticated_device" }));
    const secondFilter = { fields: ["content"] };
    const secondDigest = dataQueryGrantProposalDigest({ filter: secondFilter, key: secondKey, grantRevision: 1n, proposedState: "effective" });
    const second = verifyDataQueryGrantUpdate(frame({
      grant_key: { tenant_id: "tenant", human_principal_id: "human", device_id: "device", capability: secondKey.capability, filter_hash: secondKey.filterHash },
      filter: secondFilter, grant_revision: "1", proposed_state: "effective", proposal_digest: secondDigest,
    }), loadCapabilityRegistry(), mintGrantExpansionAuthority({ key: secondKey, humanPrincipalId: "human", authenticatedSessionId: "session", origin: "authenticated_device" }));
    const replay = replayFor();
    const store = new MemoryDataQueryGrantStore();
    await expect(store.applyBridgeUpdateAtomically(attachGrantReplayAssociation(first, replay))).resolves.toMatchObject({ kind: "applied" });
    await expect(store.applyBridgeUpdateAtomically(attachGrantReplayAssociation(second, replay))).resolves.toEqual({ kind: "rejected", error: "AUTH_FAILED" });
    await expect(store.load(secondKey)).resolves.toBeNull();
  });
});
