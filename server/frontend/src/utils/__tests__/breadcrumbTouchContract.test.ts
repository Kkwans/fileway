import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

describe("mobile breadcrumb touch contract", () => {
  it("keeps navigable breadcrumb links at least 44px tall", () => {
    const css = readFileSync(
      resolve(process.cwd(), "src/css/workspace-ui.css"),
      "utf8"
    );

    expect(css).toMatch(
      /@media \(max-width: 899px\)[\s\S]*?\.breadcrumbs > a,[\s\S]*?\.breadcrumbs > span > a\s*\{[^}]*min-height:\s*44px;/
    );
  });
});
