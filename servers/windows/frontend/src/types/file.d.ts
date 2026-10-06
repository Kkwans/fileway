// This file contains type definitions used globally across the application.
// Types can be imported via `import type { X } from "@/types/file"` or used as ambient globals.

export {};

export type FileKey = string;
export type RiskLevel = "low" | "medium" | "high";

interface ResourceBase {
  path: string;
  name: string;
  size: number;
  extension: string;
  modified: string; // ISO 8601 datetime
  /** Filesystem birth time when the backend can obtain it reliably. */
  created?: string;
  mode: number;
  isDir: boolean;
  isSymlink: boolean;
  type: ResourceType;
  riskLevel: RiskLevel;
  url: string;
  /** Original filesystem bytes encoded as a routable path by the backend. */
  wirePath?: string;
}

export interface Resource extends ResourceBase {
  items: ResourceItem[];
  numDirs: number;
  numFiles: number;
  sorting: Sorting;
  hash?: string;
  token?: string;
  index: number;
  subtitles?: string[];
  content?: string;
  rawContent?: ArrayBuffer;
}

export interface ResourceItem extends ResourceBase {
  index: number;
  subtitles?: string[];
}

export interface BatchResourceResult {
  path: string;
  status: number;
  item?: ResourceItem;
  error?: string;
}

export type ResourceType =
  | "dir"
  | "video"
  | "audio"
  | "image"
  | "pdf"
  | "text"
  | "blob"
  | "textImmutable";

export type DownloadFormat =
  | "zip"
  | "tar"
  | "targz"
  | "tarbz2"
  | "tarxz"
  | "tarlz4"
  | "tarsz"
  | null;

export interface ClipItem {
  from: string;
  name: string;
  isDir: boolean;
  size?: number;
  modified?: string;
}

export interface BreadCrumb {
  name: string;
  url: string;
}

export interface ConflictingItem {
  lastModified: number | string | undefined;
  size: number | undefined;
}

export interface ConflictingResource {
  index: number;
  name: string;
  origin: ConflictingItem;
  dest: ConflictingItem;
  checked: Array<"origin" | "dest", "origin-resume">;
  isSmallerOnServer?: boolean;
}

export interface CsvData {
  headers: string[];
  rows: string[][];
}

export interface RecursiveEntry {
  path: string;
  name: string;
  size: number;
  modified: string;
  isDir: boolean;
}

export interface SearchResult {
  dir: boolean;
  path: string;
  name: string;
  size: number;
  modified: string;
  riskLevel: RiskLevel;
  url?: string;
}

export interface MoveCopyItem extends ClipItem {
  to: string;
  isDir: boolean;
  overwrite: boolean;
  rename: boolean;
}

// Re-export for convenience
export type MoveCopyItemUploadList = MoveCopyItem[];

export interface PasteItem {
  name: string;
  size: number;
  modified?: string;
  from: string;
  to: string;
  isDir: boolean;
  overwrite: boolean;
  rename: boolean;
}

/**
 * Result item type returned by the conflict resolution dialog (ResolveConflict.vue).
 * Each item carries the resolution decision made by the user for a conflicting entry.
 */
export interface ConflictResult {
  index: number;
  checked: string[];
}
