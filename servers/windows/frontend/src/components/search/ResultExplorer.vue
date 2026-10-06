<template>
  <section class="result-explorer" :aria-labelledby="headingId">
    <div class="result-explorer-header">
      <div class="result-explorer-heading">
        <AppIcon :name="icon" :size="28" :style="{ color: iconColor }" />
        <div>
          <span class="result-explorer-kicker">{{ scopeKicker }}</span>
          <h1 :id="headingId">{{ title }}</h1>
        </div>
      </div>

      <div
        class="result-explorer-scope"
        role="group"
        :aria-label="`${noun}范围`"
      >
        <button
          type="button"
          :class="{ active: scope === 'current' }"
          :aria-pressed="scope === 'current'"
          @click="$emit('scope-change', 'current')"
        >
          当前目录
        </button>
        <button
          type="button"
          :class="{ active: scope === alternateScope }"
          :aria-pressed="scope === alternateScope"
          @click="$emit('scope-change', alternateScope)"
        >
          {{ alternateScopeLabel }}
        </button>
      </div>

      <router-link
        v-if="showReturn"
        class="result-explorer-back"
        :to="returnRoute"
        replace
        @click="$emit('return')"
      >
        <AppIcon name="arrow-left" :size="18" />
        返回文件列表
      </router-link>
    </div>

    <div v-if="loading" class="result-state" aria-live="polite">
      <AppIcon name="loader" :size="28" class="spin" />
      <span>正在加载{{ noun }}结果…</span>
    </div>
    <template v-else>
      <div v-if="error" class="result-error-state" role="alert">
        <AppIcon name="circle-alert" :size="20" />
        <span>无法加载{{ noun }}结果：{{ error }}</span>
        <button type="button" @click="$emit('retry')">重试</button>
      </div>
      <template v-else>
        <div
          v-if="
            termination &&
            ['limit', 'timeout', 'canceled'].includes(termination.reason)
          "
          class="result-limit-state"
          role="status"
        >
          <AppIcon name="info" :size="18" />
          <span v-if="termination.reason === 'limit'">
            已达到 1,000 项上限，当前结果并不完整。
          </span>
          <span v-else-if="termination.reason === 'timeout'">
            搜索已在 30 秒后停止，以下为已找到的结果。
          </span>
          <span v-else>搜索已取消，以下为取消前找到的结果。</span>
        </div>
        <div v-if="results.length === 0" class="result-state">
          <AppIcon :name="emptyIcon" :size="32" />
          <span>{{ emptyText }}</span>
        </div>
        <div v-else class="result-explorer-list">
          <component
            v-for="result in results"
            :key="result.path"
            :is="result.error ? 'div' : RouterLink"
            :to="result.error ? undefined : resultRoute(result)"
            class="result-explorer-item"
            :class="{ 'is-error': result.error }"
            @contextmenu.prevent.stop="
              result.error ? undefined : openContextMenu($event, result)
            "
          >
            <span class="result-icon" aria-hidden="true">
              <AppIcon :name="getResultIcon(result)" :size="24" />
            </span>
            <span class="result-copy">
              <strong :title="result.name">{{ result.name }}</strong>
              <span :title="getResultParentPath(result.path, basePath)">
                <AppIcon name="folder" :size="14" />
                {{ getResultParentPath(result.path, basePath) }}
              </span>
            </span>
            <span class="result-meta">
              <span v-if="result.error" class="result-read-error">
                无法读取（{{ result.status || "网络错误" }}）
              </span>
              <template v-else>
                <span v-if="!result.dir && result.size !== null">{{
                  formatSize(result.size)
                }}</span>
                <span>{{ formatTime(result.modified) }}</span>
              </template>
            </span>
          </component>
        </div>
      </template>
    </template>

    <context-menu
      :show="contextMenuVisible"
      :pos="contextMenuPosition"
      @hide="closeContextMenu"
    >
      <MenuItemButton
        icon="folder"
        label="打开文件所在位置"
        @click="emitAction('open-location')"
      />
      <MenuItemButton
        v-if="authStore.user?.perm.create"
        icon="copy"
        label="复制"
        @click="emitAction('copy')"
      />
      <MenuItemButton
        v-if="authStore.user?.perm.rename"
        icon="move"
        label="移动"
        @click="emitAction('move')"
      />
      <MenuItemButton
        v-if="authStore.user?.perm.download"
        icon="download"
        label="下载"
        @click="emitAction('download')"
      />
      <MenuItemButton
        icon="info"
        label="详细信息"
        @click="emitAction('info')"
      />
    </context-menu>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { RouterLink } from "vue-router";
import ContextMenu from "@/components/ContextMenu.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import MenuItemButton from "@/components/ui/MenuItemButton.vue";
import { filesize } from "@/utils";
import dayjs from "@/utils/date";
import { getResourceIconName } from "@/utils/fileIcons";
import type { AppIconName } from "@/components/ui/iconRegistry";
import { getResultParentPath } from "@/utils/tagResults";
import { useAuthStore } from "@/stores/auth";
import { resourceOpenRoute } from "@/utils/archivePath";

