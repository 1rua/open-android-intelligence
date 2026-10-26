/// <reference types="node" />

import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { signTestOnly, verifyEs256 } from "../src/crypto.js";
import { canonicalBytes, sha256B64Url, signingPreimage } from "../src/encoding.js";
import { verifyConnectMessage, type ConnectMessageAdmissionContext, type VerifiedConnectHello, type VerifiedConnectWelcome } from "../src/message-registry.js";
import type { Clock, SignerRole, Verifier } from "../src/ports.js";
import { parseSignatureDomain } from "../src/profile.js";
import { compareProtocolVersions, loadVersionRegistry, parseProtocolVersion, selectHighestCommonVersion, verifyWelcome, type LockedVersionRegistry } from "../src/version-negotiation.js";

type TestJwk = JsonWebKey & { alg: "ES256"; crv: "P-256"; kid: string; kty: "EC"; use: "sig"; x: string; y: string };
const readJwk = (name: string): TestJwk => JSON.parse(readFileSync(new URL(`../test-only/keys/${name}`, import.meta.url), "utf8")) as TestJwk;
const devicePrivate = readJwk("device-a-private.jwk.json");
const devicePublic = readJwk("device-a-public.jwk.json");
const bridgePrivate = readJwk("bridge-command-private.jwk.json");
const bridgePublic = readJwk("bridge-command-public.jwk.json");
const bridgeNextPublic = readJwk("bridge-command-next-public.jwk.json");
const NONCE_A = Buffer.alloc(32, 1).toString("base64url");
const NONCE_B = Buffer.alloc(32, 2).toString("base64url");
const now = new Date("2026-08-08T00:01:00.000Z");
const clock: Clock = { wallNow: () => new Date(now), monotonicNowMs: () => 1n };
const verifier: Verifier = { verify: async (role: SignerRole, keyId: string, preimage: Uint8Array, signature: string) => {
  const key = role === "device" && keyId === devicePublic.kid ? devicePublic : role === "bridge-command" && keyId === bridgePublic.kid ? bridgePublic : undefined;
  return key ? verifyEs256(key, preimage, signature) : false;
} };
const context = (role: "device" | "bridge-command", key: TestJwk): ConnectMessageAdmissionContext => ({ verifier, expectedSignerRole: role, expectedKeyId: key.kid, expectedDeviceId: "device-a", expectedPairingGeneration: "3", clock });
const encode = (value: unknown) => canonicalBytes(value);

const signedConnect = (type: "connect_hello" | "connect_welcome", payload: Record<string, unknown>, key: TestJwk, privateKey: TestJwk, headerPatch: Record<string, unknown> = {}) => {
  const app = type === "connect_hello";
  const header = {
    protocol_version: "1.0", message_schema: `urn:open-android-intelligence:protocol:v1:message:${type}`, message_type: type,
    message_id: app ? "018f4f9a-4444-4444-8444-444444444444" : "018f4f9a-4555-4555-8555-555555555555",
    key_id: key.kid, direction: app ? "app-to-bridge" : "bridge-to-app", sequence: app ? "1" : "2",
    issued_at: "2026-08-08T00:00:00.000Z", expires_at: "2026-08-08T00:05:00.000Z",
    payload_digest: sha256B64Url(canonicalBytes(payload)), device_id: "device-a", pairing_generation: "3", ...headerPatch,
  };
  const unsigned = { header, payload };
  const domain = parseSignatureDomain(app ? "control/app-to-bridge" : "control/bridge-to-app");
  return encode({ ...unsigned, signature: signTestOnly(privateKey, signingPreimage(domain, unsigned)) });
};

const helloPayload = { client_nonce: NONCE_A, supported_versions: ["0.9", "1.0"], last_manifest_generation: "7", last_event_cursor: "cursor-1" };
const welcomePayload = { client_offer_digest: sha256B64Url(canonicalBytes(helloPayload)), client_nonce: NONCE_A, bridge_nonce: NONCE_B, selected_protocol: "1.0", bridge_time: "2026-08-08T00:01:00.000Z", command_key_set: { current: bridgePublic, next: null }, connection_generation: "9" };
const invalidPoint = (kid: string): TestJwk => ({ ...bridgePublic, kid, x: Buffer.alloc(32).toString("base64url"), y: Buffer.alloc(32).toString("base64url") });

