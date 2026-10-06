import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = resolve(process.cwd(), "src");
const readSource = (path: string) =>
  readFileSync(resolve(sourceRoot, path), "utf8");

describe("快捷预览 UI 契约", () => {
  it("使用本地语义图标并保持可触达的操作尺寸", () => {
    const source = readSource("components/prompts/QuickPreview.vue");
    const styles = readSource("css/prompts.css");

    expect(source).not.toContain("material-icons");
    expect(source).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(source).toContain("getResourceIconName");
    expect(styles).toContain(".quick-preview-btn > .app-icon");
    expect(styles).toMatch(
      /\.quick-preview-btn\s*\{[\s\S]*min-width:\s*40px;[\s\S]*min-height:\s*40px;/
    );
  });
});
