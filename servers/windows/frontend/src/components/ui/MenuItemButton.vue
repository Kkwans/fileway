<template>
  <button
    type="button"
    :id="id"
    class="menu-item-button"
    :class="[`menu-item-button--${tone}`, { 'is-active': active }]"
    :aria-label="label"
    role="menuitem"
    :aria-checked="checked === undefined ? undefined : checked"
    :disabled="disabled"
    @click="onClick"
  >
    <AppIcon :name="icon" :size="iconSize" :stroke-width="1.9" />
    <span class="menu-item-button__label">{{ label }}</span>
    <AppIcon
      v-if="checked"
      class="menu-item-button__check"
      name="circle-check"
      :size="18"
      :stroke-width="1.9"
      aria-hidden="true"
    />
    <slot />
  </button>
</template>

<script setup lang="ts">
import AppIcon from "./AppIcon.vue";
import type { AppIconName } from "./iconRegistry";

withDefaults(
  defineProps<{
    icon: AppIconName;
    label: string;
    id?: string;
    iconSize?: number;
    tone?: "neutral" | "primary" | "danger";
    active?: boolean;
    checked?: boolean;
    disabled?: boolean;
  }>(),
  {
    iconSize: 19,
    tone: "neutral",
    active: false,
    checked: undefined,
    disabled: false,
  }
);

const emit = defineEmits<{
  (event: "click", payload: MouseEvent): void;
}>();

const onClick = (event: MouseEvent) => emit("click", event);
</script>
