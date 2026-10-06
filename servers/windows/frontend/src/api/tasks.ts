import { fetchJSON, fetchURL } from "./utils";

export type TaskType =
  | "file.copy"
  | "file.move"
  | "file.delete.permanent"
  | "trash.delete.permanent"
  | "trash.clear"
  | "trash.size"
  | "analysis.duplicates"
  | "analysis.duplicates.cleanup"
  | "analysis.storage"
  | "archive.extract"
  | "media.hls";
export type TaskStatus =
  | "queued"
  | "running"
  | "completed"
  | "failed"
  | "canceled"
  | "interrupted";

export interface TaskItem {
  id: string;
  userId: number;
  ownerName: string;
  type: TaskType;
  title: string;
  status: TaskStatus;
  createdAt: number;
  startedAt?: number;
  finishedAt?: number;
  archivedAt?: number;
  totalItems: number;
  processedItems: number;
  totalBytes: number;
  processedBytes: number;
  error?: string;
  retryOf?: string;
  undoUntil?: number;
}

export interface TaskListCounts {
  all: number;
  active: number;
  attention: number;
  canceled: number;
  completed: number;
  archived: number;
}

export interface TaskListFilter {
  statuses?: TaskStatus[];
  user?: string;
  type?: TaskType;
  archived?: boolean;
  text?: string;
  from?: number;
  to?: number;
  category?: "file" | "background";
  cursor?: string;
  limit?: number;
}

export interface TaskListResponse {
  items: TaskItem[];
  nextCursor?: string;
  total: number;
  counts: TaskListCounts;
  categoryCounts?: {
    file: TaskListCounts;
    background: TaskListCounts;
  };
  owners: string[];
}

export type TaskBatchAction = "retry" | "archive" | "unarchive";

export interface TaskBatchResponse {
  matched: number;
  succeeded: number;
  skipped: number;
  actualCount?: number;
  created?: TaskItem[];
  failures?: Array<{ id: string; error: string }>;
}

export async function removeAllRecords(
  category: "file" | "background"
): Promise<number> {
  const response = await fetchURL(
    `/api/tasks?category=${encodeURIComponent(category)}`,
    { method: "DELETE" }
  );
  const result = (await response.json()) as { deleted?: number };
  return result.deleted ?? 0;
}

function queryString(filter: TaskListFilter) {
  const query = new URLSearchParams();
  if (filter.statuses?.length) query.set("status", filter.statuses.join(","));
  if (filter.user) query.set("user", filter.user);
  if (filter.type) query.set("type", filter.type);
  if (filter.archived !== undefined)
    query.set("archived", String(filter.archived));
  if (filter.text) query.set("text", filter.text);
  if (filter.from) query.set("from", String(filter.from));
  if (filter.to) query.set("to", String(filter.to));
  if (filter.category) query.set("category", filter.category);
  if (filter.cursor) query.set("cursor", filter.cursor);
  if (filter.limit) query.set("limit", String(filter.limit));
  const encoded = query.toString();
  return encoded ? `?${encoded}` : "";
}

export function list(filter: TaskListFilter = {}): Promise<TaskListResponse> {
  return fetchJSON<TaskListResponse>(`/api/tasks${queryString(filter)}`);
}

export function get(id: string): Promise<TaskItem> {
  return fetchJSON<TaskItem>(`/api/tasks/${encodeURIComponent(id)}`);
}

async function mutate(
  id: string,
  action: "cancel" | "retry" | "archive" | "unarchive"
) {
  const response = await fetchURL(
    `/api/tasks/${encodeURIComponent(id)}/${action}`,
    { method: "POST" }
  );
  return (await response.json()) as TaskItem;
}

export function cancel(id: string): Promise<TaskItem> {
  return mutate(id, "cancel");
}

export function retry(id: string): Promise<TaskItem> {
  return mutate(id, "retry");
}

export function archive(id: string): Promise<TaskItem> {
  return mutate(id, "archive");
}

export function unarchive(id: string): Promise<TaskItem> {
  return mutate(id, "unarchive");
}

export async function batch(
  action: TaskBatchAction,
  filters: TaskListFilter,
  expectedCount: number
): Promise<TaskBatchResponse> {
  const response = await fetchURL("/api/tasks/batch", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      action,
      filters: {
        statuses: filters.statuses,
        user: filters.user,
        type: filters.type,
        archived: filters.archived,
        text: filters.text,
        from: filters.from,
        to: filters.to,
        category: filters.category,
      },
      expectedCount,
    }),
  });
  return (await response.json()) as TaskBatchResponse;
}
