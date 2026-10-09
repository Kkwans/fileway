import { fetchJSON, StatusError } from "./utils";
import { encodePath } from "@/utils/url";

export interface RecentEntry {
  id: string;
  path: string;
  wirePath?: string;
  pathVerified?: boolean;
  name: string;
  isDir: boolean;
  accessedAt: number;
}

export function list(limit = 100): Promise<RecentEntry[]> {
  return fetchJSON<RecentEntry[]>(`/api/recent?limit=${limit}`);
}

export async function record(
  path: string,
  wirePath?: string
): Promise<RecentEntry> {
  const originalPath = wirePath || encodePath(path);
  const options = {
    method: "POST" as const,
    headers: { "Content-Type": "application/json" },
  };
  try {
    // A wire-only request is rejected by old servers before any write. Sending
    // display path beside opaque bytes could otherwise record the wrong sibling.
    return await fetchJSON<RecentEntry>("/api/recent", {
      ...options,
      body: JSON.stringify({ wirePath: originalPath }),
    });
  } catch (error) {
    if (!(error instanceof StatusError) || error.status !== 400) throw error;
    let decoded: string;
    try {
      decoded = originalPath
        .split("/")
        .map((part) => decodeURIComponent(part))
        .join("/");
    } catch {
      throw new Error(
        "服务器最近访问接口不支持原始路径，请升级服务器；该次访问未同步"
      );
    }
    if (decoded !== path)
      throw new Error("最近访问路径与原始路径不一致；该次访问未同步");
    return fetchJSON<RecentEntry>("/api/recent", {
      ...options,
      body: JSON.stringify({ path }),
    });
  }
}
