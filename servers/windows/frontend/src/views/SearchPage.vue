<template>
  <div id="search-page">
    <header-bar show-menu show-logo title="搜索" title-icon="search" />

    <main class="search-page-content">
      <form v-if="!tagMode" class="search-page-input" @submit.prevent="submit">
        <input
          ref="inputRef"
          v-model.trim="prompt"
          type="search"
          placeholder="搜索文件"
          aria-label="搜索文件"
          autofocus
          @keyup.escape="clearSearch"
        />
        <div class="search-page-actions">
          <button
            v-if="prompt"
            type="button"
            title="清空搜索内容"
            aria-label="清空搜索内容"
            @click="clearSearch"
          >
            <AppIcon name="x" :size="18" />
          </button>
          <button
            :type="ongoing ? 'button' : 'submit'"
            :title="ongoing ? '取消搜索' : '开始搜索'"
            :aria-label="ongoing ? '取消搜索' : '开始搜索'"
            :disabled="!prompt"
            @click="ongoing ? stopSearch() : undefined"
          >
            <AppIcon :name="ongoing ? 'circle-stop' : 'search'" :size="18" />
          </button>
        </div>
        <span v-if="searchResults.length" class="search-result-count"
          >{{ searchResults.length }} 项</span
        >
      </form>

      <result-explorer
        v-if="tagMode"
        kind="tag"
        :scope="tagSearchScope"
        :title="activeTag?.name || '标签'"
        :results="tagResults"
        :loading="tagLoading"
        :error="tagError"
        :base-path="currentBasePath"
        :return-route="returnFileRoute"
        :show-return="false"
        :icon-color="activeTag?.color"
        @scope-change="setTagSearchScope"
        @retry="loadTagResults"
        @return="prepareTagExit"
        @action="handleResultAction"
      />

      <template v-else>
        <div class="search-shortcuts">
          <span>按文件类型快速搜索</span>
          <button
            type="button"
            :class="{ active: activeType === null }"
            @click="selectSearchType(null)"
          >
            <AppIcon name="categories" :size="16" />
            全部
          </button>
          <button
            v-for="(item, type) in SEARCH_TYPE_OPTIONS"
            :key="type"
            type="button"
            :class="{ active: activeType === type }"
            @click="selectSearchType(type)"
          >
            <AppIcon :name="item.icon" :size="16" />
            {{ item.label }}
          </button>
        </div>
        <result-explorer
          kind="search"
          :scope="fileSearchScope"
          :title="searchTitle"
          :results="searchResults"
          :loading="ongoing"
          :termination="searchTermination"
          :error="searchError"
          :base-path="searchBase"
          :return-route="returnFileRoute"
          :show-return="false"
          @scope-change="setSearchScope"
          @retry="submit"
          @action="handleResultAction"
        />
      </template>
    </main>
  </div>
</template>

<script setup lang="ts">
import {
  computed,
  inject,
  nextTick,
  onMounted,
  onUnmounted,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import HeaderBar from "@/components/header/HeaderBar.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import ResultExplorer, {
  type ExplorerResult,
  type ExplorerResultAction,
  type ExplorerScope,
} from "@/components/search/ResultExplorer.vue";
import type { SearchResult } from "@/types/file";
import { files as filesApi, search } from "@/api";
import type { SearchTermination } from "@/api/search";
import { StatusError } from "@/api/utils";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import { type Tag, useTagsStore } from "@/stores/tags";
import {
  buildFilesRouteFromSearchBase,
  buildTagSearchQuery,
  getSearchPromptFromRoute,
  normalizeSearchBase,
} from "@/utils/searchPath";
import {
  buildResultParentRoute,
  buildTaggedPathUrl,
  getTaggedPathName,
} from "@/utils/tagResults";
import {
  applySearchType,
  detectSearchType,
  filterSearchResults,
  SEARCH_TYPE_OPTIONS,
  type SearchType,
} from "@/utils/searchFilters";

const router = useRouter();
const route = useRoute();
const fileStore = useFileStore();
const layoutStore = useLayoutStore();
const tagsStore = useTagsStore();
const $showError = inject<IToastError>("$showError")!;

