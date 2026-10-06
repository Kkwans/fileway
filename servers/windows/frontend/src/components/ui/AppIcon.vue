<template>
  <component
    :is="appIcons[name]"
    :class="['app-icon', `app-icon--${name}`]"
    :size="size"
    :stroke-width="strokeWidth"
    :aria-hidden="decorative ? 'true' : undefined"
    :aria-label="decorative ? undefined : label"
    :role="decorative ? undefined : 'img'"
  />
</template>

<script setup lang="ts">
import { appIcons, type AppIconName } from "./iconRegistry";

withDefaults(
  defineProps<{
    name: AppIconName;
    size?: number;
    strokeWidth?: number;
    decorative?: boolean;
    label?: string;
  }>(),
  {
    size: 24,
    strokeWidth: 1.9,
    decorative: true,
    label: "",
  }
);
</script>
