import { describe, expect, it } from "vitest";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import { PairingService, type PairingTicketInput } from "../../bridge-contract/src/pairing-service.js";
import {
  BridgeIngress,
  ConnectionGenerationFence,
  MemoryReplayAdmission,
  type IngressControlFrame,
  type TailscaleUserspaceListener,
} from "../src/ingress.js";
import {
  BridgeHealth,
  createHealthHttpHandler,
  type HealthCheck,
} from "../src/health.js";

const input = (overrides: Partial<PairingTicketInput> = {}): PairingTicketInput => ({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  bridgeFingerprint: "sha256:bridge-a",
  pairingGeneration: 1n,
  policyAttestationRevision: 4n,
  ...overrides,
});

const frame = (overrides: Partial<IngressControlFrame> = {}): IngressControlFrame => ({
  tenantId: "tenant-a",
  humanPrincipalId: "human-a",
  deviceId: "device-a",
  bridgeFingerprint: "sha256:bridge-a",
  pairingGeneration: 1n,
  connectionGeneration: 1n,
  messageId: "message-1",
  payloadDigest: "sha256:payload-a",
  payload: new Uint8Array([1, 2, 3]),
  ...overrides,
});

describe("BridgeIngress", () => {
  it("fails closed and stays pending until the tsnet dependency is locked", async () => {
    let binds = 0;
    const listener: TailscaleUserspaceListener = {
      bind: async () => {
        binds += 1;
        return { close: async () => undefined };
      },
    };
    const ingress = new BridgeIngress({
      tsnetDependency: "pending",
      listener,
      port: 443,
      fingerprint: "sha256:bridge-a",
    });

    await expect(ingress.start()).resolves.toMatchObject({ status: "pending", reason: "MVP-DEP-TSNET_PENDING" });
    expect(binds).toBe(0);
    expect(ingress.status()).toMatchObject({ state: "pending", port: 443 });
  });

  it("binds only through the userspace listener after a locked dependency", async () => {
    let bindOptions: unknown;
    const listener: TailscaleUserspaceListener = {
      bind: async (options) => {
        bindOptions = options;
        return { close: async () => undefined };
      },
    };
    const ingress = new BridgeIngress({
      tsnetDependency: "locked",
      listener,
      port: 443,
      fingerprint: "sha256:bridge-a",
    });

    await expect(ingress.start()).resolves.toMatchObject({ status: "started" });
    expect(bindOptions).toEqual({ port: 443, bridgeFingerprint: "sha256:bridge-a" });
    await expect(ingress.stop()).resolves.toBeUndefined();
  });

  it("authorizes pairing fingerprint, pairing generation, connection generation and replay before dispatch", async () => {
    const pairing = new PairingService({ clock: () => 1_000 });
    const paired = pairing.issueTicket(input());
    pairing.acceptTicket(paired);
    const generations = new ConnectionGenerationFence();
    generations.open("tenant-a\u0000human-a\u0000device-a", 1n);
    const ingress = new BridgeIngress({
      tsnetDependency: "locked",
      listener: { bind: async () => ({ close: async () => undefined }) },
      port: 443,
      fingerprint: "sha256:bridge-a",
      pairing,
      generations,
      replay: new MemoryReplayAdmission(),
    });
    await ingress.start();
    let calls = 0;
    const dispatch = async () => {
      calls += 1;
      return new Uint8Array([9]);
    };

    await expect(ingress.handle(frame(), dispatch)).resolves.toEqual({ status: "accepted", receipt: new Uint8Array([9]) });
    await expect(ingress.handle(frame(), dispatch)).resolves.toEqual({ status: "duplicate", receipt: new Uint8Array([9]) });
    expect(calls).toBe(1);
    await expect(ingress.handle(frame({ payloadDigest: "sha256:payload-other" }), dispatch))
      .rejects.toMatchObject({ code: "INGRESS_REPLAY_DIGEST_MISMATCH" });
    await expect(ingress.handle(frame({ connectionGeneration: 2n }), dispatch))
      .rejects.toMatchObject({ code: "INGRESS_CONNECTION_FENCED" });
    await expect(ingress.handle(frame({ bridgeFingerprint: "sha256:wrong" }), dispatch))
      .rejects.toMatchObject({ code: "INGRESS_FINGERPRINT_MISMATCH" });
  });

  it("does not allocate an unsequenced future connection generation", () => {
    const fence = new ConnectionGenerationFence();
    expect(() => fence.open("binding", 2n)).toThrowError(/INGRESS_GENERATION_GAP/);
    fence.open("binding", 1n);
    expect(() => fence.open("binding", 3n)).toThrowError(/INGRESS_GENERATION_GAP/);
  });
});

