import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it } from "vitest";
import type { Resource, ResourceItem } from "@/types/file";
import type { IUser } from "@/types/user";
import { useAuthStore } from "../auth";
import { useFileStore } from "../file";
import { useClipboardStore } from "../clipboard";
import { useLayoutStore } from "../layout";

const item = (path: string, wirePath?: string): ResourceItem => ({
  path,
  wirePath,
  name: path.split("/").at(-1)!,
  size: 1,
  extension: ".txt",
  modified: "2026-01-01T00:00:00Z",
  mode: 0,
  isDir: false,
  isSymlink: false,
  type: "text",
  url: `/files${wirePath || path}`,
  index: 0,
});
const listing = (items: ResourceItem[], wirePath = "/"): Resource => ({
  ...item("/", wirePath),
  isDir: true,
  type: "dir",
  items,
  numDirs: 0,
  numFiles: items.length,
  sorting: { by: "name", asc: true },
});
const a = item("/中文.txt", "/%D6%D0%CE%C4.txt");
const b = item("/中文.txt", "/%E4%B8%AD%E6%96%87.txt");

describe("wire selection and workspace provenance", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("selecting B yields only B for the actual trash candidates and single-item actions", () => {
    const store = useFileStore();
    store.updateRequest(listing([a, b]));
    store.selectOnly(store.keyFor(b));
    expect(store.selectedItems.map((value) => value.url)).toEqual([b.url]);
    expect(store.selectedCount).toBe(1);
    expect(store.itemForKey(store.focused!)?.wirePath).toBe(b.wirePath);
    store.updateRequest(listing([b, a]));
    expect(store.selectedItems.map((value) => value.url)).toEqual([b.url]);
  });

  it("range, all, inversion and context selection preserve independent sibling keys", () => {
    const store = useFileStore();
    const c = item("/third.txt", "/third.txt");
    store.updateRequest(listing([a, b, c]));
    const keys = [a, b, c].map((value) => store.keyFor(value));
    expect(new Set(keys).size).toBe(3);
    store.selectOnly(keys[1]);
    store.selectRange(keys, keys[2]);
    expect(store.selectedItems.map((value) => value.url)).toEqual([
      b.url,
      c.url,
    ]);
    store.setSelected(keys);
    expect(store.selectedCount).toBe(3);
    store.setSelected(keys.filter((key) => !store.selected.includes(key)));
    expect(store.selectedCount).toBe(0);
    store.setSelected(keys);
    store.toggleSelected(keys[0]);
    expect(store.selectedItems.map((value) => value.url)).toEqual([
      b.url,
      c.url,
    ]);
    store.selectOnly(keys[0]);
    expect(store.selectedItems.map((value) => value.url)).toEqual([a.url]);
    store.updateRequest({ ...listing([a], "/%D6%D0"), path: "/中" });
    store.selectOnly(store.keyFor(a));
    store.updateRequest({ ...listing([a], "/%E4%B8%AD"), path: "/中" });
    expect(store.selectedCount).toBe(0);
  });

  it("legacy strings select only a unique strict UTF8 resource and never lost-byte display names", () => {
    const store = useFileStore();
    const unknown = item("/lost�");
    const ordinary = item("/literal%2F.txt", "/literal%252F.txt");
    store.updateRequest(listing([a, b, unknown, ordinary]));
    store.selectOnly("/中文.txt");
    expect(store.selectedCount).toBe(0);
    store.selectOnly(store.keyFor(unknown));
    expect(store.selectedCount).toBe(0);
    store.selectOnly("/literal%2F.txt");
    expect(store.selectedItems.map((value) => value.url)).toEqual([
      ordinary.url,
    ]);
  });

  it("wire preselection identifies B while ambiguous display preselection selects nothing", () => {
    const store = useFileStore();
    store.updateRequest(listing([a, b]));
    store.setPreselect(b);
    store.updateRequest(listing([a, b]));
    store.applyPreSelection();
    expect(store.selectedItems.map((value) => value.url)).toEqual([b.url]);
    store.clearSelection();
    store.preselect = "/中文.txt";
    store.applyPreSelection();
    expect(store.selectedCount).toBe(0);
  });

  it("account/scope changes invalidate selection, clipboard, preselection and late results", () => {
    const auth = useAuthStore();
    auth.setUser({ id: 7, scope: "/one" } as IUser);
    const store = useFileStore();
    const clipboard = useClipboardStore();
    store.isFiles = true;
    store.updateRequest(listing([a, b]));
    const layout = useLayoutStore();
    layout.showHover("rename");
    layout.showHover("risk-confirm");
    const source = store.scope;
    const oldKey = store.keyFor(b);
    store.selectOnly(oldKey);
    store.setPreselect(b, source);
    clipboard.setClipboard(
      {
        key: "x",
        path: "/files/",
        items: [{ from: b.url, name: b.name, isDir: false }],
      },
      source
    );
    auth.setUser({ id: 7, scope: "/two" } as IUser);
    expect(store.req).toBeNull();
    expect(store.isFiles).toBe(true);
    expect(layout.prompts).toEqual([]);
    expect(store.selectedCount).toBe(0);
    expect(store.preselect).toBeNull();
    expect(clipboard.items).toEqual([]);
    expect(store.updateRequest(listing([a, b]), source)).toBe(false);
    store.updateRequest(listing([a, b]));
    store.selectOnly(oldKey);
    expect(store.selectedCount).toBe(0);
    expect(store.setPreselect(b, source)).toBe(false);
    expect(
      clipboard.setClipboard(
        { key: "x", items: [{ from: b.url, name: b.name, isDir: false }] },
        source
      )
    ).toBe(false);
    expect(clipboard.items).toEqual([]);
  });
});
