<template>
  <section class="duplicate-cleanup" aria-labelledby="duplicate-cleanup-title">
    <div class="duplicate-cleanup__header" role="heading" aria-level="3">
      <div>
        <span class="duplicate-cleanup__icon" aria-hidden="true">
          <AppIcon name="trash" :size="20" />
        </span>
        <span>
          <strong id="duplicate-cleanup-title">智能清理</strong>
          <small>逐组确认保留项，其余副本移入回收站</small>
        </span>
      </div>
      <button
        v-if="cleanupTask && isCleanupActive"
        type="button"
        class="cleanup-secondary-button"
        :disabled="canceling"
        @click="cancelCleanup"
      >
        {{ canceling ? "取消中…" : "取消任务" }}
      </button>
      <button
        v-else-if="!cleanupTask"
        type="button"
        class="cleanup-primary-button"
        :disabled="!canSubmit"
        @click="showConfirmation = true"
      >
        清理所选 {{ selectedGroups.length }} 组
      </button>
      <router-link v-else :to="cleanupTaskRoute">查看后台任务</router-link>
    </div>

    <details class="duplicate-cleanup__notice">
      <summary>
        <AppIcon name="circle-alert" :size="16" />
        清理前须知
      </summary>
      <p>
        移入回收站不会立即释放磁盘空间；相同内容也不代表这些路径具有相同用途。
        请确认保留项和完整路径后再继续。
      </p>
    </details>

    <div
      v-if="report.schemaVersion !== 3"
      class="duplicate-cleanup__blocked"
      role="status"
    >
      这份旧报告缺少文件身份信息。请重新扫描后再使用智能清理。
    </div>

    <div
      v-else-if="cleanupTask"
      class="duplicate-cleanup__task"
      aria-live="polite"
    >
      <span
        class="duplicate-cleanup__task-icon"
        :class="`is-${cleanupTask.status}`"
      >
        <AppIcon :name="cleanupTaskIcon" :size="19" />
      </span>
      <span>
        <strong>{{ cleanupStatusLabel }}</strong>
        <small v-if="cleanupTask.totalItems">
          {{ cleanupTask.processedItems }} / {{ cleanupTask.totalItems }} 个副本
        </small>
        <small v-if="cleanupTask.error" class="is-error">{{
          cleanupTask.error
        }}</small>
      </span>
      <router-link v-if="cleanupResult" to="/trash">前往回收站恢复</router-link>
    </div>

    <div
      v-if="cleanupResult"
      class="duplicate-cleanup__result"
      aria-live="polite"
    >
      <strong>清理结果</strong>
      <span>成功 {{ resultCounts.success }}</span>
      <span>跳过 {{ resultCounts.skipped }}</span>
      <span :class="{ 'is-error': resultCounts.failed }"
        >失败 {{ resultCounts.failed }}</span
      >
      <details v-if="resultFiles.length">
        <summary>查看逐文件结果</summary>
        <ul>
          <li v-for="file in resultFiles" :key="file.path">
            <span :class="`is-${file.status}`">{{
              resultStatus(file.status)
            }}</span>
            <code :title="file.path">{{ file.path }}</code>
            <small v-if="file.reason">{{ file.reason }}</small>
          </li>
        </ul>
      </details>
    </div>

    <div class="duplicate-cleanup__summary" aria-live="polite">
      <span
        >已选 {{ selectedGroups.length }} / {{ report.groups.length }} 组</span
      >
      <span>{{ selectedDeleteCount }} 个副本</span>
      <strong>预计 {{ formatBytes(selectedBytes) }}</strong>
    </div>

    <div class="duplicate-cleanup__groups">
      <article
        v-for="(group, index) in visibleGroups"
        :key="group.sha256"
        class="cleanup-group"
        :class="{
          'is-included': selectionFor(group).included,
          'is-blocked': !canCleanGroup(group),
        }"
      >
        <div class="cleanup-group__header" role="heading" aria-level="4">
          <label>
            <input
              type="checkbox"
              :checked="selectionFor(group).included"
              :disabled="Boolean(cleanupTask) || !canCleanGroup(group)"
              @change="toggleGroup(group, $event)"
            />
            <span>
              <strong
                >第 {{ index + 1 }} 组 · {{ group.totalFiles }} 个文件</strong
              >
              <small>
                每个 {{ formatBytes(group.size) }} · 可清理
                {{ formatBytes(group.reclaimableBytes) }}
              </small>
            </span>
          </label>
          <span
            class="cleanup-group__reason"
            :class="{ 'is-manual': !group.suggestedKeepPath }"
          >
            {{ keepReasonLabel(group) }}
          </span>
        </div>

        <details class="cleanup-group__details">
          <summary>查看 {{ group.totalFiles }} 个文件与保留项</summary>
          <div class="cleanup-group__files">
            <div
              v-for="file in group.files"
              :key="file.path"
              class="cleanup-file-row"
              :class="{
                'is-keeper': selectionFor(group).keepPath === file.path,
              }"
            >
              <label class="cleanup-file-choice">
                <input
                  type="radio"
                  :name="`keeper-${group.sha256}`"
                  :value="file.path"
                  :checked="selectionFor(group).keepPath === file.path"
                  :disabled="
                    Boolean(cleanupTask) || !selectionFor(group).included
                  "
                  :aria-label="`保留 ${file.path}`"
                  @change="setKeeper(group.sha256, file.path)"
                />
              </label>
              <AppIcon :name="resourceIcon(file.path)" :size="18" />
              <span>
                <strong>{{ fileName(file.path) }}</strong>
                <small :title="file.path">{{ file.path }}</small>
              </span>
              <time v-if="file.created" :datetime="file.created">
                创建于 {{ formatCreated(file.created) }}
              </time>
              <em v-else>创建时间未知</em>
              <router-link :to="fileRoute(file.path)" aria-label="打开文件">
                <AppIcon name="external-link" :size="17" />
              </router-link>
            </div>
          </div>
        </details>
      </article>
    </div>

    <div v-if="hasMoreGroups" class="duplicate-cleanup__load-more">
      <button type="button" @click="loadMoreGroups">加载更多重复组</button>
      <small
        >已显示 {{ visibleGroups.length }} /
        {{ report.groups.length }} 组</small
      >
    </div>

    <AppDialog
      v-if="showConfirmation"
      title="确认移入回收站"
      :description="`将清理 ${selectedGroups.length} 组中的 ${selectedDeleteCount} 个副本`"
      size="small"
      :close-disabled="submitting"
      @closed="showConfirmation = false"
    >
      <template #icon><AppIcon name="trash" :size="21" /></template>
      <div class="cleanup-confirmation">
        <p>预计涉及 {{ formatBytes(selectedBytes) }}。保留项不会移动。</p>
        <p>文件进入回收站后仍占用空间，可在回收站逐项恢复或永久删除。</p>
      </div>
      <template #footer>
        <button
          type="button"
          class="cleanup-secondary-button"
          :disabled="submitting"
          @click="showConfirmation = false"
        >
          取消
        </button>
        <button
          type="button"
          class="cleanup-primary-button"
          :disabled="submitting"
          @click="submitCleanup"
        >
          {{ submitting ? "提交中…" : "确认移入回收站" }}
        </button>
      </template>
    </AppDialog>
  </section>
