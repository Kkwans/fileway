import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { StatusError } from "@/api/utils";
import { useFavoritesStore, type Favorite } from "../favorites";
import { useTagsStore, type Tag } from "../tags";

const apiMock = vi.hoisted(() => {
  class MockStatusError extends Error {
    constructor(
      message: string,
      public status?: number,
      public is_canceled?: boolean
    ) {
      super(message);
    }
  }

  return {
    fetchURL: vi.fn(),
    StatusError: MockStatusError,
  };
});
const fetchURLMock = apiMock.fetchURL;

vi.mock("@/api/utils", () => apiMock);

const favorite = (overrides: Partial<Favorite> = {}): Favorite => ({
  id: "favorite-1",
  path: "/docs/report.md",
  name: "report.md",
  groupId: "",
  addedAt: 1,
  order: 0,
  ...overrides,
});

const tag = (overrides: Partial<Tag> = {}): Tag => ({
  id: "tag-1",
  name: "工作",
  color: "#1677ff",
  paths: ["/docs/report.md"],
  createdAt: 1,
  ...overrides,
});

const cloneFavorites = (items: Favorite[]) =>
  items.map((item) => ({ ...item }));
const cloneTags = (items: Tag[]) =>
  items.map((item) => ({ ...item, paths: [...item.paths] }));

describe("metadata optimistic rollback", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    fetchURLMock.mockReset();
  });

  it.each([
    new StatusError("bad request", 400),
    new StatusError("forbidden", 403),
    new StatusError("not found", 404),
    new StatusError("conflict", 409),
    new StatusError("server failed", 500),
    new StatusError("network failed", 0),
    new StatusError("canceled", 0, true),
  ])("restores a deleted favorite after %s", async (failure) => {
    const store = useFavoritesStore();
    store.favorites = [favorite()];
    const before = cloneFavorites(store.favorites);
    fetchURLMock.mockRejectedValueOnce(failure);

    await store.removeFavorite("favorite-1");

    expect(store.favorites).toEqual(before);
  });

  it("restores favorite order after a reorder request fails", async () => {
    const store = useFavoritesStore();
    store.favorites = [
      favorite({ id: "a", order: 0 }),
      favorite({ id: "b", path: "/docs/b.md", order: 1 }),
    ];
    const before = cloneFavorites(store.favorites);
    fetchURLMock.mockRejectedValueOnce(new StatusError("conflict", 409));

    await store.reorderFavorite(0, 1);

    expect(store.favorites).toEqual(before);
  });

  it("restores a tag and its nested path list after update failure", async () => {
    const store = useTagsStore();
    store.tags = [tag()];
    const before = cloneTags(store.tags);
    fetchURLMock.mockRejectedValueOnce(new StatusError("forbidden", 403));

    await store.updateTag("tag-1", { name: "新名称" });

    expect(store.tags).toEqual(before);
  });

  it("restores a removed tag path after cancellation", async () => {
    const store = useTagsStore();
    store.tags = [tag()];
    const before = cloneTags(store.tags);
    fetchURLMock.mockRejectedValueOnce(new StatusError("canceled", 0, true));

    await store.removePathFromTag("tag-1", "/docs/report.md");

    expect(store.tags).toEqual(before);
  });

  it("restores the active filter when deleting a tag fails", async () => {
    const store = useTagsStore();
    store.tags = [tag()];
    store.activeFilter = "tag-1";
    fetchURLMock.mockRejectedValueOnce(new StatusError("server failed", 500));

    await store.deleteTag("tag-1");

    expect(store.tags).toEqual([tag()]);
    expect(store.activeFilter).toBe("tag-1");
  });

  it("keeps current-user favorites and tags aligned after path mutations", () => {
    const favorites = useFavoritesStore();
    favorites.favorites = [
      favorite(),
      favorite({ id: "similar", path: "/docs-old/keep.md" }),
      favorite({ id: "case", path: "/Docs/keep.md" }),
    ];
    const tags = useTagsStore();
    tags.tags = [
      tag({
        paths: [
          "/docs/report.md",
          "/archive/report.md",
          "/docs-old/keep.md",
          "/Docs/keep.md",
        ],
      }),
    ];

    favorites.applyPathRewrite("/docs", "/archive");
    tags.applyPathRewrite("/docs", "/archive");

    expect(favorites.favorites.map((item) => item.path)).toEqual([
      "/archive/report.md",
      "/docs-old/keep.md",
      "/Docs/keep.md",
    ]);
    expect(tags.tags[0].paths).toEqual([
      "/archive/report.md",
      "/docs-old/keep.md",
      "/Docs/keep.md",
    ]);

    favorites.applyPathRemoval("/archive");
    tags.applyPathRemoval("/archive");
    expect(favorites.favorites.map((item) => item.path)).toEqual([
      "/docs-old/keep.md",
      "/Docs/keep.md",
    ]);
    expect(tags.tags[0].paths).toEqual(["/docs-old/keep.md", "/Docs/keep.md"]);
  });
});
