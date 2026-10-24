/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { isLowS, isValidP256PublicJwk, signTestOnly, verifyEs256 } from "../src/crypto.js";
import { signingPreimage } from "../src/encoding.js";
import { parseSignatureDomain } from "../src/profile.js";

type TestJwk = JsonWebKey & { kid: string };
type KeyRole = "device" | "bridge-command" | "adapter";
type KeyGeneration = "current" | "next";
type KeyringEntry = {
  role: KeyRole;
  generation: KeyGeneration;
  private_jwk: string;
  public_jwk: string;
};
type TestKeyring = {
  schema_version: "1";
  keys: Record<string, KeyringEntry>;
};

const readJwk = (name: string): TestJwk => JSON.parse(
  readFileSync(new URL(`../test-only/keys/${name}`, import.meta.url), "utf8"),
) as TestJwk;
const readKeyring = (): TestKeyring => JSON.parse(
  readFileSync(new URL("../test-only/keys/test-signer-keyring.json", import.meta.url), "utf8"),
) as TestKeyring;

const devicePrivateJwk = () => readJwk("device-a-private.jwk.json");
const devicePublicJwk = () => readJwk("device-a-public.jwk.json");
const deviceNextPublicJwk = () => readJwk("device-a-next-public.jwk.json");
const bridgePublicJwk = () => readJwk("bridge-command-public.jwk.json");
const adapterPublicJwk = () => readJwk("adapter-a-public.jwk.json");

const value = { message_id: "018f4f9a-4444-4444-8444-444444444444", sequence: "1" };
const controlDomain = parseSignatureDomain("control/app-to-bridge");
const approvalDomain = parseSignatureDomain("approval/device");
const preimage = () => signingPreimage(controlDomain, value);

const P256_N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551n;

const bytesToBigInt = (bytes: Uint8Array): bigint => BigInt(`0x${Buffer.from(bytes).toString("hex")}`);
const bigIntTo32Bytes = (valueToEncode: bigint): Uint8Array => {
  const hex = valueToEncode.toString(16).padStart(64, "0");
  return Uint8Array.from(Buffer.from(hex, "hex"));
};

