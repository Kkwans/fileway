import { defineStore } from "pinia";
import { computed, reactive, toRefs, watch } from "vue";
import { useAuthStore } from "./auth";
import { fileSelectionScope } from "@/utils/fileListing";
import * as api from "@/api/recent";
import type { RecentEntry } from "@/api/recent";
import { normalizeFileKey } from "@/utils/fileListing";
import { encodePath } from "@/utils/url";

function belongsToPrefix(candidate: string, prefix: string) {
  const value = normalizeFileKey(candidate);
  const root = normalizeFileKey(prefix);
  return root === "/" || value === root || value.startsWith(`${root}/`);
}

// Compare encoded bytes, including opaque non-UTF-8 names, without decoding
// them into Unicode display paths. Normalize equivalent escape spellings only.
function wireIdentity(value: string) {
  return (
    value
      .replace(/%[0-9a-f]{2}|[^%/]/gi, (token) => {
        const byte = token.startsWith("%")
          ? Number.parseInt(token.slice(1), 16)
          : token.charCodeAt(0);
        const character = String.fromCharCode(byte);
        return /^[A-Za-z0-9._~-]$/.test(character)
          ? character
          : `%${byte.toString(16).toUpperCase().padStart(2, "0")}`;
      })
      .replace(/\/+$/, "") || "/"
  );
}

export const useRecentStore = defineStore("recent", () => {
  const auth = useAuthStore();
  const scope = computed(() => fileSelectionScope(auth.user ?? null));
  const initial = () => ({
    items: [] as RecentEntry[],
    loading: false,
    loaded: false,
    error: "",
  });
  const state = reactive(initial());
  let generation = 0,
    readEpoch = 0;
  function resetForUser() {
    generation++;
    readEpoch++;
    Object.assign(state, initial());
  }
  watch(scope, resetForUser, { flush: "sync" });
  async function load() {
    const source = scope.value,
      version = ++readEpoch;
    state.loading = true;
    state.error = "";
    try {
      const rows = await api.list();
      if (scope.value !== source || readEpoch !== version) return;
      state.items = rows;
      state.loaded = true;
    } catch (error) {
      if (scope.value !== source || readEpoch !== version) return;
      state.error = error instanceof Error ? error.message : String(error);
      throw error;
    } finally {
      if (scope.value === source && readEpoch === version)
        state.loading = false;
    }
  }
  async function record(path: string, wirePath?: string) {
    const source = scope.value,
      bound = generation;
    readEpoch++;
    state.loading = false;
    const entry = await api.record(normalizeFileKey(path), wirePath);
    if (scope.value !== source || generation !== bound) return entry;
    readEpoch++;
    state.loading = false;
    const newer = state.items.find(
      (saved) => saved.id === entry.id && saved.accessedAt > entry.accessedAt
    );
    if (!newer)
      state.items = [
        entry,
        ...state.items.filter((saved) => saved.id !== entry.id),
      ]
        .sort((left, right) => right.accessedAt - left.accessedAt)
        .slice(0, 100);
    return entry;
  }
  function applyPathRewrite(
    from: string,
    to: string,
    fromWirePath?: string,
    toWirePath?: string
  ) {
    readEpoch++;
    state.loading = false;
    const source = normalizeFileKey(from);
    const destination = normalizeFileKey(to);
    const sourceWire = wireIdentity(fromWirePath || encodePath(source));
    const destinationWire = wireIdentity(toWirePath || encodePath(destination));
    state.items = state.items.map((entry) => {
      if (entry.pathVerified === false) return entry;
      const wire = wireIdentity(entry.wirePath || encodePath(entry.path));
      if (!belongsToPrefix(wire, sourceWire)) return entry;
      const suffix = normalizeFileKey(entry.path).slice(source.length);
      const path = normalizeFileKey(`${destination}${suffix}`);
      const name =
        wire === sourceWire ? path.split("/").at(-1) || entry.name : entry.name;
      return {
        ...entry,
        path,
        name,
        wirePath: destinationWire + wire.slice(sourceWire.length),
      };
    });
  }
  function applyPathRemoval(prefix: string, wirePath?: string) {
    readEpoch++;
    state.loading = false;
    const wirePrefix = wireIdentity(
      wirePath || encodePath(normalizeFileKey(prefix))
    );
    state.items = state.items.filter(
      (entry) =>
        entry.pathVerified === false ||
        !belongsToPrefix(
          wireIdentity(entry.wirePath || encodePath(entry.path)),
          wirePrefix
        )
    );
  }

  return {
    ...toRefs(state),
    load,
    record,
    applyPathRewrite,
    applyPathRemoval,
    resetForUser,
    $reset: resetForUser,
  };
});
