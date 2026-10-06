import { describe, expect, it } from "vitest";
import {
  cycleListingSort,
  getFileTypeLabel,
  normalizeFileKey,
  normalizeViewMode,
  selectForContextMenu,
  sortListingItems,
  sortItemsByType,
} from "../fileListing";

describe("file listing preferences", () => {
  it("normalizes URL separators without changing Linux filename semantics", () => {
    expect(normalizeFileKey("docs//Report.TXT/")).toBe("/docs/Report.TXT");
    expect(normalizeFileKey("/docs/report.txt")).toBe("/docs/report.txt");
    expect(normalizeFileKey("/docs/with\\backslash.txt")).toBe(
      "/docs/with\\backslash.txt"
    );
    expect(normalizeFileKey("/docs/./drafts/../Report.TXT")).toBe(
      "/docs/Report.TXT"
    );
    expect(normalizeFileKey("../../docs/report.txt")).toBe("/docs/report.txt");
    expect(normalizeFileKey("/")).toBe("/");
  });
  it("keeps directory type explicit in the detailed list", () => {
    expect(getFileTypeLabel({ isDir: true })).toBe("文件夹");
    expect(getFileTypeLabel({ isDir: false, extension: ".md" })).toBe(
      "Markdown 文件"
    );
  });
  it("supports the four required file views", () => {
    expect(normalizeViewMode("mosaic")).toBe("mosaic");
    expect(normalizeViewMode("compact-grid")).toBe("compact-grid");
    expect(normalizeViewMode("details")).toBe("details");
    expect(normalizeViewMode("compact-list")).toBe("compact-list");
  });

  it("migrates the legacy gallery preference to details", () => {
    expect(normalizeViewMode("mosaic gallery")).toBe("details");
  });

  it("falls back to the grid view for invalid stored data", () => {
    expect(normalizeViewMode("unknown")).toBe("mosaic");
    expect(normalizeViewMode(null)).toBe("mosaic");
  });

  it("uses four explicit views and migrates removed legacy modes", () => {
    expect(normalizeViewMode("mosaic")).toBe("mosaic");
    expect(normalizeViewMode("compact-grid")).toBe("compact-grid");
    expect(normalizeViewMode("details")).toBe("details");
    expect(normalizeViewMode("compact-list")).toBe("compact-list");
    expect(normalizeViewMode("list")).toBe("details");
    expect(normalizeViewMode("compact")).toBe("compact-list");
  });

  it("selects the right-clicked item without discarding an existing target selection", () => {
    expect(selectForContextMenu([1, 3], 3)).toEqual([1, 3]);
    expect(selectForContextMenu([1, 3], 8)).toEqual([8]);
  });

  it("cycles sorting from default to ascending, descending, then account default", () => {
    const accountDefault = { by: "modified", asc: false };
    const initial = { by: "modified", asc: false, overridden: false };
    const ascending = cycleListingSort(initial, "name", accountDefault);
    const descending = cycleListingSort(ascending, "name", accountDefault);
    const restored = cycleListingSort(descending, "name", accountDefault);

    expect(ascending).toEqual({ by: "name", asc: true, overridden: true });
    expect(descending).toEqual({ by: "name", asc: false, overridden: true });
    expect(restored).toEqual({
      by: "modified",
      asc: false,
      overridden: false,
    });
  });

  it("sorts by directory, type, name, and direction", () => {
    const items = [
      { name: "notes-b.txt", type: "text", isDir: false },
      { name: "photos.png", type: "image", isDir: false },
      { name: "documents", type: "directory", isDir: true },
      { name: "notes-a.txt", type: "text", isDir: false },
    ];

    expect(sortItemsByType(items, true).map((item) => item.name)).toEqual([
      "documents",
      "photos.png",
      "notes-a.txt",
      "notes-b.txt",
    ]);
    expect(sortItemsByType(items, false).map((item) => item.name)).toEqual([
      "documents",
      "notes-b.txt",
      "notes-a.txt",
      "photos.png",
    ]);
  });

  it("sorts an already loaded listing without changing its input", () => {
    const items = [
      {
        name: "file-10.txt",
        type: "text",
        isDir: false,
        size: 10,
        modified: "2026-01-02T00:00:00Z",
      },
      {
        name: "file-2.txt",
        type: "text",
        isDir: false,
        size: 2,
        modified: "2026-01-01T00:00:00Z",
      },
    ];

    expect(sortListingItems(items, "name", true)[0].name).toBe("file-2.txt");
    expect(sortListingItems(items, "size", false)[0].size).toBe(10);
    expect(sortListingItems(items, "modified", true)[0].name).toBe(
      "file-2.txt"
    );
    expect(items[0].name).toBe("file-10.txt");
  });
});
