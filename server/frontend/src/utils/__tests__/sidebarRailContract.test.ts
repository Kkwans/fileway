import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const readSource = (relativePath: string) =>
  readFileSync(
    fileURLToPath(new URL(`../../${relativePath}`, import.meta.url)),
    "utf8"
  );

describe("桌面侧边栏图标轨契约", () => {
  it("使用用户偏好持久化 72px 桌面折叠状态", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const preferenceSource = readSource("utils/sidebarPreferences.ts");
    const storeSource = readSource("stores/sidebarPreferences.ts");
    const cssSource = readSource("css/sidebar.css");

    expect(preferenceSource).toContain("desktopCollapsed: boolean");
    expect(storeSource).toContain("setDesktopCollapsed");
    expect(sidebarSource).toContain("sidebarPreferencesStore.desktopCollapsed");
    expect(cssSource).toContain("--sidebar-rail-width: 4.5rem");
  });

  it("普通页面直接导航，集合入口使用锚定浮层", () => {
    const sidebarSource = readSource("components/Sidebar.vue");

    expect(sidebarSource).toContain('v-for="option in orderedSystemOptions"');
    expect(sidebarSource).toContain('@click="runSystemOption(option.id)"');
    for (const panel of ["favorites", "tags", "categories", "volumes"]) {
      expect(sidebarSource).toContain(`toggleRailPanel('${panel}', $event)`);
    }
    expect(sidebarSource).toContain('class="sidebar-rail-popover"');
    expect(sidebarSource).toContain('@keydown.esc.stop="closeRailPanel(true)"');
  });

  it("完整侧栏内容栈占满可用高度且不会挤压页脚", () => {
    const cssSource = readSource("css/sidebar.css");

    expect(cssSource).toMatch(
      /nav\.sidebar \.sidebar-personalized-stack\s*\{[^}]*flex:\s*1 0 auto;[^}]*min-height:\s*0;/s
    );
    expect(cssSource).toMatch(
      /nav\.sidebar \.sidebar-collapse-row\s*\{[^}]*margin:\s*0;/s
    );
    expect(cssSource).toMatch(
      /nav\.sidebar \.sidebar-personalized-stack > #logout\s*\{[^}]*margin-top:\s*0\.25rem\s*!important;/s
    );
  });

  it("图标由本地 AppIcon 包装层管理并提供可访问标签和 Tooltip", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const iconRegistrySource = readSource("components/ui/iconRegistry.ts");

    expect(sidebarSource).toContain("<AppIcon");
    expect(sidebarSource).toContain(':data-tooltip="option.label"');
    expect(sidebarSource).toContain(':aria-label="option.label"');
    expect(iconRegistrySource).toContain('from "@lucide/vue"');
  });

  it("折叠轨不在账户入口后绘制孤立分隔线", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const profileStart = sidebarSource.indexOf("sidebar-rail-profile");
    const systemOptionsStart = sidebarSource.indexOf(
      'v-for="option in orderedSystemOptions"'
    );
    const between = sidebarSource.slice(profileStart, systemOptionsStart);

    expect(between).not.toContain("sidebar-rail-divider");
  });

  it("展开侧栏与图标轨使用稳定的视觉尺寸而不缩小点击区", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const sectionHeaderSource = readSource(
      "components/sidebar/SidebarSectionHeader.vue"
    );
    const groupHeaderSource = readSource(
      "components/sidebar/SidebarGroupHeader.vue"
    );
    const cssSource = readSource("css/sidebar.css");
    const iconRegistrySource = readSource("components/ui/iconRegistry.ts");

    expect(cssSource).toContain("--sidebar-primary-icon-size: 1.375rem");
    expect(cssSource).toContain("--sidebar-primary-icon-column: 2rem");
    expect(cssSource).toContain("--sidebar-rail-icon-size: 1.375rem");
    expect(cssSource).toMatch(
      /\.sidebar-rail-action\s*\{[^}]*width:\s*2\.75rem;[^}]*height:\s*2\.75rem;/s
    );
    expect(sectionHeaderSource).toContain('name="chevron-right"');
    expect(groupHeaderSource).toContain('name="chevron-right"');
    expect(sectionHeaderSource).not.toContain("expand_more");
    expect(groupHeaderSource).not.toContain("expand_more");
    expect(sidebarSource).toContain('aria-label="新建收藏分组"');
    expect(sidebarSource).not.toContain('aria-label="清空收藏夹"');
    expect(iconRegistrySource).toContain("file: File");
    expect(iconRegistrySource).not.toContain("file: Files");
  });

  it("展开侧栏共享外边界且不继承 action 图标的额外 padding", () => {
    const cssSource = readSource("css/sidebar.css");

    expect(cssSource).toContain("--sidebar-inline-gutter: 0.5rem");
    expect(cssSource).toMatch(
      /nav\.sidebar \.action > \.app-icon,[\s\S]*?nav\.sidebar \.action > \.material-icons\s*\{[\s\S]*?box-sizing:\s*border-box;[\s\S]*?padding:\s*0;/s
    );
  });

  it("导航图标使用语义图形并共享同一视觉尺寸", () => {
    const cssSource = readSource("css/sidebar.css");
    const iconRegistrySource = readSource("components/ui/iconRegistry.ts");

    expect(iconRegistrySource).toContain("FolderTree");
    expect(iconRegistrySource).toContain("SlidersHorizontal");
    expect(iconRegistrySource).toContain("HardDrive");
    expect(cssSource).toContain("--sidebar-icon-size: 1.375rem");
    expect(cssSource).toContain("--sidebar-icon-column: 2rem");
    expect(cssSource).toContain(
      "nav.sidebar .favorite-item > .favorite-icon:not(.favorite-drag-handle),"
    );
    expect(cssSource).toContain(
      "nav.sidebar .sidebar-section-header .section-toggle > .app-icon,"
    );
    expect(
      cssSource.match(/width: var\(--sidebar-icon-size\);/g)
    ).not.toBeNull();
    expect(
      cssSource.match(/height: var\(--sidebar-icon-size\);/g)
    ).not.toBeNull();
  });

  it("侧栏辅助文字不低于可读的字号层级", () => {
    const cssSource = readSource("css/sidebar.css");

    expect(cssSource).toMatch(
      /nav\.sidebar \.favorite-path\s*\{[^}]*font-size:\s*0\.6875rem;[^}]*line-height:\s*1\.25;/s
    );
    expect(cssSource).toMatch(
      /nav\.sidebar \.volume-usage\s*\{[^}]*font-size:\s*0\.6875rem;[^}]*font-variant-numeric:\s*tabular-nums;/s
    );
    expect(cssSource).toMatch(
      /nav\.sidebar > \.credits:last-child\s*\{[^}]*font-size:\s*0\.6875rem;[^}]*line-height:\s*1\.4;/s
    );
  });

  it("移动端保持完整抽屉并关闭桌面图标轨", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const cssSource = readSource("css/sidebar.css");

    expect(sidebarSource).toContain('window.matchMedia("(min-width: 900px)")');
    expect(cssSource).toMatch(
      /@media \(max-width: 899px\)[\s\S]*\.sidebar-icon-rail\s*\{[\s\S]*display:\s*none;/
    );
  });
});
