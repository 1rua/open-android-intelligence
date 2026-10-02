import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes } from "node:crypto";
import { describe, expect, it } from "vitest";
import { FIXTURE_SEED, referenceSigningKey, validateReferenceWasm } from "../src/reference-build-policy.js";

describe("release reference package boundaries", () => {
  it("requires an external private identity and rejects public seeds", async () => {
    await expect(referenceSigningKey(false)).rejects.toThrow("PLUGIN_SIGNING_KEY_REQUIRED");
    const root = await mkdtemp(join(tmpdir(), "reference-key-"));
    try {
      const path = join(root, "seed");
      for (const bytes of [FIXTURE_SEED, new Uint8Array(32), new Uint8Array(31)]) {
        await writeFile(path, bytes);
        await expect(referenceSigningKey(false, path)).rejects.toThrow("PLUGIN_SIGNING_KEY_");
      }
      const privateSeed = randomBytes(32);
      await writeFile(path, privateSeed, { mode: 0o600 });
      expect(await referenceSigningKey(false, path)).toEqual(privateSeed);
      expect(await referenceSigningKey(true)).toEqual(FIXTURE_SEED);
    } finally { await rm(root, { recursive: true, force: true }); }
  });
  it("never accepts an empty module as a release plugin", () => {
    expect(() => validateReferenceWasm(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]))).toThrow("PLUGIN_WASM_ABI_INVALID");
  });
});
