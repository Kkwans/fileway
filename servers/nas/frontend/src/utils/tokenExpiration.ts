const DEFAULT_TOKEN_EXPIRATION_MINUTES = 120;
export const MIN_TOKEN_EXPIRATION_MINUTES = 10;
export const MAX_TOKEN_EXPIRATION_MINUTES = 30 * 24 * 60;

export function clampTokenExpirationMinutes(value: number): number {
  const normalized = Number.isFinite(value)
    ? Math.round(value)
    : DEFAULT_TOKEN_EXPIRATION_MINUTES;
  return Math.min(
    MAX_TOKEN_EXPIRATION_MINUTES,
    Math.max(MIN_TOKEN_EXPIRATION_MINUTES, normalized)
  );
}

export function durationToMinutes(value: string): number {
  const match = value.trim().match(/^(\d+(?:\.\d+)?)(m|h)$/i);
  if (!match) return DEFAULT_TOKEN_EXPIRATION_MINUTES;
  const amount = Number(match[1]);
  const minutes = match[2].toLowerCase() === "h" ? amount * 60 : amount;
  return clampTokenExpirationMinutes(minutes);
}

export function minutesToDuration(value: number): string {
  const minutes = clampTokenExpirationMinutes(value);
  return minutes % 60 === 0 ? `${minutes / 60}h` : `${minutes}m`;
}
