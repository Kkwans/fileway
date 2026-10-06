<template>
  <div class="breadcrumbs">
    <component
      :is="element"
      :to="base || ''"
      class="breadcrumb-root"
      :aria-label="rootLabel || '首页'"
      :title="rootLabel || '首页'"
    >
      <AppIcon name="home" :size="20" />
      <span v-if="rootLabel" class="breadcrumb-root-label">
        {{ rootLabel }}
      </span>
    </component>

    <span v-for="(link, index) in items" :key="index">
      <span class="chevron"><AppIcon name="chevron-right" :size="18" /></span>
      <component :is="element" :to="link.url">{{ link.name }}</component>
    </span>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import type { BreadCrumb } from "@/types/file";
import AppIcon from "@/components/ui/AppIcon.vue";
const route = useRoute();

const props = defineProps<{
  base: string;
  noLink?: boolean;
  rootLabel?: string;
}>();

const rootLabel = computed(() => props.rootLabel?.trim() || "");

const decodeSegment = (segment: string) => {
  try {
    return decodeURIComponent(segment);
  } catch {
    // 文件名可能包含不完整的旧编码，显示原文比让面包屑渲染失败更安全。
    return segment;
  }
};

const items = computed(() => {
  const relativePath = route.path.replace(props.base, "");
  const parts = relativePath.split("/");

  if (parts[0] === "") {
    parts.shift();
  }

  if (parts[parts.length - 1] === "") {
    parts.pop();
  }

  const breadcrumbs: BreadCrumb[] = [];

  for (let i = 0; i < parts.length; i++) {
    if (i === 0) {
      breadcrumbs.push({
        name: decodeSegment(parts[i]),
        url: props.base + "/" + parts[i] + "/",
      });
    } else {
      breadcrumbs.push({
        name: decodeSegment(parts[i]),
        url: breadcrumbs[i - 1].url + parts[i] + "/",
      });
    }
  }

  if (breadcrumbs.length > 3) {
    while (breadcrumbs.length !== 4) {
      breadcrumbs.shift();
    }

    breadcrumbs[0].name = "...";
  }

  return breadcrumbs;
});

const element = computed(() => {
  if (props.noLink) {
    return "span";
  }

  return "router-link";
});
</script>
