<template>
  <div id="analysis-page" class="analysis-page">
    <header-bar
      show-menu
      show-logo
      :show-task-center="false"
      :title="analysisHeaderTitle"
      title-icon="chart-storage"
    />

    <main
      class="analysis-workspace"
      :class="{ 'is-result-detail': isResultDetail }"
    >
      <div v-if="!isResultDetail" class="analysis-workspace__topline">
        <AnalysisToolSwitcher :active-tool="activeTool" @select="selectTool" />
      </div>

      <div class="analysis-run-shell" :class="{ 'has-report': hasReport }">
        <button
          v-if="hasReport && !isResultDetail"
          type="button"
          class="analysis-run-toggle"
          :aria-expanded="showRunPanel"
          @click="showRunPanel = !showRunPanel"
        >
          <span class="analysis-run-toggle__icon" aria-hidden="true">
            <AppIcon name="scan" :size="19" />
          </span>
          <span class="analysis-run-toggle__copy">
            <strong>再次运行扫描</strong>
            <small>
              {{ activeTool === "storage" ? "空间分布" : "重复文件" }} ·
              {{ scopes.length }} 个范围
            </small>
          </span>
          <AppIcon
            :name="showRunPanel ? 'chevron-up' : 'chevron-down'"
            :size="18"
            aria-hidden="true"
          />
        </button>

        <AnalysisScopePanel
          v-if="!isResultDetail && (!hasReport || showRunPanel)"
          v-model:root-confirmed="rootConfirmed"
          :tool="activeTool"
          :scopes="scopes"
          :includes-root="includesRoot"
          :can-start="canStart"
          :starting="starting"
          @remove="removeScope"
          @start="startScan"
          @browse="showScopePicker = true"
        />
      </div>

      <PathPicker
        v-if="showScopePicker"
        title="选择分析范围"
        mode="both"
        multiple
        interaction-mode="analysis"
        :model-value="[]"
        @select="addScopeValue"
        @close="showScopePicker = false"
      />

      <section
        v-if="currentTask && !report && !storageReport"
        class="analysis-task-card"
        aria-live="polite"
      >
        <div class="analysis-task-icon" :class="`is-${currentTask.status}`">
          <AppIcon :name="taskIcon" :size="23" />
        </div>
        <div class="analysis-task-copy">
          <div>
            <strong>{{ currentTask.title }}</strong>
            <span>{{ taskStatusLabel }}</span>
          </div>
          <div v-if="currentTask.totalItems > 0" class="analysis-task-progress">
            <div :style="{ width: `${taskProgress}%` }"></div>
          </div>
          <small v-if="currentTask.totalItems > 0">
            已处理 {{ currentTask.processedItems }} /
            {{ currentTask.totalItems }}
            {{ activeTool === "duplicates" ? "个校验阶段" : "个文件或目录" }}
          </small>
          <small v-else-if="isTaskActive">
            <template v-if="currentTask.processedItems > 0">
              已枚举 {{ currentTask.processedItems.toLocaleString() }} 项 ·
              {{ formatBytes(currentTask.processedBytes) }}
            </template>
            <template v-else>正在枚举范围或等待唯一分析工作槽</template>
          </small>
          <small v-if="currentTask.error" class="analysis-task-error">{{
            currentTask.error
          }}</small>
        </div>
        <button
          v-if="isTaskActive"
          type="button"
          class="analysis-cancel-action"
          :disabled="canceling"
          @click="cancelScan"
        >
          {{ canceling ? "提交中…" : "取消扫描" }}
        </button>
        <router-link
          v-else-if="isResultDetail"
          class="analysis-task-back"
          :to="analysisHomeRoute"
        >
          返回分析
        </router-link>
        <router-link v-else-if="currentTask.status !== 'completed'" to="/tasks">
          查看任务
        </router-link>
      </section>

      <section v-if="loadError" class="analysis-error" role="alert">
        <AppIcon name="circle-alert" :size="23" />
        <div>
          <strong>无法读取分析状态</strong>
          <p>{{ loadError }}</p>
        </div>
      </section>

      <template v-if="report && activeTool === 'duplicates'">
        <section class="analysis-results-heading">
          <div class="analysis-results-heading__main">
            <span class="analysis-results-heading__icon" aria-hidden="true">
              <AppIcon name="scan" :size="20" />
            </span>
            <div class="analysis-results-heading__copy">
              <span class="analysis-results-heading__eyebrow"
                >重复文件 · 分析报告</span
              >
              <h2>确认结果</h2>
              <p>
                <time>{{ completedTime }}</time>
                <span aria-hidden="true">·</span>
                <span :title="report.scopes.join('、')">{{
                  report.scopes.join("、")
                }}</span>
              </p>
            </div>
          </div>
          <div class="analysis-results-heading__actions">
            <router-link
              v-if="isResultDetail"
              class="analysis-detail-action analysis-detail-action--back"
              :to="analysisHomeRoute"
            >
              <AppIcon name="arrow-left" :size="17" />
              <span>返回分析</span>
            </router-link>
            <button
              v-if="isResultDetail"
              type="button"
              class="analysis-detail-action"
              @click="showRunPanel = !showRunPanel"
            >
              <AppIcon name="scan" :size="17" />
              <span>{{ showRunPanel ? "收起扫描" : "再次扫描" }}</span>
            </button>
            <span class="analysis-readonly-chip">
              <AppIcon name="shield-check" :size="14" />
              只读报告
            </span>
          </div>
        </section>

        <section class="analysis-metric-strip" aria-label="重复文件分析摘要">
          <article>
            <small>已扫描文件</small>
            <strong>{{ report.scannedFiles.toLocaleString() }}</strong>
            <span>{{ formatBytes(report.scannedBytes) }}</span>
          </article>
          <article>
            <small>重复组</small>
            <strong>{{ report.duplicateGroups.toLocaleString() }}</strong>
            <span>{{ report.duplicateFiles.toLocaleString() }} 个文件</span>
          </article>
          <article class="analysis-summary-highlight">
            <small>可回收空间</small>
            <strong>{{ formatBytes(report.reclaimableBytes) }}</strong>
            <span>保留每组一份后的估算值</span>
          </article>
        </section>

        <details v-if="report.truncated" class="analysis-report-note">
          <summary>
            <AppIcon name="circle-alert" :size="16" />
            结果已截断
          </summary>
          <p>
            结果文件超过
            {{ report.resultFileLimit.toLocaleString() }}
            项，当前仅展示前一部分；顶部统计仍为完整扫描结果。
          </p>
        </details>

        <DuplicateCleanupPanel
          v-if="report.groups.length && currentTask"
          :report="report"
          :report-id="currentTask.id"
        />

        <section v-else class="analysis-clean-state">
          <AppIcon name="circle-check" :size="34" />
          <div class="analysis-clean-state__copy">
            <h2>所选范围内没有确认的重复文件</h2>
            <p>同名或同大小并不会被误判；只有完整 SHA-256 相同才会进入结果。</p>
          </div>
        </section>

        <details v-if="report.skippedCount" class="analysis-skipped">
          <summary>有 {{ report.skippedCount }} 个路径被安全跳过</summary>
          <p>符号链接、无权读取或扫描期间发生变化的文件不会进入重复结果。</p>
          <ul>
            <li v-for="item in report.skipped" :key="item.path">
              <span>{{ item.path }}</span>
              <small>{{ item.reason }}</small>
            </li>
          </ul>
        </details>
      </template>

      <template v-if="storageReport && activeTool === 'storage'">
        <section class="analysis-results-heading">
          <div class="analysis-results-heading__main">
            <span class="analysis-results-heading__icon" aria-hidden="true">
              <AppIcon name="analysis-storage" :size="20" />
            </span>
            <div class="analysis-results-heading__copy">
              <span class="analysis-results-heading__eyebrow"
                >空间分布 · 分析报告</span
              >
              <h2>空间分布</h2>
              <p>
                <time>{{ completedTime }}</time>
                <span aria-hidden="true">·</span>
                <span
                  :title="
                    storageReport.scopes.map((scope) => scope.path).join('、')
                  "
                >
                  {{
                    storageReport.scopes.map((scope) => scope.path).join("、")
                  }}
                </span>
              </p>
            </div>
          </div>
          <div class="analysis-results-heading__actions">
            <router-link
              v-if="isResultDetail"
              class="analysis-detail-action analysis-detail-action--back"
              :to="analysisHomeRoute"
            >
              <AppIcon name="arrow-left" :size="17" />
              <span>返回分析</span>
            </router-link>
            <button
              v-if="isResultDetail"
              type="button"
              class="analysis-detail-action"
              @click="showRunPanel = !showRunPanel"
            >
              <AppIcon name="scan" :size="17" />
              <span>{{ showRunPanel ? "收起扫描" : "再次扫描" }}</span>
            </button>
            <span class="analysis-readonly-chip">
              <AppIcon name="shield-check" :size="14" />
              实时只读报告
            </span>
          </div>
        </section>

        <section class="analysis-metric-strip" aria-label="存储空间分析摘要">
          <article>
            <small>已扫描文件</small>
            <strong>{{ storageReport.scannedFiles.toLocaleString() }}</strong>
            <span
              >{{
                storageReport.scannedDirectories.toLocaleString()
              }}
              个目录</span
            >
          </article>
          <article class="analysis-summary-highlight">
            <small>所选范围占用</small>
            <strong>{{ formatBytes(storageReport.scannedBytes) }}</strong>
            <span>基于本次元数据扫描</span>
          </article>
          <article>
            <small>统计时间</small>
            <strong class="analysis-summary-time">{{ completedTime }}</strong>
            <span>未使用旧缓存</span>
          </article>
        </section>

        <section
          v-if="storageReport.scopes.length > 1"
          class="storage-scope-grid"
          aria-label="各扫描范围占用"
        >
          <article v-for="scope in storageReport.scopes" :key="scope.path">
            <AppIcon :name="resourceIcon(scope.path, scope.isDir)" :size="20" />
            <span>
              <strong :title="scope.path">{{ scope.path }}</strong>
              <small>
                {{ scope.files.toLocaleString() }} 个文件 ·
                {{ formatBytes(scope.bytes) }}
              </small>
            </span>
          </article>
        </section>

        <details v-if="storageReport.truncated" class="analysis-report-note">
          <summary>
            <AppIcon name="circle-alert" :size="16" />
            排行榜已截断
          </summary>
          <p>
            排行榜各最多展示
            {{ storageReport.resultLimit.toLocaleString() }}
            项；顶部总量仍为完整扫描结果。
          </p>
        </details>

        <section class="storage-rankings" aria-label="存储占用排行">
          <article>
            <div class="storage-ranking-header" role="heading" aria-level="3">
              <div>
                <span>目录占用</span>
                <small>包含全部后代文件</small>
              </div>
              <div class="storage-ranking-columns" aria-hidden="true">
                <span>文件数</span>
                <span>占用空间</span>
              </div>
            </div>
            <div v-if="storageReport.largestDirectories.length">
              <router-link
                v-for="(directory, index) in visibleLargestDirectories"
                :key="directory.path"
                class="storage-rank-row storage-rank-row--directory"
                :to="fileRoute(directory.path, true)"
              >
                <b class="storage-rank-index">{{
                  String(index + 1).padStart(2, "0")
                }}</b>
                <AppIcon name="folder" :size="19" />
                <span class="storage-rank-main">
                  <strong>{{ fileName(directory.path) }}</strong>
                  <small :title="directory.path">{{ directory.path }}</small>
                  <em>
                    <i
                      :style="{
                        width: storageBarWidth(
                          directory.bytes,
                          maxDirectoryBytes
                        ),
                      }"
                    ></i>
                  </em>
                </span>
                <span class="storage-rank-count">{{
                  directory.files.toLocaleString()
                }}</span>
                <strong class="storage-rank-size">{{
                  formatBytes(directory.bytes)
                }}</strong>
              </router-link>
            </div>
            <p v-else class="storage-rank-empty">所选范围内没有目录。</p>
            <button
              v-if="
                visibleLargestDirectories.length <
                storageReport.largestDirectories.length
              "
              type="button"
              class="storage-rank-more"
              @click="loadMoreStorageDirectories"
            >
              加载更多目录
            </button>
          </article>

          <article>
            <div class="storage-ranking-header" role="heading" aria-level="3">
              <div>
                <span>大文件</span>
                <small>按实际文件大小排序</small>
              </div>
              <div class="storage-ranking-columns" aria-hidden="true">
                <span>文件大小</span>
                <span>修改时间</span>
              </div>
            </div>
            <div v-if="storageReport.largestFiles.length">
              <router-link
                v-for="(file, index) in visibleLargestFiles"
                :key="file.path"
                class="storage-rank-row storage-rank-row--file"
                :to="fileRoute(file.path, false)"
              >
                <b class="storage-rank-index">{{
                  String(index + 1).padStart(2, "0")
                }}</b>
                <AppIcon :name="resourceIcon(file.path)" :size="19" />
                <span class="storage-rank-main">
                  <strong>{{ fileName(file.path) }}</strong>
                  <small :title="file.path">{{ file.path }}</small>
                  <em>
                    <i
                      :style="{
                        width: storageBarWidth(file.size, maxFileBytes),
                      }"
                    ></i>
                  </em>
                </span>
                <strong class="storage-rank-size">{{
                  formatBytes(file.size)
                }}</strong>
                <time
                  class="storage-rank-time"
                  :datetime="String(file.modified)"
                  >{{ formatModified(file.modified) }}</time
                >
              </router-link>
            </div>
            <p v-else class="storage-rank-empty">所选范围内没有普通文件。</p>
            <button
              v-if="
                visibleLargestFiles.length < storageReport.largestFiles.length
              "
              type="button"
              class="storage-rank-more"
              @click="loadMoreStorageFiles"
            >
              加载更多文件
            </button>
          </article>
        </section>

        <details v-if="storageReport.skippedCount" class="analysis-skipped">
          <summary>
            有 {{ storageReport.skippedCount }} 个路径被安全跳过
          </summary>
          <p>符号链接、特殊文件或无权读取的路径不计入空间统计。</p>
          <ul>
            <li v-for="item in storageReport.skipped" :key="item.path">
              <span>{{ item.path }}</span>
              <small>{{ item.reason }}</small>
            </li>
          </ul>
        </details>
      </template>

      <AnalysisRecentScans
        v-if="!isResultDetail"
        :tool="activeTool"
        :items="recentScans"
        :loading="recentLoading"
        :loading-more="recentLoadingMore"
        :has-more="recentHasMore"
        :clearing="recentClearing"
        :error="recentError"
        @retry="loadRecent(activeTool)"
        @load-more="loadMoreRecent"
        @clear="clearRecent"
      />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import AnalysisRecentScans from "@/components/analysis/AnalysisRecentScans.vue";
