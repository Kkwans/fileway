<template>
  <div
    id="previewer"
    :class="{ 'media-preview-shell': isUnifiedMedia }"
    @touchmove="onPreviewTouchMove"
    @wheel="onPreviewWheel"
    @mousemove="toggleNavigation"
    @touchstart="toggleNavigation"
  >
    <header-bar
      v-if="isPdf || isEpub || isCsv || showNav || mediaInfoOpen"
      :class="{ 'media-preview-header': isUnifiedMedia }"
    >
      <action app-icon="x" label="关闭" @action="close()" />
      <span class="header-title" :title="name">{{ name }}</span>
      <action
        :disabled="layoutStore.loading"
        v-if="isResizeEnabled && fileStore.req?.type === 'image'"
        :app-icon="fullSize ? 'minimize-2' : 'maximize-2'"
        @action="toggleSize"
      />

      <template #actions>
        <action
          v-if="isUnifiedMedia"
          :disabled="favoritePending"
          :app-icon="isCurrentFavorite ? 'heart-filled' : 'heart'"
          :active="isCurrentFavorite"
          :label="isCurrentFavorite ? '取消收藏' : '收藏'"
          @action="toggleCurrentFavorite"
        />
        <action
          v-if="isUnifiedMedia"
          :app-icon="isFullscreen ? 'minimize-2' : 'maximize'"
          :label="isFullscreen ? '退出全屏' : '全屏'"
          @action="toggleFullscreen"
        />
        <action
          :disabled="layoutStore.loading"
          v-if="authStore.user?.perm.rename"
          app-icon="rename"
          label="重命名"
          show="rename"
        />
        <action
          :disabled="layoutStore.loading"
          v-if="isCsv && authStore.user?.perm.modify"
          app-icon="text"
          label="编辑文本"
          @action="editAsText"
        />
        <action
          :disabled="layoutStore.loading"
          v-if="authStore.user?.perm.delete"
          app-icon="trash"
          label="删除"
          @action="deleteFile"
          id="delete-button"
        />
        <action
          :disabled="layoutStore.loading"
          v-if="authStore.user?.perm.download"
          app-icon="download"
          label="下载"
          @action="download"
        />
        <action
          :disabled="layoutStore.loading"
          v-if="
            ['image', 'audio', 'video'].includes(fileStore.req?.type || '') &&
            authStore.user?.perm.download
          "
          app-icon="external-link"
          label="直接打开"
          @action="openDirect"
        />
        <action
          :disabled="layoutStore.loading"
          app-icon="info"
          label="文件信息"
          :show="isUnifiedMedia ? undefined : 'info'"
          @action="isUnifiedMedia && (mediaInfoOpen = !mediaInfoOpen)"
        />
      </template>
    </header-bar>

    <div class="loading delayed" v-if="layoutStore.loading">
      <div class="spinner">
        <div class="bounce1"></div>
        <div class="bounce2"></div>
        <div class="bounce3"></div>
      </div>
    </div>
    <template v-else>
      <div class="preview">
        <div v-if="isEpub" class="epub-reader">
          <vue-reader
            :location="location"
            :url="previewUrl"
            :get-rendition="getRendition"
            :epubInitOptions="{
              requestCredentials: true,
            }"
            :epubOptions="{
              allowPopups: true,
            }"
            @update:location="locationChange"
          />
          <div class="size">
            <button
              type="button"
              @click="changeSize(Math.max(100, size - 10))"
              class="reader-button"
              aria-label="缩小字号"
            >
              <AppIcon :name="mediaIcon('remove')" :size="18" />
            </button>
            <button
              type="button"
              @click="changeSize(Math.min(150, size + 10))"
              class="reader-button"
              aria-label="放大字号"
            >
              <AppIcon :name="mediaIcon('add')" :size="18" />
            </button>
            <span>{{ size }}%</span>
          </div>
        </div>
        <CsvViewer v-else-if="isCsv" :content="csvContent" :error="csvError" />
        <ExtendedImage
          v-else-if="fileStore.req?.type == 'image'"
          :src="previewUrl"
          :placeholder-src="imagePlaceholderUrl"
          :defer-full-until-placeholder="isLargeJpegPreview"
          :fallback-placeholder-src="imageFallbackPlaceholderUrl"
          :fileName="name"
          :fileSizeBytes="fileStore.req?.size || 0"
          :directSrc="directUrl"
          :downloadSrc="downloadUrl"
          @ready="onCurrentImageReady"
        />
        <AudioPreview v-else-if="fileStore.req?.type == 'audio'" :name="name" />
        <ArtPlayerVideo
          v-if="useArtPlayer && fileStore.req?.type == 'video'"
          :key="'art-' + fileStore.req.path"
          :path="fileStore.req.path"
          :source="previewUrl"
          :poster="videoPosterUrl"
          :download-source="downloadUrl"
          :subtitles="subtitleItems"
        />
        <VideoPlayer
          v-else-if="fileStore.req?.type == 'video'"
          ref="player"
          :path="fileStore.req.path"
          :source="previewUrl"
          :poster="videoPosterUrl"
          :download-source="downloadUrl"
          :direct-source="directUrl"
          :subtitles="subtitles"
          :options="videoOptions"
        >
        </VideoPlayer>
        <PdfViewer v-else-if="isPdf" :src="previewUrl" :name="name" />
        <div v-else-if="fileStore.req?.type == 'blob'" class="info">
          <div class="title">
            <AppIcon :name="mediaIcon('feedback')" :size="42" />
            此文件无法预览
          </div>
          <div>
            <a target="_blank" :href="downloadUrl" class="button button--flat">
              <div>
                <AppIcon :name="mediaIcon('file_download')" :size="20" />下载
              </div>
            </a>
            <a
              target="_blank"
              :href="previewUrl"
              class="button button--flat"
              v-if="!fileStore.req?.isDir"
            >
              <div>
                <AppIcon :name="mediaIcon('open_in_new')" :size="20" />打开文件
              </div>
            </a>
          </div>
        </div>
      </div>
    </template>

    <button
      @click="prev"
      @mouseover="hoverNav = true"
      @mouseleave="hoverNav = false"
      :class="[
        'preview-nav',
        'preview-nav--previous',
        { hidden: !hasPrevious || !showNav },
      ]"
      aria-label="上一个"
      title="上一个"
    >
      <AppIcon :name="mediaIcon('chevron_left')" :size="28" />
    </button>
    <button
      @click="next"
      @mouseover="hoverNav = true"
      @mouseleave="hoverNav = false"
      :class="[
        'preview-nav',
        'preview-nav--next',
        { hidden: !hasNext || !showNav },
      ]"
      aria-label="下一个"
      title="下一个"
    >
      <AppIcon :name="mediaIcon('chevron_right')" :size="28" />
    </button>
    <link v-if="nextPrefetchEnabled" rel="prefetch" :href="nextRaw" />
    <MediaInfoPanel
      v-if="isUnifiedMedia && fileStore.req"
      :open="mediaInfoOpen"
      :resource="fileStore.req"
      @close="mediaInfoOpen = false"
    />
  </div>
