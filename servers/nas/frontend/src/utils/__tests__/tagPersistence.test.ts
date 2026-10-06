import { describe, expect, it } from "vitest";
import { replaceTagByName } from "../tagPersistence";

describe("replaceTagByName", () => {
  it("用服务端返回的标签替换临时标签", () => {
    const tags = [
      {
        id: "temporary",
        name: "工作",
        color: "#2196F3",
        paths: [],
        createdAt: 1,
      },
    ];
    const saved = {
      id: "server",
      name: "工作",
      color: "#2196F3",
      paths: [],
      createdAt: 2,
    };

    expect(replaceTagByName(tags, saved)).toEqual([saved]);
  });
});