import AnalysisScopePanel from "@/components/analysis/AnalysisScopePanel.vue";
import AnalysisToolSwitcher from "@/components/analysis/AnalysisToolSwitcher.vue";
import DuplicateCleanupPanel from "@/components/analysis/DuplicateCleanupPanel.vue";
import PathPicker from "@/components/prompts/PathPicker.vue";
import HeaderBar from "@/components/header/HeaderBar.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import * as analysisApi from "@/api/analysis";
import * as taskApi from "@/api/tasks";
import type {
  AnalysisRecentItem,
  DuplicateReport,
  StorageReport,
} from "@/api/analysis";
import type { TaskItem, TaskListFilter, TaskStatus } from "@/api/tasks";
import { useAuthStore } from "@/stores/auth";
import { useTasksStore } from "@/stores/tasks";
import {
  addAnalysisScope,
  analysisScopesFromQuery,
} from "@/utils/analysisScopes";
import { encodePath } from "@/utils/url";
import { resourceOpenRoute } from "@/utils/archivePath";
import { filesize } from "@/utils";
import dayjs from "@/utils/date";
import type { AnalysisTool } from "@/utils/analysisTools";
import { getResourceIconName } from "@/utils/fileIcons";

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const tasksStore = useTasksStore();
const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess")!;

