export interface PersistedFavorite {
  id: string;
  path: string;
  name: string;
  groupId?: string;
  addedAt: number;
  order: number;
}

export function replaceFavoriteByPath<T extends PersistedFavorite>(
  favorites: T[],
  created: T
): T[] {
  return favorites.map((favorite) =>
    favorite.path === created.path ? created : favorite
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
