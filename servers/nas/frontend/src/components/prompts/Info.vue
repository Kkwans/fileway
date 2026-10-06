<template>
  <div class="card floating info-dialog">
    <!-- Header with icon -->
    <div class="card-title info-header">
      <div class="info-title-row">
        <AppIcon :name="infoIcon" class="info-type-icon" :size="32" />
        <div class="info-title-text">
          <h2 v-if="selectedCount > 1">
            {{ "已选择 " + selectedCount + " 个项目" }}
          </h2>
          <input
            v-else
            v-model="editableName"
            class="info-name-input"
            type="text"
            maxlength="255"
            aria-label="文件名称"
            @keyup.enter="confirmInfo"
          />
        </div>
      </div>
    </div>

    <div class="card-content info-content">
      <!-- Multi-selection banner -->
      <div v-if="selectedCount > 1" class="info-banner info-banner-multi">
        <AppIcon name="select" :size="20" />
        <span>已选择 {{ selectedCount }} 个文件/文件夹</span>
      </div>

      <!-- Basic info rows -->
      <div class="info-row" v-if="selectedCount < 2">
        <span class="info-label">类型</span>
        <span class="info-value">{{ fileTypeLabel }}</span>
      </div>

      <div class="info-row" v-if="!dir || selectedCount > 1">
        <span class="info-label">大小</span>
        <span class="info-value info-size">
          {{ humanSize }}（{{ rawSize.toLocaleString("zh-CN") }} 字节）
        </span>
      </div>

      <div class="info-row" v-if="selectedCount < 2">
        <span class="info-label">路径</span>
        <div class="info-value info-path-value">
          <code>{{ displayPath }}</code>
          <button
            class="copy-path-button"
            type="button"
            :aria-label="pathCopied ? '已复制路径' : '复制路径'"
            :title="pathCopied ? '已复制' : '复制路径'"
            @click="copyFullPath"
          >
            <AppIcon :name="pathCopied ? 'circle-check' : 'copy'" :size="18" />
          </button>
        </div>
      </div>

      <div class="info-row" v-if="resolution">
        <span class="info-label">分辨率</span>
        <span class="info-value"
          >{{ resolution.width }} × {{ resolution.height }}</span
        >
      </div>

      <div class="info-row" v-if="selectedCount < 2">
        <span class="info-label">修改时间</span>
        <span class="info-value">{{ humanTime }}</span>
      </div>

      <div class="info-row" v-if="selectedCount < 2 && creationTime">
        <span class="info-label">创建时间</span>
        <span class="info-value">{{ creationTime }}</span>
      </div>

      <!-- Directory info -->
      <template v-if="isDirectoryInfo">
        <div class="info-divider"></div>
        <div class="info-row">
          <span class="info-label">文件数</span>
          <span class="info-value">{{
            directoryStats?.files ?? "统计中..."
          }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">文件夹</span>
          <span class="info-value">{{
            directoryStats?.directories ?? "统计中..."
          }}</span>
        </div>
        <div class="info-row" v-if="directoryStats">
          <span class="info-label">总大小</span>
          <span class="info-value info-size">
            {{ filesize(directoryStats.size) }}（{{
              directoryStats.size.toLocaleString("zh-CN")
            }}
            字节）
          </span>
        </div>
        <p v-if="statsError" class="info-stats-error">{{ statsError }}</p>
      </template>

      <!-- Checksums (collapsible) -->
      <template v-if="!dir">
        <div class="info-collapse">
          <button
            class="info-collapse-btn"
            @click="showChecksums = !showChecksums"
            type="button"
          >
            <AppIcon
              :name="showChecksums ? 'chevron-up' : 'chevron-down'"
              :size="18"
            />
            <span>文件校验</span>
          </button>
          <div v-if="showChecksums" class="info-collapse-content">
            <div class="info-row info-checksum">
              <span class="info-label">MD5</span>
              <span class="info-value"
                ><a @click="checksum($event, 'md5')" tabindex="2"
                  >点击以显示</a
                ></span
              >
            </div>
            <div class="info-row info-checksum">
              <span class="info-label">SHA1</span>
              <span class="info-value"
                ><a @click="checksum($event, 'sha1')" tabindex="3"
                  >点击以显示</a
                ></span
              >
            </div>
            <div class="info-row info-checksum">
              <span class="info-label">SHA256</span>
              <span class="info-value"
                ><a @click="checksum($event, 'sha256')" tabindex="4"
                  >点击以显示</a
                ></span
              >
            </div>
          </div>
        </div>
      </template>
    </div>

    <div class="card-action info-actions">
      <button
        id="focus-prompt"
        type="submit"
        @click="cancelInfo"
        class="button--secondary"
        aria-label="取消"
      >
        取消
      </button>
      <button
        type="submit"
        :disabled="renaming"
        @click="confirmInfo"
        class="button--primary"
        aria-label="确认"
      >
        <AppIcon :name="renaming ? 'loader' : 'circle-check'" :size="18" />
        <span>{{ renaming ? "保存中…" : "确认" }}</span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import { filesize } from "@/utils";
import dayjs from "@/utils/date";
import { files as api } from "@/api";
import url from "@/utils/url";
import { summarizeDirectory } from "@/utils/directoryStats";
import { getFileTypeLabel } from "@/utils/fileListing";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import { getResourceIconName } from "@/utils/fileIcons";

const $showError = inject<IToastError>("$showError")!;
const showChecksums = ref(false);
const directoryStats = ref<ReturnType<typeof summarizeDirectory> | null>(null);
const statsError = ref("");
const route = useRoute();
const router = useRouter();

const fileStore = useFileStore();
const layoutStore = useLayoutStore();
const { closeHovers } = layoutStore;

const { req, selectedItems, selectedCount, isListing, reload, preselect } =
  storeToRefs(fileStore);

const editableName = ref("");
const renaming = ref(false);
const pathCopied = ref(false);
const metadataResource = ref<any>(null);

const rawSize = computed(() => {
  if (isDirectoryInfo.value && directoryStats.value) {
    return directoryStats.value.size;
  }
  if (selectedCount.value === 0 || !isListing.value) {
    return req.value?.size ?? 0;
  }
  return selectedItems.value.reduce((sum, item) => sum + (item.size ?? 0), 0);
});

const humanSize = computed(() => {
  return filesize(rawSize.value);
});

onMounted(async () => {
  if (isDirectoryInfo.value) {
    try {
      directoryStats.value = summarizeDirectory(
        await api.fetchAll(directoryPath.value)
      );
    } catch {
      statsError.value = "目录统计失败，请稍后重试";
    }
  }

  if (selectedCount.value > 1 || !selectedResource.value) return;
  try {
    metadataResource.value = await api.fetchMetadata(
      selectedResource.value.url || fullPath.value
    );
  } catch {
    // Metadata is additive. If a filesystem cannot provide birth time the
    // optional row simply remains hidden and the rest of the dialog works.
  }
});

const humanTime = computed(() => {
  const resource = metadataResource.value ?? selectedResource.value;
  return resource?.modified
    ? dayjs(resource.modified).format("YYYY年M月D日 HH:mm:ss")
    : "后端未提供";
});

const name = computed(() => {
  return selectedCount.value === 0
    ? req.value!.name
    : selectedItems.value[0].name;
});

const selectedResource = computed<any>(() => {
  if (!req.value) return null;
  if (selectedCount.value === 0 || !isListing.value) return req.value;
  return selectedItems.value[0];
});

const creationTime = computed(() => {
  const value =
    metadataResource.value?.created ?? selectedResource.value?.created;
  return value ? dayjs(value).format("YYYY年M月D日 HH:mm:ss") : "";
});

const fullPath = computed(() => {
  if (selectedCount.value === 0) {
    return route.path;
  }
  return selectedItems.value[0].path;
});

const decodePath = (value: string) =>
  value
    .split("/")
    .map((segment) => {
      try {
        return decodeURIComponent(segment);
      } catch {
        return segment;
      }
    })
    .join("/");

const displayPath = computed(() => {
  const path = fullPath.value;
  const withoutPrefix =
    path === "/files" || path === "/files/"
      ? "/"
      : path.startsWith("/files/")
        ? path.slice("/files".length)
        : path;
  return decodePath(withoutPrefix);
});

const copyText = async (text: string) => {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }

  // HTTP 访问或部分移动浏览器不会暴露 Clipboard API，保留兼容回退。
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "");
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  textarea.select();
  const copied = document.execCommand("copy");
  textarea.remove();
  if (!copied) throw new Error("copy failed");
};

