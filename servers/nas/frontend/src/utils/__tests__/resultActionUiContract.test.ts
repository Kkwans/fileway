import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = resolve(process.cwd(), "src");

describe("搜索结果操作弹窗 UI 契约", () => {
  it("使用统一资源图标并保持路径复制触控目标", () => {
    const source = readFileSync(
      resolve(sourceRoot, "components/prompts/ResultAction.vue"),
      "utf8"
    );

    expect(source).not.toContain("material-icons");
    expect(source).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(source).toContain("getResourceIconName");
    expect(source).toContain("resultIcon");
    expect(source).toContain("result-path-copy");
    expect(source).toContain("width: 44px;");
    expect(source).toContain("height: 44px;");
    expect(source).toContain("min-height: 44px;");
  });
});
