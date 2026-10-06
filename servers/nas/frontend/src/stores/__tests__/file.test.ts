import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it } from "vitest";

import type { Resource, ResourceItem } from "@/types/file";
import { useFileStore } from "../file";

const item = (path: string, index: number): ResourceItem => ({
  path,
  name: path.split("/").at(-1) || "",
  size: 1,
  extension: ".txt",
  modified: "2026-01-01T00:00:00Z",
  mode: 0,
  isDir: false,
  isSymlink: false,
  type: "text",
  url: `/files${path}`,
  index,
});

const listing = (path: string, items: ResourceItem[]): Resource => ({
  path,
  name: path.split("/").filter(Boolean).at(-1) || "",
  size: 0,
  extension: "",
  modified: "2026-01-01T00:00:00Z",
  mode: 0,
  isDir: true,
  isSymlink: false,
  type: "dir",
  url: `/files${path}`,
  index: 0,
  items,
  numDirs: 0,
  numFiles: items.length,
  sorting: { by: "name", asc: true },
});

describe("file selection identity", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("preserves path selections when the same directory is reordered", () => {
    const store = useFileStore();
    store.updateRequest(
      listing("/docs", [item("/docs/a.txt", 0), item("/docs/b.txt", 1)])
    );
    store.selectOnly("/docs/b.txt");

    store.updateRequest(
      listing("/docs/", [item("/docs/b.txt", 0), item("/docs/a.txt", 1)])
    );

    expect(store.selected).toEqual(["/docs/b.txt"]);
    expect(store.focused).toBe("/docs/b.txt");
    expect(store.rangeAnchor).toBe("/docs/b.txt");
    expect(store.selectedItems.map((entry) => entry.name)).toEqual(["b.txt"]);
  });

  it("drops missing items and clears selection after directory navigation", () => {
    const store = useFileStore();
    store.updateRequest(listing("/docs", [item("/docs/a.txt", 0)]));
    store.selectOnly("/docs/a.txt");
    store.updateRequest(listing("/docs", []));
    expect(store.selected).toEqual([]);

    store.updateRequest(listing("/docs", [item("/docs/a.txt", 0)]));
    store.selectOnly("/docs/a.txt");
    store.updateRequest(listing("/photos", []));
    expect(store.selected).toEqual([]);
    expect(store.focused).toBeNull();
    expect(store.rangeAnchor).toBeNull();
  });

  it("keeps a stable path anchor while a keyboard range grows and shrinks", () => {
    const store = useFileStore();
    const keys = ["/docs/a.txt", "/docs/b.txt", "/docs/c.txt"];
    store.updateRequest(
      listing(
        "/docs",
        keys.map((path, index) => item(path, index))
      )
    );
    store.selectOnly(keys[1]);

    store.selectRange(keys, keys[2]);
    expect(store.selected).toEqual([keys[1], keys[2]]);
    expect(store.focused).toBe(keys[2]);
    expect(store.rangeAnchor).toBe(keys[1]);

    store.selectRange(keys, keys[0]);
    expect(store.selected).toEqual([keys[0], keys[1]]);
    expect(store.focused).toBe(keys[0]);
    expect(store.rangeAnchor).toBe(keys[1]);
  });
});
