import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

type SourceEntry = {
  path?: string;
  fixture?: string;
  asset?: string;
  sha256: string;
  bytes?: number;
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
  }
  return entries.length;
}

export async function verifyRemoteSources(projectRoot: string = root): Promise<number> {
  let count = 0;
  for (const file of [
    'tools/remote/fixtures/dsh-remote/source.json',
    'tools/remote-server/fixtures/source.json',
    'integrations/oai-android-mcp/test/fixtures/dsh-remote/source.json',
  ]) {
    const target = member(projectRoot, file);
    const source = JSON.parse(await readFile(target, 'utf8'));
    if (source.repository !== repository || source.commit !== commit) throw Error(`固定上游身份不匹配：${file}`);
    count += await verifyEntries(dirname(target), source.files);
  }
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
