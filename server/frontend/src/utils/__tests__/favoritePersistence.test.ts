import { describe, expect, it } from "vitest";
import {
  replaceFavoriteByPath,
  resolvePersistenceState,
  userStorageKey,
  favoriteIdentity,
  favoriteWirePath,
  favoriteCreateBody,
} from "../favoritePersistence";

describe("收藏持久化", () => {
  it("同显示名的opaque和UTF8收藏分别替换服务端ID", () => {
    const opaque = {
      id: "opaque-local",
      path: "/中文.txt",
      wirePath: "/%D6%D0%CE%C4.txt",
      name: "中文.txt",
      addedAt: 1,
      order: 0,
    };
    const utf8 = {
      ...opaque,
      id: "utf8-local",
      wirePath: "/%E4%B8%AD%E6%96%87.txt",
    };
    expect(favoriteIdentity(opaque)).not.toBe(favoriteIdentity(utf8));
    expect(
      replaceFavoriteByPath([opaque, utf8], {
        ...opaque,
        id: "opaque-server",
      }).map((item) => item.id)
    ).toEqual(["opaque-server", "utf8-local"]);
    expect(favoriteCreateBody(opaque)).not.toHaveProperty("path");
    expect(favoriteCreateBody(utf8).path).toBe("/中文.txt");
  });

  it("不猜旧替代字符路径，确证UTF8替代字符与literal percent可打开", () => {
    expect(favoriteWirePath({ path: "/lost�.txt" })).toBeNull();
    expect(
      favoriteWirePath({
        path: "/lost�.txt",
        wirePath: "/lost%EF%BF%BD.txt",
        pathVerified: false,
      })
    ).toBeNull();
    expect(
      favoriteWirePath({
        path: "/lost�.txt",
        wirePath: "/lost%EF%BF%BD.txt",
        pathVerified: true,
      })
    ).toBe("/lost%EF%BF%BD.txt");
    expect(
      favoriteWirePath({ path: "/a%2Fb +?#", wirePath: "/a%252Fb%20%2B%3F%23" })
    ).toBe("/a%252Fb%20%2B%3F%23");
    expect(
      favoriteWirePath({ path: "/same", wirePath: "/different" })
    ).toBeNull();
    expect(favoriteWirePath({ path: "/same", wirePath: "/bad%GG" })).toBeNull();
  });

  it("使用服务端创建记录的真实 ID 替换本地临时收藏", () => {
    const result = replaceFavoriteByPath(
      [
        {
          id: "local-id",
          path: "/资料",
          name: "资料",
          addedAt: 1,
          order: 0,
        },
      ],
      {
        id: "server-id",
        path: "/资料",
        name: "资料",
        addedAt: 2,
        order: 0,
      }
    );

    expect(result).toEqual([
      {
        id: "server-id",
        path: "/资料",
        name: "资料",
        addedAt: 2,
        order: 0,
      },
    ]);
  });
});

describe("收藏持久化恢复", () => {
  it("为不同账号生成隔离的本地缓存键", () => {
    expect(userStorageKey("nas-file-browser-favorites", 7)).toBe(
      "nas-file-browser-favorites:user:7"
    );
    expect(userStorageKey("nas-file-browser-favorites", 8)).not.toBe(
      userStorageKey("nas-file-browser-favorites", 7)
    );
  });

  it("服务端暂时为空时保留待同步的本地收藏", () => {
    const cached = [
      {
        id: "local-id",
        path: "/资料",
        name: "资料",
        addedAt: 1,
        order: 0,
      },
    ];

    expect(resolvePersistenceState([], cached)).toEqual({
      data: cached,
      shouldSync: true,
    });
  });

  it("服务端有记录时以服务端记录为准", () => {
    const remote = [
      {
        id: "server-id",
        path: "/资料",
        name: "资料",
        addedAt: 2,
        order: 0,
      },
    ];

    expect(resolvePersistenceState(remote, [])).toEqual({
      data: remote,
      shouldSync: false,
    });
  });
});
