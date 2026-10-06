import { fetchJSON } from "./utils";

export interface RecentEntry {
  id: string;
  path: string;
  name: string;
  isDir: boolean;
  accessedAt: number;
}

export function list(limit = 100): Promise<RecentEntry[]> {
  return fetchJSON<RecentEntry[]>(`/api/recent?limit=${limit}`);
}

export function record(path: string): Promise<RecentEntry> {
  return fetchJSON<RecentEntry>("/api/recent", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path }),
  });
}
