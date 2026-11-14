import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { test } from 'node:test';
import { verifyEntries, verifyRemoteSources } from './remote-source-integrity.ts';

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
