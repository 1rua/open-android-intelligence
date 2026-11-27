import { spawn } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
type Execute = (command: string, args: string[], cwd: string) => Promise<number>;

function execute(command: string, args: string[], cwd: string): Promise<number> {
  return new Promise((resolveExit, reject) => {
    const child = spawn(command, args, { cwd, stdio: 'inherit' });
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (signal) reject(Error(`手机互通子进程被信号终止：${signal}`));
      else resolveExit(code ?? 1);
    });
  });
}

/** 每次先让 Gradle 核对当前源码；缺少或过期的 JVM 产物不能绕过互通门禁。 */
export async function runPhoneMcpInterop(run: Execute = execute): Promise<number> {
  const prepared = await run('./gradlew', [':gateway-client:compileDebugUnitTestKotlin'], resolve(projectRoot, 'apps/android'));
  if (prepared !== 0) return prepared;
  return run(process.execPath, [
    '--experimental-vm-modules', 'integrations/oai-android-mcp/test/original-tools-probe.mjs', '.',
  ], projectRoot);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = await runPhoneMcpInterop();
}
