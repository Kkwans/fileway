import { describe, expect, it } from "vitest";
import type { Tag } from "@/stores/tags";
import {
  TAG_COLORS,
  getUsedTagColors,
  isTagColorAvailable,
  normalizeTagColor,
} from "../tagColors";

const tags: Tag[] = [
  {
    id: "tag-red",
    name: "红色标签",
    color: "#F04438",
    paths: [],
    createdAt: 1,
  },
  {
    id: "tag-blue",
    name: "蓝色标签",
    color: "#2E90FA",
    paths: [],
    createdAt: 2,
  },
];

describe("tag color uniqueness", () => {
  it("uses a distinct rainbow-ordered preset palette", () => {
    expect(TAG_COLORS).toEqual([
      "#E5484D",
      "#D95876",
      "#F06A5B",
      "#F28C28",
      "#DDAA1D",
      "#D6BE21",
      "#86B83E",
      "#35A867",
      "#2AA889",
      "#28AFC0",
      "#3A9BD9",
      "#3F72D8",
      "#5B62D9",
      "#7656C9",
      "#9B4DB5",
      "#C34F90",
      "#758195",
    ]);
    expect(new Set(TAG_COLORS).size).toBe(TAG_COLORS.length);
  });

  it("normalizes equivalent hexadecimal colors before comparison", () => {
    expect(normalizeTagColor("#f04438")).toBe("#F04438");
    expect(normalizeTagColor(" f04438 ")).toBe("#F04438");
  });

  it("marks colors used by another tag as unavailable", () => {
    expect(getUsedTagColors(tags)).toEqual(new Set(["#F04438", "#2E90FA"]));
    expect(isTagColorAvailable(tags, "#f04438")).toBe(false);
    expect(isTagColorAvailable(tags, "#7F56D9")).toBe(true);
  });

  it("allows an edited tag to keep its current color", () => {
    expect(isTagColorAvailable(tags, "#f04438", "tag-red")).toBe(true);
    expect(isTagColorAvailable(tags, "#2E90FA", "tag-red")).toBe(false);
  });
});
