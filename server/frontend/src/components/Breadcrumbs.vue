<template>
  <nav class="breadcrumbs" aria-label="当前位置">
    <div class="breadcrumb-ancestors">
      <component
        :is="element"
        :to="base || ''"
        class="breadcrumb-root"
        :aria-label="rootLabel || '首页'"
        :title="rootLabel || '首页'"
      >
        <AppIcon name="home" :size="18" />
        <span v-if="rootLabel" class="breadcrumb-root-label">{{
          rootLabel
        }}</span>
      </component>
      <span
        v-for="(link, index) in ancestors"
        :key="link.url"
        class="breadcrumb-item breadcrumb-ancestor"
        :class="{
          'breadcrumb-ancestor-middle':
            index > 0 && index < ancestors.length - 1,
        }"
      >
        <span class="chevron" aria-hidden="true">
          <AppIcon name="chevron-right" :size="16" />
        </span>
        <component
          :is="element"
          :to="link.url"
          class="breadcrumb-label"
          :title="link.name"
        >
          {{ link.name }}
        </component>
      </span>
    </div>
    <span v-if="current" class="breadcrumb-item breadcrumb-current">
      <span class="chevron" aria-hidden="true">
        <AppIcon name="chevron-right" :size="16" />
      </span>
      <component
        :is="element"
        :to="current.url"
        class="breadcrumb-label"
        :title="current.name"
        aria-current="location"
      >
        {{ current.name }}
      </component>
    </span>
  </nav>
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

const ancestors = computed(() => items.value.slice(0, -1));
const current = computed(() => items.value.at(-1));

const element = computed(() => {
  if (props.noLink) {
    return "span";
  }

  return "router-link";
});
</script>

<style scoped>
.breadcrumbs {
  position: sticky;
  top: calc(var(--app-header-height, 56px) + 10px);
  z-index: 800;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: auto;
  min-height: 46px;
  height: auto;
  margin: 10px 16px 4px;
  padding: 10px 12px;
  overflow: visible;
  border: 1px solid var(--borderPrimary, #e1e6ec);
  border-radius: 12px;
  background: var(--surfacePrimary, #fff);
  color: var(--textSecondary, #52606d);
  font-size: 14px;
  font-weight: 500;
  line-height: 24px;
}

.breadcrumb-ancestors {
  display: flex;
  align-items: center;
  flex: 0 1 auto;
  min-width: 0;
  max-width: 45%;
}

.breadcrumb-root {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 6px;
  padding: 0;
  color: inherit;
  line-height: 24px;
  white-space: nowrap;
}

.breadcrumb-root-label {
  display: block;
  color: inherit;
}

.breadcrumb-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.breadcrumb-ancestor {
  flex: 0 1 auto;
  max-width: 12rem;
  margin-inline-start: 8px;
}

.breadcrumb-ancestor:last-child {
  flex: 1 1 auto;
}

.breadcrumb-label {
  display: block;
  min-width: 0;
  padding: 0;
  color: inherit;
  line-height: 24px;
}

.breadcrumb-ancestor > .breadcrumb-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.breadcrumb-current {
  align-items: flex-start;
  flex: 1 1 0;
}

.breadcrumb-current > .breadcrumb-label {
  flex: 1 1 auto;
  overflow-wrap: anywhere;
  white-space: normal;
  color: var(--textPrimary);
}

.chevron {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  height: 24px;
  color: var(--textSecondary);
}

.breadcrumb-root:hover,
.breadcrumb-label:hover {
  color: var(--blue, #1677ff);
}

@media (max-width: 899px) {
  .breadcrumbs {
    top: calc(var(--app-mobile-header-height, 96px) + 8px);
    flex-direction: column;
    align-items: stretch;
    gap: 0;
    margin: 8px 10px 2px;
    padding: 0 12px 10px;
    border-radius: 10px;
  }

  .breadcrumb-ancestors {
    flex: 0 0 auto;
    max-width: none;
    font-size: 13px;
  }

  .breadcrumb-ancestor {
    max-width: none;
  }

  .breadcrumb-ancestor-middle {
    display: none;
  }

  .breadcrumb-ancestors .breadcrumb-label,
  .breadcrumb-root {
    display: flex;
    align-items: center;
    min-height: 44px;
  }

  .breadcrumb-ancestors .breadcrumb-label {
    display: block;
    line-height: 44px;
  }

  .breadcrumb-current {
    flex: 0 0 auto;
  }

  .breadcrumb-current > .chevron {
    display: none;
  }

  .breadcrumb-current > .breadcrumb-label {
    display: flex;
    align-items: center;
    min-height: 44px;
    line-height: 22px;
  }

  .breadcrumbs:has(.breadcrumb-ancestors:only-child) {
    padding-bottom: 0;
  }
}
</style>
