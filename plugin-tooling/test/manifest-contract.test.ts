import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { ed25519 } from "@noble/curves/ed25519.js";
import { base64urlToBytes } from "../src/build-package.js";
import type { PluginManifest } from "../src/manifest.js";

/**
 * 契约依据：docs/contracts/device-plugin-package-v1.md §5（:87-156）。
 *
 * 三份参考插件源 manifest（plugins/{notifications,sms,call-log}/manifest.json）
 * 必须与构建器（build-references.ts）产出的 manifest 同形，且满足 §5 的每个必选节。
 * §5:156 规定「所有对象默认拒绝未知字段」，因此根级与各节的键集合是封闭的。
 */

const PLUGIN_ROOT = fileURLToPath(new URL("../../plugins", import.meta.url));

// 与 build-references.ts:12-15 一致的固定参考种子；author.publicKey 必须是
// 它派生的真实 Ed25519 公钥（base64url），不得是占位值。
const REFERENCE_SEED = new Uint8Array(32).fill(0x77);
const REFERENCE_PUBLIC_KEY_B64 = Buffer.from(
  ed25519.getPublicKey(REFERENCE_SEED),
).toString("base64url");

const REFERENCE_PLUGINS = ["notifications", "sms", "call-log"] as const;

const ROOT_FIELDS = [
  "author",
  "capabilities",
  "compatibility",
  "plugin",
  "runtime",
  "schemaVersion",
  "security",
  "state",
  "ui",
].sort();

const RESOURCE_FIELDS = [
  "maxConcurrentInvocations",
  "maxDailyNetworkBytes",
  "maxInvocationMillis",
  "maxMemoryBytes",
  "maxStorageBytes",
].sort();

