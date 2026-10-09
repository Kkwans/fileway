import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useFavoritesStore, type Favorite } from "../favorites";

const mocks = vi.hoisted(() => ({
  fetchURL: vi.fn(),
  StatusError: class extends Error {
    constructor(
      message: string,
      public status?: number
    ) {
      super(message);
    }
  },
}));
vi.mock("@/api/utils", () => mocks);

const opaqueWire = "/%D6%D0%CE%C4.mp3";
const utf8Wire = "/%E4%B8%AD%E6%96%87.mp3";
const favorite = (id: string, wirePath: string): Favorite => ({
  id,
  wirePath,
  path: "/中文.mp3",
  pathVerified: true,
  name: "中文.mp3",
  addedAt: 1,
  order: 0,
});

describe("favorite wire protocol store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    mocks.fetchURL.mockReset();
  });

  it("creates separate associations and removes only the selected wire sibling", async () => {
    const store = useFavoritesStore();
    const writes: { endpoint: string; body?: Record<string, unknown> }[] = [];
    const remote: Favorite[] = [];
    mocks.fetchURL.mockImplementation(
      async (endpoint: string, options: { method: string; body?: string }) => {
        const body = options.body ? JSON.parse(options.body) : undefined;
        writes.push({ endpoint, body });
        if (options.method === "POST") {
          const created = favorite(`owned-${remote.length}`, body.wirePath);
          remote.push(created);
          return { json: async () => created };
        }
        remote.splice(
          remote.findIndex((item) => endpoint.endsWith(`/${item.id}`)),
          1
        );
        return {};
      }
    );
    await store.addFavorite("/中文.mp3", "中文.mp3", "", opaqueWire);
    await store.addFavorite("/中文.mp3", "中文.mp3", "", utf8Wire);
    expect(store.favorites).toHaveLength(2);
    expect(writes[0].body).not.toHaveProperty("path");
    expect(writes[1].body?.path).toBe("/中文.mp3");
    const first = store.findFavorite("/中文.mp3", opaqueWire)!;
    const second = store.findFavorite("/中文.mp3", utf8Wire)!;
    expect(first.id).not.toBe(second.id);
    await store.removeByPath("/中文.mp3", opaqueWire);
    expect(store.favorites.map((item) => item.id)).toEqual([second.id]);
    expect(writes[2].endpoint).toBe(`/api/favorites/${first.id}`);
    expect(store.isFavorite("/中文.mp3", opaqueWire)).toBe(false);
    expect(store.isFavorite("/中文.mp3", utf8Wire)).toBe(true);
  });

  it("does not retry an opaque 400 and sends compatible UTF8 path in its first request", async () => {
    const store = useFavoritesStore();
    mocks.fetchURL.mockRejectedValueOnce(
      new mocks.StatusError("legacy path required", 400)
    );
    await store.addFavorite("/中文.mp3", "中文.mp3", "", opaqueWire);
    expect(mocks.fetchURL).toHaveBeenCalledTimes(1);
    expect(store.favorites).toEqual([]);
    expect(JSON.parse(mocks.fetchURL.mock.calls[0][1].body)).not.toHaveProperty(
      "path"
    );
    mocks.fetchURL.mockResolvedValueOnce({
      json: async () => ({
        id: "legacy-server",
        path: "/中文.mp3",
        name: "中文.mp3",
        order: 0,
        addedAt: 1,
      }),
    });
    await store.addFavorite("/中文.mp3", "中文.mp3", "", utf8Wire);
    expect(mocks.fetchURL).toHaveBeenCalledTimes(2);
    expect(JSON.parse(mocks.fetchURL.mock.calls[1][1].body).path).toBe(
      "/中文.mp3"
    );
    expect(store.favorites[0].id).toBe("legacy-server");
  });

  it("does not rewrite a UTF8 sibling from an ambiguous display-only rename callback", async () => {
    const store = useFavoritesStore();
    store.favorites = [
      favorite("opaque", opaqueWire),
      favorite("utf8", utf8Wire),
    ];
    const renamed = { ...favorite("opaque", "/new.mp3"), path: "/new.mp3" };
    mocks.fetchURL.mockImplementation(async (endpoint: string) => ({
      json: async () =>
        endpoint.endsWith("/groups")
          ? []
          : [renamed, favorite("utf8", utf8Wire)],
    }));
    store.applyPathRewrite("/中文.mp3", "/new.mp3");
    expect(store.favorites.every((item) => item.path === "/中文.mp3")).toBe(
      true
    );
    await vi.waitFor(() =>
      expect(
        store.favorites.find((item) => item.id === "opaque")?.wirePath
      ).toBe("/new.mp3")
    );
    expect(store.findFavorite("/中文.mp3", utf8Wire)?.id).toBe("utf8");
    expect(
      mocks.fetchURL.mock.calls.every(
        (call) => !call[1]?.method || call[1].method === "GET"
      )
    ).toBe(true);
  });
});
