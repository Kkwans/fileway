/**
 * Shared file icon utility - maps file extensions to the legacy Material
 * names used by older surfaces. The listing uses getResourceIconName below
 * so its primary resource icon can use the local AppIcon family instead.
 */

import type { AppIconName } from "@/components/ui/iconRegistry";

const APP_ICON_EXTENSIONS: Record<string, AppIconName> = {
  // Keep the extension mapping intentionally semantic rather than mirroring
  // every legacy Material glyph. Unknown extensions use the neutral file icon.
  pdf: "file-text",
  doc: "file-text",
  docx: "file-text",
  txt: "file-text",
  md: "file-text",
  mdx: "file-text",
  rtf: "file-text",
  odt: "file-text",
  pages: "file-text",
  xls: "file-spreadsheet",
  xlsx: "file-spreadsheet",
  csv: "file-spreadsheet",
  ods: "file-spreadsheet",
  numbers: "file-spreadsheet",
  tsv: "file-spreadsheet",
  ppt: "file-text",
  pptx: "file-text",
  odp: "file-text",
  keynote: "file-text",
  zip: "file-archive",
  rar: "file-archive",
  "7z": "file-archive",
  tar: "file-archive",
  gz: "file-archive",
  bz2: "file-archive",
  xz: "file-archive",
  zst: "file-archive",
  lz4: "file-archive",
  cab: "file-archive",
};

/**
 * Return the semantic local icon used by the primary file listing.
 *
 * Media items still render their real thumbnail before this function is used;
 * this function only controls the fallback/native resource icon. Keeping the
 * mapping here makes all four listing layouts use the same icon family and
 * avoids another CSS pseudo-element classification table.
 */
export function getResourceIconName(
  fileName: string,
  type: string,
  isDir = false
): AppIconName {
  if (isDir || type === "dir") return "folder";
  if (type === "image") return "file-image";
  if (type === "video") return "file-video";
  if (type === "audio") return "file-music";
  if (type === "text" || type === "textImmutable" || type === "pdf") {
    return "file-text";
  }

  const extension = fileName.split(".").pop()?.toLowerCase() ?? "";
  if (APP_ICON_EXTENSIONS[extension]) return APP_ICON_EXTENSIONS[extension];

  // Code and configuration are common in NAS folders even when the backend
  // reports them as a generic blob during directory listing.
  if (
    /^(c|cc|cpp|cs|css|go|h|hpp|html?|java|js|jsx|json|kt|lua|php|py|rb|rs|scss|sh|sql|ts|tsx|vue|xml|ya?ml|toml|conf|ini|env)$/.test(
      extension
    )
  ) {
    return "file-code";
  }
  return "file-type";
}

const EXT_ICON_MAP: Record<string, string> = {
  // Documents
  pdf: "picture_as_pdf",
  doc: "description",
  docx: "description",
  txt: "article",
  md: "article",
  mdx: "article",
  rtf: "description",
  odt: "description",
  pages: "description",

  // Spreadsheets
  xls: "table_chart",
  xlsx: "table_chart",
  csv: "table_chart",
  ods: "table_chart",
  numbers: "table_chart",
  tsv: "table_chart",

  // Presentations
  ppt: "slideshow",
  pptx: "slideshow",
  odp: "slideshow",
  keynote: "slideshow",

  // Audio
  mp3: "audiotrack",
  wav: "audiotrack",
  flac: "audiotrack",
  aac: "audiotrack",
  ogg: "audiotrack",
  wma: "audiotrack",
  m4a: "audiotrack",
  opus: "audiotrack",
  amr: "audiotrack",

  // Video
  mp4: "movie",
  avi: "movie",
  mkv: "movie",
  mov: "movie",
  wmv: "movie",
  flv: "movie",
  webm: "movie",
  m4v: "movie",
  mpeg: "movie",
  mpg: "movie",
  "3gp": "movie",

  // Images
  jpg: "image",
  jpeg: "image",
  png: "image",
  gif: "image",
  webp: "image",
  svg: "image",
  bmp: "image",
  ico: "image",
  tiff: "image",
  tif: "image",
  avif: "image",
  heic: "image",
  heif: "image",
  raw: "image",
  cr2: "image",
  nef: "image",
  psd: "image",
  ai: "image",
  eps: "image",

  // Archives
  zip: "folder_zip",
  rar: "folder_zip",
  "7z": "folder_zip",
  tar: "folder_zip",
  gz: "folder_zip",
  bz2: "folder_zip",
  xz: "folder_zip",
  zst: "folder_zip",
  lz4: "folder_zip",
  cab: "folder_zip",

  // Code
  js: "code",
  mjs: "code",
  cjs: "code",
  ts: "code",
  jsx: "code",
  tsx: "code",
  py: "code",
  pyw: "code",
  java: "code",
  go: "code",
  html: "code",
  htm: "code",
  css: "code",
  scss: "code",
  sass: "code",
  less: "code",
  vue: "code",
  svelte: "code",
  astro: "code",
  rs: "code",
  rb: "code",
  php: "code",
  c: "code",
  cpp: "code",
  cc: "code",
  h: "code",
  hpp: "code",
  swift: "code",
  kt: "code",
  kts: "code",
  sql: "code",
  sh: "code",
  bash: "code",
  zsh: "code",
  fish: "code",
  ps1: "code",
  bat: "code",
  cmd: "code",
  lua: "code",
  r: "code",
  scala: "code",
  clj: "code",
  ex: "code",
  exs: "code",
  erl: "code",
  hs: "code",
  dart: "code",
  zig: "code",
  nim: "code",
  v: "code",
  graphql: "code",
  gql: "code",
  proto: "code",
  tf: "code",
  hcl: "code",
  dockerfile: "code",
  makefile: "code",
  cmake: "code",
  gradle: "code",
  groovy: "code",

  // Data / Config
  json: "data_object",
  jsonc: "data_object",
  json5: "data_object",
  xml: "data_object",
  yaml: "data_object",
  yml: "data_object",
  toml: "data_object",
  ini: "data_object",
  cfg: "data_object",
  conf: "data_object",
  env: "data_object",
  properties: "data_object",

  // Fonts
  ttf: "font_download",
  otf: "font_download",
  woff: "font_download",
  woff2: "font_download",
  eot: "font_download",

  // 3D / CAD
  stl: "view_in_ar",
  obj: "view_in_ar",
  fbx: "view_in_ar",
  blend: "view_in_ar",
  dwg: "view_in_ar",

  // Ebooks
  epub: "menu_book",
  mobi: "menu_book",
  azw: "menu_book",
  azw3: "menu_book",

  // Certificates / Keys
  pem: "security",
  crt: "security",
  key: "vpn_key",
  p12: "security",
  pfx: "security",

  // Logs
  log: "receipt_long",

  // Subtitles
  srt: "subtitles",
  vtt: "subtitles",
  ass: "subtitles",
  ssa: "subtitles",

  // Other
  exe: "settings_ethernet",
  msi: "settings_ethernet",
  dmg: "settings_ethernet",
  pkg: "settings_ethernet",
  deb: "settings_ethernet",
  rpm: "settings_ethernet",
  appimage: "settings_ethernet",
  iso: "settings_ethernet",
  img: "settings_ethernet",
  bin: "settings_ethernet",
  torrent: "cloud_download",
  url: "link",
  lnk: "link",
};

