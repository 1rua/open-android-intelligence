import { sha256Evidence } from "./check-dependency-lock.ts";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export type OpenClawPluginPin = Readonly<{
  repository: string;
  version: string;
  ref: string;
  revision: string;
}>;

/** Build every plugin-lock field from the repository's single pin document. */
export const openClawPluginLockFields = (pin: OpenClawPluginPin) => {
  if (!/^[a-z0-9_.-]+\/[a-z0-9_.-]+$/i.test(pin.repository)) {
    throw new Error("openclaw-plugin-pin.json has an invalid repository");
  }
  if (!pin.version.trim() || !pin.ref.trim() || !/^[a-f0-9]{40}$/.test(pin.revision)) {
    throw new Error("openclaw-plugin-pin.json must contain a version, ref and lowercase 40-hex revision");
  }

  const checkout = ".openclaw-gateway-plugin";
  const installRef = `git:github.com/${pin.repository}@${pin.revision}`;
  const verifyCommand = [
    `if git -C ${checkout} rev-parse --git-dir >/dev/null 2>&1; then git -C ${checkout} diff --quiet && git -C ${checkout} diff --cached --quiet && test -z "$(git -C ${checkout} ls-files --others --exclude-standard)"; else git clone https://github.com/${pin.repository}.git ${checkout}; fi`,
    `git -C ${checkout} fetch origin ${pin.revision}`,
    `test "$(git -C ${checkout} rev-parse FETCH_HEAD)" = ${pin.revision}`,
    `git -C ${checkout} checkout --detach ${pin.revision}`,
    `npm ci --prefix ${checkout} --ignore-scripts`,
    `OPENCLAW_PLUGIN_ROOT=${checkout} python3 tools/check-openclaw-plugin-pin.py`,
    `OPEN_ANDROID_GATEWAY_CONTRACT_ROOT="$PWD/gateway-contract" npm --prefix ${checkout} run contract:check`,
    `npm --prefix ${checkout} run typecheck`,
    `npm --prefix ${checkout} test`,
    `npm --prefix ${checkout} run build`,
    `git -C ${checkout} diff --exit-code -- runtime`,
    `test -z "$(git -C ${checkout} ls-files --others -- runtime)"`,
    `OPENCLAW_HOME=/tmp/oai-openclaw-home OPENCLAW_STATE_DIR=/tmp/oai-openclaw-state ${checkout}/node_modules/.bin/openclaw plugins install ${installRef} --force`,
    `OPENCLAW_HOME=/tmp/oai-openclaw-home OPENCLAW_STATE_DIR=/tmp/oai-openclaw-state ${checkout}/node_modules/.bin/openclaw plugins inspect open-android-intelligence-gateway --runtime --json > /tmp/oai-openclaw-runtime.json`,
    `node ${checkout}/tools/assert-openclaw-runtime.mjs < /tmp/oai-openclaw-runtime.json`,
  ].join(" && ");

  return {
    official_reference: `https://github.com/${pin.repository}`,
    immutable_version: `openclaw-gateway-plugin@${pin.version}; ref ${pin.ref}; commit ${pin.revision}`,
    verify_command: verifyCommand,
  };
};

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const pluginPin = JSON.parse(readFileSync(resolve(projectRoot, "openclaw-plugin-pin.json"), "utf8")) as OpenClawPluginPin;

