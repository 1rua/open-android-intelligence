import { mkdir, readFile, rm, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { ed25519 } from "@noble/curves/ed25519.js";
import { buildPackage, bytesToBase64url, type BuiltPackage } from "./build-package.js";
import { referenceSigningKey, validateReferenceWasm } from "./reference-build-policy.js";
import type { PluginManifest } from "./manifest.js";

/** Build the existing protected notification plugin; APK bundling grants no access. */
export async function buildBundledNotifications(repositoryRoot: string, keyFile: string | undefined, output: string): Promise<BuiltPackage> {
  const privateKey = await referenceSigningKey(false, keyFile);
  const wasm = await readFile(join(repositoryRoot, "plugins/target/wasm32-unknown-unknown/release/notifications.wasm"));
  validateReferenceWasm(wasm);
  const template = JSON.parse(await readFile(join(repositoryRoot, "plugins/notifications/manifest.json"), "utf8"));
  const manifest: PluginManifest = {
    ...template,
    author: { algorithm: "Ed25519", publicKey: bytesToBase64url(ed25519.getPublicKey(privateKey)) },
  };
  const staging = await mkdtemp(join(tmpdir(), "oai-notifications-"));
  try {
    await mkdir(join(staging, "payload"));
    await mkdir(join(staging, "schemas"));
    await writeFile(join(staging, "payload/notifications.wasm"), wasm);
    await writeFile(join(staging, "schemas/notifications.json"), await readFile(join(repositoryRoot, "plugins/schemas/notifications.json")));
    const built = await buildPackage({ manifest, baseDirectory: staging, privateKey });
    await mkdir(dirname(output), { recursive: true });
    await writeFile(output, built.bytes);
    return built;
  } finally {
    privateKey.fill(0);
    await rm(staging, { recursive: true, force: true });
  }
}
