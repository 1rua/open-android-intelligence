import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { buildBundledNotifications } from "./bundled-notifications.js";

const output = process.argv[2];
if (!output || process.argv.length !== 3) {
  throw new Error("Usage: npm run build:android-notifications -- <output.alp>");
}
const root = fileURLToPath(new URL("../..", import.meta.url));
const built = await buildBundledNotifications(root, process.env.OPEN_ANDROID_INTELLIGENCE_PLUGIN_SIGNING_KEY_FILE, resolve(output));
console.log(`Bundled notifications: ${built.manifest.plugin.id}@${built.manifest.plugin.version} sha256:${built.sha256}`);
