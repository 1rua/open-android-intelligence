import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

type SourceEntry = {
  path?: string;
  fixture?: string;
  asset?: string;
  sha256: string;
  bytes?: number;
  gitBlob?: string;
};
type ReadBytes = (path: string) => Promise<Buffer>;

const repository = 'liguobao/ds-harness-remote';
const commit = 'ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** 只接受清单目录内的相对路径，错误、缺失与重复条目均阻止门禁通过。 */
function member(base: string, path: string | undefined): string {
  if (typeof path !== 'string' || !path || isAbsolute(path) || path.split(/[\\/]/).includes('..')) {
    throw Error('固定来源路径无效');
  }
  const target = resolve(base, path);
  if (relative(base, target).startsWith('..')) throw Error('固定来源路径越界');
  return target;
}

export async function verifyEntries(base: string, entries: SourceEntry[], read: ReadBytes = path => readFile(path)): Promise<number> {
  if (!Array.isArray(entries) || !entries.length) throw Error('固定来源清单为空');
  const seen = new Set<string>();
  for (const entry of entries) {
    const path = entry.fixture ?? entry.asset ?? entry.path;
    const target = member(base, path);
    if (seen.has(target)) throw Error(`固定来源重复：${path}`);
    seen.add(target);
    if (!/^[a-f0-9]{64}$/.test(entry.sha256 ?? '')) throw Error(`来源摘要无效：${path}`);
    const bytes = await read(target);
    if (entry.bytes !== undefined && bytes.length !== entry.bytes) throw Error(`来源长度不匹配：${path}`);
    if (createHash('sha256').update(bytes).digest('hex') !== entry.sha256) throw Error(`来源摘要不匹配：${path}`);
    if (entry.gitBlob !== undefined && (!/^[a-f0-9]{40}$/.test(entry.gitBlob) ||
      createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex') !== entry.gitBlob)) {
      throw Error(`来源 Git blob 不匹配：${path}`);
    }
  }
  return entries.length;
}

/** 媒体快照严格覆盖目录内全部原文，不能漏登新增文件或 MIT 许可。 */
export async function verifyMediaSources(base: string): Promise<number> {
  const source = JSON.parse(await readFile(resolve(base, 'source.json'), 'utf8'));
  if (source.repository !== repository || source.commit !== commit || !Array.isArray(source.files)) {
    throw Error('媒体固定上游身份或清单不匹配');
  }
  const entries: SourceEntry[] = source.files;
  const license = entries.find(entry => entry.path === 'LICENSE');
  if (!license || license.sha256 !== '6e41a78bbbe0c3ed54d085b661eb5af5a7836f4d40460d54f1469f43b386285d') {
    throw Error('媒体固定来源缺少原始 MIT 许可');
  }
  if (entries.some(entry => typeof entry.path !== 'string' || entry.fixture !== undefined || entry.asset !== undefined ||
    !/^[a-f0-9]{40}$/.test(entry.gitBlob ?? ''))) throw Error('媒体来源路径或 Git blob 元数据无效');
  const count = await verifyEntries(base, entries);
  const registered = new Set(entries.map(entry => relative(base, member(base, entry.path))));
  async function inventory(directory: string): Promise<string[]> {
    const files: string[] = [];
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const path = resolve(directory, entry.name);
      if (entry.isDirectory()) files.push(...await inventory(path));
      else if (entry.isFile()) files.push(relative(base, path));
      else throw Error(`媒体来源不能包含链接或特殊文件：${entry.name}`);
    }
    return files;
  }
  const files = (await inventory(base)).filter(path => path !== 'source.json');
  if (files.length !== registered.size || files.some(path => !registered.has(path))) throw Error('媒体原文目录与来源清单不完整');
  return count;
}

