export const DEFAULT_SIDEBAR_MODULE_ORDER = [
  "user",
  "system-options",
  "favorites",
  "tags",
  "categories",
  "volumes",
  "logout",
] as const;

export const DEFAULT_SYSTEM_OPTION_ORDER = [
  "files",
  "search",
  "recent",
  "trash",
  "analysis",
  "tasks",
  "new-directory",
  "new-file",
] as const;

export type SidebarModuleId = (typeof DEFAULT_SIDEBAR_MODULE_ORDER)[number];
export type SystemOptionId = (typeof DEFAULT_SYSTEM_OPTION_ORDER)[number];

export interface SidebarPreferences {
  desktopCollapsed: boolean;
  moduleOrder: SidebarModuleId[];
  systemOptionOrder: SystemOptionId[];
  tagOrder: string[];
  categoryOrder: string[];
  categoryPathOrder: Record<string, string[]>;
  volumeOrder: string[];
}

export const DEFAULT_SIDEBAR_PREFERENCES: SidebarPreferences = {
  desktopCollapsed: false,
  moduleOrder: [...DEFAULT_SIDEBAR_MODULE_ORDER],
  systemOptionOrder: [...DEFAULT_SYSTEM_OPTION_ORDER],
  tagOrder: [],
  categoryOrder: ["nas-root"],
  categoryPathOrder: {},
  volumeOrder: [],
};

function stringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return [
    ...new Set(
      value.filter((item): item is string => typeof item === "string")
    ),
  ];
}

function knownOrder<T extends string>(
  value: unknown,
  defaults: readonly T[]
): T[] {
  const allowed = new Set<string>(defaults);
  const saved = stringArray(value).filter((id): id is T => allowed.has(id));
  return [...saved, ...defaults.filter((id) => !saved.includes(id))];
}

export function normalizeSidebarPreferences(
  value: string | Partial<SidebarPreferences> | null | undefined
): SidebarPreferences {
  let source: Partial<SidebarPreferences> = {};
  if (typeof value === "string" && value.trim()) {
    try {
      source = JSON.parse(value) as Partial<SidebarPreferences>;
    } catch {
      source = {};
    }
  } else if (value && typeof value === "object") {
    source = value;
  }

  const categoryPathOrder: Record<string, string[]> = {};
  if (
    source.categoryPathOrder &&
    typeof source.categoryPathOrder === "object"
  ) {
    for (const [groupId, order] of Object.entries(source.categoryPathOrder)) {
      categoryPathOrder[groupId] = stringArray(order);
    }
  }

  const categoryOrder = stringArray(source.categoryOrder);
  if (!categoryOrder.includes("nas-root")) categoryOrder.unshift("nas-root");

  return {
    desktopCollapsed: source.desktopCollapsed === true,
    moduleOrder: knownOrder(source.moduleOrder, DEFAULT_SIDEBAR_MODULE_ORDER),
    systemOptionOrder: knownOrder(
      source.systemOptionOrder,
      DEFAULT_SYSTEM_OPTION_ORDER
    ),
    tagOrder: stringArray(source.tagOrder),
    categoryOrder,
    categoryPathOrder,
    volumeOrder: stringArray(source.volumeOrder),
  };
}

export function reorderByPreference<T>(
  items: readonly T[],
  preference: readonly string[],
  getId: (item: T) => string
): T[] {
  const available = new Map(items.map((item) => [getId(item), item]));
  const result: T[] = [];

  for (const id of preference) {
    const item = available.get(id);
    if (!item) continue;
    result.push(item);
    available.delete(id);
  }

  return [...result, ...available.values()];
}

export function reorderPreference(
  order: readonly string[],
  draggedId: string,
  targetId: string,
  position: "before" | "after" = "before"
): string[] {
  if (draggedId === targetId) return [...order];
  const next = order.filter((id) => id !== draggedId);
  const targetIndex = next.indexOf(targetId);
  if (targetIndex < 0) return [...next, draggedId];
  next.splice(targetIndex + (position === "after" ? 1 : 0), 0, draggedId);
  return next;
}
