import { describe, expect, it } from "vitest";
import type { ArchiveEntry } from "@/api/archive";
import {
  buildArchiveTree,
  flattenArchiveTree,
  hasSelectedAncestor,
  pathCoveredBySelection,
  selectedArchiveStats,
} from "../archiveTree";

const entries: ArchiveEntry[] = [
  { path: "docs/a.txt", name: "a.txt", isDir: false, size: 4, modified: 1 },
  {
    path: "docs/nested/b.txt",
    name: "b.txt",
    isDir: false,
    size: 6,
    modified: 2,
  },
  { path: "root.txt", name: "root.txt", isDir: false, size: 3, modified: 3 },
];

describe("archive tree", () => {
  it("creates implicit folders and flattens only expanded branches", () => {
    const tree = buildArchiveTree(entries);
    expect(tree.map((node) => [node.path, node.isDir])).toEqual([
      ["docs", true],
      ["root.txt", false],
    ]);
    expect(
      flattenArchiveTree(tree, new Set(["docs"])).map((row) => row.path)
    ).toEqual(["docs", "docs/nested", "docs/a.txt", "root.txt"]);
  });

  it("computes inherited selection and real entry totals", () => {
    const selected = new Set(["docs"]);
    expect(pathCoveredBySelection(selected, "docs/nested/b.txt")).toBe(true);
    expect(hasSelectedAncestor(selected, "docs/a.txt")).toBe(true);
    expect(hasSelectedAncestor(selected, "docs")).toBe(false);
    expect(selectedArchiveStats(entries, selected)).toEqual({
      items: 2,
      files: 2,
      bytes: 10,
    });
    expect(selectedArchiveStats(entries, new Set(["."]))).toEqual({
      items: 3,
      files: 3,
      bytes: 13,
    });
  });
});