const scopes = ref(analysisScopesFromQuery(route.query.paths));
const activeTool = ref<AnalysisTool>(toolFromRoute());
const rootConfirmed = ref(false);
const starting = ref(false);
const canceling = ref(false);
const currentTask = ref<TaskItem | null>(null);
const report = ref<DuplicateReport | null>(null);
const storageReport = ref<StorageReport | null>(null);
const storageDirectoriesVisibleCount = ref(10);
const storageFilesVisibleCount = ref(10);
const showRunPanel = ref(false);
const showScopePicker = ref(false);
const loadError = ref("");
const recentScans = ref<AnalysisRecentItem[]>([]);
const recentLoading = ref(false);
const recentLoadingMore = ref(false);
const recentHasMore = ref(false);
const recentCursor = ref("");
const recentClearing = ref(false);
const recentError = ref("");
let pollTimer: number | undefined;
let disposed = false;
let taskLoadSequence = 0;
let recentLoadSequence = 0;

const isResultDetail = computed(
  () =>
    typeof route.query.task === "string" && route.query.task.trim().length > 0
);
const includesRoot = computed(() => scopes.value.includes("/"));
const isTaskActive = computed(
  () =>
    currentTask.value?.status === "queued" ||
    currentTask.value?.status === "running"
);
const canStart = computed(
  () =>
    scopes.value.length > 0 &&
    (!includesRoot.value || rootConfirmed.value) &&
    !starting.value &&
    !isTaskActive.value
);
const hasReport = computed(() => Boolean(report.value || storageReport.value));
const analysisHeaderTitle = computed(() =>
  isResultDetail.value
    ? hasReport.value
      ? "扫描结果"
      : "扫描任务"
    : "存储工具"
);
const taskProgress = computed(() => {
  const task = currentTask.value;
  if (!task || task.totalItems <= 0) return 0;
  return Math.min(
    100,
    Math.round((task.processedItems / task.totalItems) * 100)
  );
});
const taskStatusLabel = computed(() =>
  currentTask.value ? taskStatus(currentTask.value.status) : ""
);
const taskIcon = computed<AppIconName>(() => {
  const icons: Record<TaskStatus, AppIconName> = {
    queued: "hourglass",
    running: "loader",
    completed: "circle-check",
    failed: "circle-alert",
    canceled: "circle-stop",
    interrupted: "retry",
  };
  return currentTask.value ? icons[currentTask.value.status] : "hourglass";
});
const completedTime = computed(() => {
  const completedAt =
    activeTool.value === "storage"
      ? storageReport.value?.completedAt
      : report.value?.completedAt;
  return completedAt ? dayjs(completedAt).format("YYYY-MM-DD HH:mm") : "";
});
const maxDirectoryBytes = computed(
  () => storageReport.value?.largestDirectories[0]?.bytes ?? 0
);
const maxFileBytes = computed(
  () => storageReport.value?.largestFiles[0]?.size ?? 0
);
const visibleLargestDirectories = computed(
  () =>
    storageReport.value?.largestDirectories.slice(
      0,
      storageDirectoriesVisibleCount.value
    ) ?? []
);
const visibleLargestFiles = computed(
  () =>
    storageReport.value?.largestFiles.slice(
      0,
      storageFilesVisibleCount.value
    ) ?? []
);
const analysisHomeRoute = computed(() => ({
  path: "/analysis",
  query: { tool: activeTool.value, paths: scopes.value },
}));

