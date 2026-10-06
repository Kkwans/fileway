import { describe, expect, it } from "vitest";
import {
  archiveRoute,
  isBrowsableArchivePath,
  resourceOpenRoute,
} from "../archivePath";

describe("archive routes", () => {
  it("only recognizes backend-supported archive families", () => {
    for (const path of [
      "/a.ZIP",
      "/a.tar",
      "/a.tar.gz",
      "/a.tar.bz2",
      "/a.tar.xz",
      "/a.tar.zst",
    ]) {
      expect(isBrowsableArchivePath(path)).toBe(true);
    }
    for (const path of ["/a.rar", "/a.7z", "/a.gz", "/a.tar.lz4"]) {
      expect(isBrowsableArchivePath(path)).toBe(false);
    }
  });

  it("normalizes archive query paths and preserves ordinary routes", () => {
    expect(archiveRoute("docs//bundle.zip")).toEqual({
      path: "/archive",
      query: { path: "/docs/bundle.zip" },
    });
    expect(
      resourceOpenRoute({
        isDir: false,
        path: "/docs/bundle.zip",
        url: "/files/docs/bundle.zip",
      })
    ).toEqual(archiveRoute("/docs/bundle.zip"));
    expect(
      resourceOpenRoute({
        isDir: true,
        path: "/docs/archive.zip",
        url: "/files/docs/archive.zip/",
      })
    ).toEqual({ path: "/files/docs/archive.zip/" });
  });
});
