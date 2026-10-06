<template>
  <button
    class="toolbar-button"
    :class="[`toolbar-button--${tone}`, { 'is-active': active }]"
    type="button"
    :disabled="disabled"
    :aria-pressed="active || undefined"
    :aria-label="label"
    :title="label"
    @click="$emit('click', $event)"
  >
    <AppIcon :name="icon" :size="iconSize" :stroke-width="1.9" />
    <span>{{ label }}</span>
    <slot name="trailing" />
  </button>
</template>

<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import type { AppIconName } from "./iconRegistry";

withDefaults(
  defineProps<{
    icon: AppIconName;
    label: string;
    iconSize?: number;
    tone?: "neutral" | "primary" | "danger";
    active?: boolean;
    disabled?: boolean;
  }>(),
  {
    iconSize: 19,
    tone: "neutral",
    active: false,
    disabled: false,
  }
);

defineEmits<{
  (event: "click", payload: MouseEvent): void;
}>();
</script>
