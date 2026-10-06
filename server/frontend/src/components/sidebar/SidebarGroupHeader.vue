<template>
  <div class="sidebar-group-header sidebar-level-two" :data-expanded="expanded">
    <button
      class="sidebar-group-toggle"
      type="button"
      :aria-expanded="expanded"
      :aria-label="expanded ? `收起${label}` : `展开${label}`"
      @click="$emit('toggle')"
    ></button>
    <AppIcon
      class="favorite-group-icon"
      :name="appIcon ?? resolveCategoryIcon(icon)"
      :size="18"
      :stroke-width="1.9"
      :style="{ color }"
    />
    <span class="group-name">{{ label }}</span>
    <span class="sidebar-group-actions">
      <slot name="actions"></slot>
    </span>
    <AppIcon
      class="category-arrow"
      name="chevron-right"
      :class="{ expanded }"
      :size="18"
      :stroke-width="2"
    />
  </div>
</template>

<script setup lang="ts">
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import { resolveCategoryIcon } from "@/utils/sidebarIconSemantics";

withDefaults(
  defineProps<{
    icon: string;
    appIcon?: AppIconName;
    label: string;
    expanded: boolean;
    color?: string;
  }>(),
  { color: "var(--blue, #1677ff)" }
);

defineEmits<{ toggle: [] }>();
</script>
