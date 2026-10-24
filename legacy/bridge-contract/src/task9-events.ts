import type {
  EventAckStore,
  Task9PreReplayAuthorityResolver,
  VerifiedDeviceEvent,
} from "../../protocol/src/event-contract.js";
import type { ReplayClaimReference } from "../../protocol/src/replay-window.js";

/** Exact durable lookup identity for one authenticated device event source. */
export type Task9SourceIdentity = Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  deviceId: string;
  sourceEpoch: string;
  sourceCapability: string;
}>;

/**
 * Opaque capture authority material persisted behind the Bridge transaction
 * port. It deliberately carries no Agent/session destination.
 */
export type Task9CaptureAuthorityRecord = Task9SourceIdentity & Readonly<{
  pairingGeneration: bigint;
  authorizationEpoch: bigint;
  scopeRevisions: ReadonlyMap<string, bigint>;
}>;

export type Task9ServerDestination = Readonly<{
  agentPrincipalId: string;
  agentInstanceId: string;
  workspaceId: string;
  sessionId: string;
  jobId: string | null;
}>;

/** A server-authenticated subscription; device wire values never construct it. */
export type Task9ServerSubscription = Task9SourceIdentity & Readonly<{
  subscriptionId: string;
  destination: Task9ServerDestination;
}>;

export type Task9CursorState = Readonly<{
  highestContiguousCursor: bigint;
  bufferedCursors: readonly bigint[];
}>;

export type Task9PendingAck = Task9SourceIdentity & Readonly<{
  highestContiguousCursor: bigint;
}>;

export type Task9PendingRoute = Readonly<{
  subscriptionId: string;
  cursor: bigint;
  occurrenceId: string;
  eventKind: "upsert" | "delete_tombstone" | "loss_marker";
  destination: Task9ServerDestination;
}>;

export type Task9IngestResult = Task9CursorState & Readonly<{
  kind: "buffered" | "committed" | "duplicate";
  routed: readonly Readonly<{ subscriptionId: string; cursor: bigint }>[];
}>;

/**
 * Network/database-neutral Task 9 durability port. Implementations must make
 * every ingest result one atomic DurableBridgeStore transaction.
 */
export interface DurableTask9EventPathPort extends Task9PreReplayAuthorityResolver, EventAckStore {
  putCaptureAuthority(authority: Task9CaptureAuthorityRecord): Promise<void>;
  putServerSubscription(subscription: Task9ServerSubscription): Promise<void>;
  ingestEvent(
    event: VerifiedDeviceEvent,
    replay: ReplayClaimReference<"device_event">,
  ): Promise<Task9IngestResult>;
  loadCursorState(source: Task9SourceIdentity): Promise<Task9CursorState | null>;
  recoverPendingAcks(): Promise<readonly Task9PendingAck[]>;
  markAckSent(source: Task9SourceIdentity, highestContiguousCursor: bigint): Promise<void>;
  recoverPendingRoutes(subscriptionId: string): Promise<readonly Task9PendingRoute[]>;
}
