import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = resolve(process.cwd(), "src");

describe("重命名弹窗 UI 契约", () => {
  it("使用统一语义图标并保证批量操作触控目标", () => {
    const source = readFileSync(
      resolve(sourceRoot, "components/prompts/Rename.vue"),
      "utf8"
    );
    const styles = readFileSync(resolve(sourceRoot, "css/prompts.css"), "utf8");

    expect(source).not.toContain("material-icons");
    expect(source).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(source).toContain('name="circle-check"');
    expect(source).toContain('name="arrow-right"');
    expect(source).toContain(":name=\"draft.isDir ? 'folder' : 'file'\"");
    expect(source).toContain('class="card floating rename-card"');
    expect(styles).toContain(".rename-card .card-action button");
    expect(styles).toContain("min-height: 44px;");
    expect(styles).toContain(".rename-apply-rule");
    expect(styles).toMatch(/\.rename-apply-rule[\s\S]*?min-height:\s*44px;/);
  });
});
