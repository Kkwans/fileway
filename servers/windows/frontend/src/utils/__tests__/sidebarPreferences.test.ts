import { describe, expect, it } from "vitest";

import {
  DEFAULT_SIDEBAR_MODULE_ORDER,
  normalizeSidebarPreferences,
  reorderByPreference,
  reorderPreference,
} from "../sidebarPreferences";

describe("sidebar preferences", () => {
  it("adds NAS root first once without resetting an existing category order", () => {
    expect(
      normalizeSidebarPreferences({ categoryOrder: ["system", "shared"] })
        .categoryOrder
    ).toEqual(["nas-root", "system", "shared"]);
    expect(
      normalizeSidebarPreferences({
        categoryOrder: ["shared", "nas-root", "system"],
      }).categoryOrder
    ).toEqual(["shared", "nas-root", "system"]);
  });
  it("uses the required default module order", () => {
    expect(DEFAULT_SIDEBAR_MODULE_ORDER).toEqual([
      "user",
      "system-options",
      "favorites",
      "tags",
      "categories",
      "volumes",
      "logout",
    ]);
  });

  it("keeps saved order, drops stale ids and appends newly available ids", () => {
    expect(
      reorderByPreference(
        [
          { id: "a", label: "A" },
          { id: "b", label: "B" },
          { id: "c", label: "C" },
        ],
        ["c", "missing", "a"],
        (item) => item.id
      ).map((item) => item.id)
    ).toEqual(["c", "a", "b"]);
  });

  it("moves an item without losing the remaining order", () => {
    expect(reorderPreference(["a", "b", "c"], "c", "a")).toEqual([
      "c",
      "a",
      "b",
    ]);
  });

  it("supports placing an item after the target", () => {
    expect(reorderPreference(["a", "b", "c"], "a", "b", "after")).toEqual([
      "b",
      "a",
      "c",
    ]);
  });

  it("normalizes malformed persisted JSON to safe defaults", () => {
    const preferences = normalizeSidebarPreferences("{bad json");
    expect(preferences.moduleOrder).toEqual(DEFAULT_SIDEBAR_MODULE_ORDER);
    expect(preferences.desktopCollapsed).toBe(false);
  });

  it("keeps the desktop icon rail preference while old records stay expanded", () => {
    expect(
      normalizeSidebarPreferences({ desktopCollapsed: true }).desktopCollapsed
    ).toBe(true);
    expect(normalizeSidebarPreferences({}).desktopCollapsed).toBe(false);
  });

  it("preserves saved order and appends the task center entry", () => {
    expect(
      normalizeSidebarPreferences({
        systemOptionOrder: ["search", "files"],
      }).systemOptionOrder
    ).toEqual([
      "search",
      "files",
      "recent",
      "trash",
      "analysis",
      "tasks",
      "new-directory",
      "new-file",
    ]);
  });
});
