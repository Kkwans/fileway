import type { TaskItem } from "./tasks";
import { fetchJSON, fetchURL } from "./utils";

export interface ArchiveEntry {
  path: string;
  wirePath?: string;
  pathVerified?: boolean;
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
  archiveWirePath?: string;
  pathVerified?: boolean;
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
  archiveWirePath?: string;
  destinationWirePath?: string;
  selectedWirePaths?: string[];
}

export interface SkippedArchiveEntry {
  path: string;
  reason: string;
}

export interface ArchiveExtractReport {
  archivePath: string;
  destination: string;
  selected: string[];
  archiveWirePath?: string;
  destinationWirePath?: string;
  selectedWirePaths?: string[];
  pathsVerified?: boolean;
  extractedFiles: number;
  extractedDirs: number;
  extractedBytes: number;
  skippedCount: number;
  skipped?: SkippedArchiveEntry[];
  completedAt: number;
}

export function entries(path: string, wirePath?: string) {
  if (!wirePath && path.includes("\uFFFD"))
    throw new Error("原始路径无法确认，请从文件列表重新选择压缩包。");
  return fetchJSON<ArchiveListing>(
    `/api/archives/entries?${new URLSearchParams(wirePath ? { wirePath } : { path })}`
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
