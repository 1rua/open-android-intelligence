import { execFileSync, spawnSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { installDshAdapter } from "./install.mjs";

const OLD = "dsh-android-gateway";
const NEW = "oai-dsh-session-adapter";
const root = fileURLToPath(new URL("../../", import.meta.url));
const digest = (bytes) => createHash("sha256").update(bytes).digest("hex");
const readJson = (path) => JSON.parse(readFileSync(path, "utf8"));

function fixture({ bundles = ["base", OLD, "tail"], manifest: customManifest, includeUninstalled = false,
  result = { exitCode: 0 }, missingManager = false,
  missingAtomicWrite = false, lockMutation = "", mutatePnpmFiles = false, throwPnpm = false,
  throwActivation = false, casMutation = false, waitForAbort = false, initialLock = null,
  profileModes = { manifest: 0o600, lockfile: 0o600 } } = {}) {
  // Fixtures stay inside this worktree for review; no permanent deletion.
  const cwd = join(root, ".worktrees", "dsh-node-install-tests", randomUUID());
  const installation = join(cwd, "official");
  const launcher = join(installation, "lib", "bin.js");
  const home = join(cwd, "home");
  const directory = join(home, "profiles", "web");
  const backup = join(cwd, "backup");
  const trace = join(cwd, "calls.json");
  for (const path of [dirname(launcher), directory, backup]) mkdirSync(path, { recursive: true, mode: 0o700 });
  writeFileSync(launcher, "// launcher fixture\n");
  writeFileSync(join(installation, "package.json"), JSON.stringify({ name: "@deepseek-ai/dsh", type: "module" }));
  const manifest = customManifest !== undefined ? customManifest : { private: true, custom: { account: "unchanged" },
    dependencies: { [OLD]: "0.3.0", other: "1.0.0" },
    dsh: { keep: true, profile: { bundles, metadata: { user: "keep" } } } };
  const original = JSON.stringify(manifest);
  writeFileSync(join(directory, "package.json"), original, { mode: profileModes.manifest });
  const originalLock = initialLock === null ? null : Buffer.from(initialLock);
  if (originalLock !== null) writeFileSync(join(directory, "pnpm-lock.yaml"), originalLock, { mode: profileModes.lockfile });
  writeFileSync(join(backup, "package.json"), original, { mode: 0o600 });
  if (originalLock !== null) writeFileSync(join(backup, "pnpm-lock.yaml"), originalLock, { mode: 0o600 });
  const bundle = "integrations/dsh-session-adapter";
  writeFileSync(join(backup, "metadata.json"), JSON.stringify({ version: 1, profile: "web", includeUninstalled,
    home: relative(cwd, home), directory: relative(cwd, directory),
    launcher: relative(cwd, launcher), bundle: relative(cwd, join(root, bundle)),
    files: { "package.json": digest(original), "pnpm-lock.yaml": originalLock === null ? null : digest(originalLock) },
  }), { mode: 0o600 });
  function official(name, source, exports = "./index.js") {
    const path = join(installation, "node_modules", "@deepseek-ai", name);
    mkdirSync(path, { recursive: true });
    writeFileSync(join(path, "package.json"), JSON.stringify({ name: "@deepseek-ai/" + name, type: "module", exports }));
    writeFileSync(join(path, "index.js"), source);
  }
  const traceCode = `import {existsSync,readFileSync,writeFileSync} from 'node:fs';
    const trace=${JSON.stringify(trace)};
    function record(value){const records=existsSync(trace)?JSON.parse(readFileSync(trace)):[];records.push(value);writeFileSync(trace,JSON.stringify(records));}`;
  const atomicWrite = missingAtomicWrite ? "" : `
    export async function writeFileAtomic(path, contents, options) {
      if(!globalThis[trace])throw Error('atomic write outside lock');
      record({atomic:path});
      const {writeFile,rename} = await import('node:fs/promises');
      const temporary = path + '.atomic-test';
      await writeFile(temporary, contents, {mode: options.mode, flag: 'wx'});
      await rename(temporary, path);
      ${casMutation ? `if(path.includes('recovery-')&&path.endsWith('/package.json')){
        const target=join(${JSON.stringify(directory)},'package.json');
        const current=JSON.parse(readFileSync(target,'utf8'));
        current.userMetadata={concurrent:true};
        writeFileSync(target,JSON.stringify(current));
        writeFileSync(join(${JSON.stringify(directory)},'pnpm-lock.yaml'),'concurrent lock\\n');
      }` : ""}
    }`;
  official("dsh-atomic-write", `${traceCode}
    import {join} from 'node:path';
    export async function withFileLock(path, callback, options){record({lock:path,options});globalThis[trace]=true;try{${lockMutation}return await callback();}finally{globalThis[trace]=false;}}
    ${atomicWrite}`);
  official("dsh-app-boot", `${traceCode}
    import {join} from 'node:path';
    export function readProfileManifest(bin,dir){if(!globalThis[trace])throw Error('read outside lock');return JSON.parse(readFileSync(join(dir,'package.json')));}
    export function writeProfileBundles(dir,manifest,bundles){if(!globalThis[trace])throw Error('write outside lock');record({activate:bundles});const updated={...manifest,dsh:{...manifest.dsh,profile:{...manifest.dsh?.profile,bundles}}};writeFileSync(join(dir,'package.json'),JSON.stringify(updated));${throwActivation ? "throw Error('private activation failure');" : ""}return updated;}`);
  if (!missingManager) official("dsh-plugin-manager", `${traceCode}
    import {join} from 'node:path';
    export async function runProfilePnpm(context,args,options){if(!globalThis[trace])throw Error('install outside lock');record({context,args,options});
    ${mutatePnpmFiles ? `const target=join(context.dir,'package.json');
    const partial=JSON.parse(readFileSync(target,'utf8'));
    partial.dependencies ??= {};
    partial.dependencies[${JSON.stringify(NEW)}]='file:partial-install';
    partial.partialInstallState='failed-write';
    writeFileSync(target,JSON.stringify(partial));
    writeFileSync(join(context.dir,'pnpm-lock.yaml'),'partial pnpm lock\\n');` : ""}
    ${throwPnpm ? "throw Error('private pnpm failure');" : ""}
    ${waitForAbort ? "await new Promise(resolve=>options.signal.aborted?resolve():options.signal.addEventListener('abort',resolve,{once:true}));" : ""}
    const result=${JSON.stringify(result)};
    if(!${mutatePnpmFiles}&&result.exitCode===0&&!result.timedOut){const p=join(context.dir,'package.json');const manifest=JSON.parse(readFileSync(p));manifest.dependencies ??= {};manifest.dependencies[${JSON.stringify(NEW)}]=args[1];writeFileSync(p,JSON.stringify(manifest));}
    return result;}`, { "./operations": "./index.js" });
  return { cwd, home, profile: "web", launcher, backup, directory, trace, manifest, original, originalLock, profileModes };
}

function call(f, options = {}) {
  return installDshAdapter({ cwd: f.cwd, launcher: relative(f.cwd, f.launcher),
    home: relative(f.cwd, f.home), profile: f.profile, backup: relative(f.cwd, f.backup), ...options });
}

function recoveryDirectory(f) {
  const name = readdirSync(f.backup).find((entry) => entry.startsWith("recovery-"));
  return name ? join(f.backup, name) : null;
}

function expectOriginalProfile(f) {
  expect(readFileSync(join(f.directory, "package.json"))).toEqual(Buffer.from(f.original));
  expect(statSync(join(f.directory, "package.json")).mode & 0o777).toBe(f.profileModes.manifest);
  const lock = join(f.directory, "pnpm-lock.yaml");
  if (f.originalLock === null) expect(existsSync(lock)).toBe(false);
  else {
    expect(readFileSync(lock)).toEqual(f.originalLock);
    expect(statSync(lock).mode & 0o777).toBe(f.profileModes.lockfile);
  }
  const restored = readJson(join(f.directory, "package.json"));
  expect(restored.dsh.profile.bundles).toEqual(["base", OLD, "tail"]);
  expect(restored.custom).toEqual({ account: "unchanged" });
  expect(restored.dsh.profile.metadata).toEqual({ user: "keep" });
}

describe("bounded official DSH profile upgrade", () => {
  it.each([
    [{}, [NEW]],
    [{ dsh: {} }, [NEW]],
    [{ dsh: { keep: true, profile: { metadata: { keep: true } } } }, [NEW]],
    [{ dsh: { profile: { bundles: [] } } }, [NEW]],
    [{ dsh: { profile: { bundles: ["base", "base", "tail"] } } }, ["base", "base", "tail", NEW]],
  ])("installs into an explicitly opted-in fresh profile %j", async (fields, expected) => {
    const manifest = { private: true, custom: { account: "unchanged" }, ...fields };
    const f = fixture({ manifest, includeUninstalled: true });
    await call(f, { includeUninstalled: true });
    const installed = readJson(join(f.directory, "package.json"));
    expect(installed.dsh.profile.bundles).toEqual(expected);
    expect(installed.custom).toEqual({ account: "unchanged" });
    expect(installed.private).toBe(true);
    expect(installed.dsh.keep).toBe(fields.dsh?.keep);
    expect(installed.dsh.profile.metadata).toEqual(fields.dsh?.profile?.metadata);
    expect(installed.dependencies[NEW]).toMatch(/^file:/);
    expect(readFileSync(join(f.backup, "package.json"), "utf8")).toBe(f.original);
    expect(readJson(f.trace).filter((record) => record.context)).toHaveLength(1);
    expect(readJson(f.trace).filter((record) => record.activate)).toHaveLength(1);
  });

  it("rejects a fresh target without explicitly enabling first installation", async () => {
    const f = fixture({ manifest: { private: true } });
    await expect(call(f)).rejects.toMatchObject({ code: "DSH_PROFILE_UNSAFE" });
    expect(readFileSync(join(f.directory, "package.json"), "utf8")).toBe(f.original);
    expect(readJson(f.trace).some((record) => record.context)).toBe(false);
  });

  it.each([false, true])("binds first-install mode to the confirmed backup (%s)", async (recorded) => {
    const f = fixture({ includeUninstalled: recorded });
    await expect(call(f, { includeUninstalled: !recorded })).rejects.toMatchObject({ code: "DSH_PROFILE_CHANGED" });
    expect(existsSync(f.trace)).toBe(false);
    expect(readFileSync(join(f.directory, "package.json"), "utf8")).toBe(f.original);
  });

  it("keeps old upgrade backups without a mode field compatible", async () => {
    const f = fixture();
    const path = join(f.backup, "metadata.json");
    const { includeUninstalled: _mode, ...metadata } = readJson(path);
    writeFileSync(path, JSON.stringify(metadata));
    await call(f);
    expect(readJson(join(f.directory, "package.json")).dsh.profile.bundles).toEqual(["base", NEW, "tail"]);
  });

  it.each([
    null, [], { dsh: null }, { dsh: [] }, { dsh: { profile: null } },
    { dsh: { profile: [] } }, { dsh: { profile: { bundles: null } } },
    { dsh: { profile: { bundles: "base" } } }, { dsh: { profile: { bundles: ["base", 1] } } },
  ])("rejects malformed profile fields even in first-install mode: %j", async (manifest) => {
    const f = fixture({ manifest, includeUninstalled: true });
    await expect(call(f, { includeUninstalled: true })).rejects.toMatchObject({ code: "DSH_PROFILE_UNSAFE" });
    expect(readJson(f.trace).some((record) => record.context)).toBe(false);
  });

  it.each([
    ["failed install", { result: { exitCode: 1 } }, "DSH_INSTALL_FAILED"],
    ["incompatible install", { result: { exitCode: 1, incompatible: [{ name: "private" }] } }, "DSH_INSTALL_INCOMPATIBLE"],
    ["timed out install", { result: { exitCode: 1, timedOut: true } }, "DSH_INSTALL_TIMEOUT"],
    ["failed activation", { throwActivation: true, initialLock: Buffer.from("original lock\n") }, "DSH_INSTALL_FAILED"],
  ])("restores an unchanged fresh manifest after %s", async (_label, outcome, code) => {
    const f = fixture({ manifest: { private: true, custom: { account: "unchanged" } },
      includeUninstalled: true, mutatePnpmFiles: true, ...outcome });
    await expect(call(f, { includeUninstalled: true })).rejects.toMatchObject({ code, message: code });
    expect(readFileSync(join(f.directory, "package.json"), "utf8")).toBe(f.original);
    expect(readJson(join(f.directory, "package.json")).dsh).toBeUndefined();
    if (f.originalLock === null) expect(existsSync(join(f.directory, "pnpm-lock.yaml"))).toBe(false);
    else expect(readFileSync(join(f.directory, "pnpm-lock.yaml"))).toEqual(f.originalLock);
    expect(recoveryDirectory(f)).not.toBeNull();
    expect(readJson(join(recoveryDirectory(f), "package.json")).partialInstallState).toBe("failed-write");
  });

  it("holds the official lock and preserves unrelated metadata, old dependencies and other profiles", async () => {
    const f = fixture();
    const other = join(f.home, "profiles", "other");
    mkdirSync(other);
    writeFileSync(join(other, "package.json"), f.original);
    for (const name of ["master.key", "accounts.json", "history.json", "cordis.patch.yml"]) writeFileSync(join(f.directory, name), "old " + name);
    await call(f);
    const manifest = readJson(join(f.directory, "package.json"));
    expect(manifest).toEqual({ ...f.manifest, dependencies: { ...f.manifest.dependencies,
      [NEW]: "file:" + relative(f.cwd, join(root, "integrations/dsh-session-adapter")) },
      dsh: { ...f.manifest.dsh, profile: { ...f.manifest.dsh.profile, bundles: ["base", NEW, "tail"] } } });
    const records = readJson(f.trace);
    expect(records[0].lock).toBe(join(f.directory, "package.json"));
    const install = records.find((r) => r.context);
    expect(install.context).toEqual({ profile: "web", dir: f.directory,
      installAnchor: join(dirname(dirname(f.launcher)), "package.json"), cwd: f.cwd, home: f.home });
    expect(install.args).toEqual(["add", "file:" + relative(f.cwd, join(root, "integrations/dsh-session-adapter"))]);
    expect(install.options).toMatchObject({ execution: "service", outputBytes: 8192, activateNewBundles: false });
    expect(install.options.idleTimeoutMs).toBeGreaterThan(0);
    expect(install.options.lookupTimeoutMs).toBeGreaterThan(0);
    expect(readFileSync(join(other, "package.json"), "utf8")).toBe(f.original);
    for (const name of ["master.key", "accounts.json", "history.json", "cordis.patch.yml"]) expect(readFileSync(join(f.directory, name), "utf8")).toBe("old " + name);
  });

  it("never imports a manager installed only in the controlled profile", async () => {
    const f = fixture({ missingManager: true });
    const malicious = join(f.directory, "node_modules", "@deepseek-ai", "dsh-plugin-manager");
    mkdirSync(malicious, { recursive: true });
    writeFileSync(join(malicious, "package.json"), JSON.stringify({ name: "@deepseek-ai/dsh-plugin-manager", type: "module", exports: { "./operations": "./index.js" } }));
    const marker = join(f.directory, "profile-code-executed");
    writeFileSync(join(malicious, "index.js"), `import {writeFileSync} from 'node:fs';writeFileSync(${JSON.stringify(marker)},'bad');export async function runProfilePnpm(){return {exitCode:0};}`);
    await expect(call(f)).rejects.toMatchObject({ code: "DSH_PLUGIN_MANAGER_UNAVAILABLE", message: "DSH_PLUGIN_MANAGER_UNAVAILABLE" });
    expect(existsSync(marker)).toBe(false);
    expect(existsSync(f.trace)).toBe(false);
    expect(readFileSync(join(f.directory, "package.json"), "utf8")).toBe(f.original);
  });

  it("reports unsupported official APIs without trying a fallback", async () => {
    const f = fixture();
    writeFileSync(join(dirname(dirname(f.launcher)), "node_modules", "@deepseek-ai", "dsh-app-boot", "index.js"), "export function readProfileManifest() {}\n");
    await expect(call(f)).rejects.toMatchObject({ code: "DSH_PLUGIN_MANAGER_UNAVAILABLE" });
    expect(existsSync(f.trace)).toBe(false);
  });

  it("reports a missing official atomic-write API without trying a fallback", async () => {
    const f = fixture({ missingAtomicWrite: true });
    await expect(call(f)).rejects.toMatchObject({ code: "DSH_PLUGIN_MANAGER_UNAVAILABLE" });
    expect(existsSync(f.trace)).toBe(false);
    expect(readFileSync(join(f.directory, "package.json"), "utf8")).toBe(f.original);
  });

  it.each([
    [{ exitCode: 1, output: "token=private stdout", logPath: "/private/account/log" }, "DSH_INSTALL_FAILED"],
    [{ exitCode: 0, timedOut: true, logPath: "/private/account/log" }, "DSH_INSTALL_TIMEOUT"],
    [{ exitCode: 1, timedOut: true, incompatible: [{ name: "hidden" }] }, "DSH_INSTALL_TIMEOUT"],
    [{ exitCode: 1, incompatible: [{ name: "hidden" }], output: "token=secret" }, "DSH_INSTALL_INCOMPATIBLE"],
  ])("retains the snapshot and old activation after a manager failure %#", async (result, code) => {
    const f = fixture({ result });
    await expect(call(f)).rejects.toMatchObject({ code, message: code });
    expect(readFileSync(join(f.backup, "package.json"), "utf8")).toBe(f.original);
    expect(readJson(join(f.directory, "package.json")).dsh.profile.bundles).toEqual(["base", OLD, "tail"]);
    expect(readJson(f.trace).some((r) => r.activate)).toBe(false);
    expect(recoveryDirectory(f)).toBeNull();
    expect(readJson(f.trace).some((record) => record.atomic?.startsWith(f.directory))).toBe(false);
  });

  it.each([
    ["nonzero result", { result: { exitCode: 1 } }, "DSH_INSTALL_FAILED"],
    ["timeout result", { result: { exitCode: 1, timedOut: true } }, "DSH_INSTALL_TIMEOUT"],
    ["thrown error", { throwPnpm: true }, "DSH_INSTALL_FAILED"],
  ])("restores exact files and retains the failed PNPM state after a %s", async (_label, outcome, code) => {
    const f = fixture({ ...outcome, mutatePnpmFiles: true });
    await expect(call(f)).rejects.toMatchObject({ code, message: code });

    expectOriginalProfile(f);
    const recovery = recoveryDirectory(f);
    expect(recovery).not.toBeNull();
    const failedManifest = readJson(join(recovery, "package.json"));
    expect(failedManifest.partialInstallState).toBe("failed-write");
    expect(failedManifest.dependencies[NEW]).toBe("file:partial-install");
    expect(failedManifest.custom).toEqual({ account: "unchanged" });
    expect(failedManifest.dsh.profile.bundles).toEqual(["base", OLD, "tail"]);
    expect(readFileSync(join(recovery, "pnpm-lock.yaml"), "utf8")).toBe("partial pnpm lock\n");
    const retained = readdirSync(recovery).find((entry) => entry.startsWith("generated-pnpm-lock-"));
    expect(retained).toBeDefined();
    expect(readFileSync(join(recovery, retained), "utf8")).toBe("partial pnpm lock\n");
    expect(readJson(f.trace).some((record) => record.activate)).toBe(false);
  });

  it("restores an existing lock byte-for-byte after activation writes and throws", async () => {
    const originalLock = Buffer.from("lockfileVersion: '9.0'\r\noriginal: true\n");
    const profileModes = { manifest: 0o640, lockfile: 0o640 };
    const f = fixture({ mutatePnpmFiles: true, throwActivation: true, initialLock: originalLock, profileModes });
    await expect(call(f)).rejects.toMatchObject({ code: "DSH_INSTALL_FAILED" });

    expectOriginalProfile(f);
    const recovery = recoveryDirectory(f);
    expect(recovery).not.toBeNull();
    const failedManifest = readJson(join(recovery, "package.json"));
    expect(failedManifest.dsh.profile.bundles).toEqual(["base", NEW, "tail"]);
    expect(failedManifest.custom).toEqual({ account: "unchanged" });
    expect(readFileSync(join(recovery, "pnpm-lock.yaml"), "utf8")).toBe("partial pnpm lock\n");
  });

  it("finishes rollback after the package operation is aborted", async () => {
    const f = fixture({ mutatePnpmFiles: true, waitForAbort: true });
    const controller = new AbortController();
    const pending = call(f, { signal: controller.signal });
    const abortTimer = setTimeout(() => controller.abort(), 10);
    await expect(pending).rejects.toMatchObject({ code: "DSH_INSTALL_TIMEOUT" });
    clearTimeout(abortTimer);

    expectOriginalProfile(f);
    const recovery = recoveryDirectory(f);
    expect(recovery).not.toBeNull();
    expect(readFileSync(join(recovery, "pnpm-lock.yaml"), "utf8")).toBe("partial pnpm lock\n");
  });

  it("refuses to restore over a concurrent change after preserving the failed state", async () => {
    const f = fixture({ result: { exitCode: 1 }, mutatePnpmFiles: true, casMutation: true });
    const result = spawnSync(process.execPath, [relative(f.cwd, join(root, "integrations/dsh-session-adapter/install.mjs")),
      "--launcher", relative(f.cwd, f.launcher), "--home", relative(f.cwd, f.home),
      "--profile", f.profile, "--backup", relative(f.cwd, f.backup)],
    { cwd: f.cwd, encoding: "utf8", timeout: 5000 });
    expect(result.status).toBe(27);
    expect(result.stderr.trim()).toBe("DSH_INSTALL_RECOVERY_REQUIRED");

    const concurrent = readJson(join(f.directory, "package.json"));
    expect(concurrent.userMetadata.concurrent).toBe(true);
    expect(concurrent.partialInstallState).toBe("failed-write");
    expect(readFileSync(join(f.directory, "pnpm-lock.yaml"), "utf8")).toBe("concurrent lock\n");
    const recovery = recoveryDirectory(f);
    expect(recovery).not.toBeNull();
    expect(readJson(join(recovery, "package.json")).partialInstallState).toBe("failed-write");
    expect(readFileSync(join(recovery, "pnpm-lock.yaml"), "utf8")).toBe("partial pnpm lock\n");
  });

  it("checks the input hash after acquiring the lock", async () => {
    const f = fixture({ lockMutation: `writeFileSync(path,readFileSync(path,'utf8')+' ');` });
    await expect(call(f)).rejects.toMatchObject({ code: "DSH_PROFILE_CHANGED" });
    expect(readJson(f.trace).some((r) => r.context)).toBe(false);
    expect(readFileSync(join(f.backup, "package.json"), "utf8")).toBe(f.original);
  });

  it("rejects a lockfile created after the Python snapshot", async () => {
    const f = fixture();
    writeFileSync(join(f.directory, "pnpm-lock.yaml"), "new concurrent lockfile");
    await expect(call(f)).rejects.toMatchObject({ code: "DSH_PROFILE_CHANGED" });
    expect(readJson(f.trace).some((r) => r.context)).toBe(false);
  });

  it("keeps unrelated bundle order and duplicates while activating this adapter once", async () => {
    const f = fixture({ bundles: ["base", NEW, "duplicate", OLD, "duplicate", OLD, "tail"] });
    await call(f);
    expect(readJson(join(f.directory, "package.json")).dsh.profile.bundles).toEqual(["base", NEW, "duplicate", "duplicate", "tail"]);
  });

  it("repeated upgrades preserve the manifest without duplicating activation", async () => {
    const f = fixture();
    await call(f);
    const installed = readFileSync(join(f.directory, "package.json"), "utf8");
    writeFileSync(join(f.backup, "package.json"), installed);
    const metadataPath = join(f.backup, "metadata.json");
    const metadata = readJson(metadataPath);
    metadata.files["package.json"] = digest(installed);
    writeFileSync(metadataPath, JSON.stringify(metadata));
    await call(f);
    expect(readFileSync(join(f.directory, "package.json"), "utf8")).toBe(installed);
    expect(readJson(f.trace).filter((r) => r.context)).toHaveLength(2);
    expect(readJson(f.trace).filter((r) => r.activate)).toHaveLength(1);
  });


  it("the Python-facing CLI accepts first installation with the explicit mode flag", () => {
    const f = fixture({ manifest: { private: true }, includeUninstalled: true });
    const result = spawnSync(process.execPath, [relative(f.cwd, join(root, "integrations/dsh-session-adapter/install.mjs")),
      "--launcher", relative(f.cwd, f.launcher), "--home", relative(f.cwd, f.home),
      "--profile", "web", "--backup", relative(f.cwd, f.backup), "--include-uninstalled"],
    { cwd: f.cwd, encoding: "utf8", timeout: 5000 });
    expect({ status: result.status, stderr: result.stderr }).toEqual({ status: 0, stderr: "" });
    expect(readJson(join(f.directory, "package.json")).dsh.profile.bundles).toEqual([NEW]);
  });

  it("the Python-facing CLI accepts relative arguments without running main on import", () => {
    const f = fixture();
    const output = execFileSync(process.execPath, [relative(f.cwd, join(root, "integrations/dsh-session-adapter/install.mjs")),
      "--launcher", relative(f.cwd, f.launcher), "--home", relative(f.cwd, f.home),
      "--profile", "web", "--backup", relative(f.cwd, f.backup)],
    { cwd: f.cwd, encoding: "utf8", timeout: 5000 });
    expect(output).toBe("");
    expect(readJson(join(f.directory, "package.json")).dsh.profile.bundles).toEqual(["base", NEW, "tail"]);
  });

  it("prefixes ./ when cwd is the repository root so official anchorPathSpec resolves bundle outside profile", async () => {
    const f = fixture({ includeUninstalled: true, manifest: { private: true } });
    // Rewrite metadata relative to `root` (the exact cwd used by dsh_installation.py).
    writeFileSync(join(f.backup, "metadata.json"), JSON.stringify({
      version: 1, profile: "web", includeUninstalled: true,
      home: relative(root, f.home), directory: relative(root, f.directory),
      launcher: relative(root, f.launcher), bundle: "integrations/dsh-session-adapter",
      files: { "package.json": digest(f.original), "pnpm-lock.yaml": null },
    }), { mode: 0o600 });
    // Enforce @deepseek-ai/dsh-plugin-manager's exact anchorPathSpec regex:
    // file: specs without a leading ./ or ../ are NOT anchored to cwd and fail in pnpm!
    const managerIndex = join(dirname(dirname(f.launcher)), "node_modules", "@deepseek-ai", "dsh-plugin-manager", "index.js");
    writeFileSync(managerIndex, `import {existsSync,readFileSync,writeFileSync} from 'node:fs';
      import {join,resolve} from 'node:path';
      const trace=${JSON.stringify(f.trace)};
      const anchorRe=/^(?:file|link):(?<path>\\.{1,2}(?:[/\\\\].*)?)$/;
      export async function runProfilePnpm(context,args,options){
        const records=existsSync(trace)?JSON.parse(readFileSync(trace)):[];
        records.push({context,args,options});
        writeFileSync(trace,JSON.stringify(records));
        const m=anchorRe.exec(args[1]??'');
        if(!m||!existsSync(join(resolve(context.cwd,m.groups.path),'package.json'))){
          return {exitCode:1};
        }
        const p=join(context.dir,'package.json');
        const manifest=JSON.parse(readFileSync(p));
        manifest.dependencies ??= {};
        manifest.dependencies[${JSON.stringify(NEW)}]=args[1];
        writeFileSync(p,JSON.stringify(manifest));
        return {exitCode:0};
      }\n`);
    await installDshAdapter({
      cwd: root,
      launcher: relative(root, f.launcher),
      home: relative(root, f.home),
      profile: "web",
      backup: relative(root, f.backup),
      includeUninstalled: true,
    });
    const records = readJson(f.trace);
    const installCall = records.find((r) => r.context);
    expect(installCall.args).toEqual(["add", "file:./integrations/dsh-session-adapter"]);
    expect(readJson(join(f.directory, "package.json")).dsh.profile.bundles).toEqual([NEW]);
  });

});
