/// <reference types="node" />

import { createHash } from "node:crypto";
import canonicalize from "canonicalize";
import {
  CRYPTO_PROFILE,
  parseSignatureDomain,
  type SignatureDomain,
} from "./profile.js";

export type { SignatureDomain } from "./profile.js";

const utf8Encoder = new TextEncoder();
const strictUtf8Decoder = new TextDecoder("utf-8", { fatal: true });

const hasLoneSurrogate = (value: string): boolean => {
  for (let index = 0; index < value.length; index += 1) {
    const codeUnit = value.charCodeAt(index);
    if (codeUnit >= 0xd800 && codeUnit <= 0xdbff) {
      if (index + 1 >= value.length) return true;
      const next = value.charCodeAt(index + 1);
      if (next < 0xdc00 || next > 0xdfff) return true;
      index += 1;
    } else if (codeUnit >= 0xdc00 && codeUnit <= 0xdfff) {
      return true;
    }
  }
  return false;
};

const assertJsonValue = (value: unknown, seen: Set<object>): void => {
  if (value === null || typeof value === "boolean") return;
  if (typeof value === "string") {
    if (hasLoneSurrogate(value)) throw new Error("NON_CANONICAL_JSON");
    return;
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value) || Object.is(value, -0)) throw new Error("NON_CANONICAL_JSON");
    return;
  }
  if (typeof value !== "object") throw new Error("NON_JSON_VALUE");
  if (seen.has(value)) throw new Error("NON_JSON_VALUE");

  seen.add(value);
  if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index += 1) {
      if (!(index in value)) throw new Error("NON_JSON_VALUE");
      assertJsonValue(value[index], seen);
    }
  } else {
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) throw new Error("NON_JSON_VALUE");
    for (const [key, member] of Object.entries(value)) {
      if (hasLoneSurrogate(key)) throw new Error("NON_CANONICAL_JSON");
      assertJsonValue(member, seen);
    }
  }
  seen.delete(value);
};

export function canonicalBytes(value: unknown): Uint8Array {
  assertJsonValue(value, new Set());
  const encoded = canonicalize(value);
  if (encoded === undefined) throw new Error("NON_JSON_VALUE");
  return utf8Encoder.encode(encoded);
}

const equalBytes = (left: Uint8Array, right: Uint8Array): boolean => {
  if (left.byteLength !== right.byteLength) return false;
  for (let index = 0; index < left.byteLength; index += 1) {
    if (left[index] !== right[index]) return false;
  }
  return true;
};

export function parseCanonicalJson(raw: Uint8Array): unknown {
  if (raw.byteLength > CRYPTO_PROFILE.maxEnvelopeBytes) throw new Error("MESSAGE_TOO_LARGE");

  let text: string;
  try {
    text = strictUtf8Decoder.decode(raw);
  } catch {
    throw new Error("INVALID_UTF8");
  }

  let value: unknown;
  try {
    value = JSON.parse(text) as unknown;
  } catch {
    throw new Error("INVALID_JSON");
  }

  let canonical: Uint8Array;
  try {
    canonical = canonicalBytes(value);
  } catch (error) {
    if (error instanceof Error && error.message === "NON_CANONICAL_JSON") throw error;
    throw new Error("NON_CANONICAL_JSON");
  }
  if (!equalBytes(raw, canonical)) throw new Error("NON_CANONICAL_JSON");
  return value;
}

export function sha256B64Url(bytes: Uint8Array): string {
  return createHash("sha256").update(bytes).digest("base64url");
}

export function signingPreimage(domain: SignatureDomain, value: unknown): Uint8Array {
  parseSignatureDomain(domain);
  const prefix = utf8Encoder.encode(`open-android-intelligence/v1/${domain}\0`);
  const canonical = canonicalBytes(value);
  const length = new Uint8Array(4);
  new DataView(length.buffer).setUint32(0, canonical.byteLength, false);
  const preimage = new Uint8Array(prefix.byteLength + length.byteLength + canonical.byteLength);
  preimage.set(prefix, 0);
  preimage.set(length, prefix.byteLength);
  preimage.set(canonical, prefix.byteLength + length.byteLength);
  return preimage;
}
