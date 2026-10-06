<template>
  <div
    ref="itemElement"
    class="item"
    role="option"
    tabindex="0"
    :draggable="isDraggable"
    @dragstart="dragStart"
    @dragover="dragOver"
    @dragleave="dragLeave"
    @drop="drop"
    @dragend="dragEnd"
    @click="itemClick"
    @keydown.enter.stop.prevent="open"
    @keydown.space.stop.prevent="itemClick"
    @mousedown="handleMouseDown"
    @mouseup="handleMouseUp"
    @mouseleave="handleMouseLeave"
    @touchstart="handleTouchStart"
    @touchend="handleTouchEnd"
    @touchcancel="handleTouchCancel"
    @touchmove="handleTouchMove"
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
    :data-ext="getExtension(name).toLowerCase()"
    @contextmenu="contextMenu"
  >
    <div class="item-visual">
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

    <div v-if="fieldVisibility.quickActions" class="item-controls" @click.stop>
      <div class="item-quick-actions">
        <button
          class="item-icon-button favorite-star"
          :class="{ 'is-fav': isFavorited }"
          type="button"
          :aria-label="isFavorited ? '取消收藏' : '添加收藏'"
          :title="isFavorited ? '取消收藏' : '添加收藏'"
          @click.prevent="toggleFav"
        >
          <AppIcon name="star" :size="19" :stroke-width="2" />
        </button>
        <button
          class="item-icon-button tag-btn"
          :class="{ 'has-tags': pathTags.length > 0 }"
          type="button"
          title="分配标签"
          aria-label="分配标签"
          @click.prevent="toggleTagPicker"
        >
          <AppIcon name="tags" :size="18" :stroke-width="2" />
        </button>
      </div>
      <FileActionMenu
        v-if="viewMode === 'mosaic'"
        :name="name"
        :can-rename="Boolean(authStore.user?.perm.rename)"
        :can-download="Boolean(authStore.user?.perm.download)"
        :can-delete="Boolean(authStore.user?.perm.delete)"
        trigger-class="item-icon-button"
        @select="runFileAction"
      />
      <button
        v-if="viewMode === 'details'"
        class="mobile-item-more"
        type="button"
        aria-label="更多操作"
        title="更多操作"
        @click.stop="openMobileActionSheet"
      >
        <AppIcon name="ellipsis" :size="20" :stroke-width="2" />
      </button>
    </div>

    <div>
      <div class="name">
        <div class="item-title-row">
          <span class="item-name">{{ name }}</span>
          <RiskIndicator v-if="inlineRiskLevel" :level="inlineRiskLevel" />
        </div>
        <div
          v-if="renderTagSlot"
          class="item-tag-list"
          :class="`tag-presentation-${tagPresentation}`"
          :aria-hidden="pathTags.length === 0"
        >
          <span
            v-for="tag in pathTags"
            :key="tag.id"
            :class="
              tagPresentation === 'dots' ? 'tag-color-marker' : 'tag-chip'
            "
            :style="{ '--tag-color': tag.color }"
            :title="tag.name"
            :aria-label="tag.name"
          >
            <template v-if="tagPresentation === 'names'">
              <span class="tag-chip-dot"></span>{{ tag.name }}
            </template>
          </span>
        </div>
      </div>

      <div v-if="fieldVisibility.type" class="detail-meta">
        <span class="detail-type">{{ fileTypeLabel }}</span>
        <span class="detail-path" :title="path">{{ path }}</span>
      </div>

      <p v-if="renderSize" class="size" :data-order="humanSize">
        {{ humanSize }}
      </p>

      <p v-if="fieldVisibility.modified" class="modified">
        <time :datetime="modified">{{ humanTime }}</time>
      </p>
    </div>
  </div>
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
  <Teleport to="body">
    <div
      v-if="showMobileActionSheet"
      class="mobile-item-action-backdrop"
      @click.self="closeMobileActionSheet"
    >
      <div class="mobile-item-action-sheet" role="menu" @click.stop>
        <div class="mobile-item-action-title">{{ name }}</div>
        <button type="button" role="menuitem" @click="runMobileAction('info')">
          <AppIcon name="info" :size="19" />
          <span>详细信息</span>
        </button>
        <button
          v-if="authStore.user?.perm.rename"
          type="button"
          role="menuitem"
          @click="runMobileAction('rename')"
        >
          <AppIcon name="rename" :size="19" />
          <span>重命名</span>
        </button>
        <button
          v-if="authStore.user?.perm.rename"
          type="button"
          role="menuitem"
          @click="runMobileAction('move')"
        >
          <AppIcon name="move" :size="19" />
          <span>移动</span>
        </button>
        <button
          v-if="authStore.user?.perm.download"
          type="button"
          role="menuitem"
          @click="runMobileAction('download')"
        >
          <AppIcon name="download" :size="19" />
          <span>下载</span>
        </button>
        <button
          v-if="authStore.user?.perm.delete"
          class="danger"
          type="button"
          role="menuitem"
          @click="runMobileAction('delete')"
        >
          <AppIcon name="trash" :size="19" />
          <span>删除</span>
        </button>
        <button
          class="cancel"
          type="button"
          role="menuitem"
          @click="closeMobileActionSheet"
        >
          <span>取消</span>
        </button>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { useAuthStore } from "@/stores/auth";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import { useFavoritesStore } from "@/stores/favorites";
