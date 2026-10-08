import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Cross-host conformance artifact contract.
 *
 * The OpenClaw (TypeScript) and Hermes (Python) runners are separate processes
 * that consume the same shared vector documents and the same single dispatched
 * fixture registry. Each runner writes its own JSONL result file plus a
 * manifest; this module reads them back and regenerates them when they are
 * missing or stale so a bare `vitest run` still proves the gate.
 */

export type ConformanceImplementation = "openclaw-typescript" | "hermes-python";

export const CONFORMANCE_IMPLEMENTATIONS: readonly ConformanceImplementation[] = [
  "openclaw-typescript",
  "hermes-python",
];

export const CONFORMANCE_VECTOR_FILE_NAMES = [
  "request-signatures.json",
  "protocol-negotiation.json",
  "auth-sessions.json",
  "attachments.json",
  "sse-events.json",
  "device-requests.json",
] as const;

export const CONFORMANCE_FIXTURE_FILE_NAMES = [
  "dispatched-schema-fixtures.json",
  "dispatched-schema-fixtures-1.0.0.schema.json",
] as const;

export const CONFORMANCE_MANIFEST_FORMAT_VERSION = "1.0.0";

export type ConformanceManifest = Readonly<{
  formatVersion: string;
  implementation: ConformanceImplementation;
  caseCount: number;
  vectorDigests: Readonly<Record<string, string>>;
  recordsDigest: string;
}>;

export type ConformanceRecord = Readonly<{
  vectorId: string;
  operation: string;
  implementation: ConformanceImplementation;
  status: "pass" | "fail";
  resultHash: string;
}>;

export type ConformanceArtifacts = Readonly<{
  manifest: ConformanceManifest;
  records: readonly ConformanceRecord[];
}>;

const toolsDirectory = dirname(fileURLToPath(import.meta.url));
const contractRootDirectory = dirname(toolsDirectory);

export const conformanceContractRoot = (): string => contractRootDirectory;

export const conformanceArtifactDirectory = (): string =>
  process.env["OPEN_ANDROID_INTELLIGENCE_CONFORMANCE_DIR"] === undefined ||
  process.env["OPEN_ANDROID_INTELLIGENCE_CONFORMANCE_DIR"] === ""
    ? join(contractRootDirectory, ".artifacts", "conformance")
    : resolve(process.env["OPEN_ANDROID_INTELLIGENCE_CONFORMANCE_DIR"]!);

const sha256OfFile = (path: string): string =>
  `sha256:${createHash("sha256").update(readFileSync(path)).digest("hex")}`;

/**
 * Digests every input the runners consume. A result set is only acceptable
 * when these digests still match the files on disk.
 */
export const conformanceInputDigests = (
  contractRoot: string = conformanceContractRoot(),
  implementation?: ConformanceImplementation,
): Readonly<Record<string, string>> => {
  const digests: Record<string, string> = {};
  const vectorsDirectory = join(contractRoot, "vectors");
  for (const fileName of CONFORMANCE_VECTOR_FILE_NAMES) {
    digests[`vectors/${fileName}`] = sha256OfFile(join(vectorsDirectory, fileName));
  }
  for (const fileName of CONFORMANCE_FIXTURE_FILE_NAMES) {
    digests[`vectors/${fileName}`] = sha256OfFile(join(vectorsDirectory, fileName));
  }
  if (implementation === "openclaw-typescript") {
    digests["openclaw-plugin-pin.json"] = sha256OfFile(
      join(dirname(contractRootDirectory), "openclaw-plugin-pin.json"),
    );
  }
  return digests;
};

/**
 * The runners serialise their manifests with different key orders (Python uses
 * `sort_keys`), so digest maps are compared through a sorted projection.
 */
const sortedDigestMap = (digests: Readonly<Record<string, string>>): string =>
  JSON.stringify(
    Object.keys(digests)
      .sort()
      .map((key) => [key, digests[key]]),
  );

const artifactPaths = (
  directory: string,
  implementation: ConformanceImplementation,
): { records: string; manifest: string } => ({
  records: join(directory, `${implementation}.jsonl`),
  manifest: join(directory, `${implementation}.manifest.json`),
});

const isFile = (path: string): boolean => {
  try {
    return statSync(path).isFile();
  } catch {
    return false;
  }
};

const parseRecordLine = (
  line: string,
  implementation: ConformanceImplementation,
  path: string,
): ConformanceRecord => {
  const parsed: unknown = JSON.parse(line);
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new Error(`INVALID_CONFORMANCE_RECORD:${path}`);
  }
  const record = parsed as Record<string, unknown>;
  for (const key of ["vectorId", "operation", "implementation", "status", "resultHash"]) {
    if (typeof record[key] !== "string") {
      throw new Error(`INVALID_CONFORMANCE_RECORD:${path}`);
    }
  }
  if (record["implementation"] !== implementation) {
    throw new Error(`INVALID_CONFORMANCE_IMPLEMENTATION:${path}`);
  }
  if (record["status"] !== "pass" && record["status"] !== "fail") {
    throw new Error(`INVALID_CONFORMANCE_STATUS:${path}`);
  }
  if (!/^sha256:[0-9a-f]{64}$/.test(String(record["resultHash"]))) {
    throw new Error(`INVALID_CONFORMANCE_HASH:${path}`);
  }
  return {
    vectorId: String(record["vectorId"]),
    operation: String(record["operation"]),
    implementation,
    status: record["status"] === "pass" ? "pass" : "fail",
    resultHash: String(record["resultHash"]),
  };
};

