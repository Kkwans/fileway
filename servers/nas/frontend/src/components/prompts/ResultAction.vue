<template>
  <PathPicker
    v-if="mode !== 'info'"
    :title="`${title}到目标目录`"
    :exclude="mode === 'move' && result.dir ? [result.url] : []"
    @select="transfer"
    @close="close"
  />

  <div v-else class="card floating result-action-dialog">
    <div class="card-title">
      <h2>{{ title }}</h2>
    </div>

    <div class="card-content result-info">
      <div class="result-info-name">
        <AppIcon :name="resultIcon" :size="32" aria-hidden="true" />
        <strong>{{ result.name }}</strong>
      </div>
      <dl>
        <div>
          <dt>类型</dt>
          <dd>{{ fileType }}</dd>
        </div>
        <div v-if="!result.dir">
          <dt>大小</dt>
          <dd>
            {{ result.size === null ? "无法读取" : filesize(result.size) }}
          </dd>
        </div>
        <div>
          <dt>修改时间</dt>
          <dd>{{ modifiedTime }}</dd>
        </div>
        <div class="path-row">
          <dt>完整路径</dt>
          <dd>
            <code>{{ result.path }}</code>
          </dd>
          <button
            class="result-path-copy"
            type="button"
            title="复制完整路径"
            aria-label="复制完整路径"
            @click="copyPath"
          >
            <AppIcon
              :name="copied ? 'circle-check' : 'copy'"
              :size="18"
              aria-hidden="true"
            />
          </button>
        </div>
      </dl>
    </div>

    <div class="card-action result-action-buttons">
      <button
        id="focus-prompt"
        class="button button--flat"
        type="button"
        aria-label="确定"
        @click="close"
      >
        确定
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, ref } from "vue";
import { files as api } from "@/api";
import AppIcon from "@/components/ui/AppIcon.vue";
import PathPicker from "./PathPicker.vue";
import { useLayoutStore } from "@/stores/layout";
import { getFileTypeLabel } from "@/utils/fileListing";
import { getResourceIconName } from "@/utils/fileIcons";
import { filesize } from "@/utils";
import dayjs from "@/utils/date";
import url from "@/utils/url";
import type { ConflictResult, MoveCopyItem } from "@/types/file";
import * as upload from "@/utils/upload";
import type { ExplorerResult } from "@/components/search/ResultExplorer.vue";

const props = defineProps<{
  mode: "copy" | "move" | "info";
  result: ExplorerResult;
}>();

const layoutStore = useLayoutStore();
const $showError = inject<IToastError>("$showError")!;
const copied = ref(false);

const title = computed(
  () => ({ copy: "复制", move: "移动", info: "详细信息" })[props.mode]
);
const resultIcon = computed(() =>
  getResourceIconName(props.result.name, "", props.result.dir)
);
const fileType = computed(() => {
  const dot = props.result.name.lastIndexOf(".");
  return getFileTypeLabel({
    isDir: props.result.dir,
    extension: dot >= 0 ? props.result.name.slice(dot) : undefined,
  });
});
const modifiedTime = computed(() =>
  props.result.modified
    ? dayjs(props.result.modified).format("YYYY年M月D日 HH:mm:ss")
    : "未知"
);

function close() {
  layoutStore.closeHovers();
}

async function copyPath() {
  await navigator.clipboard.writeText(props.result.path);
  copied.value = true;
  window.setTimeout(() => (copied.value = false), 1500);
}

async function executeTransfer(item: MoveCopyItem) {
  try {
    if (props.mode === "copy") await api.copy([item], false, false);
    else await api.move([item], false, false);
    const action = layoutStore.currentPrompt?.action;
    close();
    action?.(new Event("result-action"));
  } catch (error) {
    $showError(error as Error);
  }
}

async function transfer(value: string | string[]) {
  if (props.mode === "info") return;
  const destination = Array.isArray(value) ? value[0] : value;
  if (!destination) return;
  const item: MoveCopyItem = {
    from: props.result.url,
    to: url.appendResourceRouteSegment(destination, props.result.name),
    name: props.result.name,
    size: props.result.size ?? undefined,
    modified: props.result.modified ?? undefined,
    isDir: props.result.dir,
    overwrite: false,
    rename: false,
  };

  const conflicts = await upload.checkConflict(
    [item],
    url.encodeResourceRoute(destination)
  );
  if (conflicts.length === 0) {
    await executeTransfer(item);
    return;
  }

  layoutStore.showHover({
    prompt: "resolve-conflict",
    props: { conflict: conflicts },
    confirm: (_event: Event, result: ConflictResult[]) => {
      layoutStore.closeHovers();
      const decision = result[0];
      if (!decision || decision.checked.length === 0) return;
      item.rename = decision.checked.length === 2;
      item.overwrite =
        decision.checked.length === 1 && decision.checked[0] === "origin";
      void executeTransfer(item);
    },
  });
}
</script>

<style scoped>
.result-action-dialog {
  width: min(42rem, calc(100vw - 2rem));
}

.result-info-name {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.result-info-name > .app-icon {
  flex: 0 0 auto;
  color: var(--blue, #1677ff);
}

.result-info dl,
.result-info dd {
  margin: 0;
}

.result-info dl > div {
  display: grid;
  grid-template-columns: 6rem minmax(0, 1fr);
  gap: 0.5rem;
  align-items: center;
  padding: 0.65rem 0;
  border-bottom: 1px solid var(--borderPrimary, #e2e8f0);
}

.result-info dt {
  color: var(--textPrimary, #64748b);
  font-size: 0.8rem;
}

.result-info dd {
  min-width: 0;
  color: var(--textSecondary, #1e293b);
  font-size: 0.85rem;
  overflow-wrap: anywhere;
}

.result-info .path-row {
  grid-template-columns: 6rem minmax(0, 1fr) 2.75rem;
}

.result-info code {
  overflow-wrap: anywhere;
}

.result-path-copy {
  display: grid;
  width: 44px;
  min-width: 44px;
  height: 44px;
  min-height: 44px;
  place-items: center;
  border: 0;
  border-radius: 0.5rem;
  color: var(--textPrimary, #64748b);
  background: transparent;
}

.result-path-copy:hover,
.result-path-copy:focus-visible {
  color: var(--blue, #1677ff);
  background: var(--hover, #f1f5f9);
}

.result-action-buttons {
  display: flex;
  justify-content: flex-end;
}
</style>
