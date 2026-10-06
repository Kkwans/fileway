<template>
  <tr
    ref="itemElement"
    class="item details-table-row"
    role="row"
    tabindex="0"
    :draggable="isDraggable"
    @dragstart="dragStart"
    @dragover="dragOver"
    @dragleave="dragLeave"
    @drop="drop"
    @dragend="dragEnd"
    :data-dir="isDir"
    :data-type="type"
    :data-url="url"
    :data-index="index"
    :data-key="itemKey"
    :class="{
      'is-touch-pressed': touchPressed,
      'is-drop-target': dropTargetActive,
    }"
    :aria-label="name"
    :aria-selected="isSelected"
    @click="itemClick"
    @dblclick.stop.prevent="open"
    @keydown.enter.stop.prevent="open"
    @keydown.space.stop.prevent="itemClick"
    @contextmenu="contextMenu"
    @touchstart="handleTouchStart"
    @touchend="handleTouchEnd"
    @touchcancel="handleTouchCancel"
    @touchmove="handleTouchMove"
  >
    <td
      class="details-name-cell"
      @click.stop="itemClick"
      @dblclick.stop.prevent="open"
    >
      <div class="details-identity">
        <div class="details-row-visual item-visual">
          <FileThumbnail
            :name="name"
            :path="path"
            :type="type"
            :modified="modified"
            :size="size"
            :enabled="enableThumbs"
            :is-dir="isDir"
            :risk-level="normalizedRiskLevel"
            :read-only="readOnly"
          />
        </div>
        <div class="details-name-content">
          <div class="name">
            <div class="item-title-row">
              <span class="item-name">{{ name }}</span>
              <RiskIndicator v-if="inlineRiskLevel" :level="inlineRiskLevel" />
            </div>
            <div v-if="pathTags.length" class="item-tag-list">
              <span
                v-for="tag in pathTags"
                :key="tag.id"
                class="tag-chip"
                :style="{ '--tag-color': tag.color }"
                :title="tag.name"
              >
                <span class="tag-chip-dot"></span>{{ tag.name }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </td>
    <td
      class="details-type-cell"
      @click.stop="itemClick"
      @dblclick.stop.prevent="open"
    >
      <span>{{ fileTypeLabel }}</span>
    </td>
    <td class="details-size-cell">{{ isDir ? "—" : humanSize }}</td>
    <td
      class="details-modified-cell"
      @click.stop="itemClick"
      @dblclick.stop.prevent="open"
    >
      <time :datetime="modified">{{ humanTime }}</time>
    </td>
    <td class="details-actions-cell" @click.stop @dblclick.stop>
      <button
        class="detail-action-button favorite-star"
        :class="{ 'is-fav': isFavorited }"
        type="button"
        :aria-label="isFavorited ? '取消收藏' : '添加收藏'"
        :title="isFavorited ? '取消收藏' : '添加收藏'"
        @click.prevent="toggleFav"
      >
        <AppIcon name="star" :size="19" :stroke-width="2" />
      </button>
      <button
        class="detail-action-button tag-btn"
        :class="{ 'has-tags': pathTags.length > 0 }"
        type="button"
        title="分配标签"
        aria-label="分配标签"
        @click.prevent="toggleTagPicker"
      >
        <AppIcon name="tags" :size="18" :stroke-width="2" />
      </button>
      <FileActionMenu
        :name="name"
        :can-rename="Boolean(authStore.user?.perm.rename)"
        :can-download="Boolean(authStore.user?.perm.download)"
        :can-delete="Boolean(authStore.user?.perm.delete)"
        @select="runFileAction"
      />
    </td>
  </tr>
  <Teleport to="body">
    <div
      v-if="showTagPicker"
      class="tag-dialog-backdrop"
      @click.self="closeTagPicker"
    >
      <div
        class="tag-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="分配标签"
        @click.stop
      >
        <TagPicker
          :path="path || ''"
          @manage="openTagManager"
          @close="closeTagPicker"
        />
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import TagPicker from "@/components/TagPicker.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import FileActionMenu from "@/components/files/FileActionMenu.vue";
import FileThumbnail from "@/components/files/FileThumbnail.vue";
import RiskIndicator from "@/components/files/RiskIndicator.vue";
import { useAuthStore } from "@/stores/auth";
import { useFavoritesStore } from "@/stores/favorites";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import { useTagsStore } from "@/stores/tags";
import { files as api } from "@/api";
import * as upload from "@/utils/upload";
import { enableThumbs } from "@/utils/constants";
import { filesize } from "@/utils";
import dayjs from "@/utils/date";
import { getFileTypeLabel, normalizeFileKey } from "@/utils/fileListing";
import {
  canDropFilePaths,
  clearFileDragPayload,
  readFileDragPayload,
  writeFileDragPayload,
} from "@/utils/fileDrag";
import { resourceOpenRoute } from "@/utils/archivePath";
import {
  getMobileTouchAction,
  shouldOpenDetailedRow,
  shouldOpenDetailedRowFromClick,
  shouldSuppressTouchContextMenu,
} from "@/utils/layoutContract";
import type {
  ConflictingResource,
  MoveCopyItem,
  RiskLevel,
} from "@/types/file";
import type { FileActionMenuAction } from "@/utils/fileActionMenu";

const $showError = inject<IToastError>("$showError")!;
const router = useRouter();
const authStore = useAuthStore();
const favoritesStore = useFavoritesStore();
const fileStore = useFileStore();
const layoutStore = useLayoutStore();
const tagsStore = useTagsStore();

const props = defineProps<{
  name: string;
  isDir: boolean;
  url: string;
  type: string;
  extension?: string;
  size: number;
  modified: string;
  index: number;
  readOnly?: boolean;
  path: string;
  riskLevel?: RiskLevel;
  visibleKeys?: string[];
  registerItem?: (key: string, element: HTMLElement | null) => void;
}>();

const itemKey = computed(() => normalizeFileKey(props.path));
const itemElement = ref<HTMLElement | null>(null);
const isSelected = computed(() => fileStore.selected.includes(itemKey.value));
const touchInteraction = ref(false);
const touchGestureActive = ref(false);
const touchMoved = ref(false);
const touchPressed = ref(false);
const longPressTriggered = ref(false);
const mobileTapCount = ref(0);
const startPosition = ref<{ x: number; y: number } | null>(null);
let longPressTimer: number | null = null;
let mobileTapTimer: number | null = null;
let touchResetTimer: number | null = null;
const isDraggable = computed(
  () => !props.readOnly && authStore.user?.perm.rename
);
const dropTargetActive = ref(false);
onMounted(() => props.registerItem?.(itemKey.value, itemElement.value));
onBeforeUnmount(() => {
  props.registerItem?.(itemKey.value, null);
  if (longPressTimer !== null) window.clearTimeout(longPressTimer);
  if (mobileTapTimer !== null) window.clearTimeout(mobileTapTimer);
  if (touchResetTimer !== null) window.clearTimeout(touchResetTimer);
});
const normalizedRiskLevel = computed(() => props.riskLevel ?? "low");
const inlineRiskLevel = computed<Exclude<RiskLevel, "low"> | null>(() => {
  const level = normalizedRiskLevel.value;
  return level !== "low" &&
    enableThumbs &&
    !props.readOnly &&
    (props.type === "image" || props.type === "video")
    ? level
    : null;
});
const isFavorited = computed(() =>
  props.path ? favoritesStore.isFavorite(props.path) : false
);
const pathTags = computed(() =>
  props.path ? tagsStore.getTagsForPath(props.path) : []
);
const humanSize = computed(() =>
  props.type === "invalid_link"
    ? "无效链接"
    : props.isDir
      ? ""
      : filesize(props.size)
);
const humanTime = computed(() =>
  dayjs(props.modified).format("YYYY/M/D HH:mm")
);
const fileTypeLabel = computed(() =>
  getFileTypeLabel({ isDir: props.isDir, extension: props.extension })
);

const dragStart = (event: DragEvent) => {
  if (fileStore.selectedCount === 0) {
    fileStore.addSelected(itemKey.value);
  } else if (!isSelected.value) {
    fileStore.selectOnly(itemKey.value);
  }
  writeFileDragPayload(
    event.dataTransfer,
    fileStore.selectedItems.map((item) => item.url)
  );
};

const dragOver = (event: DragEvent) => {
  const paths = readFileDragPayload(event.dataTransfer);
  if (
    !props.isDir ||
    paths.length === 0 ||
    !canDropFilePaths(paths, props.url)
  ) {
    if (event.dataTransfer) event.dataTransfer.dropEffect = "none";
    dropTargetActive.value = false;
    return;
  }
  event.preventDefault();
  if (event.dataTransfer)
    event.dataTransfer.dropEffect =
      event.ctrlKey || event.metaKey ? "copy" : "move";
  dropTargetActive.value = true;
};

const dragLeave = (event: DragEvent) => {
  const relatedTarget = event.relatedTarget as Node | null;
  if (!relatedTarget || !itemElement.value?.contains(relatedTarget)) {
    dropTargetActive.value = false;
  }
};

const dragEnd = () => {
  dropTargetActive.value = false;
  clearFileDragPayload();
};

const drop = async (event: DragEvent) => {
  dropTargetActive.value = false;
  const draggedPaths = readFileDragPayload(event.dataTransfer);
  clearFileDragPayload();
  if (!props.isDir || draggedPaths.length === 0) return;
  event.preventDefault();
  if (!canDropFilePaths(draggedPaths, props.url)) return;

  const items: MoveCopyItem[] = fileStore.selectedItems
    .filter((item) => draggedPaths.includes(item.url.replace(/\/+$/, "")))
    .map((item) => ({
      from: item.url,
      to: `${props.url.replace(/\/+$/, "")}/${encodeURIComponent(item.name)}`,
      name: item.name,
      size: item.size,
      modified: item.modified,
      isDir: item.isDir,
      overwrite: false,
      rename: false,
    }));
  if (items.length === 0) return;

  const destinationRoute = props.url;
  const action = event.ctrlKey || event.metaKey ? api.copy : api.move;
  try {
    const conflict = await upload.checkConflict(items, destinationRoute);
    if (conflict.length > 0) {
      layoutStore.showHover({
        prompt: "resolve-conflict",
        props: { conflict },
        confirm: (confirmEvent: Event, result: ConflictingResource[]) => {
          confirmEvent.preventDefault();
          layoutStore.closeHovers();
          for (let i = result.length - 1; i >= 0; i--) {
            const decision = result[i];
            if (decision.checked.length === 2)
              items[decision.index].rename = true;
            else if (
              decision.checked.length === 1 &&
              decision.checked[0] === "origin"
            ) {
              items[decision.index].overwrite = true;
            } else {
              items.splice(decision.index, 1);
            }
          }
          if (items.length > 0) {
            void action(items, false, false)
              .then(() => (fileStore.reload = true))
              .catch($showError);
          }
        },
      });
      return;
    }
    await action(items, false, false);
    fileStore.reload = true;
  } catch (error) {
    $showError(error instanceof Error ? error : new Error(String(error)));
  }
};

const toggleFav = () => {
  if (props.path) favoritesStore.toggleFavorite(props.path, props.name);
};
const open = (event?: MouseEvent) => {
  if (event && touchInteraction.value) return;
  const isActionControl = Boolean(
    (event?.target as HTMLElement | null)?.closest(".details-actions-cell")
  );
  if (!shouldOpenDetailedRow(isActionControl)) return;
  router.push(resourceOpenRoute(props));
};
const toggleTagPicker = () => {
  showTagPicker.value = !showTagPicker.value;
};
const showTagPicker = ref(false);
const closeTagPicker = () => {
  showTagPicker.value = false;
};
const openTagManager = () => {
  closeTagPicker();
  layoutStore.showHover({ prompt: "tag-manager" });
};
const selectForAction = () => {
  fileStore.multiple = false;
  fileStore.selectOnly(itemKey.value);
};
const showItemAction = (prompt: string) => {
  selectForAction();
  layoutStore.showHover(prompt);
};
const downloadItem = () => {
  selectForAction();
  if (props.isDir) layoutStore.showHover("download");
  else {
    try {
      api.download(null, props.url);
    } catch (error) {
      $showError(error instanceof Error ? error : new Error(String(error)));
    }
  }
};
const runFileAction = (action: FileActionMenuAction) => {
  if (action === "download") {
    downloadItem();
    return;
  }
  showItemAction(action);
};
const itemClick = (event: Event | KeyboardEvent) => {
  if (touchInteraction.value) return;
  if ((event.target as HTMLElement).closest("button")) return;
  if (
    shouldOpenDetailedRowFromClick(
      (event as MouseEvent).detail ?? 0,
      Boolean(authStore.user?.singleClick),
      fileStore.multiple
    )
  ) {
    router.push(resourceOpenRoute(props));
    return;
  }
  if (fileStore.selected.includes(itemKey.value)) {
    if (
      (event as KeyboardEvent).ctrlKey ||
      (event as KeyboardEvent).metaKey ||
      fileStore.multiple
    ) {
      fileStore.removeSelected(itemKey.value);
    } else {
      fileStore.selectOnly(itemKey.value);
    }
    return;
  }
  if (
    !(event as KeyboardEvent).shiftKey &&
    !(event as KeyboardEvent).ctrlKey &&
    !(event as KeyboardEvent).metaKey &&
    !fileStore.multiple
  ) {
    fileStore.clearSelection();
  }
  if ((event as KeyboardEvent).shiftKey && fileStore.selected.length > 0) {
    const visibleKeys = props.visibleKeys || [];
    fileStore.selectRange(
      visibleKeys,
      itemKey.value,
      (event as KeyboardEvent).ctrlKey || (event as KeyboardEvent).metaKey
    );
    return;
  }
  fileStore.addSelected(itemKey.value);
};
const contextMenu = (event: MouseEvent) => {
  event.preventDefault();
  if (shouldSuppressTouchContextMenu(touchInteraction.value)) {
    event.stopPropagation();
    return;
  }
  if (!fileStore.selected.includes(itemKey.value) || event.ctrlKey) {
    fileStore.selectOnly(itemKey.value);
  }
};

const clearLongPress = () => {
  if (longPressTimer !== null) {
    window.clearTimeout(longPressTimer);
    longPressTimer = null;
  }
  startPosition.value = null;
};

const clearMobileTapCandidate = () => {
  mobileTapCount.value = 0;
  if (mobileTapTimer !== null) {
    window.clearTimeout(mobileTapTimer);
    mobileTapTimer = null;
  }
};

const finishTouchInteraction = () => {
  if (touchResetTimer !== null) window.clearTimeout(touchResetTimer);
  touchResetTimer = window.setTimeout(() => {
    touchInteraction.value = false;
    touchResetTimer = null;
  }, 400);
};

const handleTouchStart = (event: TouchEvent) => {
  const target = event.target as HTMLElement | null;
  if (target?.closest("button, input, select, textarea, a")) return;

  if (touchResetTimer !== null) {
    window.clearTimeout(touchResetTimer);
    touchResetTimer = null;
  }
  touchInteraction.value = true;
  touchGestureActive.value = true;
  touchMoved.value = false;
  touchPressed.value = true;
  longPressTriggered.value = false;
  if (event.touches.length !== 1) {
    touchMoved.value = true;
    touchPressed.value = false;
    clearMobileTapCandidate();
    clearLongPress();
    return;
  }

  const touch = event.touches[0];
  startPosition.value = { x: touch.clientX, y: touch.clientY };
  longPressTimer = window.setTimeout(() => {
    const action = getMobileTouchAction({
      tapCount: mobileTapCount.value,
      longPress: touchGestureActive.value,
      moved: touchMoved.value,
    });
    if (action === "select") {
      longPressTriggered.value = true;
      clearMobileTapCandidate();
      fileStore.multiple = true;
      fileStore.addSelected(itemKey.value);
    }
    clearLongPress();
  }, 500);
};

const handleTouchMove = (event: TouchEvent) => {
  if (event.touches.length !== 1) {
    touchMoved.value = true;
    touchPressed.value = false;
    clearMobileTapCandidate();
    clearLongPress();
    return;
  }
  if (!startPosition.value) return;
  const touch = event.touches[0];
  const moved =
    Math.abs(touch.clientX - startPosition.value.x) > 10 ||
    Math.abs(touch.clientY - startPosition.value.y) > 10;
  if (!moved) return;

  touchMoved.value = true;
  touchPressed.value = false;
  clearMobileTapCandidate();
  clearLongPress();
};

const handleTouchEnd = () => {
  if (!touchGestureActive.value) return;
  touchPressed.value = false;
  clearLongPress();

  if (!longPressTriggered.value && !touchMoved.value) {
    mobileTapCount.value += 1;
    const action = getMobileTouchAction({
      tapCount: mobileTapCount.value,
      longPress: false,
      moved: false,
      multiple: fileStore.multiple,
    });
    if (action === "toggle-selection") {
      clearMobileTapCandidate();
      fileStore.toggleSelected(itemKey.value);
      if (fileStore.selectedCount === 0) fileStore.multiple = false;
    } else if (action === "open") {
      clearMobileTapCandidate();
      open();
    } else {
      if (mobileTapTimer !== null) window.clearTimeout(mobileTapTimer);
      mobileTapTimer = window.setTimeout(clearMobileTapCandidate, 320);
    }
  } else if (touchMoved.value) {
    clearMobileTapCandidate();
  }

  touchGestureActive.value = false;
  touchMoved.value = false;
  longPressTriggered.value = false;
  finishTouchInteraction();
};

const handleTouchCancel = () => {
  touchPressed.value = false;
  touchGestureActive.value = false;
  touchMoved.value = true;
  longPressTriggered.value = false;
  clearMobileTapCandidate();
  clearLongPress();
  finishTouchInteraction();
};
</script>
