<template>
  <div>
    <div class="path-container" ref="container">
      <template v-for="(item, index) in path" :key="index">
        /
        <span class="path-item">
          <span v-if="isDir === true || index < path.length - 1">
            <AppIcon name="folder" :size="14" :stroke-width="1.9" />
          </span>
          <span v-else>
            <AppIcon name="file" :size="14" :stroke-width="1.9" />
          </span>
          {{ item }}
        </span>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from "vue";
import { useRoute } from "vue-router";
import { useFileStore } from "@/stores/file";
import url from "@/utils/url";
import AppIcon from "@/components/ui/AppIcon.vue";

const fileStore = useFileStore();
const route = useRoute();

const props = defineProps({
  name: {
    type: String,
    required: true,
  },
  isDir: {
    type: Boolean,
    default: false,
  },
  path: {
    type: String,
    default: null,
  },
});

const container = ref<HTMLElement | null>(null);

const path = computed(() => {
  const routePath = props.path || route.path;
  let basePath = fileStore.isFiles ? routePath : url.removeLastDir(routePath);
  if (!basePath.endsWith("/")) {
    basePath += "/";
  }
  basePath += props.name;
  return basePath.split("/").filter(Boolean).splice(1);
});

watch(path, () => {
  nextTick(() => {
    const lastItem = container.value?.lastElementChild;
    lastItem?.scrollIntoView({ behavior: "auto", inline: "end" });
  });
});
</script>

<style scoped>
.path-container {
  display: flex;
  align-items: center;
  margin: 0.2em 0;
  gap: 0.25em;
  overflow-x: auto;
  max-width: 100%;
  scrollbar-width: none;
  opacity: 0.5;
}

.path-container::-webkit-scrollbar {
  display: none;
}

.path-item {
  display: flex;
  align-items: center;
  margin: 0.2em 0;
  gap: 0.25em;
  white-space: nowrap;
}

.path-item > span {
  display: inline-flex;
  align-items: center;
  font-size: 0.9em;
}
</style>
