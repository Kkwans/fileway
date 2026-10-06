import { defineStore } from "pinia";
import type { FileKey, Resource, ResourceItem } from "@/types/file";
import { normalizeFileKey } from "@/utils/fileListing";

export const useFileStore = defineStore("file", {
  // convert to a function
  state: (): {
    req: Resource | null;
    oldReq: Resource | null;
    reload: boolean;
    selected: FileKey[];
    focused: FileKey | null;
    rangeAnchor: FileKey | null;
    multiple: boolean;
    isFiles: boolean;
    preselect: string | null;
  } => ({
    req: null,
    oldReq: null,
    reload: false,
    selected: [],
    focused: null,
    rangeAnchor: null,
    multiple: false,
    isFiles: false,
    preselect: null,
  }),
  getters: {
    selectedCount: (state) => state.selected.length,
    selectedItems: (state): ResourceItem[] => {
      if (!state.req?.items) return [];
      const selected = new Set(state.selected);
      return state.req.items.filter((item) =>
        selected.has(normalizeFileKey(item.path))
      );
    },
    // route: () => {
    //   const routerStore = useRouterStore();
    //   return routerStore.router.currentRoute;
    // },
    // isFiles: (state) => {
    //   const layoutStore = useLayoutStore();
    //   return !layoutStore.loading && state.route._value.name === "Files";
    // },
    isListing: (state) => {
      return state.isFiles && state?.req?.isDir;
    },
  },
  actions: {
    // no context as first argument, use `this` instead
    toggleMultiple() {
      this.multiple = !this.multiple;
    },
    updateRequest(value: Resource | null) {
      const previousPath = this.req?.path
        ? normalizeFileKey(this.req.path)
        : null;
      this.oldReq = this.req;
      this.req = value;

      const nextPath = this.req?.path ? normalizeFileKey(this.req.path) : null;
      if (previousPath !== nextPath || !this.req?.items) {
        this.clearSelection();
        return;
      }

      const available = new Set(
        this.req.items.map((item) => normalizeFileKey(item.path))
      );
      this.selected = this.selected.filter((key) => available.has(key));
      if (this.focused && !available.has(this.focused)) this.focused = null;
      if (this.rangeAnchor && !available.has(this.rangeAnchor)) {
        this.rangeAnchor = this.focused;
      }
    },
    keyFor(item: Pick<ResourceItem, "path">): FileKey {
      return normalizeFileKey(item.path);
    },
    itemForKey(key: FileKey): ResourceItem | undefined {
      return this.req?.items.find(
        (item) => normalizeFileKey(item.path) === normalizeFileKey(key)
      );
    },
    selectOnly(key: FileKey) {
      const normalized = normalizeFileKey(key);
      this.selected = [normalized];
      this.focused = normalized;
      this.rangeAnchor = normalized;
    },
    addSelected(key: FileKey, updateAnchor = true) {
      const normalized = normalizeFileKey(key);
      if (!this.selected.includes(normalized)) this.selected.push(normalized);
      this.focused = normalized;
      if (updateAnchor || this.rangeAnchor === null)
        this.rangeAnchor = normalized;
    },
    removeSelected(value: FileKey) {
      const normalized = normalizeFileKey(value);
      const i = this.selected.indexOf(normalized);
      if (i === -1) return;
      this.selected.splice(i, 1);
      this.focused = normalized;
      this.rangeAnchor = normalized;
    },
    toggleSelected(key: FileKey) {
      const normalized = normalizeFileKey(key);
      if (this.selected.includes(normalized)) this.removeSelected(normalized);
      else this.addSelected(normalized);
    },
    setSelected(keys: FileKey[], focused?: FileKey | null) {
      this.selected = [...new Set(keys.map(normalizeFileKey))];
      const nextFocus = focused ?? this.selected.at(-1) ?? null;
      this.focused = nextFocus ? normalizeFileKey(nextFocus) : null;
      this.rangeAnchor = this.focused;
    },
    selectRange(visibleKeys: FileKey[], target: FileKey, additive = false) {
      const keys = visibleKeys.map(normalizeFileKey);
      const normalizedTarget = normalizeFileKey(target);
      let anchor = this.rangeAnchor;
      if (!anchor || !keys.includes(anchor)) {
        anchor =
          this.focused && keys.includes(this.focused)
            ? this.focused
            : normalizedTarget;
      }
      const anchorIndex = keys.indexOf(anchor);
      const targetIndex = keys.indexOf(normalizedTarget);
      if (anchorIndex < 0 || targetIndex < 0) {
        this.selectOnly(normalizedTarget);
        return;
      }
      const start = Math.min(anchorIndex, targetIndex);
      const end = Math.max(anchorIndex, targetIndex);
      const range = keys.slice(start, end + 1);
      this.selected = additive
        ? [...new Set([...this.selected, ...range])]
        : range;
      this.focused = normalizedTarget;
      this.rangeAnchor = anchor;
    },
    clearSelection() {
      this.selected = [];
      this.focused = null;
      this.rangeAnchor = null;
      this.multiple = false;
    },
    // easily reset state using `$reset`
    clearFile() {
      this.$reset();
    },
  },
});