</template>

<script setup lang="ts">
import { computed, inject, onBeforeUnmount, reactive, ref, watch } from "vue";
import type { DuplicateGroup, DuplicateReport } from "@/api/analysis";
import * as analysisApi from "@/api/analysis";
import type { TaskItem, TaskStatus } from "@/api/tasks";
import { useTasksStore } from "@/stores/tasks";
import AppDialog from "@/components/ui/AppDialog.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import { getResourceIconName } from "@/utils/fileIcons";
import { resourceOpenRoute } from "@/utils/archivePath";
import { encodePath } from "@/utils/url";
import { filesize } from "@/utils";
import dayjs from "@/utils/date";
import {
  duplicateCleanupSummary,
  initialDuplicateCleanupSelection,
  isDuplicateGroupCleanable,
  type DuplicateCleanupSelectionState,
} from "@/utils/duplicateCleanup";

const props = defineProps<{ report: DuplicateReport; reportId: string }>();
const tasksStore = useTasksStore();
const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess")!;

const selections = reactive<Record<string, DuplicateCleanupSelectionState>>({});
const showConfirmation = ref(false);
const submitting = ref(false);
const canceling = ref(false);
const visibleGroupCount = ref(10);
const cleanupTask = ref<TaskItem | null>(null);
const cleanupResult = ref<analysisApi.DuplicateCleanupResult | null>(null);
let disposed = false;
let waitingTaskId = "";

