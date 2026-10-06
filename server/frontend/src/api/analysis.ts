import type { TaskItem } from "./tasks";
import { fetchJSON, fetchURL, StatusError } from "./utils";

export interface DuplicateFile {
  created?: string;
  identity?: {
    deviceMajor: number;
    deviceMinor: number;
    inode: number;
    links: number;
    mode: number;
    uid: number;
    gid: number;
  };
  path: string;
  size: number;
  modified: number;
}

export interface DuplicateGroup {
  suggestedKeepPath?: string;
  keepReason?:
    | "oldest-created"
    | "missing-created"
    | "tied-created"
    | "truncated"
    | "unsafe-identity";
  sha256: string;
  size: number;
  totalFiles: number;
  reclaimableBytes: number;
  files: DuplicateFile[];
}

export interface SkippedFile {
  path: string;
  reason: string;
}

export interface DuplicateReport {
  schemaVersion?: number;
  scopes: string[];
  scannedFiles: number;
  scannedBytes: number;
  candidateFiles: number;
  duplicateGroups: number;
  duplicateFiles: number;
  reclaimableBytes: number;
  groups: DuplicateGroup[];
  skippedCount: number;
  skipped?: SkippedFile[];
  truncated: boolean;
  completedAt: number;
  resultFileLimit: number;
}

export interface DuplicateCleanupSelection {
  sha256: string;
  keepPath: string;
}

export interface DuplicateCleanupFileResult {
  path: string;
  status: "success" | "skipped" | "failed";
  trashId?: string;
  reason?: string;
}

export interface DuplicateCleanupResult {
  reportId: string;
  groups: Array<{
    sha256: string;
    keepPath: string;
    files: DuplicateCleanupFileResult[];
  }>;
}

export interface StorageScope {
  path: string;
  isDir: boolean;
  files: number;
  directories: number;
  bytes: number;
}

export interface StorageFile {
  path: string;
  size: number;
  modified: number;
}

export interface StorageDirectory {
  path: string;
  files: number;
  bytes: number;
}

export interface StorageReport {
  scopes: StorageScope[];
  scannedFiles: number;
  scannedDirectories: number;
  scannedBytes: number;
  largestFiles: StorageFile[];
  largestDirectories: StorageDirectory[];
  skippedCount: number;
  skipped?: SkippedFile[];
  truncated: boolean;
  completedAt: number;
  resultLimit: number;
}

export interface AnalysisRecentMetrics {
  scannedFiles: number;
  scannedDirectories: number;
  scannedBytes: number;
  duplicateGroups: number;
  reclaimableBytes: number;
}

export interface AnalysisRecentItem {
  id: string;
  tool: "duplicates" | "storage";
  status: TaskItem["status"];
  createdAt: number;
  finishedAt?: number;
  scopes: string[];
  processedItems: number;
  totalItems: number;
  error?: string;
  resultReady: boolean;
  metrics?: AnalysisRecentMetrics;
}

export interface AnalysisRecentPage {
  items: AnalysisRecentItem[];
  nextCursor?: string;
}

export async function startDuplicateScan(paths: string[]) {
  const response = await fetchURL("/api/analysis/duplicates", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ paths }),
  });
  return (await response.json()) as TaskItem;
}

export function getDuplicateReport(taskId: string) {
  return fetchJSON<DuplicateReport>(
    `/api/analysis/${encodeURIComponent(taskId)}`
  );
}

export async function startDuplicateCleanup(
  reportId: string,
  groups: DuplicateCleanupSelection[]
) {
  const response = await fetchURL(
    `/api/analysis/duplicates/${encodeURIComponent(reportId)}/cleanup`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ groups }),
    }
  );
  return (await response.json()) as TaskItem;
}

export async function getDuplicateCleanupForReport(reportId: string) {
  try {
    return await fetchJSON<TaskItem>(
      `/api/analysis/duplicates/${encodeURIComponent(reportId)}/cleanup`
    );
  } catch (error) {
    if (error instanceof StatusError && error.status === 404) return null;
    throw error;
  }
}

export function getDuplicateCleanupResult(taskId: string) {
  return fetchJSON<DuplicateCleanupResult>(
    `/api/analysis/duplicates/cleanup/${encodeURIComponent(taskId)}`
  );
}

export async function startStorageScan(paths: string[]) {
  const response = await fetchURL("/api/analysis/storage", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ paths }),
  });
  return (await response.json()) as TaskItem;
}

export function getStorageReport(taskId: string) {
  return fetchJSON<StorageReport>(
    `/api/analysis/storage/${encodeURIComponent(taskId)}`
  );
}

export function listRecentScans(
  tool: AnalysisRecentItem["tool"],
  cursor?: string,
  limit = 6
) {
  const query = new URLSearchParams({ tool, limit: String(limit) });
  if (cursor) query.set("cursor", cursor);
  return fetchJSON<AnalysisRecentPage>(`/api/analysis/recent?${query}`);
}