</template>

<script setup lang="ts">
import { useStorage } from "@vueuse/core";
import { useAuthStore } from "@/stores/auth";
import { resolveControlsTimeoutMs } from "@/utils/playerControls";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import { useMediaStore } from "@/stores/media";
import { useFavoritesStore } from "@/stores/favorites";

import { files as api } from "@/api";
import { createURL } from "@/api/utils";
import { resizePreview } from "@/utils/constants";
import url from "@/utils/url";
import { throttle } from "lodash-es";
import HeaderBar from "@/components/header/HeaderBar.vue";
import Action from "@/components/header/Action.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import {
  computed,
  defineAsyncComponent,
  inject,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import type { Rendition } from "epubjs";
import type { ResourceItem, ResourceType } from "@/types/file";
import { getTheme } from "@/utils/theme";
import {
  directoryAudioQueue,
  favoriteGroupAudioQueue,
} from "@/utils/audioQueue";
import { isKnownIncompatibleVideo } from "@/utils/videoPlayback";
import { mediaIcon } from "@/utils/mediaIconSemantics";

const ExtendedImage = defineAsyncComponent(
  () => import("@/components/files/ExtendedImage.vue")
);
const VideoPlayer = defineAsyncComponent(
  () => import("@/components/files/VideoPlayer.vue")
);
const ArtPlayerVideo = defineAsyncComponent(
  () => import("@/components/files/ArtPlayerVideo.vue")
);

