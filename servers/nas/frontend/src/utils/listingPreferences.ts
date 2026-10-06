import type { ListingPreferences, PrefixRule } from "@/types/user";

export const LISTING_PREFERENCES_VERSION = 1;
export const BUILT_IN_PREFIXES = [".", "@", "#", "~", "$"] as const;
export const MAX_CUSTOM_PREFIX_RULES = 20;
export const MAX_PREFIX_LENGTH = 8;

export interface ListingItemLike {
  name: string;
  path: string;
  isDir: boolean;
}

export interface ListingSection<T extends ListingItemLike> {
  id: string;
  kind: "prefix" | "directories" | "files";
  label: string;
  prefix?: string;
  expanded: boolean;
  total: number;
  items: T[];
}

export function defaultListingPreferences(
  hideDotfiles = false
): ListingPreferences {
  return {
    version: LISTING_PREFERENCES_VERSION,
    prefixRules: BUILT_IN_PREFIXES.map((prefix, order) => ({
      prefix,
      visible: prefix !== "." || !hideDotfiles,
      expanded: true,
      order,
    })),
  };
}

export function validatePrefix(prefix: string): string | null {
  const characters = Array.from(prefix);
  if (characters.length < 1 || characters.length > MAX_PREFIX_LENGTH) {
    return `前缀长度必须为 1–${MAX_PREFIX_LENGTH} 个可见字符`;
  }
  if (characters.some((character) => /[\/\\\s\p{Cc}]/u.test(character))) {
    return "前缀不能包含空白、控制字符或路径分隔符";
  }
  return null;
}

export function normalizeListingPreferences(
  value: Partial<ListingPreferences> | null | undefined,
  hideDotfiles = false
): ListingPreferences {
  if (!value || value.version !== LISTING_PREFERENCES_VERSION) {
    return defaultListingPreferences(hideDotfiles);
  }

  const seen = new Set<string>();
  const rules = (Array.isArray(value.prefixRules) ? value.prefixRules : [])
    .filter((rule): rule is PrefixRule => {
      if (!rule || typeof rule.prefix !== "string") return false;
      if (validatePrefix(rule.prefix) || seen.has(rule.prefix)) return false;
      seen.add(rule.prefix);
      return true;
    })
    .map((rule, index) => ({
      prefix: rule.prefix,
      visible: rule.visible !== false,
      expanded: rule.expanded !== false,
      order: Number.isFinite(rule.order) ? rule.order : index,
    }))
    .sort((left, right) => left.order - right.order);

  const customRules = rules.filter(
    (rule) => !BUILT_IN_PREFIXES.includes(rule.prefix as never)
  );
  const limitedCustom = new Set(
    customRules.slice(0, MAX_CUSTOM_PREFIX_RULES).map((rule) => rule.prefix)
  );
  const keptRules = rules.filter(
    (rule) =>
      BUILT_IN_PREFIXES.includes(rule.prefix as never) ||
      limitedCustom.has(rule.prefix)
  );

  const defaults = defaultListingPreferences(hideDotfiles).prefixRules;
  for (const rule of defaults) {
    if (!keptRules.some((candidate) => candidate.prefix === rule.prefix)) {
      keptRules.push(rule);
    }
  }

  return {
    version: LISTING_PREFERENCES_VERSION,
    prefixRules: keptRules.map((rule, order) => ({ ...rule, order })),
  };
}

export function matchPrefixRule(
  name: string,
  rules: readonly PrefixRule[]
): PrefixRule | undefined {
  let matched: PrefixRule | undefined;
  for (const rule of rules) {
    if (!name.startsWith(rule.prefix)) continue;
    if (
      !matched ||
      Array.from(rule.prefix).length > Array.from(matched.prefix).length
    ) {
      matched = rule;
    }
  }
  return matched;
}

export function buildListingSections<T extends ListingItemLike>(
  directories: readonly T[],
  files: readonly T[],
  preferences: ListingPreferences
): ListingSection<T>[] {
  const normalized = normalizeListingPreferences(preferences);
  const buckets = new Map<string, { directories: T[]; files: T[] }>();
  const ordinaryDirectories: T[] = [];
  const ordinaryFiles: T[] = [];

  const collect = (item: T) => {
    const rule = matchPrefixRule(item.name, normalized.prefixRules);
    if (!rule) {
      (item.isDir ? ordinaryDirectories : ordinaryFiles).push(item);
      return;
    }
    if (!rule.visible) return;
    const bucket = buckets.get(rule.prefix) ?? { directories: [], files: [] };
    (item.isDir ? bucket.directories : bucket.files).push(item);
    buckets.set(rule.prefix, bucket);
  };
  directories.forEach(collect);
  files.forEach(collect);

  const sections: ListingSection<T>[] = [];
  for (const rule of normalized.prefixRules) {
    const bucket = buckets.get(rule.prefix);
    if (!bucket) continue;
    const items = [...bucket.directories, ...bucket.files];
    sections.push({
      id: `prefix:${rule.prefix}`,
      kind: "prefix",
      label: `以 “${rule.prefix}” 开头`,
      prefix: rule.prefix,
      expanded: rule.expanded,
      total: items.length,
      items,
    });
  }
  if (ordinaryDirectories.length > 0) {
    sections.push({
      id: "directories",
      kind: "directories",
      label: "文件夹",
      expanded: true,
      total: ordinaryDirectories.length,
      items: ordinaryDirectories,
    });
  }
  if (ordinaryFiles.length > 0) {
    sections.push({
      id: "files",
      kind: "files",
      label: "文件",
      expanded: true,
      total: ordinaryFiles.length,
      items: ordinaryFiles,
    });
  }
  return sections;
}

export function paginateListingSections<T extends ListingItemLike>(
  sections: readonly ListingSection<T>[],
  limit: number
): ListingSection<T>[] {
  let remaining = Math.max(0, limit);
  return sections
    .map((section) => {
      if (!section.expanded) return { ...section, items: [] };
      const items = section.items.slice(0, remaining);
      remaining -= items.length;
      return { ...section, items };
    })
    .filter((section) => section.kind === "prefix" || section.items.length > 0);
}
