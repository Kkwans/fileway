import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = resolve(process.cwd(), "src");
const readSource = (path: string) =>
  readFileSync(resolve(sourceRoot, path), "utf8");

describe("文件工具栏图标契约", () => {
  it("通用操作按钮支持受控的本地图标注册表", () => {
    const actionSource = readSource("components/header/Action.vue");
    const registrySource = readSource("components/ui/iconRegistry.ts");

    expect(actionSource).toContain(':icon="resolvedIconName"');
    expect(actionSource).toContain("appIcon?: AppIconName");
    expect(actionSource).toContain("resolveLegacyAppIcon");
    expect(registrySource).toContain("menu: Menu");
    expect(registrySource).toContain("more: MoreVertical");
    expect(registrySource).toContain("share: Share2");
    expect(registrySource).toContain("terminal: SquareTerminal");
    expect(registrySource).toContain('"view-mosaic": Grid2X2');
  });

  it("文件页首要操作使用同一套语义图标", () => {
    const headerSource = readSource("components/header/HeaderBar.vue");
    const listingSource = readSource("views/files/FileListing.vue");

    expect(headerSource).toContain('app-icon="menu"');
    expect(headerSource).toContain("showMenu && isMobileViewport");
    expect(headerSource).toContain('app-icon="more"');
    expect(headerSource).toContain('name="primary-actions"');
    expect(listingSource).toContain('app-icon="share"');
    expect(listingSource).toContain('app-icon="rename"');
    expect(listingSource).toContain('app-icon="copy"');
    expect(listingSource).toContain('app-icon="move"');
    expect(listingSource).toContain('app-icon="analysis"');
    expect(listingSource).toContain('app-icon="trash"');
    expect(listingSource).toContain('app-icon="download"');
    expect(listingSource).toContain('app-icon="upload"');
    expect(listingSource).toContain('app-icon="file-new"');
    expect(listingSource).toContain('app-icon="folder-new"');
    expect(listingSource).toContain(':app-icon="viewAppIcon"');
    expect(listingSource).toContain('window.matchMedia("(max-width: 899px)")');
    expect(listingSource).toContain(
      "listing can be narrower than the viewport because of the sidebar"
    );
    expect(listingSource).not.toContain(
      "width.value = listing.value.clientWidth"
    );
    expect(listingSource).not.toContain("width.value = next.clientWidth");
    expect(listingSource).toContain(
      'class="view-mode-dropdown header-primary-control"'
    );
    expect(listingSource).toContain(
      'class="sort-dropdown header-primary-control"'
    );
  });

  it("桌面点击区和图形尺寸彼此独立", () => {
    const headerCss = readSource("css/header.css");
    const contextCss = readSource("css/context-menu.css");
    const contextSource = readSource("components/ContextMenu.vue");
    const workspaceCss = readSource("css/workspace-ui.css");
    const headerSource = readSource("components/header/HeaderBar.vue");
    const listingSource = readSource("views/files/FileListing.vue");

    expect(headerCss).toMatch(
      /\.app-header-bar \.action\s*\{[^}]*width:\s*2\.5rem;[^}]*height:\s*2\.5rem;/s
    );
    expect(headerCss).toMatch(
      /\.app-header-bar \.action > \.app-icon\s*\{[^}]*width:\s*1\.25rem;[^}]*height:\s*1\.25rem;/s
    );
    expect(headerCss).not.toContain("transform: scale(1.06)");
    expect(headerCss).not.toMatch(
      /\.app-header-bar \.action:hover\s*\{[^}]*box-shadow:/s
    );
    expect(headerCss).not.toMatch(
      /\.app-header-bar > div div\s*\{[^}]*position:\s*relative;/s
    );
    expect(contextCss).toContain(".context-menu .action > .app-icon");
    expect(contextCss).toContain("position: fixed;");
    expect(contextCss).not.toContain("backdrop-filter");
    expect(contextCss).toMatch(
      /\.context-menu \.action > \.app-icon\s*\{[^}]*width:\s*18px;[^}]*height:\s*18px;/s
    );
    expect(contextCss).toContain("max-height: calc(100dvh - 16px);");
    expect(contextCss).toContain("overflow-y: auto;");
    expect(contextSource).toContain("nextTick(updatePosition)");
    expect(contextSource).toContain("const updatePosition");
    expect(contextSource).toContain("menu.offsetHeight");
    expect(contextSource).toContain(
      'window.addEventListener("resize", updatePosition)'
    );
    expect(workspaceCss).toMatch(
      /@media \(max-width: 899px\)[\s\S]*\.app-header-bar > \.header-trailing > #more\s*\{[^}]*display:\s*inline-grid;[^}]*width:\s*40px;/
    );
    expect(workspaceCss).toContain(
      ".app-header-bar > .header-trailing #dropdown.active"
    );
    expect(workspaceCss).toMatch(
      /\.app-header-bar > \.header-trailing #dropdown\s*\{[^}]*position:\s*fixed;[^}]*display:\s*block;/s
    );
    expect(workspaceCss).toMatch(
      /\.app-header-bar > \.header-trailing #dropdown > div\s*\{[^}]*display:\s*block;[^}]*width:\s*100%;/s
    );
    expect(workspaceCss).toMatch(
      /\.app-header-bar > \.header-trailing #dropdown \.action\s*\{[^}]*display:\s*flex;[^}]*width:\s*100%;[^}]*min-height:\s*42px;/s
    );
    expect(workspaceCss).toMatch(
      /@media \(max-width: 899px\)[\s\S]*\.app-header-bar > \.header-trailing > #more\s*\{[^}]*width:\s*44px;[^}]*height:\s*44px;/
    );
    expect(workspaceCss).toMatch(
      /(?:\.app-header-bar:has\(\.header-instance\)|header:has\(\.header-instance\))\s*>\s*\.header-trailing\s*>\s*#dropdown\.has-primary-actions\s*\{/s
    );
    expect(workspaceCss).toContain("width: min(9rem, calc(100vw - 1rem));");
    expect(workspaceCss).toContain("width: 8.4rem;");
    expect(workspaceCss).toContain(".view-mode-dropdown .dropdown-item > span");
    expect(workspaceCss).toMatch(
      /#dropdown\.has-primary-actions\s+\.dropdown-item/
    );
    expect(workspaceCss).toContain("white-space: nowrap;");
    expect(headerSource).toContain('@click.stop="onDropdownClick"');
    expect(headerSource).toContain("@pointerdown.stop");
    expect(headerSource).toContain("hasDirectActions");
    expect(headerSource).toContain(
      "hasPrimaryActions.value && hasActions.value"
    );
    expect(headerSource).toContain(
      'document.addEventListener("pointerdown", onOutsideInteraction);'
    );
    expect(headerSource).not.toContain(
      'document.addEventListener("pointerdown", onOutsideInteraction, true);'
    );
    expect(headerSource).not.toContain('addEventListener("focusout"');
    expect(headerSource).not.toContain("onMenuFocusout");
    expect(headerSource).toContain("View/sort controls own a nested menu");
    expect(workspaceCss).toMatch(
      /\.view-mode-dropdown \.dropdown-item\[aria-pressed="true"\]\s*\{[^}]*color:\s*var\(--blue/s
    );
    expect(workspaceCss).toContain("text-align: start;");
    expect(workspaceCss).toContain("touch-action: pan-y;");
    expect(workspaceCss).toContain("width: min(10.5rem, calc(100vw - 1rem));");
    expect(listingSource).not.toContain("恢复账号默认排序");
    expect(listingSource).toContain("恢复默认");
  });
});
