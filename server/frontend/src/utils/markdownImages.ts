import { encodePath } from "@/utils/url";

const MAX_FILENAME_ATTEMPTS = 10_000;

const IMAGE_EXTENSIONS = new Set([
  ".avif",
  ".bmp",
  ".gif",
  ".heic",
  ".heif",
  ".ico",
  ".jpeg",
  ".jpg",
  ".png",
  ".svg",
  ".tif",
  ".tiff",
  ".webp",
]);

const MIME_EXTENSIONS: Record<string, string> = {
  "image/avif": ".avif",
  "image/bmp": ".bmp",
  "image/gif": ".gif",
  "image/heic": ".heic",
  "image/heif": ".heif",
  "image/jpeg": ".jpg",
  "image/png": ".png",
  "image/svg+xml": ".svg",
  "image/tiff": ".tiff",
  "image/webp": ".webp",
};

export interface StoredMarkdownImage {
  name: string;
  path: string;
  markdown: string;
}

export interface MarkdownImagePreviewContent {
  markdown: string;
  sources: Array<{ markdownSource: string; previewSource: string }>;
}

type ImageFile = Pick<File, "name" | "type">;
type ImageUpload = (path: string, file: File) => Promise<unknown>;

export function isMarkdownImageFile(file: ImageFile): boolean {
  if (file.type.toLocaleLowerCase().startsWith("image/")) return true;
  return IMAGE_EXTENSIONS.has(
    splitExtension(file.name).extension.toLocaleLowerCase()
  );
}

export function normalizeMarkdownImageName(file: ImageFile): string {
  const leaf = file.name.replaceAll("\\", "/").split("/").at(-1) ?? "";
  let cleaned = leaf.replace(/[\u0000-\u001f\u007f]/g, "").trim();
  if (cleaned === "" || cleaned === "." || cleaned === "..") {
    cleaned = `image${MIME_EXTENSIONS[file.type.toLocaleLowerCase()] ?? ""}`;
  }
  if (cleaned.startsWith(".")) cleaned = `image${cleaned}`;
  return cleaned;
}

export function markdownImageCandidateName(
  originalName: string,
  conflictIndex: number
): string {
  if (conflictIndex <= 0) return originalName;
  const { base, extension } = splitExtension(originalName);
  return `${base}-${conflictIndex + 1}${extension}`;
}

export function markdownImageTargetPath(
  documentPath: string,
  filename: string
): string {
  const normalized = normalizeDocumentPath(documentPath);
  const separator = normalized.lastIndexOf("/");
  const directory = separator <= 0 ? "" : normalized.slice(0, separator);
  return `${directory}/assets/${filename}`;
}

export function markdownImageLink(filename: string): string {
  const alt = filename
    .replaceAll("\\", "\\\\")
    .replaceAll("[", "\\[")
    .replaceAll("]", "\\]");
  const destination = filename.replace(/[%#?()[\]<>\s]/gu, (character) => {
    if (character === "(") return "%28";
    if (character === ")") return "%29";
    return encodeURIComponent(character);
  });
  return `![${alt}](./assets/${destination})`;
}

export function markdownImagePreviewSource(
  documentPath: string,
  source: string
): string | null {
  const trimmed = source.trim();
  if (
    trimmed === "" ||
    trimmed.startsWith("/") ||
    trimmed.startsWith("#") ||
    /^[a-z][a-z\d+.-]*:/iu.test(trimmed)
  ) {
    return null;
  }

  try {
    const normalized = normalizeDocumentPath(documentPath);
    const separator = normalized.lastIndexOf("/");
    const directory = separator <= 0 ? "/" : normalized.slice(0, separator);
    const encodedDirectory = encodePath(directory);
    const base = new URL(
      `https://markdown.invalid${encodedDirectory.endsWith("/") ? encodedDirectory : `${encodedDirectory}/`}`
    );
    const resolved = new URL(trimmed, base);
    if (resolved.origin !== base.origin) return null;
    return `/api/raw${resolved.pathname}${resolved.search}${resolved.hash}`;
  } catch {
    return null;
  }
}

export function markdownImagePreviewContent(
  documentPath: string,
  markdown: string
): MarkdownImagePreviewContent {
  const sources: MarkdownImagePreviewContent["sources"] = [];
  const rewritten = markdown.replace(
    /(!\[(?:\\.|[^\]\\])*\]\()([^\s)]+)(\))/gu,
    (match, prefix: string, markdownSource: string, suffix: string) => {
      const previewSource = markdownImagePreviewSource(
        documentPath,
        markdownSource
      );
      if (!previewSource) return match;
      sources.push({ markdownSource, previewSource });
      return `${prefix}${previewSource}${suffix}`;
    }
  );
  return { markdown: rewritten, sources };
}

export async function storeMarkdownImage(
  documentPath: string,
  file: File,
  upload: ImageUpload
): Promise<StoredMarkdownImage> {
  if (!isMarkdownImageFile(file)) {
    throw new Error(`“${file.name || "未命名文件"}”不是支持的图片`);
  }
  const originalName = normalizeMarkdownImageName(file);

  for (
    let conflictIndex = 0;
    conflictIndex < MAX_FILENAME_ATTEMPTS;
    conflictIndex++
  ) {
    const name = markdownImageCandidateName(originalName, conflictIndex);
    const path = markdownImageTargetPath(documentPath, name);
    try {
      await upload(path, file);
      return { name, path, markdown: markdownImageLink(name) };
    } catch (error) {
      if (isConflict(error)) continue;
      throw error;
    }
  }

  throw new Error(`“${originalName}”的可用重命名次数已用尽`);
}

function splitExtension(filename: string) {
  const dot = filename.lastIndexOf(".");
  if (dot <= 0 || dot === filename.length - 1) {
    return { base: filename, extension: "" };
  }
  return { base: filename.slice(0, dot), extension: filename.slice(dot) };
}

function normalizeDocumentPath(documentPath: string) {
  const normalized = `/${documentPath}`.replace(/\/+/g, "/");
  if (normalized === "/" || normalized.endsWith("/")) {
    throw new Error("Markdown 文档路径无效");
  }
  return normalized;
}

function isConflict(error: unknown) {
  return (
    typeof error === "object" &&
    error !== null &&
    "status" in error &&
    (error as { status?: unknown }).status === 409
  );
}
