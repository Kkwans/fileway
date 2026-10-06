<template>
  <span
    class="risk-resource-icon"
    :class="`risk-resource-icon--${level}`"
    role="img"
    :aria-label="label"
    :title="label"
  >
    <AppIcon :name="iconName" :size="32" :stroke-width="1.9" />
  </span>
</template>

<script setup lang="ts">
import { computed } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import type { RiskLevel } from "@/types/file";

const props = defineProps<{
  isDir: boolean;
  level: Exclude<RiskLevel, "low">;
}>();

const label = computed(() =>
  props.level === "high" ? "高风险资源" : "中风险维护资源"
);
const iconName = computed<AppIconName>(() => {
  if (props.level === "medium") {
    return props.isDir ? "folder-maintenance" : "file-maintenance";
  }
  return props.isDir ? "folder-protected" : "file-warning";
});
</script>
