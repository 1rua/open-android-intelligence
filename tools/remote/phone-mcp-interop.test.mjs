import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runPhoneMcpInterop } from './phone-mcp-interop.ts';

test('互通只在当前源码 JVM 编译成功后启动原工具且保留失败状态', async () => {
  const calls = [];
  const result = await runPhoneMcpInterop(async (command, args, cwd) => {
    calls.push({ command, args, cwd });
    return calls.length === 1 ? 0 : 37;
  });
  assert.equal(result, 37);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].command, './gradlew');
  assert.deepEqual(calls[0].args, [':gateway-client:compileDebugUnitTestKotlin']);
  assert.ok(calls[0].cwd.endsWith('/apps/android'));
  assert.equal(calls[1].command, process.execPath);
  assert.deepEqual(calls[1].args, ['--experimental-vm-modules', 'integrations/oai-android-mcp/test/original-tools-probe.mjs', '.']);
  assert.equal(calls[1].cwd, calls[0].cwd.slice(0, -'/apps/android'.length));
});

test('编译失败或构建工具不可用立即失败，不运行陈旧探针产物', async () => {
  let calls = 0;
  assert.equal(await runPhoneMcpInterop(async () => { calls += 1; return 23; }), 23);
  assert.equal(calls, 1);
  await assert.rejects(runPhoneMcpInterop(async () => { throw Error('构建工具不可用'); }), /构建工具不可用/u);
});
