const STORAGE_UNITS = ["B", "KB", "MB", "GB", "TB", "PB"] as const;

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