import { useTagsStore } from "@/stores/tags";

import { enableThumbs } from "@/utils/constants";
import { filesize } from "@/utils";
import dayjs from "@/utils/date";
import {
  getMobileTouchAction,
  getListingFieldVisibility,
  getListingTagPresentation,
  getTapSelectionBehavior,
  shouldSuppressTouchContextMenu,
  shouldRenderListingSize,
  shouldRenderListingTagSlot,
} from "@/utils/layoutContract";
import { getFileTypeLabel, normalizeFileKey } from "@/utils/fileListing";
import { resourceOpenRoute } from "@/utils/archivePath";
import {
  canDropFilePaths,
  clearFileDragPayload,
  readFileDragPayload,
  writeFileDragPayload,
} from "@/utils/fileDrag";
import { files as api } from "@/api";
import * as upload from "@/utils/upload";
import { computed, inject, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import TagPicker from "@/components/TagPicker.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import FileActionMenu from "@/components/files/FileActionMenu.vue";
import FileThumbnail from "@/components/files/FileThumbnail.vue";
import RiskIndicator from "@/components/files/RiskIndicator.vue";
import type {
  ConflictingResource,
  MoveCopyItem,
  RiskLevel,
} from "@/types/file";
import type { FileActionMenuAction } from "@/utils/fileActionMenu";

const touches = ref<number>(0);

const longPressTimer = ref<number | null>(null);
const longPressTriggered = ref<boolean>(false);
const touchInteraction = ref<boolean>(false);
const touchGestureActive = ref<boolean>(false);
const touchMoved = ref<boolean>(false);
const touchPressed = ref<boolean>(false);
const mobileTapCount = ref<number>(0);
const mobileTapTimer = ref<number | null>(null);
const touchResetTimer = ref<number | null>(null);
const longPressDelay = ref<number>(500);
const doubleTapDelay = ref<number>(320);
const startPosition = ref<{ x: number; y: number } | null>(null);
const moveThreshold = ref<number>(10);

const $showError = inject<IToastError>("$showError")!;
const router = useRouter();

const props = defineProps<{
  name: string;
  isDir: boolean;
  url: string;
  type: string;
  extension?: string;
  viewMode?: string;
  size: number;
  modified: string;
  index: number;
  readOnly?: boolean;
  path: string;
  riskLevel?: RiskLevel;
  visibleKeys?: string[];
  registerItem?: (key: string, element: HTMLElement | null) => void;
}>();

const authStore = useAuthStore();
const fileStore = useFileStore();
const layoutStore = useLayoutStore();
const favoritesStore = useFavoritesStore();
const tagsStore = useTagsStore();
const fieldVisibility = computed(() =>
  getListingFieldVisibility(props.viewMode)
);
const tagPresentation = computed(() =>
  getListingTagPresentation(props.viewMode)
);

const singleClick = computed(
  () => !props.readOnly && authStore.user?.singleClick
);
const itemKey = computed(() => normalizeFileKey(props.path));
const itemElement = ref<HTMLElement | null>(null);
const isSelected = computed(() => fileStore.selected.includes(itemKey.value));
const isDraggable = computed(
  () => !props.readOnly && authStore.user?.perm.rename
);
const dropTargetActive = ref(false);

onMounted(() => props.registerItem?.(itemKey.value, itemElement.value));

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

const isFavorited = computed(() => {
  if (!props.path) return false;
  return favoritesStore.isFavorite(props.path);
});

const toggleFav = () => {
  if (!props.path) return;
  favoritesStore.toggleFavorite(props.path, props.name);
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
  if (!props.isDir) {
    api.download(null, props.url);
    return;
  }
  layoutStore.showHover("download");
};

const showTagPicker = ref(false);
const showMobileActionSheet = ref(false);

const pathTags = computed(() => {
  if (!props.path) return [];
  return tagsStore.getTagsForPath(props.path);
});
const renderTagSlot = computed(() =>
  shouldRenderListingTagSlot(props.viewMode, pathTags.value.length)
);
const renderSize = computed(() =>
  shouldRenderListingSize(props.viewMode, props.isDir)
);

const toggleTagPicker = (e: Event) => {
  e.stopPropagation();
  e.preventDefault();
  showTagPicker.value = !showTagPicker.value;
};

const closeTagPicker = () => {
  showTagPicker.value = false;
};

const openTagManager = () => {
  showTagPicker.value = false;
  layoutStore.showHover({ prompt: "tag-manager" });
};

const openMobileActionSheet = () => {
  showMobileActionSheet.value = true;
};

const closeMobileActionSheet = () => {
  showMobileActionSheet.value = false;
};

const runMobileAction = (action: string) => {
  closeMobileActionSheet();
  if (action === "download") {
    downloadItem();
    return;
  }
  showItemAction(action);
};

const runFileAction = (action: FileActionMenuAction) => {
  if (action === "download") {
    downloadItem();
    return;
  }
  showItemAction(action);
};

const humanSize = computed(() => {
  if (props.type == "invalid_link") return "无效链接";
  if (props.isDir) return "";
  if (props.size > 0) return filesize(props.size);
  return filesize(props.size);
});

const humanTime = computed(() => {
  if (props.viewMode === "details" || props.viewMode === "compact-list") {
    return dayjs(props.modified).format("YYYY/M/D HH:mm");
  }
  if (!props.readOnly && authStore.user?.dateFormat) {
    return dayjs(props.modified).format("L LT");
  }
  return dayjs(props.modified).fromNow();
});

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

  const items: MoveCopyItem[] = [];

  for (const selectedItem of fileStore.selectedItems.filter((item) =>
    draggedPaths.includes(item.url.replace(/\/+$/, ""))
  )) {
    items.push({
      from: selectedItem.url,
      to: `${props.url.replace(/\/+$/, "")}/${encodeURIComponent(selectedItem.name)}`,
      name: selectedItem.name,
      size: selectedItem.size,
      modified: selectedItem.modified,
      isDir: selectedItem.isDir,
      overwrite: false,
      rename: false,
    });
  }

  if (items.length === 0) return;
  const destinationRoute = props.url;

  const action = (overwrite?: boolean, rename?: boolean) => {
    const action = event.ctrlKey || event.metaKey ? api.copy : api.move;
    action(items, overwrite, rename)
      .then(() => {
        fileStore.reload = true;
      })
      .catch($showError);
  };

  const conflict = await upload.checkConflict(items, destinationRoute);

  if (conflict.length > 0) {
    layoutStore.showHover({
      prompt: "resolve-conflict",
      props: {
        conflict: conflict,
      },
      confirm: (event: Event, result: Array<ConflictingResource>) => {
        event.preventDefault();
        layoutStore.closeHovers();
        for (let i = result.length - 1; i >= 0; i--) {
          const item = result[i];
          if (item.checked.length == 2) {
            items[item.index].rename = true;
          } else if (item.checked.length == 1 && item.checked[0] == "origin") {
            items[item.index].overwrite = true;
          } else {
            items.splice(item.index, 1);
          }
        }
        if (items.length > 0) {
          action();
        }
      },
    });

    return;
  }

  action(false, false);
};

