<template>
  <section class="analysis-recent" aria-labelledby="analysis-recent-title">
    <div class="analysis-recent__header">
      <span class="analysis-recent__header-icon" aria-hidden="true">
        <AppIcon name="history" :size="18" />
      </span>
      <div>
        <h2 id="analysis-recent-title">最近扫描</h2>
      </div>
      <button
        v-if="items.length"
        type="button"
        class="analysis-recent__clear"
        :disabled="clearing"
        title="清空当前工具的已完成记录"
        aria-label="清空当前工具的已完成记录"
        @click="$emit('clear')"
      >
        <AppIcon name="trash" :size="17" />
      </button>
      <button
        v-if="error"
        type="button"
        class="analysis-recent__retry"
        @click="$emit('retry')"
      >
        重新加载
      </button>
    </div>

    <div v-if="loading" class="analysis-recent__state" role="status">
      <AppIcon name="scan" :size="18" />
      正在读取最近扫描…
    </div>
    <div v-else-if="error" class="analysis-recent__state is-error" role="alert">
      <AppIcon name="info" :size="18" />
      {{ error }}
    </div>
    <div v-else-if="!items.length" class="analysis-recent__state">
      <AppIcon :name="toolIcon" :size="18" />
      还没有{{ toolLabel }}记录，完成一次扫描后会显示在这里。
    </div>

    <div
      v-if="!loading && !error && items.length"
      class="analysis-recent__columns"
      aria-hidden="true"
    >
      <span class="analysis-recent__column-scope">扫描范围</span>
      <span class="analysis-recent__column-status">状态</span>
      <span class="analysis-recent__column-time">扫描时间</span>
      <span class="analysis-recent__column-actions">操作</span>
    </div>

    <ul v-if="!loading && !error && items.length" class="analysis-recent__list">
      <li v-for="item in items" :key="item.id">
        <span
          :class="[
            'analysis-recent__tool',
            item.tool === 'storage' ? 'is-storage' : 'is-duplicates',
          ]"
          aria-hidden="true"
        >
          <AppIcon
            :name="item.tool === 'storage' ? 'analysis-storage' : 'scan'"
            :size="19"
          />
        </span>
        <div class="analysis-recent__content">
          <div class="analysis-recent__scope" :title="item.scopes.join('、')">
            <b>{{ scopeLabel(item) }}</b>
          </div>
          <p class="analysis-recent__metrics">{{ metricsLabel(item) }}</p>
        </div>
        <div class="analysis-recent__status">
          <span
            :class="[
              `is-${item.status}`,
              {
                'is-result-unavailable':
                  item.status === 'completed' && !item.resultReady,
              },
            ]"
            >{{ statusLabel(item) }}</span
          >
        </div>
        <div class="analysis-recent__time-block">
          <time
            class="analysis-recent__time"
            :datetime="new Date(recordTime(item)).toISOString()"
            :aria-label="`${item.finishedAt ? '结束时间' : '提交时间'} ${formatTime(recordTime(item))}`"
          >
            <span>{{ formatDate(recordTime(item)) }}</span>
            <b>{{ formatClock(recordTime(item)) }}</b>
          </time>
        </div>
        <router-link
          class="analysis-recent__action"
          :to="{
            path: '/analysis',
            query: { tool: item.tool, task: item.id },
          }"
          :aria-label="`${itemToolLabel(item)}：${actionLabel(item)}`"
        >
          <span>{{ actionLabel(item) }}</span>
          <AppIcon name="arrow-right" :size="16" />
        </router-link>
      </li>
    </ul>

    <div
      v-if="!loading && !error && items.length && hasMore"
      class="analysis-recent__load-more"
    >
      <button type="button" :disabled="loadingMore" @click="$emit('load-more')">
        <AppIcon v-if="loadingMore" name="loader" :size="16" />
        {{ loadingMore ? "正在加载…" : "加载更多" }}
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { AnalysisRecentItem } from "@/api/analysis";
import AppIcon from "@/components/ui/AppIcon.vue";
import { filesize } from "@/utils";
import dayjs from "@/utils/date";
import type { AnalysisTool } from "@/utils/analysisTools";

const props = defineProps<{
  tool: AnalysisTool;
  items: AnalysisRecentItem[];
  loading: boolean;
  loadingMore: boolean;
  hasMore: boolean;
  clearing: boolean;
  error: string;
}>();