function detectArtPlayer() {
  // Trial branch: ArtPlayer is the default engine for hands-on testing.
  // Set ?player=videojs or localStorage engine=videojs to fall back.
  try {
    if (typeof window === "undefined") return true;
    const q = new URLSearchParams(window.location.search).get("player");
    if (q === "videojs" || q === "video.js") return false;
    const engine = localStorage.getItem("win-file-browser-player-engine");
    if (engine === "videojs") return false;
    return true;
  } catch {
    return true;
  }
}
const useArtPlayer = detectArtPlayer();
const AudioPreview = defineAsyncComponent(
  () => import("@/components/files/AudioPreview.vue")
);
const CsvViewer = defineAsyncComponent(
  () => import("@/components/files/CsvViewer.vue")
);
const PdfViewer = defineAsyncComponent(
  () => import("@/components/files/PdfViewer.vue")
);
const MediaInfoPanel = defineAsyncComponent(
  () => import("@/components/files/MediaInfoPanel.vue")
);
const VueReader = defineAsyncComponent(() =>
  import("vue-reader").then((module) => module.VueReader)
);
// CSV file size limit for preview (5MB)
// Prevents browser memory issues with large files
const CSV_MAX_SIZE = 5 * 1024 * 1024;

const location = useStorage("book-progress", 0, undefined, {
  serializer: {
    read: (v) => JSON.parse(v),
    write: (v) => JSON.stringify(v),
  },
});
const size = useStorage("book-size", 120, undefined, {
  serializer: {
    read: (v) => JSON.parse(v),
    write: (v) => JSON.stringify(v),
  },
});

const locationChange = (epubcifi: number) => {
  location.value = epubcifi;
};
let rendition: Rendition | null = null;
const changeSize = (val: number) => {
  size.value = val;
  rendition?.themes.fontSize(`${val}%`);
};

const getRendition = (_rendition: Rendition) => {
  rendition = _rendition;
  switch (getTheme()) {
    case "dark": {
      rendition.themes.override("color", "rgba(255, 255, 255, 0.6)");
      break;
    }
    case "light": {
      rendition.themes.override("color", "rgb(111, 111, 111)");
      break;
    }
  }
  rendition.themes.registerRules("h2Transparent", {
    "h1,h2,h3,h4": {
      "background-color": "transparent !important",
    },
  });
  rendition?.themes.fontSize(`${size.value}%`);
  rendition.themes.select("h2Transparent");
  rendition.themes.override("background-color", "transparent", true);
};

const mediaTypes: ResourceType[] = ["image", "video", "audio", "blob"];
const isMediaResource = (type: ResourceType | undefined) =>
  type !== undefined && mediaTypes.includes(type);

const previousLink = ref<string>("");
const nextLink = ref<string>("");
const listing = ref<ResourceItem[] | null>(null);
const name = ref<string>("");
const fullSize = ref<boolean>(false);
const showNav = ref<boolean>(true);
const navTimeout = ref<null | number>(null);
const hoverNav = ref<boolean>(false);
const autoPlay = ref<boolean>(false);
const nextRaw = ref<string>("");
const currentImageReady = ref(false);
const nextPrefetchEnabled = ref(false);
const csvContent = ref<ArrayBuffer | string>("");
const csvError = ref<string>("");
const mediaInfoOpen = ref(false);
const isFullscreen = ref(false);
const favoritePending = ref(false);

const player = ref<HTMLVideoElement | HTMLAudioElement | null>(null);

let previewGeneration = 0;
let prefetchTimeout: number | null = null;
let prefetchIdleId: number | null = null;
let listingLoadTimer: number | null = null;
let listingRequestController: AbortController | null = null;
const MEDIA_LISTING_DELAY_MS = 250;

type IdleWindow = Window & {
  requestIdleCallback?: (
    callback: () => void,
    options?: { timeout: number }
  ) => number;
  cancelIdleCallback?: (handle: number) => void;
};

const cancelNextPrefetch = () => {
  if (prefetchTimeout !== null) {
    window.clearTimeout(prefetchTimeout);
    prefetchTimeout = null;
  }
  const idleWindow = window as IdleWindow;
  if (prefetchIdleId !== null && idleWindow.cancelIdleCallback) {
    idleWindow.cancelIdleCallback(prefetchIdleId);
    prefetchIdleId = null;
  }
};

const cancelMediaListingLoad = () => {
  if (listingLoadTimer !== null) {
    window.clearTimeout(listingLoadTimer);
    listingLoadTimer = null;
  }
  listingRequestController?.abort();
  listingRequestController = null;
};

