import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const packageSource = readFileSync(
  new URL("../../../package.json", import.meta.url),
  "utf8"
);
const iconRegistrySource = readFileSync(
  new URL("../../components/ui/iconRegistry.ts", import.meta.url),
  "utf8"
);
const thumbnailSource = readFileSync(
  new URL("../../components/files/FileThumbnail.vue", import.meta.url),
  "utf8"
);
const listingItemSource = readFileSync(
  new URL("../../components/files/ListingItem.vue", import.meta.url),
  "utf8"
);
const detailedRowSource = readFileSync(
  new URL("../../components/files/DetailedTableRow.vue", import.meta.url),
  "utf8"
);
const fileListingSource = readFileSync(
  new URL("../../views/files/FileListing.vue", import.meta.url),
  "utf8"
);
const categoriesStoreSource = readFileSync(
  new URL("../../stores/categories.ts", import.meta.url),
  "utf8"
);
const listingStyles = readFileSync(
  new URL("../../css/listing.css", import.meta.url),
  "utf8"
);
const workspaceStyles = readFileSync(
  new URL("../../css/workspace-ui.css", import.meta.url),
  "utf8"
);

describe("risk presentation contract", () => {
  it("pins Lucide locally and exposes only semantic wrapper icons", () => {
    expect(JSON.parse(packageSource).dependencies["@lucide/vue"]).toBe(
      "1.30.0"
    );
    expect(iconRegistrySource).toContain('from "@lucide/vue"');
    expect(iconRegistrySource).toContain('"folder-maintenance": FolderCog');
    expect(iconRegistrySource).toContain('"folder-protected": FolderLock');
  });

  it("preserves real media thumbnails before rendering native risk icons", () => {
    expect(thumbnailSource.indexOf('v-if="displaySource"')).toBeLessThan(
      thumbnailSource.indexOf('v-else-if="nonLowRiskLevel"')
    );
    expect(thumbnailSource).toContain("<RiskResourceIcon");
    expect(listingItemSource).toContain("<RiskIndicator");
    expect(detailedRowSource).toContain("<RiskIndicator");
    expect(fileListingSource.match(/v-bind="item"/g)).toHaveLength(3);
  });

  it("removes frontend classification and every legacy overlay dot", () => {
    expect(categoriesStoreSource).not.toContain("getRiskLevel");
    expect(listingStyles).not.toContain("risk-badge");
    expect(listingStyles).toMatch(
      /#listing \.file-thumbnail > \.risk-resource-icon \{[\s\S]*?display: grid;[\s\S]*?place-items: center;/
    );
    expect(workspaceStyles).not.toContain("risk-badge");
    expect(listingItemSource).not.toContain("risk-badge");
    expect(detailedRowSource).not.toContain("risk-badge");
  });

  it("keeps media risk semantics visible in compact lists", () => {
    expect(listingStyles).not.toMatch(
      /#listing\.compact-list[^}]*\.risk-inline-indicator\s*\{[^}]*display:\s*none/s
    );
    expect(listingStyles).toMatch(
      /#listing\.compact-list \.risk-inline-indicator\s*\{[^}]*width:\s*22px/s
    );
    expect(listingItemSource).toContain("inlineRiskLevel");
  });

  it("keeps native risk icon colors above the shared thumbnail color rule", () => {
    expect(listingStyles).toMatch(
      /#listing \.file-thumbnail > \.risk-resource-icon\.risk-resource-icon--medium\s*\{[^}]*color:/s
    );
    expect(listingStyles).toMatch(
      /#listing \.file-thumbnail > \.risk-resource-icon\.risk-resource-icon--high\s*\{[^}]*color:/s
    );
  });

  it("uses one inherited resource icon size contract across responsive layouts", () => {
    expect(listingStyles).toContain("--resource-icon-size: 64px");
    expect(listingStyles).toMatch(
      /#listing \.file-thumbnail\s*\{[^}]*width:\s*var\(--resource-icon-size\);[^}]*height:\s*var\(--resource-icon-size\)/s
    );
    expect(listingStyles).toMatch(
      /#listing\.details \.item\s*\{[^}]*--resource-icon-size:\s*52px/s
    );
    expect(listingStyles).toMatch(
      /#listing\.mosaic \.item\s*\{[^}]*--resource-icon-size:\s*80px/s
    );
    expect(listingStyles).toMatch(
      /#listing\.compact-grid \.item\s*\{[^}]*--resource-icon-size:\s*64px/s
    );
  });

  it("普通文件夹使用统一的轻量填充图标，不影响真实媒体缩略图", () => {
    expect(thumbnailSource).toContain("app-resource-icon--folder");
    expect(listingStyles).toMatch(
      /#listing \.file-thumbnail > \.app-resource-icon--folder\s*\{[\s\S]*?fill:\s*color-mix\(/s
    );
    expect(listingStyles).toMatch(
      /#listing \.file-thumbnail > img\s*\{[\s\S]*?object-fit:\s*cover;/s
    );
  });
});
