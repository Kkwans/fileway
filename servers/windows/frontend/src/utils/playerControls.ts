const STORAGE_KEY = "win-file-browser-controls-timeout-v1";

/** Default when account preference is unset. */
export const DEFAULT_CONTROLS_TIMEOUT_MS = 4000;

export function readControlsTimeoutMs(): number {
  const fallback = DEFAULT_CONTROLS_TIMEOUT_MS;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return fallback;
    const n = Number(raw);
    if (!Number.isFinite(n) || n < 0 || n > 20000) return fallback;
    return Math.round(n);
  } catch {
    return fallback;
  }
}

export function writeControlsTimeoutMs(ms: number): number {
  const clamped = Math.min(20000, Math.max(0, Math.round(ms || 0)));
  try {
    localStorage.setItem(STORAGE_KEY, String(clamped));
  } catch {}
  return clamped;
}

/**
 * Account preference wins when present.
 * nil/undefined → localStorage or default 4s.
 * 0 → never hide (video.js inactivityTimeout 0).
 * 1–20 → seconds.
 */
export function resolveControlsTimeoutMs(
  accountSec?: number | null
): number {
  if (accountSec === undefined || accountSec === null) {
    return readControlsTimeoutMs();
  }
  if (!Number.isFinite(accountSec) || accountSec < 0 || accountSec > 20) {
    return DEFAULT_CONTROLS_TIMEOUT_MS;
  }
  return Math.round(accountSec * 1000);
}
