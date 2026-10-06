import { defineStore } from "pinia";
import * as api from "@/api/trash";
import type { TrashConflict, TrashItem, TrashRestoreResult } from "@/api/trash";
import { useFavoritesStore } from "@/stores/favorites";
import { useTagsStore } from "@/stores/tags";

export const useTrashStore = defineStore("trash", {
  state: (): {
    items: TrashItem[];
    loading: boolean;
    error: string;
    loaded: boolean;
    generation: number;
  } => ({
    items: [],
    loading: false,
    error: "",
    loaded: false,
    generation: 0,
  }),
  getters: {
    availableItems: (state) =>
      state.items.filter((item) => item.status === "available"),
  },
  actions: {
    async load() {
      const generation = ++this.generation;
      this.loading = true;
      this.error = "";
      try {
        const items = await api.list();
        if (generation !== this.generation) return;
        this.items = items;
        this.loaded = true;
      } catch (error) {
        if (generation !== this.generation) return;
        this.error = error instanceof Error ? error.message : String(error);
        throw error;
      } finally {
        if (generation === this.generation) this.loading = false;
      }
    },
    recordMoved(item: TrashItem) {
      this.items = [
        item,
        ...this.items.filter((saved) => saved.id !== item.id),
      ];
    },
    async restore(
      id: string,
      conflict: TrashConflict = "fail"
    ): Promise<TrashRestoreResult> {
      const result = await api.restore(id, conflict);
      if (!result.skipped) {
        this.items = this.items.filter((item) => item.id !== id);
        await this.reloadMetadata();
      }
      return result;
    },
    async restoreMany(items: readonly Pick<TrashItem, "id">[]) {
      const restored: TrashRestoreResult[] = [];
      let changed = false;
      try {
        for (const item of items) {
          const result = await api.restore(item.id, "fail");
          restored.push(result);
          if (!result.skipped) {
            changed = true;
            this.items = this.items.filter((saved) => saved.id !== item.id);
          }
        }
      } finally {
        if (changed) await this.reloadMetadata();
      }
      return restored;
    },
    async removePermanent(id: string) {
      await api.removePermanent(id);
      this.items = this.items.filter((item) => item.id !== id);
    },
    async clear() {
      return api.clear();
    },
    async reloadMetadata() {
      await Promise.allSettled([
        useFavoritesStore().loadFavorites(),
        useTagsStore().loadTags(),
      ]);
    },
    resetForUser() {
      const generation = this.generation + 1;
      this.$reset();
      this.generation = generation;
    },
  },
});
