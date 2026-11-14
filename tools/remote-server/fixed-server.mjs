import { readFile, mkdtemp, rename, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createInterface } from 'node:readline';
import { once } from 'node:events';
import { stripTypeScriptTypes } from 'node:module';
import { SourceTextModule, SyntheticModule } from 'node:vm';
import * as http from 'node:http';
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as url from 'node:url';
import * as crypto from 'node:crypto';
import * as net from 'node:net';
import * as ws from 'ws';
import { z } from 'zod';

// 固定上游服务端源码逐份核对摘要，仅导入解析依赖，不改 Gateway/Store/HTTP 行为。
const fixtureRoot = new URL('./fixtures/', import.meta.url);
const source = JSON.parse(await readFile(new URL('source.json', fixtureRoot), 'utf8'));
const modules = new Map();
for (const entry of source.files.filter(entry => entry.path.endsWith('.ts'))) {
  const bytes = await readFile(new URL(entry.path, fixtureRoot));
  if (createHash('sha256').update(bytes).digest('hex') !== entry.sha256) throw Error('固定服务端源码摘要不匹配');
  modules.set(entry.path, new SourceTextModule(stripTypeScriptTypes(bytes.toString('utf8'), { mode: 'transform' }), {
    identifier: entry.path, initializeImportMeta(meta) { meta.url = new URL(entry.path, fixtureRoot).href; },
  }));
}
const namespaces = new Map([['node:http', http], ['node:fs', fs], ['node:path', path], ['node:url', url],
  ['node:crypto', crypto], ['node:net', net], ['ws', ws], ['zod', { z }]]);
const externals = new Map();
const root = modules.get('apps/server/src/server.ts');
await root.link((specifier, referencing) => {
  if (specifier === '@dsh-remote/protocol') return modules.get('packages/protocol/src/index.ts');
  if (specifier.startsWith('.')) return modules.get(path.posix.normalize(path.posix.join(path.posix.dirname(referencing.identifier), specifier.replace(/\.js$/, '.ts'))));
  if (!namespaces.has(specifier)) throw Error(`未登记导入：${specifier}`);
  if (!externals.has(specifier)) {
    const values = namespaces.get(specifier);
    externals.set(specifier, new SyntheticModule(Object.keys(values), function () {
      for (const [key, value] of Object.entries(values)) this.setExport(key, value);
    }, { identifier: specifier }));
  }
  return externals.get(specifier);
});
await root.evaluate();
const temporaryRoot = new URL('../../.worktrees/fixed-server/', import.meta.url); await mkdir(temporaryRoot, { recursive: true });
const stateDirectory = await mkdtemp(path.join(url.fileURLToPath(temporaryRoot), 'run-'));
const app = root.namespace.createRemoteServer({ account: 'fixture@example.test', password: 'fixture-local-password',
  dataFile: path.join(stateDirectory, 'state.json'), publicUrl: 'http://127.0.0.1:8080', logLevel: 'error' });
app.server.listen(0, '127.0.0.1'); await once(app.server, 'listening');
process.stdout.write(`${JSON.stringify({ origin: `http://127.0.0.1:${app.server.address().port}`, account: 'fixture@example.test', password: 'fixture-local-password' })}\n`);
const input = createInterface({ input: process.stdin });
await once(input, 'close');
app.gateway.close(); await new Promise(resolve => app.server.close(resolve));
const trash = new URL('../../.worktrees/trash/', import.meta.url); await mkdir(trash, { recursive: true });
await rename(stateDirectory, new URL(`fixed-server-${Date.now()}`, trash));