watch(
  () => [props.reportId, props.report] as const,
  () => {
    visibleGroupCount.value = 10;
    for (const key of Object.keys(selections)) delete selections[key];
    for (const group of props.report.groups) {
      selections[group.sha256] = initialDuplicateCleanupSelection(
        props.report.schemaVersion,
        group
      );
    }
    cleanupTask.value = null;
    cleanupResult.value = null;
    showConfirmation.value = false;
    void restoreCleanupTask();
  },
  { immediate: true }
);

const selectedGroups = computed(() =>
  props.report.groups.filter((group) => selectionFor(group).included)
);
const visibleGroups = computed(() =>
  props.report.groups.slice(0, visibleGroupCount.value)
);
const hasMoreGroups = computed(
  () => visibleGroups.value.length < props.report.groups.length
);
const selectedSummary = computed(() =>
  duplicateCleanupSummary(selectedGroups.value)
);
const selectedDeleteCount = computed(() => selectedSummary.value.files);
const selectedBytes = computed(() => selectedSummary.value.bytes);
const canSubmit = computed(
  () =>
    props.report.schemaVersion === 3 &&
    selectedGroups.value.length > 0 &&
    selectedGroups.value.every((group) => selectionFor(group).keepPath) &&
    !submitting.value
);
const isCleanupActive = computed(
  () =>
    cleanupTask.value?.status === "queued" ||
    cleanupTask.value?.status === "running"
);
const cleanupTaskRoute = computed(() => ({
  path: "/tasks",
  query: { tab: "background", returnTask: cleanupTask.value?.id },
}));
const cleanupStatusLabel = computed(() => {
  if (!cleanupTask.value) return "";
  const labels: Record<TaskStatus, string> = {
    queued: "清理任务正在排队",
    running: "正在将副本移入回收站",
    completed: "清理任务已完成",
    failed: "清理任务部分或全部失败",
    canceled: "清理任务已取消",
    interrupted: "清理任务因服务重启而中断，可在任务中心重试",
  };
  return labels[cleanupTask.value.status];
});
const cleanupTaskIcon = computed<AppIconName>(() => {
  const icons: Record<TaskStatus, AppIconName> = {
    queued: "hourglass",
    running: "loader",
    completed: "circle-check",
    failed: "circle-alert",
    canceled: "circle-stop",
    interrupted: "retry",
  };
  return cleanupTask.value ? icons[cleanupTask.value.status] : "hourglass";
});
const resultFiles = computed(
  () => cleanupResult.value?.groups.flatMap((group) => group.files) ?? []
);
const resultCounts = computed(() => ({
  success: resultFiles.value.filter((file) => file.status === "success").length,
  skipped: resultFiles.value.filter((file) => file.status === "skipped").length,
  failed: resultFiles.value.filter((file) => file.status === "failed").length,
}));

function selectionFor(group: DuplicateGroup) {
  return selections[group.sha256] ?? { included: false, keepPath: "" };
}

function canCleanGroup(group: DuplicateGroup) {
  return isDuplicateGroupCleanable(props.report.schemaVersion, group);
}

function toggleGroup(group: DuplicateGroup, event: Event) {
  const checked = (event.target as HTMLInputElement).checked;
  const selection = selections[group.sha256];
  if (!selection) return;
  selection.included = checked;
  if (checked && group.suggestedKeepPath)
    selection.keepPath = group.suggestedKeepPath;
}

function setKeeper(sha256: string, path: string) {
  if (selections[sha256]) selections[sha256].keepPath = path;
}

function loadMoreGroups() {
  visibleGroupCount.value += 10;
}

