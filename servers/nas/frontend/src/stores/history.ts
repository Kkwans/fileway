import { defineStore } from "pinia";
import * as api from "@/api/history";
import type { HistoryEntry, HistoryListFilter } from "@/api/history";

function upsertPendingEvent(events: HistoryEntry[], item: HistoryEntry) {
  const index = events.findIndex((saved) => saved.id === item.id);
  if (index === -1) events.push(item);
  else events[index] = item;
}

export const useHistoryStore = defineStore("history", {
  state: (): {
    items: HistoryEntry[];
    loading: boolean;
    loaded: boolean;
    error: string;
    total: number;
    nextCursor: string;
    currentFilter: HistoryListFilter;
    requestGeneration: number;
    pendingEvents: HistoryEntry[];
    eventRevision: number;
  } => ({
    items: [],
    loading: false,
    loaded: false,
    error: "",
    total: 0,
    nextCursor: "",
    currentFilter: {},
    requestGeneration: 0,
    pendingEvents: [],
    eventRevision: 0,
  }),
  actions: {
    flushPendingEvents() {
      if (this.loading || this.pendingEvents.length === 0) return;
      const pending = this.pendingEvents;
      this.pendingEvents = [];
      for (const item of pending) this.applyRecord(item);
    },
    applyRecord(item: HistoryEntry) {
      this.items = [
        item,
        ...this.items.filter((saved) => saved.id !== item.id),
      ].sort((left, right) => right.createdAt - left.createdAt);
    },
    record(item: HistoryEntry) {
      this.eventRevision += 1;
      if (this.loading) {
        upsertPendingEvent(this.pendingEvents, item);
        return;
      }
      this.applyRecord(item);
    },
    async load(filter: HistoryListFilter = {}) {
      const generation = ++this.requestGeneration;
      this.loading = true;
      this.error = "";
      try {
        const response = await api.list(filter);
        if (generation !== this.requestGeneration) return;
        this.items = response.items;
        this.total = response.total;
        this.nextCursor = response.nextCursor ?? "";
        this.currentFilter = { ...filter };
        this.loaded = true;
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error);
        throw error;
      } finally {
        if (generation === this.requestGeneration) {
          this.loading = false;
          this.flushPendingEvents();
        }
      }
    },
    async loadMore() {
      if (!this.nextCursor || this.loading) return;
      const generation = ++this.requestGeneration;
      this.loading = true;
      this.error = "";
      try {
        const response = await api.list({
          ...this.currentFilter,
          cursor: this.nextCursor,
        });
        if (generation !== this.requestGeneration) return;
        const known = new Set(this.items.map((item) => item.id));
        this.items.push(
          ...response.items.filter((item) => !known.has(item.id))
        );
        this.total = response.total;
        this.nextCursor = response.nextCursor ?? "";
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error);
        throw error;
      } finally {
        if (generation === this.requestGeneration) {
          this.loading = false;
          this.flushPendingEvents();
        }
      }
    },
    async clear() {
      this.loading = true;
      this.error = "";
      try {
        await api.removeAll();
        this.items = [];
        this.total = 0;
        this.nextCursor = "";
        this.loaded = true;
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error);
        throw error;
      } finally {
        this.loading = false;
      }
    },
    resetForUser() {
      this.$reset();
    },
  },
});
