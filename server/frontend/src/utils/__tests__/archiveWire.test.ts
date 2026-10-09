import { describe, expect, it } from "vitest";
import {
  archiveDisplayPaths,
  archiveWireEntries,
  archiveWirePath,
} from "../archiveWire";
import { buildArchiveTree, selectedArchiveStats } from "../archiveTree";

describe("archive wire identities", () => {
  it("keeps same-display-name siblings separate in the tree and selection totals", () => {
    const entries = [
      {
        path: "中文/file.txt",
        wirePath: "%D6%D0%CE%C4/file.txt",
        name: "file.txt",
        isDir: false,
        size: 11,
        modified: 1,
      },
      {
        path: "中文/file.txt",
        wirePath: "%E4%B8%AD%E6%96%87/file.txt",
        name: "file.txt",
        isDir: false,
        size: 22,
        modified: 2,
      },
    ];
    const wires = archiveWireEntries(entries);
    const tree = buildArchiveTree(wires);
    expect(tree).toHaveLength(2);
    expect(tree[0].path).not.toBe(tree[1].path);
    expect(selectedArchiveStats(wires, new Set(["%D6%D0%CE%C4"]))).toEqual({
      items: 1,
      files: 1,
      bytes: 11,
    });
    expect(archiveDisplayPaths(entries).get("%D6%D0%CE%C4")).toBe("中文");
    expect(
      archiveDisplayPaths(entries).get("%E4%B8%AD%E6%96%87/file.txt")
    ).toBe("中文/file.txt");
  });

  it("retains literal percent plus spaces and backslashes without trimming or decoding", () => {
    expect(archiveWirePath("/ a%2F +?#\\.zip ")).toBe(
      "/%20a%252F%20%2B%3F%23%5C.zip%20"
    );
    expect(archiveWirePath("/中文.zip", "/%D6%D0%CE%C4.zip")).toBe(
      "/%D6%D0%CE%C4.zip"
    );
  });

  it("rejects an explicitly unknown identity while allowing new real U+FFFD wire names", () => {
    expect(() => archiveWirePath("/lost�.zip")).toThrow("原始路径无法确认");
    expect(() =>
      archiveWirePath("/lost�.zip", "/lost%EF%BF%BD.zip", false)
    ).toThrow("原始路径无法确认");
    expect(archiveWirePath("/lost�.zip", "/lost%EF%BF%BD.zip", true)).toBe(
      "/lost%EF%BF%BD.zip"
    );
  });
});
