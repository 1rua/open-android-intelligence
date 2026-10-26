import { mkdtemp, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, join, resolve } from "node:path";
import { randomBytes } from "node:crypto";
import { describe, expect, it } from "vitest";
import JSZip from "jszip";
import { ed25519 } from "@noble/curves/ed25519.js";
import { buildBundledPlugins } from "../src/bundled-plugins.js";
import { base64urlToBytes, buildSignatureInput } from "../src/build-package.js";
import { FIXTURE_SEED } from "../src/reference-build-policy.js";

// 仅含 ABI 导出的桩模块，供打包测试使用；运行时回归测试才用真实编译的 Rust/WASM。
function abiModule(): Uint8Array {
  const section = (id: number, bytes: number[]) => [id, bytes.length, ...bytes];
  const name = [...new TextEncoder().encode("open_android_intelligence_plugin_main")];
  const exports = [2, name.length, ...name, 0, 0, 6, ...new TextEncoder().encode("memory"), 2, 0];
  return new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0,
    ...section(1, [1, 96, 0, 0]), ...section(3, [1, 0]), ...section(5, [1, 0, 1]),
    ...section(7, exports), ...section(10, [1, 2, 0, 11])]);
}

// 内置插件的期望清单：目录、wasm 产物名、输出文件名、内核原语列表
const EXPECTED = [
  {
    directory: "notifications",
    wasm: "notifications.wasm",
    output: "notifications.alp",
    id: "org.openandroidintelligence.notifications",
    kernelPrimitives: ["kernel.notifications.read"],
    schemas: ["notifications.json"],
  },
  {
    directory: "sms",
    wasm: "sms.wasm",
    output: "sms.alp",
    id: "org.openandroidintelligence.sms",
    kernelPrimitives: [
      "kernel.sms.read",
      "kernel.scheduler.set",
      "kernel.scheduler.read",
      "kernel.scheduler.cancel",
      "kernel.background.run",
    ],
    schemas: ["sms.json", "sms-schedule.json", "sms-job-status.json", "sms-cancel.json"],
  },
  {
    directory: "call-log",
    wasm: "call_log.wasm",
    output: "call-log.alp",
    id: "org.openandroidintelligence.call-log",
    kernelPrimitives: ["kernel.call-log.read"],
    schemas: ["call-log.json"],
  },
  { directory: "screen-reader", wasm: "screen_reader.wasm", output: "screen-reader.alp",
    id: "org.openandroidintelligence.screen-reader", kernelPrimitives: ["kernel.screen.read"], schemas: ["screen-read.json"] },
  { directory: "screen-capture", wasm: "screen_capture.wasm", output: "screen-capture.alp",
    id: "org.openandroidintelligence.screen-capture", kernelPrimitives: ["kernel.screen.capture"], schemas: ["screen-capture.json"] },
  { directory: "screen-control", wasm: "screen_control.wasm", output: "screen-control.alp",
    id: "org.openandroidintelligence.screen-control", kernelPrimitives: ["kernel.screen.control"], schemas: ["screen-control.json"] },
] as const;

// 在临时目录里搭建一个最小仓库：真实 manifest 与 schema 从源仓库拷贝，wasm 用 ABI 桩代替
async function setupFakeRepository(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "bundled-plugins-build-"));
  const sourceRoot = resolve(import.meta.dirname, "../..");
  await mkdir(join(root, "plugins/target/wasm32-unknown-unknown/release"), { recursive: true });
  await mkdir(join(root, "plugins/schemas"), { recursive: true });
  for (const expected of EXPECTED) {
    await mkdir(join(root, "plugins", expected.directory), { recursive: true });
    await writeFile(
      join(root, "plugins", expected.directory, "manifest.json"),
      await readFile(join(sourceRoot, "plugins", expected.directory, "manifest.json")),
    );
    await writeFile(
      join(root, "plugins/target/wasm32-unknown-unknown/release", expected.wasm),
      abiModule(),
    );
  }
  for (const schema of ["notifications.json", "sms.json", "sms-schedule.json", "sms-job-status.json", "sms-cancel.json", "call-log.json", "screen-read.json", "screen-capture.json", "screen-control.json"]) {
    await writeFile(join(root, "plugins/schemas", schema), await readFile(join(sourceRoot, "plugins/schemas", schema)));
  }
  return root;
}