async function submitCleanup() {
  if (!canSubmit.value) return;
  submitting.value = true;
  try {
    const task = await analysisApi.startDuplicateCleanup(
      props.reportId,
      selectedGroups.value.map((group) => ({
        sha256: group.sha256,
        keepPath: selectionFor(group).keepPath,
      }))
    );
    cleanupTask.value = task;
    tasksStore.record(task);
    showConfirmation.value = false;
    $showSuccess("重复文件清理已提交到后台任务");
    await waitForCleanup(task.id);
  } catch (error) {
    if (!disposed)
      $showError(error instanceof Error ? error : String(error), false);
  } finally {
    if (!disposed) submitting.value = false;
  }
}

async function restoreCleanupTask() {
  try {
    const task = await analysisApi.getDuplicateCleanupForReport(props.reportId);
    if (disposed || !task) return;
    cleanupTask.value = task;
    tasksStore.record(task);
    if (task.status === "queued" || task.status === "running") {
      await waitForCleanup(task.id);
    } else if (task.processedItems > 0) {
      cleanupResult.value = await analysisApi.getDuplicateCleanupResult(
        task.id
      );
    }
  } catch (error) {
    if (!disposed)
      $showError(error instanceof Error ? error : String(error), false);
  }
}

async function waitForCleanup(id: string) {
  if (waitingTaskId === id) return;
  waitingTaskId = id;
  try {
    const terminal = await tasksStore.waitForTerminal(id);
    if (disposed || cleanupTask.value?.id !== id) return;
    cleanupTask.value = terminal;
    if (terminal.processedItems > 0) {
      cleanupResult.value = await analysisApi.getDuplicateCleanupResult(id);
    }
  } catch (error) {
    if (!disposed)
      $showError(error instanceof Error ? error : String(error), false);
  } finally {
    if (waitingTaskId === id) waitingTaskId = "";
  }
}

async function cancelCleanup() {
  if (!cleanupTask.value || canceling.value) return;
  canceling.value = true;
  try {
    cleanupTask.value = await tasksStore.cancel(cleanupTask.value.id);
    $showSuccess("取消请求已提交，已完成文件会保留在回收站");
  } catch (error) {
    $showError(error instanceof Error ? error : String(error), false);
  } finally {
    canceling.value = false;
  }
}

function keepReasonLabel(group: DuplicateGroup) {
  if (!canCleanGroup(group)) return "不能自动清理";
  if (group.keepReason === "oldest-created") return "已建议最早创建项";
  if (group.keepReason === "missing-created") return "创建时间缺失，需手动选择";
  if (group.keepReason === "tied-created") return "最早时间并列，需手动选择";
  return "需手动选择保留项";
}

function resultStatus(
  status: analysisApi.DuplicateCleanupFileResult["status"]
) {
  return { success: "成功", skipped: "跳过", failed: "失败" }[status];
}

function fileRoute(path: string) {
  return resourceOpenRoute({
    isDir: false,
    path,
    url: `/files${encodePath(path)}`,
  });
}
function fileName(path: string) {
  return path.split("/").at(-1) || path;
}
function resourceIcon(path: string): AppIconName {
  return getResourceIconName(fileName(path), "", false);
}
function formatCreated(value: string) {
  return dayjs(value).format("YYYY-MM-DD HH:mm:ss");
}
function formatBytes(value: number) {
  return filesize(value || 0);
}

onBeforeUnmount(() => {
  disposed = true;
});
</script>

<style scoped>
.duplicate-cleanup {
  margin-top: 12px;
  overflow: hidden;
  border: 1px solid var(--borderPrimary);
  border-radius: 12px;
  background: var(--surfacePrimary);
}

.duplicate-cleanup__header {
  display: flex;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--borderPrimary);
}

.duplicate-cleanup__header > div,
.duplicate-cleanup__header > div > span:last-child {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
}

.duplicate-cleanup__header > div > span:last-child {
  display: grid;
  gap: 2px;
}

.duplicate-cleanup__icon,
.duplicate-cleanup__task-icon {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 9px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 9%, transparent);
}

.duplicate-cleanup strong {
  color: var(--textSecondary);
}
.duplicate-cleanup small {
  color: var(--textPrimary);
}

