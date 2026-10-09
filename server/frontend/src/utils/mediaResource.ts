import { createURL } from "@/api/utils";
import { favoriteIdentity, favoriteWirePath } from "./favoritePersistence";

export type MediaResourcePath = {
  path: string;
  wirePath?: string;
  pathVerified?: boolean;
};
export type MediaSource = string | MediaResourcePath;

export function mediaReference(source: MediaSource): MediaResourcePath {
  return typeof source === "string" ? { path: source } : source;
}

/** Go's media GET/DELETE handlers consume Query.Get("path") as raw FS bytes.
 * Escape query separators only; encoded opaque bytes must survive exactly one
 * query decode, without another URLSearchParams percent-encoding pass. */
export function mediaPathQuery(source: MediaSource): string {
  const wire = favoriteWirePath(mediaReference(source));
  if (!wire) throw new Error("媒体原始路径无法确认，请重新选择");
  return (
    "path=" +
    wire.replace(
      /[\/+&=]/g,
      (value) => "%" + value.charCodeAt(0).toString(16).toUpperCase()
    )
  );
}

/** Current playback PUT and HLS POST accept JSON path only. JSON cannot carry
 * invalid UTF-8 filename bytes, so fail before sending a display-name sibling. */
export function mediaJSONPath(source: MediaSource): string {
  const resource = mediaReference(source);
  const wire = favoriteWirePath(resource);
  try {
    if (
      wire &&
      wire.split("/").map(decodeURIComponent).join("/") === resource.path
    )
      return resource.path;
  } catch {
    /* Opaque source: this server contract cannot safely write it. */
  }
  throw new Error(
    "服务器尚不支持此原始路径的续播保存或兼容播放，请直接播放或下载原文件"
  );
}

/** createURL owns the same-origin base/query; append validated original path
 * bytes afterwards so percent escapes are never encoded a second time. */
export function mediaResourceURL(
  prefix: string,
  resource: MediaResourcePath,
  params = {}
) {
  const wire = favoriteWirePath(resource);
  if (!wire) throw new Error("媒体原始路径无法确认，请从文件列表重新选择");
  const base = createURL(prefix, params);
  const query = base.indexOf("?");
  return query < 0
    ? base + wire
    : base.slice(0, query) + wire + base.slice(query);
}

/** Same display names never select a different file's previous/next position. */
export function mediaResourceIndex(
  resources: readonly MediaResourcePath[],
  selected: MediaResourcePath
) {
  const key = favoriteIdentity(selected);
  return key === null
    ? -1
    : resources.findIndex((resource) => favoriteIdentity(resource) === key);
}
