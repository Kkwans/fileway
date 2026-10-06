import { defineStore } from "pinia";
import { ref, computed } from "vue";
import { useAuthStore } from "@/stores/auth";
import { fetchURL, StatusError } from "@/api/utils";
import {
  replaceFavoriteByPath,
  resolvePersistenceState,
  userStorageKey,
} from "@/utils/favoritePersistence";
import {
  reorderFavoriteItems,
  type FavoriteDropPosition,
} from "@/utils/sidebarFavorites";
import { normalizeTagPath, rewriteTagPathPrefix } from "@/utils/tagPath";

export interface FavoriteGroup {
  id: string;
  name: string;
  order: number;
  color?: string;
}

export interface Favorite {
  id: string;
  path: string;
  name: string;
  groupId?: string;
  addedAt: number;
  order: number;
}

const STORAGE_KEY = "nas-file-browser-favorites";
const GROUPS_STORAGE_KEY = "nas-file-browser-favorite-groups";
const API_BASE = "/api/favorites";
const GROUPS_API_BASE = "/api/favorites/groups";

export const useFavoritesStore = defineStore("favorites", () => {
  const authStore = useAuthStore();
  const favorites = ref<Favorite[]>([]);
  const groups = ref<FavoriteGroup[]>([]);
  const loaded = ref(false);

  const snapshotFavorites = () => favorites.value.map((item) => ({ ...item }));
  const snapshotGroups = () => groups.value.map((item) => ({ ...item }));

  function restoreFavorites(snapshot: Favorite[]) {
    favorites.value = snapshot.map((item) => ({ ...item }));
    saveToLocalStorage();
  }

  function restoreGroups(snapshot: FavoriteGroup[]) {
    groups.value = snapshot.map((item) => ({ ...item }));
    saveGroupsToLocalStorage();
  }

  // --- API helpers ---

  async function apiGet(): Promise<Favorite[] | null> {
    try {
      const res = await fetchURL(API_BASE, {});
      return await res.json();
    } catch {
      return null;
    }
  }

  async function apiCreate(fav: Favorite): Promise<Favorite | null> {
    try {
      const res = await fetchURL(API_BASE, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          path: fav.path,
          name: fav.name,
          groupId: fav.groupId || "",
        }),
      });
      return await res.json();
    } catch {
      return null;
    }
  }

  async function apiUpdate(
    id: string,
    fav: Partial<Favorite>
  ): Promise<{ ok: boolean; status?: number }> {
    try {
      await fetchURL(`${API_BASE}/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(fav),
      });
      return { ok: true };
    } catch (error) {
      return {
        ok: false,
        status: error instanceof StatusError ? error.status : undefined,
      };
    }
  }

  async function apiDelete(
    id: string
  ): Promise<{ ok: boolean; status?: number }> {
    try {
      await fetchURL(`${API_BASE}/${id}`, { method: "DELETE" });
      return { ok: true };
    } catch (error) {
      return {
        ok: false,
        status: error instanceof StatusError ? error.status : undefined,
      };
    }
  }

  async function apiReorder(orderedIds: string[]): Promise<boolean> {
    try {
      await fetchURL(`${API_BASE}/reorder`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ids: orderedIds }),
      });
      return true;
    } catch {
      return false;
    }
  }

  // --- Groups API helpers ---

  async function apiGetGroups(): Promise<FavoriteGroup[] | null> {
    try {
      const res = await fetchURL(GROUPS_API_BASE, {});
      return await res.json();
    } catch {
      return null;
    }
  }

  async function apiCreateGroup(
    group: Partial<FavoriteGroup>
  ): Promise<FavoriteGroup | null> {
    try {
      const res = await fetchURL(GROUPS_API_BASE, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(group),
      });
      return await res.json();
    } catch {
      return null;
    }
  }

  async function apiUpdateGroup(
    id: string,
    group: Partial<FavoriteGroup>
  ): Promise<{ ok: boolean; status?: number }> {
    try {
      await fetchURL(`${GROUPS_API_BASE}/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(group),
      });
      return { ok: true };
    } catch (error) {
      return {
        ok: false,
        status: error instanceof StatusError ? error.status : undefined,
      };
    }
  }

  async function apiDeleteGroup(
    id: string
  ): Promise<{ ok: boolean; status?: number }> {
    try {
      const res = await fetchURL(`${GROUPS_API_BASE}/${id}`, {
        method: "DELETE",
      });
      return { ok: res.ok };
    } catch (error) {
      const status = error instanceof StatusError ? error.status : undefined;
      return { ok: false, status };
    }
  }

  async function apiReorderGroups(orderedIds: string[]): Promise<boolean> {
    try {
      await fetchURL(`${GROUPS_API_BASE}/reorder`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ids: orderedIds }),
      });
      return true;
    } catch {
      return false;
    }
  }

  // --- localStorage helpers ---

  function scopedStorageKey(prefix: string): string {
    return userStorageKey(prefix, authStore.user?.id ?? "anonymous");
  }

  function saveToLocalStorage() {
    try {
      localStorage.setItem(
        scopedStorageKey(STORAGE_KEY),
        JSON.stringify(favorites.value)
      );
    } catch {}
  }

  function saveGroupsToLocalStorage() {
    try {
      localStorage.setItem(
        scopedStorageKey(GROUPS_STORAGE_KEY),
        JSON.stringify(groups.value)
      );
    } catch {}
  }

  function loadFromLocalStorage(): Favorite[] {
    try {
      const saved = localStorage.getItem(scopedStorageKey(STORAGE_KEY));
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  }

  function loadGroupsFromLocalStorage(): FavoriteGroup[] {
    try {
      const saved = localStorage.getItem(scopedStorageKey(GROUPS_STORAGE_KEY));
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  }

  // --- Public methods ---

  async function loadFavorites() {
    const cachedGroups = loadGroupsFromLocalStorage();
    const apiGroups = await apiGetGroups();
    const groupState = resolvePersistenceState(apiGroups ?? [], cachedGroups);
    groups.value = apiGroups === null ? cachedGroups : groupState.data;
    saveGroupsToLocalStorage();

    const cachedFavorites = loadFromLocalStorage();
    const apiData = await apiGet();
    const favoriteState = resolvePersistenceState(
      apiData ?? [],
      cachedFavorites
    );
    favorites.value = apiData === null ? cachedFavorites : favoriteState.data;
    saveToLocalStorage();

    if (groupState.shouldSync) await syncGroups();
    if (favoriteState.shouldSync) await syncFavorites();
    loaded.value = true;
  }

  /**
   * 兼容旧版本留下的临时 ID：变更接口返回 404 时重新读取当前用户的
   * 服务端记录；若服务端暂时为空，则把本地缓存重新同步并取得真实 ID。
   */
  async function refreshAfterMutation(preferRemote = false) {
    const [remoteGroups, remoteFavorites] = await Promise.all([
      apiGetGroups(),
      apiGet(),
    ]);
    if (remoteGroups === null || remoteFavorites === null) return;

    if (!preferRemote && remoteGroups.length === 0 && groups.value.length > 0) {
      await syncGroups();
    } else {
      groups.value = remoteGroups;
    }

    if (
      !preferRemote &&
      remoteFavorites.length === 0 &&
      favorites.value.length > 0
    ) {
      await syncFavorites();
    } else {
      favorites.value = remoteFavorites;
    }

    saveGroupsToLocalStorage();
    saveToLocalStorage();
  }

  function saveFavorites() {
    saveToLocalStorage();
  }

  async function addFavorite(path: string, name: string, groupId?: string) {
    const cleaned = path.replace(/\/+$/, "");
    if (favorites.value.some((f) => f.path === cleaned)) return;

    const snapshot = snapshotFavorites();

    const newFav: Favorite = {
      id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
      path: cleaned,
      name,
      groupId: groupId || "",
      addedAt: Date.now(),
      order: favorites.value.length,
    };
    favorites.value.push(newFav);
    saveToLocalStorage();
    const created = await apiCreate(newFav);
    if (created) {
      favorites.value = replaceFavoriteByPath(favorites.value, created);
      saveToLocalStorage();
    } else {
      restoreFavorites(snapshot);
    }
  }

  async function removeFavorite(id: string) {
    const snapshot = snapshotFavorites();
    favorites.value = favorites.value.filter((f) => f.id !== id);
    favorites.value.forEach((f, i) => (f.order = i));
    saveToLocalStorage();
    const result = await apiDelete(id);
    if (!result.ok) {
      restoreFavorites(snapshot);
      if (result.status === 404) await refreshAfterMutation();
    }
  }

  async function removeByPath(path: string) {
    const cleaned = path.replace(/\/+$/, "");
    const target = favorites.value.find((f) => f.path === cleaned);
    if (!target) return;

    const snapshot = snapshotFavorites();
    favorites.value = favorites.value.filter((f) => f.path !== cleaned);
    favorites.value.forEach((f, i) => (f.order = i));
    saveToLocalStorage();
    const result = await apiDelete(target.id);
    if (!result.ok) {
      restoreFavorites(snapshot);
      if (result.status === 404) await refreshAfterMutation();
    }
  }

  function isFavorite(path: string): boolean {
    const cleaned = path.replace(/\/+$/, "");
    return favorites.value.some((f) => f.path === cleaned);
  }

  function applyPathRewrite(from: string, to: string) {
    let changed = false;
    favorites.value = favorites.value.map((favorite) => {
      const rewritten = rewriteTagPathPrefix(favorite.path, from, to);
      if (rewritten === null || rewritten === favorite.path) return favorite;
      changed = true;
      return { ...favorite, path: rewritten };
    });
    if (changed) saveToLocalStorage();
  }

  function applyPathRemoval(prefix: string) {
    const normalizedPrefix = normalizeTagPath(prefix);
    const previousLength = favorites.value.length;
    favorites.value = favorites.value.filter((favorite) => {
      const normalized = normalizeTagPath(favorite.path);
      return (
        normalized !== normalizedPrefix &&
        rewriteTagPathPrefix(normalized, normalizedPrefix, "/") === null
      );
    });
    if (favorites.value.length === previousLength) return;
    favorites.value.forEach((favorite, index) => (favorite.order = index));
    saveToLocalStorage();
  }

  async function toggleFavorite(path: string, name: string, groupId?: string) {
    const cleaned = path.replace(/\/+$/, "");
    const existing = favorites.value.find((f) => f.path === cleaned);
    if (existing) {
      await removeFavorite(existing.id);
    } else {
      await addFavorite(cleaned, name, groupId);
    }
  }

  async function moveFavoriteToGroup(favId: string, groupId: string) {
    const fav = favorites.value.find((f) => f.id === favId);
    if (!fav) return;
    const snapshot = snapshotFavorites();
    fav.groupId = groupId;
    saveToLocalStorage();
    const result = await apiUpdate(favId, { groupId });
    if (!result.ok) {
      restoreFavorites(snapshot);
      if (result.status === 404) await refreshAfterMutation();
    }
  }

  async function reorderFavorite(fromIndex: number, toIndex: number) {
    if (
      fromIndex < 0 ||
      fromIndex >= favorites.value.length ||
      toIndex < 0 ||
      toIndex >= favorites.value.length
    )
      return;

    const snapshot = snapshotFavorites();
    const [item] = favorites.value.splice(fromIndex, 1);
    favorites.value.splice(toIndex, 0, item);
    favorites.value.forEach((f, i) => (f.order = i));
    saveToLocalStorage();
    if (!(await apiReorder(favorites.value.map((f) => f.id)))) {
      restoreFavorites(snapshot);
    }
  }

  async function moveAndReorderFavorite(
    draggedId: string,
    targetId: string,
    position: FavoriteDropPosition
  ) {
    const snapshot = snapshotFavorites();
    const next = reorderFavoriteItems(
      favorites.value,
      draggedId,
      targetId,
      position
    );
    const moved = next.find((favorite) => favorite.id === draggedId);
    const previous = favorites.value.find(
      (favorite) => favorite.id === draggedId
    );
    if (!moved || !previous) return;

    const groupChanged = (previous.groupId || "") !== (moved.groupId || "");
    favorites.value = next;
    saveToLocalStorage();
    if (groupChanged) {
      const result = await apiUpdate(draggedId, {
        groupId: moved.groupId || "",
      });
      if (!result.ok) {
        restoreFavorites(snapshot);
        if (result.status === 404) await refreshAfterMutation();
        return;
      }
    }
    if (!(await apiReorder(next.map((favorite) => favorite.id)))) {
      if (groupChanged) {
        await apiUpdate(draggedId, { groupId: previous.groupId || "" });
      }
      restoreFavorites(snapshot);
    }
  }

  async function syncFavorites() {
    const apiData = await apiGet();
    if (apiData) {
      const apiPaths = new Set(apiData.map((f) => f.path));
      const localOnly = favorites.value.filter((f) => !apiPaths.has(f.path));
      for (const fav of localOnly) {
        await apiCreate(fav);
      }
      const merged = await apiGet();
      if (merged) {
        favorites.value = merged;
        saveToLocalStorage();
      }
    }
  }

  async function syncGroups() {
    const remoteGroups = await apiGetGroups();
    if (!remoteGroups) return;

    for (const localGroup of [...groups.value]) {
      if (remoteGroups.some((group) => group.name === localGroup.name))
        continue;
      const created = await apiCreateGroup({
        name: localGroup.name,
        color: localGroup.color,
      });
      if (!created) continue;

      favorites.value.forEach((favorite) => {
        if (favorite.groupId === localGroup.id) favorite.groupId = created.id;
      });
    }

    const mergedGroups = await apiGetGroups();
    if (mergedGroups) groups.value = mergedGroups;
    saveGroupsToLocalStorage();
    saveToLocalStorage();
  }

  // --- Group methods ---

  async function addGroup(name: string, color?: string) {
    const newGroup: Partial<FavoriteGroup> = { name, color: color || "" };
    const created = await apiCreateGroup(newGroup);
    if (created) {
      groups.value.push(created);
      saveGroupsToLocalStorage();
      return created;
    }
    return null;
  }

  async function updateGroup(id: string, updates: Partial<FavoriteGroup>) {
    const group = groups.value.find((g) => g.id === id);
    if (!group) return;
    const snapshot = snapshotGroups();
    if (updates.name !== undefined) group.name = updates.name;
    if (updates.color !== undefined) group.color = updates.color;
    saveGroupsToLocalStorage();
    const result = await apiUpdateGroup(id, updates);
    if (!result.ok) {
      restoreGroups(snapshot);
      if (result.status === 404) await refreshAfterMutation();
    }
  }

  async function deleteGroup(
    id: string
  ): Promise<{ ok: boolean; status?: number }> {
    const result = await apiDeleteGroup(id);
    if (result.ok) {
      groups.value = groups.value.filter((g) => g.id !== id);
      favorites.value.forEach((f) => {
        if (f.groupId === id) f.groupId = "";
      });
      saveGroupsToLocalStorage();
      saveToLocalStorage();
      await refreshAfterMutation(true);
    }
    if (!result.ok && result.status === 404) await refreshAfterMutation();
    return result;
  }

  async function reorderGroups(fromIndex: number, toIndex: number) {
    if (
      fromIndex < 0 ||
      fromIndex >= groups.value.length ||
      toIndex < 0 ||
      toIndex >= groups.value.length
    )
      return;

    const previous = snapshotGroups();
    const [item] = groups.value.splice(fromIndex, 1);
    groups.value.splice(toIndex, 0, item);
    groups.value.forEach((g, i) => (g.order = i));
    saveGroupsToLocalStorage();
    if (!(await apiReorderGroups(groups.value.map((g) => g.id)))) {
      restoreGroups(previous);
    }
  }

  // Sorted favorites
  const sortedFavorites = computed(() =>
    [...favorites.value].sort((a, b) => a.order - b.order)
  );

  // Sorted groups
  const sortedGroups = computed(() =>
    [...groups.value].sort((a, b) => a.order - b.order)
  );

  // Favorites grouped by group
  const favoritesByGroup = computed(() => {
    const result: Record<string, Favorite[]> = {};
    result[""] = []; // ungrouped
    for (const g of groups.value) {
      result[g.id] = [];
    }
    for (const fav of sortedFavorites.value) {
      const gid = fav.groupId || "";
      if (!result[gid]) result[gid] = [];
      result[gid].push(fav);
    }
    return result;
  });

  return {
    favorites,
    groups,
    sortedFavorites,
    sortedGroups,
    favoritesByGroup,
    loaded,
    loadFavorites,
    saveFavorites,
    addFavorite,
    removeFavorite,
    removeByPath,
    isFavorite,
    applyPathRewrite,
    applyPathRemoval,
    toggleFavorite,
    moveFavoriteToGroup,
    reorderFavorite,
    moveAndReorderFavorite,
    syncFavorites,
    addGroup,
    updateGroup,
    deleteGroup,
    reorderGroups,
  };
});
