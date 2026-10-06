import type { RouteLocationRaw } from "vue-router";
import { normalizeFileKey } from "./fileListing";

const BROWSABLE_ARCHIVE_SUFFIXES = [
  ".tar.bz2",
  ".tar.gz",
  ".tar.xz",
  ".tar.zst",
  ".tar",
  ".zip",
] as const;

export function isBrowsableArchivePath(value: string) {
  const normalized = value.toLowerCase();
  return BROWSABLE_ARCHIVE_SUFFIXES.some((suffix) =>
    normalized.endsWith(suffix)
  );
}

export function archiveRoute(path: string): RouteLocationRaw {
  return { path: "/archive", query: { path: normalizeFileKey(path) } };
}

export function resourceOpenRoute(resource: {
  isDir: boolean;
  path: string;
  url: string;
}): RouteLocationRaw {
  if (!resource.isDir && isBrowsableArchivePath(resource.path)) {
    return archiveRoute(resource.path);
  }
  return { path: resource.url };
}