.duplicate-cleanup__header a,
.cleanup-primary-button,
.cleanup-secondary-button {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  justify-content: center;
  padding: 0 13px;
  border: 1px solid var(--borderPrimary);
  border-radius: 8px;
  color: var(--textSecondary);
  background: var(--surfaceSecondary);
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
  text-decoration: none;
}

.cleanup-primary-button {
  border-color: var(--blue);
  color: white;
  background: var(--blue);
}

.cleanup-primary-button:disabled,
.cleanup-secondary-button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.duplicate-cleanup__header a:focus-visible,
.cleanup-primary-button:focus-visible,
.cleanup-secondary-button:focus-visible,
.cleanup-group input:focus-visible,
.cleanup-group__files a:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.duplicate-cleanup__notice {
  margin: 10px 14px 0;
  border: 1px solid
    color-mix(in srgb, var(--icon-orange) 22%, var(--borderPrimary));
  border-radius: 8px;
  color: var(--icon-orange);
  background: color-mix(in srgb, var(--icon-orange) 4%, transparent);
}

.duplicate-cleanup__notice summary {
  display: inline-flex;
  min-height: 32px;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  cursor: pointer;
  font-size: 11px;
  font-weight: 700;
}

.duplicate-cleanup__notice p {
  margin: 0;
  padding: 0 10px 9px 32px;
  color: var(--textSecondary);
  font-size: 12px;
  line-height: 1.55;
}

.duplicate-cleanup__blocked,
.duplicate-cleanup__task,
.duplicate-cleanup__result,
.duplicate-cleanup__summary {
  margin: 12px 14px 0;
  padding: 11px 13px;
  border: 1px solid var(--borderPrimary);
  border-radius: 9px;
}

.duplicate-cleanup__blocked {
  color: var(--red);
  font-size: 12px;
}
.duplicate-cleanup__task {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
  border-right: 0;
  border-left: 0;
  border-radius: 0;
  background: color-mix(in srgb, var(--surfaceSecondary) 28%, transparent);
  margin-inline: 0;
}
.duplicate-cleanup__task > span:nth-child(2) {
  display: grid;
  gap: 2px;
}
.duplicate-cleanup__task a {
  color: var(--blue);
  font-size: 12px;
}
.duplicate-cleanup__task-icon.is-running .app-icon {
  animation: cleanup-spin 1.4s linear infinite;
}
.is-error {
  color: var(--red) !important;
}

.duplicate-cleanup__result {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 14px;
  font-size: 12px;
  border-right: 0;
  border-left: 0;
  border-radius: 0;
  background: var(--surfacePrimary);
  margin-inline: 0;
}
.duplicate-cleanup__result details {
  flex-basis: 100%;
}
.duplicate-cleanup__result summary {
  cursor: pointer;
  color: var(--blue);
}
.duplicate-cleanup__result ul {
  display: grid;
  gap: 7px;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}
.duplicate-cleanup__result li {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 3px 9px;
}
.duplicate-cleanup__result li small {
  grid-column: 2;
}
.duplicate-cleanup__result code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.duplicate-cleanup__result .is-success {
  color: #16845d;
}
.duplicate-cleanup__result .is-skipped {
  color: var(--icon-orange);
}

.duplicate-cleanup__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  border-right: 0;
  border-left: 0;
  border-radius: 0;
  background: var(--surfaceSecondary);
  font-size: 12px;
  margin-inline: 0;
}
.duplicate-cleanup__summary strong {
  margin-left: auto;
  color: #16845d;
}

.duplicate-cleanup__groups {
  display: grid;
  gap: 10px;
  padding: 12px 14px 14px;
}

.duplicate-cleanup__load-more {
  display: grid;
  justify-items: center;
  gap: 5px;
  padding: 0 14px 14px;
}

.duplicate-cleanup__load-more button {
  min-height: 34px;
  padding: 0 18px;
  border: 1px solid color-mix(in srgb, var(--blue) 25%, var(--borderPrimary));
  border-radius: 7px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 4%, var(--surfacePrimary));
  cursor: pointer;
  font-size: 11px;
  font-weight: 700;
}

.duplicate-cleanup__load-more button:hover,
.duplicate-cleanup__load-more button:focus-visible {
  outline: none;
  border-color: var(--blue);
  background: color-mix(in srgb, var(--blue) 9%, var(--surfacePrimary));
}

