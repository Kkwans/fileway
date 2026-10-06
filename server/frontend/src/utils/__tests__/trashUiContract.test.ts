import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

describe("trash page UI contract", () => {
  it("uses the shared local icon component and semantic resource icons", () => {
    const source = readFileSync(
      resolve(process.cwd(), "src/views/Trash.vue"),
      "utf8"
    );
    const styles = readFileSync(
      resolve(process.cwd(), "src/css/trash.css"),
      "utf8"
    );

    expect(source).not.toContain("material-icons");
    expect(source).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(source).toContain("getResourceIconName");
    expect(source).toContain(
      'import AppDialog from "@/components/ui/AppDialog.vue"'
    );
    expect(source).toContain('title="永久删除项目？"');
    expect(source).toContain('title="原位置已有同名项目"');
    expect(source).not.toContain("trash-inline-question");
    expect(styles).not.toContain("material-icons");
    expect(styles).toContain(".app-icon");
    expect(styles).not.toContain(".trash-dialog-backdrop");
  });
});
