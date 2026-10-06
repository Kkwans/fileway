import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const thumbnailSource = readFileSync(
  new URL("../../components/files/FileThumbnail.vue", import.meta.url),
  "utf8"
);
const registrySource = readFileSync(
  new URL("../../components/ui/iconRegistry.ts", import.meta.url),
  "utf8"
);
const listingStyles = readFileSync(
  new URL("../../css/listing.css", import.meta.url),
  "utf8"
);

describe("列表资源图标契约", () => {
  it("普通资源使用 AppIcon，不再依赖列表内 Material 字体伪元素", () => {
    expect(thumbnailSource).toContain("getResourceIconName");
    expect(thumbnailSource).not.toContain("material-icons");
    expect(thumbnailSource).toContain('<AppIcon name="refresh"');
    expect(thumbnailSource).toContain(
      'class="file-type-icon app-resource-icon"'
    );
    expect(thumbnailSource).not.toContain(
      '<i v-else class="material-icons file-type-icon"'
    );
    expect(registrySource).toContain('"file-image": FileImage');
    expect(registrySource).toContain('"file-video": FileVideoCamera');
  });

  it("图标尺寸继承统一资源尺寸 Token 并保留风险图标独立分支", () => {
    expect(listingStyles).toMatch(
      /#listing \.file-thumbnail\s*\{[^}]*width:\s*var\(--resource-icon-size\);[^}]*height:\s*var\(--resource-icon-size\)/s
    );
    expect(listingStyles).toMatch(
      /#listing \.file-thumbnail > \.app-resource-icon\s*\{[^}]*width:\s*82%;[^}]*height:\s*82%/s
    );
    expect(thumbnailSource.indexOf("<RiskResourceIcon")).toBeLessThan(
      thumbnailSource.indexOf("app-resource-icon")
    );
  });
});
