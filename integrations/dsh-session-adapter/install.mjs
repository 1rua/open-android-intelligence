/** Install only through DSH's launcher-owned, lock-protected plugin APIs. */
import { createHash, randomUUID } from "node:crypto";
import { constants, closeSync, fstatSync, lstatSync, openSync, readFileSync, realpathSync } from "node:fs";
import { mkdir as mkdirAsync, rename as renameAsync } from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const OLD = "dsh-android-gateway";
const BUNDLE = "oai-dsh-session-adapter";
const excluded = new Set([".", "..", "node_modules", "build", "dist", ".gradle"]);
const statuses = {
  DSH_PLUGIN_MANAGER_UNAVAILABLE: 20,
  DSH_PROFILE_UNSAFE: 21,
  DSH_PROFILE_CHANGED: 22,
  DSH_INSTALL_INCOMPATIBLE: 23,
  DSH_INSTALL_TIMEOUT: 24,
  DSH_BUNDLE_INVALID: 25,
  DSH_INSTALL_FAILED: 26,
  DSH_INSTALL_RECOVERY_REQUIRED: 27,
};

export class DshInstallError extends Error {
  constructor(code) {
    super(Object.hasOwn(statuses, code) ? code : "DSH_INSTALL_FAILED");
    this.code = this.message;
  }
}
const fail = (code) => { throw new DshInstallError(code); };
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const plainObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);

function safePath(path, { directory = false, owner = true } = {}) {
  path = resolve(path);
  for (let current = path; ; current = dirname(current)) {
    const info = lstatSync(current);
    if (info.isSymbolicLink() || (current !== path && !info.isDirectory())) fail("DSH_PROFILE_UNSAFE");
    if (current === path && ((directory ? !info.isDirectory() : !info.isFile())
        || (owner && info.uid !== process.getuid()))) fail("DSH_PROFILE_UNSAFE");
    if (dirname(current) === current) return path;
  }
}

function safeRead(path, limit = 8 * 1024 * 1024) {
  safePath(path);
  const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const info = fstatSync(fd);
    if (!info.isFile() || info.uid !== process.getuid() || info.size > limit) fail("DSH_PROFILE_UNSAFE");
    const bytes = readFileSync(fd);
    if (bytes.length > limit) fail("DSH_PROFILE_UNSAFE");
    return bytes;
  } finally {
    closeSync(fd);
  }
}

function safeSnapshot(path, limit = 8 * 1024 * 1024) {
  try {
    safePath(path);
    const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
      const info = fstatSync(fd);
      if (!info.isFile() || info.uid !== process.getuid() || info.size > limit) fail("DSH_PROFILE_UNSAFE");
      const bytes = readFileSync(fd);
      if (bytes.length > limit) fail("DSH_PROFILE_UNSAFE");
      return { present: true, bytes, mode: info.mode & 0o777 };
    } finally {
      closeSync(fd);
    }
  } catch (error) {
    if (error?.code === "ENOENT") return { present: false };
    throw error;
  }
}

function safeDirectory(path, { privateMode = false } = {}) {
  path = safePath(path, { directory: true });
  const info = lstatSync(path);
  if (privateMode && (info.mode & 0o077) !== 0) fail("DSH_PROFILE_UNSAFE");
  return path;
}

function sameSnapshot(left, right) {
  return left.present === right.present
    && (!left.present || (left.mode === right.mode && hash(left.bytes) === hash(right.bytes)));
}

function profileSnapshot(directory) {
  safeDirectory(directory);
  return {
    manifest: safeSnapshot(join(directory, "package.json")),
    lockfile: safeSnapshot(join(directory, "pnpm-lock.yaml"), 32 * 1024 * 1024),
  };
}

function fileSummary(snapshot) {
  return snapshot.present ? { present: true, sha256: hash(snapshot.bytes), mode: snapshot.mode } : { present: false };
}

async function saveFailureSnapshot(api, backup, failed, original) {
  const recovery = join(backup, `recovery-${randomUUID()}`);
  await mkdirAsync(recovery, { mode: 0o700 });
  safeDirectory(recovery, { privateMode: true });
  for (const [name, snapshot] of [["package.json", failed.manifest], ["pnpm-lock.yaml", failed.lockfile]]) {
    if (snapshot.present) await api.writeFileAtomic(join(recovery, name), snapshot.bytes, { mode: 0o600 });
  }
  const state = {
    version: 1,
    original: { "package.json": fileSummary(original.manifest), "pnpm-lock.yaml": fileSummary(original.lockfile) },
    failed: { "package.json": fileSummary(failed.manifest), "pnpm-lock.yaml": fileSummary(failed.lockfile) },
  };
  await api.writeFileAtomic(join(recovery, "state.json"), Buffer.from(JSON.stringify(state, null, 2) + "\n"), { mode: 0o600 });
  return recovery;
}

