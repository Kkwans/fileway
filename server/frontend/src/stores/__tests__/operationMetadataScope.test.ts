import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { IUser } from "@/types/user";
import { useAuthStore } from "../auth";
import { useFavoritesStore } from "../favorites";
import { useRecentStore } from "../recent";
const mocks = vi.hoisted(() => ({
  fetchURL: vi.fn(),
  list: vi.fn(),
  record: vi.fn(),
}));
vi.mock("@/api/utils", () => ({
  fetchURL: mocks.fetchURL,
  StatusError: class extends Error {},
}));
vi.mock("@/api/recent", () => ({ list: mocks.list, record: mocks.record }));
const favorite = (id: string) => ({
  id,
  path: "/same.txt",
  name: "same.txt",
  addedAt: 1,
  order: 0,
});
const recent = (id: string) => ({
  id,
  path: "/same.txt",
  name: "same.txt",
  isDir: false,
  accessedAt: 1,
});
const user = (scope: string) => ({ id: 7, scope }) as IUser;
const json = (value: unknown) => ({ json: async () => value });
describe("operation metadata source and read revision", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    const cache = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => cache.get(key) ?? null,
      setItem: (key: string, value: string) => cache.set(key, value),
    });
    useAuthStore().setUser(user("/one"));
  });
  afterEach(() => vi.unstubAllGlobals());
  it("does not import a previous workspace or unscoped favorite cache into an empty new workspace", async () => {
    const store = useFavoritesStore();
    store.favorites = [favorite("old")];
    store.saveFavorites();
    localStorage.setItem(
      "nas-file-browser-favorites:user:7",
      JSON.stringify([favorite("legacy")])
    );
    useAuthStore().setUser(user("/two"));
    mocks.fetchURL.mockResolvedValue(json([]));
    await store.loadFavorites();
    expect(store.favorites).toEqual([]);
    expect(
      mocks.fetchURL.mock.calls.some((call) => call[1]?.method === "POST")
    ).toBe(false);
  });
  it("late favorite refresh cannot replace the new workspace or resurrect an old optimistic snapshot", async () => {
    const store = useFavoritesStore();
    store.favorites = [favorite("old")];
    let finish!: (value: unknown) => void;
    mocks.fetchURL.mockReturnValueOnce(
      new Promise((_resolve, reject) => {
        finish = reject;
      })
    );
    const pending = store.removeFavorite("old");
    useAuthStore().setUser(user("/two"));
    store.favorites = [favorite("fresh")];
    finish(new Error("owned-network-failure"));
    await pending;
    expect(store.favorites.map((row) => row.id)).toEqual(["fresh"]);
  });
  it("older same-owner authority refresh cannot replace a newer load", async () => {
    const store = useFavoritesStore();
    let finish!: (value: unknown) => void;
    let count = 0;
    mocks.fetchURL.mockImplementation(async (endpoint: string) => {
      if (endpoint.endsWith("groups")) return json([]);
      if (count++ === 0)
        return new Promise((resolve) => {
          finish = resolve;
        });
      return json([favorite("fresh")]);
    });
    const old = store.loadFavorites();
    await vi.waitFor(() => expect(finish).toBeTypeOf("function"));
    await store.loadFavorites();
    finish(json([favorite("old")]));
    await old;
    expect(store.favorites.map((row) => row.id)).toEqual(["fresh"]);
  });
  it("late recent reads and record ACK cannot replace another workspace", async () => {
    const store = useRecentStore();
    let finishRead!: (value: unknown) => void,
      finishRecord!: (value: unknown) => void;
    mocks.list.mockReturnValueOnce(
      new Promise((resolve) => {
        finishRead = resolve;
      })
    );
    mocks.record.mockReturnValueOnce(
      new Promise((resolve) => {
        finishRecord = resolve;
      })
    );
    const oldRead = store.load(),
      oldRecord = store.record("/same.txt", "/same.txt");
    useAuthStore().setUser(user("/two"));
    mocks.list.mockResolvedValue([recent("fresh")]);
    await store.load();
    finishRead([recent("old")]);
    finishRecord(recent("old-record"));
    await Promise.all([oldRead, oldRecord]);
    expect(store.items.map((row) => row.id)).toEqual(["fresh"]);
  });
  it("a failed record that invalidates an older list does not leave loading stuck", async () => {
    const store = useRecentStore();
    let finish!: (value: unknown) => void;
    mocks.list.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      })
    );
    const pending = store.load();
    mocks.record.mockRejectedValue(new Error("owned-network-failure"));
    await expect(store.record("/same.txt", "/same.txt")).rejects.toThrow(
      "owned-network-failure"
    );
    finish([recent("old")]);
    await pending;
    expect(store.loading).toBe(false);
  });
  it("a same-owner authority read cannot cut off the second step of an explicit group-and-order write", async () => {
    const store = useFavoritesStore();
    const remote = [
      { ...favorite("drag"), path: "/drag.txt", groupId: "one" },
      { ...favorite("target"), path: "/target.txt", groupId: "two", order: 1 },
    ];
    store.favorites = remote.map((row) => ({ ...row }));
    let finish!: () => void;
    mocks.fetchURL.mockImplementation(
      async (endpoint: string, options: { method?: string; body?: string }) => {
        if (options.method === "PUT" && endpoint.endsWith("/drag"))
          return new Promise((resolve) => {
            finish = () => {
              remote[0].groupId = "two";
              resolve(json({}));
            };
          });
        if (options.method === "PUT" && endpoint.endsWith("/reorder")) {
          const ids: string[] = JSON.parse(options.body!).ids;
          remote.forEach((row) => {
            row.order = ids.indexOf(row.id);
          });
          return json({});
        }
        return json(
          endpoint.endsWith("groups")
            ? [
                { id: "one", name: "Owned one", order: 0 },
                { id: "two", name: "Owned two", order: 1 },
              ]
            : remote.map((row) => ({ ...row }))
        );
      }
    );
    const pending = store.moveAndReorderFavorite("drag", "target", "after");
    await store.refreshAfterMutation(true);
    finish();
    await pending;
    expect(
      mocks.fetchURL.mock.calls.filter(
        (call) => call[0] === "/api/favorites/reorder"
      )
    ).toHaveLength(1);
    await vi.waitFor(() =>
      expect(store.favorites.find((row) => row.id === "drag")?.order).toBe(1)
    );
  });
  it("a successful create ACK after a same-owner read converges through authority without posting again", async () => {
    const store = useFavoritesStore();
    const stable = { ...favorite("stable"), path: "/stable.txt" },
      created = {
        ...favorite("created"),
        path: "/new.txt",
        name: "new.txt",
        order: 1,
      };
    let remote = [stable];
    store.favorites = [stable];
    let finish!: () => void;
    mocks.fetchURL.mockImplementation(
      async (endpoint: string, options: { method?: string }) => {
        if (options.method === "POST")
          return new Promise((resolve) => {
            finish = () => {
              remote = [stable, created];
              resolve(json(created));
            };
          });
        return json(
          endpoint.endsWith("groups") ? [] : remote.map((row) => ({ ...row }))
        );
      }
    );
    const pending = store.addFavorite("/new.txt", "new.txt");
    await store.refreshAfterMutation(true);
    finish();
    await pending;
    await vi.waitFor(() =>
      expect(store.favorites.map((row) => row.id)).toEqual([
        "stable",
        "created",
      ])
    );
    expect(
      mocks.fetchURL.mock.calls.filter((call) => call[1]?.method === "POST")
    ).toHaveLength(1);
  });
});
