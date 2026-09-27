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
const artPlayerSource = readFileSync(
  fileURLToPath(
    new URL("../../components/files/ArtPlayerVideo.vue", import.meta.url)
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
    expect(videoPlayerSource).toContain("isKnownIncompatibleVideo");
    expect(videoPlayerSource).toContain(
      "const sourceAttached = ref(!isKnownIncompatibleVideo(props.path))"
    );
    expect(videoPlayerSource).toContain("const initialSource");
    expect(videoPlayerSource).toContain("src: props.source");
    expect(videoPlayerSource).toContain("{ sources: [] }");
    expect(videoPlayerSource).toContain(
      "getVideoSourceType(props.source, props.path)"
    );
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

  it("ArtPlayer 默认引擎保留显式 Video.js 诊断入口", () => {
    const previewSource = readFileSync(
      fileURLToPath(new URL("../../views/files/Preview.vue", import.meta.url)),
      "utf8"
    );
    expect(previewSource).toContain('requested !== "videojs"');
    expect(previewSource).toContain("<ArtPlayerVideo");
    expect(previewSource).toContain("<VideoPlayer");
  });

  it("原生播放失败提供明确兼容动作并尊重账户控件隐藏时长", () => {
    const previewSource = readFileSync(
      fileURLToPath(new URL("../../views/files/Preview.vue", import.meta.url)),
      "utf8"
    );
    expect(artPlayerSource).toContain("@click=\"chooseMode('compat')\"");
    expect(previewSource).toContain("resolveControlsTimeoutMs(");
    expect(previewSource).toContain("if (hideMs <= 0)");
  });

  it("ArtPlayer 切换媒体保留进度并完整释放资源", () => {
    expect(artPlayerSource).toContain("const resume = captureResume()");
    expect(artPlayerSource).toContain("applyResume(resume)");
    expect(artPlayerSource).toContain("hlsInstance.destroy()");
    expect(artPlayerSource).toContain("clearNativeProgressHooks()");
    expect(artPlayerSource).toContain("stopLoadWaitTimer()");
    expect(artPlayerSource).toContain("++switchToken");
    expect(artPlayerSource).toContain("art.value?.destroy(false)");
    expect(artPlayerSource).toContain('xhr.setRequestHeader("X-Auth", jwt)');
  });

  it("字幕与续播偏好按账号保存且不记录鉴权参数", () => {
    expect(artPlayerSource).toContain(
      '`nas-file-browser-subtitle-v1:${authStore.user?.id ?? "guest"}`'
    );
    expect(artPlayerSource).toContain("resumeMinSec");
    expect(artPlayerSource).toContain("playerStorageId()");
    expect(artPlayerSource).toContain("accountPreferences.save(");
    expect(artPlayerSource).not.toContain("?auth=");
  });

  it("进度预览失败不阻断播放器初始化", () => {
    expect(artPlayerSource).toContain("mediaApi.getVideoSprite(props.path)");
    expect(artPlayerSource).toContain("art.value.thumbnails =");
    expect(artPlayerSource).toContain(
      "Progress thumbnails are optional; playback remains fully functional."
    );
  });

  it("等比例缩略图使用独立接口参数和 contain 展示", () => {
    const thumbnailSource = readFileSync(
      fileURLToPath(
        new URL("../../components/files/FileThumbnail.vue", import.meta.url)
      ),
      "utf8"
    );
    expect(thumbnailSource).toContain('{ fit: "contain" }');
    expect(thumbnailSource).toContain("thumbnail-image--contain");
  });
});
