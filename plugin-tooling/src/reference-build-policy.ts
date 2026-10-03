import { readFile } from "node:fs/promises";

/** Public fixtures are deliberately usable only in the separate fixture build. */
export const FIXTURE_SEED = new Uint8Array(32).fill(0x77);

export async function referenceSigningKey(fixture: boolean, keyFile?: string): Promise<Uint8Array> {
  if (fixture) return FIXTURE_SEED.slice();
  if (!keyFile) throw new Error("PLUGIN_SIGNING_KEY_REQUIRED: set OPEN_ANDROID_INTELLIGENCE_PLUGIN_SIGNING_KEY_FILE");
  const key = await readFile(keyFile);
  if (key.byteLength !== 32) throw new Error("PLUGIN_SIGNING_KEY_INVALID: expected a raw 32-byte Ed25519 seed");
  if (key.every((byte) => byte === 0x77) || key.every((byte) => byte === 0)) {
    throw new Error("PLUGIN_SIGNING_KEY_UNSAFE: public fixture seeds cannot sign release packages");
  }
  return key;
}

export function validateReferenceWasm(bytes: Uint8Array): void {
  const module = new WebAssembly.Module(Uint8Array.from(bytes));
  const exports = WebAssembly.Module.exports(module);
  if (!exports.some((item) => item.name === "open_android_intelligence_plugin_main" && item.kind === "function")
      || !exports.some((item) => item.name === "memory" && item.kind === "memory")) {
    throw new Error("PLUGIN_WASM_ABI_INVALID: entrypoint and memory exports are required");
  }
  for (const item of WebAssembly.Module.imports(module)) {
    if (item.module !== "open_android_intelligence_kernel_v1" || item.kind !== "function"
        || !["kernel_log", "kernel_random_fill", "kernel_now_millis", "kernel_call"].includes(item.name)) {
      throw new Error("PLUGIN_WASM_ABI_INVALID: unsupported host import");
    }
  }
}
