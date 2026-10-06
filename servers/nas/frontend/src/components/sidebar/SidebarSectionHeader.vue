<template>
  <div
    class="sidebar-section-header sidebar-level-one"
    :class="toneClass"
    :data-expanded="expanded"
  >
    <button
      class="section-toggle"
      type="button"
      :aria-expanded="expanded"
      @click="$emit('toggle')"
    >
      <AppIcon :name="icon" :size="22" :stroke-width="1.9" />
      <span>{{ label }}</span>
    </button>
    <div class="section-tools">
      <slot name="tools"></slot>
      <button
        class="section-action-btn section-collapse-btn"
        type="button"
        :aria-label="expanded ? `收起${label}` : `展开${label}`"
        @click="$emit('toggle')"
      >
        <AppIcon
          class="section-arrow"
          name="chevron-right"
          :class="{ expanded }"
          :size="18"
          :stroke-width="2"
        />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";

const props = withDefaults(
  defineProps<{
    icon: AppIconName;
    label: string;
    expanded: boolean;
    tone?: "default" | "favorite";
  }>(),
  { tone: "default" }
);

defineEmits<{ toggle: [] }>();

const toneClass = computed(() => `sidebar-section-header--${props.tone}`);
</script>
