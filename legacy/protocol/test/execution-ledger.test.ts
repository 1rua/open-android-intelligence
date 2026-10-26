import { describe, expect, it } from "vitest";
import { canonicalBytes, sha256B64Url } from "../src/encoding.js";
import {
  MemoryExecutionLedger,
  mintSignedOperationReceipt,
  mintTrustedExecutionReconciler,
  recordResult,
  claimExecution,
} from "../src/execution-ledger.js";
import { createOperationRecord } from "../src/operation-machine.js";

const digest = sha256B64Url(canonicalBytes({ parameters: "v1" }));
const binding = {
  tenantId: "tenant-a", humanPrincipalId: "human-a", agentPrincipalId: "agent-a",
  agentInstanceId: "instance-a", workspaceId: "workspace-a",
  sessionOrJob: { kind: "session", sessionId: "session-a" } as const,
  deviceId: "device-a", operationId: "operation-ledger-a",
  capability: "notifications.content", parametersDigest: digest,
};
const record = createOperationRecord({
  binding,
  revisionSnapshot: { pairingGeneration: 1n, authorizationEpoch: 1n, scopeRevisions: { "notifications.content": 1n } },
  operationExpiresAt: "2026-08-11T00:00:30.000Z",
  offlinePolicy: "WAIT_READ",
  state: { request_status: "approved", terminal_outcome: null, operation_reason: null },
});

function receipt(messageId = "receipt-a", resultDigest = digest) {
  return mintSignedOperationReceipt({
    operationId: binding.operationId,
    parametersDigest: digest,
    stateRevision: 2n,
    state: { request_status: null, terminal_outcome: "succeeded", operation_reason: null },
    resultDigest,
    envelopeDigest: sha256B64Url(canonicalBytes({ messageId, resultDigest })),
    messageId,
    canonicalValue: { messageId, resultDigest },
  });
}

describe("Task 7 execution claim/result ledger", () => {
  it("deduplicates registration and returns one claim for duplicate retries", async () => {
    const store = new MemoryExecutionLedger({ claimIdSource: () => "claim-a" });
    await expect(store.register({ record, registrationReplayClaimId: "replay-a" })).resolves.toBe("new");
    await expect(store.register({ record, registrationReplayClaimId: "replay-a" })).resolves.toBe("same");
    await expect(claimExecution(store, { operationId: binding.operationId, parametersDigest: digest, claimedAt: "2026-08-11T00:00:01.000Z" })).resolves.toEqual({ kind: "claimed", claimId: "claim-a" });
    await expect(claimExecution(store, { operationId: binding.operationId, parametersDigest: digest, claimedAt: "2026-08-11T00:00:02.000Z" })).resolves.toEqual({ kind: "already_claimed", claimId: "claim-a" });
  });

  it("retains the first receipt, makes exact retries idempotent and rejects mismatches", async () => {
    const store = new MemoryExecutionLedger({ claimIdSource: () => "claim-b" });
    await store.register({ record, registrationReplayClaimId: "replay-b" });
    await expect(claimExecution(store, { operationId: binding.operationId, parametersDigest: digest, claimedAt: "2026-08-11T00:00:01.000Z" })).resolves.toEqual({ kind: "claimed", claimId: "claim-b" });
    const first = receipt();
    await expect(recordResult(store, { operationId: binding.operationId, parametersDigest: digest, claimId: "claim-b", receipt: first })).resolves.toBe("stored");
    await expect(recordResult(store, { operationId: binding.operationId, parametersDigest: digest, claimId: "claim-b", receipt: first })).resolves.toBe("same");
    await expect(recordResult(store, { operationId: binding.operationId, parametersDigest: digest, claimId: "claim-b", receipt: receipt("receipt-different", digest) })).resolves.toBe("RESULT_CONFLICT");
    const entry = await store.get(binding.operationId);
    expect(entry?.kind).toBe("result");
    if (entry?.kind === "result") expect(entry.receipt.messageId).toBe("receipt-a");
  });

  it("turns a claimed operation without a receipt into result_unknown on recovery", async () => {
    const store = new MemoryExecutionLedger({ claimIdSource: () => "claim-c" });
    await store.register({ record, registrationReplayClaimId: "replay-c" });
    await store.claim({ operationId: binding.operationId, parametersDigest: digest, claimedAt: "2026-08-11T00:00:01.000Z" });
    await expect(store.recoverClaimedWithoutResult(mintTrustedExecutionReconciler("reconciler-a"))).resolves.toEqual({ recoveredOperationIds: [binding.operationId] });
    const entry = await store.get(binding.operationId);
    expect(entry?.record.state).toEqual({ request_status: null, terminal_outcome: "result_unknown", operation_reason: null });
    await expect(recordResult(store, { operationId: binding.operationId, parametersDigest: digest, claimId: "claim-c", receipt: receipt() })).resolves.toBe("INVALID_STATE_TRANSITION");
  });

  it("fails closed for duplicate operation IDs with changed bindings or digest", async () => {
    const store = new MemoryExecutionLedger();
    await store.register({ record, registrationReplayClaimId: "replay-d" });
    const changed = createOperationRecord({ ...record, binding: { ...binding, capability: "sms.read" } } as never);
    await expect(store.register({ record: changed, registrationReplayClaimId: "replay-d2" })).resolves.toBe("conflict");
    await expect(claimExecution(store, { operationId: binding.operationId, parametersDigest: "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", claimedAt: "2026-08-11T00:00:01.000Z" })).resolves.toEqual({ kind: "digest_conflict" });
  });
});
