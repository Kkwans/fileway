import { describe, expect, it } from "vitest";
import { reorderFavoriteItems } from "@/utils/sidebarFavorites";

const favorites = [
  { id: "a", groupId: "", order: 0 },
  { id: "b", groupId: "g1", order: 1 },
  { id: "c", groupId: "g1", order: 2 },
  { id: "d", groupId: "g2", order: 3 },
];

describe("reorderFavoriteItems", () => {
  it("支持在同一分组内向后调整顺序", () => {
    expect(reorderFavoriteItems(favorites, "b", "c", "after")).toEqual([
      { id: "a", groupId: "", order: 0 },
      { id: "c", groupId: "g1", order: 1 },
      { id: "b", groupId: "g1", order: 2 },
      { id: "d", groupId: "g2", order: 3 },
    ]);
  });

  it("拖到其他分组条目前会同步目标分组", () => {
    expect(reorderFavoriteItems(favorites, "a", "d", "before")).toEqual([
      { id: "b", groupId: "g1", order: 0 },
      { id: "c", groupId: "g1", order: 1 },
      { id: "a", groupId: "g2", order: 2 },
      { id: "d", groupId: "g2", order: 3 },
    ]);
  });

  it("无效拖放不改变列表", () => {
    expect(reorderFavoriteItems(favorites, "a", "missing", "before")).toEqual(
      favorites
    );
  });
});
