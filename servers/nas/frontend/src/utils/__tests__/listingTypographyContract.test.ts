import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const listingStyles = readFileSync(
  new URL("../../css/listing.css", import.meta.url),
  "utf8"
);
const workspaceStyles = readFileSync(
  new URL("../../css/workspace-ui.css", import.meta.url),
  "utf8"
);

describe("文件列表文字与图标节奏契约", () => {
  it("紧凑列表保持可读字号、统一图标列和时间基线", () => {
    expect(listingStyles).toMatch(
      /#listing\.compact-list \.item\s*\{[\s\S]*?--resource-icon-size:\s*34px;[\s\S]*?grid-template-columns:\s*42px minmax\(0, 1fr\) auto;[\s\S]*?min-height:\s*48px;/
    );
    expect(listingStyles).toMatch(
      /#listing\.compact-list \.item-title-row \.item-name\s*\{[\s\S]*?font-size:\s*14px;/
    );
    expect(listingStyles).toMatch(
      /#listing\.compact-list \.detail-meta\s*\{[\s\S]*?font-size:\s*12px;/
    );
    expect(listingStyles).toMatch(
      /#listing\.compact-list \.modified\s*\{[\s\S]*?font-size:\s*12px;[\s\S]*?font-variant-numeric:\s*tabular-nums;/
    );
    expect(workspaceStyles).toMatch(
      /#listing\.compact-list \.item\s*\{[\s\S]*?grid-template-columns:\s*42px minmax\(0, 1fr\);[\s\S]*?min-height:\s*48px;/
    );
  });
});
