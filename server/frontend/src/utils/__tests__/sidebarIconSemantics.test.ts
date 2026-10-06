import { describe, expect, it } from "vitest";
import { resolveCategoryIcon, resolveRiskIcon } from "../sidebarIconSemantics";

describe("sidebar icon semantics", () => {
  it("maps legacy category icon names to the local icon vocabulary", () => {
    expect(resolveCategoryIcon("person")).toBe("user");
    expect(resolveCategoryIcon("group")).toBe("collection");
    expect(resolveCategoryIcon("settings")).toBe("settings");
    expect(resolveCategoryIcon("unknown-category-icon")).toBe("folder");
  });

  it("uses distinct local icons for category risk levels", () => {
    expect(resolveRiskIcon("high")).toBe("risk-high");
    expect(resolveRiskIcon("medium")).toBe("risk-medium");
    expect(resolveRiskIcon("low")).toBe("shield-check");
    expect(resolveRiskIcon("unknown")).toBe("shield-check");
  });
});
