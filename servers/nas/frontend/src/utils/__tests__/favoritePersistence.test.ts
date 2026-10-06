import { describe, expect, it } from "vitest";
import {
  replaceFavoriteByPath,
  resolvePersistenceState,
  userStorageKey,
} from "../favoritePersistence";

describe("收藏持久化", () => {
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
