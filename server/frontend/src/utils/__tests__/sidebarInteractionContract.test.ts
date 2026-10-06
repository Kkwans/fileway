import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const readSource = (relativePath: string) =>
  readFileSync(
    fileURLToPath(new URL(`../../${relativePath}`, import.meta.url)),
    "utf8"
  );

const readSidebarFinalCss = () => {
  const source = readSource("css/sidebar.css");
  const marker = "/* PC 侧边栏最终契约";
  const start = source.lastIndexOf(marker);
  if (start < 0) throw new Error("缺少收敛后的侧边栏 CSS 区块");
  return source.slice(start);
};

describe("侧边栏分组交互契约", () => {
  it("收藏夹分组和目录分类都由整行切换展开状态", () => {
    const headerSource = readSource(
      "components/sidebar/SidebarGroupHeader.vue"
    );
    const sidebarSource = readSource("components/Sidebar.vue");

    expect(headerSource).toContain("@click=\"$emit('toggle')\"");
    expect(headerSource).not.toContain("$emit('primary')");
    expect(sidebarSource).not.toContain("navigateCategoryFirst");
  });

  it("分组展开使用独立语义按钮，不能把删除等操作按钮嵌套在按钮内部", () => {
    const headerSource = readSource(
      "components/sidebar/SidebarGroupHeader.vue"
    );

    expect(headerSource).toContain('class="sidebar-group-toggle"');
    expect(headerSource).toContain('type="button"');
    expect(headerSource).not.toContain('role="button"');
    expect(headerSource).not.toContain('tabindex="0"');
    expect(headerSource).toContain(':aria-expanded="expanded"');
    expect(headerSource).toContain("@click=\"$emit('toggle')\"");
  });

  it("收藏夹分组移除数量徽标并保留操作命中区", () => {
    const headerSource = readSource(
      "components/sidebar/SidebarGroupHeader.vue"
    );
    const refinementCssSource = readSidebarFinalCss();

    expect(headerSource).toContain('class="sidebar-group-actions"');
    expect(headerSource).not.toContain("category-count");
    expect(headerSource).not.toContain(":count=");
    expect(refinementCssSource).toMatch(
      /\.sidebar-group-header\.sidebar-level-two\s*\{[^}]*display:\s*grid;[^}]*grid-template-columns:\s*26px minmax\(0, 1fr\) 40px 40px;/s
    );
    expect(refinementCssSource).not.toContain(".sidebar-group-primary");
    expect(refinementCssSource).not.toContain(".sidebar-group-tools");
    expect(refinementCssSource).toMatch(
      /\.category-group-header\.sidebar-level-two\s*\{[^}]*grid-template-columns:\s*26px minmax\(0, 1fr\) 40px 40px;/s
    );
  });

  it("拖拽区位于滚动容器外并支持指针捕获和键盘", () => {
    const source = readSource("components/Sidebar.vue");
    const css = readSource("css/sidebar.css");
    expect(source.indexOf('class="sidebar-resize-handle"')).toBeGreaterThan(
      source.indexOf("</nav>")
    );
    expect(source).toContain('role="separator"');
    expect(source).toContain("setPointerCapture(event.pointerId)");
    expect(source).toContain('@keydown="resizeByKeyboard"');
    expect(css).toMatch(
      /\.sidebar-frame > \.sidebar-resize-handle\s*\{[^}]*inset-inline-end: -10px;[^}]*width: 20px;/s
    );
    expect(css).not.toContain("nav.sidebar .sidebar-resize-handle");
  });

  it("默认只展开收藏夹和存储卷", () => {
    const sidebarSource = readSource("components/Sidebar.vue");

    expect(sidebarSource).toMatch(
      /const collapsedSections = reactive\(\{\s*systemOptions: true,\s*favorites: false,\s*tags: true,\s*volumes: false,\s*categories: true,/s
    );
  });

  it("折叠和展开入口都位于登出之前的独立底部区域", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const cssSource = readSource("css/sidebar.css");

    expect(sidebarSource).toContain('class="sidebar-rail-footer"');
    expect(sidebarSource).toContain('class="sidebar-collapse-row"');
    expect(sidebarSource).toContain("sidebar-collapse-label");
    expect(cssSource).toContain(".sidebar-rail-footer");
    expect(cssSource).toContain(".sidebar-footer-actions");
  });

  it("移动端侧栏和遮罩拥有独立的触摸命中与滚动边界", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const cssSource = readSource("css/sidebar.css");

    expect(sidebarSource).toContain('class="overlay sidebar-overlay"');
    expect(sidebarSource).toContain("@pointerdown.stop");
    expect(cssSource).toContain(".sidebar-overlay");
    expect(cssSource).toContain(".sidebar-frame > nav.sidebar");
    expect(cssSource).toContain("pointer-events: auto;");
    expect(cssSource).toContain("touch-action: pan-y;");
    expect(cssSource).toContain("overscroll-behavior: contain;");
  });

  it("折叠态保留帮助入口并使用完整退出登录文案", () => {
    const sidebarSource = readSource("components/Sidebar.vue");

    expect(sidebarSource).toContain("sidebar-rail-help");
    expect(sidebarSource).toContain('data-tooltip="帮助"');
    expect(sidebarSource).toContain("退出登录");
    expect(sidebarSource).not.toContain('data-tooltip="登出"');
  });

  it("目录分类隐藏数量徽标以给单行名称留出空间", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const categoryStart = sidebarSource.indexOf(
      'class="category-group-header"'
    );
    const categoryEnd = sidebarSource.indexOf("/>", categoryStart);
    const categoryHeader = sidebarSource.slice(categoryStart, categoryEnd);

    expect(categoryHeader).not.toContain(':count="group.paths.length"');
  });

  it("侧边栏排序提供统一的前后落点提示", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const cssSource = readSidebarFinalCss();

    expect(sidebarSource).toContain("sidebarDropClass");
    expect(cssSource).toContain(".sidebar-drop-before::before");
    expect(cssSource).toContain(".sidebar-drop-after::after");
  });

  it("收藏项通过普通落点提示拖回未分组区域", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const cssSource = readSource("css/sidebar.css");

    expect(sidebarSource).toContain("onUngroupedDrop");
    expect(sidebarSource).toContain(
      'moveFavoriteToGroup(draggedFavId.value, "")'
    );
    expect(sidebarSource).toContain("isDraggingGroupedFavorite");
    expect(sidebarSource).not.toContain("favorites-ungrouped-drop-target");
    expect(sidebarSource).not.toContain("拖到这里移出分组");
    expect(sidebarSource).toContain("favorites-ungrouped-drop-zone--empty");
    expect(cssSource).toContain(".favorites-ungrouped-drop-zone--empty");
  });

  it("收藏拖放事件不会冒泡到模块排序，且会校验拖拽来源", () => {
    const sidebarSource = readSource("components/Sidebar.vue");

    expect(sidebarSource).toContain(
      '@dragover.stop.prevent="onFavDragOverItem($event, fav.id)"'
    );
    expect(sidebarSource).toContain(
      '@drop.stop="onFavDropOnItem($event, fav.id)"'
    );
    expect(sidebarSource).toContain(
      '@dragover.stop.prevent="onFavDragOverGroup($event, group.id)"'
    );
    expect(sidebarSource).toContain(
      '@drop.stop="onFavDropOnGroup($event, group.id)"'
    );
    expect(sidebarSource).toMatch(
      /const onFavDragOverItem = [\s\S]*if \(!draggedFavId\.value\) \{[\s\S]*dropEffect = "none";[\s\S]*return;/
    );
  });

  it("跨类型或跨层级目标会清除旧落点提示", () => {
    const sidebarSource = readSource("components/Sidebar.vue");

    expect(sidebarSource).toMatch(
      /if \(!valid\) \{[\s\S]*sidebarDropTarget\.value = null;[\s\S]*dropEffect = "none";/
    );
  });

  it("收藏拖拽结束统一清理全部临时状态", () => {
    const sidebarSource = readSource("components/Sidebar.vue");

    expect(sidebarSource).toMatch(
      /const onFavDragEnd = \(\) => \{[\s\S]*dragOverGroupId\.value = "";[\s\S]*dragOverFavoriteId\.value = "";[\s\S]*draggedFavId\.value = "";[\s\S]*ungroupedDropActive\.value = false;[\s\S]*\};/
    );
    expect(sidebarSource).toMatch(
      /const onFavDropOnItem = async[\s\S]*finally \{\s*clearSidebarDrag\(\);\s*\}/
    );
    expect(sidebarSource).toMatch(
      /const onFavDropOnGroup = async[\s\S]*finally \{\s*clearSidebarDrag\(\);\s*\}/
    );
  });

  it("收藏拖动不改变原列表项视觉状态", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const cssSource = readSidebarFinalCss();

    expect(sidebarSource).not.toContain("dragging: draggedFavId === fav.id");
    expect(cssSource).not.toContain(".favorite-item.dragging");
  });

  it("落点提示使用克制的纯色细线", () => {
    const cssSource = readSidebarFinalCss();

    expect(cssSource).toMatch(
      /\.sidebar-drop-after::after\s*\{[\s\S]*height:\s*2px;[\s\S]*background:\s*var\(--blue, #1677ff\);/
    );
    expect(cssSource).not.toMatch(
      /\.sidebar-drop-after::after\s*\{[\s\S]*linear-gradient/
    );
  });

  it("同层收藏项与分组图标共用一致的水平起点，悬停不改变宽度", () => {
    const cssSource = readSource("css/sidebar.css");

    expect(cssSource).toMatch(
      /\.sidebar-module \.favorites-ungrouped-drop-zone > \.favorite-item,[\s\S]*\.sidebar-module \.favorite-group-header,[\s\S]*\.sidebar-module \.category-group-header\s*\{[^}]*width:\s*calc\(100% - 1rem\)\s*!important;[^}]*margin-inline:\s*0\.5rem\s*!important;/s
    );
    expect(readSidebarFinalCss()).toMatch(
      /\.sidebar-module \.favorite-group-header,[\s\S]*\.sidebar-module \.category-group-header:focus-visible\s*\{[^}]*width:\s*100%\s*!important;[^}]*margin-inline:\s*0\s*!important;[^}]*column-gap:\s*2px;[^}]*padding-right:\s*4px;/s
    );
    expect(cssSource).not.toMatch(
      /\.sidebar-sortable-item\[draggable="true"\]:hover\s*\{[^}]*transform:/s
    );
  });

  it("未分组收藏项与收藏分组使用相同的图标列和图标尺寸", () => {
    const cssSource = readSource("css/sidebar.css");
    const compactCss = cssSource.replace(/\s+/g, " ");

    expect(cssSource).toContain("--sidebar-level-two-icon-column: 1.75rem");
    expect(cssSource).toContain("--sidebar-level-two-icon-size: 1.125rem");
    expect(compactCss).toMatch(
      /\.favorites-ungrouped-drop-zone > \.favorite-item \{ grid-template-columns: var\(--sidebar-level-two-icon-column\) minmax\(\s*0,\s*1fr\s*\) 1\.75rem;/
    );
    expect(compactCss).toMatch(
      /\.favorites-ungrouped-drop-zone > \.favorite-item > \.favorite-icon:not\(\.favorite-drag-handle\),.*\.favorite-group-header > \.favorite-group-icon \{[^}]*width: var\(--sidebar-level-two-icon-column\);[^}]*height: var\(--sidebar-level-two-icon-column\);[^}]*font-size: var\(--sidebar-level-two-icon-size\) !important;/
    );
  });

  it("收藏项移除操作使用克制的可访问命中区，而不是突兀的交叉图标", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const cssSource = readSource("css/sidebar.css");

    expect(sidebarSource).toContain('class="favorite-remove"');
    expect(sidebarSource).toContain('name="minus"');
    expect(sidebarSource).toContain('role="button"');
    expect(sidebarSource).toContain('aria-label="取消收藏"');
    expect(sidebarSource).toContain("@keydown.enter.stop.prevent");
    expect(sidebarSource).toContain("@keydown.space.stop.prevent");
    expect(cssSource).toMatch(
      /nav\.sidebar \.favorite-remove\s*\{[^}]*display:\s*inline-grid;[^}]*width:\s*2rem;[^}]*height:\s*2rem;/s
    );
    expect(cssSource).toMatch(
      /nav\.sidebar \.favorite-remove > \.app-icon\s*\{[^}]*width:\s*1rem;[^}]*height:\s*1rem;/s
    );
    expect(cssSource).toMatch(
      /@media \(max-width: 899px\)[\s\S]*?nav\.sidebar \.favorite-item\s*\{[^}]*min-height:\s*44px;[\s\S]*?nav\.sidebar \.favorite-item > \.favorite-remove\s*\{[^}]*height:\s*44px;/
    );
  });

  it("收藏分组操作和展开图标共享固定的右侧轨道并垂直居中", () => {
    const cssSource = readSource("css/sidebar.css");
    expect(cssSource).toContain(
      "grid-template-columns: 26px minmax(0, 1fr) 40px 40px;"
    );
    expect(cssSource).toMatch(
      /nav\.sidebar \.sidebar-group-actions\s*\{[\s\S]*?width:\s*40px;[\s\S]*?height:\s*40px;[\s\S]*?place-items:\s*center;/
    );
    expect(cssSource).toMatch(
      /\.sidebar-group-actions\s*>\s*\.section-action-btn\s*>\s*\.app-icon\s*\{[\s\S]*?width:\s*16px;[\s\S]*?height:\s*16px;/
    );
    expect(cssSource).toMatch(
      /\.sidebar-group-header\s*>\s*\.category-arrow\s*\{[\s\S]*?width:\s*18px !important;[\s\S]*?height:\s*18px !important;/
    );
    expect(cssSource).toMatch(
      /@media \(max-width: 899px\)[\s\S]*?\.sidebar-group-header\.sidebar-level-two,[\s\S]*?grid-template-columns: 26px minmax\(0, 1fr\) 44px 44px;/
    );
    expect(cssSource).toMatch(
      /@media \(max-width: 899px\)[\s\S]*?\.sidebar-group-actions > \.section-action-btn\s*\{[\s\S]*?width:\s*44px;[\s\S]*?height:\s*44px;/
    );
  });

  it("收藏与标签的落点线绘制在条目内部，避免被圆角容器裁掉", () => {
    const cssSource = readSource("css/sidebar.css");

    expect(cssSource).toMatch(
      /\.sidebar-drop-before::before\s*\{[^}]*top:\s*0;/s
    );
    expect(cssSource).toMatch(
      /\.sidebar-drop-after::after\s*\{[^}]*bottom:\s*0;/s
    );
  });

  it("图标轨登出保留危险色语义并在悬停或聚焦时强化反馈", () => {
    const cssSource = readSource("css/sidebar.css");

    expect(cssSource).toMatch(
      /nav\.sidebar \.sidebar-personalized-stack > #logout,\s*nav\.sidebar \.sidebar-rail-logout\s*\{[^}]*color:\s*var\(--textSecondary, #64748b\)\s*!important;/s
    );
    expect(cssSource).toMatch(
      /nav\.sidebar \.sidebar-rail-logout:hover,[\s\S]*nav\.sidebar \.sidebar-rail-logout:focus-visible\s*\{[^}]*color:\s*var\(--icon-red, #dc2626\)\s*!important;/s
    );
    expect(cssSource).toMatch(
      /nav\.sidebar \.sidebar-footer-actions > #logout > \.app-icon,[\s\S]*?nav\.sidebar \.sidebar-rail-logout > \.app-icon\s*\{[^}]*color:\s*var\(--icon-red, #dc2626\) !important;/s
    );
  });

  it("展开态登出沿用主图标列并以红色图标标识", () => {
    const cssSource = readSidebarFinalCss();

    expect(cssSource).toMatch(
      /nav\.sidebar \.sidebar-footer-actions > #logout\s*\{[^}]*display:\s*grid;[^}]*grid-template-columns:\s*var\(--sidebar-primary-icon-column\) minmax\(0, 1fr\);/s
    );
    expect(cssSource).toMatch(
      /nav\.sidebar \.sidebar-footer-actions > #logout > \.app-icon,[\s\S]*?color:\s*var\(--icon-red, #dc2626\) !important;/s
    );
    expect(cssSource).toContain("white-space: nowrap;");
  });

  it("侧栏最小宽度保护菜单标签不换行，顶栏使用同一宽度变量", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const sidebarCss = readSource("css/sidebar.css");
    const workspaceCss = readSource("css/workspace-ui.css");

    expect(sidebarSource).toContain("const SIDEBAR_MIN_WIDTH = 240;");
    expect(sidebarSource).toContain("const SIDEBAR_DEFAULT_WIDTH = 288;");
    expect(sidebarSource).toContain(':aria-valuemin="SIDEBAR_MIN_WIDTH"');
    expect(sidebarCss).toContain(
      "width: min(20rem, max(15rem, calc(100vw - 1rem)));"
    );
    expect(workspaceCss).toContain(
      "grid-template-columns: var(--sidebar-width, 288px) minmax(0, 1fr) auto;"
    );
    expect(workspaceCss).toContain("#app:has(.sidebar-frame.is-rail)");
    expect(workspaceCss).toContain("border-radius: 0.625rem;");
    expect(sidebarCss).toContain(
      ".sidebar-frame {\n    top: 0;\n    z-index: 1001;"
    );
    expect(sidebarCss).toContain(
      "height: calc(100% - var(--app-header-height, 56px));"
    );
    expect(sidebarCss).toContain("margin-top: var(--app-header-height, 56px);");
  });

  it("账户卡片与侧栏内容共用一条边界，折叠按钮不再另起一个孤立方框", () => {
    const cssSource = readSource("css/sidebar.css");

    expect(cssSource).toMatch(
      /nav\.sidebar:not\(\.sidebar--rail\) \.sidebar-user-row\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\);/s
    );
    expect(cssSource).toMatch(
      /nav\.sidebar \.sidebar-collapse-row\s*\{[^}]*display:\s*flex;[^}]*margin:\s*0;/s
    );
    expect(cssSource).toMatch(
      /nav\.sidebar:not\(\.sidebar--rail\) \.sidebar-user-card\s*\{[^}]*padding:\s*0\.5rem 0\.625rem !important/s
    );
  });
});
