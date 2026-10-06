export const DEFAULT_CONTROLS_TIMEOUT_MS = 4000;
export function resolveControlsTimeoutMs(accountSec?: number | null): number {
  if (
    accountSec == null ||
    !Number.isFinite(accountSec) ||
    accountSec < 0 ||
    accountSec > 20
  )
    return DEFAULT_CONTROLS_TIMEOUT_MS;
  return Math.round(accountSec * 1000);
}
