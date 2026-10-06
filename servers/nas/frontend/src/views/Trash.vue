<template>
  <div id="trash-page">
    <header-bar show-menu show-logo title="回收站" title-icon="trash">
      <template #actions>
        <button
          type="button"
          class="trash-header-action"
          aria-label="刷新"
          title="刷新"
          :disabled="trashStore.loading"
          @click="load"
        >
          <AppIcon name="refresh" :size="19" />
          刷新
        </button>
        <button
          v-if="trashStore.items.length > 0"
          type="button"
          class="trash-header-action trash-header-action--danger"
          aria-label="清空回收站"
          title="清空回收站"
          :disabled="clearing || clearTaskActive"
          @click="showClearConfirm = true"
        >
          <AppIcon name="trash" :size="19" />
          清空回收站
        </button>
      </template>
    </header-bar>

    <main class="trash-workspace">
      <AppDialog
        v-if="showClearConfirm"
        title="永久删除全部项目？"
        tone="danger"
        size="small"
        :close-disabled="clearing"
        @closed="showClearConfirm = false"
      >
        <template #icon>
          <AppIcon name="circle-alert" :size="23" />
        </template>
        <p class="trash-dialog-copy">
          此操作不可撤销。原文件、收藏暂存信息和标签暂存信息都会被清除。
        </p>
        <template #footer>
          <div class="trash-confirm-actions">
            <button type="button" @click="showClearConfirm = false">
              取消
            </button>
            <button
              id="focus-prompt"
              type="button"
              class="danger"
              :disabled="clearing"
              @click="clearTrash"
            >
              {{
                clearing
                  ? "正在提交…"
                  : clearTaskActive
                    ? "清空任务进行中"
                    : "永久删除全部"
              }}
            </button>
          </div>
        </template>
      </AppDialog>

      <section v-if="trashStore.error" class="trash-state trash-state--error">
        <AppIcon name="cloud-off" :size="27" />
        <div>
          <strong>无法读取回收站</strong>
          <p>{{ trashStore.error }}</p>
        </div>
        <button type="button" @click="load">重试</button>
      </section>

      <section
        v-else-if="trashStore.loading && !trashStore.loaded"
        class="trash-list trash-list--loading"
        aria-label="正在加载回收站"
      >
        <div v-for="index in 5" :key="index" class="trash-skeleton"></div>
      </section>

      <section v-else-if="trashStore.items.length === 0" class="trash-empty">
        <div class="trash-empty-illustration" aria-hidden="true">
          <AppIcon name="trash" :size="36" />
          <span></span>
        </div>
        <h2>没有待处理的文件</h2>
        <p>从文件列表删除的项目会先出现在这里。</p>
        <router-link to="/files/">返回文件</router-link>
      </section>

      <section v-else class="trash-list" aria-label="回收站项目">
        <div class="trash-list-heading">
          <span>项目 · {{ countLabel }}</span>
          <span>删除信息</span>
          <span>逻辑大小</span>
          <span>操作</span>
        </div>
        <article
          v-for="item in trashStore.items"
          :key="item.id"
          class="trash-item"
          :class="`trash-item--${item.status}`"
        >
          <div class="trash-item-file">
            <div class="trash-file-icon" :class="{ folder: item.isDir }">
              <AppIcon :name="itemIcon(item)" :size="24" />
            </div>
            <div class="trash-file-copy">
              <strong :title="displayPath(item.name)">{{
                displayPath(item.name)
              }}</strong>
              <span :title="displayPath(item.originalPath)">{{
                displayPath(item.originalPath)
              }}</span>
              <small v-if="item.error" class="trash-item-error">
                <AppIcon name="circle-alert" :size="14" />
                {{ item.error }}
              </small>
            </div>
          </div>

          <div class="trash-item-meta">
            <span>
              <AppIcon name="clock" :size="15" />
              {{ deletedLabel(item.deletedAt) }}
            </span>
            <span v-if="authStore.user?.perm.admin">
              <AppIcon name="user" :size="15" />
              {{ item.ownerName || `用户 ${item.userId}` }}
            </span>
            <span v-if="item.status !== 'available'" class="trash-status-chip">
              {{ statusLabel(item.status) }}
            </span>
          </div>

          <div class="trash-item-size">
            <AppIcon :name="item.isDir ? 'folder' : 'hard-drive'" :size="15" />
            <span>{{ sizeLabel(item) }}</span>
            <button
              v-if="
                item.isDir &&
                item.sizeState !== 'accurate' &&
                item.sizeState !== 'calculating' &&
                item.status === 'available' &&
                authStore.user?.perm.delete
              "
              type="button"
              class="trash-size-retry"
              :disabled="measuringIds.has(item.id)"
              @click="calculateSize(item)"
            >
              补算
            </button>
          </div>

          <div class="trash-item-actions">
            <button
              type="button"
              class="trash-action-primary"
              :disabled="busyIds.has(item.id) || item.status === 'restoring'"
              @click="restoreItem(item)"
            >
              <AppIcon name="archive-restore" :size="18" />
              {{ busyIds.has(item.id) ? "恢复中…" : "恢复" }}
            </button>
            <button
              type="button"
              class="trash-action-icon"
              title="永久删除"
              aria-label="永久删除"
              :disabled="busyIds.has(item.id)"
              @click="confirmDeleteId = item.id"
            >
              <AppIcon name="trash" :size="18" />
            </button>
          </div>
        </article>
      </section>
    </main>

    <AppDialog
      v-if="permanentDeleteItem"
      title="永久删除项目？"
      description="删除后无法从回收站恢复。"
      tone="danger"
      size="small"
      :close-disabled="busyIds.has(permanentDeleteItem.id)"
      @closed="confirmDeleteId = ''"
    >
      <template #icon>
        <AppIcon name="trash" :size="22" />
      </template>
      <p class="trash-dialog-copy">
        将永久删除“{{
          displayPath(permanentDeleteItem.name)
        }}”，源文件不会被恢复。
      </p>
      <template #footer>
        <div class="trash-confirm-actions">
          <button type="button" @click="confirmDeleteId = ''">取消</button>
          <button
            id="focus-prompt"
            type="button"
            class="danger"
            :disabled="busyIds.has(permanentDeleteItem.id)"
            @click="deletePermanent(permanentDeleteItem)"
          >
            {{ busyIds.has(permanentDeleteItem.id) ? "删除中…" : "永久删除" }}
          </button>
        </div>
      </template>
    </AppDialog>

    <AppDialog
      v-if="conflictItem"
      title="原位置已有同名项目"
      :description="displayPath(conflictItem.originalPath)"
      size="small"
      @closed="conflictItem = null"
    >
      <template #icon>
        <AppIcon name="copy" :size="22" />
      </template>
      <div class="trash-conflict-options">
        <button type="button" @click="resolveConflict('keep-both')">
          <AppIcon name="copy" :size="20" />
          <span
            ><strong>保留两者</strong
            ><small>恢复文件并自动添加序号</small></span
          >
        </button>
        <button type="button" @click="resolveConflict('skip')">
          <AppIcon name="skip-forward" :size="20" />
          <span
            ><strong>跳过</strong><small>保留回收站项目，稍后处理</small></span
          >
        </button>
        <button
          type="button"
          class="danger"
          @click="resolveConflict('replace')"
        >
          <AppIcon name="arrow-left-right" :size="20" />
          <span
            ><strong>替换</strong><small>现有项目会先移入回收站</small></span
          >
        </button>
      </div>
      <template #footer>
        <button
          type="button"
          class="trash-dialog-cancel"
          @click="conflictItem = null"
        >
          取消
        </button>
      </template>
    </AppDialog>
  </div>
