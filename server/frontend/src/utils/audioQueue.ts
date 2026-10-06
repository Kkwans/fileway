import { createURL } from "@/api/utils";
import type { Favorite } from "@/stores/favorites";
import type { ResourceItem } from "@/types/file";

export interface AudioQueueItem {
  path: string;
  name: string;
  source: string;
  size: number;
  modified: string;
  origin: "directory" | "favorite-group";
  groupId?: string;
}

const audioExtensions = new Set([
  ".aac",
  ".aif",
  ".aiff",
  ".amr",
  ".caf",
  ".flac",
  ".m4a",
  ".mid",
  ".midi",
  ".mp2",
  ".mp3",
  ".oga",
  ".ogg",
  ".opus",
  ".wav",
  ".weba",
  ".wma",
]);

export function audioSource(path: string) {
  return createURL(`api/raw${path}`, { inline: "true" });
}

export function directoryAudioQueue(items: ResourceItem[]): AudioQueueItem[] {
  return items
    .filter((item) => item.type === "audio" && !item.isDir)
    .map((item) => ({
      path: item.path,
      name: item.name,
      source: audioSource(item.path),
      size: item.size,
      modified: item.modified,
      origin: "directory" as const,
    }));
}

export function favoriteGroupAudioQueue(
  favorites: Favorite[],
  groupId: string
): AudioQueueItem[] {
  if (!groupId) return [];
  return favorites
    .filter(
      (favorite) =>
        favorite.groupId === groupId &&
        audioExtensions.has(extensionOf(favorite.path))
    )
    .sort((left, right) => left.order - right.order)
    .map((favorite) => ({
      path: favorite.path,
      name: favorite.name,
      source: audioSource(favorite.path),
      size: 0,
      modified: "",
      origin: "favorite-group" as const,
      groupId,
    }));
}

function extensionOf(path: string) {
  const name = path.split("/").pop() ?? "";
  const dot = name.lastIndexOf(".");
  return dot >= 0 ? name.slice(dot).toLowerCase() : "";
}
