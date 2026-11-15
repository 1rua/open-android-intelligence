import { mkdir, mkdtemp, rename } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";

/** 临时测试产物保留供人工检查，不执行永久删除。 */
export async function retainTestDirectory(
  directory: string,
  trash = join(tmpdir(), "open-android-intelligence-trash"),
): Promise<string> {
  await mkdir(trash, { recursive: true });
  const retainedRoot = await mkdtemp(join(trash, "retained-"));
  const retainedDirectory = join(retainedRoot, basename(directory));
  await rename(directory, retainedDirectory);
  return retainedDirectory;
}
