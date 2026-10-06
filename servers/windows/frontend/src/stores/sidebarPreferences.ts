import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { users } from "@/api";
import { useAuthStore } from "@/stores/auth";
import {
  DEFAULT_SIDEBAR_PREFERENCES,
  normalizeSidebarPreferences,
  reorderByPreference,
  reorderPreference,
  type SidebarPreferences,
} from "@/utils/sidebarPreferences";

type PreferenceOrderKey = Exclude<
  keyof SidebarPreferences,
  "categoryPathOrder" | "desktopCollapsed"
>;

export const useSidebarPreferencesStore = defineStore(
  "sidebarPreferences",
  () => {
    const authStore = useAuthStore();
    const preferences = ref<SidebarPreferences>(
      normalizeSidebarPreferences(DEFAULT_SIDEBAR_PREFERENCES)
    );
    const loadedUserId = ref<number | null>(null);
    let saveQueue = Promise.resolve();

    const moduleOrder = computed(() => preferences.value.moduleOrder);
    const desktopCollapsed = computed(() => preferences.value.desktopCollapsed);

    async function load() {
      const userId = authStore.user?.id;
      if (!userId) {
        loadedUserId.value = null;
        preferences.value = normalizeSidebarPreferences(
          DEFAULT_SIDEBAR_PREFERENCES
        );
        return;
      }
      if (loadedUserId.value === userId) return;

      try {
        const fullUser = await users.get(userId);
        authStore.updateUser({
          sidebarPreferences: fullUser.sidebarPreferences,
        });
        preferences.value = normalizeSidebarPreferences(
          fullUser.sidebarPreferences
        );
      } catch {
        preferences.value = normalizeSidebarPreferences(
          authStore.user?.sidebarPreferences
        );
      }
      loadedUserId.value = userId;
    }

    function queueSave() {
      const userId = authStore.user?.id;
      if (!userId) return Promise.resolve();
      const serialized = JSON.stringify(preferences.value);
      authStore.updateUser({ sidebarPreferences: serialized });
      saveQueue = saveQueue
        .catch(() => undefined)
        .then(() =>
          users.update({ id: userId, sidebarPreferences: serialized }, [
            "sidebarPreferences",
          ])
        );
      return saveQueue;
    }

    async function setDesktopCollapsed(collapsed: boolean) {
      if (preferences.value.desktopCollapsed === collapsed) return;
      const previous = preferences.value;
      const next = { ...previous, desktopCollapsed: collapsed };
      preferences.value = next;

      try {
        await queueSave();
      } catch (error) {
        if (preferences.value.desktopCollapsed === collapsed) {
          preferences.value = {
            ...preferences.value,
            desktopCollapsed: previous.desktopCollapsed,
          };
          authStore.updateUser({
            sidebarPreferences: JSON.stringify(preferences.value),
          });
        }
        throw error;
      }
    }

    async function reorder(
      key: PreferenceOrderKey,
      visibleIds: readonly string[],
      draggedId: string,
      targetId: string,
      position: "before" | "after" = "before"
    ) {
      const current = reorderByPreference(
        visibleIds,
        preferences.value[key],
        (id) => id
      );
      preferences.value = {
        ...preferences.value,
        [key]: reorderPreference(current, draggedId, targetId, position),
      };
      await queueSave();
    }

    async function reorderCategoryPath(
      categoryId: string,
      visibleIds: readonly string[],
      draggedId: string,
      targetId: string,
      position: "before" | "after" = "before"
    ) {
      const current = reorderByPreference(
        visibleIds,
        preferences.value.categoryPathOrder[categoryId] ?? [],
        (id) => id
      );
      preferences.value = {
        ...preferences.value,
        categoryPathOrder: {
          ...preferences.value.categoryPathOrder,
          [categoryId]: reorderPreference(
            current,
            draggedId,
            targetId,
            position
          ),
        },
      };
      await queueSave();
    }

    function ordered<T>(
      items: readonly T[],
      key: PreferenceOrderKey,
      getId: (item: T) => string
    ) {
      return reorderByPreference(items, preferences.value[key], getId);
    }

    function orderedCategoryPaths<T>(
      categoryId: string,
      items: readonly T[],
      getId: (item: T) => string
    ) {
      return reorderByPreference(
        items,
        preferences.value.categoryPathOrder[categoryId] ?? [],
        getId
      );
    }

    return {
      preferences,
      moduleOrder,
      desktopCollapsed,
      load,
      setDesktopCollapsed,
      reorder,
      reorderCategoryPath,
      ordered,
      orderedCategoryPaths,
    };
  }
);
