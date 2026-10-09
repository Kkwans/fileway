import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useRecentStore } from "../recent";

const mocks = vi.hoisted(() => ({ list: vi.fn(), record: vi.fn() }));

vi.mock("@/api/recent", () => mocks);

describe("recent store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    mocks.list.mockReset();
    mocks.record.mockReset();
  });

  it("records and deduplicates successful visits", async () => {
    mocks.record.mockResolvedValue({
      id: "recent",
      path: "/docs/report.md",
      name: "report.md",
      isDir: false,
      accessedAt: 20,
    });
    const store = useRecentStore();
    store.items = [
      {
        id: "recent",
        path: "/docs/report.md",
        name: "report.md",
        isDir: false,
        accessedAt: 10,
      },
    ];

    await store.record("/docs/report.md");

    expect(store.items).toHaveLength(1);
    expect(store.items[0].accessedAt).toBe(20);
  });

  it("rewrites descendants and removes only path-boundary matches", () => {
    const store = useRecentStore();
    store.items = [
      { id: "a", path: "/docs", name: "docs", isDir: true, accessedAt: 3 },
      {
        id: "b",
        path: "/docs/a.md",
        name: "a.md",
        isDir: false,
        accessedAt: 2,
      },
      {
        id: "c",
        path: "/docs-old/a.md",
        name: "a.md",
        isDir: false,
        accessedAt: 1,
      },
    ];

    store.applyPathRewrite("/docs", "/archive");
    expect(store.items.map((entry) => entry.path)).toEqual([
      "/archive",
      "/archive/a.md",
      "/docs-old/a.md",
    ]);
    store.applyPathRemoval("/archive");
    expect(store.items.map((entry) => entry.path)).toEqual(["/docs-old/a.md"]);
  });

  it("keeps same-display-name byte paths separate through rename and removal", () => {
    const store = useRecentStore();
    store.items = [
      {
        id: "opaque",
        path: "/中文",
        wirePath: "/%D6%D0%CE%C4",
        name: "中文",
        isDir: true,
        accessedAt: 3,
      },
      {
        id: "child",
        path: "/中文/a%2Fb",
        wirePath: "/%D6%D0%CE%C4/a%252Fb",
        name: "a%2Fb",
        isDir: false,
        accessedAt: 2,
      },
      {
        id: "utf8",
        path: "/中文",
        wirePath: "/%E4%B8%AD%E6%96%87",
        name: "中文",
        isDir: true,
        accessedAt: 1,
      },
    ];
    store.applyPathRewrite(
      "/中文",
      "/new +#",
      "/%D6%D0%CE%C4",
      "/new%20%2B%23"
    );
    expect(store.items[0].wirePath).toBe("/new%20%2B%23");
    expect(store.items[1].wirePath).toBe("/new%20%2B%23/a%252Fb");
    expect(store.items[2].path).toBe("/中文");
    store.applyPathRemoval("/中文", "/%D6%D0%CE%C4");
    expect(store.items).toHaveLength(3);
    store.applyPathRemoval("/new +#", "/new%20%2B%23");
    expect(store.items.map((entry) => entry.id)).toEqual(["utf8"]);
  });

  it("preserves unknown history while rewriting and removing a trusted U+FFFD sibling", () => {
    const store = useRecentStore();
    store.items = [
      {
        id: "unknown",
        path: "/lost�.txt",
        name: "lost�.txt",
        isDir: false,
        accessedAt: 3,
        pathVerified: false,
      },
      {
        id: "trusted",
        path: "/lost�.txt",
        wirePath: "/lost%EF%BF%BD.txt",
        name: "lost�.txt",
        isDir: false,
        accessedAt: 2,
        pathVerified: true,
      },
    ];
    const unknown = store.items[0];
    store.applyPathRewrite(
      "/lost�.txt",
      "/renamed�.txt",
      "/lost%EF%BF%BD.txt",
      "/renamed%EF%BF%BD.txt"
    );
    expect(store.items[0]).toBe(unknown);
    expect(store.items[0].path).toBe("/lost�.txt");
    expect(store.items[0].wirePath).toBeUndefined();
    expect(store.items[1].path).toBe("/renamed�.txt");
    expect(store.items[1].wirePath).toBe("/renamed%EF%BF%BD.txt");
    store.applyPathRemoval("/lost�.txt", "/lost%EF%BF%BD.txt");
    expect(store.items).toHaveLength(2);
    store.applyPathRemoval("/renamed�.txt", "/renamed%EF%BF%BD.txt");
    expect(store.items).toEqual([unknown]);
    expect(store.items[0].wirePath).toBeUndefined();
  });
});
