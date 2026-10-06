import type { Sorting } from "@/types/user";

export const GUEST_SORTING_KEY = "nas-file-browser-guest-sorting-v1";
const SORT_FIELDS = new Set(["name", "size", "modified", "type"]);

export function normalizeSorting(value: unknown): Sorting | null {
  if (!value || typeof value !== "object") return null;
  const candidate = value as Partial<Sorting>;
  if (
    !SORT_FIELDS.has(candidate.by ?? "") ||
    typeof candidate.asc !== "boolean"
  ) {
    return null;
  }
  return { by: candidate.by!, asc: candidate.asc };
}

export function readGuestSorting(
  storage: Pick<Storage, "getItem"> = localStorage
): Sorting | null {
  try {
    const raw = storage.getItem(GUEST_SORTING_KEY);
    return raw ? normalizeSorting(JSON.parse(raw)) : null;
  } catch {
    return null;
  }
}

export function writeGuestSorting(
  sorting: Sorting,
  storage: Pick<Storage, "setItem"> = localStorage
): void {
  storage.setItem(GUEST_SORTING_KEY, JSON.stringify(sorting));
}
