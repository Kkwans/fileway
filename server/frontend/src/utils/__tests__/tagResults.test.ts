import { describe, expect, it } from "vitest";
import {
  buildResultParentRoute,
  buildTaggedPathUrl,
  getResultParentPath,
  getTaggedPathName,
} from "../tagResults";

describe("tag result paths", () => {
  it("builds a routable URL for a tagged Chinese directory", () => {
    expect(buildTaggedPathUrl("/volume2/电影/精选", true)).toBe(
      "/files/volume2/%E7%94%B5%E5%BD%B1/%E7%B2%BE%E9%80%89/"
    );
  });

  it("uses the final raw path segment for display", () => {
    expect(getTaggedPathName("/volume2/%E7%94%B5%E5%BD%B1/精选")).toBe("精选");
    expect(getTaggedPathName("/volume2/%2Fname")).toBe("%2Fname");
  });

  it("shows only the parent path without repeating the result name", () => {
    expect(
      getResultParentPath(
        "/home/Kkwans/HOME/专题/V/1 (10).mp4",
        "/home/Kkwans/HOME/专题/"
      )
    ).toBe("V/");
    expect(getResultParentPath("V/1 (10).mp4", "/home/Kkwans/HOME/专题/")).toBe(
      "V/"
    );
    expect(getResultParentPath("/docs/with\\backslash/file", "/docs/")).toBe(
      "with\\backslash/"
    );
  });

  it("builds the file route of the containing directory", () => {
    expect(buildResultParentRoute("/home/Kkwans/专题/1 (10).mp4")).toBe(
      "/files/home/Kkwans/%E4%B8%93%E9%A2%98/"
    );
  });
});
