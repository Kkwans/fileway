import { defineStore } from "pinia";
import * as api from "@/api/transfers";

const TRANSFER_PAGE_SIZE = 10;

function upsertPendingEvent(
  events: api.TransferItem[],
  item: api.TransferItem
) {
  const index = events.findIndex((saved) => saved.id === item.id);
  if (index === -1) events.push(item);
  else events[index] = item;
}

export const useTransfersStore = defineStore("transfers", {
  state: (): {
    items: api.TransferItem[];
    loading: boolean;
    loaded: boolean;
    error: string;
    requestGeneration: Record<string, number>;
    loadingKeys: Record<string, boolean>;
    nextCursor: Record<string, string>;
    loadingMoreKeys: Record<string, boolean>;
    currentStatuses: Record<string, api.TransferStatus[]>;
    pendingEvents: api.TransferItem[];
    eventRevision: number;
  } => ({
    items: [],
    loading: false,
    loaded: false,
    error: "",
    requestGeneration: {},
    loadingKeys: {},
    nextCursor: {},
    loadingMoreKeys: {},
    currentStatuses: {},
    pendingEvents: [],
    eventRevision: 0,
  }),
  getters: {
    uploads: (state) => state.items.filter((item) => item.kind === "upload"),
    downloads: (state) =>
      state.items.filter((item) => item.kind === "download"),
    active: (state) =>
      state.items.filter(
        (item) => item.status === "queued" || item.status === "running"
      ),
    hasMore: (state) => (kind?: api.TransferKind) =>
      Boolean(state.nextCursor[kind || "all"]),
    isLoadingMore: (state) => (kind?: api.TransferKind) =>
      Boolean(state.loadingMoreKeys[kind || "all"]),
  },
  actions: {
    async load(kind?: api.TransferKind, statuses: api.TransferStatus[] = []) {
      const key = kind || "all";
      const generation = (this.requestGeneration[key] || 0) + 1;
      this.requestGeneration[key] = generation;
      this.loadingKeys[key] = true;
      this.loading = true;
      this.error = "";
      this.currentStatuses[key] = [...statuses];
      try {
        const response = statuses.length
          ? await api.list({ kind, statuses, limit: TRANSFER_PAGE_SIZE })
          : await api.list(kind, undefined, TRANSFER_PAGE_SIZE);
        if (generation !== this.requestGeneration[key]) return;
        const existing = kind
          ? this.items.filter((item) => item.kind !== kind)
          : [];
        this.items = mergeItems(existing, response.items);
        this.nextCursor[key] = response.nextCursor ?? "";
        this.loaded = true;
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error);
        throw error;
      } finally {
        if (generation === this.requestGeneration[key]) {
          this.loadingKeys[key] = false;
          this.loading =
            Object.values(this.loadingKeys).some(Boolean) ||
            Object.values(this.loadingMoreKeys).some(Boolean);
          this.flushPendingEvents();
        }
      }
    },
    async loadMore(kind: api.TransferKind) {
      const key = kind || "all";
      const cursor = this.nextCursor[key];
      if (!cursor || this.loadingMoreKeys[key]) return;
      const generation = (this.requestGeneration[key] || 0) + 1;
      this.requestGeneration[key] = generation;
      this.loadingMoreKeys[key] = true;
      this.loading = true;
      this.error = "";
      try {
        const statuses = this.currentStatuses[key] || [];
        const response = statuses.length
          ? await api.list({
              kind,
              statuses,
              cursor,
              limit: TRANSFER_PAGE_SIZE,
            })
          : await api.list(kind, cursor, TRANSFER_PAGE_SIZE);
        if (generation !== this.requestGeneration[key]) return;
        this.items = mergeItems(this.items, response.items);
        this.nextCursor[key] = response.nextCursor ?? "";
        this.loaded = true;
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error);
        throw error;
      } finally {
        if (generation === this.requestGeneration[key]) {
          this.loadingMoreKeys[key] = false;
          this.loading =
            Object.values(this.loadingKeys).some(Boolean) ||
            Object.values(this.loadingMoreKeys).some(Boolean);
          this.flushPendingEvents();
        }
      }
    },
    flushPendingEvents() {
      if (this.loading || this.pendingEvents.length === 0) return;
      const pending = this.pendingEvents;
      this.pendingEvents = [];
      for (const item of pending) this.applyRecord(item);
    },
    applyRecord(item: api.TransferItem) {
      this.items = [
        item,
        ...this.items.filter((saved) => saved.id !== item.id),
      ].sort((left, right) => right.createdAt - left.createdAt);
    },
    record(item: api.TransferItem) {
      this.eventRevision += 1;
      if (this.loading) {
        upsertPendingEvent(this.pendingEvents, item);
        return;
      }
      this.applyRecord(item);
    },
    async cancel(id: string) {
      this.record(await api.cancel(id));
    },
    async remove(id: string) {
      await api.remove(id);
      this.items = this.items.filter((item) => item.id !== id);
    },
    async removeAll(kind: api.TransferKind) {
      await api.removeAll(kind);
      this.items = this.items.filter((item) => item.kind !== kind);
      this.nextCursor[kind] = "";
      this.currentStatuses[kind] = [];
    },
    resetForUser() {
      this.$reset();
    },
  },
});

function mergeItems(
  current: api.TransferItem[],
  incoming: api.TransferItem[]
): api.TransferItem[] {
  const byId = new Map(current.map((item) => [item.id, item]));
  for (const item of incoming) byId.set(item.id, item);
  return [...byId.values()].sort(
    (left, right) =>
      right.createdAt - left.createdAt || right.id.localeCompare(left.id)
  );
}
