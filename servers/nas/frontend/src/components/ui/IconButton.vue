<template>
  <button
    type="button"
    class="icon-button"
    :class="[
      `icon-button--${size}`,
      `icon-button--${tone}`,
      { 'is-active': active },
    ]"
    :data-size="size"
    :data-tone="tone"
    :aria-label="label || undefined"
    :aria-pressed="pressed ?? (active ? true : undefined)"
    :title="label || undefined"
    :disabled="disabled"
    @click="onClick"
  >
    <AppIcon :name="icon" :size="iconSize" :stroke-width="1.9" />
    <slot></slot>
    <span v-if="hasCounter" class="icon-button__counter">{{ counter }}</span>
  </button>
</template>

<script setup lang="ts">
import { computed } from "vue";
import AppIcon from "./AppIcon.vue";
import type { AppIconName } from "./iconRegistry";

const props = withDefaults(
  defineProps<{
    label?: string;
    icon: AppIconName;
    size?: "sm" | "md" | "lg";
    iconSize?: number;
    tone?: "neutral" | "primary" | "danger";
    active?: boolean;
    pressed?: boolean;
    counter?: number | string;
    disabled?: boolean;
  }>(),
  {
    label: "",
    size: "md",
    iconSize: 20,
    tone: "neutral",
    active: false,
    counter: undefined,
    disabled: false,
  }
);

const emit = defineEmits<{
  (event: "click", payload: MouseEvent): void;
}>();

const hasCounter = computed(() => {
  if (typeof props.counter === "number") return props.counter > 0;
  return typeof props.counter === "string" && props.counter.length > 0;
});

const onClick = (event: MouseEvent) => {
  if (props.disabled) return;
  emit("click", event);
};
</script>
