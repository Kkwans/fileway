import { defineStore } from "pinia";
import { watch } from "vue";
import { users } from "@/api";
import { useAuthStore } from "@/stores/auth";
import type { IUser } from "@/types/user";

type Preferences = Partial<
  Pick<
    IUser,
    | "singleClick"
    | "redirectAfterCopyMove"
    | "dateFormat"
    | "aceEditorTheme"
    | "playerPreferences"
    | "sorting"
  >
>;

export const useAccountPreferencesStore = defineStore(
  "accountPreferences",
  () => {
    const auth = useAuthStore();
    let queue = Promise.resolve();
    let generation = 0;
    watch(
      () => auth.user?.id,
      () => {
        generation += 1;
      },
      { flush: "sync" }
    );

    function save(input: Preferences): Promise<void> {
      const id = auth.user?.id;
      const account = generation;
      const patch = JSON.parse(JSON.stringify(input)) as Preferences & {
        id?: number;
      };
      delete patch.id;
      const operation = queue
        .catch(() => undefined)
        .then(async () => {
          if (!id || account !== generation || auth.user?.id !== id)
            throw new Error("账号已切换，请重新保存");
          await users.update({ id, ...patch }, Object.keys(patch));
          if (account !== generation || auth.user?.id !== id) return;
          auth.updateUser({
            ...patch,
            ...(patch.playerPreferences
              ? {
                  playerPreferences: {
                    ...auth.user.playerPreferences,
                    ...patch.playerPreferences,
                  },
                }
              : {}),
          });
        });
      queue = operation;
      return operation;
    }
    return { save };
  }
);
