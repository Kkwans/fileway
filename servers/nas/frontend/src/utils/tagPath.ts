import { normalizeFileKey } from "./fileListing";

/** 将接口和缓存中的原始 NAS 路径统一为可比较的绝对路径。 */
export function normalizeTagPath(path: string): string {
  return normalizeFileKey(path.trim());
}

export function isDescendantPath(path: string, parent: string): boolean {
  return parent === "/" ? path.startsWith("/") : path.startsWith(`${parent}/`);
}

/** Rewrite an exact path or directory subtree while preserving Linux case. */
export function rewriteTagPathPrefix(
  candidate: string,
  from: string,
  to: string
): string | null {
  const normalizedCandidate = normalizeTagPath(candidate);
  const normalizedFrom = normalizeTagPath(from);
  const normalizedTo = normalizeTagPath(to);
  if (
    normalizedCandidate !== normalizedFrom &&
    !isDescendantPath(normalizedCandidate, normalizedFrom)
  ) {
    return null;
  }
  if (normalizedCandidate === normalizedFrom) return normalizedTo;
  const suffix = normalizedCandidate.slice(normalizedFrom.length);
  return normalizeTagPath(
    normalizedTo === "/" ? suffix : `${normalizedTo}${suffix}`
  );
}
