import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = resolve(process.cwd(), "src");

describe("风险确认弹窗 UI 契约", () => {
  it("使用统一风险图标并保证确认操作触控尺寸", () => {
    const source = readFileSync(
      resolve(sourceRoot, "components/prompts/RiskConfirm.vue"),
      "utf8"
    );
    const styles = readFileSync(resolve(sourceRoot, "css/prompts.css"), "utf8");

    expect(source).not.toContain("material-icons");
    expect(source).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(source).toContain('"risk-high"');
    expect(source).toContain('"risk-medium"');
    expect(styles).toContain(".risk-confirm-card .card-action button");
    expect(styles).toMatch(
      /\.risk-confirm-card \.card-action button[\s\S]*?min-height:\s*44px;/
    );
  });
});
