import { fetchURL, StatusError } from "./utils";
import url from "../utils/url";
import { normalizeSearchBase } from "../utils/searchPath";
import type { SearchResult } from "@/types/file";

export default async function search(
  base: string,
  query: string,
  scope: "current" | "recursive",
  signal: AbortSignal,
  callback: (item: SearchResult) => void
): Promise<SearchTermination> {
  base = normalizeSearchBase(base);

  // 路径逐段编码，避免中文目录或空格被浏览器按 URL 规则错误解析。
  const encodedBase = url.encodePath(base);

  const params = new URLSearchParams({ query, scope });
  const res = await fetchURL(`/api/search${encodedBase}?${params}`, { signal });
  if (!res.body) {
    throw new StatusError("000 No connection", 0);
  }
  let termination: SearchTermination = { reason: "completed", count: 0 };
  let summaryReceived = false;
  const consumeLine = (line: string) => {
    if (!line) return;
    const event = JSON.parse(line) as SearchStreamEvent;
    if (event.type === "summary") {
      summaryReceived = true;
      termination = {
        reason: event.reason,
        count: event.count,
        error: event.error,
      };
      return;
    }
    if (event.type !== "result" || !event.item) return;
    const item = event.item;
    const searchUrl = `/files${encodedBase}` + url.encodePath(item.path);
    callback({ ...item, url: item.dir ? searchUrl + "/" : searchUrl });
  };

  try {
    // Try streaming approach first (modern browsers)
    if (res.body && typeof res.body.pipeThrough === "function") {
      const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = "";
      while (true) {
        const { done, value } = await reader.read();
        if (value) {
          buffer += value;
        }
        const lines = buffer.split(/\n/);
        let lastLine = lines.pop();
        // Save incomplete last line
        if (!lastLine) {
          lastLine = "";
        }
        buffer = lastLine;

        for (const line of lines) consumeLine(line);
        if (done) break;
      }
      consumeLine(buffer);
    } else {
      // Fallback for browsers without streaming support (e.g., Safari)
      const text = await res.text();
      const lines = text.split(/\n/);
      for (const line of lines) consumeLine(line);
    }
    if (!summaryReceived) {
      throw new StatusError("搜索连接在完成摘要前中断", 0);
    }
  } catch (e) {
    // Check if the error is an intentional cancellation
    if (e instanceof Error && e.name === "AbortError") {
      throw new StatusError("000 No connection", 0, true);
    }
    throw e;
  }
  return termination;
}

export type SearchTerminationReason =
  | "completed"
  | "limit"
  | "timeout"
  | "canceled"
  | "error";

export type SearchTermination = {
  reason: SearchTerminationReason;
  count: number;
  error?: string;
};

type SearchStreamEvent =
  | { type: "result"; item: SearchResult }
  | {
      type: "summary";
      reason: SearchTerminationReason;
      count: number;
      error?: string;
    };
