import { describe, expect, it } from "vitest";

import type { SearchResult } from "@/types/file";
import {
  SEARCH_TYPE_OPTIONS,
  applySearchType,
  detectSearchType,
  filterSearchResults,
} from "../searchFilters";

const result = (path: string): SearchResult => ({
  path,
  name: path.split("/").pop() || path,
  dir: false,
  size: 1,
  modified: "",
});

describe("search type filters", () => {
  it("Markdown 筛选使用本地语义文件图标", () => {
    expect(SEARCH_TYPE_OPTIONS.markdown.icon).toBe("file-text");
  });

  it("recognizes composite search types", () => {
    expect(detectSearchType("type:markdown readme")).toBe("markdown");
    expect(detectSearchType("type:config nas")).toBe("config");
    expect(detectSearchType("type:code handler")).toBe("code");
  });

  it("replaces the previous type without changing the keyword", () => {
    expect(applySearchType("type:image holiday", "video")).toBe(
      "type:video holiday"
    );
    expect(applySearchType("holiday", "markdown")).toBe(
      "type:markdown holiday"
    );
    expect(applySearchType("type:image holiday", null)).toBe("holiday");
  });

  it("filters existing untyped results in memory", () => {
    const results = [
      result("README.md"),
      result("config.yaml"),
      result("main.go"),
      result("cover.jpg"),
      { ...result("folder"), dir: true },
    ];

    expect(
      filterSearchResults(results, "config").map((item) => item.path)
    ).toEqual(["config.yaml"]);
    expect(
      filterSearchResults(results, "code").map((item) => item.path)
    ).toEqual(["main.go"]);
    expect(filterSearchResults(results, null)).toEqual(results);
  });
});