const prompt = ref("");
const ongoing = ref(false);
const results = ref<SearchResult[]>([]);
const searchTermination = ref<SearchTermination | null>(null);
const searchError = ref("");
const activeType = ref<SearchType | null>(null);
const submittedType = ref<SearchType | null>(null);
const inputRef = ref<HTMLInputElement | null>(null);
const tagLoading = ref(false);
const tagError = ref("");
const tagResults = ref<ExplorerResult[]>([]);
const tagId = computed(() =>
  typeof route.query.tag === "string" ? route.query.tag : ""
);
const tagMode = computed(() => tagId.value.length > 0);
const activeTag = computed<Tag | null>(
  () => tagsStore.tags.find((tag) => tag.id === tagId.value) ?? null
);
const tagSearchScope = ref<"current" | "global">(
  route.query.scope === "global" ? "global" : "current"
);
const fileSearchScope = ref<"current" | "recursive">(
  route.query.scope === "recursive" || route.query.scope === "global"
    ? "recursive"
    : "current"
);
const currentBasePath = ref(
  normalizeSearchBase(
    typeof route.query.base === "string"
      ? route.query.base
      : fileStore.req?.path || "/"
  )
);
let searchAbortController = new AbortController();
let searchGeneration = 0;
let tagLoadGeneration = 0;
let tagLoadAbortController = new AbortController();

const searchBase = computed(() => currentBasePath.value);
const returnFileRoute = computed(() =>
  buildFilesRouteFromSearchBase(
    typeof route.query.base === "string"
      ? route.query.base
      : currentBasePath.value
  )
);
const visibleResults = computed(() =>
  submittedType.value === null
    ? filterSearchResults(results.value, activeType.value)
    : results.value
);
const searchResults = computed<ExplorerResult[]>(() =>
  visibleResults.value.map((item) => ({
    ...item,
    url: item.url ?? buildTaggedPathUrl(item.path, item.dir),
  }))
);
const searchTitle = computed(
  () => applySearchType(prompt.value, null) || "搜索文件"
);

async function loadTagResults() {
  if (!tagMode.value) return;
  const generation = ++tagLoadGeneration;
  tagLoadAbortController.abort();
  tagLoadAbortController = new AbortController();
  const signal = tagLoadAbortController.signal;
  tagLoading.value = true;
  tagError.value = "";
  try {
    if (!tagsStore.loaded) await tagsStore.loadTags();
    const tag = activeTag.value;
    if (!tag) {
      tagResults.value = [];
      return;
    }
    const base =
      normalizeSearchBase(currentBasePath.value).replace(/\/$/, "") || "/";
    const paths = tag.paths.filter((path) => {
      if (tagSearchScope.value === "global" || base === "/") return true;
      const normalized = normalizeSearchBase(path).replace(/\/$/, "");
      return normalized === base || normalized.startsWith(`${base}/`);
    });
    tagResults.value = [];
    for (let offset = 0; offset < paths.length; offset += 100) {
      const batchPaths = paths.slice(offset, offset + 100);
      const batch = await filesApi.fetchBatch(batchPaths, signal);
      if (generation !== tagLoadGeneration) return;
      tagResults.value.push(
        ...batch.map((result): ExplorerResult => {
          if (!result.item) {
            return {
              path: result.path,
              name: getTaggedPathName(result.path),
              dir: false,
              size: null,
              modified: null,
              url: "",
              status: result.status,
              error: result.error || "无法读取资源元数据",
            };
          }
          return {
            path: result.item.path,
            name: result.item.name || getTaggedPathName(result.item.path),
            dir: result.item.isDir,
            size: result.item.size,
            modified: result.item.modified,
            url: result.item.url,
          };
        })
      );
    }
  } catch (error) {
    if (generation === tagLoadGeneration && !signal.aborted) {
      tagError.value = getErrorMessage(error);
    }
  } finally {
    if (generation === tagLoadGeneration) tagLoading.value = false;
  }
}

async function setTagSearchScope(scope: ExplorerScope) {
  if (scope === "recursive" || tagSearchScope.value === scope) return;
  tagSearchScope.value = scope;
  await router.replace({
    query: {
      ...route.query,
      ...buildTagSearchQuery(currentBasePath.value, scope),
    },
  });
  await loadTagResults();
}

async function setSearchScope(scope: ExplorerScope) {
  if (scope === "global" || fileSearchScope.value === scope) return;
  fileSearchScope.value = scope;
  await router.replace({
    path: "/search",
    query: {
      ...(prompt.value ? { q: prompt.value } : {}),
      base: currentBasePath.value,
      scope,
    },
  });
  if (prompt.value) await submit();
}

function prepareTagExit() {
  tagsStore.setFilter(null);
  tagLoadGeneration++;
  tagLoadAbortController.abort();
}

