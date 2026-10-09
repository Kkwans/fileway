import { defineStore } from "pinia";
import { computed, reactive, toRefs, watch } from "vue";
import type { FileKey, Resource, ResourceItem } from "@/types/file";
import { useAuthStore } from "./auth";
import { useLayoutStore } from "./layout";
import {
  fileResourceIdentity,
  fileSelectionScope,
  normalizeFileKey,
  type ListingResourceRef,
} from "@/utils/fileListing";

export const useFileStore = defineStore("file", () => {
  const auth = useAuthStore();
  const layout = useLayoutStore();
  const scope = computed(() => fileSelectionScope(auth.user));
  const initial = () => ({
    req: null as Resource | null,
    oldReq: null as Resource | null,
    reload: false,
    selected: [] as FileKey[],
    focused: null as FileKey | null,
    rangeAnchor: null as FileKey | null,
    multiple: false,
    isFiles: false,
    preselect: null as string | ListingResourceRef | null,
    preselectScope: null as string | null,
  });
  const state = reactive(initial());
  function $reset() {
    Object.assign(state, initial());
  }
  watch(
    scope,
    () => {
      const isFiles = state.isFiles;
      $reset();
      state.isFiles = isFiles;
      layout.prompts = [];
    },
    { flush: "sync" }
  );

  function keyFor(
    item: ListingResourceRef,
    sourceScope = scope.value
  ): FileKey {
    const directory = state.req ? fileResourceIdentity(state.req) : null;
    const resource = fileResourceIdentity(item);
    return sourceScope === scope.value &&
      directory !== null &&
      resource !== null
      ? JSON.stringify([scope.value, directory, resource])
      : "";
  }
  const availableItems = computed(
    () =>
      new Map(
        (state.req?.items ?? [])
          .map((item) => [keyFor(item), item])
          .filter(([key]) => !!key) as [FileKey, ResourceItem][]
      )
  );
  function resolveKey(key: FileKey): FileKey | null {
    if (!key || !state.req?.items) return null;
    if (availableItems.value.has(key)) return key;
    // Old display callers require a unique strict UTF8 match.
    if (!key.startsWith("/")) return null;
    const path = normalizeFileKey(key);
    const matches = state.req.items.filter(
      (item) => normalizeFileKey(item.path) === path
    );
    const identity = fileResourceIdentity({ path });
    return matches.length === 1 &&
      identity !== null &&
      fileResourceIdentity(matches[0]) === identity
      ? keyFor(matches[0]) || null
      : null;
  }
  const selectedItems = computed(() => {
    const selected = new Set(state.selected),
      seen = new Set<string>();
    return (state.req?.items ?? []).filter((item) => {
      const key = keyFor(item);
      if (!key || !selected.has(key) || seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  });
  const selectedCount = computed(() => selectedItems.value.length);
  const isListing = computed(() => state.isFiles && !!state.req?.isDir);
  function clearSelection() {
    state.selected = [];
    state.focused = null;
    state.rangeAnchor = null;
    state.multiple = false;
  }
  function updateRequest(
    value: Resource | null,
    sourceScope = scope.value
  ): boolean {
    if (sourceScope !== scope.value) return false;
    const previous = state.req && fileResourceIdentity(state.req);
    state.oldReq = state.req;
    state.req = value;
    const next = value && fileResourceIdentity(value);
    if (previous === null || previous !== next || !value?.items)
      clearSelection();
    else {
      const available = new Set(
        value.items.map((item) => keyFor(item)).filter(Boolean)
      );
      state.selected = state.selected.filter((key) => available.has(key));
      if (state.focused && !available.has(state.focused)) state.focused = null;
      if (state.rangeAnchor && !available.has(state.rangeAnchor))
        state.rangeAnchor = state.focused;
    }
    return true;
  }
  function itemForKey(key: FileKey): ResourceItem | undefined {
    const resolved = resolveKey(key);
    return resolved ? availableItems.value.get(resolved) : undefined;
  }
  function selectOnly(key: FileKey) {
    const resolved = resolveKey(key);
    state.selected = resolved ? [resolved] : [];
    state.focused = resolved;
    state.rangeAnchor = resolved;
  }
  function addSelected(key: FileKey, updateAnchor = true) {
    const resolved = resolveKey(key);
    if (!resolved) return;
    if (!state.selected.includes(resolved)) state.selected.push(resolved);
    state.focused = resolved;
    if (updateAnchor || state.rangeAnchor === null)
      state.rangeAnchor = resolved;
  }
  function removeSelected(key: FileKey) {
    const resolved = resolveKey(key);
    if (!resolved) return;
    state.selected = state.selected.filter((value) => value !== resolved);
    state.focused = resolved;
    state.rangeAnchor = resolved;
  }
  function toggleSelected(key: FileKey) {
    const resolved = resolveKey(key);
    if (!resolved) return;
    if (state.selected.includes(resolved)) removeSelected(resolved);
    else addSelected(resolved);
  }
  function setSelected(keys: FileKey[], focused?: FileKey | null) {
    state.selected = [
      ...new Set(
        keys.map(resolveKey).filter((key): key is string => key !== null)
      ),
    ];
    state.focused = focused
      ? resolveKey(focused)
      : (state.selected.at(-1) ?? null);
    state.rangeAnchor = state.focused;
  }
  function selectRange(
    visibleKeys: FileKey[],
    target: FileKey,
    additive = false
  ) {
    const keys = [
      ...new Set(
        visibleKeys.map(resolveKey).filter((key): key is string => key !== null)
      ),
    ];
    const resolved = resolveKey(target);
    if (!resolved) return;
    let anchor = state.rangeAnchor;
    if (!anchor || !keys.includes(anchor))
      anchor =
        state.focused && keys.includes(state.focused)
          ? state.focused
          : resolved;
    const start = keys.indexOf(anchor),
      end = keys.indexOf(resolved);
    if (start < 0 || end < 0) {
      selectOnly(resolved);
      return;
    }
    const range = keys.slice(Math.min(start, end), Math.max(start, end) + 1);
    state.selected = additive
      ? [...new Set([...state.selected, ...range])]
      : range;
    state.focused = resolved;
    state.rangeAnchor = anchor;
  }
  function setPreselect(
    ref: ListingResourceRef | string | null | undefined,
    sourceScope = scope.value
  ): boolean {
    if (sourceScope !== scope.value) return false;
    state.preselect =
      typeof ref === "object" && ref ? { ...ref } : (ref ?? null);
    state.preselectScope = sourceScope;
    return true;
  }
  function applyPreSelection() {
    const ref = state.preselect,
      source = state.preselectScope;
    state.preselect = null;
    state.preselectScope = null;
    if (!state.req?.isDir || (source !== null && source !== scope.value))
      return;
    if (ref) {
      const key = typeof ref === "string" ? ref : keyFor(ref);
      if (key) selectOnly(key);
      return;
    }
    const parent = fileResourceIdentity(state.req),
      previous = state.oldReq && fileResourceIdentity(state.oldReq);
    if (parent === null || !previous || previous === parent) return;
    const prefix = parent === "/" ? "/" : parent + "/";
    if (!previous.startsWith(prefix)) return;
    const child = prefix + previous.slice(prefix.length).split("/")[0];
    const item = state.req.items.find(
      (value) => fileResourceIdentity(value) === child
    );
    if (item) selectOnly(keyFor(item));
  }
  return {
    ...toRefs(state),
    scope,
    selectedCount,
    selectedItems,
    isListing,
    keyFor,
    itemForKey,
    updateRequest,
    selectOnly,
    addSelected,
    removeSelected,
    toggleSelected,
    setSelected,
    selectRange,
    clearSelection,
    setPreselect,
    applyPreSelection,
    toggleMultiple: () => {
      state.multiple = !state.multiple;
    },
    clearFile: $reset,
    $reset,
  };
});
