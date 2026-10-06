import { describe, expect, it } from "vitest";
import type { ResourceItem } from "@/types/file";
import {
  findAdjacentQuickPreviewItem,
  getQuickPreviewItems,
} from "../quickPreview";

function item(
  path: string,
  type: ResourceItem["type"],
  isDir = false
): ResourceItem {
  return {
    path,
    name: path.split("/").at(-1) || "",
    type,
    isDir,
    extension: path.includes(".") ? `.${path.split(".").at(-1)}` : "",
    size: 0,
    modified: "2026-08-08T00:00:00Z",
    mode: 0,
    isSymlink: false,
    url: `/files${path}`,
    index: 0,
  };
}

describe("quick preview navigation", () => {
  const items = [
    item("/docs/folder", "dir", true),
    item("/docs/readme.md", "text"),
    item("/docs/archive.bin", "blob"),
    item("/docs/photo.jpg", "image"),
  ];

  it("keeps only previewable files in current listing order", () => {
    expect(getQuickPreviewItems(items).map((entry) => entry.path)).toEqual([
      "/docs/readme.md",
      "/docs/archive.bin",
      "/docs/photo.jpg",
    ]);
  });

  it("moves without leaving quick preview and wraps at both ends", () => {
    expect(
      findAdjacentQuickPreviewItem(items, "/docs/readme.md", 1)?.path
    ).toBe("/docs/archive.bin");
    expect(
      findAdjacentQuickPreviewItem(items, "/docs/readme.md", -1)?.path
    ).toBe("/docs/photo.jpg");
    expect(
      findAdjacentQuickPreviewItem(items, "/docs/photo.jpg", 1)?.path
    ).toBe("/docs/readme.md");
  });

  it("identifies the current file by stable path instead of duplicate name", () => {
    const duplicates = [
      item("/one/report.md", "text"),
      item("/two/report.md", "text"),
      item("/two/photo.jpg", "image"),
    ];
    expect(
      findAdjacentQuickPreviewItem(duplicates, "/two/report.md", 1)?.path
    ).toBe("/two/photo.jpg");
  });
});