/**
 * Get Material Icon name for a file based on its extension.
 * @param fileName - The file name (e.g. "photo.jpg")
 * @param isDir - Whether the item is a directory
 * @returns Material Icon name (e.g. "image", "folder", "insert_drive_file")
 */
export function getFileIcon(fileName: string, isDir?: boolean): string {
  if (isDir) return "folder";
  const ext = fileName.split(".").pop()?.toLowerCase() || "";
  return EXT_ICON_MAP[ext] || "insert_drive_file";
}

/**
 * Check if a path looks like a file (has a recognized file extension).
 * Used by Sidebar to determine whether to navigate as file or directory.
 */
export function isFileByExtension(path: string): boolean {
  const lastSegment = path.split("/").pop() || "";
  const dotIdx = lastSegment.lastIndexOf(".");
  if (dotIdx <= 0) return false;
  const ext = lastSegment.slice(dotIdx + 1).toLowerCase();
  return ext in EXT_ICON_MAP;
}

// --- Previewable / Text file classification ---

/** File types that are always text-based (by backend type string) */
export const TEXT_TYPES = ["text", "textImmutable"] as const;

/** File extensions treated as text files (for preview and editor) */
export const TEXT_EXTENSIONS: ReadonlySet<string> = new Set([
  ".txt",
  ".md",
  ".markdown",
  ".json",
  ".xml",
  ".yml",
  ".yaml",
  ".csv",
  ".log",
  ".ini",
  ".conf",
  ".cfg",
  ".sh",
  ".bash",
  ".py",
  ".js",
  ".ts",
  ".go",
  ".java",
  ".c",
  ".cpp",
  ".h",
  ".css",
  ".html",
  ".vue",
  ".rs",
  ".rb",
  ".php",
  ".sql",
  ".toml",
  ".env",
  ".gitignore",
  ".dockerfile",
  ".makefile",
  ".srt",
  ".vtt",
  ".ass",
  ".scss",
  ".less",
  ".jsx",
  ".tsx",
  ".swift",
  ".kt",
  ".lua",
  ".r",
  ".scala",
  ".dart",
  ".zig",
  ".graphql",
  ".proto",
  ".tf",
  ".hcl",
  ".gradle",
  ".groovy",
  ".ex",
  ".exs",
  ".hs",
  ".properties",
  ".toml",
  ".tscn",
  ".tres",
]);

/** File extensions that can be previewed (text + markdown + pdf + epub + subtitles) */
export const PREVIEWABLE_EXTENSIONS: ReadonlySet<string> = new Set([
  ...TEXT_EXTENSIONS,
  ".pdf",
  ".epub",
]);

/** Backend file types that support preview (media + text + blob) */
export const PREVIEWABLE_TYPES: ReadonlySet<string> = new Set([
  "image",
  "video",
  "audio",
  "blob",
  "text",
  "textImmutable",
]);

/**
 * Check if a file can be previewed based on its type and extension.
 * @param type - Backend file type string (e.g. "text", "image")
 * @param extension - File extension with dot (e.g. ".md", ".pdf")
 */
export function isPreviewable(type: string, extension?: string): boolean {
  if (PREVIEWABLE_TYPES.has(type)) return true;
  const ext = (extension || "").toLowerCase();
  return PREVIEWABLE_EXTENSIONS.has(ext);
}

/**
 * Check if a file is a text file based on its type and extension.
 */
export function isTextFile(type: string, extension?: string): boolean {
  const ext = (extension || "").toLowerCase();
  // Markdown files are handled separately (rendered)
  if (ext === ".md" || ext === ".markdown") return false;
  if (TEXT_TYPES.includes(type as (typeof TEXT_TYPES)[number])) return true;
  return TEXT_EXTENSIONS.has(ext);
}