export type ExplorerResult = {
  path: string;
  name: string;
  dir: boolean;
  size: number | null;
  modified: string | null;
  url: string;
  status?: number;
  error?: string;
};

export type ExplorerResultAction =
  | "open-location"
  | "copy"
  | "move"
  | "download"
  | "info";

export type ExplorerScope = "current" | "recursive" | "global";

const props = withDefaults(
  defineProps<{
    kind: "search" | "tag";
    scope: ExplorerScope;
    title: string;
    results: ExplorerResult[];
    loading?: boolean;
    basePath?: string;
    returnRoute: string;
    showReturn?: boolean;
    iconColor?: string;
    error?: string;
    termination?: {
      reason: "completed" | "limit" | "timeout" | "canceled" | "error";
      count: number;
    } | null;
  }>(),
  {
    loading: false,
    showReturn: true,
    basePath: "/",
    iconColor: "var(--blue, #1677ff)",
    error: "",
    termination: null,
  }
);

const emit = defineEmits<{
  "scope-change": [scope: ExplorerScope];
  retry: [];
  return: [];
  action: [action: ExplorerResultAction, result: ExplorerResult];
}>();

const authStore = useAuthStore();

function resultRoute(result: ExplorerResult) {
  return resourceOpenRoute({
    isDir: result.dir,
    path: result.path,
    url: result.url,
  });
}

const contextMenuVisible = ref(false);
const contextMenuPosition = ref({ x: 0, y: 0 });
const contextResult = ref<ExplorerResult | null>(null);

const headingId = computed(() => `${props.kind}-results-title`);
const noun = computed(() => (props.kind === "tag" ? "标签筛选" : "搜索"));
const icon = computed<AppIconName>(() =>
  props.kind === "tag" ? "tag" : "search"
);
const scopeKicker = computed(
  () =>
    `${
      props.scope === "current"
        ? "当前目录"
        : props.scope === "recursive"
          ? "递归子目录"
          : "全局"
    }${noun.value}`
);
const alternateScope = computed<Exclude<ExplorerScope, "current">>(() =>
  props.kind === "search" ? "recursive" : "global"
);
const alternateScopeLabel = computed(() =>
  props.kind === "search" ? "递归子目录" : "全局"
);
const emptyIcon = computed<AppIconName>(() =>
  props.kind === "tag" ? "filter-clear" : "search"
);
const emptyText = computed(() =>
  props.kind === "tag"
    ? "该标签下暂无可访问的文件或文件夹"
    : "没有找到匹配的文件或文件夹"
);

function formatSize(size: number) {
  return filesize(size);
}

function formatTime(time: string | null) {
  return time ? dayjs(time).fromNow() : "时间未知";
}

function getResultIcon(result: ExplorerResult): AppIconName {
  return getResourceIconName(result.name, "", result.dir);
}

function openContextMenu(event: MouseEvent, result: ExplorerResult) {
  contextResult.value = result;
  contextMenuPosition.value = {
    x: event.clientX + 8,
    y: event.clientY + 8,
  };
  contextMenuVisible.value = true;
}

function closeContextMenu() {
  contextMenuVisible.value = false;
}

function emitAction(action: ExplorerResultAction) {
  if (contextResult.value) emit("action", action, contextResult.value);
  closeContextMenu();
}
</script>

<style scoped>
.result-explorer {
  width: min(76rem, 100%);
  margin: 0 auto;
}

.result-explorer-header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 1rem;
  margin-bottom: 1rem;
  padding: 1rem 1.25rem;
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--borderPrimary, #e2e8f0);
  border-radius: 0.875rem;
}

.result-explorer-heading {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 0.75rem;
}

.result-explorer-heading > .app-icon {
  flex: 0 0 auto;
  width: 1.75rem;
  height: 1.75rem;
}

