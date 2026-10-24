import { describe, expect, it } from "vitest";
import {
  canonicalBytes,
  parseCanonicalJson,
  sha256B64Url,
  signingPreimage,
} from "../src/encoding.js";
import { parseSignatureDomain, type SignatureDomain } from "../src/profile.js";

const utf8 = new TextEncoder();
const controlDomain = parseSignatureDomain("control/app-to-bridge");

const compileTimeSignatureDomainEvidence = (): SignatureDomain => {
  // @ts-expect-error Arbitrary strings must not be assignable to the closed domain type.
  const arbitraryDomain: SignatureDomain = "unknown/domain";
  return arbitraryDomain;
};
void compileTimeSignatureDomainEvidence;

describe("canonical JSON wire encoding", () => {
  it("canonicalizes values to RFC 8785 UTF-8 bytes", () => {
    expect(new TextDecoder().decode(canonicalBytes({ b: 2, a: 1 }))).toBe('{"a":1,"b":2}');
    expect(parseCanonicalJson(utf8.encode('{"a":1,"b":2}'))).toEqual({ a: 1, b: 2 });
  });

  it("rejects valid but non-canonical JSON bytes", () => {
    expect(() => parseCanonicalJson(utf8.encode('{ "b":2,"a":1}')))
      .toThrowError("NON_CANONICAL_JSON");
  });

  it.each([
    ['{"a":1,"a":1}', "duplicate object keys"],
    ["-0", "negative zero"],
    ['"\\ud800"', "a lone high surrogate"],
    ['"\\udc00"', "a lone low surrogate"],
  ])("rejects %s as non-canonical JSON (%s)", (raw) => {
    expect(() => parseCanonicalJson(utf8.encode(raw))).toThrowError("NON_CANONICAL_JSON");
  });

  it("rejects malformed UTF-8 before JSON parsing", () => {
    expect(() => parseCanonicalJson(Uint8Array.of(0xc3, 0x28))).toThrowError("INVALID_UTF8");
  });

  it.each([
    ["UTF-8 BOM", Uint8Array.of(0xef, 0xbb, 0xbf, ...utf8.encode("{}"))],
    ["trailing newline", utf8.encode("{}\n")],
  ])("rejects canonical JSON content wrapped with %s", (_label, raw) => {
    expect(() => parseCanonicalJson(raw)).toThrowError("NON_CANONICAL_JSON");
  });

  it("accepts exactly 262,144 canonical bytes", () => {
    const raw = utf8.encode(`"${"a".repeat(262_142)}"`);
    expect(raw.byteLength).toBe(262_144);
    expect(parseCanonicalJson(raw)).toBe("a".repeat(262_142));
  });

  it("rejects 262,145 bytes even when they are syntactically valid canonical JSON", () => {
    const raw = utf8.encode(`"${"a".repeat(262_143)}"`);
    expect(raw.byteLength).toBe(262_145);
    expect(() => parseCanonicalJson(raw)).toThrowError("MESSAGE_TOO_LARGE");
  });

  it("hashes the exact supplied bytes as unpadded base64url", () => {
    expect(sha256B64Url(utf8.encode("abc"))).toBe("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0");
  });

  it("constructs the frozen domain and big-endian length preimage", () => {
    const value = { sequence: "1", message_id: "018f4f9a-4444-4444-8444-444444444444" };
    const preimage = signingPreimage(controlDomain, value);
    const prefix = utf8.encode("open-android-intelligence/v1/control/app-to-bridge\0");

    expect(preimage.slice(0, prefix.byteLength)).toEqual(prefix);
    expect(Array.from(preimage.slice(prefix.byteLength, prefix.byteLength + 4))).toEqual([0, 0, 0, 68]);
    expect(new TextDecoder().decode(preimage.slice(prefix.byteLength + 4))).toBe(
      '{"message_id":"018f4f9a-4444-4444-8444-444444444444","sequence":"1"}',
    );
  });

  it("parses only domains in the validated profile", () => {
    expect(parseSignatureDomain("control/app-to-bridge")).toBe("control/app-to-bridge");
    expect(() => parseSignatureDomain("unknown/domain")).toThrowError("UNKNOWN_SIGNATURE_DOMAIN");
  });

  it("defends the preimage boundary against an unknown runtime cast", () => {
    expect(() => signingPreimage("unknown/domain" as SignatureDomain, {}))
      .toThrowError("UNKNOWN_SIGNATURE_DOMAIN");
  });
});
