<template>
  <select v-on:change="change" :value="theme">
    <option value="">默认</option>
    <option value="light">亮色</option>
    <option value="dark">暗色</option>
  </select>
</template>

<script setup lang="ts">
import type { SelectHTMLAttributes } from "vue";
import type { UserTheme } from "@/types/user";
defineProps<{
  theme: UserTheme;
}>();

const emit = defineEmits<{
  (e: "update:theme", val: string | null): void;
}>();

const change = (event: Event) => {
  emit("update:theme", (event.target as SelectHTMLAttributes)?.value);
};
</script>
