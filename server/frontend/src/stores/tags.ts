import { defineStore } from "pinia";
import { ref, computed, watch } from "vue";
import { useAuthStore } from "@/stores/auth";
import { fetchURL, StatusError } from "@/api/utils";
import {
  replaceTagByName,
  tagReferences,
  tagAssociationBody,
  type TagPathRef,
} from "@/utils/tagPersistence";
import {
  resolvePersistenceState,
  userStorageKey,
  favoriteIdentity,
} from "@/utils/favoritePersistence";
import {
  isDescendantPath,
  normalizeTagPath,
  rewriteTagPathPrefix,
} from "@/utils/tagPath";
import { isTagColorAvailable, normalizeTagColor } from "@/utils/tagColors";

export { TAG_COLORS } from "@/utils/tagColors";

export interface Tag {
  id: string;
  name: string;
  color: string;
  paths: string[];
  pathRefs?: TagPathRef[];
  createdAt: number;
}

export type TagFilterMode = "current" | "global";

const STORAGE_KEY = "nas-file-browser-tags";
const API_BASE = "/api/tags";

function normalizeTag(tag: Tag): Tag {
  const refs = tag.pathRefs ? tagReferences(tag) : undefined;
  return {
    ...tag,
    color: normalizeTagColor(tag.color),
    paths: [...(tag.paths || [])],
    ...(refs ? { pathRefs: refs, paths: refs.map((ref) => ref.path) } : {}),
  };
}

