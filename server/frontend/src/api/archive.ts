import type { TaskItem } from "./tasks";
import { fetchJSON, fetchURL } from "./utils";

export interface ArchiveEntry {
  path: string;
  name: string;
  isDir: boolean;
  size: number;
  modified: number;
}

export interface BlockedArchiveEntry {
  path: string;
  reason: string;
}

export interface ArchiveListing {
  archivePath: string;
  format: string;
  sourceSize: number;
  sourceModified: number;
  entries: ArchiveEntry[];
  listedBytes: number;
  blockedCount: number;
  blocked?: BlockedArchiveEntry[];
  truncated: boolean;
  limitReason?: string;
  maxEntries: number;
  maxFileBytes: number;
  maxExtractBytes: number;
}

export interface ArchiveExtractRequest {
  archivePath: string;
  destination: string;
  selected: string[];
}

export interface SkippedArchiveEntry {
  path: string;
  reason: string;
}

export interface ArchiveExtractReport {
  archivePath: string;
  destination: string;
  selected: string[];
  extractedFiles: number;
  extractedDirs: number;
  extractedBytes: number;
  skippedCount: number;
  skipped?: SkippedArchiveEntry[];
  completedAt: number;
}

export function entries(path: string) {
  return fetchJSON<ArchiveListing>(
    `/api/archives/entries?path=${encodeURIComponent(path)}`
  );
}

export async function extract(request: ArchiveExtractRequest) {
  const response = await fetchURL("/api/archives/extractions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  return (await response.json()) as TaskItem;
}

export function extractionResult(taskId: string) {
  return fetchJSON<ArchiveExtractReport>(
    `/api/archives/extractions/${encodeURIComponent(taskId)}`
  );
}