describe("Bridge health", () => {
  it("reports live independently from readiness", async () => {
    const checks: HealthCheck[] = [{ name: "tsnet", check: async () => ({ ok: false, reason: "pending" }) }];
    const health = new BridgeHealth(checks);
    expect(health.live()).toEqual({ status: "ok" });
    await expect(health.ready()).resolves.toMatchObject({ status: "not_ready", checks: [{ name: "tsnet", ok: false }] });
  });

  it("exposes exact JSON health routes and rejects unknown paths", async () => {
    const health = new BridgeHealth([{ name: "database", check: async () => ({ ok: true }) }]);
    const handler = createHealthHttpHandler(health);
    await expect(handler({ method: "GET", path: "/health/live" })).resolves.toMatchObject({ statusCode: 200 });
    await expect(handler({ method: "GET", path: "/health/ready" })).resolves.toMatchObject({ statusCode: 200 });
    await expect(handler({ method: "POST", path: "/health/live" })).resolves.toMatchObject({ statusCode: 404 });
    await expect(handler({ method: "GET", path: "/health/other" })).resolves.toMatchObject({ statusCode: 404 });
  });
});

describe("deployment templates", () => {
  it("build pinned private ingress/runtime images without a public socket", async () => {
    const root = fileURLToPath(new URL("../deploy/", import.meta.url));
    const read = async (name: string) => readFile(join(root, name), "utf8");
    const runtime = await read("open-android-intelligence-bridge.service");
    const ingress = await read("open-android-intelligence-ingress.service");
    const runtimeImage = await read("Dockerfile");
    const ingressImage = await read("Dockerfile.ingress");
    const compose = await read("docker-compose.yml");

    expect(runtimeImage).toContain("node:24.18.0-bookworm-slim@sha256:6f7b03f7c2c8e2e784dcf9295400527b9b1270fd37b7e9a7285cf83b6951452d");
    expect(ingressImage).toContain("golang:1.26.5-bookworm@sha256:53eeac89074db483fdf0ab3be1df32bf6e47562263d2d0d6baa7f26acb4957dd");
    expect(ingressImage).toContain("CGO_ENABLED=0");
    expect(compose).toContain("network_mode: \"service:ingress\"");
    expect(compose).not.toContain("ports:");
    expect(compose).not.toContain("0.0.0.0");
    expect(compose).not.toContain("REPLACE_WITH_LOCKED_DIGEST");
    expect(compose).toContain(":ro");
    expect(compose).not.toContain("OPEN_ANDROID_INTELLIGENCE_TSNET_AUTHKEY_FILE");
    expect(ingress).not.toContain("OPEN_ANDROID_INTELLIGENCE_TSNET_AUTHKEY_FILE");
    expect(ingress).toContain("OPEN_ANDROID_INTELLIGENCE_TSNET_HOSTNAME=open-android-intelligence-bridge");
    expect(ingress).toContain("https://controlplane.tailscale.com");
    for (const unit of [runtime, ingress]) {
      expect(unit).not.toContain("ListenStream=");
      expect(unit).not.toContain("ListenDatagram=");
      expect(unit).not.toContain("0.0.0.0");
      expect(unit).toContain("ProtectSystem=strict");
    }
  });
});