watch(includesRoot, (value) => {
  if (!value) rootConfirmed.value = false;
});

watch(
  () => [route.query.tool, route.query.task],
  ([, taskValue]) => {
    const taskId = typeof taskValue === "string" ? taskValue : "";
    if (taskId) {
      if (taskId !== currentTask.value?.id) void loadTask(taskId);
      return;
    }
    taskLoadSequence++;
    stopPolling();
    currentTask.value = null;
    report.value = null;
    storageReport.value = null;
    resetReportPaging();
    loadError.value = "";
    activeTool.value = toolFromRoute();
    scopes.value = analysisScopesFromQuery(route.query.paths);
    void loadRecent(activeTool.value);
  }
);

function selectTool(tool: AnalysisTool) {
  if (tool === activeTool.value) return;
  activeTool.value = tool;
  taskLoadSequence++;
  stopPolling();
  currentTask.value = null;
  report.value = null;
  storageReport.value = null;
  resetReportPaging();
  showRunPanel.value = false;
  loadError.value = "";
  void router.push({
    path: "/analysis",
    query: { tool, paths: scopes.value },
  });
}

function addScope(value: string) {
  const next = addAnalysisScope(scopes.value, value);
  if (
    next.length === scopes.value.length &&
    next.every((item, index) => item === scopes.value[index])
  ) {
    return;
  }
  scopes.value = next;
}

function addScopeValue(value: string | string[]) {
  for (const path of Array.isArray(value) ? value : [value]) {
    addScope(path);
  }
  showScopePicker.value = false;
}

function removeScope(scope: string) {
  scopes.value = scopes.value.filter((item) => item !== scope);
}

async function startScan() {
  if (!canStart.value) return;
  const tool = activeTool.value;
  starting.value = true;
  loadError.value = "";
  report.value = null;
  storageReport.value = null;
  resetReportPaging();
  showRunPanel.value = false;
  try {
    const task =
      tool === "storage"
        ? await analysisApi.startStorageScan(scopes.value)
        : await analysisApi.startDuplicateScan(scopes.value);
    currentTask.value = task;
    tasksStore.record(task);
    void loadRecent(tool);
    await router.replace({
      path: "/analysis",
      query: { tool, task: task.id, paths: scopes.value },
    });
    $showSuccess(
      tool === "storage"
        ? "存储空间分析已提交，可在任务中心取消"
        : "重复文件扫描已提交，可在任务中心取消"
    );
    schedulePoll(0);
  } catch (error) {
    $showError(error instanceof Error ? error : String(error), false);
  } finally {
    starting.value = false;
  }
}

async function cancelScan() {
  if (!currentTask.value || canceling.value) return;
  canceling.value = true;
  try {
    currentTask.value = await tasksStore.cancel(currentTask.value.id);
    stopPolling();
    void loadRecent(activeTool.value);
    $showSuccess("取消请求已提交");
  } catch (error) {
    $showError(error instanceof Error ? error : String(error), false);
  } finally {
    canceling.value = false;
  }
}

function schedulePoll(delay = 750) {
  stopPolling();
  if (disposed || !currentTask.value) return;
  pollTimer = window.setTimeout(pollTask, delay);
}

function stopPolling() {
  if (pollTimer !== undefined) window.clearTimeout(pollTimer);
  pollTimer = undefined;
}

async function pollTask() {
  const id = currentTask.value?.id;
  if (!id || disposed) return;
  try {
    const task = await taskApi.get(id);
    if (disposed || currentTask.value?.id !== id) return;
    currentTask.value = task;
    tasksStore.record(task);
    if (task.status === "queued" || task.status === "running") {
      schedulePoll();
      return;
    }
    if (task.status === "completed") await loadReport(task.id);
    await loadRecent(activeTool.value);
  } catch (error) {
    if (disposed || currentTask.value?.id !== id) return;
    loadError.value = error instanceof Error ? error.message : String(error);
    schedulePoll(1500);
  }
}

