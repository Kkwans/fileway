import { describe, expect, it } from "vitest";
import {
  replaceTagByName,
  tagReferences,
  tagAssociationBody,
} from "../tagPersistence";
import { favoriteIdentity } from "../favoritePersistence";

describe("tag reference provenance", () => {
  it("preserves same-display bytes and unknown versus genuine UTF8 replacement characters", () => {
    const refs = tagReferences({
      paths: [],
      pathRefs: [
        {
          path: "/中文.txt",
          wirePath: "/%D6%D0%CE%C4.txt",
          pathVerified: true,
        },
        {
          path: "/中文.txt",
          wirePath: "/%E4%B8%AD%E6%96%87.txt",
          pathVerified: true,
        },
        { path: "/lost�", pathVerified: false },
        { path: "/lost�", wirePath: "/lost%EF%BF%BD", pathVerified: true },
      ],
    });
    expect(refs).toHaveLength(4);
    expect(favoriteIdentity(refs[0])).not.toBe(favoriteIdentity(refs[1]));
    expect(favoriteIdentity(refs[2])).toBeNull();
    expect(favoriteIdentity(refs[3])).not.toBeNull();
    expect(tagAssociationBody(refs[0])).not.toHaveProperty("path");
    expect(tagAssociationBody(refs[1]).path).toBe("/中文.txt");
    expect(() => tagAssociationBody(refs[2])).toThrow("无法确认");
    expect(tagReferences({ paths: ["/file ", "/lost�"] })[0].path).toBe(
      "/file "
    );
  });
});

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
