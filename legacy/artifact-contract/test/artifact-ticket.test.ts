import { describe, expect, it } from "vitest";
import {
  ARTIFACT_LIMITS,
  ArtifactContractError,
  issueArtifactTicket,
  interruptArtifactTicket,
  reclaimOrphanTicket,
  commitArtifactMessage,
  verifyArtifactProof,
} from "../src/artifact-ticket.js";

const MiB = 1024 * 1024;
const digest = "a".repeat(64);
const ticketInput = (overrides: Record<string, unknown> = {}) => ({
  selection: { source: "photo_picker", selectionId: "picker-item-0001" },
  mediaType: "image/jpeg",
  byteSize: MiB,
  sha256: digest,
  ...overrides,
});
const ticketIds = (...ids: string[]) => {
  let index = 0;
  return () => ids[index++] ?? `ticket-${index}`;
};

describe("M1.1 source-only artifact ticket contract", () => {
  it("issues a ticket only after a SHA-256 digest is supplied for a Photo Picker or SAF selection", () => {
    const ticket = issueArtifactTicket(ticketInput(), 1_000, ticketIds("ticket-a"));

    expect(ticket).toMatchObject({
      ticketId: "ticket-a",
      status: "issued",
      sha256: digest,
      selection: { source: "photo_picker", selectionId: "picker-item-0001" },
    });
    expect(() => issueArtifactTicket(ticketInput({ sha256: "" }), 1_000, ticketIds("ticket-b")))
      .toThrowError(new ArtifactContractError("DIGEST_REQUIRED"));
  });

  it("accepts only the closed media-type set and the 25 MiB single-artifact limit", () => {
    expect(() => issueArtifactTicket(ticketInput({ mediaType: "image/gif" }), 1_000, ticketIds("ticket-a")))
      .toThrowError(new ArtifactContractError("MEDIA_TYPE_NOT_ALLOWED"));
    expect(() => issueArtifactTicket(ticketInput({ byteSize: ARTIFACT_LIMITS.maxSingleBytes + 1 }), 1_000, ticketIds("ticket-a")))
      .toThrowError(new ArtifactContractError("ARTIFACT_TOO_LARGE"));
  });

  it("accepts audio only within the 10 MiB and 120 second limits", () => {
    const ticket = issueArtifactTicket(ticketInput({
      mediaType: "audio/mp4", byteSize: 10485760, durationMs: 120000,
    }), 1000, ticketIds("audio-ticket"));
    expect(ticket).toMatchObject({ mediaType: "audio/mp4", byteSize: 10485760, durationMs: 120000 });
    expect(() => issueArtifactTicket(ticketInput({ mediaType: "audio/mp4", byteSize: 10485761, durationMs: 1 }), 1000, ticketIds("too-large")))
      .toThrowError(new ArtifactContractError("ARTIFACT_TOO_LARGE"));
    expect(() => issueArtifactTicket(ticketInput({ mediaType: "audio/mp4", byteSize: 1, durationMs: 120001 }), 1000, ticketIds("too-long")))
      .toThrowError(new ArtifactContractError("AUDIO_DURATION_INVALID"));
  });

  it("requires audio duration and rejects duration metadata on non-audio artifacts", () => {
    expect(() => issueArtifactTicket(ticketInput({ mediaType: "audio/mp4" }), 1000, ticketIds("missing-duration")))
      .toThrowError(new ArtifactContractError("AUDIO_DURATION_INVALID"));
    expect(() => issueArtifactTicket(ticketInput({ mediaType: "image/png", durationMs: 1000 }), 1000, ticketIds("image-duration")))
      .toThrowError(new ArtifactContractError("AUDIO_DURATION_INVALID"));
  });

  it("mints an opaque artifact id only on message commit", async () => {
    const issued = issueArtifactTicket(ticketInput({ mediaType: "audio/mp4", durationMs: 25 }), 1000, ticketIds("ticket-a"));
    let verifierInput: { ticket: typeof issued; proof: unknown } | undefined;
    const verified = await verifyArtifactProof(issued, { ticketId: "ticket-a", sha256: digest, proof: "p".repeat(32) }, {
      verify: async (input) => {
        verifierInput = input;
        return "verified";
      },
    });
    expect(verifierInput?.ticket).toMatchObject({ mediaType: "audio/mp4", durationMs: 25 });
    expect(verified.artifactId).toBeUndefined();
    const receipt = commitArtifactMessage("message-a", [verified], 1500);
    expect(receipt.tickets[0]).toMatchObject({ status: "message_committed", artifactId: "ticket-a", durationMs: 25 });
  });

  it("strips forged artifact IDs from every non-committed ticket transition", async () => {
    const issued = issueArtifactTicket(ticketInput(), 1000, ticketIds("ticket-a"));
    const forgedIssued = { ...issued, artifactId: "forged-before-commit" };
    const verified = await verifyArtifactProof(forgedIssued, {
      ticketId: "ticket-a", sha256: digest, proof: "p".repeat(32),
    }, { verify: async () => "verified" });
    expect(verified.artifactId).toBeUndefined();

    const forgedVerified = { ...verified, artifactId: "forged-before-interrupt" };
    expect(interruptArtifactTicket(forgedVerified).artifactId).toBeUndefined();
    expect(reclaimOrphanTicket({ ...issued, artifactId: "forged-before-reclaim" }, 1000 + ARTIFACT_LIMITS.orphanReclaimAfterMs - 1).artifactId)
      .toBeUndefined();
    expect(reclaimOrphanTicket({ ...issued, artifactId: "forged-before-reclaim" }, 1000 + ARTIFACT_LIMITS.orphanReclaimAfterMs).artifactId)
      .toBeUndefined();
  });

  it("rejects arbitrary paths, URLs, and unrecognized keys instead of treating them as an artifact selection", () => {
    expect(() => issueArtifactTicket(ticketInput({ path: "/sdcard/DCIM/a.jpg" }), 1_000, ticketIds("ticket-a")))
      .toThrowError(new ArtifactContractError("UNSAFE_LOCATION_INPUT"));
    expect(() => issueArtifactTicket(ticketInput({ url: "https://example.invalid/a.jpg" }), 1_000, ticketIds("ticket-a")))
      .toThrowError(new ArtifactContractError("UNSAFE_LOCATION_INPUT"));
    expect(() => issueArtifactTicket(ticketInput({ selection: { source: "photo_picker", selectionId: "picker-item-0001", uri: "content://x" } }), 1_000, ticketIds("ticket-a")))
      .toThrowError(new ArtifactContractError("UNSAFE_LOCATION_INPUT"));
  });

  it("requires a verifier-confirmed proof of possession bound to the exact ticket and digest", async () => {
    const ticket = issueArtifactTicket(ticketInput(), 1_000, ticketIds("ticket-a"));
    const verified = await verifyArtifactProof(ticket, {
      ticketId: "ticket-a",
      sha256: digest,
      proof: "p".repeat(32),
    }, { verify: async () => "verified" });

    expect(verified.status).toBe("proof_verified");
    await expect(verifyArtifactProof(ticket, {
      ticketId: "other-ticket",
      sha256: digest,
      proof: "p".repeat(32),
    }, { verify: async () => "verified" })).rejects.toThrowError(new ArtifactContractError("PROOF_TICKET_MISMATCH"));
  });

  it("requires a new ticket after an interrupted upload and never permits the interrupted ticket to commit", async () => {
    const issued = issueArtifactTicket(ticketInput(), 1_000, ticketIds("ticket-a", "ticket-b"));
    const verified = await verifyArtifactProof(issued, {
      ticketId: "ticket-a", sha256: digest, proof: "p".repeat(32),
    }, { verify: async () => "verified" });
    const interrupted = interruptArtifactTicket(verified);

    expect(() => commitArtifactMessage("message-a", [interrupted], 1_500))
      .toThrowError(new ArtifactContractError("NEW_TICKET_REQUIRED"));
    const replacement = issueArtifactTicket(ticketInput(), 1_500, ticketIds("ticket-b"));
    expect(replacement.ticketId).toBe("ticket-b");
  });

  it("allows local-copy deletion only after a bounded message commit", async () => {
    const issued = issueArtifactTicket(ticketInput(), 1_000, ticketIds("ticket-a"));
    const verified = await verifyArtifactProof(issued, {
      ticketId: "ticket-a", sha256: digest, proof: "p".repeat(32),
    }, { verify: async () => "verified" });
    const receipt = commitArtifactMessage("message-a", [verified], 1_500);

    expect(receipt.tickets[0]).toMatchObject({ status: "message_committed", localCopyDeletionAllowed: true });
    expect(() => commitArtifactMessage("message-b", Array.from({ length: 5 }, () => verified), 1_500))
      .toThrowError(new ArtifactContractError("MESSAGE_ARTIFACT_COUNT_EXCEEDED"));
  });

  it("rejects a message whose verified attachments exceed the 50 MiB aggregate limit", async () => {
    const issued = issueArtifactTicket(ticketInput({ byteSize: 25 * MiB }), 1_000, ticketIds("ticket-a"));
    const verified = await verifyArtifactProof(issued, {
      ticketId: "ticket-a", sha256: digest, proof: "p".repeat(32),
    }, { verify: async () => "verified" });
    const issuedSecond = issueArtifactTicket(ticketInput({ byteSize: 25 * MiB }), 1_000, ticketIds("ticket-b"));
    const second = await verifyArtifactProof(issuedSecond, {
      ticketId: "ticket-b", sha256: digest, proof: "q".repeat(32),
    }, { verify: async () => "verified" });
    const issuedThird = issueArtifactTicket(ticketInput({ byteSize: 1 }), 1_000, ticketIds("ticket-c"));
    const third = await verifyArtifactProof(issuedThird, {
      ticketId: "ticket-c", sha256: digest, proof: "r".repeat(32),
    }, { verify: async () => "verified" });

    expect(() => commitArtifactMessage("message-a", [verified, second, third], 1_500))
      .toThrowError(new ArtifactContractError("MESSAGE_ARTIFACT_BYTES_EXCEEDED"));
  });

  it("rejects a copied proof status that is not minted by the verifier", async () => {
    const issued = issueArtifactTicket(ticketInput(), 1_000, ticketIds("ticket-a"));
    const verified = await verifyArtifactProof(issued, {
      ticketId: "ticket-a", sha256: digest, proof: "p".repeat(32),
    }, { verify: async () => "verified" });
    const forged = { ...verified, status: "proof_verified" as const };

    expect(() => commitArtifactMessage("message-a", [forged], 1_500))
      .toThrowError(new ArtifactContractError("PROOF_NOT_VERIFIED"));
  });

  it("reclaims uncommitted tickets after 24 hours but preserves committed tickets", async () => {
    const issued = issueArtifactTicket(ticketInput(), 1_000, ticketIds("ticket-a"));
    expect(reclaimOrphanTicket(issued, 1_000 + ARTIFACT_LIMITS.orphanReclaimAfterMs - 1).status).toBe("issued");
    expect(reclaimOrphanTicket(issued, 1_000 + ARTIFACT_LIMITS.orphanReclaimAfterMs).status).toBe("orphan_reclaimed");

    const verified = await verifyArtifactProof(issued, {
      ticketId: "ticket-a", sha256: digest, proof: "p".repeat(32),
    }, { verify: async () => "verified" });
    const committed = commitArtifactMessage("message-a", [verified], 1_500).tickets[0];
    expect(reclaimOrphanTicket(committed, 1_000 + ARTIFACT_LIMITS.orphanReclaimAfterMs + 1).status)
      .toBe("message_committed");
  });

  it("preserves an unexpired proof-verified ticket's proof brand for commit", async () => {
    const issued = issueArtifactTicket(ticketInput(), 1_000, ticketIds("ticket-a"));
    const verified = await verifyArtifactProof(issued, {
      ticketId: "ticket-a", sha256: digest, proof: "p".repeat(32),
    }, { verify: async () => "verified" });
    const retained = reclaimOrphanTicket(verified, 1_000 + ARTIFACT_LIMITS.orphanReclaimAfterMs - 1);

    expect(retained.status).toBe("proof_verified");
    expect(() => commitArtifactMessage("message-a", [retained], 1_500)).not.toThrow();
  });

  it("strips a forged artifact ID from an unexpired proof-verified ticket", async () => {
    const issued = issueArtifactTicket(ticketInput(), 1_000, ticketIds("ticket-a"));
    const verified = await verifyArtifactProof(issued, {
      ticketId: "ticket-a", sha256: digest, proof: "p".repeat(32),
    }, { verify: async () => "verified" });
    const forged = { ...verified, artifactId: "forged-before-commit" };

    const reclaimed = reclaimOrphanTicket(forged, 1_000 + ARTIFACT_LIMITS.orphanReclaimAfterMs - 1);

    expect(reclaimed).toMatchObject({ ticketId: "ticket-a", status: "proof_verified" });
    expect(reclaimed.artifactId).toBeUndefined();
  });
});
