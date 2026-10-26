/**
 * Shared value objects for the WP-06 contract service.
 *
 * This package is intentionally an in-memory adapter seam. It does not open
 * sockets, perform Tailscale dialing, or claim durable database semantics.
 */

export type BridgeIdentity = Readonly<{
  tenantId: string;
  humanPrincipalId: string;
  deviceId: string;
}>;

export type BridgeSessionIdentity = BridgeIdentity & Readonly<{
  agentInstanceId: string;
  workspaceId: string;
  sessionId: string;
  jobId?: string;
  pairingGeneration: bigint;
  policyAttestationRevision: bigint;
}>;

export type NotificationField = "metadata" | "content";

export type NotificationFilter = Readonly<{
  packages?: readonly string[];
  fields?: readonly NotificationField[];
}>;

export type NotificationRecordKind = "upsert" | "delete_tombstone" | "loss_marker";

export type NotificationRecordV1 = Readonly<{
  kind: NotificationRecordKind;
  recordId: string;
  packageId: string | null;
  title: string | null;
  content: string | null;
  sourceEpoch: bigint;
  cursor: bigint;
  captureRevision: bigint;
}>;

export type NotificationEventV1 = Readonly<NotificationRecordV1 & {
  eventId: string;
  subscriptionId: string;
  binding: BridgeSessionIdentity;
}>;

export type SmsRecordV1 = Readonly<{
  recordId: string;
  senderAddress: string | null;
  threadId: string | null;
  messageAtEpochMs: bigint;
  observedAtEpochMs: bigint;
  read: boolean;
  subscriptionId: number | null;
  body: string;
  sourceEpoch: bigint;
  cursorProviderId: bigint;
  captureRevision: bigint;
  policyRevision: bigint;
}>;

export type SmsEventV1 = Readonly<{
  eventId: string;
  subscriptionId: string;
  binding: BridgeSessionIdentity;
  record: SmsRecordV1;
}>;

export type CapabilityName =
  | "mobile.notifications.query"
  | "mobile.notifications.subscribe"
  | "mobile.notifications.unsubscribe"
  | "mobile.sms.query"
  | "mobile.sms.subscribe"
  | "mobile.sms.unsubscribe"
  | "assistant.chat";

export type AuthorizationRequest = Readonly<{
  capability: CapabilityName;
  session: BridgeSessionIdentity;
  policyRevision: bigint;
  filter?: NotificationFilter;
}>;

export type AuthorizationDecision = Readonly<{
  allowed: boolean;
  policyRevision: bigint;
  reason?: string;
}>;

export type Authorize = (request: AuthorizationRequest) => AuthorizationDecision | Promise<AuthorizationDecision>;

export class BridgeServiceError extends Error {
  readonly code: string;

  constructor(code: string) {
    super(code);
    this.name = "BridgeServiceError";
    this.code = code;
  }
}

export const freezeRecord = <T extends object>(value: T): Readonly<T> => Object.freeze({ ...value });

export const identityKey = (identity: BridgeIdentity): string =>
  [identity.tenantId, identity.humanPrincipalId, identity.deviceId].join("\u0000");

export const sessionKey = (identity: BridgeSessionIdentity): string =>
  [identityKey(identity), identity.agentInstanceId, identity.workspaceId, identity.sessionId, identity.jobId ?? "", identity.pairingGeneration, identity.policyAttestationRevision].join("\u0000");

export const equalIdentity = (left: BridgeSessionIdentity, right: BridgeSessionIdentity): boolean =>
  sessionKey(left) === sessionKey(right);

export function assertNonEmpty(value: unknown, code: string): asserts value is string {
  if (typeof value !== "string" || value.length === 0) throw new BridgeServiceError(code);
}

export const compareCodePoints = (left: string, right: string): number => {
  const a = [...left].map((value) => value.codePointAt(0) ?? 0);
  const b = [...right].map((value) => value.codePointAt(0) ?? 0);
  for (let index = 0; index < Math.min(a.length, b.length); index += 1) {
    if (a[index] !== b[index]) return a[index]! - b[index]!;
  }
  return a.length - b.length;
};

export const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
