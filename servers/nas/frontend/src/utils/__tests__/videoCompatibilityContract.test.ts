import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const videoPlayerSource = readFileSync(
  fileURLToPath(
    new URL("../../components/files/VideoPlayer.vue", import.meta.url)
  ),
  "utf8"
);
const videoJsPatchSource = readFileSync(
  fileURLToPath(
    new URL("../../../patches/video.js@8.23.7.patch", import.meta.url)
  ),
  "utf8"
);
const pnpmWorkspaceSource = readFileSync(
  fileURLToPath(new URL("../../../pnpm-workspace.yaml", import.meta.url)),
  "utf8"
);
const customDockerfileSource = readFileSync(
  fileURLToPath(new URL("../../../../Dockerfile.custom", import.meta.url)),
  "utf8"
);

describe("视频兼容播放依赖契约", () => {
  it("组件不调用 VHS 私有 API 或依赖延迟重选源", () => {
    expect(videoPlayerSource).not.toContain("IWillNotUseThisInPlugins");
    expect(videoPlayerSource).not.toContain(".vhs");
    expect(videoPlayerSource).not.toContain("setupEme_");
    expect(videoPlayerSource).not.toContain("compatibilityRecoveryTimer");
    expect(videoPlayerSource).toContain(
      "const sourceURL = status.sourceUrl || status.playlistUrl"
    );
    expect(videoPlayerSource).toContain('type: isWebM ? "video/webm"');
    expect(videoPlayerSource).toContain('"application/x-mpegURL"');
    expect(videoPlayerSource).toContain("processedSeconds");
    expect(videoPlayerSource).toContain("durationSeconds");
    expect(videoPlayerSource).toContain('role="progressbar"');
    expect(videoPlayerSource).toContain("compatibilityProgressPercent");
    expect(videoPlayerSource).toContain("getDirectVideoFailure");
    expect(videoPlayerSource).toContain(
      "media-video-stage--compatibility-open"
    );
    expect(videoPlayerSource).toContain("media-compatibility-card__identity");
    expect(videoPlayerSource).toContain(
      'v-if="compatibilityProgressSummary && !compatibilityProgressVisible"'
    );
    expect(videoPlayerSource).toContain("完整兼容文件生成后即可拖动进度");
    expect(videoPlayerSource).toContain("兼容文件已生成，可拖动进度");
    expect(videoPlayerSource).toContain('status?.format === "webm-copy"');
    expect(videoPlayerSource).toContain('status?.format === "mp4-copy"');
    expect(videoPlayerSource).toContain(
      'supportsH264CompatibilityPlayback() ? "mp4" : "webm"'
    );
  });

  it("已知不兼容容器先读取编码，再自动放行浏览器明确支持的原生轨道", () => {
    expect(videoPlayerSource).toContain("getNativeContainerPlayback");
    expect(videoPlayerSource).toContain("mediaApi.getMediaInformation");
    expect(videoPlayerSource).toContain("probeNativeContainer");
    expect(videoPlayerSource).toContain("nativeProbeController");
    expect(videoPlayerSource).toContain("nativeProbeBusy");
    expect(videoPlayerSource).toContain("controller.signal");
  });

  it("对无 DRM 的 HLS 通过受管补丁解除 EME 初始化等待", () => {
    expect(videoJsPatchSource).toContain("if (!this.source_.keySystems)");
    expect(videoJsPatchSource).toContain(
      "this.playlistController_.sourceUpdater_.initializedEme()"
    );
    expect(pnpmWorkspaceSource).toContain(
      "video.js@8.23.7: patches/video.js@8.23.7.patch"
    );
    expect(customDockerfileSource).toContain("frontend/pnpm-workspace.yaml");
    expect(customDockerfileSource).toContain(
      "COPY frontend/patches/ ./patches/"
    );
    expect(
      customDockerfileSource.indexOf("COPY frontend/patches/")
    ).toBeLessThan(
      customDockerfileSource.indexOf("pnpm install --frozen-lockfile")
    );
  });
});
