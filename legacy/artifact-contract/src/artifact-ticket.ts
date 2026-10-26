export const ARTIFACT_LIMITS = Object.freeze({
  maxFiles: 4,
  maxSingleBytes: 25 * 1024 * 1024,
  maxAudioBytes: 10 * 1024 * 1024,
  maxAudioDurationMs: 120000,
  maxMessageBytes: 50 * 1024 * 1024,
  orphanReclaimAfterMs: 24 * 60 * 60 * 1000,
} as const);

const MEDIA_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
  "application/pdf",
  "text/plain",
  "audio/mp4",
]);
const SHA256 = /^[A-Fa-f0-9]{64}$/;
const ID = /^[A-Za-z0-9._~-]{1,128}$/;
const PROOF_BRAND = Symbol("open-android-intelligence.artifact.proof-verified");

export class ArtifactContractError extends Error {
  readonly code: string;

  constructor(code: string) {
    super(code);
    this.name = "ArtifactContractError";
    this.code = code;
  }
}

type ArtifactSelection = Readonly<{
  source: "photo_picker" | "saf";
  selectionId: string;
}>;

export type ArtifactTicketStatus = "issued" | "proof_verified" | "upload_interrupted" | "message_committed" | "orphan_reclaimed";

export type ArtifactTicket = Readonly<{
  ticketId: string;
  status: ArtifactTicketStatus;
  selection: ArtifactSelection;
  mediaType: string;
  byteSize: number;
  durationMs?: number;
  sha256: string;
  issuedAt: number;
  proofVerifiedAt?: number;
  committedAt?: number;
  localCopyDeletionAllowed?: boolean;
  artifactId?: string;
}>;

type ArtifactInput = Readonly<{
  selection: ArtifactSelection;
  mediaType: string;
  byteSize: number;
  durationMs?: number;
  sha256: string;
}>;

type ArtifactProof = Readonly<{
  ticketId: string;
  sha256: string;
  proof: string;
}>;

type ProofVerifier = Readonly<{
  verify(input: Readonly<{ ticket: ArtifactTicket; proof: ArtifactProof }>): Promise<"verified"> | "verified";
}>;

type ArtifactReceipt = Readonly<{
  messageId: string;
  tickets: readonly ArtifactTicket[];
  committedAt: number;
}>;

type BrandedProofTicket = ArtifactTicket & { readonly [PROOF_BRAND]: true };

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const exactKeys = (value: Record<string, unknown>, keys: readonly string[]): boolean => {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
};

const fail = (code: string): never => { throw new ArtifactContractError(code); };

const stripArtifactId = (ticket: ArtifactTicket): Omit<ArtifactTicket, "artifactId"> => {
  const { artifactId: _artifactId, ...withoutArtifactId } = ticket;
  return withoutArtifactId;
};

const hasGenuineProofBrand = (ticket: ArtifactTicket): ticket is BrandedProofTicket =>
  Object.prototype.hasOwnProperty.call(ticket, PROOF_BRAND)
  && (ticket as ArtifactTicket & { readonly [PROOF_BRAND]?: unknown })[PROOF_BRAND] === true;

const assertClock = (value: number): void => {
  if (!Number.isSafeInteger(value) || value < 0) fail("TIMESTAMP_INVALID");
};

const parseSelection = (value: unknown): ArtifactSelection => {
  const record = isRecord(value) ? value : fail("SELECTION_INVALID");
  if (Object.keys(record).some((key) => key === "path" || key === "url" || key === "uri")) fail("UNSAFE_LOCATION_INPUT");
  if (!exactKeys(record, ["source", "selectionId"])) fail("SELECTION_INVALID");
  const source = record.source === "photo_picker" || record.source === "saf"
    ? record.source
    : fail("SELECTION_INVALID");
  const selectionId = typeof record.selectionId === "string" && ID.test(record.selectionId)
    ? record.selectionId
    : fail("SELECTION_INVALID");
  return Object.freeze({ source, selectionId });
};

const parseInput = (value: unknown): ArtifactInput => {
  const record = isRecord(value) ? value : fail("ARTIFACT_INVALID");
  if (Object.keys(record).some((key) => key === "path" || key === "url" || key === "uri")) fail("UNSAFE_LOCATION_INPUT");
  if (!exactKeys(record, Object.prototype.hasOwnProperty.call(record, "durationMs")
    ? ["selection", "mediaType", "byteSize", "sha256", "durationMs"]
    : ["selection", "mediaType", "byteSize", "sha256"])) fail("ARTIFACT_INVALID");
  const selection = parseSelection(record.selection);
  const mediaType = typeof record.mediaType === "string" && MEDIA_TYPES.has(record.mediaType)
    ? record.mediaType
    : fail("MEDIA_TYPE_NOT_ALLOWED");
  const hasDuration = Object.prototype.hasOwnProperty.call(record, "durationMs");
  if (mediaType === "audio/mp4" && (!hasDuration || typeof record.durationMs !== "number"
    || !Number.isSafeInteger(record.durationMs) || record.durationMs < 1
    || record.durationMs > ARTIFACT_LIMITS.maxAudioDurationMs)) fail("AUDIO_DURATION_INVALID");
  if (mediaType !== "audio/mp4" && hasDuration) fail("AUDIO_DURATION_INVALID");
  const byteSize = typeof record.byteSize === "number" && Number.isSafeInteger(record.byteSize)
    && record.byteSize >= 0 && record.byteSize <= (mediaType === "audio/mp4"
      ? ARTIFACT_LIMITS.maxAudioBytes : ARTIFACT_LIMITS.maxSingleBytes)
    ? record.byteSize
    : fail("ARTIFACT_TOO_LARGE");
  const sha256 = typeof record.sha256 === "string" && SHA256.test(record.sha256)
    ? record.sha256
    : fail("DIGEST_REQUIRED");
  return Object.freeze({
    selection,
    mediaType,
    byteSize,
    ...(hasDuration ? { durationMs: record.durationMs as number } : {}),
    sha256: sha256.toLowerCase(),
  });
};

