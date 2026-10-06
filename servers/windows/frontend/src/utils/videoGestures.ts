export type VideoGestureAxis = "seek" | "brightness" | "volume";

export function clampMediaValue(
  value: number,
  minimum: number,
  maximum: number
) {
  return Math.min(maximum, Math.max(minimum, value));
}

export function detectVideoGestureAxis(
  deltaX: number,
  deltaY: number,
  startX: number,
  width: number,
  threshold = 12
): VideoGestureAxis | null {
  if (Math.max(Math.abs(deltaX), Math.abs(deltaY)) < threshold) return null;
  if (Math.abs(deltaX) >= Math.abs(deltaY)) return "seek";
  return startX < width / 2 ? "brightness" : "volume";
}

export function seekFromSwipe(
  start: number,
  deltaX: number,
  width: number,
  duration: number
) {
  if (!Number.isFinite(duration) || duration <= 0 || width <= 0) return start;
  return clampMediaValue(start + (deltaX / width) * duration, 0, duration);
}

export function seekFromDoubleTap(
  current: number,
  tapX: number,
  width: number,
  duration: number,
  step = 10
) {
  const direction = tapX < width / 2 ? -1 : 1;
  const maximum =
    Number.isFinite(duration) && duration > 0 ? duration : Infinity;
  return clampMediaValue(current + direction * step, 0, maximum);
}

export function formatMediaTime(seconds: number) {
  if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
  const whole = Math.floor(seconds);
  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  const remainder = whole % 60;
  if (hours > 0) {
    return `${hours}:${minutes.toString().padStart(2, "0")}:${remainder
      .toString()
      .padStart(2, "0")}`;
  }
  return `${minutes}:${remainder.toString().padStart(2, "0")}`;
}

export function shouldShowResumePosition(seconds: number) {
  return Number.isFinite(seconds) && seconds >= 1;
}
