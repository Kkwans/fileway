/** Classify wide cinema and standard frames by their 16:9 equivalent size. */
export function videoClassHeight(width: number, height: number): number {
  return Math.max(height, Math.round((width * 9) / 16));
}

export function videoResolutionLabel(width: number, height: number): string {
  if (!width && !height) return "—";
  const size = videoClassHeight(width, height);
  if (size >= 2000) return "4K";
  if (size >= 1300) return "2K";
  if (size >= 900) return "1080p";
  if (size >= 600) return "720p";
  if (size >= 400) return "480p";
  return width && height ? `${width}x${height}` : `${height}p`;
}
