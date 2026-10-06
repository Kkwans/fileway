const STORAGE_UNITS = ["B", "KB", "MB", "GB", "TB", "PB"] as const;

/** SI (1000) units — original NAS style. */
export function formatStorageSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";

  const exponent = Math.min(
    Math.floor(Math.log(bytes) / Math.log(1000)),
    STORAGE_UNITS.length - 1
  );
  const value = bytes / 1000 ** exponent;
  const maximumFractionDigits = value >= 100 ? 0 : value >= 10 ? 1 : 2;
  const formatted = new Intl.NumberFormat("zh-CN", {
    maximumFractionDigits,
  }).format(value);
  return `${formatted} ${STORAGE_UNITS[exponent]}`;
}

const EXPLORER_UNITS = ["B", "KB", "MB", "GB", "TB", "PB"] as const;

/** Windows Explorer style binary units (1024), still labeled GB/TB. */
export function formatExplorerStorageSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  const exponent = Math.min(
    Math.floor(Math.log(bytes) / Math.log(1024)),
    EXPLORER_UNITS.length - 1
  );
  const value = bytes / 1024 ** exponent;
  const maximumFractionDigits = value >= 100 ? 0 : value >= 10 ? 1 : 2;
  const formatted = new Intl.NumberFormat("zh-CN", {
    maximumFractionDigits,
  }).format(value);
  return `${formatted} ${EXPLORER_UNITS[exponent]}`;
}

/** "169 GB 可用，共 1.81 TB" */
export function formatExplorerUsage(
  totalBytes: number,
  freeBytes?: number,
  usedBytes?: number
): string {
  const free =
    freeBytes != null && freeBytes > 0
      ? freeBytes
      : Math.max(0, (totalBytes || 0) - (usedBytes || 0));
  return `${formatExplorerStorageSize(free)} 可用，共 ${formatExplorerStorageSize(
    totalBytes
  )}`;
}