const resetMediaPrefetch = () => {
  previewGeneration += 1;
  cancelNextPrefetch();
  cancelMediaListingLoad();
  currentImageReady.value = false;
  nextPrefetchEnabled.value = false;
  nextRaw.value = "";
};

const scheduleNextPrefetch = () => {
  cancelNextPrefetch();
  if (!currentImageReady.value || !nextRaw.value) return;
  const generation = previewGeneration;
  const enable = () => {
    prefetchTimeout = null;
    prefetchIdleId = null;
    if (generation === previewGeneration && nextRaw.value) {
      nextPrefetchEnabled.value = true;
    }
  };
  const idleWindow = window as IdleWindow;
  if (idleWindow.requestIdleCallback) {
    prefetchIdleId = idleWindow.requestIdleCallback(enable, { timeout: 2000 });
  } else {
    // Keep older browsers from competing with the current image's decode and
    // paint; this is the idle fallback when requestIdleCallback is absent.
    prefetchTimeout = window.setTimeout(enable, 400);
  }
};

const onCurrentImageReady = () => {
  if (fileStore.req?.type !== "image") return;
  currentImageReady.value = true;
  scheduleNextPrefetch();
};

const $showError = inject<IToastError>("$showError")!;

const authStore = useAuthStore();
const fileStore = useFileStore();
const layoutStore = useLayoutStore();
const mediaStore = useMediaStore();
const favoritesStore = useFavoritesStore();

const route = useRoute();
const router = useRouter();

const hasPrevious = computed(() => previousLink.value !== "");

const hasNext = computed(() => nextLink.value !== "");

const downloadUrl = computed(() =>
  fileStore.req ? api.getDownloadURL(fileStore.req, false) : ""
);

const directUrl = computed(() =>
  fileStore.req ? api.getDownloadURL(fileStore.req, true) : ""
);

const previewUrl = computed(() => {
  if (!fileStore.req) {
    return "";
  }

  if (fileStore.req.type === "image" && !fullSize.value) {
    return api.getPreviewURL(fileStore.req, "big");
  }

  if (isEpub.value) {
    return createURL("api/raw" + fileStore.req.path, {});
  }

  return api.getDownloadURL(fileStore.req, true);
});

const LARGE_JPEG_PREVIEW_MIN_BYTES = 4 * 1024 * 1024;
const isLargeJpegPreview = computed(() => {
  const resource = fileStore.req;
  if (!resource || resource.type !== "image" || fullSize.value) return false;
  const extension = resource.extension.toLowerCase();
  return (
    resource.size >= LARGE_JPEG_PREVIEW_MIN_BYTES &&
    (extension === ".jpg" || extension === ".jpeg")
  );
});

const imagePlaceholderUrl = computed(() => {
  if (fileStore.req?.type !== "image") return "";
  if (fullSize.value) return api.getPreviewURL(fileStore.req, "thumb");
  // A cold 1080px decode can take seconds on the NAS ARM CPU.  Large JPEGs
  // must paint their real thumbnail first; the full preview starts only after
  // that thumbnail has loaded, instead of hiding the first useful pixels
  // behind a `warm=big` request.
  if (isLargeJpegPreview.value)
    return api.getPreviewURL(fileStore.req, "thumb");
  return api.getPreviewURL(fileStore.req, "thumb", { warm: "big" });
});

const imageFallbackPlaceholderUrl = computed(() => {
  if (!isLargeJpegPreview.value || !fileStore.req) return "";
  return api.getPreviewURL(fileStore.req, "thumb");
});

const videoPosterUrl = computed(() => {
  const resource = fileStore.req;
  if (!resource || resource.type !== "video") return "";
  // 不为浏览器无法直接播放的格式提前触发封面请求；用户点击兼容播放后再生成。
  if (isKnownIncompatibleVideo(resource.path)) return "";
  return api.getPreviewURL(resource, "thumb");
});

const isPdf = computed(() => fileStore.req?.extension.toLowerCase() == ".pdf");
const isEpub = computed(
  () => fileStore.req?.extension.toLowerCase() == ".epub"
);
const isCsv = computed(
  () =>
    fileStore.req?.extension.toLowerCase() == ".csv" &&
    fileStore.req.size <= CSV_MAX_SIZE
);

