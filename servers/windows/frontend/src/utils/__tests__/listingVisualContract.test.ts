import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const readStyles = () =>
  readFileSync(
    fileURLToPath(new URL("../../css/listing-icons.css", import.meta.url)),
    "utf8"
  );

const readMain = () =>
  readFileSync(
    fileURLToPath(new URL("../../main.ts", import.meta.url)),
    "utf8"
  );

const readWorkspace = () =>
  readFileSync(
    fileURLToPath(new URL("../../css/workspace-ui.css", import.meta.url)),
    "utf8"
  );

describe("文件列表视觉契约", () => {
  it("特殊前缀和备份文件不通过整项透明度弱化", () => {
    const styles = readStyles();

    expect(styles).not.toMatch(/\[aria-label\^="\."\]\s*\{[^}]*opacity:/s);
    expect(styles).not.toMatch(/\[data-ext="\.bak"\]\s*\{[^}]*opacity:/s);
  });

  it("本地图标不再依赖 Material 字体伪元素或全局字体加载门闩", () => {
    const styles = readStyles();
    const main = readMain();

    expect(styles).not.toContain('font-family: "Material Icons"');
    expect(styles).not.toContain(".file-type-icon::before");
    expect(main).not.toContain("fonts-loading");
    expect(main).not.toContain("document.fonts.ready");
  });

  it("详细网格把操作区收进卡片内部并保留完整圆角", () => {
    const workspaceStyles = readWorkspace();
    const insetControls = workspaceStyles
      .split("Detailed-grid actions stay as bare icons at rest")[1]
      ?.match(
        /#listing\.mosaic \.item > \.item-controls\s*\{([\s\S]*?)\}/
      )?.[1];

    expect(insetControls).toBeTruthy();
    expect(insetControls).toContain("top: auto;");
    expect(insetControls).toContain("right: 12px;");
    expect(insetControls).toContain("bottom: 10px;");
    expect(insetControls).toContain("left: 12px;");
    expect(insetControls).toContain("width: auto;");
    expect(insetControls).toContain("border: 1px solid transparent;");
    expect(insetControls).toContain("border-radius: 10px;");
    expect(insetControls).toContain("background: transparent;");
    expect(insetControls).toContain("box-shadow: none;");
    expect(workspaceStyles).toMatch(
      /#listing\.mosaic \.item > \.item-controls\s*\{[\s\S]*?opacity:\s*1;[\s\S]*?pointer-events:\s*auto;/s
    );
    expect(workspaceStyles).not.toMatch(
      /#listing\.mosaic \.item > \.item-controls\s*\{[^}]*transform:\s*translateX\(-50%\)/s
    );
  });

  it("手机断点保持底部操作栏及其子按钮可见", () => {
    const workspaceStyles = readWorkspace();

    expect(workspaceStyles).toMatch(
      /@media \(max-width: 899px\)[\s\S]*?#listing\.mosaic \.item > \.item-controls\s*\{[\s\S]*?top:\s*auto;[\s\S]*?bottom:\s*10px;[\s\S]*?opacity:\s*1;/s
    );
    expect(workspaceStyles).toMatch(
      /@media \(max-width: 899px\)[\s\S]*?#listing\.mosaic \.item > \.item-controls \.item-icon-button,[\s\S]*?opacity:\s*1;[\s\S]*?pointer-events:\s*auto;/s
    );
  });

  it("详细网格默认展示裸图标，仅在悬停或聚焦时展示操作卡片", () => {
    const workspaceStyles = readWorkspace();

    expect(workspaceStyles).toMatch(
      /Detailed-grid actions stay as bare icons at rest[\s\S]*?#listing\.mosaic \.item > \.item-controls\s*\{[\s\S]*?justify-content:\s*center;[\s\S]*?background:\s*transparent;/s
    );
    expect(workspaceStyles).toMatch(
      /#listing\.mosaic \.item:hover > \.item-controls,[\s\S]*?#listing\.mosaic \.item:focus-within > \.item-controls\s*\{[\s\S]*?background:\s*color-mix\(/s
    );
    expect(workspaceStyles).not.toMatch(
      /Detailed-grid actions are an inset control surface[\s\S]*?border-radius:\s*0 0/s
    );
  });
});
