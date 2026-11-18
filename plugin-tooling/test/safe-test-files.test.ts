import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { retainTestDirectory } from "./safe-test-files.js";

describe("测试目录安全回收", () => {
  let root: string;
  let trash: string;

  beforeEach(async () => {
    root = await mkdtemp(join(tmpdir(), "safe-test-files-"));
    trash = join(root, "trash");
  });

  afterEach(async () => {
    await retainTestDirectory(root);
  });

  it("并发回收同名目录时分别保留各自内容", async () => {
    const sources = [join(root, "first", "fixture"), join(root, "second", "fixture")];
    await Promise.all(sources.map(async (source, index) => {
      await mkdir(source, { recursive: true });
      await writeFile(join(source, "content.txt"), `内容-${index}`);
    }));

    const retained = await Promise.all(sources.map(source => retainTestDirectory(source, trash)));

    expect(retained[0]).not.toBe(retained[1]);
    expect(await Promise.all(retained.map(directory => readFile(join(directory, "content.txt"), "utf8"))))
      .toEqual(["内容-0", "内容-1"]);
  });

  it("回收目标已有同名目录时不覆盖既存内容", async () => {
    const source = join(root, "source", "fixture");
    const existing = join(trash, "fixture");
    await mkdir(source, { recursive: true });
    await mkdir(existing, { recursive: true });
    await writeFile(join(source, "content.txt"), "新内容");
    await writeFile(join(existing, "content.txt"), "既存内容");

    const retained = await retainTestDirectory(source, trash);

    expect(retained).not.toBe(existing);
    expect(await readFile(join(existing, "content.txt"), "utf8")).toBe("既存内容");
    expect(await readFile(join(retained, "content.txt"), "utf8")).toBe("新内容");
  });
});