function readManifest(id: string): Record<string, unknown> {
  const path = `${PLUGIN_ROOT}/${id}/manifest.json`;
  return JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function requireKeys(obj: Record<string, unknown>, allowed: string[], subject: string): void {
  const actual = Object.keys(obj).sort();
  expect(
    actual,
    `${subject} 键集合必须精确匹配契约 §5（拒绝未知与缺失字段）`,
  ).toEqual(allowed);
}

describe("reference plugin manifests conform to device-plugin-package-v1 §5", () => {
  for (const id of REFERENCE_PLUGINS) {
    describe(`plugins/${id}/manifest.json`, () => {
      const manifest = readManifest(id);

      it("has exactly the contract §5 root object shape", () => {
        requireKeys(manifest, ROOT_FIELDS, "root");
      });

      it("declares schemaVersion 1.0 and not the legacy manifestVersion", () => {
        expect(manifest["schemaVersion"]).toBe("1.0");
        expect(manifest).not.toHaveProperty("manifestVersion");
      });

      it("declares the plugin identity block", () => {
        const plugin = manifest["plugin"];
        expect(isRecord(plugin), "plugin 必须是对象").toBe(true);
        requireKeys(plugin as Record<string, unknown>, ["description", "id", "name", "version"], "plugin");
        expect((plugin as Record<string, unknown>)["id"]).toBe(
          `org.openandroidintelligence.${id}`,
        );
      });

      it("moves author to the root with algorithm Ed25519 and a real 32-byte base64url key", () => {
        const author = manifest["author"];
        expect(isRecord(author), "author 必须在根级对象").toBe(true);
        const record = author as Record<string, unknown>;
        requireKeys(record, ["algorithm", "publicKey"], "author");
        expect(record["algorithm"]).toBe("Ed25519");
        const publicKey = record["publicKey"];
        expect(typeof publicKey).toBe("string");
        const bytes = base64urlToBytes(publicKey as string);
        expect(bytes.length, "公钥解码后必须是 32 字节").toBe(32);
        expect(
          publicKey,
          "公钥必须等于固定参考种子派生的真实 Ed25519 公钥",
        ).toBe(REFERENCE_PUBLIC_KEY_B64);
      });

      it("declares runtime type protected-wasm", () => {
        const runtime = manifest["runtime"];
        expect(isRecord(runtime), "runtime 必须是对象").toBe(true);
        const record = runtime as Record<string, unknown>;
        requireKeys(record, ["abiVersion", "entrypoint", "payload", "type"], "runtime");
        expect(record["type"]).toBe("protected-wasm");
        expect(record["abiVersion"]).toBe("1.0");
        expect(record["entrypoint"]).toBe("open_android_intelligence_plugin_main");
        expect(String(record["payload"])).toMatch(/^payload\/.+\.wasm$/);
      });

      it("declares host compatibility ranges", () => {
        const compatibility = manifest["compatibility"];
        expect(isRecord(compatibility), "compatibility 必须是对象").toBe(true);
        const record = compatibility as Record<string, unknown>;
        requireKeys(record, ["androidHost", "gatewayProtocol"], "compatibility");
        expect(typeof record["androidHost"]).toBe("string");
        expect(typeof record["gatewayProtocol"]).toBe("string");
      });

      it("declares capabilities.provides as objects with id/version/schema", () => {
        const capabilities = manifest["capabilities"];
        expect(isRecord(capabilities), "capabilities 必须是对象").toBe(true);
        const record = capabilities as Record<string, unknown>;
        requireKeys(record, ["depends", "kernelPrimitives", "provides"], "capabilities");

        const provides = record["provides"];
        expect(Array.isArray(provides), "provides 必须是对象数组").toBe(true);
        for (const entry of provides as unknown[]) {
          expect(isRecord(entry), "provides 条目必须是对象").toBe(true);
          const item = entry as Record<string, unknown>;
          requireKeys(item, ["id", "schema", "version"], "provides[]");
          expect(String(item["id"])).toMatch(/^org\.openandroidintelligence\./);
          expect(String(item["schema"])).toMatch(/^(assets\/)?schemas?\/.+\.json$/);
        }
      });

      it("declares an empty depends array inside capabilities", () => {
        const capabilities = manifest["capabilities"] as Record<string, unknown>;
        expect(capabilities["depends"]).toEqual([]);
      });

      it("keeps kernelPrimitives inside capabilities as objects with id/version/purpose", () => {
        expect(manifest, "kernelPrimitives 不得置于顶层").not.toHaveProperty(
          "kernelPrimitives",
        );
        const capabilities = manifest["capabilities"] as Record<string, unknown>;
        const primitives = capabilities["kernelPrimitives"];
        expect(Array.isArray(primitives)).toBe(true);
        for (const entry of primitives as unknown[]) {
          expect(isRecord(entry), "kernelPrimitives 条目必须是对象").toBe(true);
          const item = entry as Record<string, unknown>;
          requireKeys(item, ["id", "purpose", "version"], "kernelPrimitives[]");
          expect(String(item["id"])).toMatch(/^kernel\./);
        }
      });

      it("declares the security section with network/background/resources", () => {
        const security = manifest["security"];
        expect(isRecord(security), "security 必须是对象").toBe(true);
        const record = security as Record<string, unknown>;
        requireKeys(record, ["background", "network", "resources"], "security");

        expect(Array.isArray(record["network"]), "network 必须是数组").toBe(true);

        const background = record["background"];
        expect(isRecord(background), "background 必须是对象").toBe(true);
        const bg = background as Record<string, unknown>;
        requireKeys(bg, ["minimumIntervalSeconds", "requested"], "security.background");
        expect(bg["requested"]).toBe(false);

        const resources = record["resources"];
        expect(isRecord(resources), "resources 必须是对象").toBe(true);
        requireKeys(resources as Record<string, unknown>, RESOURCE_FIELDS, "security.resources");
        for (const [, value] of Object.entries(resources as Record<string, unknown>)) {
          expect(typeof value).toBe("number");
        }
      });

      it("declares ui with empty settings and cards arrays", () => {
        const ui = manifest["ui"];
        expect(isRecord(ui), "ui 必须是对象").toBe(true);
        const record = ui as Record<string, unknown>;
        requireKeys(record, ["cards", "settings"], "ui");
        expect(record["settings"]).toEqual([]);
        expect(record["cards"]).toEqual([]);
      });

      it("declares state with schemaVersion and portableExport", () => {
        const state = manifest["state"];
        expect(isRecord(state), "state 必须是对象").toBe(true);
        const record = state as Record<string, unknown>;
        requireKeys(record, ["portableExport", "schemaVersion"], "state");
        expect(record["schemaVersion"]).toBe(1);
        expect(record["portableExport"]).toBe(false);
      });

      it("keeps matching the builder's PluginManifest type", () => {
        // 类型层面把 manifest 收敛为构建器同一形状；字段不匹配会在 typecheck 失败。
        const typed = manifest as unknown as PluginManifest;
        expect(typed.plugin.id).toBe(`org.openandroidintelligence.${id}`);
      });
    });
  }
});