</template>

<script setup lang="ts">
import {
  computed,
  inject,
  onMounted,
  onBeforeUnmount,
  reactive,
  ref,
} from "vue";
import dayjs from "dayjs";
import HeaderBar from "@/components/header/HeaderBar.vue";
import type { TrashConflict, TrashItem, TrashStatus } from "@/api/trash";
import { measureSize, schedulePermanentDeletion } from "@/api/trash";
import * as taskApi from "@/api/tasks";
import { subscribeSharedTaskCenterEvents } from "@/utils/taskCenterEvents";
import type { TaskItem } from "@/api/tasks";
import { StatusError } from "@/api/utils";
import { useAuthStore } from "@/stores/auth";
import { useTrashStore } from "@/stores/trash";
import { useTasksStore } from "@/stores/tasks";
import AppIcon from "@/components/ui/AppIcon.vue";
import AppDialog from "@/components/ui/AppDialog.vue";
import { filesize } from "@/utils";
import { getResourceIconName } from "@/utils/fileIcons";
import type { AppIconName } from "@/components/ui/iconRegistry";
import { displayPath } from "@/utils/displayPath";

const authStore = useAuthStore();
const trashStore = useTrashStore();
const tasksStore = useTasksStore();
const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess")!;
const $showAction = inject<IToastAction>("$showAction")!;

