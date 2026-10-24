import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { buildBundledPlugins } from "./bundled-plugins.js";

// CLI 用法：npm run build:android-plugins -- <outputDirectory>，参数个数必须严格等于 1
const outputDirectory = process.argv[2];
if (!outputDirectory || process.argv.length !== 3) {
  throw new Error("Usage: npm run build:android-plugins -- <outputDirectory>");
}
const root = fileURLToPath(new URL("../..", import.meta.url));
const built = await buildBundledPlugins(root, process.env.OPEN_ANDROID_INTELLIGENCE_PLUGIN_SIGNING_KEY_FILE, resolve(outputDirectory));
for (const item of built) {
  console.log(`Bundled plugin: ${item.manifest.plugin.id}@${item.manifest.plugin.version} sha256:${item.sha256}`);
}
