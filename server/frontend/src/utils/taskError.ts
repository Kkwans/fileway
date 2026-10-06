const TASK_ERROR_SUMMARY_MAX_LENGTH = 160;

/**
 * Keep task rows scannable without discarding the original diagnostic text.
 * The full error remains available in the task details disclosure.
 */
export function summarizeTaskError(error: string | null | undefined): string {
  const firstLine =
    error
      ?.split(/\r?\n/)
      .map((line) => line.trim())
      .find(Boolean)
      ?.replace(/\s+/g, " ") ?? "";

  if (!firstLine) return "任务失败，未提供详细原因";
  if (firstLine.length <= TASK_ERROR_SUMMARY_MAX_LENGTH) return firstLine;

  return `${firstLine.slice(0, TASK_ERROR_SUMMARY_MAX_LENGTH - 1).trimEnd()}…`;
}
