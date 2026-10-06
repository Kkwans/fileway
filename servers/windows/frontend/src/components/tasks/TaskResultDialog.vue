<template>
  <app-dialog
    title="任务结果"
    :description="task.title"
    close-label="关闭任务结果"
    size="large"
    @closed="emit('close')"
  >
    <template #icon>
      <app-icon :name="resultIcon" :size="22" />
    </template>

    <div class="task-result-dialog__body">
      <div v-if="loading" class="task-result-state" aria-live="polite">
        <app-icon class="is-spinning" name="loader" :size="26" />
        <strong>正在读取结果</strong>
        <span>只加载此任务的公开报告，不读取内部任务参数。</span>
      </div>

      <div v-else-if="error" class="task-result-state is-error" role="alert">
        <app-icon name="circle-alert" :size="26" />
        <strong>无法读取任务结果</strong>
        <span>{{ error }}</span>
        <button type="button" @click="load">重新读取</button>
      </div>

      <template v-else-if="duplicateReport">
        <div class="task-result-summary" aria-label="重复文件结果摘要">
          <article>
            <span>已扫描</span>
            <strong>{{ duplicateReport.scannedFiles.toLocaleString() }}</strong>
            <small>{{ filesize(duplicateReport.scannedBytes) }}</small>
          </article>
          <article>
            <span>重复组</span>
            <strong>{{
              duplicateReport.duplicateGroups.toLocaleString()
            }}</strong>
            <small
              >{{
                duplicateReport.duplicateFiles.toLocaleString()
              }}
              个文件</small
            >
          </article>
          <article class="is-highlight">
            <span>可回收空间</span>
            <strong>{{ filesize(duplicateReport.reclaimableBytes) }}</strong>
            <small>保留每组一份后的估算值</small>
          </article>
        </div>
        <div class="task-result-context">
          <strong>扫描范围</strong>
          <span>{{ duplicateReport.scopes.join("、") }}</span>
        </div>
        <ol v-if="duplicateReport.groups.length" class="task-result-list">
          <li
            v-for="group in duplicateReport.groups.slice(0, 3)"
            :key="group.sha256"
          >
            <span>{{ group.totalFiles }} 个相同文件</span>
            <strong>{{ filesize(group.reclaimableBytes) }}</strong>
            <small :title="group.files[0]?.path">{{
              group.files[0]?.path
            }}</small>
          </li>
        </ol>
        <p v-else class="task-result-clean">
          <app-icon
            name="circle-check"
            :size="18"
          />所选范围内没有确认的重复文件。
        </p>
      </template>

      <template v-else-if="storageReport">
        <div class="task-result-summary" aria-label="空间分析结果摘要">
          <article>
            <span>文件</span>
            <strong>{{ storageReport.scannedFiles.toLocaleString() }}</strong>
            <small
              >{{
                storageReport.scannedDirectories.toLocaleString()
              }}
              个目录</small
            >
          </article>
          <article class="is-highlight">
            <span>已统计空间</span>
            <strong>{{ filesize(storageReport.scannedBytes) }}</strong>
            <small>{{ storageReport.scopes.length }} 个范围</small>
          </article>
          <article>
            <span>已跳过</span>
            <strong>{{ storageReport.skippedCount.toLocaleString() }}</strong>
            <small>无权限或扫描期间变化</small>
          </article>
        </div>
        <div class="task-result-context">
          <strong>统计范围</strong>
          <span>{{
            storageReport.scopes.map((scope) => scope.path).join("、")
          }}</span>
        </div>
        <ol v-if="storageReport.largestFiles.length" class="task-result-list">
          <li
            v-for="file in storageReport.largestFiles.slice(0, 4)"
            :key="file.path"
          >
            <span>大文件</span>
            <strong>{{ filesize(file.size) }}</strong>
            <small :title="file.path">{{ file.path }}</small>
          </li>
        </ol>
      </template>

      <template v-else-if="archiveReport">
        <div class="task-result-summary" aria-label="解压结果摘要">
          <article>
            <span>已解压文件</span>
            <strong>{{ archiveReport.extractedFiles.toLocaleString() }}</strong>
            <small
              >{{ archiveReport.extractedDirs.toLocaleString() }} 个目录</small
            >
          </article>
          <article class="is-highlight">
            <span>写入空间</span>
            <strong>{{ filesize(archiveReport.extractedBytes) }}</strong>
            <small>{{ archiveReport.selected.length }} 个选择项</small>
          </article>
          <article>
            <span>已跳过</span>
            <strong>{{ archiveReport.skippedCount.toLocaleString() }}</strong>
            <small>冲突或安全限制</small>
          </article>
        </div>
        <dl class="task-result-paths">
          <div>
            <dt>压缩包</dt>
            <dd>{{ archiveReport.archivePath }}</dd>
          </div>
          <div>
            <dt>目标目录</dt>
            <dd>{{ archiveReport.destination }}</dd>
          </div>
        </dl>
      </template>
    </div>

    <template #footer>
      <div class="task-result-dialog__actions">
        <button type="button" @click="emit('close')">关闭</button>
        <button type="button" class="primary" @click="emit('full-report')">
          <app-icon name="external-link" :size="17" />查看完整报告
        </button>
      </div>
    </template>
  </app-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import * as analysisApi from "@/api/analysis";
