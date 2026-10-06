import { describe, expect, it } from "vitest";
import { normalizeTagPath, rewriteTagPathPrefix } from "../tagPath";

describe("normalizeTagPath", () => {
  it("normalizes raw resource paths without assigning special meaning to directory names", () => {
    expect(normalizeTagPath("/files/volume2/Project/")).toBe(
      "/files/volume2/Project"
    );
    expect(normalizeTagPath("/docs/./drafts/../report.md")).toBe(
      "/docs/report.md"
    );
  });

  it("preserves percent signs and backslashes in raw Linux paths", () => {
    expect(normalizeTagPath("/volume2/%2Fname")).toBe("/volume2/%2Fname");
    expect(normalizeTagPath("/volume2/with\\backslash")).toBe(
      "/volume2/with\\backslash"
    );
    expect(normalizeTagPath("/volume2/%E6%B5%8B%")).toBe("/volume2/%E6%B5%8B%");
  });
});

describe("rewriteTagPathPrefix", () => {
  it("rewrites exact paths and descendants with Linux boundaries", () => {
    expect(rewriteTagPathPrefix("/docs", "/docs", "/archive")).toBe("/archive");
    expect(rewriteTagPathPrefix("/docs/report.md", "/docs", "/archive")).toBe(
      "/archive/report.md"
    );
    expect(rewriteTagPathPrefix("/docs-old/a", "/docs", "/archive")).toBe(null);
    expect(rewriteTagPathPrefix("/Docs/a", "/docs", "/archive")).toBe(null);
  });
});
