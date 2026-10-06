import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { TransferItem } from "@/api/transfers";

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  removeAll: vi.fn(),
}));

vi.mock("@/api/transfers", () => {
  return {
    list: mocks.list,
    removeAll: mocks.removeAll,
    cancel: vi.fn(),
    remove: vi.fn(),
  };
});

function item(id: string, createdAt: number): TransferItem {
  return {
    id,
    kind: "upload",
    status: "completed",
    name: id,
    target: `/${id}`,
    bytesTransferred: 1,
    createdAt,
  };
}

describe("transfer store paging", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    mocks.list.mockReset();
    mocks.removeAll.mockReset();
  });

  it("loads ten records then appends the cursor page", async () => {
    const first = item("first", 2);
    const second = item("second", 1);
    mocks.list
      .mockResolvedValueOnce({ items: [first], total: 2, nextCursor: "next" })
      .mockResolvedValueOnce({ items: [second], total: 2 });

    const { useTransfersStore } = await import("../transfers");
    const store = useTransfersStore();
    await store.load("upload");
    expect(mocks.list).toHaveBeenLastCalledWith("upload", undefined, 10);
    expect(store.hasMore("upload")).toBe(true);

    await store.loadMore("upload");
    expect(mocks.list).toHaveBeenLastCalledWith("upload", "next", 10);
    expect(store.uploads.map((saved) => saved.id)).toEqual(["first", "second"]);
    expect(store.hasMore("upload")).toBe(false);
  });

  it("clears only the selected transfer kind", async () => {
    mocks.removeAll.mockResolvedValue(3);
    const { useTransfersStore } = await import("../transfers");
    const store = useTransfersStore();
    store.items = [
      item("upload", 2),
      { ...item("download", 1), kind: "download" },
    ];
    await store.removeAll("upload");
    expect(mocks.removeAll).toHaveBeenCalledWith("upload");
    expect(store.items.map((saved) => saved.id)).toEqual(["download"]);
  });

  it("keeps the selected status filter while loading the next cursor page", async () => {
    mocks.list
      .mockResolvedValueOnce({
        items: [item("running", 2)],
        total: 2,
        nextCursor: "next",
      })
      .mockResolvedValueOnce({ items: [item("completed", 1)], total: 2 });

    const { useTransfersStore } = await import("../transfers");
    const store = useTransfersStore();
    await store.load("upload", ["completed"]);
    expect(mocks.list).toHaveBeenLastCalledWith({
      kind: "upload",
      statuses: ["completed"],
      limit: 10,
    });

    await store.loadMore("upload");
    expect(mocks.list).toHaveBeenLastCalledWith({
      kind: "upload",
      statuses: ["completed"],
      cursor: "next",
      limit: 10,
    });
  });
});
