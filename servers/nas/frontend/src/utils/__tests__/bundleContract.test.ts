import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const themeSource = readFileSync(
  fileURLToPath(new URL("../theme.ts", import.meta.url)),
  "utf8"
);
const editorSource = readFileSync(
  fileURLToPath(new URL("../../views/files/Editor.vue", import.meta.url)),
  "utf8"
);
const editorThemeSource = readFileSync(
  fileURLToPath(new URL("../editorTheme.ts", import.meta.url)),
  "utf8"
);
const aceEditorThemeSource = readFileSync(
  fileURLToPath(
    new URL("../../components/settings/AceEditorTheme.vue", import.meta.url)
  ),
  "utf8"
);

describe("前端拆包契约", () => {
  it("全局主题工具不引入 Ace，编辑器按需加载编辑器主题", () => {
    expect(themeSource).not.toContain("ace-builds");
    expect(themeSource).not.toContain("getEditorTheme");
    expect(editorSource).toContain('from "@/utils/editorTheme"');
    expect(editorSource).toContain('await import("ace-builds")');
    expect(editorThemeSource).not.toContain('import "ace-builds"');
    expect(editorThemeSource).toContain(
      '"ace-builds/src-noconflict/ext-themelist"'
    );
  });

  it("账户设置先加载 Ace 核心，再按需加载主题清单", () => {
    expect(aceEditorThemeSource).not.toContain(
      'import { themes } from "ace-builds/src-noconflict/ext-themelist"'
    );
    expect(aceEditorThemeSource).toContain('await import("ace-builds")');
    expect(aceEditorThemeSource).toContain(
      'await import("ace-builds/src-noconflict/ext-themelist")'
    );
    expect(
      aceEditorThemeSource.indexOf('await import("ace-builds")')
    ).toBeLessThan(
      aceEditorThemeSource.indexOf(
        'await import("ace-builds/src-noconflict/ext-themelist")'
      )
    );
  });
});
