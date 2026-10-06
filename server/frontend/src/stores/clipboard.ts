import { defineStore } from "pinia";
import type { ClipItem } from "@/types/file";

export const useClipboardStore = defineStore("clipboard", {
  // convert to a function
  state: (): {
    key: string;
    items: ClipItem[];
    path?: string;
  } => ({
    key: "",
    items: [],
    path: undefined,
  }),
  getters: {
    // user and jwt getter removed, no longer needed
  },
  actions: {
    // no context as first argument, use `this` instead
    // easily reset state using `$reset`
    resetClipboard() {
      this.$reset();
    },
  },
});
