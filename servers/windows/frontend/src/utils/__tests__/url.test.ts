import { describe, expect, it } from "vitest";
import { makeRawResource } from "../encodings";
import {
  appendResourceRouteSegment,
  canonicalResourcePath,
  decodePath,
  encodeResourceRoute,
} from "../url";

describe("URL path helpers", () => {
  it("decodes encoded path segments exactly once for resource state", () => {
    expect(decodePath("/volume1/%40appstore/config/blacklist.csv")).toBe(
      "/volume1/@appstore/config/blacklist.csv"
    );
    expect(decodePath("/volume1/100%25-ready.txt")).toBe(
      "/volume1/100%-ready.txt"
    );
  });

  it("uses the canonical path for encoded text resources", async () => {
    const resource = await makeRawResource(
      new Response("a,b\n1,2"),
      "/volume1/%40appstore/config/blacklist.csv"
    );
    expect(resource.path).toBe("/volume1/@appstore/config/blacklist.csv");
    expect(resource.url).toBe("/files/volume1/@appstore/config/blacklist.csv");
    expect(resource.name).toBe("blacklist.csv");
  });

  it("decodes UI routes once before resource mutations", () => {
    expect(
      canonicalResourcePath("/files/volume2/%E7%94%B5%E5%BD%B1/%252Fname")
    ).toBe("/volume2/电影/%2Fname");
    expect(canonicalResourcePath("/volume2/%2Fname")).toBe("/volume2/%2Fname");
  });

  it("re-encodes Chinese and percent-prefixed names exactly once", () => {
    expect(encodeResourceRoute("/volume2/电影/%2Fname")).toBe(
      "/files/volume2/%E7%94%B5%E5%BD%B1/%252Fname"
    );
    expect(
      appendResourceRouteSegment(
        "/files/volume2/%E7%94%B5%E5%BD%B1/",
        "新 名称"
      )
    ).toBe("/files/volume2/%E7%94%B5%E5%BD%B1/%E6%96%B0%20%E5%90%8D%E7%A7%B0");
  });

  it("keeps opaque percent routes for legacy non-UTF8 names", () => {
    expect(
      appendResourceRouteSegment(
        "/files/tmp/%D6%D0%CE%C4.txt",
        "中文-重命名.txt"
      )
    ).toBe(
      "/files/tmp/%D6%D0%CE%C4.txt/%E4%B8%AD%E6%96%87-%E9%87%8D%E5%91%BD%E5%90%8D.txt"
    );
  });
});