const dir = computed(() => {
  return (
    selectedCount.value > 1 ||
    (selectedCount.value === 0
      ? req.value!.isDir
      : selectedItems.value[0].isDir)
  );
});

const isDirectoryInfo = computed(() => dir.value && selectedCount.value <= 1);

const infoIcon = computed<AppIconName>(() => {
  if (selectedCount.value > 1) return "select";
  if (dir.value) return "folder";
  return getResourceIconName(
    selectedResource.value?.name || name.value,
    selectedResource.value?.type || "blob"
  );
});

const fileTypeLabel = computed(() => {
  const fileName = name.value;
  const extension = fileName.includes(".")
    ? fileName.slice(fileName.lastIndexOf(".") + 1)
    : undefined;
  return getFileTypeLabel({ isDir: dir.value, extension });
});

const directoryPath = computed(() => {
  if (selectedCount.value === 0) return route.path;
  return selectedItems.value[0].url;
});

watch(
  name,
  (value) => {
    editableName.value = value;
  },
  { immediate: true }
);

const copyFullPath = async () => {
  try {
    await copyText(displayPath.value);
    pathCopied.value = true;
    window.setTimeout(() => {
      pathCopied.value = false;
    }, 1600);
  } catch {
    $showError(new Error("复制路径失败，请检查浏览器权限"));
  }
};