.duplicate-cleanup__load-more button:focus-visible {
  box-shadow: 0 0 0 2px var(--focus-ring);
}

.duplicate-cleanup__load-more small {
  color: var(--textPrimary);
  font-size: 10px;
}
.cleanup-group {
  overflow: hidden;
  border: 1px solid var(--borderPrimary);
  border-radius: 9px;
}
.cleanup-group.is-included {
  border-color: color-mix(in srgb, var(--blue) 38%, var(--borderPrimary));
}
.cleanup-group.is-blocked {
  opacity: 0.72;
}
.cleanup-group__header {
  display: flex;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  background: color-mix(
    in srgb,
    var(--surfaceSecondary) 58%,
    var(--surfacePrimary)
  );
}
.cleanup-group__header label {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}
.cleanup-group__header label > span {
  display: grid;
  min-width: 0;
  gap: 2px;
}
.cleanup-group input {
  width: 17px;
  height: 17px;
  accent-color: var(--blue);
}
.cleanup-group__reason {
  flex: 0 0 auto;
  padding: 4px 7px;
  border-radius: 6px;
  color: #16845d;
  background: color-mix(in srgb, #1ea672 9%, transparent);
  font-size: 11px;
}
.cleanup-group__reason.is-manual {
  color: var(--icon-orange);
  background: color-mix(in srgb, var(--icon-orange) 8%, transparent);
}
.cleanup-group__files {
  display: grid;
}

.cleanup-group__details > summary {
  min-height: 34px;
  padding: 0 12px;
  border-top: 1px solid var(--borderPrimary);
  color: var(--blue);
  cursor: pointer;
  font-size: 11px;
  line-height: 34px;
}

.cleanup-group__details[open] > summary {
  background: color-mix(in srgb, var(--blue) 4%, transparent);
}
.cleanup-file-row {
  display: grid;
  min-height: 58px;
  grid-template-columns: auto auto minmax(0, 1fr) auto 32px;
  align-items: center;
  gap: 9px;
  padding: 7px 10px;
  border-top: 1px solid var(--borderPrimary);
}
.cleanup-file-row.is-keeper {
  background: color-mix(in srgb, var(--blue) 5%, transparent);
}
.cleanup-file-choice {
  display: grid;
  width: 40px;
  height: 44px;
  place-items: center;
  cursor: pointer;
}
.cleanup-file-choice:has(input:disabled) {
  cursor: not-allowed;
}
.cleanup-file-row > span {
  display: grid;
  min-width: 0;
  gap: 2px;
}
.cleanup-group__files strong,
.cleanup-group__files small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cleanup-group__files time,
.cleanup-group__files em {
  color: var(--textPrimary);
  font-size: 11px;
  font-style: normal;
  white-space: nowrap;
}
.cleanup-group__files a {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 7px;
  color: var(--textPrimary);
}
.cleanup-group__files a:hover {
  color: var(--blue);
  background: var(--hover);
}

.cleanup-confirmation p {
  margin: 0 0 10px;
  color: var(--textSecondary);
  font-size: 13px;
  line-height: 1.55;
}
.cleanup-confirmation p:last-child {
  margin-bottom: 0;
}

@keyframes cleanup-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 680px) {
  .duplicate-cleanup__header {
    align-items: stretch;
    flex-direction: column;
  }
  .duplicate-cleanup__header > button,
  .duplicate-cleanup__header > a {
    min-height: 44px;
  }
  .cleanup-group__header {
    align-items: flex-start;
    flex-direction: column;
  }
  .cleanup-file-row {
    grid-template-columns: auto auto minmax(0, 1fr) 32px;
    min-height: 72px;
  }
  .cleanup-group__files time,
  .cleanup-group__files em {
    grid-column: 3 / 5;
  }
  .duplicate-cleanup__task {
    grid-template-columns: auto minmax(0, 1fr);
  }
  .duplicate-cleanup__task a {
    grid-column: 2;
  }
}

@media (prefers-reduced-motion: reduce) {
  .duplicate-cleanup__task-icon.is-running .app-icon {
    animation: none;
  }
}
</style>