export const useTagsStore = defineStore("tags", () => {
  const authStore = useAuthStore();
  const tags = ref<Tag[]>([]);
  const loaded = ref(false);
  const activeFilter = ref<string | null>(null); // tag id for filtering
  const filterMode = ref<TagFilterMode>("global");
  const owner = () =>
    `${authStore.user?.id ?? "anonymous"}/${authStore.user?.scope ?? ""}`;
  const sourceScope = computed(owner);
  watch(
    owner,
    () => {
      tags.value = [];
      loaded.value = false;
      activeFilter.value = null;
    },
    { flush: "sync" }
  );

  const snapshotTags = () => ({
    owner: owner(),
    rows: tags.value.map((tag) => ({
      ...tag,
      paths: [...tag.paths],
      ...(tag.pathRefs
        ? { pathRefs: tag.pathRefs.map((ref) => ({ ...ref })) }
        : {}),
    })),
  });

  function restoreTags(snapshot: ReturnType<typeof snapshotTags>) {
    if (owner() !== snapshot.owner) return;
    tags.value = snapshot.rows.map((tag) => ({
      ...tag,
      paths: [...tag.paths],
      ...(tag.pathRefs
        ? { pathRefs: tag.pathRefs.map((ref) => ({ ...ref })) }
        : {}),
    }));
    saveToLocalStorage();
  }

  // --- API helpers ---

  async function apiGet(expectedOwner = owner()): Promise<Tag[] | null> {
    try {
      const res = await fetchURL(API_BASE, {});
      const data = (await res.json()) as Tag[];
      if (owner() !== expectedOwner) return null;
      return data.map(normalizeTag);
    } catch {
      return null;
    }
  }

  async function apiCreate(tag: Tag): Promise<Tag | null> {
    const expectedOwner = owner();
    try {
      const res = await fetchURL(API_BASE, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(tag),
      });
      const result = await res.json();
      return owner() === expectedOwner ? normalizeTag(result) : null;
    } catch {
      return null;
    }
  }

  async function apiUpdate(
    id: string,
    updates: Partial<Tag>
  ): Promise<{ ok: boolean; status?: number }> {
    try {
      await fetchURL(`${API_BASE}/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(updates),
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

  async function apiAddPath(
    tagId: string,
    path: string,
    wirePath?: string
  ): Promise<{ ok: boolean; status?: number }> {
    try {
      const response = await fetchURL(`${API_BASE}/${tagId}/paths`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(tagAssociationBody({ path, wirePath })),
      });
      const saved = normalizeTag(await response.json());
      const identity = favoriteIdentity({ path, wirePath });
      if (
        saved.id !== tagId ||
        !tagReferences(saved).some((ref) => favoriteIdentity(ref) === identity)
      )
        return { ok: false };
      return { ok: true };
    } catch (error) {
      return {
        ok: false,
        status: error instanceof StatusError ? error.status : undefined,
      };
    }
  }

  async function apiRemovePath(
    tagId: string,
    path: string,
    wirePath?: string
  ): Promise<{ ok: boolean; status?: number }> {
    try {
      const response = await fetchURL(`${API_BASE}/${tagId}/paths`, {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(tagAssociationBody({ path, wirePath })),
      });
      const saved = normalizeTag(await response.json());
      const identity = favoriteIdentity({ path, wirePath });
      if (
        saved.id !== tagId ||
        tagReferences(saved).some((ref) => favoriteIdentity(ref) === identity)
      )
        return { ok: false };
      return { ok: true };
    } catch (error) {
      return {
        ok: false,
        status: error instanceof StatusError ? error.status : undefined,
      };
    }
  }

  // --- localStorage helpers ---

  function scopedStorageKey(): string {
    // Relative references belong to the current workspace. An older user-only
    // cache has no scope provenance and must not be imported into a new scope.
    return userStorageKey(STORAGE_KEY, owner());
  }

  function saveToLocalStorage() {
    try {
      localStorage.setItem(scopedStorageKey(), JSON.stringify(tags.value));
    } catch {
      // localStorage full or unavailable
    }
  }

  function loadFromLocalStorage(): Tag[] {
    try {
      const saved = localStorage.getItem(scopedStorageKey());
      const data = saved ? (JSON.parse(saved) as Tag[]) : [];
      return data.map(normalizeTag);
    } catch {
      return [];
    }
  }

  // --- Public methods ---

  // Load tags: API first, fallback to localStorage
  async function loadTags() {
    const expectedOwner = owner();
    const cachedTags = loadFromLocalStorage();
    const apiData = await apiGet();
    if (owner() !== expectedOwner) return;
    const state = resolvePersistenceState(apiData ?? [], cachedTags);
    tags.value = apiData === null ? cachedTags : state.data;
    saveToLocalStorage();
    if (state.shouldSync) await syncTags();
    if (owner() === expectedOwner) loaded.value = true;
  }

  /**
   * 旧版本曾把后端真实 ID 和浏览器临时 ID 混在一起，导致更新/删除
   * 返回 404 后界面仍保留错误状态。变更失败时重新同步一次，以服务端
   * 返回的用户记录为准，并让遗留的本地记录重新走创建流程。
   */
  async function refreshAfterMutation(preferRemote = false) {
    const expectedOwner = owner();
    const remote = await apiGet();
    if (remote === null || owner() !== expectedOwner) return;
    if (!preferRemote && remote.length === 0 && tags.value.length > 0) {
      await syncTags();
      return;
    }
    tags.value = remote;
    saveToLocalStorage();
  }

  // Save to localStorage (kept for backward compat, prefer API)
  function saveTags() {
    saveToLocalStorage();
  }

  // Create a new tag
  async function createTag(name: string, color: string): Promise<Tag | null> {
    const normalizedColor = normalizeTagColor(color);
    if (!isTagColorAvailable(tags.value, normalizedColor)) return null;
    const snapshot = snapshotTags();
    const tag: Tag = {
      id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
      name: name.trim(),
      color: normalizedColor,
      paths: [],
      createdAt: Date.now(),
    };
    tags.value.push(tag);
    saveToLocalStorage();
    const savedTag = await apiCreate(tag);
    if (owner() !== snapshot.owner) return null;
    if (savedTag) {
      tags.value = replaceTagByName(tags.value, savedTag);
      saveToLocalStorage();
      return savedTag;
    }
    restoreTags(snapshot);
    return null;
  }

  // Update a tag
  async function updateTag(
    id: string,
    updates: Partial<Pick<Tag, "name" | "color">>
  ): Promise<boolean> {
    const tag = tags.value.find((t) => t.id === id);
    if (!tag) return false;
    if (
      updates.color !== undefined &&
      !isTagColorAvailable(tags.value, updates.color, id)
    ) {
      return false;
    }
    const snapshot = snapshotTags();
    if (updates.name !== undefined) tag.name = updates.name.trim();
    if (updates.color !== undefined) {
      updates.color = normalizeTagColor(updates.color);
      tag.color = updates.color;
    }
    saveToLocalStorage();
    const result = await apiUpdate(id, updates);
    if (owner() !== snapshot.owner) return false;
    if (!result.ok) {
      restoreTags(snapshot);
      if (result.status === 404) await refreshAfterMutation();
    }
    return result.ok;
  }

  // Delete a tag
  async function deleteTag(id: string) {
    const snapshot = snapshotTags();
    const previousFilter = activeFilter.value;
    tags.value = tags.value.filter((t) => t.id !== id);
    if (activeFilter.value === id) activeFilter.value = null;
    saveToLocalStorage();
    const result = await apiDelete(id);
    if (owner() !== snapshot.owner) return;
    if (!result.ok) {
      restoreTags(snapshot);
      activeFilter.value = previousFilter;
      if (result.status === 404) await refreshAfterMutation();
    }
  }

  // Add a path to a tag
  async function changePath(
    tagId: string,
    path: string,
    wirePath: string | undefined,
    adding: boolean
  ) {
    const tag = tags.value.find((t) => t.id === tagId);
    const identity = favoriteIdentity({ path, wirePath });
    if (!tag || identity === null) return false;
    const refs = tagReferences(tag);
    const existing = refs.some((ref) => favoriteIdentity(ref) === identity);
    if (existing === adding) return true;
    const snapshot = snapshotTags();
    const next = adding
      ? [
          ...refs,
          {
            path,
            wirePath: tagAssociationBody({ path, wirePath }).wirePath,
            pathVerified: true,
          },
        ]
      : refs.filter((ref) => favoriteIdentity(ref) !== identity);
    tag.paths = next.map((ref) => ref.path);
    tag.pathRefs = next;
    saveToLocalStorage();
    const result = adding
      ? await apiAddPath(tagId, path, wirePath)
      : await apiRemovePath(tagId, path, wirePath);
    if (owner() !== snapshot.owner) return false;
    if (!result.ok) {
      restoreTags(snapshot);
      await refreshAfterMutation(true);
    }
    return result.ok;
  }
  async function addPathToTag(tagId: string, path: string, wirePath?: string) {
    return changePath(tagId, path, wirePath, true);
  }

  // Remove a path from a tag
  async function removePathFromTag(
    tagId: string,
    path: string,
    wirePath?: string
  ) {
    return changePath(tagId, path, wirePath, false);
  }

  // Toggle a path in a tag (add if not present, remove if present)
  async function togglePathInTag(
    tagId: string,
    path: string,
    wirePath?: string,
    expectedOwner = owner()
  ) {
    if (expectedOwner !== owner()) return false;
    return changePath(
      tagId,
      path,
      wirePath,
      !getTagsForPath(path, wirePath).some((tag) => tag.id === tagId)
    );
  }

  // Get all tags for a specific path
  function getTagsForPath(path: string, wirePath?: string): Tag[] {
    const identity = favoriteIdentity({ path, wirePath });
    if (identity === null) return [];
    return tags.value.filter((tag) =>
      tagReferences(tag).some((ref) => favoriteIdentity(ref) === identity)
    );
  }

  // Check if a path has any tags
  function hasTags(path: string, wirePath?: string): boolean {
    return getTagsForPath(path, wirePath).length > 0;
  }

  function applyPathRewrite(from: string, to: string) {
    if (tags.value.some((tag) => tag.pathRefs !== undefined)) {
      void refreshAfterMutation(true);
      return;
    }
    let changed = false;
    tags.value = tags.value.map((tag) => {
      const seen = new Set<string>();
      const paths: string[] = [];
      let tagChanged = false;
      for (const savedPath of tag.paths) {
        const rewritten = rewriteTagPathPrefix(savedPath, from, to);
        const next = rewritten ?? normalizeTagPath(savedPath);
        if (rewritten !== null && next !== savedPath) tagChanged = true;
        if (seen.has(next)) {
          tagChanged = true;
          continue;
        }
        seen.add(next);
        paths.push(next);
      }
      if (!tagChanged) return tag;
      changed = true;
      return { ...tag, paths };
    });
    if (changed) saveToLocalStorage();
  }

  function applyPathRemoval(prefix: string) {
    if (tags.value.some((tag) => tag.pathRefs !== undefined)) {
      void refreshAfterMutation(true);
      return;
    }
    const normalizedPrefix = normalizeTagPath(prefix);
    let changed = false;
    tags.value = tags.value.map((tag) => {
      const paths = tag.paths.filter((savedPath) => {
        const normalized = normalizeTagPath(savedPath);
        return (
          normalized !== normalizedPrefix &&
          rewriteTagPathPrefix(normalized, normalizedPrefix, "/") === null
        );
      });
      if (paths.length === tag.paths.length) return tag;
      changed = true;
      return { ...tag, paths };
    });
    if (changed) saveToLocalStorage();
  }

  // Set active filter tag (null = no filter)
  function setFilter(tagId: string | null) {
    activeFilter.value = activeFilter.value === tagId ? null : tagId;
  }

  function setFilterMode(mode: TagFilterMode) {
    filterMode.value = mode;
  }

  // Get filtered paths (based on active filter)
  const filteredPaths = computed(() => {
    if (!activeFilter.value) return null; // null means no filter active
    const tag = tags.value.find((t) => t.id === activeFilter.value);
    return tag
      ? new Set(
          tagReferences(tag)
            .map(favoriteIdentity)
            .filter((value): value is string => value !== null)
        )
      : null;
  });

  // Active filter tag object
  const activeFilterTag = computed(() => {
    if (!activeFilter.value) return null;
    return tags.value.find((t) => t.id === activeFilter.value) ?? null;
  });

  // Check if a path matches the current filter
  function matchesFilter(path: string, wirePath?: string): boolean {
    if (!filteredPaths.value) return true; // no filter = show all
    const cleaned = favoriteIdentity({ path, wirePath });
    if (cleaned === null) return false;
    if (filterMode.value === "current") {
      return filteredPaths.value.has(cleaned);
    }

    // 全局模式保留标签筛选状态，浏览不同目录时显示标签项及其父目录，
    // 让用户可以沿目录树进入真正被打标的文件/文件夹。
    for (const taggedPath of filteredPaths.value) {
      if (
        taggedPath === cleaned ||
        isDescendantPath(taggedPath, cleaned) ||
        isDescendantPath(cleaned, taggedPath)
      ) {
        return true;
      }
    }
    return false;
  }

  // Sync local data to API (e.g. after recovering from offline)
  async function syncTags() {
    const expectedOwner = owner();
    const apiData = await apiGet();
    if (apiData && owner() === expectedOwner) {
      for (const localTag of [...tags.value]) {
        if (owner() !== expectedOwner) return;
        let remoteTag = apiData.find((tag) => tag.name === localTag.name);
        if (!remoteTag) {
          const created = await apiCreate(localTag);
          if (owner() !== expectedOwner) return;
          if (created) remoteTag = created;
        }
        if (!remoteTag) continue;

        for (const ref of tagReferences(localTag)) {
          if (owner() !== expectedOwner) return;
          const identity = favoriteIdentity(ref);
          if (identity === null) continue;
          if (
            !tagReferences(remoteTag).some(
              (saved) => favoriteIdentity(saved) === identity
            )
          ) {
            await apiAddPath(remoteTag.id, ref.path, ref.wirePath);
          }
        }
      }

      const merged = await apiGet();
      if (merged && owner() === expectedOwner) {
        tags.value = merged;
        saveToLocalStorage();
      }
    }
  }

  // Tags sorted by name
  const sortedTags = computed(() =>
    [...tags.value].sort((a, b) => a.name.localeCompare(b.name, "zh-CN"))
  );

  return {
    tags,
    sourceScope,
    sortedTags,
    loaded,
    activeFilter,
    filterMode,
    activeFilterTag,
    filteredPaths,
    loadTags,
    saveTags,
    createTag,
    updateTag,
    deleteTag,
    addPathToTag,
    removePathFromTag,
    togglePathInTag,
    getTagsForPath,
    hasTags,
    applyPathRewrite,
    applyPathRemoval,
    setFilter,
    setFilterMode,
    matchesFilter,
    syncTags,
  };
});
