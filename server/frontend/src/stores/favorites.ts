import { defineStore } from "pinia";
import { ref, computed, watch } from "vue";
import { fileSelectionScope } from "@/utils/fileListing";
import { useAuthStore } from "@/stores/auth";
import { fetchURL, StatusError } from "@/api/utils";
import {
  replaceFavoriteByPath,
  resolvePersistenceState,
  userStorageKey,
  favoriteIdentity,
  favoriteWirePath,
  favoriteCreateBody,
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
  wirePath?: string;
  pathVerified?: boolean;
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

  const owner = () => fileSelectionScope(authStore.user ?? null);
  let revision = 0,
    mutationRevision = 0;
  const captureSource = (mutation = false) => ({
    owner: owner(),
    revision: ++revision,
    mutationRevision: mutation ? ++mutationRevision : undefined,
  });
  const currentSource = (source: ReturnType<typeof captureSource>) =>
    source.owner === owner() &&
    (source.mutationRevision === undefined
      ? source.revision === revision
      : source.mutationRevision === mutationRevision);
  function settleMutation(source: ReturnType<typeof captureSource>) {
    if (currentSource(source) && source.revision === revision) return true;
    // A read does not cancel an explicit write. After its ACK, converge from
    // the authority instead of replacing newer rows with an old snapshot.
    if (source.owner === owner())
      void refreshAfterMutation(true).catch(() => {});
    return false;
  }
  watch(
    owner,
    () => {
      revision++;
      mutationRevision++;
      favorites.value = [];
      groups.value = [];
      loaded.value = false;
    },
    { flush: "sync" }
  );
  const snapshotFavorites = () => ({
    source: captureSource(true),
    rows: favorites.value.map((item) => ({ ...item })),
  });
  const snapshotGroups = () => ({
    source: captureSource(true),
    rows: groups.value.map((item) => ({ ...item })),
  });
  function restoreFavorites(snapshot: ReturnType<typeof snapshotFavorites>) {
    if (
      !currentSource(snapshot.source) ||
      snapshot.source.revision !== revision
    )
      return;
    favorites.value = snapshot.rows.map((item) => ({ ...item }));
    saveToLocalStorage();
  }
  function restoreGroups(snapshot: ReturnType<typeof snapshotGroups>) {
    if (
      !currentSource(snapshot.source) ||
      snapshot.source.revision !== revision
    )
      return;
    groups.value = snapshot.rows.map((item) => ({ ...item }));
    saveGroupsToLocalStorage();
  }

  // --- API helpers ---

  async function apiGet(): Promise<Favorite[] | null> {
    const sourceOwner = owner();
    try {
      const res = await fetchURL(API_BASE, {});
      const rows = await res.json();
      return owner() === sourceOwner ? rows : null;
    } catch {
      return null;
    }
  }

  async function apiCreate(fav: Favorite): Promise<Favorite | null> {
    try {
      const res = await fetchURL(API_BASE, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(favoriteCreateBody(fav)),
      });
      const created = (await res.json()) as Favorite;
      if (
        favoriteIdentity(created) !== favoriteIdentity(fav) ||
        favoriteIdentity(created) === null
      )
        return null;
      return created;
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
    const sourceOwner = owner();
    try {
      const res = await fetchURL(GROUPS_API_BASE, {});
      const rows = await res.json();
      return owner() === sourceOwner ? rows : null;
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
    // Retain old user-only caches without guessing their workspace.
    return userStorageKey(prefix, owner());
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
    const source = captureSource();
    const cachedGroups = loadGroupsFromLocalStorage();
    const apiGroups = await apiGetGroups();
    if (!currentSource(source)) return;
    const groupState = resolvePersistenceState(apiGroups ?? [], cachedGroups);
    groups.value = apiGroups === null ? cachedGroups : groupState.data;
    saveGroupsToLocalStorage();

    const cachedFavorites = loadFromLocalStorage();
    const apiData = await apiGet();
    if (!currentSource(source)) return;
    const favoriteState = resolvePersistenceState(
      apiData ?? [],
      cachedFavorites
    );
    favorites.value = apiData === null ? cachedFavorites : favoriteState.data;
    saveToLocalStorage();

    if (groupState.shouldSync) await syncGroups(source);
    if (currentSource(source) && favoriteState.shouldSync)
      await syncFavorites(source);
    if (currentSource(source)) loaded.value = true;
  }

  /**
   * 兼容旧版本留下的临时 ID：变更接口返回 404 时重新读取当前用户的
   * 服务端记录；若服务端暂时为空，则把本地缓存重新同步并取得真实 ID。
   */
  async function refreshAfterMutation(preferRemote = false) {
    const source = captureSource();
    const [remoteGroups, remoteFavorites] = await Promise.all([
      apiGetGroups(),
      apiGet(),
    ]);
    if (
      !currentSource(source) ||
      remoteGroups === null ||
      remoteFavorites === null
    )
      return;

    if (!preferRemote && remoteGroups.length === 0 && groups.value.length > 0) {
      await syncGroups(source);
    } else {
      groups.value = remoteGroups;
    }

    if (!currentSource(source)) return;
    if (
      !preferRemote &&
      remoteFavorites.length === 0 &&
      favorites.value.length > 0
    ) {
      await syncFavorites(source);
    } else {
      favorites.value = remoteFavorites;
    }

    if (!currentSource(source)) return;
    saveGroupsToLocalStorage();
    saveToLocalStorage();
  }

  function saveFavorites() {
    saveToLocalStorage();
  }

  function findFavorite(path: string, wirePath?: string) {
    const identity = favoriteIdentity({ path, wirePath });
    return identity === null
      ? undefined
      : favorites.value.find(
          (favorite) => favoriteIdentity(favorite) === identity
        );
  }

  async function addFavorite(
    path: string,
    name: string,
    groupId?: string,
    wirePath?: string
  ) {
    const cleaned = path.replace(/\/+$/, "") || "/";
    const sourceWire = favoriteWirePath({ path: cleaned, wirePath });
    if (!sourceWire || findFavorite(cleaned, sourceWire)) return;

    const snapshot = snapshotFavorites();

    const newFav: Favorite = {
      id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
      path: cleaned,
      name,
      groupId: groupId || "",
      addedAt: Date.now(),
      order: favorites.value.length,
      wirePath: sourceWire,
      pathVerified: true,
    };
    favorites.value.push(newFav);
    saveToLocalStorage();
    const created = await apiCreate(newFav);
    if (!settleMutation(snapshot.source)) return;
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
    if (!settleMutation(snapshot.source)) return;
    if (!result.ok) {
      restoreFavorites(snapshot);
      if (result.status === 404) await refreshAfterMutation();
    }
  }

  async function removeByPath(path: string, wirePath?: string) {
    const target = findFavorite(path, wirePath);
    if (!target) return;

    const snapshot = snapshotFavorites();
    favorites.value = favorites.value.filter((f) => f.id !== target.id);
    favorites.value.forEach((f, i) => (f.order = i));
    saveToLocalStorage();
    const result = await apiDelete(target.id);
    if (!settleMutation(snapshot.source)) return;
    if (!result.ok) {
      restoreFavorites(snapshot);
      if (result.status === 404) await refreshAfterMutation();
    }
  }

  function isFavorite(path: string, wirePath?: string): boolean {
    return findFavorite(path, wirePath) !== undefined;
  }

  function applyPathRewrite(from: string, to: string) {
    revision++;
    // Display-only mutation callbacks cannot identify opaque siblings. The
    // server has already committed the corresponding metadata transaction.
    if (
      favorites.value.some(
        (item) => item.wirePath || item.pathVerified !== undefined
      )
    ) {
      void refreshAfterMutation(true);
      return;
    }
    // Preserve the old UTF-8-only server's immediate collection reconciliation.
    let changed = false;
    favorites.value = favorites.value.map((favorite) => {
      if (favoriteIdentity(favorite) === null) return favorite;
      const rewritten = rewriteTagPathPrefix(favorite.path, from, to);
      if (rewritten === null || rewritten === favorite.path) return favorite;
      changed = true;
      return { ...favorite, path: rewritten };
    });
    if (changed) saveToLocalStorage();
  }

  function applyPathRemoval(prefix: string) {
    revision++;
    if (
      favorites.value.some(
        (item) => item.wirePath || item.pathVerified !== undefined
      )
    ) {
      void refreshAfterMutation(true);
      return;
    }
    const normalizedPrefix = normalizeTagPath(prefix);
    favorites.value = favorites.value.filter(
      (favorite) =>
        favoriteIdentity(favorite) === null ||
        (normalizeTagPath(favorite.path) !== normalizedPrefix &&
          rewriteTagPathPrefix(favorite.path, normalizedPrefix, "/") === null)
    );
    saveToLocalStorage();
  }

  async function toggleFavorite(
    path: string,
    name: string,
    groupId?: string,
    wirePath?: string
  ) {
    const existing = findFavorite(path, wirePath);
    if (existing) {
      await removeFavorite(existing.id);
    } else {
      await addFavorite(path, name, groupId, wirePath);
    }
  }

  async function moveFavoriteToGroup(favId: string, groupId: string) {
    const fav = favorites.value.find((f) => f.id === favId);
    if (!fav) return;
    const snapshot = snapshotFavorites();
    fav.groupId = groupId;
    saveToLocalStorage();
    const result = await apiUpdate(favId, { groupId });
    if (!settleMutation(snapshot.source)) return;
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
    const reordered = await apiReorder(favorites.value.map((f) => f.id));
    if (!settleMutation(snapshot.source)) return;
    if (!reordered) restoreFavorites(snapshot);
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
      if (!currentSource(snapshot.source)) {
        settleMutation(snapshot.source);
        return;
      }
      if (!result.ok) {
        if (!settleMutation(snapshot.source)) return;
        restoreFavorites(snapshot);
        if (result.status === 404) await refreshAfterMutation();
        return;
      }
    }
    const reordered = await apiReorder(next.map((favorite) => favorite.id));
    if (!currentSource(snapshot.source)) {
      settleMutation(snapshot.source);
      return;
    }
    if (!reordered && groupChanged) {
      await apiUpdate(draggedId, { groupId: previous.groupId || "" });
    }
    if (!settleMutation(snapshot.source)) return;
    if (!reordered) restoreFavorites(snapshot);
  }

  async function syncFavorites(source = captureSource()) {
    if (!currentSource(source)) return;
    const apiData = await apiGet();
    if (!currentSource(source)) return;
    if (apiData) {
      const apiPaths = new Set(
        apiData.map(favoriteIdentity).filter((key) => key !== null)
      );
      const localOnly = favorites.value.filter((f) => {
        const key = favoriteIdentity(f);
        return key !== null && !apiPaths.has(key);
      });
      for (const fav of localOnly) {
        if (!currentSource(source)) return;
        await apiCreate(fav);
      }
      if (!currentSource(source)) return;
      const merged = await apiGet();
      if (merged && currentSource(source)) {
        favorites.value = merged;
        saveToLocalStorage();
      }
    }
  }

  async function syncGroups(source = captureSource()) {
    if (!currentSource(source)) return;
    const remoteGroups = await apiGetGroups();
    if (!currentSource(source) || !remoteGroups) return;

    for (const localGroup of [...groups.value]) {
      if (!currentSource(source)) return;
      if (remoteGroups.some((group) => group.name === localGroup.name))
        continue;
      const created = await apiCreateGroup({
        name: localGroup.name,
        color: localGroup.color,
      });
      if (!currentSource(source)) return;
      if (!created) continue;

      favorites.value.forEach((favorite) => {
        if (favorite.groupId === localGroup.id) favorite.groupId = created.id;
      });
    }

    const mergedGroups = await apiGetGroups();
    if (!currentSource(source)) return;
    if (mergedGroups) groups.value = mergedGroups;
    saveGroupsToLocalStorage();
    saveToLocalStorage();
  }

  // --- Group methods ---

  async function addGroup(name: string, color?: string) {
    const source = captureSource(true);
    const newGroup: Partial<FavoriteGroup> = { name, color: color || "" };
    const created = await apiCreateGroup(newGroup);
    if (!settleMutation(source)) return currentSource(source) ? created : null;
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
    if (!settleMutation(snapshot.source)) return;
    if (!result.ok) {
      restoreGroups(snapshot);
      if (result.status === 404) await refreshAfterMutation();
    }
  }

  async function deleteGroup(
    id: string
  ): Promise<{ ok: boolean; status?: number }> {
    const source = captureSource(true);
    const result = await apiDeleteGroup(id);
    if (!settleMutation(source)) return result;
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
    const reordered = await apiReorderGroups(groups.value.map((g) => g.id));
    if (!settleMutation(previous.source)) return;
    if (!reordered) restoreGroups(previous);
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
    refreshAfterMutation,
    saveFavorites,
    addFavorite,
    removeFavorite,
    removeByPath,
    isFavorite,
    findFavorite,
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