async function loadReport(taskId: string) {
  const taskType = currentTask.value?.type;
  try {
    resetReportPaging();
    if (taskType === "analysis.storage") {
      const next = await analysisApi.getStorageReport(taskId);
      if (disposed || currentTask.value?.id !== taskId) return;
      storageReport.value = next;
      report.value = null;
      scopes.value = storageReport.value.scopes.map((scope) => scope.path);
    } else {
      const next = await analysisApi.getDuplicateReport(taskId);
      if (disposed || currentTask.value?.id !== taskId) return;
      report.value = next;
      storageReport.value = null;
      scopes.value = [...report.value.scopes];
    }
    showRunPanel.value = false;
    loadError.value = "";
  } catch (error) {
    if (disposed || currentTask.value?.id !== taskId) return;
    loadError.value = error instanceof Error ? error.message : String(error);
  }
}

async function loadTask(taskId: string) {
  const sequence = ++taskLoadSequence;
  stopPolling();
  currentTask.value = null;
  report.value = null;
  storageReport.value = null;
  resetReportPaging();
  loadError.value = "";

  try {
    const task = await taskApi.get(taskId);
    if (sequence !== taskLoadSequence || disposed) return;
    if (
      task.type !== "analysis.duplicates" &&
      task.type !== "analysis.storage"
    ) {
      loadError.value = "该任务不是存储分析任务。";
      return;
    }
    const taskTool: AnalysisTool =
      task.type === "analysis.storage" ? "storage" : "duplicates";
    if (activeTool.value !== taskTool) {
      activeTool.value = taskTool;
      if (!isResultDetail.value) void loadRecent(taskTool);
    }
    currentTask.value = task;
    tasksStore.record(task);
    if (task.status === "completed") await loadReport(task.id);
    else if (task.status === "queued" || task.status === "running") {
      schedulePoll(0);
    }
  } catch (error) {
    if (sequence !== taskLoadSequence || disposed) return;
    loadError.value = error instanceof Error ? error.message : String(error);
  }
}

async function loadInitial() {
  try {
    activeTool.value = toolFromRoute();
    if (!isResultDetail.value) await loadRecent(activeTool.value);
    const taskId = typeof route.query.task === "string" ? route.query.task : "";
    if (!taskId) return;
    await loadTask(taskId);
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error);
  }
}

async function loadRecent(tool: AnalysisTool) {
  const sequence = ++recentLoadSequence;
  recentLoading.value = true;
  recentLoadingMore.value = false;
  recentHasMore.value = false;
  recentCursor.value = "";
  recentError.value = "";
  recentScans.value = [];
  try {
    const page = await analysisApi.listRecentScans(tool, undefined, 6);
    if (sequence !== recentLoadSequence || disposed) return;
    recentScans.value = page.items;
    recentCursor.value = page.nextCursor || "";
    recentHasMore.value = Boolean(page.nextCursor);
  } catch (error) {
    if (sequence !== recentLoadSequence || disposed) return;
    recentError.value = error instanceof Error ? error.message : String(error);
  } finally {
    if (sequence === recentLoadSequence && !disposed) {
      recentLoading.value = false;
    }
  }
}

async function loadMoreRecent() {
  if (
    !recentHasMore.value ||
    !recentCursor.value ||
    recentLoading.value ||
    recentLoadingMore.value
  ) {
    return;
  }
  const sequence = recentLoadSequence;
  recentLoadingMore.value = true;
  try {
    const page = await analysisApi.listRecentScans(
      activeTool.value,
      recentCursor.value,
      6
    );
    if (sequence !== recentLoadSequence || disposed) return;
    recentScans.value = [...recentScans.value, ...page.items];
    recentCursor.value = page.nextCursor || "";
    recentHasMore.value = Boolean(page.nextCursor);
  } catch (error) {
    if (sequence !== recentLoadSequence || disposed) return;
    recentError.value = error instanceof Error ? error.message : String(error);
  } finally {
    if (sequence === recentLoadSequence && !disposed) {
      recentLoadingMore.value = false;
    }
  }
}

async function clearRecent() {
  if (recentClearing.value) return;
  const user = authStore.user;
  if (!user) {
    $showError(new Error("用户信息尚未加载"));
    return;
  }
  recentClearing.value = true;
  try {
    const filter: TaskListFilter = {
      user: String(user.id),
      type:
        activeTool.value === "storage"
          ? "analysis.storage"
          : "analysis.duplicates",
      statuses: ["completed", "failed", "canceled", "interrupted"],
      archived: false,
    };
    const matching = await taskApi.list({ ...filter, limit: 1 });
    if (matching.total > 0) {
      await taskApi.batch("archive", filter, matching.total);
    }
    $showSuccess("最近扫描记录已清空");
    await loadRecent(activeTool.value);
  } catch (error) {
    $showError(error instanceof Error ? error : String(error));
  } finally {
    recentClearing.value = false;
  }
}

function taskStatus(status: TaskStatus) {
  const labels: Record<TaskStatus, string> = {
    queued: "排队中",
    running: "正在扫描",
    completed: "已完成",
    failed: "扫描失败",
    canceled: "已取消",
    interrupted: "已中断",
  };
  return labels[status];
}

function toolFromRoute(): AnalysisTool {
  return route.query.tool === "storage" ? "storage" : "duplicates";
}

function fileRoute(path: string, isDir = false) {
  const suffix = isDir && path !== "/" ? "/" : "";
  return resourceOpenRoute({
    isDir,
    path,
    url: `/files${encodePath(path)}${suffix}`,
  });
}

function fileName(path: string) {
  return path.split("/").at(-1) || path;
}