const isResizeEnabled = computed(() => resizePreview);
const isUnifiedMedia = computed(() =>
  ["image", "video", "audio"].includes(fileStore.req?.type ?? "")
);
const isCurrentFavorite = computed(() =>
  fileStore.req ? favoritesStore.isFavorite(fileStore.req.path) : false
);

const subtitles = computed(() => {
  if (fileStore.req?.subtitles) {
    return api.getSubtitlesURL(fileStore.req);
  }
  return [];
});

const subtitleItems = computed(() =>
  (subtitles.value || []).map((url) => ({
    url,
    name: url.split("/").pop() || "字幕",
  }))
);

const videoOptions = computed(() => {
  return { autoplay: autoPlay.value };
});

watch(route, () => {
  resetMediaPrefetch();
  mediaInfoOpen.value = false;
  updatePreview(previewGeneration);
  toggleNavigation();
});

// Specify hooks
onMounted(async () => {
  window.addEventListener("keydown", key);
  document.addEventListener("fullscreenchange", onFullscreenChange);
  listing.value = fileStore.oldReq?.items ?? null;
  updatePreview(previewGeneration);
});

onBeforeUnmount(() => {
  cancelNextPrefetch();
  cancelMediaListingLoad();
  window.removeEventListener("keydown", key);
  document.removeEventListener("fullscreenchange", onFullscreenChange);
});

// Specify methods
const deleteFile = () => {
  layoutStore.showHover({
    prompt: "delete",
    confirm: () => {
      if (listing.value === null) {
        return;
      }

      const index = listing.value.findIndex((item) => item.name == name.value);
      listing.value.splice(index, 1);

      if (hasNext.value) {
        next();
      } else if (!hasPrevious.value && !hasNext.value) {
        const nearbyItem = listing.value[Math.max(0, index - 1)];
        fileStore.preselect = nearbyItem?.path;

        close();
      } else {
        prev();
      }
    },
  });
};

const prev = () => {
  hoverNav.value = false;
  router.replace({ path: previousLink.value });
};

const next = () => {
  hoverNav.value = false;
  router.replace({ path: nextLink.value });
};

const key = (event: KeyboardEvent) => {
  if (layoutStore.currentPrompt !== null) {
    return;
  }
  // When previewing a video, let arrow keys fall through to video.js for
  // seeking instead of switching to the prev/next file. Enter still advances.
  const isVideo = fileStore.req?.type === "video";
  if (event.which === 13) {
    // enter
    if (hasNext.value) next();
  } else if (event.which === 39) {
    // right arrow
    if (isVideo) return;
    if (hasNext.value) next();
  } else if (event.which === 37) {
    // left arrow
    if (isVideo) return;
    if (hasPrevious.value) prev();
  } else if (event.which === 27) {
    // esc
    close();
  }
};

const scheduleMediaListingLoad = (generation: number) => {
  cancelMediaListingLoad();
  listingLoadTimer = window.setTimeout(() => {
    listingLoadTimer = null;
    void loadListing(generation);
  }, MEDIA_LISTING_DELAY_MS);
};

const loadListing = async (generation: number) => {
  if (generation !== previewGeneration || listing.value) return;

  const controller = new AbortController();
  listingRequestController = controller;
  try {
    const path = url.removeLastDir(route.path);
    const res = await api.fetch(path, controller.signal);
    if (generation !== previewGeneration) return;
    listing.value = res.items;
    updateNavigation(generation);
  } catch (e: any) {
    if (controller.signal.aborted || generation !== previewGeneration) return;
    $showError(e);
  } finally {
    if (listingRequestController === controller) {
      listingRequestController = null;
    }
  }
};

function updateNavigation(generation: number) {
  if (generation !== previewGeneration) return;

  syncAudioQueue();

  previousLink.value = "";
  nextLink.value = "";
  if (listing.value) {
    for (let i = 0; i < listing.value.length; i++) {
      if (listing.value[i].name !== name.value) {
        continue;
      }

      for (let j = i - 1; j >= 0; j--) {
        if (mediaTypes.includes(listing.value[j].type)) {
          previousLink.value = listing.value[j].url;
          break;
        }
      }
      for (let j = i + 1; j < listing.value.length; j++) {
        if (mediaTypes.includes(listing.value[j].type)) {
          nextLink.value = listing.value[j].url;
          nextRaw.value = prefetchUrl(listing.value[j]);
          if (currentImageReady.value) scheduleNextPrefetch();
          break;
        }
      }

      return;
    }
  }
}

