import { describe, expect, it } from "vitest";

import { getFileActionMenuPosition } from "@/utils/fileActionMenu";

describe("文件操作菜单定位", () => {
  it("优先在触发按钮下方并与右边缘对齐", () => {
    expect(
      getFileActionMenuPosition({
        trigger: { top: 40, right: 500, bottom: 72 },
        menuWidth: 208,
        menuHeight: 190,
        viewportWidth: 800,
        viewportHeight: 600,
      })
    ).toEqual({ left: 292, top: 78 });
  });

  it("靠近底部时向上展开", () => {
    expect(
      getFileActionMenuPosition({
        trigger: { top: 530, right: 500, bottom: 562 },
        menuWidth: 208,
        menuHeight: 190,
        viewportWidth: 800,
        viewportHeight: 600,
      }).top
    ).toBe(334);
  });

  it("在窄视口内保留安全边距", () => {
    expect(
      getFileActionMenuPosition({
        trigger: { top: 40, right: 70, bottom: 72 },
        menuWidth: 208,
        menuHeight: 190,
        viewportWidth: 240,
        viewportHeight: 600,
      }).left
    ).toBe(8);
  });
});
