import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { randomBytes } from "node:crypto";
import { describe, expect, it } from "vitest";
import JSZip from "jszip";
import { ed25519 } from "@noble/curves/ed25519.js";
import { buildBundledNotifications } from "../src/bundled-notifications.js";
import { base64urlToBytes, buildSignatureInput } from "../src/build-package.js";
import { FIXTURE_SEED } from "../src/reference-build-policy.js";

// ABI-only module for packaging tests; runtime regressions use compiled Rust/WASM.
function abiModule(): Uint8Array {
  const section = (id: number, bytes: number[]) => [id, bytes.length, ...bytes];
  const name = [...new TextEncoder().encode("open_android_intelligence_plugin_main")];
  const exports = [2, name.length, ...name, 0, 0, 6, ...new TextEncoder().encode("memory"), 2, 0];
  return new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0,
    ...section(1, [1, 96, 0, 0]), ...section(3, [1, 0]), ...section(5, [1, 0, 1]),
    ...section(7, exports), ...section(10, [1, 2, 0, 11])]);
}

describe("notification package delivered in Full APKs", () => {
  it("signs the checked-in notification manifest/schema and exact compiled input deterministically", async () => {
    const root = await mkdtemp(join(tmpdir(), "notification-build-"));
    try {
      const sourceRoot = resolve(import.meta.dirname, "../..");
      for (const path of ["plugins/notifications", "plugins/schemas", "plugins/target/wasm32-unknown-unknown/release"]) await mkdir(join(root, path), { recursive: true });
      for (const path of ["plugins/notifications/manifest.json", "plugins/schemas/notifications.json"]) await writeFile(join(root, path), await readFile(join(sourceRoot, path)));
      const wasmPath = join(root, "plugins/target/wasm32-unknown-unknown/release/notifications.wasm");
      const wasm = abiModule();
      await writeFile(wasmPath, wasm);
      const seed = randomBytes(32);
      const seedFile = join(root, "seed");
      const output = join(root, "out/notifications.alp");
      await writeFile(seedFile, seed, { mode: 0o600 });
      const built = await buildBundledNotifications(root, seedFile, output);
      expect((await buildBundledNotifications(root, seedFile, output)).sha256).toBe(built.sha256);
      expect(built.manifest.plugin.id).toBe("org.openandroidintelligence.notifications");
      expect(built.manifest.capabilities.kernelPrimitives.map(item => item.id)).toEqual(["kernel.notifications.read"]);
      const zip = await JSZip.loadAsync(await readFile(output));
      expect(await zip.file("payload/notifications.wasm")!.async("uint8array")).toEqual(wasm);
      const manifest = await zip.file("manifest.json")!.async("uint8array");
      const files = await zip.file("files.json")!.async("uint8array");
      const signature = base64urlToBytes((await zip.file("signature.ed25519")!.async("text")).trim());
      expect(ed25519.verify(signature, buildSignatureInput(manifest, files), ed25519.getPublicKey(seed))).toBe(true);
      expect(await readFile(seedFile)).toEqual(seed);
      await expect(buildBundledNotifications(root, undefined, output)).rejects.toThrow("PLUGIN_SIGNING_KEY_REQUIRED");
      await writeFile(seedFile, FIXTURE_SEED);
      await expect(buildBundledNotifications(root, seedFile, output)).rejects.toThrow("PLUGIN_SIGNING_KEY_UNSAFE");
      await writeFile(seedFile, seed);
      await writeFile(wasmPath, new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]));
      await expect(buildBundledNotifications(root, seedFile, output)).rejects.toThrow("PLUGIN_WASM_ABI_INVALID");
    } finally { await rm(root, { recursive: true, force: true }); }
  });
});
