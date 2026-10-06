import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  fetchJSON: vi.fn(),
  fetchURL: vi.fn(),
}));

vi.mock("./utils", () => mocks);

import {
  clearPlayback,
  cancelHLSPlayback,
  getHLSPlayback,
  getMediaInformation,
  getPlayback,
  type PlaybackPosition,
  savePlayback,
  startHLSPlayback,
} from "./media";

describe("媒体 API", () => {
  beforeEach(() => {
    mocks.fetchJSON.mockReset().mockResolvedValue({});
    mocks.fetchURL.mockReset().mockResolvedValue(new Response(null));
  });

  it("只有显式请求时才发送位置元数据参数", async () => {
    await getMediaInformation("/相册/照片.jpg");
    await getMediaInformation("/相册/照片.jpg", true);
    expect(mocks.fetchJSON.mock.calls[0][0]).toBe(
      "/api/media/info?path=%2F%E7%9B%B8%E5%86%8C%2F%E7%85%A7%E7%89%87.jpg"
    );
    expect(mocks.fetchJSON.mock.calls[1][0]).toContain("includeLocation=true");
  });

  it("使用同一播放位置资源读取、精确保存和清除", async () => {
    await getPlayback("/video.mp4");
    await savePlayback("/video.mp4", 99.875, 100);
    await clearPlayback("/video.mp4");
    expect(mocks.fetchJSON.mock.calls[0][0]).toBe(
      "/api/media/playback?path=%2Fvideo.mp4"
    );
    expect(mocks.fetchJSON.mock.calls[1][1]).toMatchObject({ method: "PUT" });
    expect(JSON.parse(mocks.fetchJSON.mock.calls[1][1].body)).toEqual({
      path: "/video.mp4",
      position: 99.875,
      duration: 100,
    });
    expect(mocks.fetchURL).toHaveBeenCalledWith(
      "/api/media/playback?path=%2Fvideo.mp4",
      { method: "DELETE" }
    );
  });

  it("合并同一路径连续保存，并让后续调用跟随最新请求", async () => {
    const pending: Array<{
      resolve: (value: PlaybackPosition) => void;
      reject: (reason?: unknown) => void;
    }> = [];
    mocks.fetchJSON.mockImplementation(() => {
      return new Promise<PlaybackPosition>((resolve, reject) => {
        pending.push({ resolve, reject });
      });
    });

    const firstRequest = savePlayback("/video.mp4", 10, 100);
    const secondRequest = savePlayback("/video.mp4", 20, 100);
    const latestRequest = savePlayback("/video.mp4", 30, 100);
    expect(mocks.fetchJSON).toHaveBeenCalledTimes(1);
    expect(JSON.parse(mocks.fetchJSON.mock.calls[0][1].body)).toMatchObject({
      position: 10,
    });

    pending[0].resolve({
      path: "/video.mp4",
      identity: "v1:1:1",
      position: 10,
      duration: 100,
      updatedAt: 1,
      exists: true,
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.fetchJSON).toHaveBeenCalledTimes(2);
    expect(JSON.parse(mocks.fetchJSON.mock.calls[1][1].body)).toMatchObject({
      position: 30,
    });

    const finalResponse = {
      path: "/video.mp4",
      identity: "v1:1:1",
      position: 30,
      duration: 100,
      updatedAt: 2,
      exists: true,
    };
    pending[1].resolve(finalResponse);
    await expect(firstRequest).resolves.toMatchObject({ position: 10 });
    await expect(secondRequest).resolves.toEqual(finalResponse);
    await expect(latestRequest).resolves.toEqual(finalResponse);
  });

  it("首个保存失败后仍继续发送同路径最新位置", async () => {
    const pending: Array<{
      resolve: (value: PlaybackPosition) => void;
      reject: (reason?: unknown) => void;
    }> = [];
    mocks.fetchJSON.mockImplementation(() => {
      return new Promise<PlaybackPosition>((resolve, reject) => {
        pending.push({ resolve, reject });
      });
    });

    const first = savePlayback("/video.mp4", 10, 100);
    const latest = savePlayback("/video.mp4", 20, 100);
    pending[0].reject(new Error("network down"));
    await expect(first).rejects.toThrow("network down");
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.fetchJSON).toHaveBeenCalledTimes(2);
    expect(JSON.parse(mocks.fetchJSON.mock.calls[1][1].body)).toMatchObject({
      position: 20,
    });
    pending[1].resolve({
      path: "/video.mp4",
      identity: "v1:1:1",
      position: 20,
      duration: 100,
      updatedAt: 2,
      exists: true,
    });
    await expect(latest).resolves.toMatchObject({ position: 20 });
  });

  it("以删除请求作为严格屏障，且不同路径互不阻塞", async () => {
    const savePending: Array<{
      resolve: (value: PlaybackPosition) => void;
      reject: (reason?: unknown) => void;
    }> = [];
    const deletePending: Array<{
      resolve: (value: Response) => void;
      reject: (reason?: unknown) => void;
    }> = [];
    mocks.fetchJSON.mockImplementation(() => {
      return new Promise<PlaybackPosition>((resolve, reject) => {
        savePending.push({ resolve, reject });
      });
    });
    mocks.fetchURL.mockImplementation(() => {
      return new Promise<Response>((resolve, reject) => {
        deletePending.push({ resolve, reject });
      });
    });

    const response = (position: number): PlaybackPosition => ({
      path: "/video.mp4",
      identity: "v1:1:1",
      position,
      duration: 100,
      updatedAt: position,
      exists: true,
    });
    const first = savePlayback("/video.mp4", 1, 100);
    const beforeDelete = savePlayback("/video.mp4", 2, 100);
    const cleared = clearPlayback("/video.mp4");
    const afterDelete = savePlayback("/video.mp4", 3, 100);
    const otherPath = savePlayback("/other.mp4", 4, 100);

    expect(mocks.fetchJSON).toHaveBeenCalledTimes(2);
    expect(mocks.fetchURL).not.toHaveBeenCalled();
    savePending[1].resolve(response(4));
    await expect(otherPath).resolves.toMatchObject({ position: 4 });

    savePending[0].resolve(response(1));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.fetchJSON).toHaveBeenCalledTimes(3);
    expect(JSON.parse(mocks.fetchJSON.mock.calls[2][1].body)).toMatchObject({
      position: 2,
    });
    savePending[2].resolve(response(2));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.fetchURL).toHaveBeenCalledTimes(1);
    deletePending[0].resolve(new Response(null));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(mocks.fetchJSON).toHaveBeenCalledTimes(4);
    expect(JSON.parse(mocks.fetchJSON.mock.calls[3][1].body)).toMatchObject({
      position: 3,
    });
    savePending[3].resolve(response(3));

    await expect(first).resolves.toMatchObject({ position: 1 });
    await expect(beforeDelete).resolves.toMatchObject({ position: 2 });
    await expect(cleared).resolves.toBeUndefined();
    await expect(afterDelete).resolves.toMatchObject({ position: 3 });
  });

  it("只有显式调用才创建、轮询或取消兼容播放任务", async () => {
    mocks.fetchURL.mockImplementation(async () =>
      Promise.resolve(
        new Response(JSON.stringify({ id: "cache-id", state: "queued" }))
      )
    );
    await startHLSPlayback("/电影/示例.mkv");
    await getHLSPlayback("cache-id");
    await cancelHLSPlayback("cache-id");

    expect(mocks.fetchURL.mock.calls[0]).toEqual([
      "/api/media/hls",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ path: "/电影/示例.mkv" }),
      },
    ]);
    expect(mocks.fetchJSON).toHaveBeenCalledWith("/api/media/hls/cache-id");
    expect(mocks.fetchURL.mock.calls[1]).toEqual([
      "/api/media/hls/cache-id/cancel",
      { method: "POST" },
    ]);
  });

  it("支持请求无重新编码的 MP4 兼容封装", async () => {
    mocks.fetchURL.mockImplementation(async () =>
      Promise.resolve(
        new Response(JSON.stringify({ id: "mp4-cache-id", format: "mp4-copy" }))
      )
    );
    await startHLSPlayback("/电影/示例.mkv", "mp4");

    expect(mocks.fetchURL.mock.calls[0]).toEqual([
      "/api/media/hls",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ path: "/电影/示例.mkv", format: "mp4" }),
      },
    ]);
  });
});
