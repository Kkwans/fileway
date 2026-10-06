import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = resolve(process.cwd(), "src");

describe("新建路径预览 UI 契约", () => {
  it("使用统一文件语义图标，不依赖 Material Icons", () => {
    const source = readFileSync(
      resolve(sourceRoot, "components/prompts/CreateFilePath.vue"),
      "utf8"
    );

    expect(source).not.toContain("material-icons");
    expect(source).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(source).toContain('"folder"');
    expect(source).toContain('"file"');
    expect(source).toContain('class="path-item"');
  });
});
