import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useTagsStore, type Tag } from "../tags";
import { useAuthStore } from "../auth";
import type { IUser } from "@/types/user";
import { tagReferences } from "@/utils/tagPersistence";

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
const opaque = "/%D6%D0%CE%C4.txt";
const utf8 = "/%E4%B8%AD%E6%96%87.txt";
const initial = (): Tag => ({
  id: "owned",
  name: "Owned tag",
  color: "#123456",
  paths: [],
  pathRefs: [],
  createdAt: 1,
});

describe("tag wire store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    mocks.fetchURL.mockReset();
    const cache = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => cache.get(key) ?? null,
      setItem: (key: string, value: string) => cache.set(key, value),
    });
  });
  afterEach(() => vi.unstubAllGlobals());
  it("never imports another workspace cache or an unscoped legacy cache", async () => {
    const auth = useAuthStore();
    auth.setUser({ id: 7, username: "one", scope: "/one" } as IUser);
    const store = useTagsStore();
    store.tags = [initial()];
    store.saveTags();
    localStorage.setItem(
      "nas-file-browser-tags:user:7",
      JSON.stringify([initial()])
    );
    auth.setUser({ id: 7, username: "one", scope: "/two" } as IUser);
    mocks.fetchURL.mockResolvedValue({ json: async () => [] });
    await store.loadTags();
    expect(store.tags).toEqual([]);
    expect(
      mocks.fetchURL.mock.calls.some((call) => call[1]?.method === "POST")
    ).toBe(false);
    auth.setUser({ id: 7, username: "one", scope: "/one" } as IUser);
    mocks.fetchURL.mockRejectedValue(new Error("offline"));
    await store.loadTags();
    expect(store.tags.map((tag) => tag.id)).toEqual(["owned"]);
  });
  it("rejects a retained picker from the previous workspace before sending a write", async () => {
    const auth = useAuthStore();
    auth.setUser({ id: 7, username: "one", scope: "/one" } as IUser);
    const store = useTagsStore();
    const source = store.sourceScope;
    auth.setUser({ id: 7, username: "one", scope: "/two" } as IUser);
    store.tags = [initial()];
    expect(
      await store.togglePathInTag("owned", "/中文.txt", opaque, source)
    ).toBe(false);
    expect(mocks.fetchURL).not.toHaveBeenCalled();
    expect(store.tags[0].paths).toEqual([]);
  });
  it("associates and unlinks same-display siblings independently and filters by wire", async () => {
    const store = useTagsStore();
    store.tags = [initial()];
    const remote = initial();
    mocks.fetchURL.mockImplementation(
      async (_endpoint: string, options: { method: string; body: string }) => {
        const body = JSON.parse(options.body);
        if (options.method === "POST")
          remote.pathRefs!.push({
            path: "/中文.txt",
            wirePath: body.wirePath,
            pathVerified: true,
          });
        else
          remote.pathRefs = remote.pathRefs!.filter(
            (ref) => ref.wirePath !== body.wirePath
          );
        remote.paths = remote.pathRefs!.map((ref) => ref.path);
        return { json: async () => JSON.parse(JSON.stringify(remote)) };
      }
    );
    await store.addPathToTag("owned", "/中文.txt", opaque);
    await store.addPathToTag("owned", "/中文.txt", utf8);
    expect(tagReferences(store.tags[0])).toHaveLength(2);
    expect(JSON.parse(mocks.fetchURL.mock.calls[0][1].body)).not.toHaveProperty(
      "path"
    );
    expect(JSON.parse(mocks.fetchURL.mock.calls[1][1].body).path).toBe(
      "/中文.txt"
    );
    await store.removePathFromTag("owned", "/中文.txt", opaque);
    expect(store.getTagsForPath("/中文.txt", opaque)).toEqual([]);
    expect(store.getTagsForPath("/中文.txt", utf8)).toHaveLength(1);
    store.setFilter("owned");
    store.setFilterMode("current");
    expect(store.matchesFilter("/中文.txt", opaque)).toBe(false);
    expect(store.matchesFilter("/中文.txt", utf8)).toBe(true);
  });
  it("opaque 400 never produces a second POST or a display fallback", async () => {
    const store = useTagsStore();
    store.tags = [initial()];
    mocks.fetchURL
      .mockRejectedValueOnce(new mocks.StatusError("legacy path required", 400))
      .mockResolvedValue({ json: async () => [initial()] });
    expect(await store.addPathToTag("owned", "/中文.txt", opaque)).toBe(false);
    const writes = mocks.fetchURL.mock.calls.filter(
      (call) => call[1]?.method === "POST"
    );
    expect(writes).toHaveLength(1);
    expect(JSON.parse(writes[0][1].body)).not.toHaveProperty("path");
    expect(store.tags[0].paths).toEqual([]);
  });
  it("old account mutation cannot restore its snapshot or returned references into a new account", async () => {
    const auth = useAuthStore();
    auth.setUser({ id: 1, username: "one", scope: "/one" } as IUser);
    const store = useTagsStore();
    store.tags = [initial()];
    let finish!: (value: unknown) => void;
    mocks.fetchURL.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      })
    );
    const pending = store.addPathToTag("owned", "/中文.txt", opaque);
    auth.setUser({ id: 2, username: "two", scope: "/two" } as IUser);
    expect(store.tags).toEqual([]);
    store.tags = [{ ...initial(), id: "new-account" }];
    finish({
      json: async () => ({
        ...initial(),
        paths: ["/中文.txt"],
        pathRefs: [{ path: "/中文.txt", wirePath: opaque, pathVerified: true }],
      }),
    });
    await pending;
    expect(store.tags.map((tag) => tag.id)).toEqual(["new-account"]);
    expect(store.tags[0].paths).toEqual([]);
  });
});