.result-explorer-kicker {
  display: block;
  color: var(--textSecondary, #64748b);
  font-size: 0.75rem;
}

.result-explorer-heading h1 {
  margin: 0.125rem 0 0;
  overflow: hidden;
  font-size: 1.125rem;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-explorer-scope {
  display: inline-flex;
  gap: 0.125rem;
  padding: 0.1875rem;
  background: var(--surfaceSecondary, #f8fafc);
  border: 1px solid var(--borderPrimary, #e2e8f0);
  border-radius: 0.625rem;
}

.result-explorer-scope button {
  min-height: 2.5rem;
  padding: 0 0.75rem;
  color: var(--textSecondary, #64748b);
  background: transparent;
  border: 0;
  border-radius: 0.4375rem;
  cursor: pointer;
}

.result-explorer-scope button:hover,
.result-explorer-scope button.active {
  color: var(--blue, #1677ff);
  background: var(--surfacePrimary, #fff);
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.1);
}

.result-explorer-back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  justify-self: end;
  gap: 0.375rem;
  min-height: 2.5rem;
  padding: 0 0.875rem;
  color: var(--textSecondary, #475569);
  border: 1px solid var(--borderPrimary, #e2e8f0);
  border-radius: 0.5rem;
  text-decoration: none;
}

.result-explorer-back:hover {
  color: var(--blue, #1677ff);
  border-color: rgba(22, 119, 255, 0.4);
}

.result-explorer-back .app-icon {
  width: 1.125rem;
  height: 1.125rem;
}

.result-explorer-list {
  display: grid;
  gap: 0.25rem;
  padding: 0.5rem;
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--borderPrimary, #e2e8f0);
  border-radius: 0.875rem;
}

.result-explorer-item {
  display: grid;
  grid-template-columns: 2.5rem minmax(0, 1fr) auto;
  align-items: center;
  min-height: 4.25rem;
  gap: 0.875rem;
  padding: 0.625rem 0.875rem;
  color: var(--textPrimary, #1e293b);
  border-radius: 0.625rem;
  text-decoration: none;
}

.result-explorer-item:hover,
.result-explorer-item:focus-visible {
  background: var(--hover, #f4f7fb);
}

.result-explorer-item:focus-visible {
  outline: 2px solid var(--focus-ring, var(--blue, #1677ff));
  outline-offset: 1px;
}

.result-icon {
  display: inline-grid;
  width: 2.5rem;
  height: 2.5rem;
  place-items: center;
  color: var(--blue, #1677ff);
  background: rgba(22, 119, 255, 0.08);
  border-radius: 0.625rem;
}

.result-icon .app-icon {
  width: 1.5rem;
  height: 1.5rem;
}

.result-copy {
  display: grid;
  min-width: 0;
  gap: 0.375rem;
}

.result-copy strong,
.result-copy > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-copy strong {
  font-size: 0.9375rem;
  font-weight: 650;
}

.result-copy > span {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  color: var(--textSecondary, #64748b);
  font-size: 0.75rem;
}

.result-copy > span .app-icon {
  flex: 0 0 auto;
  width: 0.875rem;
  height: 0.875rem;
}

.result-meta {
  display: grid;
  min-width: 6rem;
  gap: 0.375rem;
  color: var(--textSecondary, #64748b);
  font-size: 0.75rem;
  text-align: right;
  white-space: nowrap;
}

.result-state {
  display: grid;
  min-height: 13rem;
  place-content: center;
  justify-items: center;
  gap: 0.75rem;
  color: var(--textSecondary, #64748b);
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--borderPrimary, #e2e8f0);
  border-radius: 0.875rem;
}

.result-state .app-icon {
  width: 2rem;
  height: 2rem;
  opacity: 0.6;
}

.result-limit-state,
.result-error-state {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  margin-bottom: 0.75rem;
  padding: 0.75rem 0.875rem;
  color: var(--textSecondary, #64748b);
  background: var(--surfaceSecondary, #f8fafc);
  border: 1px solid var(--borderPrimary, #e2e8f0);
  border-radius: 0.625rem;
  font-size: 0.8125rem;
}

.result-error-state {
  color: var(--red, #b42318);
  background: rgba(180, 35, 24, 0.06);
  border-color: rgba(180, 35, 24, 0.22);
}

.result-error-state button {
  min-height: 2rem;
  margin-left: auto;
  padding: 0 0.75rem;
  color: inherit;
  background: var(--surfacePrimary, #fff);
  border: 1px solid currentColor;
  border-radius: 0.4375rem;
  cursor: pointer;
}

.result-explorer-item.is-error {
  cursor: default;
  background: rgba(180, 35, 24, 0.04);
}

.result-read-error {
  color: var(--red, #b42318);
}

:deep(.context-menu) {
  min-width: 13.5rem;
  padding: 0.375rem;
}

:deep(.context-menu button) {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 2.5rem;
  gap: 0.625rem;
  padding: 0 0.75rem;
  color: var(--textPrimary, #1e293b);
  background: transparent;
  border: 0;
  border-radius: 0.4375rem;
  cursor: pointer;
  text-align: left;
}

:deep(.context-menu button:hover) {
  background: var(--hover, #f1f5f9);
}

:deep(.context-menu button .app-icon) {
  color: var(--textSecondary, #64748b);
  width: 1.125rem;
  height: 1.125rem;
}

@media (max-width: 736px) {
  .result-explorer-header {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .result-explorer-scope {
    grid-column: 1 / -1;
    grid-row: 2;
  }

  .result-explorer-scope button {
    flex: 1;
    min-height: 2.75rem;
  }

  .result-explorer-back {
    min-width: 2.75rem;
    min-height: 2.75rem;
    padding-inline: 0.625rem;
  }

  .result-explorer-back span {
    display: none;
  }

  .result-explorer-item {
    grid-template-columns: 2.25rem minmax(0, 1fr);
  }

  .result-meta {
    grid-column: 2;
    grid-auto-flow: column;
    justify-content: start;
    text-align: left;
  }
}
</style>