describe("protocol version negotiation", () => {
  it.each(["01.0", "1.00", "+1.0", "1", "1.0.0", "18446744073709551616.0", "1.18446744073709551616"])("rejects noncanonical or exhausted version %s", (value) => {
    expect(() => parseProtocolVersion(value)).toThrowError("VERSION_UNSUPPORTED");
  });

  it("compares arbitrary-precision canonical components numerically", () => {
    expect(compareProtocolVersions(parseProtocolVersion("1.9"), parseProtocolVersion("1.10"))).toBe(-1);
    expect(compareProtocolVersions(parseProtocolVersion("2.0"), parseProtocolVersion("1.999"))).toBe(1);
    expect(compareProtocolVersions(parseProtocolVersion("1.0"), parseProtocolVersion("1.0"))).toBe(0);
  });

  it("loads only the frozen production registry and never negotiates the Task7 fixture", () => {
    const registry = loadVersionRegistry();
    expect(registry.versions).toEqual([{ version: "1.0", negotiable: true }, { version: "0.9", negotiable: false, fixture_owner: "Task7" }]);
    expect(Object.isFrozen(registry.versions)).toBe(true);
  });

  it("rejects a runtime-forged registry despite a TypeScript cast", async () => {
    const hello = await verifyConnectMessage(signedConnect("connect_hello", helloPayload, devicePublic, devicePrivate), "connect_hello", context("device", devicePublic));
    const forged = { ...loadVersionRegistry(), versions: [{ version: "1.0", negotiable: true }, { version: "9.9", negotiable: true }] } as unknown as ReturnType<typeof loadVersionRegistry>;
    expect(() => selectHighestCommonVersion(hello, forged)).toThrowError("VERSION_UNSUPPORTED");
  });

  it("selects the highest common version and binds the entire offer", async () => {
    const hello = await verifyConnectMessage(signedConnect("connect_hello", helloPayload, devicePublic, devicePrivate), "connect_hello", context("device", devicePublic));
    expect(Object.isFrozen(hello)).toBe(true);
    expect(Object.isFrozen(hello.header)).toBe(true);
    expect(Object.isFrozen(hello.payload)).toBe(true);
    expect(Object.isFrozen(hello.payload.supported_versions)).toBe(true);
    expect(() => (hello.payload.supported_versions as string[]).reverse()).toThrow();
    expect(selectHighestCommonVersion(hello, loadVersionRegistry())).toEqual({ selected: "1.0", clientOfferDigest: sha256B64Url(canonicalBytes(helloPayload)) });
    const welcome = await verifyConnectMessage(signedConnect("connect_welcome", welcomePayload, bridgePublic, bridgePrivate), "connect_welcome", context("bridge-command", bridgePublic));
    expect(Object.isFrozen(welcome)).toBe(true);
    expect(Object.isFrozen(welcome.payload.command_key_set)).toBe(true);
    expect(Object.isFrozen(welcome.payload.command_key_set.current)).toBe(true);
    const negotiation = verifyWelcome(welcome, hello, loadVersionRegistry());
    expect(negotiation).toEqual({ selected: "1.0", clientOfferDigest: welcomePayload.client_offer_digest, clientNonce: NONCE_A, bridgeNonce: NONCE_B, bridgeTime: welcomePayload.bridge_time, commandKeySet: welcomePayload.command_key_set, connectionGeneration: "9" });
    expect(Object.isFrozen(negotiation)).toBe(true);
  });

  it.each([
    ["challenge-style overflow in hello", "connect_hello", { ...helloPayload, supported_versions: ["18446744073709551616.0"] }],
    ["selected overflow in welcome", "connect_welcome", { ...welcomePayload, selected_protocol: "1.18446744073709551616" }],
  ] as const)("rejects signed version overflow: %s", async (_label, type, payload) => {
    const app = type === "connect_hello";
    await expect(verifyConnectMessage(signedConnect(type, payload, app ? devicePublic : bridgePublic, app ? devicePrivate : bridgePrivate), type as "connect_hello", context(app ? "device" : "bridge-command", app ? devicePublic : bridgePublic))).rejects.toThrowError(/^VERSION_UNSUPPORTED$/);
  });

  it("binds ordering and both resume fields into the full offer digest", async () => {
    const hello = await verifyConnectMessage(signedConnect("connect_hello", helloPayload, devicePublic, devicePrivate), "connect_hello", context("device", devicePublic));
    const welcome = await verifyConnectMessage(signedConnect("connect_welcome", welcomePayload, bridgePublic, bridgePrivate), "connect_welcome", context("bridge-command", bridgePublic));
    const originalDigest = selectHighestCommonVersion(hello, loadVersionRegistry()).clientOfferDigest;
    const mutations = [
      { ...helloPayload, supported_versions: ["1.0", "0.9"] },
      { ...helloPayload, last_manifest_generation: "8" },
      { ...helloPayload, last_event_cursor: "cursor-2" },
    ];
    for (const payload of mutations) {
      const mutated = await verifyConnectMessage(signedConnect("connect_hello", payload, devicePublic, devicePrivate), "connect_hello", context("device", devicePublic));
      expect(selectHighestCommonVersion(mutated, loadVersionRegistry()).clientOfferDigest).not.toBe(originalDigest);
      expect(() => verifyWelcome(welcome, mutated, loadVersionRegistry())).toThrowError("INTEGRITY_FAILED");
    }
  });

  it("admits but never selects a 0.9-only signed offer", async () => {
    const hello = await verifyConnectMessage(signedConnect("connect_hello", { ...helloPayload, supported_versions: ["0.9"] }, devicePublic, devicePrivate), "connect_hello", context("device", devicePublic));
    expect(() => selectHighestCommonVersion(hello, loadVersionRegistry())).toThrowError("VERSION_UNSUPPORTED");
  });

  it.each([
    ["device", { device_id: "device-b" }, "AUTH_BINDING_MISMATCH"],
    ["pairing", { pairing_generation: "4" }, "AUTH_BINDING_MISMATCH"],
    ["type", { message_type: "connect_welcome" }, "SCHEMA_INVALID"],
    ["schema", { message_schema: "urn:open-android-intelligence:protocol:v1:message:connect_welcome" }, "SCHEMA_INVALID"],
    ["direction", { direction: "bridge-to-app" }, "SCHEMA_INVALID"],
  ] as const)("rejects signed hello header %s mutation", async (_label, patch, error) => {
    await expect(verifyConnectMessage(signedConnect("connect_hello", helloPayload, devicePublic, devicePrivate, patch), "connect_hello", context("device", devicePublic))).rejects.toThrowError(error);
  });

  it("rejects signed missing and unknown hello/welcome payload fields", async () => {
    for (const [type, payload, key, privateKey, role] of [
      ["connect_hello", helloPayload, devicePublic, devicePrivate, "device"],
      ["connect_welcome", welcomePayload, bridgePublic, bridgePrivate, "bridge-command"],
    ] as const) {
      for (const field of Object.keys(payload)) {
        const missing = { ...payload } as Record<string, unknown>;
        delete missing[field];
        await expect(verifyConnectMessage(signedConnect(type, missing, key, privateKey), type as "connect_hello", context(role, key))).rejects.toThrowError("SCHEMA_INVALID");
      }
      await expect(verifyConnectMessage(signedConnect(type, { ...payload, unknown_field: true }, key, privateKey), type as "connect_hello", context(role, key))).rejects.toThrowError("SCHEMA_INVALID");
    }
  });

  it("enforces current/header equality and distinct optional next command key", async () => {
    await expect(verifyConnectMessage(signedConnect("connect_welcome", { ...welcomePayload, command_key_set: { current: devicePublic, next: null } }, bridgePublic, bridgePrivate), "connect_welcome", context("bridge-command", bridgePublic))).rejects.toThrowError("SCHEMA_INVALID");
    await expect(verifyConnectMessage(signedConnect("connect_welcome", { ...welcomePayload, command_key_set: { current: bridgePublic, next: bridgePublic } }, bridgePublic, bridgePrivate), "connect_welcome", context("bridge-command", bridgePublic))).rejects.toThrowError("SCHEMA_INVALID");
    const welcome = await verifyConnectMessage(signedConnect("connect_welcome", { ...welcomePayload, command_key_set: { current: bridgePublic, next: bridgeNextPublic } }, bridgePublic, bridgePrivate), "connect_welcome", context("bridge-command", bridgePublic));
    expect(welcome.payload.command_key_set.next?.kid).toBe(bridgeNextPublic.kid);
    expect(Object.isFrozen(welcome.payload.command_key_set.next)).toBe(true);
  });

  it("rejects signed welcome command keys that are not valid P-256 public points", async () => {
    await expect(verifyConnectMessage(signedConnect("connect_welcome", { ...welcomePayload, command_key_set: { current: invalidPoint(bridgePublic.kid), next: null } }, bridgePublic, bridgePrivate), "connect_welcome", context("bridge-command", bridgePublic))).rejects.toThrowError("SCHEMA_INVALID");
    await expect(verifyConnectMessage(signedConnect("connect_welcome", { ...welcomePayload, command_key_set: { current: bridgePublic, next: invalidPoint("invalid-next") } }, bridgePublic, bridgePrivate), "connect_welcome", context("bridge-command", bridgePublic))).rejects.toThrowError("SCHEMA_INVALID");
  });

  it("rejects downgrade, offer mutation, and repeated nonce mismatch", async () => {
    const hello = await verifyConnectMessage(signedConnect("connect_hello", helloPayload, devicePublic, devicePrivate), "connect_hello", context("device", devicePublic));
    const base = welcomePayload;
    for (const [patch, error] of [[{ selected_protocol: "0.9" }, "VERSION_UNSUPPORTED"], [{ client_offer_digest: Buffer.alloc(32, 9).toString("base64url") }, "INTEGRITY_FAILED"], [{ client_nonce: NONCE_B }, "AUTH_BINDING_MISMATCH"]] as const) {
      const payload = { ...base, ...patch };
      const welcome = await verifyConnectMessage(signedConnect("connect_welcome", payload, bridgePublic, bridgePrivate), "connect_welcome", context("bridge-command", bridgePublic));
      expect(() => verifyWelcome(welcome, hello, loadVersionRegistry())).toThrowError(error);
    }
  });

  it("does not trust the welcome command key set to verify its own signature", async () => {
    const payload = { client_offer_digest: sha256B64Url(canonicalBytes(helloPayload)), client_nonce: NONCE_A, bridge_nonce: NONCE_B, selected_protocol: "1.0", bridge_time: "2026-08-08T00:01:00.000Z", command_key_set: { current: devicePublic, next: null }, connection_generation: "9" };
    await expect(verifyConnectMessage(signedConnect("connect_welcome", payload, devicePublic, devicePrivate), "connect_welcome", context("bridge-command", bridgePublic))).rejects.toThrowError("AUTH_FAILED");
  });
});

const compileTimeNegotiationBrandEvidence = (
  hello: VerifiedConnectHello,
  welcome: VerifiedConnectWelcome,
  registry: LockedVersionRegistry,
): void => {
  void selectHighestCommonVersion(hello, registry);
  void verifyWelcome(welcome, hello, registry);
  // @ts-expect-error Negotiation accepts only an admitted branded hello.
  void selectHighestCommonVersion({ payload: helloPayload }, registry);
  // @ts-expect-error Welcome verification accepts only admitted branded values.
  void verifyWelcome({ payload: welcomePayload }, hello, registry);
  // @ts-expect-error Callers cannot pass a registry-shaped object.
  void selectHighestCommonVersion(hello, { versions: [{ version: "9.9", negotiable: true }] });
};
void compileTimeNegotiationBrandEvidence;