function clearSearch() {
  searchGeneration++;
  searchAbortController.abort();
  ongoing.value = false;
  prompt.value = "";
  results.value = [];
  activeType.value = null;
  submittedType.value = null;
  searchTermination.value = null;
  searchError.value = "";
  nextTick(() => inputRef.value?.focus());
}

function stopSearch() {
  searchAbortController.abort();
  ongoing.value = false;
  searchTermination.value = {
    reason: "canceled",
    count: results.value.length,
  };
}

async function selectSearchType(type: SearchType | null) {
  const nextType = activeType.value === type ? null : type;
  if (results.value.length > 0 && submittedType.value === null) {
    activeType.value = nextType;
    return;
  }

  activeType.value = nextType;
  prompt.value = applySearchType(prompt.value, nextType);
  if (submittedType.value !== null) {
    if (prompt.value) await submit();
    else clearSearch();
    return;
  }
  nextTick(() => inputRef.value?.focus());
}

async function submit() {
  if (!prompt.value) return;
  const generation = ++searchGeneration;
  const requestPrompt = prompt.value;
  const requestScope = fileSearchScope.value;
  const requestBase = searchBase.value;
  submittedType.value = detectSearchType(requestPrompt);
  activeType.value = submittedType.value;
  searchAbortController.abort();
  ongoing.value = false;
  try {
    await router.replace({
      path: "/search",
      query: {
        q: requestPrompt,
        scope: requestScope,
        base: currentBasePath.value,
      },
    });
  } catch (error) {
    if (generation === searchGeneration) {
      searchError.value = getErrorMessage(error);
      $showError(error as Error);
    }
    return;
  }
  if (generation !== searchGeneration) return;

  ongoing.value = true;
  const controller = new AbortController();
  searchAbortController = controller;
  results.value = [];
  searchTermination.value = null;
  searchError.value = "";
  const pendingResults: SearchResult[] = [];
  let flushTimer: number | null = null;
  const flushResults = () => {
    if (flushTimer !== null) window.clearTimeout(flushTimer);
    flushTimer = null;
    if (generation !== searchGeneration) {
      pendingResults.length = 0;
      return;
    }
    if (pendingResults.length) results.value.push(...pendingResults.splice(0));
  };
  const enqueueResult = (item: SearchResult) => {
    pendingResults.push(item);
    if (pendingResults.length >= 50) flushResults();
    else if (flushTimer === null)
      flushTimer = window.setTimeout(flushResults, 32);
  };
  try {
    const termination = await search(
      requestBase,
      requestPrompt,
      requestScope,
      controller.signal,
      enqueueResult
    );
    if (generation !== searchGeneration) return;
    searchTermination.value = termination;
    if (termination.reason === "error") {
      searchError.value = termination.error || "搜索过程中发生未知错误";
    }
  } catch (error: any) {
    if (
      generation === searchGeneration &&
      !(error instanceof StatusError && error.is_canceled)
    ) {
      searchError.value = getErrorMessage(error);
      $showError(error);
    }
  } finally {
    flushResults();
    if (generation === searchGeneration && searchAbortController === controller)
      ongoing.value = false;
  }
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function refreshActiveResults() {
  if (tagMode.value) loadTagResults();
  else if (prompt.value) submit();
}

async function handleResultAction(
  action: ExplorerResultAction,
  result: ExplorerResult
) {
  if (action === "open-location") {
    prepareTagExit();
    fileStore.preselect = result.path;
    await router.push(buildResultParentRoute(result.path));
    return;
  }
  if (action === "download") {
    filesApi.download(null, result.url);
    return;
  }
  layoutStore.showHover({
    prompt: "result-action",
    props: { mode: action, result },
    action: refreshActiveResults,
  });
}

onMounted(() => {
  prompt.value = getSearchPromptFromRoute(route.query.q, route.query.tag);
  activeType.value = detectSearchType(prompt.value);
  if (tagMode.value) loadTagResults();
  else {
    inputRef.value?.focus();
    if (prompt.value) nextTick(submit);
  }
});

watch(tagId, () => {
  searchAbortController.abort();
  ongoing.value = false;
  results.value = [];
  prompt.value = getSearchPromptFromRoute(route.query.q, route.query.tag);
  activeType.value = detectSearchType(prompt.value);
  submittedType.value = null;
  if (tagMode.value) {
    tagSearchScope.value =
      route.query.scope === "global" ? "global" : "current";
    loadTagResults();
  } else {
    fileSearchScope.value =
      route.query.scope === "recursive" || route.query.scope === "global"
        ? "recursive"
        : "current";
  }
});

watch(
  () => route.query.scope,
  (scope) => {
    if (tagMode.value) {
      const nextScope = scope === "global" ? "global" : "current";
      const changed = tagSearchScope.value !== nextScope;
      tagSearchScope.value = nextScope;
      if (changed) void loadTagResults();
      return;
    }
    const nextScope =
      scope === "recursive" || scope === "global" ? "recursive" : "current";
    const changed = fileSearchScope.value !== nextScope;
    fileSearchScope.value = nextScope;
    if (changed && prompt.value) void submit();
  }
);

onUnmounted(() => {
  searchGeneration++;
  searchAbortController.abort();
  tagLoadGeneration++;
  tagLoadAbortController.abort();
});
</script>

<style scoped>
#search-page {
  display: flex;
  height: 100%;
  flex-direction: column;
}

