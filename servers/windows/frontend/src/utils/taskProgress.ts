export type TaskProgressMode = "bytes" | "items" | "indeterminate";

export interface TaskProgressInput {
  processedBytes: number;
  totalBytes: number;
  processedItems: number;
  totalItems: number;
}

export interface TaskProgress {
  mode: TaskProgressMode;
  value?: number;
  max?: number;
}

/** Prefer byte progress, then item progress, and otherwise be explicit that
 * the server has not reported a measurable total yet. */
export function getTaskProgress(input: TaskProgressInput): TaskProgress {
  if (input.totalBytes > 0) {
    return {
      mode: "bytes",
      value: clamp(input.processedBytes, 0, input.totalBytes),
      max: input.totalBytes,
    };
  }

  if (input.totalItems > 0) {
    return {
      mode: "items",
      value: clamp(input.processedItems, 0, input.totalItems),
      max: input.totalItems,
    };
  }

  return { mode: "indeterminate" };
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, Number.isFinite(value) ? value : 0));
}

export function formatTaskBytes(value: number) {
  if (value < 1024) return `${Math.max(0, value)} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let size = Math.max(0, value);
  let index = -1;
  do {
    size /= 1024;
    index++;
  } while (size >= 1024 && index < units.length - 1);
  return `${size.toFixed(size >= 10 ? 0 : 1)} ${units[index]}`;
}
