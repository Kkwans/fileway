import { describe, expect, it, vi } from "vitest";
import { mediaResourceIndex, mediaResourceURL } from "../mediaResource";

vi.mock("@/api/utils", () => ({
  createURL: (prefix: string) =>
    `https://fixture.invalid/base/${prefix}?inline=true`,
}));

describe("media resource byte identity", () => {
  it("uses distinct same-display sources for previous/next indexing", () => {
    const opaque = { path: "/中文.jpg", wirePath: "/%D6%D0%CE%C4.jpg" };
    const utf8 = { path: opaque.path, wirePath: "/%E4%B8%AD%E6%96%87.jpg" };
    expect(mediaResourceIndex([opaque, utf8], utf8)).toBe(1);
    expect(mediaResourceIndex([opaque, utf8], opaque)).toBe(0);
    expect(mediaResourceIndex([opaque], utf8)).toBe(-1);
    expect(
      mediaResourceIndex([{ path: "/lost�.jpg", pathVerified: false }], {
        path: "/lost�.jpg",
      })
    ).toBe(-1);
    expect(mediaResourceURL("api/raw", opaque)).toBe(
      "https://fixture.invalid/base/api/raw/%D6%D0%CE%C4.jpg?inline=true"
    );
  });
});
