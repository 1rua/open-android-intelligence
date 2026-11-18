import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { posix } from 'node:path';
import { SourceTextModule, SyntheticModule } from 'node:vm';

function toMap(entries) {
  if (!entries) return new Map();
  return entries instanceof Map ? new Map(entries) : new Map(Object.entries(entries));
}

/**
 * 读取并校验固定上游夹具目录的 source.json 索引及文件哈希。
 */
export async function loadVerifiedFixtures(
  fixtureRootUrl,
  { expectedCommit, pathKey = 'path', verifyGitBlob = false } = {},
) {
  const index = JSON.parse(await readFile(new URL('source.json', fixtureRootUrl), 'utf8'));
  if (expectedCommit !== undefined) {
    assert.equal(index.commit, expectedCommit);
  }
  const sources = new Map();
  for (const entry of index.files) {
    const relativePath = entry[pathKey];
    const bytes = await readFile(new URL(relativePath, fixtureRootUrl));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), entry.sha256, relativePath);
    if (verifyGitBlob && entry.gitBlob) {
      assert.equal(
        createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex'),
        entry.gitBlob,
        relativePath,
      );
    }
    sources.set(relativePath, bytes.toString('utf8'));
  }
  return { index, sources };
}

/**
 * 将导出对象包装为 Node VM SyntheticModule。
 */
export function createSyntheticModule(id, exportsMap) {
  return new SyntheticModule(
    Object.keys(exportsMap),
    function () {
      for (const [name, value] of Object.entries(exportsMap)) {
        this.setExport(name, value);
      }
    },
    { identifier: id },
  );
}

/**
 * 剥离 TypeScript 类型语法并构造 Node VM SourceTextModule。
 */
export function compileTypeScriptModule(sourceCode, sourceUrl, identifier = sourceUrl) {
  const stripped = stripTypeScriptTypes(sourceCode, { mode: 'transform', sourceUrl });
  return new SourceTextModule(stripped, { identifier });
}

/**
 * 创建支持替身模块、延迟虚拟模块及 TypeScript 原文按需编译链接的 VM 加载器。
 */
export function createTsVmLoader({
  sources,
  doubles = new Map(),
  virtualModules = new Map(),
  resolveRelative,
} = {}) {
  const doublesMap = toMap(doubles);
  const virtualMap = toMap(virtualModules);
  const resolveFn = resolveRelative ?? ((specifier, referencing) => (
    specifier.startsWith('.')
      ? posix.normalize(posix.join(posix.dirname(referencing.identifier), specifier)).replace(/\.js$/u, '.ts')
      : specifier
  ));
  const cache = new Map();
  const pending = new Map();

  async function getModule(id) {
    if (cache.has(id)) return cache.get(id);
    if (pending.has(id)) return pending.get(id);
    const task = (async () => {
      let mod;
      if (doublesMap.has(id)) {
        mod = createSyntheticModule(id, doublesMap.get(id));
      } else if (virtualMap.has(id)) {
        const entry = virtualMap.get(id);
        const exportsMap = typeof entry === 'function' ? await entry({ load, id }) : entry;
        mod = createSyntheticModule(id, exportsMap);
      } else if (id.startsWith('node:')) {
        mod = createSyntheticModule(id, await import(id));
      } else {
        assert.ok(sources.has(id), `未声明依赖或缺少已登记上游原文：${id}`);
        mod = compileTypeScriptModule(sources.get(id), `fixed-upstream/${id}`, id);
      }
      cache.set(id, mod);
      return mod;
    })();
    pending.set(id, task);
    try {
      return await task;
    } finally {
      pending.delete(id);
    }
  }

  async function load(id) {
    const root = await getModule(id);
    if (root.status === 'unlinked') {
      await root.link((specifier, referencing) => {
        const target = (doublesMap.has(specifier) || virtualMap.has(specifier))
          ? specifier
          : resolveFn(specifier, referencing);
        return getModule(target);
      });
    }
    if (root.status === 'linked') {
      await root.evaluate();
    }
    return root.namespace;
  }

  return { load, getModule };
}
