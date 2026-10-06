import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { TrashItem } from "@/api/trash";
import { useTrashStore } from "../trash";

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  restore: vi.fn(),
  removePermanent: vi.fn(),
  clear: vi.fn(),
  loadFavorites: vi.fn(),
  loadTags: vi.fn(),
}));

vi.mock("@/api/trash", () => ({
  list: mocks.list,
  restore: mocks.restore,
  removePermanent: mocks.removePermanent,
  clear: mocks.clear,
}));

vi.mock("@/stores/favorites", () => ({
  useFavoritesStore: () => ({ loadFavorites: mocks.loadFavorites }),
}));

vi.mock("@/stores/tags", () => ({
  useTagsStore: () => ({ loadTags: mocks.loadTags }),
}));

const item = (id: string): TrashItem => ({
  id,
  userId: 7,
  ownerName: "owner",
  originalPath: `/docs/${id}.txt`,
  name: `${id}.txt`,
  isDir: false,
  size: 12,
  deletedAt: 10,
  status: "available",
});

describe("trash store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    for (const mock of Object.values(mocks)) mock.mockReset();
    mocks.loadFavorites.mockResolvedValue(undefined);
    mocks.loadTags.mockResolvedValue(undefined);
  });

  it("loads and records moved items without duplicates", async () => {
    mocks.list.mockResolvedValue([item("first")]);
    const store = useTrashStore();
    await store.load();
    store.recordMoved(item("second"));
    store.recordMoved({ ...item("first"), name: "new-name.txt" });

    expect(store.items.map((saved) => saved.id)).toEqual(["first", "second"]);
    expect(store.items[0].name).toBe("new-name.txt");
  });

  it("does not restore the previous account's items after a late response", async () => {
    let finishOld!: (items: TrashItem[]) => void;
    mocks.list.mockReturnValueOnce(
      new Promise<TrashItem[]>((resolve) => {
        finishOld = resolve;
      })
    );
    const store = useTrashStore();
    const oldLoad = store.load();
    store.resetForUser();
    mocks.list.mockResolvedValueOnce([item("new-account")]);
    await store.load();
    finishOld([item("old-account")]);
    await oldLoad;
    expect(store.items.map((entry) => entry.id)).toEqual(["new-account"]);
  });

  it("removes only successfully restored items and refreshes metadata", async () => {
    const store = useTrashStore();
    store.items = [item("first"), item("second")];
    mocks.restore.mockResolvedValue({
      path: "/docs/first.txt",
      skipped: false,
    });

    await store.restore("first", "keep-both");

    expect(mocks.restore).toHaveBeenCalledWith("first", "keep-both");
    expect(store.items.map((saved) => saved.id)).toEqual(["second"]);
    expect(mocks.loadFavorites).toHaveBeenCalledOnce();
    expect(mocks.loadTags).toHaveBeenCalledOnce();
  });

  it("keeps an item when restore is skipped", async () => {
    const store = useTrashStore();
    store.items = [item("first")];
    mocks.restore.mockResolvedValue({ path: "/docs/first.txt", skipped: true });

    await store.restore("first", "skip");

    expect(store.items).toHaveLength(1);
    expect(mocks.loadFavorites).not.toHaveBeenCalled();
  });

  it("does not mutate local state when permanent deletion fails", async () => {
    const store = useTrashStore();
    store.items = [item("first")];
    mocks.removePermanent.mockRejectedValue(new Error("failed"));

    await expect(store.removePermanent("first")).rejects.toThrow("failed");
    expect(store.items).toHaveLength(1);
  });

  it("returns the clear task without pretending items are already gone", async () => {
    const store = useTrashStore();
    store.items = [item("first")];
    const task = { id: "clear-task", status: "queued" };
    mocks.clear.mockResolvedValue(task);

    await expect(store.clear()).resolves.toBe(task);
    expect(store.items).toHaveLength(1);
  });
});