import * as archiveApi from "@/api/archive";
import type { TaskItem } from "@/api/tasks";
import AppDialog from "@/components/ui/AppDialog.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import { filesize } from "@/utils";

const props = defineProps<{ task: TaskItem }>();
const emit = defineEmits<{ close: []; "full-report": [] }>();

const loading = ref(true);
const error = ref("");
const duplicateReport = ref<analysisApi.DuplicateReport>();
const storageReport = ref<analysisApi.StorageReport>();
const archiveReport = ref<archiveApi.ArchiveExtractReport>();

const resultIcon = computed<AppIconName>(() => {
  if (props.task.type === "analysis.duplicates") return "analysis-duplicates";
  if (props.task.type === "analysis.storage") return "analysis-storage";
  return "archive";
});

async function load() {
  loading.value = true;
  error.value = "";
  try {
    if (props.task.type === "analysis.duplicates") {
      duplicateReport.value = await analysisApi.getDuplicateReport(
        props.task.id
      );
    } else if (props.task.type === "analysis.storage") {
      storageReport.value = await analysisApi.getStorageReport(props.task.id);
    } else if (props.task.type === "archive.extract") {
      archiveReport.value = await archiveApi.extractionResult(props.task.id);
    } else {
      throw new Error("此任务没有可展示的结果报告");
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "读取结果失败";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.task-result-dialog__body {
  min-width: 0;
}

.task-result-state {
  display: grid;
  min-height: 240px;
  place-items: center;
  align-content: center;
  gap: 8px;
  color: var(--textPrimary);
  text-align: center;
}

.task-result-state.is-error > .app-icon {
  color: var(--icon-red);
}

.task-result-state span {
  max-width: 420px;
  font-size: 12px;
}

.task-result-state button {
  min-height: 44px;
  margin-top: 8px;
  padding: 0 16px;
  border: 1px solid var(--borderPrimary);
  border-radius: 9px;
  color: var(--textSecondary);
  background: var(--surfacePrimary);
  cursor: pointer;
}

.task-result-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.task-result-summary article {
  display: grid;
  min-width: 0;
  gap: 4px;
  padding: 13px 14px;
  border: 1px solid var(--borderPrimary);
  border-radius: 11px;
  background: var(--surfaceSecondary);
}

.task-result-summary article.is-highlight {
  border-color: color-mix(in srgb, var(--blue) 24%, var(--borderPrimary));
  background: color-mix(in srgb, var(--blue) 5%, var(--surfacePrimary));
}

.task-result-summary span,
.task-result-summary small {
  color: var(--textPrimary);
  font-size: 11px;
}

.task-result-summary strong {
  overflow: hidden;
  font-size: 20px;
  text-overflow: ellipsis;
}

.task-result-context,
.task-result-paths {
  display: grid;
  gap: 5px;
  margin: 14px 0 0;
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--surfaceSecondary);
  font-size: 12px;
}

.task-result-context span,
.task-result-paths dd {
  overflow-wrap: anywhere;
  color: var(--textPrimary);
}

.task-result-list {
  display: grid;
  gap: 0;
  margin: 14px 0 0;
  padding: 0;
  border: 1px solid var(--borderPrimary);
  border-radius: 10px;
  list-style: none;
}

.task-result-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 3px 12px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--borderPrimary);
}

.task-result-list li:last-child {
  border-bottom: 0;
}

.task-result-list small {
  grid-column: 1 / -1;
  overflow: hidden;
  color: var(--textPrimary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-result-clean {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 14px 0 0;
  padding: 13px 14px;
  border-radius: 10px;
  color: var(--icon-green);
  background: color-mix(in srgb, var(--icon-green) 7%, var(--surfacePrimary));
  font-size: 12px;
}

.task-result-paths > div {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 10px;
}

.task-result-paths dt,
.task-result-paths dd {
  margin: 0;
}

.task-result-dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.task-result-dialog__actions button {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 15px;
  border: 1px solid var(--borderPrimary);
  border-radius: 9px;
  color: var(--textSecondary);
  background: var(--surfacePrimary);
  cursor: pointer;
}

.task-result-dialog__actions button.primary {
  border-color: var(--blue);
  color: #fff;
  background: var(--blue);
}

.is-spinning {
  animation: activity-spin 1s linear infinite;
}

@keyframes activity-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 600px) {
  .task-result-summary {
    grid-template-columns: 1fr;
  }

  .task-result-dialog__actions button {
    flex: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .is-spinning {
    animation: none;
  }
}
</style>
