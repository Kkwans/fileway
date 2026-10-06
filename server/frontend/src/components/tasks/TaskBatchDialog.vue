<template>
  <app-dialog
    :title="title"
    :description="description"
    :close-disabled="busy"
    size="small"
    @closed="emit('close')"
  >
    <template #icon>
      <app-icon :name="actionIcon" :size="22" />
    </template>

    <template v-if="!result">
      <dl class="task-batch-dialog__summary">
        <div>
          <dt>任务数量</dt>
          <dd>{{ count }} 项</dd>
        </div>
        <div>
          <dt>涉及用户</dt>
          <dd>{{ ownerLabel }}</dd>
        </div>
      </dl>
      <p class="task-batch-dialog__note">{{ actionNote }}</p>
    </template>

    <template v-else>
      <div class="task-batch-dialog__result">
        <div class="is-success">
          <strong>{{ result.succeeded }}</strong>
          <span>成功</span>
        </div>
        <div>
          <strong>{{ result.skipped }}</strong>
          <span>跳过</span>
        </div>
        <div :class="{ 'is-failed': failureCount > 0 }">
          <strong>{{ failureCount }}</strong>
          <span>失败</span>
        </div>
      </div>
      <details v-if="failureCount" class="task-batch-dialog__failures">
        <summary>查看失败项</summary>
        <ul>
          <li v-for="failure in result.failures" :key="failure.id">
            <strong>{{ failure.id }}</strong>
            <span>{{ failure.error }}</span>
          </li>
        </ul>
      </details>
    </template>

    <template #footer>
      <div class="task-batch-dialog__actions">
        <button
          type="button"
          class="secondary"
          :disabled="busy"
          @click="emit('close')"
        >
          {{ result ? "完成" : "取消" }}
        </button>
        <button
          v-if="!result"
          id="focus-prompt"
          type="button"
          class="primary"
          :disabled="busy || count === 0"
          @click="emit('confirm')"
        >
          <app-icon v-if="busy" name="loader" :size="18" />
          {{ busy ? "正在处理…" : confirmLabel }}
        </button>
      </div>
    </template>
  </app-dialog>
</template>

<script setup lang="ts">
import { computed } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import AppDialog from "@/components/ui/AppDialog.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import type { TaskBatchAction, TaskBatchResponse } from "@/api/tasks";

const props = defineProps<{
  action: TaskBatchAction;
  count: number;
  owners: string[];
  busy?: boolean;
  result?: TaskBatchResponse | null;
}>();

const emit = defineEmits<{ close: []; confirm: [] }>();

const actionMeta: Record<
  TaskBatchAction,
  { title: string; confirm: string; note: string; icon: AppIconName }
> = {
  retry: {
    title: "处理当前需处理任务",
    confirm: "确认重试",
    note: "仅重试失败和中断任务；已取消任务不会被重新启动。",
    icon: "retry",
  },
  archive: {
    title: "归档当前筛选任务",
    confirm: "确认归档",
    note: "归档不会删除任务或结果，之后可在“已归档”中恢复。",
    icon: "archive",
  },
  unarchive: {
    title: "恢复当前归档任务",
    confirm: "确认恢复",
    note: "恢复后任务会回到它原本的状态分类。",
    icon: "archive-restore",
  },
};

const meta = computed(() => actionMeta[props.action]);
const title = computed(() =>
  props.result ? "批量操作结果" : meta.value.title
);
const description = computed(() =>
  props.result ? "本次批量操作已完成。" : "操作范围已锁定为当前筛选结果。"
);
const confirmLabel = computed(() => meta.value.confirm);
const actionNote = computed(() => meta.value.note);
const actionIcon = computed(() => meta.value.icon);
const failureCount = computed(() => props.result?.failures?.length ?? 0);
const ownerLabel = computed(() => {
  if (!props.owners.length) return "当前用户";
  if (props.owners.length <= 3) return props.owners.join("、");
  return `${props.owners.slice(0, 3).join("、")} 等 ${props.owners.length} 位用户`;
});
</script>

<style scoped>
.task-batch-dialog__summary,
.task-batch-dialog__result {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 18px 0 0;
}

.task-batch-dialog__summary > div,
.task-batch-dialog__result > div {
  padding: 13px;
  border-radius: 10px;
  background: var(--surfaceSecondary);
}

.task-batch-dialog__summary dt,
.task-batch-dialog__result span {
  color: var(--textPrimary);
  font-size: 11px;
}

.task-batch-dialog__summary dd,
.task-batch-dialog__result strong {
  margin: 4px 0 0;
  font-size: 15px;
  font-weight: 700;
}

.task-batch-dialog__result {
  grid-template-columns: repeat(3, 1fr);
}

.task-batch-dialog__result > div {
  display: grid;
  gap: 2px;
}

.task-batch-dialog__result .is-success strong {
  color: var(--icon-green);
}

.task-batch-dialog__result .is-failed strong {
  color: var(--icon-red);
}

.task-batch-dialog__note {
  margin: 12px 0 0;
  padding: 11px 12px;
  border-radius: 9px;
  color: var(--textPrimary);
  background: var(--surfaceSecondary);
  font-size: 12px;
  line-height: 1.55;
}

.task-batch-dialog__failures {
  margin-top: 12px;
  color: var(--textPrimary);
  font-size: 12px;
}

.task-batch-dialog__failures ul {
  max-height: 160px;
  margin: 8px 0 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.task-batch-dialog__failures li {
  display: grid;
  gap: 2px;
  padding: 8px 0;
  border-top: 1px solid var(--borderPrimary);
}

.task-batch-dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.task-batch-dialog__actions button {
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

.task-batch-dialog__actions button.primary {
  color: #fff;
  border-color: var(--blue);
  background: var(--blue);
}

.task-batch-dialog__actions button:disabled {
  cursor: wait;
  opacity: 0.65;
}

.task-batch-dialog__actions .app-icon:deep(svg),
.task-batch-dialog__actions :deep(.app-icon) {
  animation: task-dialog-spin 1s linear infinite;
}

@keyframes task-dialog-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .task-batch-dialog__actions .app-icon:deep(svg),
  .task-batch-dialog__actions :deep(.app-icon) {
    animation: none;
  }
}
</style>