function resourceIcon(path: string, isDir = false): AppIconName {
  return getResourceIconName(fileName(path), "", isDir);
}

function formatBytes(value: number) {
  return filesize(value || 0);
}

function formatModified(value: number) {
  return dayjs(value).format("YYYY-MM-DD HH:mm");
}

function storageBarWidth(value: number, maximum: number) {
  if (value <= 0 || maximum <= 0) return "0%";
  return `${Math.max(4, Math.round((value / maximum) * 100))}%`;
}

function resetReportPaging() {
  storageDirectoriesVisibleCount.value = 10;
  storageFilesVisibleCount.value = 10;
}

function loadMoreStorageDirectories() {
  storageDirectoriesVisibleCount.value += 10;
}

function loadMoreStorageFiles() {
  storageFilesVisibleCount.value += 10;
}

onMounted(loadInitial);
onBeforeUnmount(() => {
  disposed = true;
  stopPolling();
});
</script>

<style scoped>
.analysis-page {
  min-height: 100vh;
  color: var(--textSecondary);
  background: var(--background);
}

.analysis-header-action {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
  border: 1px solid var(--borderPrimary);
  border-radius: 9px;
  color: var(--textSecondary);
  font-size: 13px;
  text-decoration: none;
}

.analysis-header-action:hover,
.analysis-header-action:focus-visible {
  outline: none;
  border-color: color-mix(in srgb, var(--blue) 40%, var(--borderPrimary));
  background: color-mix(in srgb, var(--blue) 5%, var(--surfacePrimary));
}

.analysis-header-action:focus-visible {
  box-shadow: inset 0 0 0 2px var(--focus-ring);
}

.analysis-workspace {
  box-sizing: border-box;
  width: calc(100% - 32px);
  max-width: 1440px;
  margin: 0 auto;
  padding: 18px 0 56px;
}

.analysis-workspace.is-result-detail {
  max-width: 1120px;
  padding-top: 24px;
}

.analysis-workspace__topline {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-bottom: 12px;
}

.analysis-run-shell {
  min-width: 0;
}

.analysis-run-toggle {
  display: grid;
  width: 100%;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
  min-height: 54px;
  margin-top: 14px;
  padding: 8px 16px;
  border: 1px solid var(--borderPrimary);
  border-radius: 10px;
  color: var(--textSecondary);
  background: color-mix(
    in srgb,
    var(--surfaceSecondary) 76%,
    var(--surfacePrimary)
  );
  cursor: pointer;
  text-align: left;
}

.analysis-run-toggle:hover,
.analysis-run-toggle:focus-visible {
  outline: none;
  border-color: color-mix(in srgb, var(--blue) 34%, var(--borderPrimary));
  background: color-mix(in srgb, var(--blue) 4%, var(--surfacePrimary));
}

.analysis-run-toggle:focus-visible {
  box-shadow: inset 0 0 0 2px var(--focus-ring);
}

.analysis-run-toggle__icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--blue) 18%, transparent);
  border-radius: 9px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 9%, transparent);
}

.analysis-run-toggle__copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.analysis-run-toggle__copy strong,
.analysis-run-toggle__copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analysis-run-toggle__copy strong {
  font-size: 13px;
}

.analysis-run-toggle__copy small {
  color: var(--textPrimary);
  font-size: 11px;
}

.analysis-run-toggle > .app-icon {
  color: var(--textPrimary);
}

.analysis-run-shell.has-report :deep(.analysis-run-panel) {
  margin-top: 12px;
}

.analysis-readonly-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 6px;
  color: #16845d;
  background: color-mix(in srgb, #1ea672 11%, transparent);
  font-size: 11px;
  font-weight: 700;
}

.analysis-task-card,
.analysis-error {
  margin-top: 14px;
  border: 1px solid var(--borderPrimary);
  border-radius: 10px;
  background: var(--surfacePrimary);
}

.analysis-results-heading {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
}

.analysis-results-heading > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.analysis-results-heading__main {
  justify-content: flex-start;
  min-width: 0;
}

.analysis-results-heading > .analysis-results-heading__main {
  justify-content: flex-start;
}

.analysis-results-heading__icon {
  display: grid;
  width: 42px;
  height: 42px;
  flex-shrink: 0;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--blue) 20%, var(--borderPrimary));
  border-radius: 12px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 7%, var(--surfacePrimary));
}

.analysis-results-heading__copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.analysis-results-heading__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
}

.analysis-results-heading > .analysis-results-heading__actions {
  justify-content: flex-end;
}

.analysis-detail-action {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 11px;
  border: 1px solid var(--borderPrimary);
  border-radius: 8px;
  color: var(--textSecondary);
  background: var(--surfacePrimary);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  font-weight: 650;
  text-decoration: none;
  white-space: nowrap;
}

.analysis-detail-action:hover,
.analysis-detail-action:focus-visible {
  outline: none;
  border-color: color-mix(in srgb, var(--blue) 38%, var(--borderPrimary));
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 5%, var(--surfacePrimary));
}

.analysis-detail-action--back {
  color: var(--blue);
}

.analysis-results-heading__eyebrow {
  overflow: hidden;
  color: var(--blue);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.03em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analysis-results-heading h2 {
  margin: 0;
  color: var(--textSecondary);
  font-size: 21px;
  letter-spacing: -0.02em;
}

.analysis-results-heading p {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  margin: 2px 0 0;
  overflow: hidden;
  color: var(--textPrimary);
  font-size: 12px;
}

.analysis-results-heading p span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analysis-results-heading p time {
  flex: 0 0 auto;
  white-space: nowrap;
}

.analysis-cancel-action {
  border: 0;
  border-radius: 8px;
  cursor: pointer;
  font-weight: 700;
}

.analysis-cancel-action:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.analysis-task-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 16px;
  padding: 18px 20px;
}

.analysis-task-icon {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border-radius: 11px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 10%, transparent);
}

