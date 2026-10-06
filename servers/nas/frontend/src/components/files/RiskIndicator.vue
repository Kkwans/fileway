<template>
  <span
    class="risk-inline-indicator"
    :class="`risk-inline-indicator--${level}`"
    role="img"
    :aria-label="label"
    :title="label"
  >
    <AppIcon :name="iconName" :size="13" :stroke-width="2" />
    <span>{{ label }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import type { RiskLevel } from "@/types/file";

const props = defineProps<{
  level: Exclude<RiskLevel, "low">;
}>();

const label = computed(() => (props.level === "high" ? "高风险" : "中风险"));
const iconName = computed<AppIconName>(() =>
  props.level === "high" ? "risk-high" : "risk-medium"
);
</script>
