/**
 * Decode paths that were persisted by older clients with URI-encoded UTF-8
 * segments. Invalid or non-text encodings are returned unchanged so a literal
 * percent sign in a filename is never treated as a fatal display error.
 */
export function displayPath(value: string): string {
  const fallback = value.replace(/\uFFFD+/gu, "（原始名称不可用）");
  if (!value.includes("%")) return fallback;

  try {
    const decoded = value
      .split("/")
      .map((segment) => decodeURIComponent(segment))
      .join("/");

    return decoded !== value && /[^\u0000-\u007f]/u.test(decoded)
      ? decoded.replace(/\uFFFD+/gu, "（原始名称不可用）")
      : fallback;
  } catch {
    return fallback;
  }
}
