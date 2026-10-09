import type { ArchiveEntry } from "@/api/archive";
import { encodePath } from "./url";

/** Encode a known UTF-8 path once; never manufacture identity from lost bytes. */
export function archiveWirePath(
  path: string,
  wirePath?: string,
  verified?: boolean
) {
  if (verified === false || (!wirePath && path.includes("\uFFFD")))
    throw new Error("原始路径无法确认，请从文件列表重新选择压缩包或目录。");
  return wirePath || encodePath(path);
}

export function archiveWireEntries(entries: ArchiveEntry[]): ArchiveEntry[] {
  return entries.map((entry) => ({
    ...entry,
    path: archiveWirePath(entry.path, entry.wirePath, entry.pathVerified),
  }));
}

/** Tree keys use wire segments; display labels come from the server's matching
 * path segments, so same-display opaque siblings remain distinct. */
export function archiveDisplayPaths(entries: ArchiveEntry[]) {
  const labels = new Map<string, string>();
  for (const entry of entries) {
    const wire = archiveWirePath(
      entry.path,
      entry.wirePath,
      entry.pathVerified
    ).split("/");
    const display = entry.path.split("/");
    for (let count = 1; count <= wire.length; count++)
      labels.set(
        wire.slice(0, count).join("/"),
        display.slice(0, count).join("/")
      );
  }
  return labels;
}
