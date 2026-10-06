<template>
  <form
    class="analysis-run-panel"
    aria-labelledby="analysis-scope-title"
    @submit.prevent="$emit('start')"
  >
    <div class="analysis-run-panel__heading">
      <h2 id="analysis-scope-title">扫描范围</h2>
      <small aria-live="polite">
        {{
          scopes.length
            ? "已选 " + scopes.length + " 个，最多 32 个"
            : "尚未选择范围"
        }}
      </small>
    </div>

    <div class="analysis-run-panel__body">
      <section
        class="analysis-run-panel__selection"
        aria-label="本次选择的扫描范围"
      >
        <div class="analysis-run-panel__section-heading">
          <strong>本次选择</strong>
          <button
            type="button"
            class="analysis-run-panel__browse"
            :disabled="scopes.length >= 32"
            @click="$emit('browse')"
          >
            <AppIcon name="folder-new" :size="18" />添加范围
          </button>
        </div>

        <div v-if="scopes.length" class="analysis-run-panel__scopes">
          <span v-for="scope in scopes" :key="scope">
            <AppIcon name="folder" :size="16" />
            <b :title="scope">{{ scope }}</b>
            <button
              type="button"
              :aria-label="'移除 ' + scope"
              @click="$emit('remove', scope)"
            >
              <AppIcon name="x" :size="16" />
            </button>
          </span>
        </div>
        <div v-else class="analysis-run-panel__empty">
          <AppIcon name="folder" :size="19" aria-hidden="true" />
          <span>尚未选择范围</span>
          <button
            type="button"
            class="analysis-run-panel__empty-action"
            :disabled="scopes.length >= 32"
            @click="$emit('browse')"
          >
            选择目录
          </button>
        </div>
      </section>

      <aside class="analysis-run-panel__summary" aria-label="本次扫描摘要">
        <div class="analysis-run-panel__summary-heading">
          <strong>本次扫描摘要</strong>
          <span
            class="analysis-run-panel__info"
            title="扫描只读，不会删除文件。"
            aria-label="扫描只读，不会删除文件"
          >
            <AppIcon name="info" :size="17" />
          </span>
        </div>
        <dl>
          <div>
            <dt>分析类型</dt>
            <dd>{{ tool === "storage" ? "空间分布" : "重复文件" }}</dd>
          </div>
          <div>
            <dt>扫描范围</dt>
            <dd>{{ scopes.length ? scopes.length + " 个" : "尚未选择" }}</dd>
          </div>
        </dl>

        <label v-if="includesRoot" class="analysis-run-panel__root-confirm">
          <input
            :checked="rootConfirmed"
            type="checkbox"
            @change="updateRootConfirmed"
          />
          <span>
            <strong>确认扫描整个可访问范围</strong>
            <small>根目录扫描可能唤醒更多磁盘并持续较长时间。</small>
          </span>
        </label>

        <div class="analysis-run-panel__footer">
          <span>结果会作为只读报告保存。</span>
          <button
            type="submit"
            class="analysis-run-panel__start"
            :disabled="!canStart"
          >
            <AppIcon :name="starting ? 'scan' : 'play'" :size="18" />{{
              starting ? "正在提交…" : "开始扫描"
            }}
          </button>
        </div>
      </aside>
    </div>
  </form>
</template>

<script setup lang="ts">
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AnalysisTool } from "@/utils/analysisTools";

defineProps<{
  tool: AnalysisTool;
  scopes: string[];
  includesRoot: boolean;
  rootConfirmed: boolean;
  canStart: boolean;
  starting: boolean;
}>();

const emit = defineEmits<{
  "update:rootConfirmed": [value: boolean];
  remove: [scope: string];
  start: [];
  browse: [];
}>();

function updateRootConfirmed(event: Event) {
  emit("update:rootConfirmed", (event.target as HTMLInputElement).checked);
}
</script>

