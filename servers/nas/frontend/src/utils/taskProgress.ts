import type { MediaProgress, TaskStatus } from "@/api/tasks";

export type TaskProgressMode = "media" | "bytes" | "items" | "indeterminate";

export interface TaskProgressInput {
  processedBytes: number;
  totalBytes: number;
  processedItems: number;
  totalItems: number;
  media?: MediaProgress;
}

export interface TaskProgress {
  mode: TaskProgressMode;
  value?: number;
  max?: number;
}

/** Prefer byte progress, then item progress, and otherwise be explicit that
 * the server has not reported a measurable total yet. */
export function getTaskProgress(input: TaskProgressInput): TaskProgress {
  if (input.media && input.media.durationSeconds > 0) {
    return {
      mode: "media",
      value: clamp(
        input.media.processedSeconds,
        0,
        input.media.durationSeconds
      ),
      max: input.media.durationSeconds,
    };
  }
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

export function mediaTaskEstimate(
  media: MediaProgress,
  status: TaskStatus,
  now: number
) {
  const elapsed = Math.max(0, (now - (media.startedAt || now)) / 1000);
  const stale =
    status === "running" &&
    media.phase !== "queued" &&
    now - media.updatedAt > 15000;
  const stalled =
    status === "running" &&
    media.phase === "encoding" &&
    now - (media.advancedAt || media.startedAt || media.updatedAt) > 30000;
  const canEstimate =
    status === "running" &&
    media.phase === "encoding" &&
    !stale &&
    !stalled &&
    media.speed > 0 &&
    media.durationSeconds > 0;
  const remaining = canEstimate
    ? Math.max(0, media.durationSeconds - media.processedSeconds) / media.speed
    : undefined;
  return {
    stale,
    stalled,
    remaining,
    total: remaining === undefined ? undefined : elapsed + remaining,
  };
}

export function formatMediaTime(seconds?: number) {
  if (seconds === undefined || !Number.isFinite(seconds)) return "估算中";
  const s = Math.max(0, Math.ceil(seconds));
  return [Math.floor(s / 3600), Math.floor((s % 3600) / 60), s % 60]
    .map((part) => String(part).padStart(2, "0"))
    .join(":");
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