defineEmits<{ retry: []; "load-more": []; clear: [] }>();

const toolLabel = computed(() =>
  props.tool === "storage" ? "空间分析" : "重复文件扫描"
);
const toolIcon = computed(() =>
  props.tool === "storage" ? "analysis-storage" : "analysis-duplicates"
);

function itemToolLabel(item: AnalysisRecentItem) {
  return item.tool === "storage" ? "空间分析" : "重复文件扫描";
}

function statusLabel(item: AnalysisRecentItem) {
  const labels: Record<AnalysisRecentItem["status"], string> = {
    queued: "排队中",
    running: "扫描中",
    completed: item.resultReady ? "已完成" : "结果不可用",
    failed: "失败",
    canceled: "已取消",
    interrupted: "已中断",
  };
  return labels[item.status];
}

function scopeLabel(item: AnalysisRecentItem) {
  if (!item.scopes.length) return "扫描范围不可用";
  const first = item.scopes[0];
  return item.scopes.length === 1
    ? first
    : `${first} 等 ${item.scopes.length} 个范围`;
}

function metricsLabel(item: AnalysisRecentItem) {
  if (item.metrics) {
    if (item.tool === "storage") {
      return `${item.metrics.scannedFiles} 个文件 · ${item.metrics.scannedDirectories} 个目录 · ${filesize(item.metrics.scannedBytes)}`;
    }
    return `扫描 ${item.metrics.scannedFiles} 个文件 · 重复 ${item.metrics.duplicateGroups} 组 · 可回收 ${filesize(item.metrics.reclaimableBytes)}`;
  }
  if (item.error) return item.error;
  if (item.status === "queued") return "正在等待低并发扫描队列";
  if (item.status === "running") {
    return item.totalItems > 0
      ? `已处理 ${item.processedItems} / ${item.totalItems} 项`
      : `已处理 ${item.processedItems} 项，正在继续扫描`;
  }
  if (item.status === "completed") return "扫描已完成，但报告暂时无法读取";
  return "任务未生成分析报告";
}

function actionLabel(item: AnalysisRecentItem) {
  return item.status === "completed" && item.resultReady
    ? "查看结果"
    : "查看详情";
}

function recordTime(item: AnalysisRecentItem) {
  return item.finishedAt || item.createdAt;
}

function formatTime(value: number) {
  return dayjs(value).format("YYYY-MM-DD HH:mm:ss");
}

function formatDate(value: number) {
  return dayjs(value).format("YYYY-MM-DD");
}

function formatClock(value: number) {
  return dayjs(value).format("HH:mm:ss");
}
</script>

