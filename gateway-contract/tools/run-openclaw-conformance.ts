/**
 * OpenClaw conformance runner.
 *
 * Loads the built runtime from the immutable plugin checkout selected by
 * openclaw-plugin-pin.json, consumes this repository's shared vectors, and
 * emits the standard JSONL plus manifest. Hermes remains a separate Python
 * process; the two runners share no runtime binary.
 */

import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

import {
  conformanceArtifactDirectory,
  conformanceContractRoot,
  writeConformanceArtifacts,
  type ConformanceRecord,
} from "./conformance-artifacts.js";

const openClawImplementation = "openclaw-typescript" as const;
const toolsDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(toolsDirectory, "../..");

type OpenClawPluginPin = Readonly<{
  repository: string;
  version: string;
  ref: string;
  revision: string;
}>;

const loadPinnedGatewayCore = async (): Promise<{ createGatewayCore: () => { runSharedVectors: (root: string) => readonly ConformanceRecord[] } }> => {
  const pin = JSON.parse(readFileSync(join(repositoryRoot, "openclaw-plugin-pin.json"), "utf8")) as OpenClawPluginPin;
  if (!/^([0-9a-f]{40})$/.test(pin.revision)) throw new Error("OPENCLAW_PLUGIN_PIN_INVALID");

  const configuredRoot = process.env["OPENCLAW_PLUGIN_ROOT"]?.trim() ?? "";
  const pluginRoot = configuredRoot.length > 0
    ? resolve(configuredRoot)
    : join(repositoryRoot, ".openclaw-gateway-plugin");
  const checkout = spawnSync("git", ["-C", pluginRoot, "rev-parse", "HEAD"], { encoding: "utf8" });
  if (checkout.error !== undefined || checkout.status !== 0) {
    throw new Error(`OPENCLAW_PLUGIN_CHECKOUT_UNAVAILABLE:${pluginRoot}`);
  }
  const actualRevision = checkout.stdout.trim();
  if (actualRevision !== pin.revision) {
    throw new Error(`OPENCLAW_PLUGIN_REVISION_MISMATCH:${actualRevision}:${pin.revision}`);
  }

  const entry = join(pluginRoot, "runtime", "src", "core", "gateway-core.js");
  if (!existsSync(entry)) throw new Error(`OPENCLAW_PLUGIN_RUNTIME_MISSING:${entry}`);
  return import(pathToFileURL(entry).href) as Promise<{ createGatewayCore: () => { runSharedVectors: (root: string) => readonly ConformanceRecord[] } }>;
};

const main = async (): Promise<void> => {
  const contractRoot = conformanceContractRoot();
  const directory = conformanceArtifactDirectory();

  const { createGatewayCore } = await loadPinnedGatewayCore();
  const results = createGatewayCore().runSharedVectors(contractRoot);
  const records: ConformanceRecord[] = results.map((result) => ({
    vectorId: result.vectorId,
    operation: result.operation,
    implementation: result.implementation,
    status: result.status,
    resultHash: result.resultHash,
  }));

  writeConformanceArtifacts(openClawImplementation, records, directory, contractRoot);

  const failed = records.filter((record) => record.status !== "pass");
  process.stdout.write(
    `${openClawImplementation}: ${records.length - failed.length}/${records.length} pass\n`,
  );
  for (const record of records) {
    process.stdout.write(
      `${record.status}\t${record.vectorId}\t${record.operation}\t${record.resultHash}\n`,
    );
  }
  if (failed.length > 0) {
    process.stderr.write(`${openClawImplementation}: ${failed.length} vector case(s) failed\n`);
    process.exitCode = 1;
  }
};

void main().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : String(error);
  process.stderr.write(`openclaw-typescript: ${message}\n`);
  process.exitCode = 1;
});