const renameFromInfo = async () => {
  const nextName = editableName.value.trim();
  if (
    !nextName ||
    nextName === name.value ||
    !selectedResource.value ||
    renaming.value
  ) {
    return;
  }

  const oldLink = selectedResource.value.url;
  const newLink = url.appendResourceRouteSegment(
    url.removeLastDir(oldLink),
    nextName
  );
  renaming.value = true;
  try {
    await api.move([{ from: oldLink, to: newLink }]);
    if (!isListing.value) {
      await router.push({ path: newLink });
    } else {
      preselect.value = url.canonicalResourcePath(newLink);
      reload.value = true;
    }
    closeHovers();
  } catch (e: any) {
    $showError(e);
  } finally {
    renaming.value = false;
  }
};

const confirmInfo = async () => {
  if (editableName.value.trim() !== name.value) {
    await renameFromInfo();
    return;
  }
  closeHovers();
};

const cancelInfo = () => {
  closeHovers();
};

const resolution = computed(() => {
  if (selectedCount.value === 1) {
    const selectedItem = selectedItems.value[0] as any;
    if (selectedItem && selectedItem.type === "image") {
      return selectedItem.resolution;
    }
  } else if (req.value && (req.value as any).type === "image") {
    return (req.value as any).resolution;
  }
  return null;
});

const checksum = async (
  event: Event,
  algo: "md5" | "sha1" | "sha256" | "sha512"
) => {
  event.preventDefault();
  const target = event.target as HTMLElement;

  let link: string;
  if (selectedCount.value) {
    link = selectedItems.value[0].url;
  } else {
    link = route.path;
  }

  try {
    const hash = await api.checksum(link, algo);
    target.textContent = hash;
  } catch (e: any) {
    $showError(e);
  }
};
</script>

<style scoped>
.info-dialog {
  max-width: 48rem;
}

/* Header with icon */
.info-header {
  padding-bottom: 0.5em;
}

.info-title-row {
  display: grid;
  grid-template-columns: 5.5em minmax(0, 1fr);
  width: 100%;
  align-items: center;
  gap: 0.75em;
}

