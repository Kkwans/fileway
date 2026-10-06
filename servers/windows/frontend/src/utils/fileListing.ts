export const FILE_VIEW_MODES = [
  "mosaic",
  "compact-grid",
  "details",
  "compact-list",
] as const;

export type FileViewMode = (typeof FILE_VIEW_MODES)[number];

export interface FileListingSortItem {
  isDir: boolean;
  name: string;
  type: string;
  size?: number;
  modified?: string;
}

export function normalizeFileKey(value: string): string {
  const segments: string[] = [];
  for (const segment of value.split("/")) {
    if (segment === "" || segment === ".") continue;
    if (segment === "..") {
      segments.pop();
      continue;
    }
    segments.push(segment);
  }

  return segments.length === 0 ? "/" : `/${segments.join("/")}`;
}

const FILE_TYPE_LABELS: Record<string, string> = {
  md: "Markdown 文件",
  db: "数据库文件",
  json: "JSON 文件",
  js: "JavaScript 文件",
  ts: "TypeScript 文件",
  vue: "Vue 组件文件",
  sh: "Shell 脚本",
  mp4: "视频文件",
  mp3: "音频文件",
  jpg: "JPEG 图片",
  jpeg: "JPEG 图片",
  png: "PNG 图片",
};

export function getFileTypeLabel({
  isDir,
  extension,
}: {
  isDir: boolean;
  extension?: string;
}): string {
  if (isDir) return "文件夹";
  const normalized = extension?.replace(/^\./, "").toLowerCase();
  if (!normalized) return "文件";
  return FILE_TYPE_LABELS[normalized] || `${normalized.toUpperCase()} 文件`;
}

export function normalizeViewMode(value: unknown): FileViewMode {
  if (value === "mosaic gallery") {
    return "details";
  }

  if (value === "list") return "details";
  if (value === "compact") return "compact-list";

  return FILE_VIEW_MODES.includes(value as FileViewMode)
    ? (value as FileViewMode)
    : "mosaic";
}

export function selectForContextMenu<T>(selected: T[], target: T): T[] {
  return selected.includes(target) ? selected : [target];
}

export interface ListingSortState {
  by: string;
  asc: boolean;
  overridden: boolean;
}

export function cycleListingSort(
  state: ListingSortState,
  by: string,
  accountDefault: Pick<ListingSortState, "by" | "asc">
): ListingSortState {
  if (!state.overridden || state.by !== by) {
    return { by, asc: true, overridden: true };
  }
  if (state.asc) {
    return { ...state, asc: false };
  }
  return { ...accountDefault, overridden: false };
}

export function sortItemsByType<T extends FileListingSortItem>(
  items: T[],
  ascending: boolean
): T[] {
  return [...items].sort((left, right) => {
    if (left.isDir !== right.isDir) {
      return left.isDir ? -1 : 1;
    }

    const typeComparison = left.type.localeCompare(right.type);
    const comparison =
      typeComparison === 0
        ? left.name.localeCompare(right.name)
        : typeComparison;

    return ascending ? comparison : -comparison;
  });
}

export function sortListingItems<T extends FileListingSortItem>(
  items: T[],
  by: string,
  ascending: boolean
): T[] {
  const collator = new Intl.Collator(undefined, {
    numeric: true,
    sensitivity: "base",
  });

  const isDriveLetterName = (name: string) => /^[A-Za-z]$/.test(name);

  return [...items].sort((left, right) => {
    // Windows computer root: always C, D, E… regardless of asc toggle.
    if (
      by === "name" &&
      left.isDir &&
      right.isDir &&
      isDriveLetterName(left.name) &&
      isDriveLetterName(right.name)
    ) {
      return left.name.toUpperCase().localeCompare(right.name.toUpperCase());
    }

    let comparison = 0;
    switch (by) {
      case "size":
        comparison = (left.size || 0) - (right.size || 0);
        break;
      case "modified":
        comparison =
          Date.parse(left.modified || "") - Date.parse(right.modified || "");
        break;
      case "type":
        comparison = collator.compare(left.type, right.type);
        if (comparison === 0)
          comparison = collator.compare(left.name, right.name);
        break;
      default:
        comparison = collator.compare(left.name, right.name);
    }

    if (left.isDir !== right.isDir) {
      // Keep directories first in both directions (Explorer-like).
      return left.isDir ? -1 : 1;
    }

    return ascending ? comparison : -comparison;
  });
}
