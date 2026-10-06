import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = resolve(process.cwd(), "src");
const readSource = (path: string) =>
  readFileSync(resolve(sourceRoot, path), "utf8");

describe("文件信息弹窗 UI 契约", () => {
  it("使用统一语义图标并保持信息操作可触达", () => {
    const source = readSource("components/prompts/Info.vue");

    expect(source).not.toContain("material-icons");
    expect(source).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(source).toContain("getResourceIconName");
    expect(source).toContain("infoIcon");
    expect(source).toContain("copy");
    expect(source).toContain("circle-check");
    expect(source).toContain("loader");
    expect(source).toContain(".copy-path-button");
    expect(source).toContain("width: 44px;");
    expect(source).toContain("height: 44px;");
    expect(source).toContain("min-height: 44px;");
    expect(source).toContain(".info-actions .button--primary > .app-icon");
  });
});