.info-type-icon {
  width: 2rem;
  height: 2rem;
  padding: 0.35em;
  border-radius: 0.5em;
  background: var(--surfaceSecondary, #f0f0f5);
  flex-shrink: 0;
  justify-self: start;
}

.info-title-text {
  grid-column: 2;
  min-width: 0;
}

.info-title-text h2 {
  margin: 0 0 0.1em !important;
  font-size: 1.05em !important;
}

.info-subtitle {
  display: block;
  font-size: 0.8em;
  color: var(--textPrimary, #888);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 22em;
}

/* Content layout */
.info-content {
  padding: 0.75em 1.25em 0.5em;
}

/* Row layout: label | value */
.info-row {
  display: grid;
  grid-template-columns: 5.5em minmax(0, 1fr);
  align-items: flex-start;
  padding: 0.45em 0;
  gap: 0.75em;
  border-bottom: 1px solid var(--divider, rgba(0, 0, 0, 0.04));
}

.info-row:last-child {
  border-bottom: none;
}

.info-label {
  min-width: 0;
  font-size: 0.85em;
  font-weight: 600;
  color: var(--textPrimary, #666);
  padding-top: 0.1em;
}

.info-value {
  min-width: 0;
  font-size: 0.9em;
  color: var(--textPrimary, #333);
  word-break: break-all;
  line-height: 1.5;
}

.path-value {
  font-size: 0.82em;
  font-family: "SF Mono", "Fira Code", monospace;
  background: var(--surfaceSecondary, #f5f5f8);
  padding: 0.25em 0.5em;
  border-radius: 0.35em;
  word-break: break-all;
  color: var(--textPrimary, #555);
}

.info-size {
  font-weight: 600;
  color: var(--textPrimary, #222);
}

/* Divider */
.info-divider {
  height: 1px;
  background: var(--divider, rgba(0, 0, 0, 0.06));
  margin: 0.5em 0;
}

/* Banner */
.info-banner {
  display: flex;
  align-items: center;
  gap: 0.5em;
  padding: 0.6em 0.85em;
  border-radius: 0.5em;
  font-size: 0.88em;
  margin-bottom: 0.75em;
}

.info-banner-multi {
  background: var(--surfaceSecondary, #f0f0f5);
  color: var(--textPrimary, #666);
}

.info-banner > .app-icon {
  width: 1.2rem;
  height: 1.2rem;
  color: var(--blue, #2196f3);
}

/* Collapsible checksums */
.info-collapse {
  margin-top: 0.5em;
}

.info-collapse-btn {
  display: flex;
  align-items: center;
  gap: 0.4em;
  width: 100%;
  padding: 0.6em 0;
  border: none;
  background: none;
  color: var(--blue, #2196f3);
  font-size: 0.85em;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.15s;
  border-top: 1px solid var(--divider, rgba(0, 0, 0, 0.04));
}

.info-collapse-btn:hover {
  opacity: 0.8;
}

.info-collapse-btn > .app-icon {
  width: 1.1rem;
  height: 1.1rem;
}

.info-collapse-content {
  padding: 0.25em 0;
}

.info-checksum {
  padding: 0.3em 0;
}

.info-checksum .info-value {
  font-size: 0.82em;
}

.info-checksum a {
  color: var(--blue, #2196f3);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
  text-decoration-color: rgba(33, 150, 243, 0.3);
  transition: opacity 0.15s;
}

.info-checksum a:hover {
  opacity: 0.8;
}

/* Action buttons */
.info-actions {
  justify-content: flex-end !important;
  padding-top: 0.5em;
}

.info-actions .button--primary {
  display: flex;
  align-items: center;
  gap: 0.35em;
  padding: 0.5em 1em;
  border-radius: 0.5em;
  font-size: 0.85em;
  font-weight: 600;
  color: var(--blue, #2196f3);
  background: rgba(33, 150, 243, 0.06);
  border: none;
  cursor: pointer;
  transition: all 0.15s;
}

.info-actions .button--primary:hover {
  background: rgba(33, 150, 243, 0.12);
  transform: translateY(-1px);
}

.info-actions .button--primary:active {
  transform: scale(0.97);
}

.info-actions .button--primary > .app-icon {
  width: 1.1rem;
  height: 1.1rem;
}

/* Dark mode */
:root.dark .info-type-icon,
html.dark .info-type-icon {
  background: rgba(255, 255, 255, 0.06);
}

:root.dark .info-row {
  border-bottom-color: rgba(255, 255, 255, 0.04);
}

:root.dark .info-label {
  color: rgba(255, 255, 255, 0.5);
}

:root.dark .info-value {
  color: rgba(255, 255, 255, 0.85);
}

:root.dark .path-value {
  background: rgba(255, 255, 255, 0.04);
  color: rgba(255, 255, 255, 0.7);
}

:root.dark .info-size {
  color: rgba(255, 255, 255, 0.95);
}

:root.dark .info-banner-multi {
  background: rgba(255, 255, 255, 0.04);
  color: rgba(255, 255, 255, 0.7);
}

:root.dark .info-collapse-btn {
  border-top-color: rgba(255, 255, 255, 0.06);
}

/* 文件属性采用 Windows 风格的可编辑名称和独立路径行。 */
.info-dialog {
  width: min(48rem, calc(100vw - 2rem));
  max-width: none;
  overflow: hidden;
}

.info-title-text {
  flex: 1;
  min-width: 0;
}

.info-name-input {
  display: block;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  min-height: 2.75rem;
  padding: 0.45rem 0.6rem;
  color: var(--textPrimary, #1e293b);
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--divider, #cbd5e1);
  border-radius: 0.45rem;
  font-size: 1.05rem;
  font-weight: 650;
  text-overflow: ellipsis;
  outline: none;
}

.info-name-input:focus {
  border-color: #1677ff;
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.14);
}

.copy-path-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  min-height: 2.25rem;
  padding: 0.35rem 0.65rem;
  color: #1677ff;
  background: rgba(22, 119, 255, 0.08);
  border: 1px solid rgba(22, 119, 255, 0.18);
  border-radius: 0.45rem;
  cursor: pointer;
}

.copy-path-button > .app-icon {
  width: 1.05rem;
  height: 1.05rem;
}

.info-path-value {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex: 1;
  min-width: 0;
}

.info-path-value code {
  flex: 1;
  min-width: 0;
  padding: 0.4rem 0.55rem;
  overflow-wrap: anywhere;
  color: var(--textPrimary, #475569);
  background: var(--surfaceSecondary, #f8fafc);
  border: 1px solid var(--divider, #e2e8f0);
  border-radius: 0.4rem;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 0.8rem;
  line-height: 1.45;
}

.info-actions {
  display: flex;
  gap: 0.625rem;
  padding: 0.875rem 1.25rem 1.125rem;
}

.info-actions .button--secondary,
.info-actions .button--primary {
  min-width: 5.5rem;
  min-height: 44px;
  border-radius: 0.625rem;
}

.info-actions .button--secondary {
  color: var(--textPrimary, #475569);
  background: var(--surfaceSecondary, #f8fafc);
  border: 1px solid var(--divider, #e2e8f0);
}

.info-actions .button--secondary:hover {
  background: var(--hover, #f1f5f9);
}

.info-actions .button--primary:disabled {
  opacity: 0.65;
  cursor: wait;
}

.copy-path-button {
  flex: 0 0 auto;
  width: 44px;
  min-width: 44px;
  height: 44px;
  min-height: 44px;
  padding: 0;
  border-radius: 0.75rem;
}

@media (max-width: 736px) {
  .info-dialog {
    width: calc(100vw - 1rem);
  }

  .info-content {
    padding-inline: 0.9rem;
  }

  .info-row {
    grid-template-columns: 4.5rem minmax(0, 1fr);
    gap: 0.5rem;
  }

  .info-label {
    width: auto;
  }

  .info-title-row {
    grid-template-columns: 4.5rem minmax(0, 1fr);
  }
}

:root.dark .info-name-input,
html.dark .info-name-input {
  color: rgba(255, 255, 255, 0.9);
  background: rgba(255, 255, 255, 0.05);
  border-color: rgba(255, 255, 255, 0.16);
}

:root.dark .info-path-value code,
html.dark .info-path-value code {
  color: rgba(255, 255, 255, 0.78);
  background: rgba(255, 255, 255, 0.05);
  border-color: rgba(255, 255, 255, 0.12);
}
</style>
