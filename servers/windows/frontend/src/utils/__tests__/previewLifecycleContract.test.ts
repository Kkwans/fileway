import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const filesViewSource = readFileSync(
  fileURLToPath(new URL("../../views/Files.vue", import.meta.url)),
  "utf8"
);
const videoPlayerSource = readFileSync(
  fileURLToPath(
    new URL("../../components/files/VideoPlayer.vue", import.meta.url)
  ),
  "utf8"
);

describe("媒体预览生命周期契约", () => {
  it("等待新资源元数据后再按资源路径重建预览", () => {
    expect(filesViewSource).toContain(':key="currentViewKey"');
    expect(filesViewSource).not.toContain(':key="route.fullPath"');
    expect(filesViewSource).toContain("`${fileStore.req.path}:${mode}`");
  });

  it("先尝试浏览器原生源，明确不支持时再展示兼容播放", () => {
    // Native-first: never block attach by extension; compatibility is opt-in after failure.
    expect(videoPlayerSource).toContain("const sourceAttached = ref(true)");
    expect(videoPlayerSource).toContain("function buildDirectSource");
    expect(videoPlayerSource).toContain("buildDirectSource(props.path, props.source)");
    expect(videoPlayerSource).toContain("{ sources: [] }");
    expect(videoPlayerSource).toContain('type === "video/x-matroska"');
    expect(videoPlayerSource).toContain("inactivityTimeout: controlsTimeoutMs.value");
    expect(videoPlayerSource).toContain("nativeControlsForTouch: false");
    expect(videoPlayerSource).toContain("function bindControlKeepAlive");
    expect(videoPlayerSource).not.toContain("<source />");
    expect(videoPlayerSource).toContain(
      "'media-video-stage--awaiting-source': !sourceAttached"
    );
    expect(videoPlayerSource).toContain(
      ".media-video-stage--awaiting-source :deep(.vjs-big-play-button)"
    );
    expect(videoPlayerSource).toContain(
      'player.value.on("play", applyPendingResume)'
    );
    expect(videoPlayerSource).toContain(
      "const canTryDirectPlayback = computed("
    );
    expect(videoPlayerSource).toContain("directPlaybackFailed.value");
    expect(videoPlayerSource).toContain("再次尝试原视频");
    expect(videoPlayerSource).toContain("function detachDirectSource()");
  });

  it("兼容播放只在原生源明确报错后可选启动", () => {
    expect(videoPlayerSource).toContain(
      'player.value.on("error", onPlayerError)'
    );
    expect(videoPlayerSource).toContain("getDirectVideoFailure(");
    expect(videoPlayerSource).toContain("directPlaybackFailure.value =");
    expect(videoPlayerSource).toContain("startHLSPlayback(");
    expect(videoPlayerSource).not.toContain("preflightDirectPlayback");
  });

  it("兼容播放等待 HLS 可寻址范围后再恢复进度", () => {
    expect(videoPlayerSource).toContain("isPlaybackPositionSeekable");
    expect(videoPlayerSource).toContain('"durationchange"');
    expect(videoPlayerSource).toContain("currentPlayer.seekable()");
    expect(videoPlayerSource).not.toMatch(
      /currentPlayer\.one\("loadedmetadata", \(\) => \{[\s\S]*currentPlayer\.currentTime\(resumeAt\)/
    );
  });

  it("兼容源切换不异步重置 Video.js tech", () => {
    const activation = videoPlayerSource.slice(
      videoPlayerSource.indexOf("function activateCompatibilityPlayback"),
      videoPlayerSource.indexOf("function clearHLSResumeWait")
    );
    expect(activation).not.toContain("currentPlayer.reset()");
    expect(activation).toContain("currentPlayer.load()");
    expect(activation).toContain('type: isWebM ? "video/webm"');
  });

  it("大图生成期间先显示真实缩略图占位", () => {
    const previewSource = readFileSync(
      fileURLToPath(new URL("../../views/files/Preview.vue", import.meta.url)),
      "utf8"
    );
    const imageSource = readFileSync(
      fileURLToPath(
        new URL("../../components/files/ExtendedImage.vue", import.meta.url)
      ),
      "utf8"
    );
    expect(previewSource).toContain(':placeholder-src="imagePlaceholderUrl"');
    expect(previewSource).toContain(
      'getPreviewURL(fileStore.req, "thumb", { warm: "big" })'
    );
    expect(imageSource).toContain("imageStatus === 'loading'");
    expect(imageSource).toContain("placeholderFailed");
    expect(imageSource).toContain("PLACEHOLDER_MAX_WAIT_MS");
    expect(imageSource).toContain(
      'class="image-ex-img image-ex-img-placeholder"'
    );
  });

  it("大图请求已经启动后不再被超时回退抢占原图", () => {
    const imageSource = readFileSync(
      fileURLToPath(
        new URL("../../components/files/ExtendedImage.vue", import.meta.url)
      ),
      "utf8"
    );
    expect(imageSource).toContain(
      "if (!fullLoadStarted.value) startRawImageFallback(token);"
    );
  });

  it("大图缩略图先完成后才允许原图作为兜底", () => {
    const imageSource = readFileSync(
      fileURLToPath(
        new URL("../../components/files/ExtendedImage.vue", import.meta.url)
      ),
      "utf8"
    );
    expect(imageSource).toContain("const RAW_IMAGE_FALLBACK_DELAY_MS = 2000;");
    expect(imageSource).toContain("Let the real thumbnail finish");
  });
});
