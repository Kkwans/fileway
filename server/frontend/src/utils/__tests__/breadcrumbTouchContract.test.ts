import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

describe("mobile breadcrumb touch contract", () => {
  it("keeps navigable breadcrumb links at least 44px tall", () => {
    const css = readFileSync(
      resolve(process.cwd(), "src/components/Breadcrumbs.vue"),
      "utf8"
    );

    expect(css).toMatch(
      /@media \(max-width: 899px\)[\s\S]*?\.breadcrumb-ancestors \.breadcrumb-label,[\s\S]*?\.breadcrumb-root\s*\{[^}]*min-height:\s*44px;/
    );
    expect(css).toMatch(
      /@media \(max-width: 899px\)[\s\S]*?\.breadcrumb-current > \.breadcrumb-label\s*\{[^}]*min-height:\s*44px;/
    );
  });
});
