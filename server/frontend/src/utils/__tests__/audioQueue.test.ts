import { beforeEach, describe, expect, it, vi } from "vitest";
import { directoryAudioQueue, favoriteGroupAudioQueue } from "../audioQueue";

vi.mock("@/api/utils", () => ({
  createURL: (path: string) => `/${path}`,
}));

beforeEach(() => vi.clearAllMocks());

describe("音频播放队列", () => {
  it("只使用当前目录当前顺序中的直接音频文件", () => {
    const items = [
      { path: "/music/b.mp3", name: "b.mp3", type: "audio", isDir: false },
      {
        path: "/music/photo.jpg",
        name: "photo.jpg",
        type: "image",
        isDir: false,
      },
      { path: "/music/a.flac", name: "a.flac", type: "audio", isDir: false },
      { path: "/music/live", name: "live", type: "audio", isDir: true },
    ];
    const queue = directoryAudioQueue(
      items.map((item, index) => ({
        ...item,
        size: index,
        modified: "2026-08-08T00:00:00Z",
        extension: "",
        mode: 0,
        isSymlink: false,
        url: "",
        index,
      })) as any
    );
    expect(queue.map((item) => item.path)).toEqual([
      "/music/b.mp3",
      "/music/a.flac",
    ]);
    expect(queue.every((item) => item.origin === "directory")).toBe(true);
  });

  it("收藏队列仅使用指定分组并按现有顺序过滤音频", () => {
    const queue = favoriteGroupAudioQueue(
      [
        {
          id: "1",
          path: "/a.mp3",
          name: "a",
          groupId: "g",
          order: 2,
          addedAt: 0,
        },
        {
          id: "2",
          path: "/cover.jpg",
          name: "cover",
          groupId: "g",
          order: 1,
          addedAt: 0,
        },
        {
          id: "3",
          path: "/b.FLAC",
          name: "b",
          groupId: "g",
          order: 0,
          addedAt: 0,
        },
        {
          id: "4",
          path: "/other.mp3",
          name: "other",
          groupId: "x",
          order: 0,
          addedAt: 0,
        },
      ],
      "g"
    );
    expect(queue.map((item) => item.path)).toEqual(["/b.FLAC", "/a.mp3"]);
    expect(queue.every((item) => item.groupId === "g")).toBe(true);
  });
});