const itemClick = (event: Event | KeyboardEvent) => {
  // Close pickers on any item click
  showTagPicker.value = false;

  // Browsers dispatch a synthetic click after touchend. Mobile gestures are
  // resolved in the touch handlers, so this click must never select an item.
  if (touchInteraction.value) return;

  // If long press was triggered, prevent normal click behavior
  if (longPressTriggered.value) {
    longPressTriggered.value = false;
    return;
  }

  const selectionBehavior = getTapSelectionBehavior({
    isTouch: touchInteraction.value,
    multiple: fileStore.multiple,
    selectedCount: fileStore.selectedCount,
  });
  if (
    singleClick.value &&
    !(event as KeyboardEvent).ctrlKey &&
    !(event as KeyboardEvent).metaKey &&
    !(event as KeyboardEvent).shiftKey &&
    !selectionBehavior.preserveExisting
  )
    open();
  else click(event, selectionBehavior);
};

const contextMenu = (event: MouseEvent) => {
  event.preventDefault();
  if (shouldSuppressTouchContextMenu(touchInteraction.value)) {
    event.stopPropagation();
    return;
  }
  if (
    fileStore.selected.length === 0 ||
    event.ctrlKey ||
    !fileStore.selected.includes(itemKey.value)
  ) {
    click(event);
  }
};

