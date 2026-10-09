import { describe, expect, it } from "vitest";
import {
  applyBatchRenameRule,
  batchRenameWireKey,
  validateBatchRenameDrafts,
  type BatchRenameDraft,
} from "../batchRename";

const drafts: BatchRenameDraft[] = [
  {
    sourcePath: "/docs/report-a.txt",
    oldName: "report-a.txt",
    newName: "report-a.txt",
    isDir: false,
  },
  {
    sourcePath: "/docs/report-b.md",
    oldName: "report-b.md",
    newName: "report-b.md",
    isDir: false,
  },
];

describe("batch rename", () => {
  it("applies literal replacement and preserves suffix extensions", () => {
    expect(
      applyBatchRenameRule(drafts, {
        type: "replace",
        search: "report",
        replacement: "draft",
      }).map((item) => item.newName)
    ).toEqual(["draft-a.txt", "draft-b.md"]);

    expect(
      applyBatchRenameRule(drafts, { type: "suffix", value: "-final" }).map(
        (item) => item.newName
      )
    ).toEqual(["report-a-final.txt", "report-b-final.md"]);
  });

  it("numbers files while preserving extensions and dotfiles", () => {
    const numbered = applyBatchRenameRule(
      [
        drafts[0],
        {
          sourcePath: "/docs/.env",
          oldName: ".env",
          newName: ".env",
          isDir: false,
        },
      ],
      {
        type: "number",
        base: "文件-",
        start: 8,
        padding: 3,
        preserveExtension: true,
      }
    );
    expect(numbered.map((item) => item.newName)).toEqual([
      "文件-008.txt",
      "文件-009",
    ]);
  });

  it("skips unchanged rows and builds same-directory changes", () => {
    const validation = validateBatchRenameDrafts([
      drafts[0],
      { ...drafts[1], newName: "final.md" },
    ]);
    expect(validation.errors.size).toBe(0);
    expect(validation.changes).toEqual([
      { from: "/docs/report-b.md", to: "/docs/final.md" },
    ]);
  });

  it("reports invalid and duplicate target names without changing case semantics", () => {
    const validation = validateBatchRenameDrafts([
      { ...drafts[0], newName: "same.txt" },
      { ...drafts[1], newName: "same.txt" },
      {
        sourcePath: "/docs/folder",
        oldName: "folder",
        newName: "bad/name",
        isDir: true,
      },
    ]);
    expect(validation.errors.get(0)).toContain("重复");
    expect(validation.errors.get(1)).toContain("重复");
    expect(validation.errors.get(2)).toContain("包含 /");

    const caseSensitive = validateBatchRenameDrafts([
      { ...drafts[0], newName: "Report.txt" },
      { ...drafts[1], newName: "report.txt" },
    ]);
    expect(caseSensitive.errors.size).toBe(0);
  });

  it("keeps opaque parent bytes and encodes the new filename exactly once", () => {
    const validation = validateBatchRenameDrafts([
      {
        sourcePath: "/目录/中文%2F.txt",
        sourceWirePath: "/%C4%BF%C2%BC/%D6%D0%CE%C4%252F.txt",
        oldName: "中文%2F.txt",
        newName: "新 +%?#.txt",
        isDir: false,
      },
    ]);
    expect(validation.errors.size).toBe(0);
    expect(validation.changes).toEqual([
      {
        from: "/目录/中文%2F.txt",
        to: "/目录/新 +%?#.txt",
        fromWirePath: "/%C4%BF%C2%BC/%D6%D0%CE%C4%252F.txt",
        toWirePath: "/%C4%BF%C2%BC/%E6%96%B0%20%2B%25%3F%23.txt",
      },
    ]);
  });

  it("does not merge identical display paths from different opaque parents", () => {
    const validation = validateBatchRenameDrafts([
      {
        sourcePath: "/中文/a",
        sourceWirePath: "/%D6%D0%CE%C4/a",
        oldName: "a",
        newName: "renamed",
        isDir: false,
      },
      {
        sourcePath: "/中文/a",
        sourceWirePath: "/%E4%B8%AD%E6%96%87/a",
        oldName: "a",
        newName: "renamed",
        isDir: false,
      },
    ]);
    expect(validation.errors.size).toBe(0);
    expect(validation.changes).toHaveLength(2);
    expect(validation.changes[0].toWirePath).not.toBe(
      validation.changes[1].toWirePath
    );
  });

  it("rejects malformed wire identities and preserves literal percent and plus bytes", () => {
    expect(batchRenameWireKey("/a%252Fb+%23")).toBe("/a%2Fb+#");
    expect(batchRenameWireKey("/%61%21")).toBe(batchRenameWireKey("/a!"));
    for (const wire of [
      "/bad%",
      "/bad%GG",
      "/a%2Fb",
      "/bad%00",
      "//host/file",
      "/file?query",
    ]) {
      expect(() => batchRenameWireKey(wire)).toThrow("原始路径");
      const validation = validateBatchRenameDrafts([
        { ...drafts[0], sourceWirePath: wire, newName: "safe.txt" },
      ]);
      expect(validation.errors.get(0)).toContain("原始路径");
    }
  });
});
