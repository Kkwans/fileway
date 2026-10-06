import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const readSource = (relativePath: string) =>
  readFileSync(new URL(`../../${relativePath}`, import.meta.url), "utf8");

describe("媒体预览控件无障碍契约", () => {
  it("图片工具栏的图标按钮提供可访问名称", () => {
    const source = readSource("components/files/ExtendedImage.vue");

    expect(source).toContain('aria-label="放大 (+)"');
    expect(source).toContain('aria-label="缩小 (-)"');
    expect(source).toContain('aria-label="适应屏幕"');
    expect(source).toContain('aria-label="原始大小"');
    expect(source).toContain('aria-label="左旋转"');
    expect(source).toContain('aria-label="右旋转"');
    expect(source).toContain('aria-label="切换缩放"');
  });

  it("EPUB 字号按钮提供可访问名称", () => {
    const source = readSource("views/files/Preview.vue");

    expect(source).toContain('aria-label="缩小字号"');
    expect(source).toContain('aria-label="放大字号"');
  });
});
