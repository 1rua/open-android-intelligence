import { generateKeyPairSync, sign } from "node:crypto";
import { chmod, mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { PairingTicket } from "../../bridge-contract/src/pairing-service.js";
import { openLocalPairingTicketVerifier } from "../src/local-pairing-ticket-verifier.js";

const root = await mkdtemp(join(tmpdir(), "open-android-intelligence-pairing-verifier-"));
const secretDir = join(root, "secrets");
const publicPath = join(secretDir, "pairing-ticket-public.pem");
const { publicKey, privateKey } = generateKeyPairSync("ed25519");
const ticket: PairingTicket = {
  ticketId: "ticket-a",
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  bridgeFingerprint: "sha256:bridge-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 4n,
  issuedAtMs: 1_000,
  expiresAtMs: 10_000,
};
await mkdir(secretDir, { recursive: true });
await writeFile(publicPath, publicKey.export({ format: "pem", type: "spki" }).toString(), { mode: 0o444 });
const verifier = await openLocalPairingTicketVerifier({ publicPath, clock: () => 2_000 });

const canonical = (value: PairingTicket): string => JSON.stringify({
  ticketId: value.ticketId,
  tenantId: value.tenantId,
  humanPrincipalId: value.humanPrincipalId,
  deviceId: value.deviceId,
  bridgeFingerprint: value.bridgeFingerprint,
  pairingGeneration: value.pairingGeneration.toString(10),
  policyAttestationRevision: value.policyAttestationRevision.toString(10),
  issuedAtMs: value.issuedAtMs,
  expiresAtMs: value.expiresAtMs,
});
const envelope = (value: PairingTicket): { envelope: string; keyId: string; payload: string; signature: string } => {
  const payload = Buffer.from(canonical(value), "utf8").toString("base64url");
  return {
    envelope: "open-android-intelligence.pairing-ticket/v1",
    keyId: verifier.keyId,
    payload,
    signature: sign(null, Buffer.from(`open-android-intelligence.pairing-ticket/v1\n${verifier.keyId}\n${payload}`, "utf8"), privateKey).toString("base64url"),
  };
};

afterAll(async () => {
  await rm(root, { recursive: true, force: true });
});

describe("local Ed25519 pairing verifier", () => {
  it("accepts a signed ticket and rejects tampering or expiry", async () => {
    await expect(verifier.verify(envelope(ticket))).resolves.toEqual(ticket);
    const tampered = envelope({ ...ticket, deviceId: "device-b" });
    await expect(verifier.verify({ ...tampered, signature: envelope(ticket).signature }))
      .rejects.toMatchObject({ code: "PAIRING_TICKET_TAMPERED" });
  });

  it("fails closed on an unsafe secret path", async () => {
    const linkPath = join(root, "linked-public.pem");
    await symlink(publicPath, linkPath);
    await expect(openLocalPairingTicketVerifier({ publicPath: linkPath, clock: () => 2_000 }))
      .rejects.toMatchObject({ code: "SECRET_STORE_PATH_INVALID" });
    await chmod(publicPath, 0o644);
    await expect(openLocalPairingTicketVerifier({ publicPath, clock: () => 2_000 }))
      .rejects.toMatchObject({ code: "SECRET_STORE_PERMISSION_INVALID" });
    await chmod(publicPath, 0o444);
  });
});
