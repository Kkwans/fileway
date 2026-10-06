<template>
  <div class="fav-group-picker" @click.stop>
    <div class="fav-group-picker-header">
      <span>{{ "收藏到分组" }}</span>
      <button
        class="fav-group-picker-manage"
        @click="createNew"
        title="新建分组"
      >
        <AppIcon name="plus" :size="16" />
      </button>
    </div>
    <!-- Quick add (no group) -->
    <button
      class="fav-group-picker-item"
      :class="{ active: currentGroupId === '' }"
      @click="assignTo('')"
    >
      <AppIcon name="star" :size="17" />
      <span class="fav-group-name">{{ "未分组" }}</span>
      <AppIcon
        v-if="currentGroupId === ''"
        name="circle-check"
        class="fav-group-check"
        :size="16"
      />
    </button>
    <!-- Existing groups -->
    <button
      v-for="group in favoritesStore.sortedGroups"
      :key="group.id"
      class="fav-group-picker-item"
      :class="{ active: currentGroupId === group.id }"
      @click="assignTo(group.id)"
    >
      <AppIcon
        name="folder"
        :size="17"
        :style="{ color: group.color || 'var(--blue)' }"
      />
      <span class="fav-group-name">{{ group.name }}</span>
      <AppIcon
        v-if="currentGroupId === group.id"
        name="circle-check"
        class="fav-group-check"
        :size="16"
      />
    </button>
    <!-- Create new group inline -->
    <div v-if="showCreate" class="fav-group-create">
      <input
        v-model="newGroupName"
        placeholder="分组名称"
        @keyup.enter="confirmCreate"
        @keyup.escape="cancelCreate"
        ref="createInput"
      />
      <button @click="confirmCreate" :disabled="!newGroupName.trim()">
        <AppIcon name="circle-check" :size="16" />
      </button>
      <button @click="cancelCreate">
        <AppIcon name="x" :size="16" />
      </button>
    </div>
    <!-- Remove from favorites -->
    <button
      v-if="isFavorited"
      class="fav-group-picker-item fav-group-remove"
      @click="remove"
    >
      <AppIcon name="star-off" :size="17" />
      <span class="fav-group-name">{{ "取消收藏" }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import { useFavoritesStore } from "@/stores/favorites";

const props = defineProps<{
  path: string;
  name: string;
}>();

const emit = defineEmits<{
  close: [];
}>();

const favoritesStore = useFavoritesStore();
const showCreate = ref(false);
const newGroupName = ref("");
const createInput = ref<HTMLInputElement | null>(null);

const cleaned = computed(() => props.path.replace(/\/+$/, ""));

const isFavorited = computed(() => favoritesStore.isFavorite(cleaned.value));

const currentGroupId = computed(() => {
  const fav = favoritesStore.favorites.find((f) => f.path === cleaned.value);
  return fav ? fav.groupId || "" : "";
});

function assignTo(groupId: string) {
  if (isFavorited.value) {
    // Move existing favorite to group
    const fav = favoritesStore.favorites.find((f) => f.path === cleaned.value);
    if (fav) {
      favoritesStore.moveFavoriteToGroup(fav.id, groupId);
    }
  } else {
    // Add new favorite in group
    favoritesStore.addFavorite(cleaned.value, props.name, groupId);
  }
  emit("close");
}

function remove() {
  favoritesStore.removeByPath(cleaned.value);
  emit("close");
}

function createNew() {
  showCreate.value = true;
  nextTick(() => createInput.value?.focus());
}

function cancelCreate() {
  showCreate.value = false;
  newGroupName.value = "";
}

async function confirmCreate() {
  const name = newGroupName.value.trim();
  if (!name) return;
  await favoritesStore.addGroup(name);
  showCreate.value = false;
  newGroupName.value = "";
}
</script>

<style scoped>
.fav-group-picker {
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--divider, rgba(0, 0, 0, 0.1));
  border-radius: 0.5em;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  min-width: 13em;
  max-height: 18em;
  overflow-y: auto;
}

.fav-group-picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.5em 0.75em;
  border-bottom: 1px solid var(--divider, rgba(0, 0, 0, 0.06));
  font-size: 0.75em;
  font-weight: 600;
  color: var(--textSecondary, #666);
}

.fav-group-picker-manage {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--textSecondary, #888);
  padding: 0.125em;
  border-radius: 0.25em;
  display: flex;
}

.fav-group-picker-manage:hover {
  background: var(--hover, rgba(0, 0, 0, 0.06));
}

.fav-group-picker-manage .app-icon {
  width: 0.875rem;
  height: 0.875rem;
}

.fav-group-picker-item {
  display: flex;
  align-items: center;
  gap: 0.5em;
  width: 100%;
  padding: 0.375em 0.75em;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 0.8125em;
  color: var(--textPrimary, #1a1a2e);
  text-align: left;
}

.fav-group-picker-item:hover {
  background: var(--hover, rgba(0, 0, 0, 0.04));
}

.fav-group-picker-item.active {
  background: rgba(255, 193, 7, 0.08);
}

.fav-group-picker-item .app-icon {
  width: 1rem;
  height: 1rem;
  color: var(--textSecondary, #888);
}

.fav-group-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fav-group-check {
  width: 1rem !important;
  height: 1rem !important;
  color: var(--blue, #2196f3) !important;
}

.fav-group-remove {
  border-top: 1px solid var(--divider, rgba(0, 0, 0, 0.06));
  color: var(--icon-red, #da4453);
}

.fav-group-remove .app-icon {
  color: var(--icon-red, #da4453) !important;
}

.fav-group-create {
  display: flex;
  align-items: center;
  gap: 0.25em;
  padding: 0.375em 0.5em;
  border-top: 1px solid var(--divider, rgba(0, 0, 0, 0.06));
}

.fav-group-create input {
  flex: 1;
  border: 1px solid var(--divider, rgba(0, 0, 0, 0.15));
  border-radius: 0.25em;
  padding: 0.25em 0.5em;
  font-size: 0.75em;
  background: var(--surfacePrimary, #fff);
  color: var(--textPrimary, #1a1a2e);
  outline: none;
}

.fav-group-create input:focus {
  border-color: var(--blue, #2196f3);
}

.fav-group-create button {
  background: none;
  border: none;
  cursor: pointer;
  padding: 0.125em;
  border-radius: 0.25em;
  display: flex;
  color: var(--textSecondary, #888);
}

.fav-group-create button:hover {
  background: var(--hover, rgba(0, 0, 0, 0.06));
}

.fav-group-create button:disabled {
  opacity: 0.4;
  cursor: default;
}

.fav-group-create button .app-icon {
  width: 0.875rem;
  height: 0.875rem;
}

/* Dark mode */
.dark .fav-group-picker {
  background: var(--surfacePrimary, #1e1e2e);
  border-color: var(--divider, rgba(255, 255, 255, 0.1));
}

.dark .fav-group-create input {
  background: var(--surfaceSecondary, #2a2a3e);
  border-color: var(--divider, rgba(255, 255, 255, 0.15));
  color: var(--textPrimary, #e0e0e0);
}
</style>
