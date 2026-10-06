export type FavoriteDropPosition = "before" | "after";

export interface ReorderableFavorite {
  id: string;
  groupId?: string;
  order: number;
}

export function reorderFavoriteItems<T extends ReorderableFavorite>(
  items: readonly T[],
  draggedId: string,
  targetId: string,
  position: FavoriteDropPosition
): T[] {
  const ordered = [...items].sort((left, right) => left.order - right.order);
  const dragged = ordered.find((item) => item.id === draggedId);
  const target = ordered.find((item) => item.id === targetId);
  if (!dragged || !target || dragged.id === target.id) return ordered;

  const remaining = ordered.filter((item) => item.id !== draggedId);
  const targetIndex = remaining.findIndex((item) => item.id === targetId);
  const insertIndex = position === "after" ? targetIndex + 1 : targetIndex;
  const moved = { ...dragged, groupId: target.groupId || "" } as T;
  remaining.splice(insertIndex, 0, moved);
  return remaining.map((item, order) => ({ ...item, order }));
}

export function getFavoriteDropPosition(
  pointerY: number,
  top: number,
  height: number
): FavoriteDropPosition {
  return pointerY < top + height / 2 ? "before" : "after";
}
