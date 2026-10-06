import { onBeforeUnmount, onMounted } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate } from "vue-router";
import { useLayoutStore } from "@/stores/layout";

/** Guard settings forms that require an explicit save action. */
export function useUnsavedChangesGuard(
  hasUnsavedChanges: () => boolean,
  saveChanges: () => Promise<boolean>
) {
  const layoutStore = useLayoutStore();

  const beforeUnload = (event: BeforeUnloadEvent) => {
    if (!hasUnsavedChanges()) return;
    event.preventDefault();
    event.returnValue = "";
  };
  onMounted(() => window.addEventListener("beforeunload", beforeUnload));
  onBeforeUnmount(() =>
    window.removeEventListener("beforeunload", beforeUnload)
  );

  const confirmLeave = (): boolean | Promise<boolean> => {
    if (!hasUnsavedChanges()) return true;

    return new Promise<boolean>((resolve) => {
      let actionStarted = false;
      layoutStore.showHover({
        prompt: "discardEditorChanges",
        close: async () => {
          if (!actionStarted) resolve(false);
          return "";
        },
        confirm: (event: Event) => {
          event.preventDefault();
          actionStarted = true;
          layoutStore.closeHovers();
          resolve(true);
        },
        saveAction: async () => {
          if (actionStarted) return;
          actionStarted = true;
          layoutStore.closeHovers();
          try {
            resolve(await saveChanges());
          } catch {
            resolve(false);
          }
        },
      });
    });
  };

  onBeforeRouteLeave(confirmLeave);
  onBeforeRouteUpdate(confirmLeave);
}
