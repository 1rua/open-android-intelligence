import { mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createHash, randomBytes } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  buildPackage,
  buildSignatureInput,
  bytesToBase64url,
  sha256Hex,
  randomPrivateKey,
} from "../src/build-package.js";
import { MINIMAL_MANIFEST } from "../src/manifest.js";
import JSZip from "jszip";
import canonicalize from "canonicalize";
import { ed25519 } from "@noble/curves/ed25519.js";

describe("buildPackage determinism", () => {
  let dir: string;
  let privateKey: Uint8Array;

  beforeEach(async () => {
    dir = join(tmpdir(), "alp-test-" + Date.now());
    await mkdir(dir, { recursive: true });
    // Deterministic seed so the golden hash is stable across runs.
    privateKey = new Uint8Array(32).fill(0xab);
  });

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  async function writeFixture(): Promise<void> {
    await mkdir(join(dir, "payload"), { recursive: true });
    const wasm = new Uint8Array([0x00, 0x61, 0x73, 0x6d]); // WASM magic
    await writeFile(join(dir, "payload", "plugin.wasm"), wasm);
    const manifest = {
      ...MINIMAL_MANIFEST,
      plugin: { ...MINIMAL_MANIFEST.plugin, id: "org.example.test" },
      author: {
        algorithm: "Ed25519" as const,
        publicKey: bytesToBase64url(ed25519.getPublicKey(privateKey)),
      },
    };
    await writeFile(join(dir, "manifest.json"), canonicalize(manifest)!);
  }

  it("produces identical sha256 for identical inputs", async () => {
    await writeFixture();
    const manifest = {
      ...MINIMAL_MANIFEST,
      plugin: { ...MINIMAL_MANIFEST.plugin, id: "org.example.test" },
      author: {
        algorithm: "Ed25519" as const,
        publicKey: bytesToBase64url(ed25519.getPublicKey(privateKey)),
      },
    };
    const first = await buildPackage({ manifest, baseDirectory: dir, privateKey });
    const second = await buildPackage({ manifest, baseDirectory: dir, privateKey });

    expect(first.sha256).toBe(second.sha256);
    expect(first.sha256).toBe(FIXTURE_ALP_SHA256);
  });

  it("fails when a payload byte changes", async () => {
    await writeFixture();
    const manifest = {
      ...MINIMAL_MANIFEST,
      plugin: { ...MINIMAL_MANIFEST.plugin, id: "org.example.test" },
      author: {
        algorithm: "Ed25519" as const,
        publicKey: bytesToBase64url(ed25519.getPublicKey(privateKey)),
      },
    };
    const first = await buildPackage({ manifest, baseDirectory: dir, privateKey });

    await writeFile(join(dir, "payload", "plugin.wasm"), new Uint8Array([0x01]));
    const second = await buildPackage({ manifest, baseDirectory: dir, privateKey });

    expect(second.sha256).not.toBe(first.sha256);
  });

  it("rejects path traversal in base directory", async () => {
    await mkdir(join(dir, "payload"), { recursive: true });
    await writeFile(join(dir, "payload", "plugin.wasm"), new Uint8Array([0x00]));
    // Simulate a malicious file entry path
    const manifest = {
      ...MINIMAL_MANIFEST,
      plugin: { ...MINIMAL_MANIFEST.plugin, id: "org.example.traversal" },
      author: {
        algorithm: "Ed25519" as const,
        publicKey: bytesToBase64url(ed25519.getPublicKey(privateKey)),
      },
    };
    await expect(
      buildPackage({ manifest, baseDirectory: dir + "/../traversal", privateKey }),
    ).rejects.toThrow();
  });

  it("builds a verifyable signature input", async () => {
    await writeFixture();
    const manifest = {
      ...MINIMAL_MANIFEST,
      plugin: { ...MINIMAL_MANIFEST.plugin, id: "org.example.test" },
      author: {
        algorithm: "Ed25519" as const,
        publicKey: bytesToBase64url(ed25519.getPublicKey(privateKey)),
      },
    };
    const pkg = await buildPackage({ manifest, baseDirectory: dir, privateKey });
    const zip = await JSZip.loadAsync(pkg.bytes);
    const manifestBytes = await zip.file("manifest.json")!.async("uint8array");
    const filesBytes = await zip.file("files.json")!.async("uint8array");
    const signatureText = await zip.file("signature.ed25519")!.async("text");
    const signature = Buffer.from(signatureText.trim(), "base64url");

    const input = buildSignatureInput(manifestBytes, filesBytes);
    const publicKey = ed25519.getPublicKey(privateKey);
    expect(ed25519.verify(signature, input, publicKey)).toBe(true);
  });

  it("orders entries by UTF-8 path bytes", async () => {
    await mkdir(join(dir, "payload"), { recursive: true });
    await mkdir(join(dir, "ui"), { recursive: true });
    await writeFile(join(dir, "payload", "plugin.wasm"), new Uint8Array([0x00]));
    await writeFile(join(dir, "ui", "card.json"), new TextEncoder().encode('{"type":"text"}'));

    const manifest = {
      ...MINIMAL_MANIFEST,
      plugin: { ...MINIMAL_MANIFEST.plugin, id: "org.example.order" },
      author: {
        algorithm: "Ed25519" as const,
        publicKey: bytesToBase64url(ed25519.getPublicKey(privateKey)),
      },
      ui: { settings: [], cards: [{ type: "text" }] },
    };
    const pkg = await buildPackage({ manifest, baseDirectory: dir, privateKey });
    const zip = await JSZip.loadAsync(pkg.bytes);
    const names = Object.keys(zip.files);
    const sorted = [...names].sort((a, b) => {
      const ab = new TextEncoder().encode(a);
      const bb = new TextEncoder().encode(b);
      const len = Math.min(ab.length, bb.length);
      for (let i = 0; i < len; i++) {
        if (ab[i] !== bb[i]) return ab[i] - bb[i];
      }
      return ab.length - bb.length;
    });
    expect(names).toEqual(sorted);
  });
});

/**
 * 黄金向量（契约 §11「相同输入产生相同 .alp SHA-256」）。
 *
 * 历史锚点 0272109b… 生成于品牌更名提交 22fc40d 之前：该提交把签名输入前缀从
 * "AGENT-LIFE-PLUGIN-PACKAGE-V1" 改为 "OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1"
 * （契约 §4:79 规定的新前缀），ZIP 字节随之变化但锚点未同步重锚。
 * 当前值锚定在现有确定性实现上。
 */
const FIXTURE_ALP_SHA256 =
  "73e0b453af929457acd44656544465b9f3e24628daa32bab14ceda95644b1b26";
