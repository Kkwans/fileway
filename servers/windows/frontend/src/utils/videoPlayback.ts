const KNOWN_INCOMPATIBLE_VIDEO_EXTENSIONS = new Set([
  "mkv",
  "avi",
  "flv",
  "wmv",
  "rm",
  "rmvb",
  // Chromium commonly rejects the QuickTime container before issuing a media
  // request; keep the source detached until the user chooses a playback
  // path instead of leaving the player in an apparent loading state.
  "mov",
]);

const VIDEO_MIME_TYPES: Record<string, string> = {
  mp4: "video/mp4",
  m4v: "video/mp4",
  webm: "video/webm",
  ogg: "video/ogg",
  ogv: "video/ogg",
  mov: "video/quicktime",
  mkv: "video/x-matroska",
  avi: "video/x-msvideo",
  wmv: "video/x-ms-wmv",
  flv: "video/x-flv",
  rm: "application/vnd.rn-realmedia",
  rmvb: "application/vnd.rn-realmedia-vbr",
  ts: "video/mp2t",
  m2ts: "video/mp2t",
  "3gp": "video/3gpp",
};

const VIDEO_CODEC_PREFLIGHT_EXTENSIONS = new Set(["mp4", "m4v"]);
const DEFINITELY_UNSUPPORTED_BROWSER_CODECS = new Set([
  "hevc",
  "h265",
  "x265",
  "mpeg2video",
  "mpeg2",
  "mpeg1video",
  "mpeg1",
  "vc1",
  "wmv1",
  "wmv2",
  "wmv3",
  "rv10",
  "rv20",
  "rv30",
  "rv40",
  "realvideo",
]);

function extensionOf(value: string) {
  const path = value.split("?", 1)[0].split("#", 1)[0];
  const fileName = path.slice(path.lastIndexOf("/") + 1);
  const separator = fileName.lastIndexOf(".");
  if (separator < 0 || separator === fileName.length - 1) return "";
  return fileName.slice(separator + 1).toLowerCase();
}

export function isKnownIncompatibleVideo(path: string) {
  // Always try the direct source first (MKV/MOV/etc.). Chromium may still
  // decode common tracks; forcing HLS without ffmpeg is worse UX.
  // Compatibility playback remains available after a real media error.
  return false;
}

/**
 * MP4/M4V can contain codecs that look browser-friendly from the extension
 * alone (for example HEVC). Probe those containers before attaching the raw
 * source so Chromium does not read megabytes of an unplayable file first.
 */
export function shouldPreflightVideoCodec(path: string) {
  return VIDEO_CODEC_PREFLIGHT_EXTENSIONS.has(extensionOf(path));
}

function normalizeCodec(codec?: string) {
  return (
    codec
      ?.trim()
      .toLowerCase()
      .replace(/[^a-z0-9g]/g, "") ?? ""
  );
}

export function isHevcCodec(codec?: string) {
  const n = normalizeCodec(codec);
  return (
    n.includes("hevc") ||
    n.includes("h265") ||
    n.includes("x265") ||
    n.startsWith("hev1") ||
    n.startsWith("hvc1")
  );
}

export type NativeContainerPlayback = "supported" | "unsupported" | "unknown";

const NATIVE_WEBM_VIDEO_CODECS = new Set(["vp8", "vp9", "av1"]);
const NATIVE_WEBM_AUDIO_CODECS = new Set(["opus", "vorbis"]);

/**
 * Returns a conservative decision for containers that are not safe to attach
 * from the extension alone.  Chromium can decode WebM-compatible tracks from
 * a Matroska file even when `canPlayType("video/x-matroska")` only says
 * `maybe`; codec metadata lets us avoid both unnecessary remuxing and unsafe
 * reads of H.264/unknown files.
 */
export function getNativeContainerPlayback(
  path: string,
  videoCodec?: string,
  audioCodec?: string
): NativeContainerPlayback {
  if (extensionOf(path) !== "mkv") return "unknown";

  const video = normalizeCodec(videoCodec);
  const audio = normalizeCodec(audioCodec);
  if (!video) return "unknown";

  if (NATIVE_WEBM_VIDEO_CODECS.has(video)) {
    if (audio && !NATIVE_WEBM_AUDIO_CODECS.has(audio)) return "unsupported";
    if (typeof document === "undefined") return "unknown";
    const codecs = audio ? `${video},${audio}` : video;
    const support = document
      .createElement("video")
      .canPlayType(`video/webm; codecs="${codecs}"`);
    return /maybe|probably/i.test(support) ? "supported" : "unsupported";
  }

  // H.264 is only safe to attach when the active browser advertises a
  // proprietary decoder.  The current NAS Chromium does not, so it is
  // rejected before any large Range request is made.
  if (video === "h264") {
    return supportsH264CompatibilityPlayback() ? "unknown" : "unsupported";
  }
  if (isDefinitelyUnsupportedVideoCodec(video)) return "unsupported";
  return "unknown";
}