async function restoreProfile(api, directory, backup, original) {
  try {
    safeDirectory(backup, { privateMode: true });
    const failed = profileSnapshot(directory);
    const manifestChanged = !sameSnapshot(failed.manifest, original.manifest);
    const lockfileChanged = !sameSnapshot(failed.lockfile, original.lockfile);
    if (!manifestChanged && !lockfileChanged) return;

    const recovery = await saveFailureSnapshot(api, backup, failed, original);
    const current = profileSnapshot(directory);
    if (!sameSnapshot(current.manifest, failed.manifest) || !sameSnapshot(current.lockfile, failed.lockfile)) {
      throw new Error("profile changed during recovery snapshot");
    }

    const manifestPath = join(directory, "package.json");
    if (manifestChanged) {
      if (!sameSnapshot(safeSnapshot(manifestPath), failed.manifest) || !original.manifest.present) {
        throw new Error("manifest changed during recovery");
      }
      await api.writeFileAtomic(manifestPath, original.manifest.bytes, { mode: original.manifest.mode });
    }

    const lockfilePath = join(directory, "pnpm-lock.yaml");
    if (lockfileChanged) {
      if (!sameSnapshot(safeSnapshot(lockfilePath, 32 * 1024 * 1024), failed.lockfile)) {
        throw new Error("lockfile changed during recovery");
      }
      if (original.lockfile.present) {
        await api.writeFileAtomic(lockfilePath, original.lockfile.bytes, { mode: original.lockfile.mode });
      } else if (failed.lockfile.present) {
        safeDirectory(recovery, { privateMode: true });
        const retainedLockfile = join(recovery, `generated-pnpm-lock-${randomUUID()}.yaml`);
        if (safeSnapshot(retainedLockfile, 32 * 1024 * 1024).present) {
          throw new Error("recovery lockfile destination already exists");
        }
        await renameAsync(lockfilePath, retainedLockfile);
        if (!sameSnapshot(safeSnapshot(retainedLockfile, 32 * 1024 * 1024), failed.lockfile)
            || safeSnapshot(lockfilePath, 32 * 1024 * 1024).present) {
          throw new Error("generated lockfile could not be safely retained");
        }
      }
    }

    const restored = profileSnapshot(directory);
    if (!sameSnapshot(restored.manifest, original.manifest) || !sameSnapshot(restored.lockfile, original.lockfile)) {
      throw new Error("profile recovery verification failed");
    }
  } catch {
    throw new DshInstallError("DSH_INSTALL_RECOVERY_REQUIRED");
  }
}

