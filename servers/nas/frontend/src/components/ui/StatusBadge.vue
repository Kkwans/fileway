<template>
  <span class="status-badge" :class="`status-badge--${tone}`">
    <span v-if="dot" class="status-badge__dot" aria-hidden="true"></span>
    <slot>{{ label }}</slot>
  </span>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    label?: string;
    tone?: "neutral" | "info" | "success" | "warning" | "danger";
    dot?: boolean;
  }>(),
  { tone: "neutral", dot: false }
);
</script>
