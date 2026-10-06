<template>
  <div class="page-title">
    <AppIcon :name="icon" :size="22" />
    <h1>{{ title }}</h1>
  </div>
</template>

<script setup lang="ts">
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";

defineProps<{ title: string; icon: AppIconName }>();
</script>

<style scoped>
.page-title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  color: var(--color-text);
}
.page-title > .app-icon {
  flex: 0 0 auto;
  color: var(--color-accent);
}
.page-title > h1 {
  margin: 0;
  overflow: hidden;
  color: inherit;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
