import { createURL } from "@/api/utils";
import type { Favorite } from "@/stores/favorites";
import type { ResourceItem } from "@/types/file";
import { favoriteIdentity, favoriteWirePath } from "./favoritePersistence";

export interface AudioQueueItem {
  path: string;
  name: string;
  source: string;
  size: number;
  modified: string;
  origin: "directory" | "favorite-group";
  groupId?: string;
  wirePath?: string;
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

export function audioSource(path: string, wirePath?: string) {
  const wire = favoriteWirePath({ path, wirePath });
  if (!wire) throw new Error("音频原始路径无法确认，请重新选择");
  // createURL encodes plain text. Append an already validated wire path after
  // it has built the same-origin base, without double-encoding opaque bytes.
  const base = createURL("api/raw", { inline: "true" });
  const query = base.indexOf("?");
  return query < 0
    ? base + wire
    : base.slice(0, query) + wire + base.slice(query);
}

export function audioQueueIdentity(
  item: Pick<AudioQueueItem, "path" | "wirePath">
) {
  return favoriteIdentity(item);
}

export function directoryAudioQueue(items: ResourceItem[]): AudioQueueItem[] {
  return items
    .filter((item) => item.type === "audio" && !item.isDir)
    .filter((item) => favoriteWirePath(item) !== null)
    .map((item) => ({
      path: item.path,
      wirePath: favoriteWirePath(item)!,
      name: item.name,
      source: audioSource(item.path, item.wirePath),
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
        audioExtensions.has(extensionOf(favorite.path)) &&
        favoriteWirePath(favorite) !== null
    )
    .sort((left, right) => left.order - right.order)
    .map((favorite) => ({
      path: favorite.path,
      wirePath: favoriteWirePath(favorite)!,
      name: favorite.name,
      source: audioSource(favorite.path, favoriteWirePath(favorite)!),
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
