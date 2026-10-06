type TagColorOwner = {
  id: string;
  color: string;
};

export const TAG_COLORS = [
  "#E5484D",
  "#D95876",
  "#F06A5B",
  "#F28C28",
  "#DDAA1D",
  "#D6BE21",
  "#86B83E",
  "#35A867",
  "#2AA889",
  "#28AFC0",
  "#3A9BD9",
  "#3F72D8",
  "#5B62D9",
  "#7656C9",
  "#9B4DB5",
  "#C34F90",
  "#758195",
] as const;

export function normalizeTagColor(color: string): string {
  const trimmed = color.trim().toUpperCase();
  return trimmed.startsWith("#") ? trimmed : `#${trimmed}`;
}

export function getUsedTagColors(
  tags: TagColorOwner[],
  excludingId?: string
): Set<string> {
  return new Set(
    tags
      .filter((tag) => tag.id !== excludingId)
      .map((tag) => normalizeTagColor(tag.color))
  );
}

export function isTagColorAvailable(
  tags: TagColorOwner[],
  color: string,
  excludingId?: string
): boolean {
  return !getUsedTagColors(tags, excludingId).has(normalizeTagColor(color));
}
