import { defineStore } from "pinia";
import * as api from "@/api/recent";
import type { RecentEntry } from "@/api/recent";
import { normalizeFileKey } from "@/utils/fileListing";

function belongsToPrefix(candidate: string, prefix: string) {
  const value = normalizeFileKey(candidate);
  const root = normalizeFileKey(prefix);
  return root === "/" || value === root || value.startsWith(`${root}/`);
}

export const useRecentStore = defineStore("recent", {
  state: (): {
    items: RecentEntry[];
    loading: boolean;
    loaded: boolean;
    error: string;
  } => ({ items: [], loading: false, loaded: false, error: "" }),
  actions: {
    async load() {
      this.loading = true;
      this.error = "";
      try {
        this.items = await api.list();
        this.loaded = true;
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error);
        throw error;
      } finally {
        this.loading = false;
      }
    },
    async record(path: string) {
      const entry = await api.record(normalizeFileKey(path));
      this.items = [
        entry,
        ...this.items.filter((saved) => saved.id !== entry.id),
      ]
        .sort((left, right) => right.accessedAt - left.accessedAt)
        .slice(0, 100);
      return entry;
    },
    applyPathRewrite(from: string, to: string) {
      const source = normalizeFileKey(from);
      const destination = normalizeFileKey(to);
      this.items = this.items.map((entry) => {
        if (!belongsToPrefix(entry.path, source)) return entry;
        const suffix = normalizeFileKey(entry.path).slice(source.length);
        const path = normalizeFileKey(`${destination}${suffix}`);
        const name =
          entry.path === source
            ? path.split("/").at(-1) || entry.name
            : entry.name;
        return { ...entry, path, name };
      });
    },
    applyPathRemoval(prefix: string) {
      this.items = this.items.filter(
        (entry) => !belongsToPrefix(entry.path, prefix)
      );
    },
    resetForUser() {
      this.$reset();
    },
  },
});
