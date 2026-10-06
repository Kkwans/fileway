import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = resolve(process.cwd(), "src");

describe("冲突解决弹窗 UI 契约", () => {
  it("使用统一语义图标并保证批量决策按钮可触达", () => {
    const source = readFileSync(
      resolve(sourceRoot, "components/prompts/ResolveConflict.vue"),
      "utf8"
    );

    expect(source).not.toContain("material-icons");
    expect(source).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(source).toContain("result-buttons");
    expect(source).toContain("min-height: 44px;");
    expect(source).toContain("circle-alert");
    expect(source).toContain('name="tasks"');
  });
});