export async function verifyRemoteSources(projectRoot: string = root): Promise<number> {
  let count = 0;
  for (const file of [
    'tools/remote/fixtures/dsh-remote/source.json',
    'tools/remote-server/fixtures/source.json',
    'tools/remote-interop/fixtures/source.json',
    'integrations/oai-android-mcp/test/fixtures/dsh-remote/source.json',
  ]) {
    const target = member(projectRoot, file);
    const source = JSON.parse(await readFile(target, 'utf8'));
    if (source.repository !== repository || source.commit !== commit) throw Error(`固定上游身份不匹配：${file}`);
    count += await verifyEntries(dirname(target), source.files);
    if (file === 'tools/remote-interop/fixtures/source.json') {
      await verifyInventory(dirname(target), ['source.json', ...source.files.map((entry: SourceEntry) => entry.path!)]);
    }
  }
  count += await verifyInteropReleaseSources(member(projectRoot, 'tools/remote-interop/release-fixtures'));
  count += await verifyHarnessConsumerSources(member(projectRoot, 'tools/remote-interop/harness-fixtures'));
  count += await verifyMediaSources(member(projectRoot, 'tools/remote/fixtures/media-upstream'));
  const vectorRoot = member(projectRoot, 'apps/android/gateway-client/src/test/resources/dsh-remote');
  const sums = (await readFile(resolve(vectorRoot, 'SHA256SUMS'), 'utf8')).split(/\r?\n/).filter(Boolean);
  const vectors = sums.map((line): SourceEntry => {
    const match = /^([a-f0-9]{64})  (.+)$/.exec(line);
    if (!match) throw Error('Remote 向量摘要清单格式无效');
    return { sha256: match[1]!, path: match[2]! };
  });
  count += await verifyEntries(vectorRoot, vectors);
  const licensesRoot = member(projectRoot, 'apps/android/gateway-client/src/main/assets/licenses/remote');
  const licenses = JSON.parse(await readFile(resolve(licensesRoot, 'sources.json'), 'utf8'));
  if (!Array.isArray(licenses.licenseAssets) || licenses.licenseAssets.some((row: SourceEntry & { commit?: string }) =>
    !/^[a-f0-9]{40}$/.test(row.commit ?? '') || !Number.isSafeInteger(row.bytes) || (row.bytes ?? 0) <= 0)) {
    throw Error('Remote 许可来源元数据无效');
  }
  // WebRTC 上游 notices 保留原始空白；只允许这一精确资产作为 diff 空白例外。
  const notice = licenses.licenseAssets.find((row: SourceEntry) => row.asset === 'webrtc-native-and-third-party.txt');
  if (!notice || notice.bytes !== 788358 || notice.sha256 !== 'd1f9382c6878ac024155fd6d44a5977329108bb8b0a01cea40e4a2f1d7de252e') {
    throw Error('上游 WebRTC 原文例外的固定摘要不匹配');
  }
  count += await verifyEntries(licensesRoot, licenses.licenseAssets);
  return count;
}

async function verifyInventory(base: string, paths: string[]): Promise<void> {
  const expected = new Set(paths);
  const observed: string[] = [];
  async function visit(directory: string): Promise<void> {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const path = resolve(directory, entry.name);
      if (entry.isDirectory()) await visit(path);
      else if (entry.isFile()) observed.push(relative(base, path));
      else throw Error('互通来源不允许符号链接或特殊文件');
    }
  }
  await visit(base);
  if (observed.length !== expected.size || observed.some(path => !expected.has(path))) throw Error('互通来源目录与清单不完整');
}

export async function verifyInteropReleaseSources(base: string): Promise<number> {
  const source = JSON.parse(await readFile(resolve(base, 'source.json'), 'utf8'));
  if (source.repository !== repository || source.commit !== 'eef232d2c43b0c9d04998a18db98cbc9e673e488' ||
      source.release !== 'v0.6.5' || source.baseCommit !== commit || !Array.isArray(source.files) || source.files.length !== 8) {
    throw Error('Remote 0.6.5 发布差异来源身份不匹配');
  }
  const count = await verifyEntries(base, source.files);
  await verifyInventory(base, ['source.json', ...source.files.map((entry: SourceEntry) => entry.path!)]);
  return count;
}

export async function verifyHarnessConsumerSources(base: string): Promise<number> {
  const source = JSON.parse(await readFile(resolve(base, 'source.json'), 'utf8'));
  const packages: Record<string, string> = {
    '@deepseek-ai/dsh-client-connection': 'sha512-GuUgWVWP3+ngXcZhU9ctzVxD78owjP1JVIbXNIGJm2RW9avhBg3590x95EJNPD4YIFuOISg72q8pbXT4Nd1UMw==',
    '@deepseek-ai/dsh-api-gateway': 'sha512-pVBKLnxB9D3VrN3OfmpTLZir3UI2sTydbOjsHe6Ae6o0Zi3TBBAyMmz1BsLygUn11jDWYKwKywftOgskWm8zxg==',
    '@deepseek-ai/dsh-api-session-controller': 'sha512-BtAWPuxMzaEKTQWR2D4vNbZf+3eJ9Zi9Y2XFx843SJF/nECakC+QVXeT3WB+YguU0i+9RqEf4Fy/R7nXNfJOyw==',
  };
  if (source.repository !== 'deepseek-ai/deepseek-harness' || source.licenseCommit !== '39163dd30f703a79079e2d2446545cff3c453738' ||
      source.licenseSha256 !== 'ebb4f09972aee8608be255debaf78451a68e95c290f55c240dec2ecfa16ea6be' ||
      !Array.isArray(source.files) || source.files.length !== 4 || source.files.some((entry: { package: string; version: string; tarballIntegrity: string }) =>
        entry.version !== '0.2.2-rc.1' || !packages[entry.package] || packages[entry.package] !== entry.tarballIntegrity)) {
    throw Error('Harness 0.2.2-rc.1 官方消费者来源身份不匹配');
  }
  const entries: SourceEntry[] = [...source.files, { path: 'LICENSE', sha256: source.licenseSha256 }];
  const count = await verifyEntries(base, entries);
  await verifyInventory(base, ['source.json', ...entries.map(entry => entry.path!)]);
  return count;
}
