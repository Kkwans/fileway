<template>
  <nav class="analysis-tool-switcher" aria-label="选择存储工具">
    <button
      v-for="tool in tools"
      :key="tool.id"
      type="button"
      :class="{ 'is-active': tool.id === activeTool }"
      :aria-pressed="tool.id === activeTool"
      @click="$emit('select', tool.id)"
    >
      <span class="analysis-tool-switcher__icon" aria-hidden="true">
        <AppIcon :name="tool.content.icon" :size="20" />
      </span>
      <span>
        <strong>{{ tool.content.label }}</strong>
      </span>
    </button>
  </nav>
</template>

<script setup lang="ts">
import AppIcon from "@/components/ui/AppIcon.vue";
import { analysisToolContent, type AnalysisTool } from "@/utils/analysisTools";

defineProps<{ activeTool: AnalysisTool }>();

defineEmits<{
  select: [tool: AnalysisTool];
}>();

const tools = (Object.keys(analysisToolContent) as AnalysisTool[]).map(
  (id) => ({
    id,
    content: analysisToolContent[id],
  })
);
</script>

<style scoped>
.analysis-tool-switcher {
  display: grid;
  width: fit-content;
  margin-inline: auto;
  max-width: 100%;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--borderPrimary);
  border-radius: 10px;
  background: var(--surfaceSecondary);
}
.analysis-tool-switcher button {
  display: inline-flex;
  min-width: 0;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: 7px;
  color: var(--textPrimary);
  background: transparent;
  cursor: pointer;
}
.analysis-tool-switcher button:hover {
  background: var(--hover);
}
.analysis-tool-switcher button:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: -2px;
}
.analysis-tool-switcher button.is-active {
  color: var(--blue);
  border-color: color-mix(in srgb, var(--blue) 22%, var(--borderPrimary));
  background: color-mix(in srgb, var(--blue) 10%, var(--surfacePrimary));
  box-shadow: 0 1px 3px color-mix(in srgb, var(--blue) 10%, transparent);
}
.analysis-tool-switcher__icon {
  display: flex;
  align-items: center;
}
.analysis-tool-switcher strong {
  font-size: 14px;
  font-weight: 600;
}
@media (max-width: 520px) {
  .analysis-tool-switcher {
    width: 100%;
  }
}
</style>
