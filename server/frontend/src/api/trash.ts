import { fetchJSON, fetchURL } from "./utils";
import type { TaskItem } from "./tasks";

export type TrashStatus = "pending" | "available" | "restoring" | "failed";
export type TrashConflict = "fail" | "keep-both" | "replace" | "skip";

export interface TrashItem {
  id: string;
  userId: number;
  ownerName: string;
  originalPath: string;
  name: string;
  isDir: boolean;
  size: number;
  sizeState?: "unknown" | "calculating" | "accurate" | "incomplete" | "failed";
  sizeTaskId?: string;
  deletedAt: number;
  status: TrashStatus;
  error?: string;
}

export interface TrashRestoreResult {
  path: string;
  skipped: boolean;
}

export function list(): Promise<TrashItem[]> {
  return fetchJSON<TrashItem[]>("/api/trash");
}

export async function restore(
  id: string,
  conflict: TrashConflict = "fail"
): Promise<TrashRestoreResult> {
  const response = await fetchURL(
    `/api/trash/${encodeURIComponent(id)}/restore`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ conflict }),
    }
  );
  return (await response.json()) as TrashRestoreResult;
}

export async function removePermanent(id: string): Promise<void> {
  await fetchURL(`/api/trash/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
}

export async function schedulePermanentDeletion(
  ids: string[]
): Promise<TaskItem> {
  const response = await fetchURL("/api/deletions/pending", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ kind: "trash-items", ids }),
  });
  return (await response.json()) as TaskItem;
}

export async function clear(): Promise<TaskItem> {
  const response = await fetchURL("/api/deletions/pending", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ kind: "trash-all" }),
  });
  return (await response.json()) as TaskItem;
}

export async function measureSize(id: string): Promise<TaskItem> {
  const response = await fetchURL(`/api/trash/${encodeURIComponent(id)}/size`, {
    method: "POST",
  });
  return (await response.json()) as TaskItem;
}
