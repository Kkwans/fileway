import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const baseCss = readFileSync(
  new URL("../../css/base.css", import.meta.url),
  "utf8"
);
const fileListingSource = readFileSync(
  new URL("../../views/files/FileListing.vue", import.meta.url),
  "utf8"
);

describe("本地图标对齐契约", () => {
  it("让 SVG 图标脱离文字基线并保持稳定的内联尺寸", () => {
    expect(baseCss).toContain(".app-icon {");
    expect(baseCss).toContain("display: inline-block;");
    expect(baseCss).toContain("vertical-align: middle;");
    expect(baseCss).toContain("flex: 0 0 auto;");
  });

  it("视图和排序菜单使用真实 SVG 状态图标", () => {
    expect(fileListingSource).not.toMatch(
      /v-if="(?:currentViewMode|compactGridSize) === [^\"]+"\s*\n?\s*name="circle-check"/
    );
    expect(fileListingSource).toContain(
      ':aria-pressed="currentViewMode === mode.value"'
    );
    expect(fileListingSource).toContain(
      ':aria-pressed="compactGridSize === size.value"'
    );
    expect(fileListingSource).toMatch(
      /<AppIcon[\s\S]{0,100}class="sort-arrow"[\s\S]{0,120}:name="listingSortDirectionIcon/
    );
    expect(fileListingSource).not.toMatch(
      /<i\s+v-if="currentViewMode === mode\.value"\s+class="check"/s
    );
  });
});
