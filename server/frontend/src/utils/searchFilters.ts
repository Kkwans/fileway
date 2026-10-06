import type { SearchResult } from "@/types/file";

export const SEARCH_TYPE_OPTIONS = {
  image: {
    label: "图片",
    icon: "file-image",
    extensions: ["jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif"],
  },
  audio: {
    label: "音频",
    icon: "file-music",
    extensions: ["mp3", "m4a", "aac", "flac", "wav", "ogg", "opus"],
  },
  video: {
    label: "视频",
    icon: "file-video",
    extensions: ["mp4", "mkv", "avi", "mov", "webm", "m4v", "ts"],
  },
  pdf: {
    label: "PDF 文档",
    icon: "file-text",
    extensions: ["pdf"],
  },
  markdown: {
    label: "Markdown 文档",
    icon: "file-text",
    extensions: ["md", "markdown", "mdown", "mkd"],
  },
  config: {
    label: "配置文件",
    icon: "settings",
    extensions: [
      "json",
      "json5",
      "yaml",
      "yml",
      "toml",
      "ini",
      "conf",
      "config",
      "env",
      "xml",
      "properties",
    ],
  },
  code: {
    label: "代码文件",
    icon: "file-code",
    extensions: [
      "java",
      "py",
      "go",
      "js",
      "jsx",
      "ts",
      "tsx",
      "vue",
      "rs",
      "c",
      "h",
      "cpp",
      "hpp",
      "cs",
      "php",
      "rb",
      "kt",
      "kts",
      "swift",
      "sh",
      "ps1",
      "sql",
    ],
  },
} as const;

export type SearchType = keyof typeof SEARCH_TYPE_OPTIONS;

const TYPE_PATTERN = /(?:^|\s)type:(\w+)(?=\s|$)/i;

function extension(path: string): string {
  const name = path.split("/").pop() || path;
  if (name.startsWith(".") && !name.slice(1).includes(".")) {
    return name.slice(1).toLowerCase();
  }
  const index = name.lastIndexOf(".");
  return index >= 0 ? name.slice(index + 1).toLowerCase() : "";
}

export function detectSearchType(prompt: string): SearchType | null {
  const value = prompt.match(TYPE_PATTERN)?.[1]?.toLowerCase();
  return value && value in SEARCH_TYPE_OPTIONS ? (value as SearchType) : null;
}

export function applySearchType(
  prompt: string,
  type: SearchType | null
): string {
  const keyword = prompt.replace(TYPE_PATTERN, " ").replace(/\s+/g, " ").trim();
  return [type ? `type:${type}` : "", keyword].filter(Boolean).join(" ");
}

export function matchesSearchType(
  result: SearchResult,
  type: SearchType
): boolean {
  if (result.dir) return false;
  const suffix = extension(result.name || result.path);
  return (SEARCH_TYPE_OPTIONS[type].extensions as readonly string[]).includes(
    suffix
  );
}

export function filterSearchResults(
  results: readonly SearchResult[],
  type: SearchType | null
): SearchResult[] {
  return type
    ? results.filter((result) => matchesSearchType(result, type))
    : [...results];
}
