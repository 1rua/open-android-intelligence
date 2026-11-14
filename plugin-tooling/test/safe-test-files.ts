import { mkdir, rename } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";

/** 临时测试产物保留供人工检查，不执行永久删除。 */
export async function retainTestDirectory(directory: string): Promise<void> {
  const trash = join(tmpdir(), "open-android-intelligence-trash");
  await mkdir(trash, { recursive: true });
  await rename(directory, join(trash, basename(directory)));
}
