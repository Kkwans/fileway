import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const readSource = (relativePath: string) =>
  readFileSync(new URL(`../../${relativePath}`, import.meta.url), "utf8");

const readSidebarFinalCss = () => {
  const source = readSource("css/sidebar.css");
  const marker = "/* PC 侧边栏最终契约";
  const start = source.lastIndexOf(marker);
  if (start < 0) throw new Error("缺少收敛后的侧边栏 CSS 区块");
  return source.slice(start);
};

describe("UI foundation and sidebar contract", () => {
  it("uses a dedicated icon button primitive without glyph padding", () => {
    const iconButtonSource = readSource("components/ui/IconButton.vue");
    const actionSource = readSource("components/header/Action.vue");
    const stylesSource = readSource("css/styles.css");

    expect(iconButtonSource).toContain('class="icon-button"');
    expect(iconButtonSource).toContain('type="button"');
    expect(iconButtonSource).toContain(':aria-label="label || undefined"');
    expect(iconButtonSource).toContain("counter");
    expect(actionSource).toContain(
      'import IconButton from "@/components/ui/IconButton.vue"'
    );
    expect(actionSource).toContain("<IconButton");
    expect(stylesSource).not.toMatch(
      /\.action\s*>\s*\.app-icon\s*\{[^}]*box-sizing:\s*content-box/s
    );
    expect(stylesSource).not.toMatch(
      /\.action\s*>\s*\.app-icon\s*\{[^}]*padding:\s*0\.4em/s
    );
  });

  it("provides one dialog shell on top of BaseModal", () => {
    const dialogSource = readSource("components/ui/AppDialog.vue");
    const modalSource = readSource("components/prompts/BaseModal.vue");

    expect(dialogSource).toContain(
      'import BaseModal from "@/components/prompts/BaseModal.vue"'
    );
    expect(dialogSource).toContain('class="app-dialog__body"');
    expect(dialogSource).toContain('class="app-dialog__footer"');
    expect(modalSource).toContain("labelledBy?: string");
    expect(modalSource).toContain(':aria-labelledby="props.labelledBy"');
  });

  it("keeps create commands out of the sidebar system section", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const computedBlock = sidebarSource.match(
      /const systemOptions = computed<[\s\S]*?\n\nconst orderedSystemOptions/
    )?.[0];

    expect(computedBlock).toBeTruthy();
    expect(computedBlock).not.toContain('id: "new-directory"');
    expect(computedBlock).not.toContain('id: "new-file"');
    expect(sidebarSource).not.toContain('aria-label="清空收藏夹"');
  });

  it("places collapse control on its own row above logout", () => {
    const sidebarSource = readSource("components/Sidebar.vue");
    const userRow = sidebarSource.match(
      /<div class="sidebar-user-row">[\s\S]*?<\/div>/
    )?.[0];

    expect(userRow).toBeTruthy();
    expect(userRow).not.toContain("sidebar-collapse-control");
    expect(sidebarSource).toContain('class="sidebar-collapse-row"');
    expect(sidebarSource).toContain('label="折叠为图标侧栏"');
  });

  it("retains the intentional mobile double-tap interaction contract", () => {
    const touchSource = readSource("utils/layoutContract.ts");

    expect(touchSource).toMatch(/if \(tapCount >= 2\) return "open"/);
    expect(touchSource).toContain('if (longPress) return "select"');
    expect(touchSource).toContain('return "toggle-selection"');
    expect(touchSource).toMatch(/return "none";/);
  });

  it("keeps the account card compact instead of stretching the avatar into a blue tile", () => {
    const finalCss = readSidebarFinalCss();

    expect(finalCss).toMatch(
      /nav\.sidebar:not\(\.sidebar--rail\) \.sidebar-user-card\s*\{[^}]*display:\s*grid;[^}]*grid-template-columns:\s*2\.25rem minmax\(0, 1fr\);/s
    );
    expect(finalCss).toMatch(
      /nav\.sidebar:not\(\.sidebar--rail\) \.sidebar-user-card > \.sidebar-user-icon\s*\{[^}]*width:\s*2\.25rem;[^}]*height:\s*2\.25rem;[^}]*flex:\s*0 0 2\.25rem;/s
    );
    expect(finalCss).toMatch(
      /nav\.sidebar:not\(\.sidebar--rail\) \.sidebar-user-card:hover\s*\{[^}]*color:\s*var\(--textSecondary, #282832\) !important;[^}]*transform:\s*none !important;/s
    );
  });
});
