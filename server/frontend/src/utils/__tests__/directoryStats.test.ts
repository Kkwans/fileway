import { describe, expect, it } from "vitest";
import { summarizeDirectory } from "../directoryStats";

describe("目录递归统计", () => {
  it("分别汇总子文件夹、文件和文件大小", () => {
    expect(
      summarizeDirectory([
        { isDir: true, size: 4096 },
        { isDir: false, size: 12 },
        { isDir: false, size: 30 },
      ])
    ).toEqual({ directories: 1, files: 2, size: 42 });
  });
});