const showClearConfirm = ref(false);
const clearing = ref(false);
const confirmDeleteId = ref("");
const conflictItem = ref<TrashItem | null>(null);
const busyIds = reactive(new Set<string>());
const measuringIds = reactive(new Set<string>());
let stopSizeEvents: (() => void) | undefined;
let sizeRefreshTimer: ReturnType<typeof setTimeout> | undefined;
function scheduleSizeRefresh() {
  if (sizeRefreshTimer !== undefined) return;
  sizeRefreshTimer = setTimeout(() => {
    sizeRefreshTimer = undefined;
    void load();
  }, 120);
}
function sizeLabel(item: TrashItem) {
  const state = item.sizeState ?? (item.isDir ? "unknown" : "accurate");
  if (state === "accurate") return filesize(item.size);
  if (state === "calculating") return "统计中";
  if (state === "incomplete")
    return item.size > 0
      ? `至少 ${filesize(item.size)} · 不完整`
      : "未完成统计";
  return state === "failed" ? "统计失败" : "未统计";
}
async function calculateSize(item: TrashItem) {
  if (measuringIds.has(item.id)) return;
  measuringIds.add(item.id);
  const userId = authStore.user?.id;
  try {
    const task = await measureSize(item.id);
    if (authStore.user?.id !== userId) return;
    tasksStore.record(task);
    await load();
  } catch (error) {
    $showError(error as Error, false);
  } finally {
    measuringIds.delete(item.id);
  }
}
onBeforeUnmount(() => {
  stopSizeEvents?.();
  if (sizeRefreshTimer !== undefined) clearTimeout(sizeRefreshTimer);
});

const permanentDeleteItem = computed(
  () =>
    trashStore.items.find((item) => item.id === confirmDeleteId.value) ?? null
);

const countLabel = computed(() =>
  trashStore.items.length === 0 ? "暂无项目" : `${trashStore.items.length} 项`
);
const clearTaskActive = computed(() =>
  tasksStore.activeItems.some((task) => task.type === "trash.clear")
);

async function load() {
  try {
    await trashStore.load();
  } catch (error) {
    $showError(error as Error, false);
  }
}

async function restoreItem(item: TrashItem, conflict: TrashConflict = "fail") {
  if (busyIds.has(item.id)) return;
  busyIds.add(item.id);
  try {
    const result = await trashStore.restore(item.id, conflict);
    if (result.skipped) {
      conflictItem.value = null;
      $showSuccess("已跳过，项目仍保留在回收站");
      return;
    }
    if (conflict === "replace") {
      await trashStore.load().catch(() => undefined);
    }
    conflictItem.value = null;
    $showSuccess(
      result.path === item.originalPath
        ? "文件已恢复"
        : `已恢复为 ${displayPath(result.path)}`
    );
  } catch (error) {
    if (
      error instanceof StatusError &&
      error.status === 409 &&
      conflict === "fail"
    ) {
      conflictItem.value = item;
      return;
    }
    $showError(error as Error, false);
  } finally {
    busyIds.delete(item.id);
  }
}

function resolveConflict(conflict: Exclude<TrashConflict, "fail">) {
  if (!conflictItem.value) return;
  void restoreItem(conflictItem.value, conflict);
}

async function deletePermanent(item: TrashItem) {
  if (busyIds.has(item.id)) return;
  busyIds.add(item.id);
  try {
    const task = await schedulePermanentDeletion([item.id]);
    tasksStore.record(task);
    confirmDeleteId.value = "";
    $showAction("永久删除将在 3 秒后执行", "撤回", async () => {
      await taskApi.cancel(task.id);
      await trashStore.load();
      $showSuccess("已撤回永久删除", { importance: "minor" });
    });
  } catch (error) {
    $showError(error as Error, false);
  } finally {
    busyIds.delete(item.id);
  }
}

async function clearTrash() {
  clearing.value = true;
  try {
    const task = await trashStore.clear();
    tasksStore.record(task);
    showClearConfirm.value = false;
    $showAction("回收站将在 3 秒后清空", "撤回", async () => {
      await taskApi.cancel(task.id);
      await trashStore.load();
      $showSuccess("已撤回清空操作", { importance: "minor" });
    });
    void tasksStore
      .waitForTerminal(task.id)
      .then(async (finished) => {
        await trashStore.load();
        if (finished.status === "completed") {
          $showSuccess("回收站清空任务已完成");
        } else {
          $showError(finished.error || "回收站清空任务未完成", false);
        }
      })
      .catch((error) => $showError(error as Error, false));
  } catch (error) {
    $showError(error as Error, false);
  } finally {
    clearing.value = false;
  }
}

function itemIcon(item: TrashItem): AppIconName {
  return getResourceIconName(item.name, "", item.isDir);
}

function deletedLabel(timestamp: number) {
  return dayjs(timestamp).fromNow();
}

function statusLabel(status: TrashStatus) {
  const labels: Record<TrashStatus, string> = {
    pending: "正在移入",
    available: "可恢复",
    restoring: "正在恢复",
    failed: "需要处理",
  };
  return labels[status];
}

onMounted(() => {
  stopSizeEvents = subscribeSharedTaskCenterEvents((event) => {
    const task = event.data as TaskItem | undefined;
    if (
      event.type === "task.changed" &&
      task?.type === "trash.size" &&
      task.status !== "running" &&
      task.status !== "queued" &&
      trashStore.items.some((item) => item.sizeTaskId === task.id)
    )
      scheduleSizeRefresh();
  }, scheduleSizeRefresh);
  void load();
  void tasksStore.load().catch(() => undefined);
});
</script>