const click = (
  event: Event | KeyboardEvent,
  selectionBehavior = getTapSelectionBehavior({
    isTouch: false,
    multiple: fileStore.multiple,
    selectedCount: fileStore.selectedCount,
  })
) => {
  if (!singleClick.value && fileStore.selectedCount !== 0)
    event.preventDefault();

  if (selectionBehavior.allowDoubleOpen) {
    setTimeout(() => {
      touches.value = 0;
    }, 300);

    touches.value++;
    if (touches.value > 1) {
      open();
    }
  } else {
    touches.value = 0;
  }

  if (fileStore.selected.includes(itemKey.value)) {
    if (
      (event as KeyboardEvent).ctrlKey ||
      (event as KeyboardEvent).metaKey ||
      selectionBehavior.preserveExisting
    ) {
      fileStore.removeSelected(itemKey.value);
      if (fileStore.selectedCount === 0) {
        fileStore.multiple = false;
      }
    } else {
      fileStore.selectOnly(itemKey.value);
    }
    return;
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

  if (
    !(event as KeyboardEvent).ctrlKey &&
    !(event as KeyboardEvent).metaKey &&
    !selectionBehavior.preserveExisting
  ) {
    fileStore.clearSelection();
  }
  fileStore.addSelected(itemKey.value);
};

const open = () => {
  router.push(resourceOpenRoute(props));
};

const getExtension = (fileName: string): string => {
  const lastDotIndex = fileName.lastIndexOf(".");
  if (lastDotIndex === -1) {
    return fileName;
  }
  return fileName.substring(lastDotIndex);
};

// Long-press helper functions
const startLongPress = (clientX: number, clientY: number) => {
  startPosition.value = { x: clientX, y: clientY };
  longPressTimer.value = window.setTimeout(() => {
    handleLongPress();
  }, longPressDelay.value);
};

const clearMobileTapCandidate = () => {
  if (mobileTapTimer.value !== null) {
    window.clearTimeout(mobileTapTimer.value);
    mobileTapTimer.value = null;
  }
  mobileTapCount.value = 0;
};

const finishTouchInteraction = () => {
  if (touchResetTimer.value !== null) {
    window.clearTimeout(touchResetTimer.value);
  }
  touchResetTimer.value = window.setTimeout(() => {
    touchInteraction.value = false;
    touchResetTimer.value = null;
  }, doubleTapDelay.value + 80);
};

const cancelLongPress = () => {
  if (longPressTimer.value !== null) {
    window.clearTimeout(longPressTimer.value);
    longPressTimer.value = null;
  }
  startPosition.value = null;
};

const handleLongPress = () => {
  const action = getMobileTouchAction({
    tapCount: mobileTapCount.value,
    longPress: touchGestureActive.value,
    moved: touchMoved.value,
  });
  if (action === "select") {
    longPressTriggered.value = true;
    clearMobileTapCandidate();
    fileStore.multiple = true;
    if (!fileStore.selected.includes(itemKey.value)) {
      fileStore.addSelected(itemKey.value);
    }
  } else if (singleClick.value) {
    longPressTriggered.value = true;
    click(new Event("longpress"));
  }
  cancelLongPress();
};

const checkMovement = (clientX: number, clientY: number): boolean => {
  if (!startPosition.value) return false;

  const deltaX = Math.abs(clientX - startPosition.value.x);
  const deltaY = Math.abs(clientY - startPosition.value.y);

  return deltaX > moveThreshold.value || deltaY > moveThreshold.value;
};

// Event handlers
const handleMouseDown = (event: MouseEvent) => {
  if (event.button === 0) {
    startLongPress(event.clientX, event.clientY);
  }
};

const handleMouseUp = () => {
  cancelLongPress();
};

const handleMouseLeave = () => {
  cancelLongPress();
};

const handleTouchStart = (event: TouchEvent) => {
  const target = event.target as HTMLElement | null;
  if (target?.closest("button, input, select, textarea, a")) return;

  if (touchResetTimer.value !== null) {
    window.clearTimeout(touchResetTimer.value);
    touchResetTimer.value = null;
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
    cancelLongPress();
    return;
  }
  const touch = event.touches[0];
  startLongPress(touch.clientX, touch.clientY);
};

const handleTouchEnd = () => {
  if (!touchGestureActive.value) return;
  touchPressed.value = false;
  cancelLongPress();

  const wasLongPress = longPressTriggered.value;
  if (!wasLongPress && !touchMoved.value) {
    mobileTapCount.value += 1;
    const action = getMobileTouchAction({
      tapCount: mobileTapCount.value,
      longPress: false,
      moved: false,
      multiple: fileStore.multiple,
    });

    if (action === "toggle-selection") {
      clearMobileTapCandidate();
      click(
        new Event("touchselection"),
        getTapSelectionBehavior({
          isTouch: true,
          multiple: true,
          selectedCount: fileStore.selectedCount,
        })
      );
    } else if (action === "open") {
      clearMobileTapCandidate();
      open();
    } else {
      if (mobileTapTimer.value !== null) {
        window.clearTimeout(mobileTapTimer.value);
      }
      mobileTapTimer.value = window.setTimeout(() => {
        clearMobileTapCandidate();
      }, doubleTapDelay.value);
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
  cancelLongPress();
  finishTouchInteraction();
};

const handleTouchMove = (event: TouchEvent) => {
  if (event.touches.length !== 1) {
    touchMoved.value = true;
    touchPressed.value = false;
    clearMobileTapCandidate();
    cancelLongPress();
    return;
  }
  if (!startPosition.value) return;
  const touch = event.touches[0];
  if (checkMovement(touch.clientX, touch.clientY)) {
    touchMoved.value = true;
    touchPressed.value = false;
    clearMobileTapCandidate();
    cancelLongPress();
  }
};

onBeforeUnmount(() => {
  props.registerItem?.(itemKey.value, null);
  cancelLongPress();
  clearMobileTapCandidate();
  if (touchResetTimer.value !== null) {
    window.clearTimeout(touchResetTimer.value);
  }
});
</script>
