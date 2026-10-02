import { createHash, randomBytes } from "node:crypto";
import { readFile } from "node:fs/promises";
import { basename, join, normalize, posix } from "node:path";
import { fileURLToPath } from "node:url";
import JSZip from "jszip";
import canonicalize from "canonicalize";
import { ed25519 } from "@noble/curves/ed25519.js";
import type { PluginManifest } from "./manifest.js";

const FIXED_ZIP_DATE = new Date("1980-01-01T00:00:00Z");

export type BuildOptions = {
  manifest: PluginManifest;
  baseDirectory: string;
  privateKey: Uint8Array; // 32 bytes Ed25519 seed
};

export type FileEntry = {
  path: string;
  sha256: string;
  size: number;
};

export type BuiltPackage = {
  bytes: Uint8Array;
  sha256: string;
  manifest: PluginManifest;
  files: FileEntry[];
};

/**
 * Build a deterministic `.alp` package from a manifest and a base directory.
 *
 * Determinism rules (per device-plugin-package-v1):
 * - entries sorted by UTF-8 path bytes ascending
 * - all entries STORED (no compression)
 * - fixed timestamp 1980-01-01T00:00:00Z
 * - no directories, symlinks, absolute paths, `.` or `..` segments
 * - manifest.json and files.json canonicalized with RFC 8785
 */
export async function buildPackage(options: BuildOptions): Promise<BuiltPackage> {
  const { manifest, baseDirectory, privateKey } = options;
  validatePluginId(manifest.plugin.id);
  validatePaths(baseDirectory);

  const root = normalize(fileURLToPath(new URL(baseDirectory, import.meta.url)));
  const listed = await listFiles(root);
  const payloadFiles = listed.filter(
    (p) =>
      p !== "manifest.json" &&
      p !== "files.json" &&
      p !== "signature.ed25519",
  );

  const files: FileEntry[] = [];
  for (const relativePath of payloadFiles.sort(compareUtf8Bytes)) {
    const absolutePath = join(root, relativePath);
    const bytes = await readFile(absolutePath);
    validatePathSegments(relativePath);
    if (bytes.length === 0 && relativePath.endsWith("/")) {
      throw new Error("DIRECTORY_ENTRY_NOT_ALLOWED:" + relativePath);
    }
    files.push({
      path: relativePath,
      sha256: sha256Hex(bytes),
      size: bytes.length,
    });
  }

  const canonicalManifest = canonicalize(manifest);
  if (canonicalManifest == null) throw new Error("MANIFEST_CANONICALIZATION_FAILED");
  const canonicalFiles = canonicalize(files);
  if (canonicalFiles == null) throw new Error("FILES_CANONICALIZATION_FAILED");

  const manifestBytes = new TextEncoder().encode(canonicalManifest);
  const filesBytes = new TextEncoder().encode(canonicalFiles);

  const signatureInput = buildSignatureInput(manifestBytes, filesBytes);
  const signatureBytes = ed25519.sign(signatureInput, privateKey);
  const signatureText = bytesToBase64url(signatureBytes) + "\n";
  const signatureTextBytes = new TextEncoder().encode(signatureText);

  const zip = new JSZip();
  const allEntries: { path: string; bytes: Uint8Array }[] = [
    { path: "manifest.json", bytes: manifestBytes },
    { path: "files.json", bytes: filesBytes },
    { path: "signature.ed25519", bytes: signatureTextBytes },
    ...(
      await Promise.all(
        files.map(async (entry) => ({
          path: entry.path,
          bytes: await readFile(join(root, entry.path)),
        })),
      )
    ),
  ];
  for (const { path, bytes } of allEntries.sort((a, b) => compareUtf8Bytes(a.path, b.path))) {
    addStoredFile(zip, path, bytes);
  }

  const bytes = await zip.generateAsync({
    type: "uint8array",
    compression: "STORE",
    platform: "UNIX",
  });

  return {
    bytes,
    sha256: sha256Hex(bytes),
    manifest,
    files,
  };
}

export function buildSignatureInput(
  canonicalManifestBytes: Uint8Array,
  canonicalFilesBytes: Uint8Array,
): Uint8Array {
  const prefix = new TextEncoder().encode("OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1\n");
  const newline = new TextEncoder().encode("\n");
  const total = new Uint8Array(
    prefix.length + canonicalManifestBytes.length + newline.length + canonicalFilesBytes.length,
  );
  let offset = 0;
  total.set(prefix, offset);
  offset += prefix.length;
  total.set(canonicalManifestBytes, offset);
  offset += canonicalManifestBytes.length;
  total.set(newline, offset);
  offset += newline.length;
  total.set(canonicalFilesBytes, offset);
  return total;
}

function addStoredFile(zip: JSZip, path: string, bytes: Uint8Array): void {
  zip.file(path, bytes, {
    compression: "STORE",
    date: FIXED_ZIP_DATE,
    unixPermissions: null,
    createFolders: false,
  });
}

async function listFiles(root: string): Promise<string[]> {
  const entries: string[] = [];
  const { readdir } = await import("node:fs/promises");
  const { join } = await import("node:path");
  async function walk(dir: string): Promise<void> {
    const items = await readdir(dir, { withFileTypes: true });
    for (const item of items) {
      const full = join(dir, item.name);
      if (item.isDirectory()) {
        await walk(full);
      } else if (item.isFile()) {
        entries.push(posix.normalize(full.slice(root.length + 1)));
      } else {
        throw new Error("NON_REGULAR_FILE:" + full);
      }
    }
  }
  await walk(root);
  return entries;
}

function validatePluginId(id: string): void {
  const re = /^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$/;
  if (!re.test(id)) throw new Error("INVALID_PLUGIN_ID:" + id);
}

function validatePaths(root: string): void {
  if (typeof root !== "string" || root.length === 0) {
    throw new Error("INVALID_BASE_DIRECTORY");
  }
}

function validatePathSegments(relativePath: string): void {
  if (relativePath.startsWith("/")) throw new Error("ABSOLUTE_PATH:" + relativePath);
  for (const segment of relativePath.split("/")) {
    if (segment === "" || segment === "." || segment === "..") {
      throw new Error("INVALID_PATH_SEGMENT:" + relativePath);
    }
    if (/\\/.test(segment)) throw new Error("BACKSLASH_IN_PATH:" + relativePath);
  }
}

function compareUtf8Bytes(a: string, b: string): number {
  const ab = new TextEncoder().encode(a);
  const bb = new TextEncoder().encode(b);
  const len = Math.min(ab.length, bb.length);
  for (let i = 0; i < len; i++) {
    if (ab[i] !== bb[i]) return ab[i]! - bb[i]!;
  }
  return ab.length - bb.length;
}

export function sha256Hex(bytes: Uint8Array): string {
  return createHash("sha256").update(bytes).digest("hex");
}

export function bytesToBase64url(bytes: Uint8Array): string {
  const base64 = Buffer.from(bytes).toString("base64");
  return base64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function base64urlToBytes(text: string): Uint8Array {
  const padded = text.padEnd(text.length + ((4 - (text.length % 4)) % 4), "=");
  const base64 = padded.replace(/-/g, "+").replace(/_/g, "/");
  return new Uint8Array(Buffer.from(base64, "base64"));
}

export function randomPrivateKey(): Uint8Array {
  return randomBytes(32);
}
