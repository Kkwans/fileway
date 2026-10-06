import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const archiveSource = readFileSync(
  fileURLToPath(new URL("../../views/Archive.vue", import.meta.url)),
  "utf8"
);

describe("压缩包浏览无障碍契约", () => {
  it("目录展开按钮保留至少 32px 的触摸目标", () => {
    expect(archiveSource).toMatch(
      /\.archive-expand,\s*\.archive-expand-spacer\s*\{[\s\S]*?width:\s*32px;/
    );
    expect(archiveSource).toMatch(
      /\.archive-expand\s*\{[\s\S]*?height:\s*32px;/
    );
  });

  it("搜索输入复用至少 36px 高的标签点击区域", () => {
    expect(archiveSource).toMatch(
      /\.archive-browser-actions label\s*\{[\s\S]*?min-height:\s*36px;/
    );
  });

  it("归档条目整行选择标签保留至少 32px 的触摸高度", () => {
    expect(archiveSource).toMatch(
      /\.archive-entry-name > label\s*\{[\s\S]*?min-height:\s*32px;/
    );
  });
});
