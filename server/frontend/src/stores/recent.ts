import { defineStore } from "pinia";
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

export const useRecentStore = defineStore("recent", {
  state: (): {
    items: RecentEntry[];
    loading: boolean;
    loaded: boolean;
    error: string;
  } => ({ items: [], loading: false, loaded: false, error: "" }),
  actions: {
    async load() {
      this.loading = true;
      this.error = "";
      try {
        this.items = await api.list();
        this.loaded = true;
      } catch (error) {
        this.error = error instanceof Error ? error.message : String(error);
        throw error;
      } finally {
        this.loading = false;
      }
    },
    async record(path: string, wirePath?: string) {
      const entry = await api.record(normalizeFileKey(path), wirePath);
      this.items = [
        entry,
        ...this.items.filter((saved) => saved.id !== entry.id),
      ]
        .sort((left, right) => right.accessedAt - left.accessedAt)
        .slice(0, 100);
      return entry;
    },
    applyPathRewrite(
      from: string,
      to: string,
      fromWirePath?: string,
      toWirePath?: string
    ) {
      const source = normalizeFileKey(from);
      const destination = normalizeFileKey(to);
      const sourceWire = wireIdentity(fromWirePath || encodePath(source));
      const destinationWire = wireIdentity(
        toWirePath || encodePath(destination)
      );
      this.items = this.items.map((entry) => {
        if (entry.pathVerified === false) return entry;
        const wire = wireIdentity(entry.wirePath || encodePath(entry.path));
        if (!belongsToPrefix(wire, sourceWire)) return entry;
        const suffix = normalizeFileKey(entry.path).slice(source.length);
        const path = normalizeFileKey(`${destination}${suffix}`);
        const name =
          wire === sourceWire
            ? path.split("/").at(-1) || entry.name
            : entry.name;
        return {
          ...entry,
          path,
          name,
          wirePath: destinationWire + wire.slice(sourceWire.length),
        };
      });
    },
    applyPathRemoval(prefix: string, wirePath?: string) {
      const wirePrefix = wireIdentity(
        wirePath || encodePath(normalizeFileKey(prefix))
      );
      this.items = this.items.filter(
        (entry) =>
          entry.pathVerified === false ||
          !belongsToPrefix(
            wireIdentity(entry.wirePath || encodePath(entry.path)),
            wirePrefix
          )
      );
    },
    resetForUser() {
      this.$reset();
    },
  },
});
