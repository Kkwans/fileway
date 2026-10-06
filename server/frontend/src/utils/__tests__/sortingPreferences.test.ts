import { describe, expect, it } from "vitest";
import {
  GUEST_SORTING_KEY,
  normalizeSorting,
  readGuestSorting,
  writeGuestSorting,
} from "../sortingPreferences";

describe("sorting preferences", () => {
  it("preserves an explicit descending preference", () => {
    expect(normalizeSorting({ by: "name", asc: false })).toEqual({
      by: "name",
      asc: false,
    });
  });

  it("rejects unknown fields and malformed directions", () => {
    expect(normalizeSorting({ by: "path", asc: true })).toBeNull();
    expect(normalizeSorting({ by: "name", asc: "true" })).toBeNull();
  });

  it("uses the NAS guest namespace and tolerates corrupt storage", () => {
    const values = new Map<string, string>();
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    };
    writeGuestSorting({ by: "modified", asc: false }, storage);
    expect(values.has(GUEST_SORTING_KEY)).toBe(true);
    expect(readGuestSorting(storage)).toEqual({ by: "modified", asc: false });
    values.set(GUEST_SORTING_KEY, "{");
    expect(readGuestSorting(storage)).toBeNull();
  });
});
