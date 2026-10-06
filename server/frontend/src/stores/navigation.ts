import { defineStore } from "pinia";

const storageKey = "nas-file-browser-navigation-v1";
const maxEntries = 40;

export interface NavigationEntry {
  path: string;
  position: number | null;
}

export interface DirectoryState {
  scrollY: number;
  limit: number;
  sortBy: string;
  sortAsc: boolean;
  sortOverridden: boolean;
  viewMode: string;
  search: string;
  tag: string | null;
  filterMode: "current" | "global";
}

export function isAppLocation(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.length < 8192 &&
    !/[\\\u0000-\u001f]/.test(value) &&
    /^\/(?:files|search|recent|trash|tasks|analysis|archive|settings)(?:\/|[?#]|$)/.test(
      value
    )
  );
}

function page(path: string) {
  return path.split(/[?#]/, 1)[0];
}

function validEntry(value: unknown): value is NavigationEntry {
  if (!value || typeof value !== "object") return false;
  const entry = value as NavigationEntry;
  return (
    isAppLocation(entry.path) &&
    (entry.position === null ||
      (Number.isSafeInteger(entry.position) && entry.position >= 0))
  );
}

function validDirectory(value: unknown): value is DirectoryState {
  if (!value || typeof value !== "object") return false;
  const state = value as DirectoryState;
  return (
    Number.isFinite(state.scrollY) &&
    state.scrollY >= 0 &&
    Number.isSafeInteger(state.limit) &&
    state.limit >= 0 &&
    ["name", "size", "modified", "type"].includes(state.sortBy) &&
    typeof state.sortAsc === "boolean" &&
    typeof state.sortOverridden === "boolean" &&
    ["mosaic", "compact-grid", "details", "compact-list"].includes(
      state.viewMode
    ) &&
    typeof state.search === "string" &&
    (state.tag === null || typeof state.tag === "string") &&
    ["current", "global"].includes(state.filterMode)
  );
}

export const useNavigationStore = defineStore("navigation", {
  state: () => ({
    userId: null as number | null,
    trail: [] as NavigationEntry[],
    lastDirectory: "/files/",
    directories: {} as Record<string, DirectoryState>,
    restorePath: null as string | null,
  }),
  getters: {
    returnEntry(state): NavigationEntry {
      return (
        state.trail.at(-2) ?? { path: state.lastDirectory, position: null }
      );
    },
  },
  actions: {
    setAccount(userId: number) {
      if (this.userId === userId) return;
      this.$reset();
      this.userId = userId;
      try {
        const saved = JSON.parse(sessionStorage.getItem(storageKey) || "null");
        if (saved?.userId === userId) {
          this.trail = Array.isArray(saved.trail)
            ? saved.trail.filter(validEntry).slice(-maxEntries)
            : [];
          if (
            isAppLocation(saved.lastDirectory) &&
            saved.lastDirectory.startsWith("/files/")
          ) {
            this.lastDirectory = saved.lastDirectory;
          }
          if (saved.directories && typeof saved.directories === "object") {
            for (const [path, state] of Object.entries(saved.directories).slice(
              -maxEntries
            )) {
              if (
                isAppLocation(path) &&
                path.startsWith("/files/") &&
                validDirectory(state)
              ) {
                this.directories[path] = state;
              }
            }
          }
        }
      } catch {
        /* Private mode or corrupt state must not block navigation. */
      }
      this.persist();
    },
    clear() {
      this.$reset();
      try {
        sessionStorage.removeItem(storageKey);
      } catch {
        /* Storage unavailable. */
      }
    },
    persist() {
      if (this.userId === null) return;
      try {
        sessionStorage.setItem(
          storageKey,
          JSON.stringify({
            userId: this.userId,
            trail: this.trail,
            lastDirectory: this.lastDirectory,
            directories: this.directories,
          })
        );
      } catch {
        /* Keep the in-memory return chain when storage is unavailable. */
      }
    },
    record(entry: NavigationEntry, initial = false) {
      if (this.userId === null || !validEntry(entry)) return;
      const current = this.trail.at(-1);
      if (initial) {
        if (current?.path === entry.path) this.restorePath = entry.path;
        else this.trail = [];
      }
      let previous = -1;
      for (let index = this.trail.length - 1; index >= 0; index--) {
        if (this.trail[index].path === entry.path) {
          previous = index;
          break;
        }
      }
      if (previous >= 0 && previous < this.trail.length - 1) {
        this.trail = this.trail.slice(0, previous);
        this.restorePath = entry.path;
      } else if (page(this.trail.at(-1)?.path || "") === page(entry.path)) {
        // Filter/tab/page query changes update the current step, not the back chain.
        this.trail.pop();
      }
      this.trail.push(entry);
      this.trail = this.trail.slice(-maxEntries);
      this.persist();
    },
    prepareReturn(path: string) {
      if (isAppLocation(path)) this.restorePath = path;
    },
    rememberDirectory(path: string, state: DirectoryState) {
      if (
        this.userId === null ||
        !isAppLocation(path) ||
        !path.startsWith("/files/") ||
        !validDirectory(state)
      )
        return;
      this.lastDirectory = path;
      delete this.directories[path];
      this.directories[path] = { ...state };
      const keys = Object.keys(this.directories);
      for (const key of keys.slice(0, -maxEntries))
        delete this.directories[key];
      this.persist();
    },
    takeDirectoryState(path: string) {
      if (this.restorePath !== path) return null;
      this.restorePath = null;
      return this.directories[path] ? { ...this.directories[path] } : null;
    },
  },
});
