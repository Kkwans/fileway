import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const stylesSource = readFileSync(
  fileURLToPath(new URL("../../css/styles.css", import.meta.url)),
  "utf8"
);

describe("媒体预览外壳契约", () => {
  it("标题保持左上高对比布局并与右侧操作区解耦", () => {
    expect(stylesSource).toContain("#previewer header.media-preview-header");
    expect(stylesSource).toMatch(
      /#previewer\s+header\.media-preview-header\s*>\s*\.header-center\s*\{[\s\S]*?justify-content:\s*flex-start;/
    );
    expect(stylesSource).toMatch(
      /#previewer\s+header\.media-preview-header\s*>\s*\.header-center \.header-title\s*\{[\s\S]*?color:\s*#fff;[\s\S]*?text-align:\s*left;/
    );
  });

  it("媒体前后切换图标使用独立网格并做向下光学校正", () => {
    expect(stylesSource).toMatch(
      /#previewer\s*>\s*\.preview-nav\s*>\s*i\s*\{[\s\S]*?display:\s*grid;[\s\S]*?place-items:\s*center;[\s\S]*?transform:\s*translateY\(2px\);/
    );
  });
});
