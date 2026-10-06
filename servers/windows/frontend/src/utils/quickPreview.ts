import type { ResourceItem } from "@/types/file";
import { normalizeFileKey } from "./fileListing";
import { isPreviewable } from "./fileIcons";

export function getQuickPreviewItems(items: ResourceItem[]) {
  return items.filter(
    (item) => !item.isDir && isPreviewable(item.type, item.extension)
  );
}

export function findAdjacentQuickPreviewItem(
  items: ResourceItem[],
  currentPath: string,
  direction: -1 | 1
) {
  const previewable = getQuickPreviewItems(items);
  if (previewable.length < 2) return null;
  const currentKey = normalizeFileKey(currentPath);
  const currentIndex = previewable.findIndex(
    (item) => normalizeFileKey(item.path) === currentKey
  );
  if (currentIndex < 0) return null;
  const nextIndex =
    (currentIndex + direction + previewable.length) % previewable.length;
  return previewable[nextIndex];
}
