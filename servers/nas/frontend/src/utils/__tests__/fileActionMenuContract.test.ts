import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const readSource = (relativePath: string) =>
  readFileSync(
    fileURLToPath(new URL(`../../${relativePath}`, import.meta.url)),
    "utf8"
  );

describe("文件操作区契约", () => {
  it("详细列表只保留收藏、标签和统一更多菜单", () => {
    const rowSource = readSource("components/files/DetailedTableRow.vue");

    expect(rowSource).toContain("<FileActionMenu");
    expect(rowSource).toContain('<AppIcon name="star"');
    expect(rowSource).toContain('<AppIcon name="tags"');
    expect(rowSource).not.toContain("drive_file_rename_outline");
    expect(rowSource).not.toContain("drive_file_move");
  });

  it("详细网格使用右上操作条，触屏始终可达", () => {
    const itemSource = readSource("components/files/ListingItem.vue");
    const listingStyles = readSource("css/listing.css");
    const workspaceStyles = readSource("css/workspace-ui.css");

    expect(itemSource).toContain("v-if=\"viewMode === 'mosaic'\"");
    expect(itemSource).toContain('@select="runFileAction"');
    expect(listingStyles).toMatch(
      /#listing\.mosaic \.item-controls\s*\{[^}]*top:\s*8px;[^}]*right:\s*8px;[^}]*bottom:\s*auto;[^}]*left:\s*auto;/s
    );
    expect(listingStyles).toMatch(
      /@media \(hover: none\), \(pointer: coarse\)[\s\S]*\.file-action-menu-host\s*\{[^}]*opacity:\s*1;[^}]*pointer-events:\s*auto;/s
    );
    expect(workspaceStyles).toMatch(
      /#listing\.mosaic \.item-icon-button\s*\{[^}]*width:\s*44px;[^}]*min-width:\s*44px;[^}]*height:\s*44px;/s
    );
    expect(workspaceStyles).toMatch(
      /#listing\.details \.details-mobile-list \.item-icon-button,[\s\S]*?width:\s*44px;[\s\S]*?min-width:\s*44px;[\s\S]*?height:\s*44px;/s
    );
  });

  it("菜单支持权限裁剪、键盘导航和焦点恢复", () => {
    const menuSource = readSource("components/files/FileActionMenu.vue");

    expect(menuSource).toContain('role="menu"');
    expect(menuSource).toContain('role="menuitem"');
    expect(menuSource).toContain("props.canRename");
    expect(menuSource).toContain("props.canDownload");
    expect(menuSource).toContain("props.canDelete");
    expect(menuSource).toContain('event.key === "Escape"');
    expect(menuSource).toContain("closeMenu(true)");
    expect(menuSource).toContain("preventScroll: true");
    expect(menuSource).toContain(
      '@keydown.down.prevent.stop="openMenu(false)"'
    );
  });

  it("移动端单击、双击、长按契约保持原实现", () => {
    const itemSource = readSource("components/files/ListingItem.vue");

    expect(itemSource).toContain("getMobileTouchAction");
    expect(itemSource).toContain('action === "open"');
    expect(itemSource).toContain('action === "select"');
    expect(itemSource).toContain("touchMoved.value = true;");
  });
});