<style scoped>
.analysis-recent {
  margin-top: 20px;
  border: 1px solid var(--borderPrimary);
  border-radius: 10px;
  background: var(--surfacePrimary);
  container-type: inline-size;
}
.analysis-recent__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--borderPrimary);
}
.analysis-recent__header-icon {
  display: flex;
  color: var(--textPrimary);
}
.analysis-recent__header h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.analysis-recent__header button {
  margin-inline-start: auto;
  min-height: 40px;
  border: 0;
  color: var(--blue);
  background: transparent;
  cursor: pointer;
}
.analysis-recent__header .analysis-recent__clear {
  display: inline-grid;
  width: 40px;
  min-width: 40px;
  height: 40px;
  margin-inline-start: auto;
  place-items: center;
  color: var(--textPrimary);
  border-radius: 7px;
}
.analysis-recent__header .analysis-recent__clear + .analysis-recent__retry {
  margin-inline-start: 0;
}
.analysis-recent__header .analysis-recent__clear:hover,
.analysis-recent__header .analysis-recent__clear:focus-visible {
  color: var(--icon-red);
  background: var(--hover);
}
.analysis-recent__header .analysis-recent__clear:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.analysis-recent__header .analysis-recent__retry {
  margin-inline-start: auto;
}
.analysis-recent__state {
  display: flex;
  min-height: 88px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 16px;
  color: var(--textPrimary);
  font-size: 13px;
}
.analysis-recent__state.is-error {
  color: var(--red);
}
.analysis-recent__columns,
.analysis-recent__list > li {
  display: grid;
  grid-template-columns:
    24px minmax(0, 1.4fr) minmax(220px, 0.8fr) minmax(132px, auto)
    92px;
  align-items: center;
  gap: 12px;
  padding-inline: 16px;
}
.analysis-recent__columns {
  min-height: 32px;
  border-bottom: 1px solid var(--borderPrimary);
  color: var(--textPrimary);
  font-size: 11px;
}
.analysis-recent__column-scope {
  grid-column: 1 / span 2;
  text-align: start;
}
.analysis-recent__column-status {
  grid-column: 3;
  justify-self: center;
  text-align: center;
}
.analysis-recent__column-time {
  grid-column: 4;
  justify-self: center;
  text-align: center;
}
.analysis-recent__column-actions {
  grid-column: 5;
  justify-self: center;
  text-align: center;
}
.analysis-recent__list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.analysis-recent__list > li {
  min-height: 64px;
  padding-block: 8px;
}
.analysis-recent__list > li + li {
  border-top: 1px solid var(--borderPrimary);
}
.analysis-recent__list > li:hover {
  background: var(--hover);
}
.analysis-recent__tool {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--blue);
}
.analysis-recent__tool.is-storage {
  color: var(--icon-blue);
}
.analysis-recent__content {
  min-width: 0;
}
.analysis-recent__scope {
  display: flex;
  align-items: center;
  min-height: 28px;
  color: var(--textSecondary);
  cursor: default;
}
.analysis-recent__scope b {
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.analysis-recent__status {
  display: grid;
  min-width: 0;
  min-height: 44px;
  place-items: center;
}
.analysis-recent__status > span {
  font-size: 12px;
  font-weight: 600;
  text-align: center;
}
.analysis-recent__status .is-completed {
  color: var(--icon-green);
}
.analysis-recent__status .is-failed,
.analysis-recent__status .is-interrupted,
.analysis-recent__status .is-result-unavailable {
  color: var(--red);
}
.analysis-recent__metrics {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--textPrimary);
  font-size: 11px;
  line-height: 1.4;
  text-align: left;
}
.analysis-recent__time-block {
  display: flex;
  min-width: 0;
  justify-content: center;
}
.analysis-recent__time {
  display: grid;
  justify-items: center;
  gap: 6px;
  color: var(--textPrimary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.analysis-recent__time b {
  font-weight: 400;
}
.analysis-recent__action {
  position: relative;
  display: inline-flex;
  width: 92px;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  padding: 0 20px 0 4px;
  color: var(--blue);
  font-size: 12px;
  text-decoration: none;
  white-space: nowrap;
}
.analysis-recent__action > .app-icon {
  position: absolute;
  right: 2px;
}
.analysis-recent__action:hover {
  text-decoration: underline;
}
.analysis-recent__load-more {
  display: flex;
  justify-content: center;
  padding: 10px 16px 14px;
  border-top: 1px solid var(--borderPrimary);
}
.analysis-recent__load-more button {
  display: inline-flex;
  min-height: 40px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 16px;
  border: 1px solid var(--borderPrimary);
  border-radius: 7px;
  color: var(--blue);
  background: var(--surfacePrimary);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}
.analysis-recent__load-more button:hover,
.analysis-recent__load-more button:focus-visible {
  border-color: color-mix(in srgb, var(--blue) 35%, var(--borderPrimary));
  background: color-mix(in srgb, var(--blue) 5%, var(--surfacePrimary));
}
.analysis-recent__load-more button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.analysis-recent__action:focus-visible,
.analysis-recent__scope:focus-visible,
.analysis-recent__header button:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}
@container (max-width: 720px) {
  .analysis-recent__columns {
    display: none;
  }
  .analysis-recent__list > li {
    grid-template-columns: 20px minmax(0, 1fr);
    gap: 2px 8px;
    padding-inline: 12px;
  }
  .analysis-recent__scope {
    min-height: 44px;
  }
  .analysis-recent__status,
  .analysis-recent__time-block,
  .analysis-recent__action {
    grid-column: 2;
  }
  .analysis-recent__status {
    display: flex;
    align-items: center;
    justify-content: flex-start;
  }
  .analysis-recent__status > span {
    text-align: left;
  }
  .analysis-recent__time-block {
    justify-content: flex-start;
  }
  .analysis-recent__time {
    display: flex;
    gap: 6px;
    justify-items: initial;
  }
  .analysis-recent__action {
    justify-self: start;
  }
}
</style>