<style scoped>
.analysis-run-panel {
  display: grid;
  min-width: 0;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--borderPrimary);
  border-radius: 10px;
  background: var(--surfacePrimary);
}
.analysis-run-panel__heading,
.analysis-run-panel__section-heading,
.analysis-run-panel__summary-heading,
.analysis-run-panel__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.analysis-run-panel__heading h2,
.analysis-run-panel__section-heading strong,
.analysis-run-panel__summary-heading strong {
  margin: 0;
  color: var(--textSecondary);
  font-size: 14px;
  font-weight: 650;
}
.analysis-run-panel__heading small,
.analysis-run-panel__empty,
.analysis-run-panel__footer > span,
.analysis-run-panel__summary dd,
.analysis-run-panel__root-confirm small {
  margin: 0;
  color: var(--textPrimary);
  font-size: 12px;
  line-height: 1.5;
}
.analysis-run-panel__body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 320px);
  align-items: stretch;
  gap: 16px;
}
.analysis-run-panel__selection,
.analysis-run-panel__summary {
  min-width: 0;
  height: 100%;
  box-sizing: border-box;
  padding: 12px;
  border: 1px solid var(--borderPrimary);
  border-radius: 9px;
  background: var(--surfaceSecondary);
}
.analysis-run-panel__section-heading {
  min-height: 40px;
}
.analysis-run-panel__browse,
.analysis-run-panel__start {
  display: inline-flex;
  min-height: 40px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 12px;
  border: 1px solid var(--borderPrimary);
  border-radius: 7px;
  color: var(--textSecondary);
  background: var(--surfacePrimary);
  font: inherit;
  font-size: 13px;
  font-weight: 550;
  cursor: pointer;
}
.analysis-run-panel__browse:hover {
  border-color: color-mix(in srgb, var(--blue) 35%, var(--borderPrimary));
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 5%, var(--surfacePrimary));
}
.analysis-run-panel__scopes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 8px;
}
.analysis-run-panel__scopes > span {
  display: grid;
  min-width: 0;
  grid-template-columns: auto minmax(0, auto) auto;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding-inline-start: 10px;
  border: 1px solid color-mix(in srgb, var(--blue) 22%, var(--borderPrimary));
  border-radius: 8px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 6%, var(--surfacePrimary));
  font-size: 12px;
}
.analysis-run-panel__scopes b {
  overflow: hidden;
  font-weight: 550;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.analysis-run-panel__scopes button {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 7px;
  color: inherit;
  background: transparent;
  cursor: pointer;
}
.analysis-run-panel__scopes button:hover,
.analysis-run-panel__scopes button:focus-visible {
  color: var(--red);
  background: var(--hover);
}
.analysis-run-panel__empty {
  display: flex;
  min-height: 84px;
  align-items: center;
  gap: 9px;
  margin-top: 8px;
  padding: 12px;
  border: 1px dashed color-mix(in srgb, var(--borderPrimary) 86%, var(--blue));
  border-radius: 8px;
  color: var(--textSecondary);
  background: color-mix(in srgb, var(--surfacePrimary) 68%, transparent);
}
.analysis-run-panel__empty > .app-icon {
  flex: 0 0 auto;
  color: var(--blue);
}
.analysis-run-panel__empty-action {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  justify-content: center;
  margin-inline-start: auto;
  padding: 0 11px;
  border: 1px solid color-mix(in srgb, var(--blue) 26%, var(--borderPrimary));
  border-radius: 7px;
  color: var(--blue);
  background: var(--surfacePrimary);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  font-weight: 600;
}
.analysis-run-panel__empty-action:hover,
.analysis-run-panel__empty-action:focus-visible {
  border-color: color-mix(in srgb, var(--blue) 45%, var(--borderPrimary));
  background: color-mix(in srgb, var(--blue) 7%, var(--surfacePrimary));
}
.analysis-run-panel__summary {
  display: grid;
  align-content: start;
  gap: 12px;
}
.analysis-run-panel__summary-heading {
  justify-content: flex-start;
}
.analysis-run-panel__info {
  display: inline-grid;
  width: 26px;
  height: 26px;
  place-items: center;
  color: var(--textPrimary);
  border-radius: 50%;
}
.analysis-run-panel__info:hover,
.analysis-run-panel__info:focus-visible {
  color: var(--blue);
  background: var(--hover);
}
.analysis-run-panel__summary dl {
  display: grid;
  gap: 8px;
  margin: 0;
}
.analysis-run-panel__summary dl > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}
.analysis-run-panel__summary dt {
  color: var(--textPrimary);
  font-size: 12px;
}
.analysis-run-panel__summary dd {
  color: var(--textSecondary);
  font-weight: 600;
  text-align: right;
}
.analysis-run-panel__root-confirm {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px;
  border: 1px solid
    color-mix(in srgb, var(--icon-orange) 30%, var(--borderPrimary));
  border-radius: 8px;
  background: color-mix(in srgb, var(--icon-orange) 6%, transparent);
  font-size: 12px;
  line-height: 1.5;
}
.analysis-run-panel__root-confirm input {
  flex: 0 0 18px;
  width: 18px;
  height: 18px;
  margin: 2px 0 0;
}
.analysis-run-panel__root-confirm span {
  display: grid;
  gap: 2px;
}
.analysis-run-panel__root-confirm strong {
  color: var(--textSecondary);
}
.analysis-run-panel__footer {
  align-items: center;
  padding-top: 4px;
}
.analysis-run-panel__footer > span {
  max-width: 14rem;
}
.analysis-run-panel__start {
  flex: 0 0 auto;
  color: white;
  border-color: var(--blue);
  background: var(--blue);
}
.analysis-run-panel__start:hover {
  background: color-mix(in srgb, var(--blue) 88%, #000);
}
.analysis-run-panel button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.analysis-run-panel button:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}
@media (max-width: 760px) {
  .analysis-run-panel__body {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 560px) {
  .analysis-run-panel {
    padding: 12px;
  }
  .analysis-run-panel__footer {
    align-items: stretch;
    flex-direction: column;
  }
  .analysis-run-panel__footer > span {
    max-width: none;
  }
  .analysis-run-panel__start,
  .analysis-run-panel__browse {
    min-height: 44px;
  }
}
</style>