export function isDefinitelyUnsupportedVideoCodec(codec?: string) {
  const normalized = normalizeCodec(codec);
  return (
    normalized.length > 0 &&
    DEFINITELY_UNSUPPORTED_BROWSER_CODECS.has(normalized)
  );
}

export function getVideoSourceType(source: string, fallbackPath = "") {
  // The resource path is authoritative; signed/download URLs may end in an
  // endpoint suffix that does not describe the media file.
  const extension = extensionOf(fallbackPath) || extensionOf(source);
  return VIDEO_MIME_TYPES[extension] ?? "";
}

export type DirectVideoFailure =
  | "network"
  | "decode"
  | "unsupported"
  | "unknown";

export function getDirectVideoFailure(
  errorCode?: number | null
): DirectVideoFailure {
  if (errorCode === 2) return "network";
  if (errorCode === 3) return "decode";
  if (errorCode === 4) return "unsupported";
  return "unknown";
}

export function getDirectVideoFailureCopy(
  failure: DirectVideoFailure,
  videoCodec?: string
) {
  const isHevc = isHevcCodec(videoCodec);

  if (isHevc && (failure === "decode" || failure === "unsupported" || failure === "unknown")) {
    return {
      icon: "movie_filter",
      title: "当前浏览器无法解码 H.265 / HEVC",
      description:
        "Chrome/Edge 桌面版通常不支持 HEVC 硬解，可能出现黑屏、极慢或只有声音。可一键「兼容播放」（服务端转 H.264/WebM），或下载后用本地播放器打开。手机浏览器有时能播 HEVC，故手机端可能正常。",
    };
  }

  switch (failure) {
    case "network":
      return {
        icon: "cloud_off",
        title: "视频源暂时无法读取",
        description:
          "网络连接或视频源响应异常；这不能说明视频格式不受支持。可先重试原视频，或下载后检查文件。",
      };
    case "decode":
      return {
        icon: "error_outline",
        title: "当前浏览器无法解码此视频",
        description:
          "浏览器已经读取视频源，但无法解码其中的音视频轨道。可启动兼容播放，原文件不会修改。",
      };
    case "unsupported":
      return {
        icon: "movie_filter",
        title: "当前浏览器不支持此视频格式",
        description:
          "浏览器明确拒绝了当前视频源。可启动兼容播放，兼容文件生成后支持拖动进度，原文件不会修改。",
      };
    default:
      return {
        icon: "error_outline",
        title: "原视频播放失败",
        description:
          "播放器没有返回可确认的失败原因。可重试原视频、启动兼容播放，或下载后使用本地播放器。",
      };
  }
}

/**
 * The bundled Chromium on some NAS installations deliberately omits the
 * proprietary H.264 decoder.  Video.js then rejects HLS before requesting
 * the playlist, so compatibility playback must use the browser's WebM path.
 */
export function supportsH264CompatibilityPlayback() {
  if (typeof window === "undefined") return true;
  const mediaSource = window.MediaSource;
  if (mediaSource && typeof mediaSource.isTypeSupported === "function") {
    if (
      mediaSource.isTypeSupported(
        'video/mp4; codecs="avc1.4d401f,mp4a.40.2"'
      ) ||
      mediaSource.isTypeSupported('video/mp4; codecs="avc1.4d400d,mp4a.40.2"')
    ) {
      return true;
    }
  }
  const video = document.createElement("video");
  // MP4 playback and HLS/MSE support are separate browser capabilities. A
  // desktop browser may decode H.264/AAC MP4 while deliberately omitting HLS;
  // prefer the lossless MP4 remux in that case instead of forcing a VP9 encode.
  if (
    /maybe|probably/i.test(
      video.canPlayType('video/mp4; codecs="avc1.4d401f,mp4a.40.2"')
    )
  ) {
    return true;
  }
  return /maybe|probably/i.test(
    video.canPlayType("application/vnd.apple.mpegurl")
  );
}

/**
 * A growing HLS playlist can expose metadata before it exposes the segment
 * containing a saved position.  Do not call currentTime() until the browser
 * reports that the target is inside its seekable range; otherwise Video.js
 * silently clamps the seek to zero and the resume chip becomes misleading.
 */
export function isPlaybackPositionSeekable(
  position: number,
  seekableStart: number,
  seekableEnd: number,
  duration: number
) {
  if (!Number.isFinite(position) || position < 0) return false;
  if (Number.isFinite(seekableStart) && Number.isFinite(seekableEnd)) {
    return position >= seekableStart && position <= seekableEnd;
  }
  return Number.isFinite(duration) && duration > 0 && position <= duration;
}
