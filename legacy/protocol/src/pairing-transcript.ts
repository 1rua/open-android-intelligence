/// <reference types="node" />

import { createHash } from "node:crypto";
import { canonicalBytes } from "./encoding.js";

export interface PairingTranscript {
  readonly ticket_digest: string;
  readonly bridge_fingerprint: string;
  readonly challenge: string;
  readonly client_nonce: string;
  readonly bridge_nonce: string;
  readonly device_jwk_thumbprint: string;
  readonly selected_protocol: string;
}

const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const encoder = new TextEncoder();

export function pairingShortCode(transcript: PairingTranscript): string {
  const canonical = canonicalBytes(transcript);
  const prefix = encoder.encode("open-android-intelligence/v1/pairing-short-code\0");
  const length = new Uint8Array(4);
  new DataView(length.buffer).setUint32(0, canonical.byteLength, false);
  const digest = createHash("sha256").update(prefix).update(length).update(canonical).digest();
  let value = BigInt(`0x${digest.subarray(0, 7).toString("hex")}`) >> 6n;
  let code = "";
  for (let index = 0; index < 10; index += 1) {
    code = `${CROCKFORD[Number(value & 31n)]}${code}`;
    value >>= 5n;
  }
  return `${code.slice(0, 5)}-${code.slice(5)}`;
}
