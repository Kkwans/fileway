import { describe, expect, it } from "vitest";

import {
  getDirectVideoFailure,
  getDirectVideoFailureCopy,
  getVideoSourceType,
  getNativeContainerPlayback,
  isDefinitelyUnsupportedVideoCodec,
  isKnownIncompatibleVideo,
  isPlaybackPositionSeekable,
  shouldPreflightVideoCodec,
  supportsH264CompatibilityPlayback,
} from "../videoPlayback";

describe("视频播放源策略", () => {
  it("区分网络、解码与格式错误，网络失败不冒充格式不支持", () => {
    expect(getDirectVideoFailure(2)).toBe("network");
    expect(getDirectVideoFailure(3)).toBe("decode");
    expect(getDirectVideoFailure(4)).toBe("unsupported");
    expect(getDirectVideoFailure(1)).toBe("unknown");
    expect(getDirectVideoFailureCopy("network").description).toContain(
      "不能说明视频格式不受支持"
    );
    expect(getDirectVideoFailureCopy("unsupported").title).toBe(
      "当前浏览器不支持此视频格式"
    );
    expect(
      getDirectVideoFailureCopy("decode", "hevc").title
    ).toContain("H.265");
    expect(getDirectVideoFailureCopy("decode", "hevc").description).toContain(
      "只有声音"
    );
  });
  it("所有常见容器都先尝试原生播放（含 MKV/MOV）", () => {
    expect(isKnownIncompatibleVideo("/movie/demo.MKV")).toBe(false);
    expect(isKnownIncompatibleVideo("/movie/demo.avi?download=true")).toBe(
      false
    );
    expect(isKnownIncompatibleVideo("/movie/demo.MOV")).toBe(false);
    expect(isKnownIncompatibleVideo("/movie/demo.mp4")).toBe(false);
  });

  it("兼容播放仅在真实失败后作为备选，不按扩展名预拦截", () => {
    const scope = globalThis as typeof globalThis & {
      document?: { createElement: () => { canPlayType: () => string } };
    };
    const originalDocument = scope.document;
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: {
        createElement: () => ({ canPlayType: () => "probably" }),
      },
    });
    expect(isKnownIncompatibleVideo("/movie/demo.mkv")).toBe(false);
    expect(isKnownIncompatibleVideo("/movie/demo.mov")).toBe(false);
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: originalDocument,
    });
  });

  it("即使 canPlayType 为 maybe 也保持原生优先", () => {
    const scope = globalThis as typeof globalThis & {
      document?: { createElement: () => { canPlayType: () => string } };
    };
    const originalDocument = scope.document;
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: {
        createElement: () => ({ canPlayType: () => "maybe" }),
      },
    });
    expect(isKnownIncompatibleVideo("/movie/demo.mkv")).toBe(false);
    expect(isKnownIncompatibleVideo("/movie/demo.mov")).toBe(false);
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: originalDocument,
    });
  });

  it("为浏览器和 Video.js 提供真实 MIME 类型", () => {
    expect(getVideoSourceType("/api/raw/movie.mp4?auth=1")).toBe("video/mp4");
    expect(getVideoSourceType("/api/raw/movie.mkv")).toBe("video/x-matroska");
    expect(
      getVideoSourceType("/api/raw/no-extension", "/movie/demo.webm")
    ).toBe("video/webm");
    expect(getVideoSourceType("/api/download.php", "/movie/demo.mp4")).toBe(
      "video/mp4"
    );
    expect(getVideoSourceType("/api/raw/unknown.xyz")).toBe("");
  });

  it("只为可能容纳不兼容编码的 MP4 容器做快速预检", () => {
    expect(shouldPreflightVideoCodec("/movie/demo.mp4")).toBe(true);
    expect(shouldPreflightVideoCodec("/movie/demo.M4V?inline=true")).toBe(true);
    expect(shouldPreflightVideoCodec("/movie/demo.webm")).toBe(false);
    expect(shouldPreflightVideoCodec("/movie/demo.mkv")).toBe(false);
  });

  it("只拦截 Chromium 明确无法直接解码的编码", () => {
    expect(isDefinitelyUnsupportedVideoCodec("hevc")).toBe(true);
    expect(isDefinitelyUnsupportedVideoCodec("H.265")).toBe(true);
    expect(isDefinitelyUnsupportedVideoCodec("h264")).toBe(false);
    expect(isDefinitelyUnsupportedVideoCodec("av1")).toBe(false);
    expect(isDefinitelyUnsupportedVideoCodec(undefined)).toBe(false);
  });

  it("只对浏览器明确支持的 VP9/Opus MKV 自动尝试原生播放", () => {
    const scope = globalThis as typeof globalThis & {
      window?: { MediaSource?: { isTypeSupported: () => boolean } };
      document?: {
        createElement: () => { canPlayType: (mime: string) => string };
      };
    };
    const originalWindow = scope.window;
    const originalDocument = scope.document;
    Object.defineProperty(scope, "window", {
      configurable: true,
      value: { MediaSource: { isTypeSupported: () => false } },
    });
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: {
        createElement: () => ({
          canPlayType: (mime: string) =>
            mime === 'video/webm; codecs="vp9,opus"' ? "probably" : "",
        }),
      },
    });
    expect(getNativeContainerPlayback("/movie/demo.mkv", "vp9", "opus")).toBe(
      "supported"
    );
    expect(getNativeContainerPlayback("/movie/demo.mkv", "h264", "aac")).toBe(
      "unsupported"
    );
    expect(
      getNativeContainerPlayback("/movie/demo.mkv", "mystery", "aac")
    ).toBe("unknown");
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: originalDocument,
    });
    Object.defineProperty(scope, "window", {
      configurable: true,
      value: originalWindow,
    });
  });

  it("只在 HLS 已暴露目标时间范围后应用续播位置", () => {
    expect(
      isPlaybackPositionSeekable(60, 0, 120, Number.POSITIVE_INFINITY)
    ).toBe(true);
    expect(
      isPlaybackPositionSeekable(60, 0, 30, Number.POSITIVE_INFINITY)
    ).toBe(false);
    expect(isPlaybackPositionSeekable(60, Number.NaN, Number.NaN, 120)).toBe(
      true
    );
    expect(
      isPlaybackPositionSeekable(
        60,
        Number.NaN,
        Number.NaN,
        Number.POSITIVE_INFINITY
      )
    ).toBe(false);
  });

  it("在没有 H.264 MSE 的 Chromium 上选择 WebM 兼容路径", () => {
    const scope = globalThis as typeof globalThis & {
      window?: { MediaSource?: unknown };
      document?: { createElement: () => { canPlayType: () => string } };
    };
    const originalWindow = scope.window;
    const originalDocument = scope.document;
    Object.defineProperty(scope, "window", {
      configurable: true,
      value: { isTypeSupported: () => false },
    });
    Object.defineProperty(scope.window, "MediaSource", {
      configurable: true,
      value: { isTypeSupported: () => false },
    });
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: { createElement: () => ({ canPlayType: () => "" }) },
    });
    expect(supportsH264CompatibilityPlayback()).toBe(false);
    Object.defineProperty(scope, "window", {
      configurable: true,
      value: originalWindow,
    });
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: originalDocument,
    });
  });

  it("浏览器支持 MP4 H.264 但不支持 HLS 时仍复用无损封装", () => {
    const scope = globalThis as typeof globalThis & {
      window?: { MediaSource?: unknown };
      document?: {
        createElement: () => { canPlayType: (mime: string) => string };
      };
    };
    const originalWindow = scope.window;
    const originalDocument = scope.document;
    Object.defineProperty(scope, "window", {
      configurable: true,
      value: { MediaSource: { isTypeSupported: () => false } },
    });
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: {
        createElement: () => ({
          canPlayType: (mime: string) =>
            mime.startsWith("video/mp4") ? "probably" : "",
        }),
      },
    });
    expect(supportsH264CompatibilityPlayback()).toBe(true);
    Object.defineProperty(scope, "window", {
      configurable: true,
      value: originalWindow,
    });
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: originalDocument,
    });
  });

  it("仅报告通用 MP4 maybe 时不误判为支持 H.264", () => {
    const scope = globalThis as typeof globalThis & {
      window?: { MediaSource?: unknown };
      document?: {
        createElement: () => { canPlayType: (mime: string) => string };
      };
    };
    const originalWindow = scope.window;
    const originalDocument = scope.document;
    Object.defineProperty(scope, "window", {
      configurable: true,
      value: { MediaSource: { isTypeSupported: () => false } },
    });
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: {
        createElement: () => ({
          canPlayType: (mime: string) => (mime === "video/mp4" ? "maybe" : ""),
        }),
      },
    });
    expect(supportsH264CompatibilityPlayback()).toBe(false);
    Object.defineProperty(scope, "window", {
      configurable: true,
      value: originalWindow,
    });
    Object.defineProperty(scope, "document", {
      configurable: true,
      value: originalDocument,
    });
  });
});
