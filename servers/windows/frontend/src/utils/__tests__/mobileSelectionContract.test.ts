import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const readSource = (relativePath: string) =>
  readFileSync(
    fileURLToPath(new URL(`../../${relativePath}`, import.meta.url)),
    "utf8"
  );

describe("移动端多选界面契约", () => {
  it("长按只进入多选，不主动打开单项操作抽屉", () => {
    const source = readSource("components/files/ListingItem.vue");
    const longPressHandler = source.match(
      /const handleLongPress = \(\) => \{[\s\S]*?\n\};/
    )?.[0];

    expect(longPressHandler).toBeTruthy();
    expect(longPressHandler).not.toContain("openMobileActionSheet()");
  });

  it("手机端隐藏旧版多选栏，只保留唯一的文件选择栏", () => {
    const cssSource = readSource("css/listing.css");

    expect(cssSource).toMatch(
      /@media \(max-width: 899px\)\s*\{[\s\S]*?#listing #multiple-selection,[\s\S]*?#listing #multiple-selection\.active\s*\{[^}]*display:\s*none\s*!important;/s
    );
  });

  it("宽表格行也使用单击不打开、双击打开、长按选择的触摸契约", () => {
    const source = readSource("components/files/DetailedTableRow.vue");

    expect(source).toContain('@touchstart="handleTouchStart"');
    expect(source).toContain('@touchmove="handleTouchMove"');
    expect(source).toContain("if (touchInteraction.value) return;");
    expect(source).toContain("longPress: touchGestureActive.value");
    expect(source).toContain('action === "open"');
    expect(source).toContain('action === "toggle-selection"');
  });

  it("触摸按压态在滚动移动后立即取消，并受 Reduced Motion 约束", () => {
    const itemSource = readSource("components/files/ListingItem.vue");
    const cssSource = readSource("css/listing.css");

    expect(itemSource).toContain("touchPressed.value = false;");
    expect(cssSource).toContain("#listing .item.is-touch-pressed");
    expect(cssSource).toMatch(
      /@media \(prefers-reduced-motion: reduce\)[\s\S]*?#listing \.item\.is-touch-pressed\s*\{[^}]*transform:\s*none;/s
    );
  });
});
