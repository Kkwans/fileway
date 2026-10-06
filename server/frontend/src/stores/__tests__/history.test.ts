import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useHistoryStore } from "../history";

const mocks = vi.hoisted(() => ({ list: vi.fn(), removeAll: vi.fn() }));

vi.mock("@/api/history", () => mocks);

describe("history store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    mocks.list.mockReset();
    mocks.removeAll.mockReset();
  });

  it("loads the authenticated user's private history", async () => {
    mocks.list.mockResolvedValue({
      items: [
        {
          id: "entry",
          action: "trash.restore",
          target: "/document.txt",
          status: "success",
          createdAt: 10,
        },
      ],
      total: 1,
      nextCursor: "next",
    });
    const store = useHistoryStore();

    await store.load();

    expect(store.items).toHaveLength(1);
    expect(store.items[0].target).toBe("/document.txt");
    expect(store.total).toBe(1);
    expect(store.nextCursor).toBe("next");
    expect(mocks.list).toHaveBeenCalledWith({});
  });

  it("loads the next page without duplicating entries", async () => {
    mocks.list
      .mockResolvedValueOnce({
        items: [
          {
            id: "first",
            action: "file.rename",
            target: "/first",
            status: "success",
            createdAt: 20,
          },
        ],
        total: 2,
        nextCursor: "cursor",
      })
      .mockResolvedValueOnce({
        items: [
          {
            id: "first",
            action: "file.rename",
            target: "/first",
            status: "success",
            createdAt: 20,
          },
          {
            id: "second",
            action: "file.copy",
            target: "/second",
            status: "submitted",
            createdAt: 10,
          },
        ],
        total: 2,
      });
    const store = useHistoryStore();
    await store.load({ text: "file", limit: 30 });
    await store.loadMore();

    expect(store.items.map((item) => item.id)).toEqual(["first", "second"]);
    expect(mocks.list).toHaveBeenLastCalledWith({
      text: "file",
      limit: 30,
      cursor: "cursor",
    });
  });

  it("clears only the authenticated user's history view", async () => {
    mocks.removeAll.mockResolvedValue(2);
    const store = useHistoryStore();
    store.items = [
      {
        id: "entry",
        action: "file.rename",
        target: "/entry",
        status: "success",
        createdAt: 1,
      },
    ];
    store.total = 1;
    store.nextCursor = "next";

    await store.clear();

    expect(mocks.removeAll).toHaveBeenCalledOnce();
    expect(store.items).toEqual([]);
    expect(store.total).toBe(0);
    expect(store.nextCursor).toBe("");
  });
});
