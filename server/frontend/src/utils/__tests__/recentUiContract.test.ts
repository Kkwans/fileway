import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

describe("recent page UI contract", () => {
  it("uses the shared local icon component for every recent-page state", () => {
    const source = readFileSync(
      resolve(process.cwd(), "src/views/Recent.vue"),
      "utf8"
    );

    expect(source).not.toContain("material-icons");
    expect(source).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(source).toContain("getResourceIconName");
    expect(source).not.toContain('class="recent-summary"');
  });
});
