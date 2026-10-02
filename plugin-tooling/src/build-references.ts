import { mkdir, writeFile, readFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { buildPackage, bytesToBase64url, sha256Hex } from "./build-package.js";
import { ed25519 } from "@noble/curves/ed25519.js";
import type { PluginManifest } from "./manifest.js";
import { referenceSigningKey, validateReferenceWasm } from "./reference-build-policy.js";

const __dirname = fileURLToPath(new URL(".", import.meta.url));
const ROOT = resolve(__dirname, "../..");

interface PluginSpec {
  id: string;
  name: string;
  version: string;
  description: string;
  wasmName: string;
  provides: { id: string; version: string; schema: string }[];
  kernelPrimitives: { id: string; version: string; purpose: string }[];
}

const REFERENCE_PLUGINS: PluginSpec[] = [
  {
    id: "org.openandroidintelligence.notifications",
    name: "Notifications Query",
    version: "1.0.0",
    description: "Official reference plugin for querying notifications",
    wasmName: "notifications.wasm",
    provides: [
      {
        id: "org.openandroidintelligence.notifications.query",
        version: "1.0.0",
        schema: "schemas/notifications.json",
      },
    ],
    kernelPrimitives: [
      {
        id: "kernel.notifications.read",
        version: "1.0.0",
        purpose: "Read recent notifications under local policy",
      },
    ],
  },
  {
    id: "org.openandroidintelligence.sms",
    name: "SMS Query",
    version: "1.0.0",
    description: "Official reference plugin for querying SMS messages",
    wasmName: "sms.wasm",
    provides: [
      {
        id: "org.openandroidintelligence.sms.query",
        version: "1.0.0",
        schema: "schemas/sms.json",
      },
    ],
    kernelPrimitives: [
      {
        id: "kernel.sms.read",
        version: "1.0.0",
        purpose: "Read SMS inbox under local policy",
      },
    ],
  },
  {
    id: "org.openandroidintelligence.call-log",
    name: "Call Log Query",
    version: "1.0.0",
    description: "Official reference plugin for querying call logs",
    wasmName: "call_log.wasm",
    provides: [
      {
        id: "org.openandroidintelligence.call-log.query",
        version: "1.0.0",
        schema: "schemas/call-log.json",
      },
    ],
    kernelPrimitives: [
      {
        id: "kernel.call-log.read",
        version: "1.0.0",
        purpose: "Read call logs under local policy",
      },
    ],
  },
];

async function main() {
  const fixture = process.argv.includes("--fixtures");
  const privateKey = await referenceSigningKey(fixture, process.env["OPEN_ANDROID_INTELLIGENCE_PLUGIN_SIGNING_KEY_FILE"]);
  const publicKey = bytesToBase64url(ed25519.getPublicKey(privateKey));
  console.log("Building reference plugins...");
  const outDir = join(ROOT, fixture ? "plugins/dist/fixtures" : "plugins/dist");
  await mkdir(outDir, { recursive: true });

  for (const spec of REFERENCE_PLUGINS) {
    const provided = spec.provides[0];
    if (provided === undefined) throw new Error("REFERENCE_CAPABILITY_MISSING");
    const stagingDir = join(outDir, ".staging", spec.id);
    await mkdir(join(stagingDir, "payload"), { recursive: true });
    await mkdir(join(stagingDir, "schemas"), { recursive: true });

    // 复制或构造 schema
    const schemaContent = JSON.stringify({ type: "object" });
    await writeFile(
      join(stagingDir, provided.schema),
      new TextEncoder().encode(schemaContent),
    );

    // 检查 target/wasm32-unknown-unknown/release 产物或使用 fixture stub
    const wasmPath = join(
      ROOT,
      "plugins/target/wasm32-unknown-unknown/release",
      spec.wasmName,
    );
    let wasmBytes: Uint8Array;
    try {
      wasmBytes = await readFile(wasmPath);
    } catch (error) {
      if (!fixture) throw new Error(`PLUGIN_WASM_REQUIRED: compile ${spec.wasmName} before packaging`, { cause: error });
      // Empty modules belong only to explicitly named packaging fixtures.
      wasmBytes = new Uint8Array([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00]);
    }
    if (!fixture) validateReferenceWasm(wasmBytes);
    await writeFile(join(stagingDir, "payload", spec.wasmName), wasmBytes);

    const manifest: PluginManifest = {
      schemaVersion: "1.0",
      plugin: {
        id: spec.id,
        version: spec.version,
        name: spec.name,
        description: spec.description,
      },
      author: {
        algorithm: "Ed25519",
        publicKey,
      },
      runtime: {
        type: "protected-wasm",
        abiVersion: "1.0",
        entrypoint: "open_android_intelligence_plugin_main",
        payload: `payload/${spec.wasmName}`,
      },
      compatibility: {
        androidHost: ">=2.0.0 <3.0.0",
        gatewayProtocol: ">=2.0 <3.0",
      },
      capabilities: {
        provides: spec.provides,
        depends: [],
        kernelPrimitives: spec.kernelPrimitives,
      },
      security: {
        network: [],
        background: {
          requested: false,
          minimumIntervalSeconds: null,
        },
        resources: {
          maxInvocationMillis: 5000,
          maxMemoryBytes: 16777216,
          maxStorageBytes: 10485760,
          maxConcurrentInvocations: 1,
          maxDailyNetworkBytes: 0,
        },
      },
      ui: {
        settings: [],
        cards: [],
      },
      state: {
        schemaVersion: 1,
        portableExport: false,
      },
    };

    const built1 = await buildPackage({
      manifest,
      baseDirectory: stagingDir,
      privateKey,
    });

    const built2 = await buildPackage({
      manifest,
      baseDirectory: stagingDir,
      privateKey,
    });

    if (built1.sha256 !== built2.sha256) {
      throw new Error(`Non-deterministic build detected for ${spec.id}`);
    }

    const alpPath = join(outDir, `${spec.id}-${spec.version}.alp`);
    await writeFile(alpPath, built1.bytes);
    console.log(`[PASS] ${spec.id} -> ${alpPath} (sha256: ${built1.sha256})`);
  }

  console.log("All reference plugins built deterministically.");
}

main().catch((err) => {
  console.error("Build failed:", err);
  process.exit(1);
});
