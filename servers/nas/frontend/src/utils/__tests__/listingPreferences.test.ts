import { describe, expect, it } from "vitest";
import type { ListingPreferences } from "@/types/user";
import {
  buildListingSections,
  defaultListingPreferences,
  matchPrefixRule,
  normalizeListingPreferences,
  paginateListingSections,
  validatePrefix,
} from "../listingPreferences";

const item = (name: string, isDir: boolean) => ({
  name,
  isDir,
  path: `/${name}`,
});

describe("listing preferences", () => {
  it("migrates legacy dotfile visibility and defaults every group to expanded", () => {
    const visible = defaultListingPreferences(false);
    const hidden = defaultListingPreferences(true);

    expect(visible.prefixRules.map((rule) => rule.prefix)).toEqual([
      ".",
      "@",
      "#",
      "~",
      "$",
    ]);
    expect(visible.prefixRules[0].visible).toBe(true);
    expect(hidden.prefixRules[0].visible).toBe(false);
    expect(hidden.prefixRules.every((rule) => rule.expanded)).toBe(true);
  });

  it("uses the longest matching prefix", () => {
    const preferences: ListingPreferences = {
      version: 1,
      prefixRules: [
        { prefix: "@", visible: true, expanded: true, order: 0 },
        { prefix: "@@", visible: false, expanded: false, order: 1 },
      ],
    };
    expect(matchPrefixRule("@@cache", preferences.prefixRules)?.prefix).toBe(
      "@@"
    );
  });

  it("groups before pagination and excludes hidden or collapsed items", () => {
    const preferences = normalizeListingPreferences({
      version: 1,
      prefixRules: [
        { prefix: ".", visible: false, expanded: true, order: 0 },
        { prefix: "@", visible: true, expanded: false, order: 1 },
        { prefix: "#", visible: true, expanded: true, order: 2 },
      ],
    });
    const sections = buildListingSections(
      [item(".cache", true), item("@system", true), item("normal", true)],
      [item("#notes", false), item("readme", false)],
      preferences
    );

    expect(sections.map((section) => section.id)).toEqual([
      "prefix:@",
      "prefix:#",
      "directories",
      "files",
    ]);
    expect(sections[0].items.map((entry) => entry.name)).toEqual(["@system"]);
    expect(sections[0].expanded).toBe(false);

    const paged = paginateListingSections(sections, 2);
    expect(paged.map((section) => [section.id, section.items.length])).toEqual([
      ["prefix:@", 0],
      ["prefix:#", 1],
      ["directories", 1],
    ]);
  });

  it("validates custom prefixes", () => {
    expect(validatePrefix("@@")).toBeNull();
    expect(validatePrefix("a/b")).not.toBeNull();
    expect(validatePrefix(" ")).not.toBeNull();
    expect(validatePrefix("123456789")).not.toBeNull();
  });
});
