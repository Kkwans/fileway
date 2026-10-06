import { describe, expect, it } from "vitest";
import {
  listingGridSizeIcon,
  listingSortDirectionIcon,
  listingSortIcon,
  listingViewIcon,
} from "../listingIconSemantics";

describe("listing icon semantics", () => {
  it("keeps view and grid-size controls in the local icon vocabulary", () => {
    expect(listingViewIcon("mosaic")).toBe("view-mosaic");
    expect(listingViewIcon("details")).toBe("view-details");
    expect(listingViewIcon("unknown")).toBe("view-mosaic");
    expect(listingGridSizeIcon("small")).toBe("view-compact-grid");
    expect(listingGridSizeIcon("xlarge")).toBe("view-mosaic");
  });

  it("maps sort controls to readable local icons", () => {
    expect(listingSortIcon("name")).toBe("text");
    expect(listingSortIcon("size")).toBe("chart-storage");
    expect(listingSortIcon("modified")).toBe("clock");
    expect(listingSortIcon("type")).toBe("categories");
    expect(listingSortIcon("unknown")).toBe("sort");
    expect(listingSortDirectionIcon(true)).toBe("arrow-up");
    expect(listingSortDirectionIcon(false)).toBe("arrow-down");
  });
});
