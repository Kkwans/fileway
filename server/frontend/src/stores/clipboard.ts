import { defineStore } from "pinia";
import { computed, reactive, toRefs, watch } from "vue";
import type { ClipItem } from "@/types/file";
import { useAuthStore } from "./auth";
import { fileSelectionScope } from "@/utils/fileListing";

export const useClipboardStore = defineStore("clipboard", () => {
  const auth = useAuthStore();
  const scope = computed(() => fileSelectionScope(auth.user));
  const initial = () => ({
    key: "",
    items: [] as ClipItem[],
    path: undefined as string | undefined,
  });
  const state = reactive(initial());
  function resetClipboard() {
    Object.assign(state, initial());
  }
  watch(scope, resetClipboard, { flush: "sync" });
  function setClipboard(
    value: { key: string; items: ClipItem[]; path?: string },
    sourceScope = scope.value
  ) {
    if (sourceScope !== scope.value) return false;
    Object.assign(state, {
      ...value,
      items: value.items.map((item) => ({ ...item })),
    });
    return true;
  }
  return {
    ...toRefs(state),
    scope,
    setClipboard,
    resetClipboard,
    $reset: resetClipboard,
  };
});
