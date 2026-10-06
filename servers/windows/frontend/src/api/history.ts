import { fetchJSON, fetchURL } from "./utils";

export type HistoryStatus = "success" | "failed" | "submitted";

export interface HistoryEntry {
  id: string;
  action: string;
  target: string;
  detail?: string;
  status: HistoryStatus;
  createdAt: number;
}

export interface HistoryListFilter {
  text?: string;
  action?: string;
  status?: HistoryStatus;
  from?: number;
  to?: number;
  cursor?: string;
  limit?: number;
}

export interface HistoryListResponse {
  items: HistoryEntry[];
  nextCursor?: string;
  total: number;
}

export function list(
  filter: HistoryListFilter = {}
): Promise<HistoryListResponse> {
  const query = new URLSearchParams();
  if (filter.text) query.set("text", filter.text);
  if (filter.action) query.set("action", filter.action);
  if (filter.status) query.set("status", filter.status);
  if (filter.from) query.set("from", String(filter.from));
  if (filter.to) query.set("to", String(filter.to));
  if (filter.cursor) query.set("cursor", filter.cursor);
  if (filter.limit) query.set("limit", String(filter.limit));
  const suffix = query.size ? `?${query.toString()}` : "";
  return fetchJSON<HistoryListResponse>(`/api/history${suffix}`);
}

export async function removeAll(): Promise<number> {
  const response = await fetchURL("/api/history", { method: "DELETE" });
  const result = (await response.json()) as { deleted?: number };
  return result.deleted ?? 0;
}
