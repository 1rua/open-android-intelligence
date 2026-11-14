import { verifyRemoteSources } from './remote-source-integrity.ts';

try {
  console.log(`Remote 固定来源完整性通过：${await verifyRemoteSources()} 份原文与许可`);
} catch (failure) {
  console.error(`Remote 固定来源完整性失败：${failure.message}`);
  process.exitCode = 1;
}