const updatePreview = async (generation = previewGeneration) => {
  if (player.value && player.value.paused && !player.value.ended) {
    autoPlay.value = false;
  }

  const dirs = route.fullPath.split("/");
  name.value = decodeURIComponent(dirs[dirs.length - 1]);

  // Load CSV content if it's a CSV file
  if (isCsv.value && fileStore.req) {
    csvContent.value = "";
    csvError.value = "";

    if (fileStore.req.size > CSV_MAX_SIZE) {
      csvError.value = "CSV 文件过大";
    } else {
      if (fileStore.req.rawContent != null) {
        csvContent.value = fileStore.req.rawContent;
      } else {
        csvContent.value = fileStore.req.content ?? "";
      }
    }
  }

  if (!listing.value) {
    if (isMediaResource(fileStore.req?.type)) {
      // Directory listings are only needed for previous/next navigation. Let
      // the current media request win the first network/CPU slot, then load
      // the parent directory in the background.
      scheduleMediaListingLoad(generation);
      return;
    }
    try {
      const path = url.removeLastDir(route.path);
      const res = await api.fetch(path);
      if (generation !== previewGeneration) return;
      listing.value = res.items;
    } catch (e: any) {
      $showError(e);
    }
  }

  updateNavigation(generation);
};

const syncAudioQueue = () => {
  const current = fileStore.req;
  if (current?.type !== "audio") return;
  const favoriteGroupId =
    typeof route.query.mediaQueue === "string" ? route.query.mediaQueue : "";
  const favoriteQueue = favoriteGroupId
    ? favoriteGroupAudioQueue(favoritesStore.favorites, favoriteGroupId)
    : [];
  const queue =
    favoriteQueue.length > 0
      ? favoriteQueue
      : directoryAudioQueue(listing.value ?? []);
  if (queue.some((item) => item.path === current.path)) {
    mediaStore.openAudioQueue(queue, current.path, autoPlay.value);
  }
};

const prefetchUrl = (item: ResourceItem) => {
  if (item.type !== "image") {
    return "";
  }

  return fullSize.value
    ? api.getDownloadURL(item, true)
    : api.getPreviewURL(item, "big");
};

const toggleSize = () => {
  fullSize.value = !fullSize.value;
  resetMediaPrefetch();
  void updatePreview(previewGeneration);
};

const toggleCurrentFavorite = async () => {
  const current = fileStore.req;
  if (!current || favoritePending.value) return;
  favoritePending.value = true;
  try {
    await favoritesStore.toggleFavorite(current.path, current.name);
  } finally {
    favoritePending.value = false;
  }
};

const toggleFullscreen = async () => {
  const previewer = document.getElementById("previewer");
  if (!previewer) return;
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await previewer.requestFullscreen();
  } catch (error) {
    $showError(error instanceof Error ? error : new Error("无法切换全屏"));
  }
};

const onFullscreenChange = () => {
  isFullscreen.value = document.fullscreenElement?.id === "previewer";
};

const isMediaPanelEvent = (event: Event) =>
  event.target instanceof Element &&
  Boolean(event.target.closest(".media-info-panel"));

const onPreviewTouchMove = (event: TouchEvent) => {
  if (isMediaPanelEvent(event)) return;
  event.preventDefault();
  event.stopPropagation();
};

const onPreviewWheel = (event: WheelEvent) => {
  if (isMediaPanelEvent(event)) return;
  event.preventDefault();
  event.stopPropagation();
};

const toggleNavigation = throttle(function () {
  showNav.value = true;

  if (navTimeout.value) {
    clearTimeout(navTimeout.value);
  }

  const hideMs = resolveControlsTimeoutMs(
    authStore.user?.playerPreferences?.controlsTimeoutSec
  );
  // 0 = never hide (same account setting as player control bar)
  if (hideMs <= 0) {
    navTimeout.value = null;
    return;
  }
  navTimeout.value = window.setTimeout(() => {
    showNav.value = false || hoverNav.value;
    navTimeout.value = null;
  }, hideMs);
}, 250);

const close = () => {
  const uri = url.removeLastDir(route.path) + "/";
  router.push({ path: uri });
};

const download = () => {
  const a = document.createElement("a");
  a.href = downloadUrl.value;
  a.style.display = "none";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
};
const openDirect = () => window.open(directUrl.value);

const editAsText = () => {
  router.push({ path: route.path, query: { edit: "true" } });
};
</script>
