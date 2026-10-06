import { normalizeFileKey } from "./fileListing";

export const FILE_DRAG_MIME = "application/x-nas-file-paths";

// Some browser automation layers and older WebViews expose the internal MIME
// type during dragover/drop but discard the value written during dragstart.
// Keep a same-document fallback for that short drag lifecycle. Native drags
// still use DataTransfer as the source of truth, and the value is cleared on
// drop/dragend by the listing components.
let sameDocumentFallback: string[] = [];

/**
 * Returns true for files coming from the operating system, not an internal
 * drag created by this application. The listing uses this distinction to keep
 * its upload drop-zone behavior while handling app-to-app moves separately.
 */
export function isExternalFileDrag(
  types?: DataTransfer["types"] | null
): boolean {
  if (!types) return false;
  const values = Array.from(types as ArrayLike<string>);
  return (
    !values.includes(FILE_DRAG_MIME) &&
    (values.includes("Files") || values.includes("text/uri-list"))
  );
}

/** Writes an internal file drag payload without exposing encoded UI routes. */
export function writeFileDragPayload(
  dataTransfer: DataTransfer | null,
  paths: string[]
) {
  if (!dataTransfer) return;
  const normalized = [...new Set(paths.map(normalizeFileDragKey))];
  if (normalized.length === 0) return;
  const payload = JSON.stringify(normalized);
  try {
    dataTransfer.setData(FILE_DRAG_MIME, payload);
    dataTransfer.setData("text/plain", normalized.join("\n"));
  } catch {
    // Safari/WebView can reject writes for a synthetic or protected transfer.
  }
  // Keep the fallback even when the write appears to succeed: Playwright and
  // some WebViews can expose the value during dragstart, then hand a fresh
  // empty DataTransfer to dragover/drop.
  sameDocumentFallback = normalized;
  dataTransfer.effectAllowed = "copyMove";
}

/** Reads only the payload created by this app; OS file drops stay uploads. */
export function readFileDragPayload(
  dataTransfer: DataTransfer | null
): string[] {
  if (!dataTransfer) return [];
  let raw = "";
  try {
    raw = dataTransfer.getData(FILE_DRAG_MIME);
  } catch {
    raw = "";
  }
  if (!raw) return sameDocumentFallbackFor(dataTransfer);
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return sameDocumentFallbackFor(dataTransfer);
    return [
      ...new Set(
        parsed
          .filter((value): value is string => typeof value === "string")
          .map(normalizeFileDragKey)
      ),
    ];
  } catch {
    return sameDocumentFallbackFor(dataTransfer);
  }
}

function sameDocumentFallbackFor(dataTransfer: DataTransfer) {
  const types = Array.from((dataTransfer.types ?? []) as ArrayLike<string>);
  return types.includes(FILE_DRAG_MIME) ? [...sameDocumentFallback] : [];
}

/** Clears the short-lived fallback after a drop or dragend event. */
export function clearFileDragPayload() {
  sameDocumentFallback = [];
}

/** A folder cannot receive itself or one of its own descendants. */
export function canDropFilePaths(paths: string[], target: string) {
  const normalizedTarget = normalizeFileDragKey(target);
  return paths.every((path) => {
    const source = normalizeFileDragKey(path);
    return (
      source !== normalizedTarget && !normalizedTarget.startsWith(`${source}/`)
    );
  });
}

/**
 * Internal drags use the browser route rather than a decoded filesystem path.
 * This is important for legacy filenames whose original bytes cannot be
 * represented as a JavaScript UTF-8 string. Keep the route opaque and only
 * normalize its trailing slash; retain the canonical path fallback for older
 * callers and tests.
 */
export function normalizeFileDragKey(value: string) {
  const trimmed = value.trim();
  if (trimmed === "/files" || trimmed.startsWith("/files/")) {
    return trimmed.replace(/\/+$/, "") || "/files";
  }
  return normalizeFileKey(trimmed);
}

export function fileNameFromPath(path: string) {
  return normalizeFileKey(path).split("/").at(-1) || "";
}
