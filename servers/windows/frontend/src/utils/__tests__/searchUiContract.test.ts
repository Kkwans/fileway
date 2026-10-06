import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

describe("search UI contract", () => {
  it("uses local semantic icons for search and result states", () => {
    const searchPage = readFileSync(
      resolve(process.cwd(), "src/views/SearchPage.vue"),
      "utf8"
    );
    const resultExplorer = readFileSync(
      resolve(process.cwd(), "src/components/search/ResultExplorer.vue"),
      "utf8"
    );
    const filters = readFileSync(
      resolve(process.cwd(), "src/utils/searchFilters.ts"),
      "utf8"
    );

    expect(searchPage).not.toContain("material-icons");
    expect(resultExplorer).not.toContain("material-icons");
    expect(searchPage).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(resultExplorer).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(filters).toContain('icon: "file-image"');
    expect(filters).toContain('icon: "file-code"');
  });

  it("keeps mobile type shortcuts in the page flow instead of horizontal clipping", () => {
    const searchPage = readFileSync(
      resolve(process.cwd(), "src/views/SearchPage.vue"),
      "utf8"
    );

    expect(searchPage).toContain("flex-wrap: wrap");
    expect(searchPage).not.toContain("overflow-x: auto");
  });

  it("keeps desktop search controls large enough for reliable pointer targets", () => {
    const searchPage = readFileSync(
      resolve(process.cwd(), "src/views/SearchPage.vue"),
      "utf8"
    );
    const resultExplorer = readFileSync(
      resolve(process.cwd(), "src/components/search/ResultExplorer.vue"),
      "utf8"
    );

    expect(searchPage).toContain("min-height: 2.5rem;");
    expect(resultExplorer).toContain(".result-explorer-scope button {");
    expect(resultExplorer).toContain("min-height: 2.5rem;");
  });
});
