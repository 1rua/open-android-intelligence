import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { cp, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { test } from 'node:test';
import { verifyEntries, verifyMediaSources, verifyRemoteSources } from './remote-source-integrity.ts';

const bytes = Buffer.from('固定上游原文\n');
const sha256 = createHash('sha256').update(bytes).digest('hex');
const entry = { path: 'fixture.txt', bytes: bytes.length, sha256 };

test('相同原文逐字节通过长度与摘要验证', async () => {
  assert.equal(await verifyEntries(resolve('fixture'), [entry], async () => bytes), 1);
});

test('内容与长度篡改、缺失和重复清单均阻止通过', async () => {
  await assert.rejects(verifyEntries(resolve('fixture'), [entry], async () => Buffer.from('变更原文')));
  await assert.rejects(verifyEntries(resolve('fixture'), [{ ...entry, bytes: bytes.length + 1 }], async () => bytes));
  await assert.rejects(verifyEntries(resolve('fixture'), [entry], async () => { throw Error('缺少固定原文'); }));
  await assert.rejects(verifyEntries(resolve('fixture'), [entry, entry], async () => bytes));
  await assert.rejects(verifyEntries(resolve('fixture'), [], async () => bytes));
});

test('越界路径与无效摘要不能冒充合法来源', async () => {
  for (const path of ['../escape', '/absolute', 'nested/../../escape']) {
    await assert.rejects(verifyEntries(resolve('fixture'), [{ ...entry, path }], async () => bytes));
  }
  await assert.rejects(verifyEntries(resolve('fixture'), [{ ...entry, sha256: '0' }], async () => bytes));
});

test('当前 Remote 原文、协议向量和 Android 分发许可完整', async () => {
  assert.ok(await verifyRemoteSources() > 20);
});

test('Git blob 按完整原文字节校验，错误或无效对象摘要阻止通过', async () => {
  const gitBlob = createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
  assert.equal(await verifyEntries(resolve('fixture'), [{ ...entry, gitBlob }], async () => bytes), 1);
  for (const invalid of ['0', '0'.repeat(40)]) {
    await assert.rejects(verifyEntries(resolve('fixture'), [{ ...entry, gitBlob: invalid }], async () => bytes), /Git blob/u);
  }
});

test('媒体来源拒绝身份篡改、漏登原文、缺许可与缺少 Git blob', async () => {
  const base = await mkdtemp(resolve(tmpdir(), 'oai-media-integrity-'));
  await cp(new URL('./remote/fixtures/media-upstream/', import.meta.url), base, { recursive: true });
  const path = resolve(base, 'source.json');
  const source = JSON.parse(await readFile(path, 'utf8'));
  assert.equal(await verifyMediaSources(base), source.files.length);
  for (const changed of [
    { ...source, repository: 'other/repository' },
    { ...source, commit: '0'.repeat(40) },
    { ...source, files: source.files.filter(entry => entry.path !== 'LICENSE') },
    { ...source, files: source.files.slice(1) },
    { ...source, files: source.files.map((entry, index) => index ? entry : { ...entry, gitBlob: undefined }) },
  ]) {
    await writeFile(path, JSON.stringify(changed));
    await assert.rejects(verifyMediaSources(base));
  }
  await writeFile(path, JSON.stringify(source));
  await writeFile(resolve(base, 'unregistered.ts'), '// 未登记原文\n');
  await assert.rejects(verifyMediaSources(base), /清单不完整/u);
});

test('媒体原文被篡改时即使文件仍在清单内也阻止通过', async () => {
  const base = await mkdtemp(resolve(tmpdir(), 'oai-media-integrity-'));
  await cp(new URL('./remote/fixtures/media-upstream/', import.meta.url), base, { recursive: true });
  await writeFile(resolve(base, 'LICENSE'), '篡改的许可原文');
  await assert.rejects(verifyMediaSources(base), /摘要不匹配/u);
});
