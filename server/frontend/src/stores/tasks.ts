import { defineStore } from "pinia";
import * as api from "@/api/tasks";
import type {
  TaskBatchAction,
  TaskItem,
  TaskListCounts,
  TaskListFilter,
} from "@/api/tasks";

const activeStatuses = new Set<TaskItem["status"]>(["queued", "running"]);
type TaskCountBucket = Exclude<keyof TaskListCounts, "all">;
type TaskCategory = "file" | "background";
type TaskCategoryCounts = Record<TaskCategory, TaskListCounts>;

function emptyTaskListCounts(): TaskListCounts {
  return {
    all: 0,
    active: 0,
    attention: 0,
    canceled: 0,
    completed: 0,
    archived: 0,
  };
}

function emptyCategoryCounts(): TaskCategoryCounts {
  return { file: emptyTaskListCounts(), background: emptyTaskListCounts() };
}

function taskCountBucket(value: TaskItem | undefined): TaskCountBucket {
  if (!value || value.archivedAt) return "archived";
  if (value.status === "queued" || value.status === "running") return "active";
  if (value.status === "failed" || value.status === "interrupted") {
    return "attention";
  }
  if (value.status === "canceled") return "canceled";
  return "completed";
}

function isFileTask(value: TaskItem) {
  return value.type === "file.copy" || value.type === "file.move";
}

function taskCategory(value: TaskItem): TaskCategory {
  return isFileTask(value) ? "file" : "background";
}

function upsertPendingEvent(events: TaskItem[], item: TaskItem) {
  const index = events.findIndex((saved) => saved.id === item.id);
  if (index === -1) {
    events.push(item);
  } else {
    events[index] = item;
  }
}