.search-page-input {
  position: relative;
  display: flex;
  align-items: center;
  width: min(46rem, 100%);
  height: 2.5rem;
  margin: 0 auto;
  padding: 0 0.3125rem 0 0.875rem;
  background: var(--surfaceSecondary, #f6f8fb);
  border: 1px solid var(--borderPrimary, #dbe2ea);
  border-radius: 0.625rem;
}

.search-page-input:focus-within {
  background: var(--surfacePrimary, #fff);
  border-color: rgba(22, 119, 255, 0.55);
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.12);
}

.search-page-input input {
  min-width: 0;
  height: 100%;
  flex: 1;
  padding: 0;
  color: var(--textPrimary, #1e293b);
  background: transparent;
  border: 0;
  outline: 0;
  font: inherit;
  text-align: center;
}

.search-page-input input:focus,
.search-page-input input:not(:placeholder-shown) {
  text-align: left;
}

.search-page-actions {
  display: inline-flex;
  gap: 0.125rem;
}

.search-page-actions button {
  display: inline-grid;
  width: 2.5rem;
  height: 2.5rem;
  place-items: center;
  padding: 0;
  color: var(--textSecondary, #64748b);
  background: transparent;
  border: 0;
  border-radius: 0.5rem;
  cursor: pointer;
}

.search-page-actions button:last-child {
  color: var(--blue, #1677ff);
}

.search-page-actions button:hover:not(:disabled) {
  background: rgba(22, 119, 255, 0.08);
}

.search-page-actions button:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.search-page-actions .app-icon {
  width: 1.125rem;
  height: 1.125rem;
}

.search-result-count {
  position: absolute;
  left: calc(100% + 0.75rem);
  padding: 0.25rem 0.625rem;
  color: var(--textSecondary, #64748b);
  background: var(--surfaceSecondary, #f1f5f9);
  border-radius: 999px;
  font-size: 0.75rem;
  white-space: nowrap;
}

.search-page-content {
  flex: 1;
  width: 100%;
  margin: 0;
  padding: 1.5rem;
  overflow-y: auto;
}

.search-shortcuts {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  width: min(76rem, 100%);
  gap: 0.5rem;
  margin: 0 auto 1rem;
  color: var(--textSecondary, #64748b);
  font-size: 0.8125rem;
}

.search-shortcuts button {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  width: auto;
  min-width: max-content;
  min-height: 2.5rem;
  gap: 0.25rem;
  padding: 0 0.625rem;
  color: var(--textSecondary, #475569);
  background: var(--surfacePrimary, #fff);
  border: 1px solid var(--borderPrimary, #e2e8f0);
  border-radius: 0.5rem;
  cursor: pointer;
}

.search-shortcuts button:hover {
  color: var(--blue, #1677ff);
  border-color: rgba(22, 119, 255, 0.35);
}

.search-shortcuts button.active {
  color: var(--blue, #1677ff);
  background: rgba(22, 119, 255, 0.08);
  border-color: rgba(22, 119, 255, 0.35);
}

.search-shortcuts .app-icon {
  display: inline-grid;
  width: 1rem;
  height: 1rem;
  place-items: center;
}

@media (max-width: 736px) {
  .search-page-input {
    height: 2.875rem;
  }

  .search-page-input input {
    height: 2.75rem;
  }

  .search-page-actions button {
    width: 2.75rem;
    height: 2.75rem;
  }

  .search-page-content {
    padding: 0.75rem;
  }

  .search-result-count {
    display: none;
  }

  .search-shortcuts {
    align-content: flex-start;
  }

  .search-shortcuts > span {
    display: none;
  }

  .search-shortcuts button {
    flex: 0 0 auto;
    min-height: 2.75rem;
  }
}
</style>