function optionalLockfile(directory) {
  try {
    return safeRead(join(directory, "pnpm-lock.yaml"), 32 * 1024 * 1024);
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
}

function validName(name) {
  return typeof name === "string" && name.length > 0 && name.trim() === name
    && !excluded.has(name) && !/[\/\\\x00-\x1f\x7f]/.test(name);
}

function bundlesOf(manifest, includeUninstalled = false) {
  if (!plainObject(manifest)) fail("DSH_PROFILE_UNSAFE");
  const dsh = manifest.dsh === undefined ? {} : manifest.dsh;
  if (!plainObject(dsh)) fail("DSH_PROFILE_UNSAFE");
  const profile = dsh.profile === undefined ? {} : dsh.profile;
  if (!plainObject(profile)) fail("DSH_PROFILE_UNSAFE");
  const bundles = profile.bundles === undefined ? [] : profile.bundles;
  if (!Array.isArray(bundles) || !bundles.every((name) => typeof name === "string")
      || (!includeUninstalled && !bundles.some((name) => name === OLD || name === BUNDLE))) {
    fail("DSH_PROFILE_UNSAFE");
  }
  return bundles;
}

function relativeArgument(cwd, argument) {
  if (typeof argument !== "string" || argument.length === 0 || isAbsolute(argument) || argument.includes("\0")) {
    fail("DSH_PROFILE_UNSAFE");
  }
  return resolve(cwd, argument);
}

function within(parent, child) {
  const path = relative(parent, child);
  return path === "" || (path !== ".." && !path.startsWith("../") && !isAbsolute(path));
}

async function officialApis(launcher, directory) {
  try {
    safePath(launcher, { owner: false });
    const anchor = join(dirname(dirname(launcher)), "package.json");
    safePath(anchor, { owner: false });
    if (JSON.parse(readFileSync(anchor, "utf8")).name !== "@deepseek-ai/dsh"
        || dirname(launcher).split(/[\/\\]/).at(-1) !== "lib"
        || launcher.split(/[\/\\]/).at(-1) !== "bin.js"
        || realpathSync(launcher) !== launcher || within(directory, launcher)) {
      fail("DSH_PLUGIN_MANAGER_UNAVAILABLE");
    }
    // In particular, never use createRequire(profile/package.json), cwd, the
    // helper's own require scope, NODE_PATH, or a profile dependency fallback.
    const require = createRequire(launcher);
    const names = ["@deepseek-ai/dsh-plugin-manager/operations", "@deepseek-ai/dsh-atomic-write", "@deepseek-ai/dsh-app-boot"];
    const paths = names.map((name) => {
      const path = require.resolve(name);
      const real = realpathSync(path);
      if (within(directory, path) || within(directory, real)) fail("DSH_PLUGIN_MANAGER_UNAVAILABLE");
      return pathToFileURL(real).href;
    });
    const [operations, atomic, boot] = await Promise.all(paths.map((path) => import(path)));
    if (typeof operations.runProfilePnpm !== "function" || typeof atomic.withFileLock !== "function"
        || typeof atomic.writeFileAtomic !== "function"
        || typeof boot.readProfileManifest !== "function" || typeof boot.writeProfileBundles !== "function") {
      fail("DSH_PLUGIN_MANAGER_UNAVAILABLE");
    }
    return { runProfilePnpm: operations.runProfilePnpm, withFileLock: atomic.withFileLock,
      writeFileAtomic: atomic.writeFileAtomic, readProfileManifest: boot.readProfileManifest,
      writeProfileBundles: boot.writeProfileBundles, anchor };
  } catch {
    fail("DSH_PLUGIN_MANAGER_UNAVAILABLE");
  }
}

function verifyInput(directory, metadata, includeUninstalled) {
  const files = profileSnapshot(directory);
  if (!files.manifest.present || hash(files.manifest.bytes) !== metadata.files["package.json"]
      || (files.lockfile.present ? hash(files.lockfile.bytes) : null) !== metadata.files["pnpm-lock.yaml"]) {
    fail("DSH_PROFILE_CHANGED");
  }
  bundlesOf(JSON.parse(files.manifest.bytes.toString("utf8")), includeUninstalled);
  return files;
}

function activeBundles(bundles) {
  let active = false;
  const next = bundles.flatMap((name) => {
    if (name !== OLD && name !== BUNDLE) return [name];
    if (active) return [];
    active = true;
    return [BUNDLE];
  });
  if (!active) next.push(BUNDLE);
  return next;
}

export async function installDshAdapter({ cwd = process.cwd(), launcher, home, profile, backup, signal,
  includeUninstalled = false } = {}) {
  try {
    if (!validName(profile) || typeof includeUninstalled !== "boolean") fail("DSH_PROFILE_UNSAFE");
    cwd = resolve(cwd);
    launcher = relativeArgument(cwd, launcher);
    home = relativeArgument(cwd, home);
    backup = relativeArgument(cwd, backup);
    safePath(home, { directory: true });
    const profiles = safePath(join(home, "profiles"), { directory: true });
    const directory = safePath(join(profiles, profile), { directory: true });
    safePath(join(directory, "package.json"));
    safeDirectory(backup, { privateMode: true });
    const metadata = JSON.parse(safeRead(join(backup, "metadata.json")));
    const recordedMode = metadata.includeUninstalled === undefined ? false : metadata.includeUninstalled;
    const bundle = safePath(dirname(fileURLToPath(import.meta.url)), { directory: true });
    if (JSON.parse(safeRead(join(bundle, "package.json"))).name !== BUNDLE) fail("DSH_BUNDLE_INVALID");
    if (metadata.version !== 1 || metadata.profile !== profile || !plainObject(metadata.files)
        || recordedMode !== includeUninstalled
        || relativeArgument(cwd, metadata.home) !== home
        || relativeArgument(cwd, metadata.directory) !== directory
        || relativeArgument(cwd, metadata.launcher) !== launcher
        || relativeArgument(cwd, metadata.bundle) !== bundle
        || hash(safeRead(join(backup, "package.json"))) !== metadata.files["package.json"]
        || (optionalLockfile(backup) === null ? null : hash(optionalLockfile(backup))) !== metadata.files["pnpm-lock.yaml"]) {
      fail("DSH_PROFILE_CHANGED");
    }
    const api = await officialApis(launcher, directory);
    const deadline = AbortSignal.timeout(90_000);
    const bounded = signal ? AbortSignal.any([signal, deadline]) : deadline;
    await api.withFileLock(join(directory, "package.json"), async () => {
      // Hash validation belongs under the official write lock; the Python
      // snapshot may be stale by the time this process finally acquires it.
      safePath(home, { directory: true });
      safePath(profiles, { directory: true });
      verifyInput(directory, metadata, includeUninstalled);
      const before = api.readProfileManifest("dsh", directory);
      bundlesOf(before, includeUninstalled);
      const original = verifyInput(directory, metadata, includeUninstalled);
      if (bounded.aborted) fail("DSH_INSTALL_TIMEOUT");
      try {
        const result = await api.runProfilePnpm({ profile, dir: directory, installAnchor: api.anchor, cwd, home },
          ["add", "file:" + relative(cwd, bundle)],
          { execution: "service", outputBytes: 8192, activateNewBundles: false,
            idleTimeoutMs: 30_000, lookupTimeoutMs: 10_000, signal: bounded });
        // Never forward output or logPath. Timeout wins even over an exitCode 0.
        if (result?.timedOut || result?.kind === "timeout" || bounded.aborted) fail("DSH_INSTALL_TIMEOUT");
        if (Array.isArray(result?.incompatible) && result.incompatible.length > 0) fail("DSH_INSTALL_INCOMPATIBLE");
        if (result?.exitCode !== 0) fail("DSH_INSTALL_FAILED");
        safePath(home, { directory: true });
        safePath(profiles, { directory: true });
        safePath(directory, { directory: true });
        safeRead(join(directory, "package.json"));
        const manifest = api.readProfileManifest("dsh", directory);
        const bundles = bundlesOf(manifest, includeUninstalled);
        const next = activeBundles(bundles);
        if (bundles.length !== next.length || bundles.some((name, index) => name !== next[index])) {
          safePath(join(directory, "package.json"));
          await api.writeProfileBundles(directory, manifest, next);
        }
        if (bounded.aborted) fail("DSH_INSTALL_TIMEOUT");
      } catch (error) {
        await restoreProfile(api, directory, backup, original);
        if (bounded.aborted) fail("DSH_INSTALL_TIMEOUT");
        throw error;
      }
    }, { waitMs: 5_000, signal: bounded });
  } catch (error) {
    if (error instanceof DshInstallError) throw error;
    fail(error?.name === "TimeoutError" || error?.name === "AbortError" ? "DSH_INSTALL_TIMEOUT" : "DSH_INSTALL_FAILED");
  }
}

function argumentsOf(args) {
  const options = {};
  for (let i = 0; i < args.length; i += 1) {
    if (args[i] === "--include-uninstalled") {
      if (Object.hasOwn(options, "includeUninstalled")) fail("DSH_PROFILE_UNSAFE");
      options.includeUninstalled = true;
      continue;
    }
    if (!["--launcher", "--home", "--profile", "--backup"].includes(args[i])
        || !args[i + 1] || Object.hasOwn(options, args[i].slice(2))) fail("DSH_PROFILE_UNSAFE");
    const name = args[i].slice(2);
    options[name] = args[++i];
  }
  return options;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const cancellation = new AbortController();
  process.once("SIGTERM", () => cancellation.abort());
  process.once("SIGINT", () => cancellation.abort());
  try {
    await installDshAdapter({ ...argumentsOf(process.argv.slice(2)), signal: cancellation.signal });
  } catch (error) {
    const code = error instanceof DshInstallError ? error.code : "DSH_INSTALL_FAILED";
    process.stderr.write(code + "\n");
    process.exitCode = statuses[code];
  }
}
