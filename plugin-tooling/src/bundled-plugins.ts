import { mkdir, mkdtemp, readFile, rename, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, join } from "node:path";
import { ed25519 } from "@noble/curves/ed25519.js";
import { buildPackage, bytesToBase64url, type BuiltPackage } from "./build-package.js";
import { referenceSigningKey, validateReferenceWasm } from "./reference-build-policy.js";
import type { PluginManifest } from "./manifest.js";

/** 内置插件清单：manifest 所在目录、编译产物 wasm 文件名、输出的 .alp 文件名 */
const BUNDLED_PLUGINS = [
  { directory: "notifications", wasm: "notifications.wasm", output: "notifications.alp" },
  { directory: "sms", wasm: "sms.wasm", output: "sms.alp" },
  { directory: "call-log", wasm: "call_log.wasm", output: "call-log.alp" },
  { directory: "screen-reader", wasm: "screen_reader.wasm", output: "screen-reader.alp" },
  { directory: "screen-capture", wasm: "screen_capture.wasm", output: "screen-capture.alp" },
  { directory: "screen-control", wasm: "screen_control.wasm", output: "screen-control.alp" },
] as const;

/**
 * 构建随 APK 内置的官方插件签名包（数据读取与独立屏幕能力）。
 *
 * 每个插件的 manifest 模版取自 plugins/<directory>/manifest.json，
 * 仅把 author.publicKey 替换为由签名私钥派生的公钥；wasm 取自
 * plugins/target/wasm32-unknown-unknown/release/ 并经 validateReferenceWasm
 * 校验 ABI；schema 列表从 manifest 的 capabilities.provides[].schema 读取，
 * 从 plugins/schemas/ 逐字节拷入包内。构建结果为确定性 .alp，写入
 * outputDirectory 下的固定文件名。
 */
export async function buildBundledPlugins(
  repositoryRoot: string,
  keyFile: string | undefined,
  outputDirectory: string,
): Promise<BuiltPackage[]> {
  // 统一取一次签名私钥；所有包共用同一身份公钥
  const privateKey = await referenceSigningKey(false, keyFile);
  try {
    const publicKey = bytesToBase64url(ed25519.getPublicKey(privateKey));
    const built: BuiltPackage[] = [];
    for (const plugin of BUNDLED_PLUGINS) {
      built.push(await buildBundledPlugin(repositoryRoot, plugin, publicKey, privateKey, outputDirectory));
    }
    return built;
  } finally {
    // 无论成功与否都要清零私钥，避免密钥残留在内存里
    privateKey.fill(0);
  }
}

/** 构建单个内置插件：准备暂存目录、组装 manifest、调用 buildPackage 并写出 .alp */
async function buildBundledPlugin(
  repositoryRoot: string,
  plugin: (typeof BUNDLED_PLUGINS)[number],
  publicKey: string,
  privateKey: Uint8Array,
  outputDirectory: string,
): Promise<BuiltPackage> {
  // manifest 模版来自仓库内 plugins/<directory>/manifest.json
  const template = JSON.parse(
    await readFile(join(repositoryRoot, "plugins", plugin.directory, "manifest.json"), "utf8"),
  );
  const manifest: PluginManifest = {
    ...template,
    // 公钥必须与签名私钥配对，模版里的占位公钥不能进包
    author: { algorithm: "Ed25519", publicKey },
  };

  // wasm 来自共享的 cargo target 目录，进包前先校验宿主 ABI
  const wasm = await readFile(
    join(repositoryRoot, "plugins/target/wasm32-unknown-unknown/release", plugin.wasm),
  );
  validateReferenceWasm(wasm);

  // schema 路径完全由 manifest 的 capabilities.provides[].schema 决定，不硬编码
  const schemaPaths = manifest.capabilities.provides.map((capability) => capability.schema);

  const staging = await mkdtemp(join(tmpdir(), `oai-${plugin.directory}-`));
  try {
    await mkdir(join(staging, "payload"), { recursive: true });
    await writeFile(join(staging, "payload", plugin.wasm), wasm);
    for (const schemaPath of schemaPaths) {
      // schemaPath 是包内相对路径（形如 "schemas/sms.json"），禁止绝对路径与 ".." 逃逸
      if (schemaPath.startsWith("/") || schemaPath.split("/").some((segment) => segment === "..")) {
        throw new Error("INVALID_SCHEMA_PATH:" + schemaPath);
      }
      // 包内路径的文件名对应仓库 plugins/schemas/ 下的同名源文件
      const fileName = schemaPath.split("/").pop()!;
      const source = await readFile(join(repositoryRoot, "plugins/schemas", fileName));
      const target = join(staging, schemaPath);
      await mkdir(dirname(target), { recursive: true });
      await writeFile(target, source);
    }
    const result = await buildPackage({ manifest, baseDirectory: staging, privateKey });
    await mkdir(outputDirectory, { recursive: true });
    await writeFile(join(outputDirectory, plugin.output), result.bytes);
    return result;
  } finally {
    // 暂存目录移入回收区，遵循仓库文件保留规范。
    const trash = join(tmpdir(), "open-android-intelligence-trash");
    await mkdir(trash, { recursive: true });
    await rename(staging, join(trash, basename(staging)));
  }
}
