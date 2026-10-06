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
});