export const readConformanceArtifacts = (
  implementation: ConformanceImplementation,
  directory: string = conformanceArtifactDirectory(),
  contractRoot: string = conformanceContractRoot(),
): ConformanceArtifacts => {
  const paths = artifactPaths(directory, implementation);
  if (!isFile(paths.records) || !isFile(paths.manifest)) {
    throw new Error(
      `CONFORMANCE_ARTIFACTS_MISSING:${implementation} run "npm run gateway:v2:conformance"`,
    );
  }

  const manifest = JSON.parse(readFileSync(paths.manifest, "utf8")) as ConformanceManifest;
  const expectedDigests = conformanceInputDigests(contractRoot, implementation);
  if (
    manifest.formatVersion !== CONFORMANCE_MANIFEST_FORMAT_VERSION ||
    manifest.implementation !== implementation ||
    sortedDigestMap(manifest.vectorDigests) !== sortedDigestMap(expectedDigests)
  ) {
    throw new Error(`CONFORMANCE_ARTIFACTS_STALE:${implementation}`);
  }

  // The manifest binds the exact JSONL bytes it was written for, so a
  // hand-edited result set is rejected instead of silently trusted.
  if (manifest.recordsDigest !== sha256OfFile(paths.records)) {
    throw new Error(`CONFORMANCE_ARTIFACTS_STALE:${implementation}`);
  }

  const lines = readFileSync(paths.records, "utf8")
    .split("\n")
    .filter((line) => line.trim().length > 0);
  if (lines.length !== manifest.caseCount) {
    throw new Error(`CONFORMANCE_ARTIFACTS_INCOMPLETE:${implementation}`);
  }

  return {
    manifest,
    records: lines.map((line) => parseRecordLine(line, implementation, paths.records)),
  };
};

const pythonCommand = (): string => {
  const configured = process.env["OPEN_ANDROID_INTELLIGENCE_PYTHON"];
  if (configured !== undefined && configured !== "") return configured;
  return process.platform === "win32" ? "python" : "python3";
};

const tsxCliPath = (): string => {
  const localPath = join(dirname(contractRootDirectory), "node_modules", "tsx", "dist", "cli.mjs");
  if (existsSync(localPath)) return localPath;
  throw new Error(`TSX_TOOLCHAIN_MISSING:${localPath}`);
};

const runnerCommand = (
  implementation: ConformanceImplementation,
): readonly string[] =>
  implementation === "openclaw-typescript"
    ? [process.execPath, tsxCliPath(), join(toolsDirectory, "run-openclaw-conformance.ts")]
    : [pythonCommand(), join(toolsDirectory, "run-hermes-conformance.py")];

/**
 * Regenerates one runner's artifacts by executing it as a separate process.
 * The two runners never share a runtime binary.
 */
export const generateConformanceArtifacts = (
  implementation: ConformanceImplementation,
  directory: string = conformanceArtifactDirectory(),
): void => {
  const [command, ...args] = runnerCommand(implementation);
  const result = spawnSync(command!, args, {
    cwd: dirname(contractRootDirectory),
    encoding: "utf8",
    env: { ...process.env, OPEN_ANDROID_INTELLIGENCE_CONFORMANCE_DIR: directory },
  });
  if (result.error !== undefined || result.status !== 0) {
    const detail = result.stderr?.trim() || result.stdout?.trim() || String(result.error);
    throw new Error(`CONFORMANCE_RUNNER_FAILED:${implementation}:${detail}`);
  }
};

const isFresh = (
  implementation: ConformanceImplementation,
  directory: string,
  contractRoot: string,
): boolean => {
  try {
    readConformanceArtifacts(implementation, directory, contractRoot);
    return true;
  } catch {
    return false;
  }
};

/**
 * Returns both runners' artifacts, regenerating any that are missing or stale.
 */
export const ensureConformanceArtifacts = (
  directory: string = conformanceArtifactDirectory(),
  contractRoot: string = conformanceContractRoot(),
): Readonly<Record<ConformanceImplementation, ConformanceArtifacts>> => {
  if (!existsSync(directory)) mkdirSync(directory, { recursive: true });
  for (const implementation of CONFORMANCE_IMPLEMENTATIONS) {
    if (!isFresh(implementation, directory, contractRoot)) {
      generateConformanceArtifacts(implementation, directory);
    }
  }
  const openclaw = readConformanceArtifacts("openclaw-typescript", directory, contractRoot);
  const hermes = readConformanceArtifacts("hermes-python", directory, contractRoot);
  return { "openclaw-typescript": openclaw, "hermes-python": hermes };
};

/**
 * Writes one runner's artifacts. Shared by both runner entry points so the
 * JSONL and manifest shapes cannot drift between implementations.
 */
export const writeConformanceArtifacts = (
  implementation: ConformanceImplementation,
  records: readonly ConformanceRecord[],
  directory: string,
  contractRoot: string,
): void => {
  mkdirSync(directory, { recursive: true });
  const paths = artifactPaths(directory, implementation);
  const jsonl = `${records
    .map(
      (record) =>
        JSON.stringify({
          vectorId: record.vectorId,
          operation: record.operation,
          implementation: record.implementation,
          status: record.status,
          resultHash: record.resultHash,
        }),
    )
    .join("\n")}\n`;
  writeFileSync(paths.records, jsonl, "utf8");
  const manifest: ConformanceManifest = {
    formatVersion: CONFORMANCE_MANIFEST_FORMAT_VERSION,
    implementation,
    caseCount: records.length,
    vectorDigests: conformanceInputDigests(contractRoot, implementation),
    recordsDigest: sha256OfFile(paths.records),
  };
  writeFileSync(paths.manifest, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
};
