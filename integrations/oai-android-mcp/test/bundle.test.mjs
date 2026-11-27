import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { access, mkdir, mkdtemp, readFile, rename, symlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

const packageDir = fileURLToPath(new URL('..', import.meta.url));
const packageJsonPath = join(packageDir, 'package.json');
const patchPath = join(packageDir, 'cordis.patch.yml');
const serverName = '@open-android-intelligence/oai-android-mcp-server';

async function readPackageJson() {
  return JSON.parse(await readFile(packageJsonPath, 'utf8'));
}

function assertNpmMetadata(manifest) {
  assert.equal(manifest.name, serverName);
  assert.equal(manifest.main, './index.mjs');
  assert.deepEqual(manifest.bin, { 'oai-android-mcp': './src/stdio.mjs' });
  assert.equal(manifest.exports['.'], './index.mjs');
  assert.equal(manifest.exports['./cordis.patch.yml'], './cordis.patch.yml');
  assert.equal(manifest.exports['./src/stdio.mjs'], './src/stdio.mjs');
  assert.equal(manifest.exports['./src/bridge.mjs'], './src/bridge.mjs');
  assert.equal(manifest.exports['./package.json'], './package.json');
  assert.ok(manifest.files.includes('index.mjs'));
  assert.ok(manifest.files.includes('cordis.patch.yml'));
  assert.ok(manifest.files.includes('src/'));
  assert.deepEqual(manifest.dsh, { bundle: { patch: './cordis.patch.yml' } });
}

function loadPatchWithoutHost(source) {
  const lines = source.split(/\r?\n/u);
  const insertIndex = lines.findIndex((line) => line.trim() === '- insert:');
  assert.ok(insertIndex >= 0, 'patch must use insert for a bundle row');
  const rowLines = lines.slice(insertIndex + 1);
  const rowLine = rowLines.find((line) => line.trim() === '- id: oai-android-mcp');
  assert.ok(rowLine);
  assert.equal(rowLine.match(/^\s*/u)?.[0].length, 4);
  assert.equal(rowLines.find((line) => line.trim() === `name: '${serverName}'`)?.trim(), `name: '${serverName}'`);
  assert.equal(rowLines.find((line) => line.trim() === 'disabled: true')?.trim(), 'disabled: true');
  assert.equal(rowLines.find((line) => line.trim() === "privateConfigPath: ''")?.trim(), "privateConfigPath: ''");
  return [{
    insert: [{ id: 'oai-android-mcp', name: serverName, disabled: true, config: { privateConfigPath: '' } }],
  }];
}

test('npm 包元数据保留 Stdio bin，并声明可加载的 Bundle 与根入口', async () => {
  const manifest = await readPackageJson();
  assertNpmMetadata(manifest);
  await access(join(packageDir, 'node_modules/@modelcontextprotocol/sdk/package.json'));
  await access(join(packageDir, 'node_modules/zod/package.json'));
  const index = await import(pathToFileURL(join(packageDir, 'index.mjs')).href);
  assert.equal(index.name, 'oai-android-mcp');
  assert.equal(typeof index.apply, 'function');
  assert.deepEqual(index.inject, ['dshRemoteClient', 'connection', 'webServer']);
});

test('cordis.patch.yml 使用 loader 的 insert 语法并默认停用', async () => {
  const source = await readFile(patchPath, 'utf8');
  const patches = loadPatchWithoutHost(source);
  assert.equal(patches.length, 1);
  assert.equal(patches[0].insert.length, 1);
  const row = patches[0].insert[0];
  assert.equal(row.id, 'oai-android-mcp');
  assert.equal(row.name, serverName);
  assert.equal(row.disabled, true);
  assert.deepEqual(row.config, { privateConfigPath: '' });
  assert.ok(!source.includes('/mnt/'));
  assert.ok(!source.includes('/tmp/'));
  assert.ok(!source.includes('/home/'));
});

test('npm 打包结果包含入口、Stdio 与 patch', { skip: !process.env.npm_execpath || process.env.OAI_DSH_SKIP_NPM_PACK === '1' }, () => {
  const cache = join(process.env.TMPDIR ?? '/tmp', 'oai-android-mcp-npm-cache');
  const npmCli = process.env.npm_execpath;
  const run = spawnSync(process.execPath, [npmCli, 'pack', '--json', '--dry-run', '--cache', cache, '--pack-destination', process.env.TMPDIR ?? '/tmp'], {
    cwd: packageDir,
    encoding: 'utf8',
    env: { ...process.env, npm_config_cache: cache, npm_config_logs_dir: join(process.env.TMPDIR ?? '/tmp', 'oai-android-mcp-npm-logs'), npm_config_update_notifier: 'false' },
  });
  assert.equal(run.status, 0, run.stderr || run.stdout);
  const [result] = JSON.parse(run.stdout.slice(run.stdout.indexOf('[')));
  const paths = new Set(result.files.map((file) => file.path));
  for (const required of ['index.mjs', 'src/stdio.mjs', 'src/bridge.mjs', 'cordis.patch.yml', 'README.md', 'package.json']) {
    assert.ok(paths.has(required), `npm package is missing ${required}`);
  }
  assert.ok([...paths].every((path) => !path.startsWith('test/')), 'tests must not be published');
});

test('固定内核 PluginManager 接受本地目录为 Bundle 并解析停用行', { skip: !process.env.OAI_DSH_FIXTURES_NODE_MODULES }, async (t) => {
  const fixtures = process.env.OAI_DSH_FIXTURES_NODE_MODULES;
  const fixtureUrl = (path) => pathToFileURL(join(fixtures, path)).href;
  const managerModule = await import(fixtureUrl('@deepseek-ai/dsh-plugin-manager/lib/index.js'));
  const appBoot = await import(fixtureUrl('@deepseek-ai/dsh-app-boot/lib/index.js'));
  const cordis = await import(fixtureUrl('@deepseek-ai/cordis/lib/index.js'));
  const Loader = (await import(fixtureUrl('@deepseek-ai/cordis-plugin-loader/lib/index.js'))).default;
  const installAnchor = [
    process.env.OAI_DSH_INSTALL_ANCHOR,
    join(fixtures, '../package.json'),
    join(fixtures, '@deepseek-ai/dsh/package.json'),
    join(fixtures, 'ds-harness-remote/package.json'),
  ].find((candidate) => candidate && existsSync(candidate));
  assert.ok(installAnchor, '未找到有效的 DSH installAnchor package.json');
  const inspection = await managerModule.PluginManager.prototype.inspect.call({
    profile: { dir: packageDir, installAnchor },
    registries: async () => ({ registry: null, resolved: null, fallbackRegistries: [] }),
  }, packageDir, { registry: null }, AbortSignal.timeout(5000));
  assert.equal(inspection.status, 'accepted');
  assert.equal(inspection.bundle, true);
  assert.equal(inspection.name, serverName);

  const profile = await mkdtemp(join(process.env.TMPDIR ?? '/tmp', 'oai-android-mcp-profile-'));
  const packageScope = join(profile, 'node_modules', '@open-android-intelligence');
  await mkdir(packageScope, { recursive: true });
  await symlink(packageDir, join(packageScope, 'oai-android-mcp-server'), 'dir');
  await writeFile(join(profile, 'package.json'), JSON.stringify({
    name: 'oai-android-mcp-bundle-test',
    private: true,
    dependencies: { [serverName]: `file:${packageDir}` },
    dsh: { profile: { bundles: [serverName] } },
  }, null, 2));
  await writeFile(join(profile, 'cordis.patch.yml'), '[]\n');
  await writeFile(join(profile, 'cordis.yml'), '[]\n');
  t.after(async () => {
    const trash = join(process.env.TMPDIR ?? '/tmp', 'oai-android-mcp-trash');
    await mkdir(trash, { recursive: true });
    await rename(profile, join(trash, `profile-${Date.now()}`));
  });

  const loaded = appBoot.loadProfileDirectory('dsh', profile, installAnchor);
  assert.equal(loaded.layers.length, 1);
  assert.deepEqual(loaded.layers[0].patchPaths, [join(profile, 'node_modules', serverName, 'cordis.patch.yml')]);
  const patches = loaded.layers[0].patches;
  assert.ok(patches.length > 0);
  const row = patches[0]?.insert?.[0];
  assert.equal(row?.id, 'oai-android-mcp');
  assert.equal(row?.disabled, true);
  assert.deepEqual(row?.config, { privateConfigPath: '' });

  const ctx = new cordis.Context();
  await ctx.plugin(Loader, { baseUrl: pathToFileURL(join(profile, 'package.json')).href });
  ctx.loader.builtins.include = (await import(fixtureUrl('@deepseek-ai/cordis-plugin-include/lib/index.js'))).default;
  const includeEntry = await ctx.loader.create({ id: 'probe', name: 'cordis:include', config: { path: pathToFileURL(join(profile, 'cordis.yml')).href, patches } });
  await ctx.loader.await();
  const include = ctx.loader.resolve(includeEntry);
  const entry = include?.subtree?.store['oai-android-mcp'];
  assert.ok(entry);
  assert.equal(entry.disabled, true);
  assert.equal(entry.fiber, undefined);
  await ctx.fiber.dispose();
});
