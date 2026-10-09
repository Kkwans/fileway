import { batchRenameWireKey } from "./batchRename";
import { encodePath } from "./url";

export interface PersistedFavorite {
  id: string;
  path: string;
  name: string;
  groupId?: string;
  addedAt: number;
  order: number;
  wirePath?: string;
  pathVerified?: boolean;
}

type FavoritePath = Pick<
  PersistedFavorite,
  "path" | "wirePath" | "pathVerified"
>;

/** Match the NAS byte identity, never a display-name sibling. Legacy JSON
 * paths are literal UTF-8; lost-byte references cannot be reconstructed. */
export function favoriteWirePath(favorite: FavoritePath): string | null {
  const path = favorite.path.replace(/\/+$/, "") || "/";
  if (
    favorite.pathVerified === false ||
    !path.startsWith("/") ||
    (!favorite.wirePath &&
      (favorite.pathVerified === true || path.includes("\uFFFD")))
  )
    return null;
  try {
    const wire = favorite.wirePath || encodePath(path);
    batchRenameWireKey(wire);
    let decoded: string | undefined;
    try {
      decoded =
        wire.split("/").map(decodeURIComponent).join("/").replace(/\/+$/, "") ||
        "/";
    } catch {
      /* Opaque bytes have no UTF-8 display identity. */
    }
    if (decoded !== undefined && decoded !== path) return null;
    return wire.replace(/\/+$/, "") || "/";
  } catch {
    return null;
  }
}

export function favoriteIdentity(favorite: FavoritePath): string | null {
  const wire = favoriteWirePath(favorite);
  return wire === null ? null : batchRenameWireKey(wire);
}

/** A single request remains compatible with the old decoder, which ignores
 * wirePath but requires path. Opaque sources never send the display fallback. */
export function favoriteCreateBody(favorite: PersistedFavorite) {
  const wirePath = favoriteWirePath(favorite);
  if (!wirePath) throw new Error("收藏原始路径无法确认，请从文件列表重新选择");
  let compatible = false;
  try {
    compatible =
      wirePath.split("/").map(decodeURIComponent).join("/") === favorite.path;
  } catch {
    /* Never retry an opaque write using display text. */
  }
  return {
    wirePath,
    ...(compatible ? { path: favorite.path } : {}),
    name: favorite.name,
    groupId: favorite.groupId || "",
  };
}

export function replaceFavoriteByPath<T extends PersistedFavorite>(
  favorites: T[],
  created: T
): T[] {
  const identity = favoriteIdentity(created);
  return favorites.map((favorite) =>
    favorite.id === created.id ||
    (identity !== null && favoriteIdentity(favorite) === identity)
      ? created
      : favorite
  );
}

/** 为账号隔离浏览器兜底缓存，避免不同账号互相覆盖。 */
export function userStorageKey(
  prefix: string,
  userId: string | number
): string {
  return `${prefix}:user:${userId}`;
}

/**
 * 服务端暂时没有记录时，保留本地写入并标记为待同步；避免刷新直接丢失。
 * 一旦服务端存在记录，仍以服务端数据作为账号级事实来源。
 */
export function resolvePersistenceState<T>(
  remote: T[],
  cached: T[]
): { data: T[]; shouldSync: boolean } {
  if (remote.length === 0 && cached.length > 0) {
    return { data: cached, shouldSync: true };
  }
  return { data: remote, shouldSync: false };
}
