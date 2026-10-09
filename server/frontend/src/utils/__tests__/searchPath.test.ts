import { describe, expect, it } from "vitest";
import {
  buildFilesRouteFromSearchBase,
  buildTagSearchQuery,
  getSearchPromptFromRoute,
  normalizeFilesRouteBase,
  normalizeSearchBase,
} from "../searchPath";

describe("normalizeSearchBase", () => {
  it("keeps an absolute NAS path when it is already a resource path", () => {
    expect(normalizeSearchBase("/home/Kkwans/电影")).toBe("/home/Kkwans/电影/");
    expect(normalizeSearchBase("/home/Kkwans/%2Fname")).toBe(
      "/home/Kkwans/%2Fname/"
    );
  });

  it("decodes and removes an explicitly identified UI files route", () => {
    expect(
      normalizeFilesRouteBase("/files/home/Kkwans/%E7%94%B5%E5%BD%B1")
    ).toBe("/home/Kkwans/电影/");
    expect(normalizeFilesRouteBase("/files/home/Kkwans/%252Fname")).toBe(
      "/home/Kkwans/%2Fname/"
    );
    expect(normalizeFilesRouteBase("/settings/profile")).toBe("/");
  });

  it("uses the root path for empty or invalid input", () => {
    expect(normalizeSearchBase("")).toBe("/");
    expect(normalizeFilesRouteBase("/files")).toBe("/");
  });
});

describe("search route helpers", () => {
  it("keeps a byte-identified parent across tag scope changes", () => {
    expect(buildTagSearchQuery("/中文", "global", "/%D6%D0%CE%C4")).toEqual({
      base: "/中文/",
      scope: "global",
      baseWirePath: "/%D6%D0%CE%C4",
    });
    expect(buildFilesRouteFromSearchBase("/中文/", "/%D6%D0%CE%C4")).toBe(
      "/files/%D6%D0%CE%C4/"
    );
    expect(normalizeSearchBase("/folder/  ")).toBe("/folder/  /");
  });
  it("returns to the exact file directory represented by the search base", () => {
    expect(buildFilesRouteFromSearchBase("/")).toBe("/files/");
    expect(buildFilesRouteFromSearchBase("/home/Kkwans/电影")).toBe(
      "/files/home/Kkwans/%E7%94%B5%E5%BD%B1/"
    );
    expect(buildFilesRouteFromSearchBase("/home/Kkwans/%2Fname")).toBe(
      "/files/home/Kkwans/%252Fname/"
    );
  });

  it("keeps the return directory when switching tag search scope", () => {
    expect(buildTagSearchQuery("/home/Kkwans/电影", "global")).toEqual({
      base: "/home/Kkwans/电影/",
      scope: "global",
    });
  });

  it("clears a stale keyword when the route switches to tag filtering", () => {
    expect(getSearchPromptFromRoute("Video", "tag-1")).toBe("");
    expect(getSearchPromptFromRoute("Video", undefined)).toBe("Video");
    expect(getSearchPromptFromRoute(["Video"], undefined)).toBe("");
  });
});