.analysis-task-icon.is-running .app-icon {
  animation: analysis-spin 1.4s linear infinite;
}

.analysis-task-icon.is-completed {
  color: #16845d;
  background: color-mix(in srgb, #1ea672 10%, transparent);
}

.analysis-task-icon.is-failed,
.analysis-task-icon.is-canceled,
.analysis-task-icon.is-interrupted {
  color: var(--red);
  background: color-mix(in srgb, var(--red) 8%, transparent);
}

.analysis-task-copy,
.analysis-task-copy > div:first-child {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.analysis-task-copy strong {
  color: var(--textSecondary);
  font-size: 15px;
}

.analysis-task-copy span,
.analysis-task-copy small {
  color: var(--textPrimary);
  font-size: 12px;
}

.analysis-task-error {
  color: var(--red) !important;
}

.analysis-task-progress {
  height: 5px;
  margin: 5px 0 2px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--surfaceSecondary);
}

.analysis-task-progress > div {
  height: 100%;
  border-radius: inherit;
  background: var(--blue);
  transition: width 180ms ease;
}

.analysis-cancel-action,
.analysis-task-card > a {
  min-height: 36px;
  padding: 0 12px;
  color: var(--textSecondary);
  background: var(--surfaceSecondary);
  font-size: 11px;
}

.analysis-task-card > a {
  display: inline-flex;
  align-items: center;
  border-radius: 8px;
  text-decoration: none;
}

.analysis-error {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px 18px;
}

.analysis-error {
  color: var(--red);
}

.analysis-error strong,
.analysis-error p {
  margin: 0;
}

.analysis-error p {
  margin-top: 3px;
  font-size: 12px;
}

.analysis-results-heading {
  margin-top: 20px;
  padding: 14px 16px;
  border: 1px solid var(--borderPrimary);
  border-radius: 12px;
  background: var(--surfacePrimary);
}

.analysis-readonly-chip {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border: 1px solid color-mix(in srgb, #16845d 22%, var(--borderPrimary));
  border-radius: 8px;
}

.analysis-metric-strip {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 12px;
}

.analysis-metric-strip article {
  display: grid;
  gap: 3px;
  min-width: 0;
  min-height: 76px;
  padding: 12px 14px;
  border: 1px solid var(--borderPrimary);
  border-radius: 10px;
  background: var(--surfacePrimary);
}

.analysis-metric-strip small,
.analysis-metric-strip span {
  color: var(--textPrimary);
  font-size: 11px;
}

.analysis-metric-strip strong {
  color: var(--textSecondary);
  font-size: 20px;
  letter-spacing: -0.03em;
}

.analysis-metric-strip .analysis-summary-time {
  font-size: 14px;
  letter-spacing: 0;
}

.analysis-metric-strip .analysis-summary-highlight {
  border-color: color-mix(in srgb, #1ea672 24%, var(--borderPrimary));
  background: color-mix(in srgb, #1ea672 5%, var(--surfacePrimary));
}

.analysis-summary-highlight strong {
  color: #16845d;
}

.analysis-report-note {
  margin-top: 9px;
  border: 1px solid
    color-mix(in srgb, var(--icon-orange) 24%, var(--borderPrimary));
  border-radius: 8px;
  color: var(--textSecondary);
  background: color-mix(in srgb, var(--icon-orange) 5%, transparent);
  font-size: 11px;
}

.analysis-report-note summary {
  display: inline-flex;
  min-height: 30px;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  color: var(--icon-orange);
  cursor: pointer;
  font-weight: 700;
}

.analysis-report-note p {
  margin: 0;
  padding: 0 10px 9px 31px;
  line-height: 1.45;
}

.storage-scope-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
  margin-top: 14px;
}

.storage-scope-grid article {
  display: grid;
  min-width: 0;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 11px;
  padding: 13px 15px;
  border: 1px solid var(--borderPrimary);
  border-radius: 8px;
  background: color-mix(in srgb, var(--surfacePrimary) 82%, transparent);
}

.storage-scope-grid > article > .app-icon {
  width: 20px;
  height: 20px;
  color: var(--blue);
}

.storage-scope-grid span {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.storage-scope-grid strong,
.storage-scope-grid small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.storage-scope-grid strong {
  font-size: 13px;
}

.storage-scope-grid small {
  color: var(--textPrimary);
  font-size: 11px;
}

.storage-rankings {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 12px;
}

.storage-rankings > article {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--borderPrimary);
  border-radius: 9px;
  background: var(--surfacePrimary);
}

.storage-rankings > article > .storage-ranking-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 52px;
  padding: 0 13px;
  border-bottom: 1px solid var(--borderPrimary);
  background: color-mix(
    in srgb,
    var(--surfaceSecondary) 34%,
    var(--surfacePrimary)
  );
}

.storage-ranking-header > div:first-child {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.storage-ranking-header > div:first-child > span {
  color: var(--textSecondary);
  font-size: 13px;
  font-weight: 700;
}

.storage-ranking-header > div:first-child > small {
  color: var(--textPrimary);
  font-size: 11px;
}

.storage-ranking-columns {
  display: grid;
  grid-template-columns: 68px 84px;
  gap: 10px;
  color: var(--textPrimary);
  font-size: 10px;
  text-align: right;
}

.storage-rankings > article > div:not(.storage-ranking-header) {
  max-height: 480px;
  overflow: auto;
}

.storage-rank-row {
  display: grid;
  min-width: 0;
  align-items: center;
  gap: 8px;
  min-height: 64px;
  padding: 7px 13px;
  border-bottom: 1px solid var(--borderPrimary);
  color: var(--textSecondary);
  text-decoration: none;
}

.storage-rank-row--directory {
  grid-template-columns: 24px 19px minmax(0, 1fr) 68px 84px;
}

.storage-rank-row--file {
  grid-template-columns: 24px 19px minmax(0, 1fr) 84px 112px;
}

.storage-rank-row:last-child {
  border-bottom: 0;
}

.storage-rank-row:hover,
.storage-rank-row:focus-visible {
  outline: none;
  background: color-mix(in srgb, var(--blue) 4%, var(--surfacePrimary));
}

.storage-rank-row:focus-visible {
  box-shadow: inset 0 0 0 2px var(--focus-ring);
}

.storage-rank-index {
  color: var(--textPrimary);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}

.storage-rank-row > .app-icon {
  width: 19px;
  height: 19px;
  color: var(--textPrimary);
}

.storage-rank-main {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.storage-rank-row strong,
.storage-rank-row small,
.storage-rank-row time {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.storage-rank-row strong {
  font-size: 12px;
}

.storage-rank-row small,
.storage-rank-row time {
  color: var(--textPrimary);
  font-size: 10px;
}

.storage-rank-row em {
  display: block;
  height: 3px;
  margin-top: 3px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--surfaceSecondary);
}

.storage-rank-row em > i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--blue);
  transition: width 180ms ease;
}

.storage-rank-count,
.storage-rank-size,
.storage-rank-time {
  min-width: 0;
  text-align: right;
  white-space: nowrap;
}

.storage-rank-count,
.storage-rank-time {
  color: var(--textPrimary);
  font-size: 10px;
}

.storage-rank-size {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.storage-rank-more {
  display: block;
  width: calc(100% - 26px);
  min-height: 34px;
  margin: 9px 13px 11px;
  border: 1px solid color-mix(in srgb, var(--blue) 25%, var(--borderPrimary));
  border-radius: 7px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 4%, var(--surfacePrimary));
  cursor: pointer;
  font-size: 11px;
  font-weight: 700;
}

.storage-rank-more:hover,
.storage-rank-more:focus-visible {
  outline: none;
  border-color: var(--blue);
  background: color-mix(in srgb, var(--blue) 9%, var(--surfacePrimary));
}

.storage-rank-more:focus-visible {
  box-shadow: 0 0 0 2px var(--focus-ring);
}

.storage-rank-empty {
  margin: 0;
  padding: 28px 14px;
  color: var(--textPrimary);
  font-size: 11px;
  text-align: center;
}

.analysis-clean-state {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  justify-items: start;
  gap: 16px;
  margin-top: 12px;
  padding: 22px 24px;
  border: 1px solid color-mix(in srgb, #1ea672 22%, var(--borderPrimary));
  border-radius: 12px;
  background: color-mix(in srgb, #1ea672 4%, var(--surfacePrimary));
  text-align: left;
}

.analysis-clean-state > .app-icon {
  width: 42px;
  height: 42px;
  padding: 9px;
  box-sizing: border-box;
  border: 1px solid color-mix(in srgb, #1ea672 22%, transparent);
  border-radius: 12px;
  color: #16845d;
  background: color-mix(in srgb, #1ea672 10%, transparent);
}

.analysis-clean-state__copy {
  min-width: 0;
}

.analysis-clean-state__copy h2 {
  margin: 0;
  color: var(--textSecondary);
  font-size: 16px;
}

.analysis-clean-state__copy p {
  margin: 5px 0 0;
  color: var(--textPrimary);
  font-size: 12px;
  line-height: 1.5;
}

.analysis-skipped {
  margin-top: 10px;
  padding: 11px 13px;
  border: 1px solid var(--borderPrimary);
  border-radius: 10px;
  background: var(--surfacePrimary);
  font-size: 11px;
}

.analysis-skipped summary {
  color: var(--textSecondary);
  cursor: pointer;
  font-weight: 700;
}

.analysis-skipped p {
  color: var(--textPrimary);
}

.analysis-skipped ul {
  display: grid;
  gap: 5px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.analysis-skipped li {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding-top: 5px;
  border-top: 1px solid var(--borderPrimary);
}

.analysis-skipped li small {
  color: var(--textPrimary);
}

@keyframes analysis-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 760px) {
  .analysis-workspace {
    width: min(100% - 20px, 1120px);
    padding-top: 14px;
  }

  .analysis-workspace__topline {
    align-items: stretch;
    flex-direction: column;
    gap: 8px;
    min-height: 0;
  }

  .analysis-tool-switcher {
    width: 100%;
  }

  .analysis-run-toggle {
    min-height: 54px;
  }

  .analysis-metric-strip {
    grid-template-columns: 1fr;
  }

  .analysis-metric-strip article {
    border: 1px solid var(--borderPrimary);
  }

  .storage-rankings {
    grid-template-columns: 1fr;
  }

  .storage-ranking-columns {
    display: none;
  }

  .storage-rank-row--directory,
  .storage-rank-row--file {
    grid-template-columns: 24px 19px minmax(0, 1fr) auto;
  }

  .storage-rank-count,
  .storage-rank-time {
    display: none;
  }

  .analysis-task-card {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .analysis-task-card > button,
  .analysis-task-card > a {
    grid-column: 2;
    justify-self: start;
  }
}

@media (max-width: 520px) {
  .analysis-header-action {
    width: 42px;
    justify-content: center;
    padding: 0;
    font-size: 0;
  }

  .analysis-results-heading {
    grid-template-columns: 1fr;
    align-items: stretch;
  }

  .analysis-results-heading__main {
    width: 100%;
  }

  .analysis-results-heading__copy {
    max-width: calc(100% - 54px);
  }

  .analysis-readonly-chip {
    margin-inline-start: auto;
  }

  .analysis-results-heading__actions {
    justify-content: flex-start;
  }

  .analysis-results-heading > .analysis-results-heading__actions {
    justify-content: flex-start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .analysis-task-icon.is-running .app-icon {
    animation: none;
  }

  .analysis-task-progress > div,
  .storage-rankings em > i {
    transition: none;
  }
}
</style>
