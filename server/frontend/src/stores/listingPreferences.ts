import { computed, ref, watch } from "vue";
import { defineStore } from "pinia";
import { users } from "@/api";
import { useAuthStore } from "@/stores/auth";
import type { ListingPreferences, PrefixRule } from "@/types/user";
import {
  BUILT_IN_PREFIXES,
  defaultListingPreferences,
  normalizeListingPreferences,
} from "@/utils/listingPreferences";

export const useListingPreferencesStore = defineStore(
  "listingPreferences",
  () => {
    const authStore = useAuthStore();
    const preferences = ref<ListingPreferences>(
      defaultListingPreferences(Boolean(authStore.user?.hideDotfiles))
    );
    let confirmedPreferences = preferences.value;
    let saveQueue = Promise.resolve();
    let saveRevision = 0;
    let accountRevision = 0;

    const prefixRules = computed(() => preferences.value.prefixRules);

    function loadFromUser() {
      accountRevision += 1;
      saveRevision = 0;
      const loaded = normalizeListingPreferences(
        authStore.user?.listingPreferences,
        Boolean(authStore.user?.hideDotfiles)
      );
      preferences.value = loaded;
      confirmedPreferences = loaded;
    }

    async function replace(next: ListingPreferences) {
      const userId = authStore.user?.id;
      if (!userId) throw new Error("用户未登录");
      const normalized = normalizeListingPreferences(next);
      const revision = ++saveRevision;
      const account = accountRevision;
      preferences.value = normalized;
      authStore.updateUser({ listingPreferences: normalized });

      const operation = saveQueue
        .catch(() => undefined)
        .then(async () => {
          await users.update({ id: userId, listingPreferences: normalized }, [
            "listingPreferences",
          ]);
          if (accountRevision === account) {
            confirmedPreferences = normalized;
          }
        });
      saveQueue = operation;
      try {
        await operation;
      } catch (error) {
        if (accountRevision === account && saveRevision === revision) {
          preferences.value = confirmedPreferences;
          authStore.updateUser({ listingPreferences: confirmedPreferences });
        }
        throw error;
      }
    }

    async function updateRule(
      prefix: string,
      patch: Partial<Pick<PrefixRule, "visible" | "expanded">>
    ) {
      await replace({
        ...preferences.value,
        prefixRules: preferences.value.prefixRules.map((rule) =>
          rule.prefix === prefix ? { ...rule, ...patch } : rule
        ),
      });
    }

    async function setRules(rules: PrefixRule[]) {
      await replace({
        version: preferences.value.version,
        prefixRules: rules.map((rule, order) => ({ ...rule, order })),
      });
    }

    function isBuiltIn(prefix: string) {
      return BUILT_IN_PREFIXES.includes(prefix as never);
    }

    loadFromUser();
    watch(
      () => authStore.user?.id,
      () => loadFromUser()
    );

    return {
      preferences,
      prefixRules,
      loadFromUser,
      replace,
      updateRule,
      setRules,
      isBuiltIn,
    };
  }
);
