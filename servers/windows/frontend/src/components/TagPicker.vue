<template>
  <div class="tag-picker" @click.stop>
    <div class="tag-picker-header">
      <span>分配标签</span>
      <div class="tag-picker-actions">
        <button
          class="tag-picker-manage"
          type="button"
          @click="openManager"
          aria-label="管理标签"
          title="管理标签"
        >
          <AppIcon name="settings" :size="18" />
        </button>
        <button
          class="tag-picker-close"
          type="button"
          @click="closePicker"
          aria-label="关闭"
          title="关闭"
        >
          <AppIcon name="x" :size="18" />
        </button>
      </div>
    </div>
    <div v-if="tagsStore.sortedTags.length === 0" class="tag-picker-empty">
      暂无标签，创建一个吧
    </div>
    <div v-else class="tag-picker-list">
      <button
        v-for="tag in tagsStore.sortedTags"
        :key="tag.id"
        class="tag-picker-item"
        :class="{ active: isAssigned(tag.id) }"
        type="button"
        :aria-pressed="isAssigned(tag.id)"
        @click="toggle(tag.id)"
      >
        <span class="tag-dot" :style="{ background: tag.color }"></span>
        <span class="tag-name">{{ tag.name }}</span>
        <AppIcon
          v-if="isAssigned(tag.id)"
          name="circle-check"
          class="tag-check"
          :size="16"
        />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import AppIcon from "@/components/ui/AppIcon.vue";
import { useTagsStore } from "@/stores/tags";
const props = defineProps<{
  path: string;
}>();

const emit = defineEmits<{
  manage: [];
  close: [];
}>();

const tagsStore = useTagsStore();

function isAssigned(tagId: string): boolean {
  const cleaned = props.path.replace(/\/+$/, "");
  const tag = tagsStore.tags.find((t) => t.id === tagId);
  return tag ? tag.paths.includes(cleaned) : false;
}

function toggle(tagId: string) {
  tagsStore.togglePathInTag(tagId, props.path);
}

function openManager() {
  emit("manage");
}

function closePicker() {
  emit("close");
}
</script>

<style scoped>
.tag-picker {
  box-sizing: border-box;
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--divider, rgba(0, 0, 0, 0.1));
  border-radius: 1rem;
  box-shadow: 0 1.5rem 3rem rgba(15, 23, 42, 0.2);
  width: min(24rem, calc(100vw - 2rem));
  max-height: min(32rem, calc(100dvh - 2rem));
  overflow-y: auto;
}

.tag-picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 3.5rem;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--divider, rgba(0, 0, 0, 0.06));
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--textSecondary, #666);
}

.tag-picker-manage {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--textSecondary, #888);
  min-width: 2.5rem;
  min-height: 2.5rem;
  padding: 0;
  border-radius: 0.25em;
  display: flex;
  align-items: center;
  justify-content: center;
}

.tag-picker-actions {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
}

.tag-picker-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 2.5rem;
  min-height: 2.5rem;
  padding: 0;
  color: var(--textSecondary, #888);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 0.5rem;
  cursor: pointer;
}

.tag-picker-close:hover {
  color: #dc2626;
  background: #fef2f2;
  border-color: #fecaca;
}

.tag-picker-manage:hover {
  background: var(--hover, rgba(0, 0, 0, 0.06));
}

.tag-picker-manage .app-icon {
  width: 1.25rem;
  height: 1.25rem;
}

.tag-picker-empty {
  padding: 1em;
  text-align: center;
  font-size: 0.75em;
  color: var(--textSecondary, #999);
}

.tag-picker-list {
  display: grid;
  gap: 0.25rem;
  padding: 0.625rem;
}

.tag-picker-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  width: 100%;
  min-height: 3rem;
  padding: 0.5rem 0.75rem;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 0.625rem;
  cursor: pointer;
  font-size: 0.9375rem;
  color: var(--textPrimary, #1a1a2e);
  text-align: left;
}

.tag-picker-item:hover {
  background: var(--hover, rgba(0, 0, 0, 0.04));
  border-color: var(--divider, rgba(0, 0, 0, 0.08));
}

.tag-picker-item.active {
  color: var(--blue, #1677ff);
  background: rgba(22, 119, 255, 0.08);
  border-color: rgba(22, 119, 255, 0.18);
}

.tag-dot {
  width: 0.75rem;
  height: 0.75rem;
  border-radius: 50%;
  flex-shrink: 0;
}

.tag-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-check {
  display: inline-grid;
  place-items: center;
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 50%;
  color: #fff;
  background: var(--blue, #1677ff);
}

/* Dark mode */
.dark .tag-picker {
  background: var(--surfacePrimary, #1e1e2e);
  border-color: var(--divider, rgba(255, 255, 255, 0.1));
}
</style>