describe("ES256 wire signatures", () => {
  it("exposes production-safe exact public-JWK validity without accepting private material", () => {
    expect(isValidP256PublicJwk(devicePublicJwk())).toBe(true);
    expect(isValidP256PublicJwk(devicePrivateJwk())).toBe(false);
    expect(isValidP256PublicJwk({ ...devicePublicJwk(), x: Buffer.alloc(32).toString("base64url"), y: Buffer.alloc(32).toString("base64url") })).toBe(false);
  });
  it("validates every closed keyring binding and all six current/next keypairs", () => {
    const keyring = readKeyring();
    expect(keyring).toEqual({
      schema_version: "1",
      keys: {
        "test-device-a-current": {
          role: "device", generation: "current",
          private_jwk: "device-a-private.jwk.json", public_jwk: "device-a-public.jwk.json",
        },
        "test-device-a-next": {
          role: "device", generation: "next",
          private_jwk: "device-a-next-private.jwk.json", public_jwk: "device-a-next-public.jwk.json",
        },
        "test-bridge-command-current": {
          role: "bridge-command", generation: "current",
          private_jwk: "bridge-command-private.jwk.json", public_jwk: "bridge-command-public.jwk.json",
        },
        "test-bridge-command-next": {
          role: "bridge-command", generation: "next",
          private_jwk: "bridge-command-next-private.jwk.json", public_jwk: "bridge-command-next-public.jwk.json",
        },
        "test-adapter-a-current": {
          role: "adapter", generation: "current",
          private_jwk: "adapter-a-private.jwk.json", public_jwk: "adapter-a-public.jwk.json",
        },
        "test-adapter-a-next": {
          role: "adapter", generation: "next",
          private_jwk: "adapter-a-next-private.jwk.json", public_jwk: "adapter-a-next-public.jwk.json",
        },
      },
    });

    const pairs = Object.entries(keyring.keys).map(([kid, binding]) => ({
      kid,
      binding,
      privateJwk: readJwk(binding.private_jwk),
      publicJwk: readJwk(binding.public_jwk),
    }));

    for (const pair of pairs) {
      expect(pair.privateJwk.kid).toBe(pair.kid);
      expect(pair.publicJwk.kid).toBe(pair.kid);
      const signature = signTestOnly(pair.privateJwk, preimage());
      expect(isLowS(signature)).toBe(true);
      expect(verifyEs256(pair.publicJwk, preimage(), signature)).toBe(true);

      for (const substituted of pairs) {
        if (substituted.kid === pair.kid) continue;
        if (substituted.binding.role !== pair.binding.role
          || substituted.binding.generation !== pair.binding.generation) {
          expect(verifyEs256(substituted.publicJwk, preimage(), signature)).toBe(false);
        }
      }
    }
  });

  it("domain-separates and verifies a deterministic low-S signature", () => {
    const signature = signTestOnly(devicePrivateJwk(), preimage());

    expect(signature).toMatch(/^[A-Za-z0-9_-]{86}$/);
    expect(signTestOnly(devicePrivateJwk(), preimage())).toBe(signature);
    expect(verifyEs256(devicePublicJwk(), preimage(), signature)).toBe(true);
    expect(verifyEs256(
      devicePublicJwk(),
      signingPreimage(approvalDomain, value),
      signature,
    )).toBe(false);
    expect(isLowS(signature)).toBe(true);
  });

  it("rejects a one-byte payload mutation", () => {
    const signature = signTestOnly(devicePrivateJwk(), preimage());
    const mutated = signingPreimage(controlDomain, { ...value, sequence: "2" });
    expect(verifyEs256(devicePublicJwk(), mutated, signature)).toBe(false);
  });

  it("rejects the corresponding high-S signature", () => {
    const low = Uint8Array.from(Buffer.from(signTestOnly(devicePrivateJwk(), preimage()), "base64url"));
    const highS = P256_N - bytesToBigInt(low.slice(32));
    const high = Uint8Array.from([...low.slice(0, 32), ...bigIntTo32Bytes(highS)]);
    const encoded = Buffer.from(high).toString("base64url");

    expect(isLowS(encoded)).toBe(false);
    expect(verifyEs256(devicePublicJwk(), preimage(), encoded)).toBe(false);
  });

  it.each([
    ["not+base64url", "invalid alphabet"],
    [`${"A".repeat(86)}=`, "padding"],
    [Buffer.alloc(63).toString("base64url"), "wrong decoded length"],
    [Buffer.alloc(64).toString("base64url"), "out-of-range zero components"],
  ])("rejects malformed P1363 signatures: %s (%s)", (signature) => {
    expect(isLowS(signature)).toBe(false);
    expect(verifyEs256(devicePublicJwk(), preimage(), signature)).toBe(false);
  });

  it("rejects malformed, wrong-curve and private verifier JWKs", () => {
    const signature = signTestOnly(devicePrivateJwk(), preimage());
    expect(verifyEs256({ ...devicePublicJwk(), crv: "secp256k1" }, preimage(), signature)).toBe(false);
    expect(verifyEs256({ ...devicePublicJwk(), x: "AA" }, preimage(), signature)).toBe(false);
    expect(verifyEs256(devicePrivateJwk(), preimage(), signature)).toBe(false);
  });

  it("rejects incomplete and wrong-curve private test JWKs", () => {
    const { d: _d, ...withoutD } = devicePrivateJwk();
    expect(() => signTestOnly(withoutD, preimage())).toThrowError("INVALID_PRIVATE_JWK");
    expect(() => signTestOnly({ ...devicePrivateJwk(), crv: "secp256k1" }, preimage()))
      .toThrowError("INVALID_PRIVATE_JWK");
  });

  it("does not substitute next-generation or cross-role keys", () => {
    const signature = signTestOnly(devicePrivateJwk(), preimage());
    expect(verifyEs256(deviceNextPublicJwk(), preimage(), signature)).toBe(false);
    expect(verifyEs256(bridgePublicJwk(), preimage(), signature)).toBe(false);
    expect(verifyEs256(adapterPublicJwk(), preimage(), signature)).toBe(false);
  });
});