describe("bundled plugin packages delivered in Full APKs", () => {
  it("signs all checked-in manifests/schemas and exact compiled inputs deterministically", async () => {
    const root = await setupFakeRepository();
    try {
      const seed = randomBytes(32);
      const seedFile = join(root, "seed");
      const outputDirectory = join(root, "out");
      await writeFile(seedFile, seed, { mode: 0o600 });

      const built = await buildBundledPlugins(root, seedFile, outputDirectory);
      // 每项必须各自独立签名，且保持声明顺序
      expect(built.map((item) => item.manifest.plugin.id)).toEqual(EXPECTED.map((item) => item.id));
      expect(built.map((item) => item.manifest.plugin.version)).toEqual(EXPECTED.map(() => "1.0.0"));

      // 确定性：同一输入再次构建，每个包的 sha256 必须逐一相同
      const rebuilt = await buildBundledPlugins(root, seedFile, outputDirectory);
      expect(rebuilt.map((item) => item.sha256)).toEqual(built.map((item) => item.sha256));

      for (let index = 0; index < EXPECTED.length; index++) {
        const expected = EXPECTED[index]!;
        const item = built[index]!;
        // 内核原语列表与 manifest 完全一致
        expect(item.manifest.capabilities.kernelPrimitives.map((primitive) => primitive.id)).toEqual(
          [...expected.kernelPrimitives],
        );
        // manifest 中 author.publicKey 已替换为私钥派生公钥
        expect(item.manifest.author.publicKey).toBe(
          (await import("../src/build-package.js")).bytesToBase64url(ed25519.getPublicKey(seed)),
        );

        // 输出文件名固定，包内 payload 与 schema 与仓库内文件逐字节一致
        const zip = await JSZip.loadAsync(await readFile(join(outputDirectory, expected.output)));
        expect(await zip.file(join("payload", expected.wasm))!.async("uint8array")).toEqual(abiModule());
        for (const schema of expected.schemas) {
          const inPackage = await zip.file(join("schemas", schema))!.async("uint8array");
          // 逐字节比对：readFile 返回 Buffer，统一转成 Uint8Array 再比较
          expect(inPackage).toEqual(new Uint8Array(await readFile(join(root, "plugins/schemas", schema))));
        }

        // 签名可由派生公钥验证
        const manifestBytes = await zip.file("manifest.json")!.async("uint8array");
        const filesBytes = await zip.file("files.json")!.async("uint8array");
        const signature = base64urlToBytes((await zip.file("signature.ed25519")!.async("text")).trim());
        expect(ed25519.verify(signature, buildSignatureInput(manifestBytes, filesBytes), ed25519.getPublicKey(seed))).toBe(true);
      }

      // 私钥文件内容不被改动
      expect(await readFile(seedFile)).toEqual(seed);

      // 缺少私钥文件必须被拒绝
      await expect(buildBundledPlugins(root, undefined, outputDirectory)).rejects.toThrow("PLUGIN_SIGNING_KEY_REQUIRED");
      // 公共 fixture 种子不能签发布包
      await writeFile(seedFile, FIXTURE_SEED);
      await expect(buildBundledPlugins(root, seedFile, outputDirectory)).rejects.toThrow("PLUGIN_SIGNING_KEY_UNSAFE");
      await writeFile(seedFile, seed);
      // 破坏任一 wasm 的 ABI 必须被拒绝
      await writeFile(join(root, "plugins/target/wasm32-unknown-unknown/release/sms.wasm"), new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]));
      await expect(buildBundledPlugins(root, seedFile, outputDirectory)).rejects.toThrow("PLUGIN_WASM_ABI_INVALID");
    } finally {
      const trash = join(tmpdir(), "open-android-intelligence-trash");
      await mkdir(trash, { recursive: true });
      await rename(root, join(trash, basename(root)));
    }
  });
});
