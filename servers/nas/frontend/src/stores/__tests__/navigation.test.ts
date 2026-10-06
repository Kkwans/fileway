import { createPinia, setActivePinia } from "pinia";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import {
  isAppLocation,
  useNavigationStore,
  type DirectoryState,
} from "../navigation";

const directory: DirectoryState = {
  scrollY: 1800,
  limit: 150,
  sortBy: "modified",
  sortAsc: true,
  sortOverridden: true,
  viewMode: "compact-list",
  search: "照片",
  tag: "work",
  filterMode: "current",
};

describe("navigation return context", () => {
  beforeEach(() => {
    const values = new Map<string, string>();
    vi.stubGlobal("sessionStorage", {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
    });
    setActivePinia(createPinia());
  });
  afterEach(() => vi.unstubAllGlobals());

  it("returns through tools to the originating directory, skipping same-page queries", () => {
    const store = useNavigationStore();
    store.setAccount(1);
    store.record({ path: "/files/photos/", position: 1 });
    store.record({ path: "/trash", position: 2 });
    store.record({ path: "/trash?filter=failed", position: 3 });
    store.record({ path: "/analysis", position: 4 });
    expect(store.returnEntry).toEqual({
      path: "/trash?filter=failed",
      position: 3,
    });
    store.record(store.returnEntry);
    expect(store.returnEntry).toEqual({ path: "/files/photos/", position: 1 });
    store.record(store.returnEntry);
    expect(store.trail).toHaveLength(1);
  });

  it("restores the same tab after reload including listing state", () => {
    let store = useNavigationStore();
    store.setAccount(1);
    store.record({ path: "/files/photos/", position: 1 });
    store.rememberDirectory("/files/photos/", directory);
    store.record({ path: "/tasks?tab=download", position: 2 });
    setActivePinia(createPinia());
    store = useNavigationStore();
    store.setAccount(1);
    store.record({ path: "/tasks?tab=download", position: 2 }, true);
    expect(store.returnEntry.path).toBe("/files/photos/");
    store.record(store.returnEntry);
    expect(store.takeDirectoryState("/files/photos/")).toEqual(directory);
    expect(store.takeDirectoryState("/files/photos/")).toBeNull();
  });

  it("uses the last directory for a direct tool link without adopting stale history positions", () => {
    const store = useNavigationStore();
    store.setAccount(1);
    store.rememberDirectory("/files/photos/", directory);
    store.record({ path: "/tasks", position: 8 });
    store.record({ path: "/trash", position: 0 }, true);
    expect(store.returnEntry).toEqual({
      path: "/files/photos/",
      position: null,
    });
  });

  it("does not leak paths or filters across accounts or logout", () => {
    const store = useNavigationStore();
    store.setAccount(1);
    store.rememberDirectory("/files/private/", directory);
    store.record({ path: "/files/private/", position: 1 });
    store.setAccount(2);
    expect(store.returnEntry.path).toBe("/files/");
    expect(store.directories).toEqual({});
    store.clear();
    store.setAccount(1);
    expect(store.directories).toEqual({});
  });

  it("rejects external and malformed return locations", () => {
    for (const path of [
      "https://evil.test",
      "//evil.test",
      "/\\evil.test",
      "/login",
      "javascript:alert(1)",
      "/files/\nno",
    ]) {
      expect(isAppLocation(path)).toBe(false);
    }
    expect(isAppLocation("/files/照片/?sort=name")).toBe(true);
  });

  it("keeps navigation working with blocked storage", () => {
    vi.stubGlobal("sessionStorage", {
      getItem() {
        throw Error("blocked");
      },
      setItem() {
        throw Error("blocked");
      },
    });
    const store = useNavigationStore();
    store.setAccount(1);
    store.record({ path: "/files/a/", position: 1 });
    store.record({ path: "/recent", position: 2 });
    expect(store.returnEntry.path).toBe("/files/a/");
  });
});