export const useTasksStore = defineStore("tasks", {
  state: (): {
    items: TaskItem[];
    loading: boolean;
    loaded: boolean;
    error: string;
    total: number;
    nextCursor: string;
    owners: string[];
    counts: TaskListCounts;
    categoryCounts: TaskCategoryCounts;
    currentFilter: TaskListFilter;
    requestGeneration: number;
    summaryGeneration: number;
    summaryLoading: boolean;
    pendingEvents: TaskItem[];
    eventRevision: number;
  } => ({
    items: [],
    loading: false,
    loaded: false,
    error: "",
    total: 0,
    nextCursor: "",
    owners: [],
    counts: emptyTaskListCounts(),
    categoryCounts: emptyCategoryCounts(),
    currentFilter: {},
    requestGeneration: 0,
    summaryGeneration: 0,
    summaryLoading: false,
    pendingEvents: [],
    eventRevision: 0,
  }),
  getters: {
    activeItems: (state) =>
      state.items.filter((item) => activeStatuses.has(item.status)),
  },
  actions: {
    async load(filter: TaskListFilter = {}) {
      const generation = ++this.requestGeneration;
      ++this.summaryGeneration;
      this.summaryLoading = false;
      this.loading = true;
      this.error = "";
      try {
        const response = await api.list(filter);
        if (generation !== this.requestGeneration) return;
        this.items = response.items;
        this.total = response.total;
        this.nextCursor = response.nextCursor ?? "";
        this.owners = response.owners;
        this.counts = response.counts;
        if (response.categoryCounts)
          this.categoryCounts = response.categoryCounts;
        this.currentFilter = { ...filter, statuses: filter.statuses?.slice() };
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
    // Header only needs the aggregate badge. Keep the full list untouched so
    // navigating away from Task Center does not discard a caller's current
    // filter/page while still making the global badge useful after login.
    async loadSummary() {
      if (this.loading || this.summaryLoading) return;
      const generation = ++this.summaryGeneration;
      this.summaryLoading = true;
      try {
        const response = await api.list({ limit: 1 });
        if (generation !== this.summaryGeneration) return;
        this.total = response.total;
        this.counts = response.counts;
        if (response.categoryCounts)
          this.categoryCounts = response.categoryCounts;
        this.owners = response.owners;
      } catch {
        // The header is non-blocking; the Task Center itself exposes errors
        // and offers an explicit retry action.
      } finally {
        if (generation === this.summaryGeneration) {
          this.summaryLoading = false;
          this.flushPendingEvents();
        }
      }
    },
    async loadMore() {
      if (!this.nextCursor || this.loading) return;
      const generation = ++this.requestGeneration;
      ++this.summaryGeneration;
      this.summaryLoading = false;
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
        this.nextCursor = response.nextCursor ?? "";
        this.total = response.total;
        this.owners = response.owners;
        this.counts = response.counts;
        if (response.categoryCounts)
          this.categoryCounts = response.categoryCounts;
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
    async clearRecords(category: "file" | "background") {
      this.loading = true;
      this.error = "";
      try {
        await api.removeAllRecords(category);
        this.items = this.items.filter(
          (item) =>
            !(
              ((category === "file" && isFileTask(item)) ||
                (category === "background" && !isFileTask(item))) &&
              !item.archivedAt &&
              !activeStatuses.has(item.status)
            )
        );
        this.nextCursor = "";
        this.loaded = true;
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error);
        throw error;
      } finally {
        this.loading = false;
      }
    },
    flushPendingEvents() {
      if (
        this.loading ||
        this.summaryLoading ||
        this.pendingEvents.length === 0
      )
        return;
      const pending = this.pendingEvents;
      this.pendingEvents = [];
      for (const item of pending) this.applyRecord(item);
    },
    applyRecord(item: TaskItem) {
      const previous = this.items.find((saved) => saved.id === item.id);
      const categoryMatches =
        !this.loaded ||
        !this.currentFilter.category ||
        (this.currentFilter.category === "file" && isFileTask(item)) ||
        (this.currentFilter.category === "background" && !isFileTask(item));
      if (categoryMatches) {
        this.items = [
          item,
          ...this.items.filter((saved) => saved.id !== item.id),
        ].sort((left, right) => right.createdAt - left.createdAt);
      } else if (previous) {
        this.items = this.items.filter((saved) => saved.id !== item.id);
      }
      // Events are the live source between snapshots. Adjust the aggregate
      // counters using the previous visible value instead of reloading the
      // entire task page for every progress update.
      const after = taskCountBucket(item);
      const adjust = (
        counts: TaskListCounts,
        bucket: TaskCountBucket,
        delta: number
      ) => {
        counts[bucket] = Math.max(0, counts[bucket] + delta);
        if (bucket !== "archived") {
          counts.all = Math.max(0, counts.all + delta);
        }
      };
      const adjustAll = (
        task: TaskItem,
        bucket: TaskCountBucket,
        delta: number
      ) => {
        adjust(this.counts, bucket, delta);
        adjust(this.categoryCounts[taskCategory(task)], bucket, delta);
      };
      if (!previous) {
        adjustAll(item, after, 1);
        return;
      }

      const before = taskCountBucket(previous);
      if (before === after) return;
      adjustAll(previous, before, -1);
      adjustAll(item, after, 1);
    },
    record(item: TaskItem) {
      this.eventRevision += 1;
      if (this.loading || this.summaryLoading) {
        upsertPendingEvent(this.pendingEvents, item);
        return;
      }
      this.applyRecord(item);
    },
    async cancel(id: string) {
      const item = await api.cancel(id);
      this.record(item);
      return item;
    },
    async retry(id: string) {
      const item = await api.retry(id);
      this.record(item);
      return item;
    },
    async archive(id: string) {
      const item = await api.archive(id);
      this.record(item);
      return item;
    },
    async unarchive(id: string) {
      const item = await api.unarchive(id);
      this.record(item);
      return item;
    },
    async batch(action: TaskBatchAction, expectedCount: number) {
      const result = await api.batch(action, this.currentFilter, expectedCount);
      for (const item of result.created ?? []) this.record(item);
      return result;
    },
    async waitForTerminal(id: string, interval = 750): Promise<TaskItem> {
      for (;;) {
        const item = await api.get(id);
        this.record(item);
        if (!activeStatuses.has(item.status)) return item;
        await new Promise((resolve) =>
          globalThis.setTimeout(resolve, interval)
        );
      }
    },
    resetForUser() {
      this.$reset();
    },
  },
});