export const issueArtifactTicket = (
  input: unknown,
  nowMs: number,
  nextTicketId: () => string,
): ArtifactTicket => {
  assertClock(nowMs);
  if (typeof nextTicketId !== "function") fail("TICKET_ID_SOURCE_INVALID");
  const parsed = parseInput(input);
  const ticketId = nextTicketId();
  if (typeof ticketId !== "string" || !ID.test(ticketId)) fail("TICKET_ID_INVALID");
  return Object.freeze({ ticketId, status: "issued", ...parsed, issuedAt: nowMs });
};

export const verifyArtifactProof = async (
  ticket: ArtifactTicket,
  proof: ArtifactProof,
  verifier: ProofVerifier,
): Promise<ArtifactTicket> => {
  if (!ticket || ticket.status !== "issued") fail("TICKET_NOT_ISSUED");
  const sanitizedTicket = stripArtifactId(ticket);
  const proofRecord = isRecord(proof) ? proof : fail("PROOF_INVALID");
  if (!exactKeys(proofRecord, ["ticketId", "sha256", "proof"])) fail("PROOF_INVALID");
  if (proofRecord.ticketId !== sanitizedTicket.ticketId) fail("PROOF_TICKET_MISMATCH");
  if (typeof proofRecord.sha256 !== "string" || proofRecord.sha256.toLowerCase() !== sanitizedTicket.sha256) fail("PROOF_DIGEST_MISMATCH");
  if (typeof proofRecord.proof !== "string" || proofRecord.proof.length < 1) fail("PROOF_INVALID");
  if (!verifier || typeof verifier.verify !== "function") fail("PROOF_VERIFIER_INVALID");
  if ((await verifier.verify({ ticket: sanitizedTicket, proof: proofRecord as ArtifactProof })) !== "verified") fail("PROOF_REJECTED");
  const branded = { ...sanitizedTicket, status: "proof_verified" as const, proofVerifiedAt: sanitizedTicket.issuedAt } as ArtifactTicket & { [PROOF_BRAND]?: true };
  Object.defineProperty(branded, PROOF_BRAND, { value: true, enumerable: false, writable: false, configurable: false });
  return Object.freeze(branded) as BrandedProofTicket;
};

export const commitArtifactMessage = (
  messageId: string,
  tickets: readonly ArtifactTicket[],
  nowMs: number,
): ArtifactReceipt => {
  assertClock(nowMs);
  if (typeof messageId !== "string" || !ID.test(messageId)) fail("MESSAGE_ID_INVALID");
  if (!Array.isArray(tickets) || tickets.length === 0) fail("MESSAGE_ARTIFACT_COUNT_INVALID");
  if (tickets.length > ARTIFACT_LIMITS.maxFiles) fail("MESSAGE_ARTIFACT_COUNT_EXCEEDED");
  const ids = new Set<string>();
  let total = 0;
  const committed = tickets.map((ticket) => {
    if (!ticket || ticket.status !== "proof_verified") fail("NEW_TICKET_REQUIRED");
    if (!Object.prototype.hasOwnProperty.call(ticket, PROOF_BRAND)) fail("PROOF_NOT_VERIFIED");
    if (ids.has(ticket.ticketId)) fail("DUPLICATE_TICKET");
    ids.add(ticket.ticketId);
    if (!Number.isSafeInteger(ticket.byteSize) || ticket.byteSize < 0) fail("ARTIFACT_INVALID");
    total += ticket.byteSize;
    if (total > ARTIFACT_LIMITS.maxMessageBytes) fail("MESSAGE_ARTIFACT_BYTES_EXCEEDED");
    return Object.freeze({ ...ticket, status: "message_committed" as const, artifactId: ticket.ticketId, committedAt: nowMs, localCopyDeletionAllowed: true as const });
  });
  return Object.freeze({ messageId, tickets: Object.freeze(committed), committedAt: nowMs });
};

/** Marks a verified upload as interrupted; only a fresh ticket may commit. */
export const interruptArtifactTicket = (ticket: ArtifactTicket): ArtifactTicket => {
  if (!ticket || ticket.status !== "proof_verified") fail("TICKET_NOT_PROOF_VERIFIED");
  return Object.freeze({ ...stripArtifactId(ticket), status: "upload_interrupted" as const });
};

export const reclaimOrphanTicket = (ticket: ArtifactTicket, nowMs: number): ArtifactTicket => {
  assertClock(nowMs);
  if (ticket.status === "message_committed") return ticket;
  if (ticket.status === "orphan_reclaimed") return Object.freeze(stripArtifactId(ticket));
  if (nowMs - ticket.issuedAt < ARTIFACT_LIMITS.orphanReclaimAfterMs
    && ticket.status === "proof_verified"
    && !Object.prototype.hasOwnProperty.call(ticket, "artifactId")
    && hasGenuineProofBrand(ticket)) return ticket;
  const sanitizedTicket = stripArtifactId(ticket);
  if (nowMs - ticket.issuedAt < ARTIFACT_LIMITS.orphanReclaimAfterMs) return Object.freeze(sanitizedTicket);
  return Object.freeze({ ...sanitizedTicket, status: "orphan_reclaimed", localCopyDeletionAllowed: true });
};