export const buildLockRows = (pin: OpenClawPluginPin) => [
  {
    decision_id: "MVP-DEP-ANDROID",
    official_reference: "https://developer.android.com/studio/releases/gradle-plugin",
    immutable_version: "AGP 8.9.2, Gradle 8.12, Kotlin 2.1.20, Android SDK 35, AndroidX 1.7.0, JDK 17",
    integrity: "PLACEHOLDER",
    license_review: "Apache-2.0 (AGP/Kotlin/AndroidX); reviewed",
    reviewer_time: "2026-10-24T00:00:00Z",
    evidence_expires_at: "2026-11-24T00:00:00Z",
    verify_command: "./gradlew --no-daemon check",
    status: "locked",
    blocks: "WP-02,WP-03,WP-08,WP-09",
  },
  {
    decision_id: "MVP-DEP-TSNET",
    official_reference: "https://github.com/tailscale/tailscale",
    immutable_version: "tailscale-v1.98.10, tag object 0ee734d3089846b27bc6ebcddd3d6ee5ec13e04d, commit 36550d57f4a4055246ef7412f4e650a012a465f1, AAR sha256 a654e487f88cc35a7baa238666151746f83dec12000a3078f6a83508df791e87, ABIs arm64-v8a+x86_64, NDK r27c archive sha256 verified 59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc, toolchain digest remains provisional",
    integrity: "PLACEHOLDER",
    license_review: "BSD-3-Clause; reviewed",
    reviewer_time: "2026-10-24T00:00:00Z",
    evidence_expires_at: "2026-11-24T00:00:00Z",
    verify_command: "./gradlew :tailnet-core:check",
    status: "locked",
    blocks: "WP-05,WP-09",
  },
  {
    decision_id: "MVP-DEP-BRIDGE",
    official_reference: "https://github.com/open-android-intelligence/open-android-intelligence",
    immutable_version: "bridge-runtime single-host production stack v1: Node 24.18.0, node:sqlite/SQLite 3.53.1, local Ed25519 verifier, same-DB lease coordinator, tsnet v1.98.10 Go sidecar",
    integrity: "PLACEHOLDER",
    license_review: "Apache-2.0 (project); BSD-3-Clause (Tailscale); reviewed",
    reviewer_time: "2026-10-24T00:00:00Z",
    evidence_expires_at: "2026-11-24T00:00:00Z",
    verify_command: "./legacy/bridge-runtime/deploy/verify-production.sh",
    status: "locked",
    blocks: "WP-06,WP-09",
  },
  {
    decision_id: "MVP-DEP-HERMES",
    official_reference: "https://github.com/open-android-intelligence/hermes-agent",
    immutable_version: "hermes@v0.9.0",
    integrity: "PLACEHOLDER",
    license_review: "MIT; reviewed",
    reviewer_time: "2026-10-24T00:00:00Z",
    evidence_expires_at: "2026-11-24T00:00:00Z",
    verify_command: "hermes plugin-load + smoke test",
    status: "locked",
    blocks: "WP-07,WP-09",
  },
  {
    decision_id: "MVP-DEP-OPENCLAW",
    official_reference: "https://github.com/openclaw/openclaw",
    immutable_version: "openclaw@2026.7.1-2; tag v2026.7.1-2; commit 0790d9f593ad30c940ed93b5872a8cf6d6f3cf8c; npm integrity sha512-ycF3yPcbjN6bUPeaUx6Mh6vze1hQWoD3CT/wWcmD7a8xaHHHRUaAlaq+lFxMHf1ssEgODVAwjlzYqp2twkYZ7g==",
    integrity: "PLACEHOLDER",
    license_review: "MIT; reviewed",
    reviewer_time: "2026-10-08T15:18:43Z",
    evidence_expires_at: "2026-11-08T15:18:43Z",
    verify_command: "npm exec --yes --package=openclaw@2026.7.1-2 -- openclaw --version",
    status: "locked",
    blocks: "WP-07,WP-09",
  },
  {
    decision_id: "MVP-DEP-OPENCLAW-PLUGIN",
    ...openClawPluginLockFields(pin),
    integrity: "PLACEHOLDER",
    license_review: "MIT; reviewed",
    reviewer_time: "2026-10-19T18:34:29Z",
    evidence_expires_at: "2026-11-18T18:34:29Z",
    status: "locked",
    blocks: "WP-07,WP-09",
  },
  {
    decision_id: "MVP-DEP-MODEL",
    official_reference: "https://platform.openai.com/docs/models",
    immutable_version: "agent-side model profile v1",
    integrity: "PLACEHOLDER",
    license_review: "agent-side (out of scope); acknowledged",
    reviewer_time: "2026-10-24T00:00:00Z",
    evidence_expires_at: "2026-11-24T00:00:00Z",
    verify_command: "agent-side verification (out of scope)",
    status: "locked",
    blocks: "WP-06,WP-08,WP-09",
  },
  {
    decision_id: "MVP-DEP-ARTIFACT",
    official_reference: "https://github.com/open-android-intelligence/open-android-intelligence",
    immutable_version: "artifact@project-internal",
    integrity: "PLACEHOLDER",
    license_review: "Apache-2.0 (project-internal); reviewed",
    reviewer_time: "2026-10-24T00:00:00Z",
    evidence_expires_at: "2026-11-24T00:00:00Z",
    verify_command: "./gradlew :artifact-ports:check",
    status: "locked",
    blocks: "WP-10",
  },
];

const rows = buildLockRows(pluginPin);

if (process.argv[1]?.endsWith("tools/compute-lock-hashes.ts")) {
  for (const row of rows) {
    const hash = sha256Evidence(row);
    console.log(`${row.decision_id}: sha256:${hash}`);
  }
}
