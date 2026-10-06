import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const readSource = (relativePath: string) =>
  readFileSync(new URL(`../../${relativePath}`, import.meta.url), "utf8");

describe("目录选择器图标契约", () => {
  it("目录选择器使用本地 AppIcon 而不是 Material 伪元素", () => {
    const component = readSource("components/prompts/PathPicker.vue");
    const promptsStyles = readSource("css/prompts.css");
    const dashboardStyles = readSource("css/dashboard.css");

    expect(component).toContain('name="folder"');
    expect(component).toContain('class="path-picker__entry-main"');
    expect(component).not.toContain("material-icons");
    expect(promptsStyles).not.toMatch(
      /\.file-list li:before[\s\S]*?Material Icons/
    );
    expect(dashboardStyles).not.toMatch(
      /\.file-list li:before[\s\S]*?Material Icons/
    );
  });
});
