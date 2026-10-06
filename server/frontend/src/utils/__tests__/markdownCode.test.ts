import { describe, expect, it } from "vitest";
import {
  filterMarkdownCodeLanguages,
  getMarkdownCodeSource,
  getMarkdownHighlightOptions,
  createMarkdownCodeFence,
  getMarkdownLineNumberStorageKey,
  getMarkdownOutlineStorageKey,
  getMarkdownPreviewShellClass,
  getMarkdownWidthStorageKey,
  inferMarkdownCodeLanguage,
  MARKDOWN_CODE_LANGUAGES,
  normalizeMarkdownCodeLanguage,
  resolveMarkdownCodeLanguage,
  renderMarkdownCodeLines,
  updateMarkdownCodeFenceLanguage,
  wrapMarkdownCodeLines,
} from "../markdownCode";

describe("Markdown 代码块契约", () => {
  it("优先使用围栏声明的语言并规范常见别名", () => {
    expect(resolveMarkdownCodeLanguage("language-js", "const value = 1;")).toBe(
      "javascript"
    );
    expect(resolveMarkdownCodeLanguage("lang-sh", "echo hello")).toBe("bash");
  });

  it("只对受控语言做自动识别，未知语言安全降级", () => {
    expect(
      inferMarkdownCodeLanguage("public class Demo { }\nSystem.out.println();")
    ).toBe("java");
    expect(
      inferMarkdownCodeLanguage("some completely unknown syntax $$$")
    ).toBe(null);
    expect(
      resolveMarkdownCodeLanguage("language-not-real", "const value = 1;")
    ).toBe(null);
  });

  it("识别未标注围栏中的常见 Shell 命令，避免整块黑色纯文本", () => {
    expect(
      inferMarkdownCodeLanguage(
        "# 构建\nnpm run build\ntar czf -C dist . | ssh user@nas 'tar xzf -'"
      )
    ).toBe("bash");
  });

  it("行号关闭时不创建 gutter，开启时每一行只包装一次", () => {
    const source = "第一行\n第二行\n第三行\n";
    expect(wrapMarkdownCodeLines(source, false)).toEqual({
      html: source.slice(0, -1),
      hasLineNumbers: false,
    });
    const withLineNumbers = wrapMarkdownCodeLines("a\nb\nc", true);
    expect(withLineNumbers.hasLineNumbers).toBe(true);
    expect(withLineNumbers.html.match(/class=\"code-line\"/g)).toHaveLength(3);
    expect(
      withLineNumbers.html.match(/class=\"code-line-content\"/g)
    ).toHaveLength(3);
    expect(withLineNumbers.html).not.toContain("</span>\n<span");
  });

  it("完整块高亮后保持每行标签闭合，避免多行代码互相覆盖", () => {
    const rendered = renderMarkdownCodeLines(
      "first\nsecond",
      true,
      (line) => `<span class="token">${line}</span>`
    );

    expect(rendered.html).toContain(
      '<span class="code-line-content"><span class="token">first</span></span>'
    );
    expect(rendered.html).toContain(
      '<span class="code-line-content"><span class="token">second</span></span>'
    );
  });

  it("完整高亮跨行 token 后再拆行，保留字符串或注释的语义类", () => {
    const rendered = renderMarkdownCodeLines(
      "first\nsecond",
      true,
      (source) => `<span class="token-string">${source}</span>`
    );

    expect(rendered.html).toContain(
      '<span class="code-line-content"><span class="token-string">first</span></span>'
    );
    expect(rendered.html).toContain(
      '<span class="code-line-content"><span class="token-string">second</span></span>'
    );
    expect(rendered.html.match(/class="token-string"/g)).toHaveLength(2);
  });

  it("按用户 ID 隔离行号偏好，访客使用固定命名空间", () => {
    expect(getMarkdownLineNumberStorageKey("42")).toBe(
      "nas-file-browser:markdown-line-numbers:42"
    );
    expect(getMarkdownLineNumberStorageKey(null)).toBe(
      "nas-file-browser:markdown-line-numbers:guest"
    );
    expect(getMarkdownOutlineStorageKey("42")).toBe(
      "nas-file-browser:markdown-outline:42"
    );
    expect(getMarkdownWidthStorageKey("42")).toBe(
      "nas-file-browser:markdown-width:42"
    );
    expect(getMarkdownWidthStorageKey(null)).toBe(
      "nas-file-browser:markdown-width:guest"
    );
  });

  it("语言选择器写回可复现的 Markdown 围栏", () => {
    expect(createMarkdownCodeFence("JavaScript")).toBe(
      "```javascript\n\n```\n"
    );
    expect(createMarkdownCodeFence("unknown-language")).toBe("```\n\n```\n");
    expect(createMarkdownCodeFence("Dockerfile")).toBe(
      "```dockerfile\n\n```\n"
    );
    expect(
      resolveMarkdownCodeLanguage("language-tsx", "const App = () => null;")
    ).toBe("typescript");
  });

  it("公开受支持语言目录，并可按名称、别名和说明筛选", () => {
    expect(MARKDOWN_CODE_LANGUAGES.length).toBeGreaterThanOrEqual(20);
    expect(
      filterMarkdownCodeLanguages("js").map((item) => item.value)
    ).toContain("javascript");
    expect(
      filterMarkdownCodeLanguages("命令").map((item) => item.value)
    ).toContain("bash");
    expect(normalizeMarkdownCodeLanguage("YML")).toBe("yaml");
    expect(normalizeMarkdownCodeLanguage("not-supported")).toBe(null);
  });

  it("重复切换行号时始终使用原始换行，不能把代码粘成一行", () => {
    const source = "const first = 1;\nconst second = 2;";
    const decorated = wrapMarkdownCodeLines(source, true).html;

    expect(getMarkdownCodeSource(decorated, source, true)).toBe(source);
    expect(getMarkdownCodeSource(source, "stale", false)).toBe(source);
  });

  it("Vditor 编辑模式与预览模式共享行号开关", () => {
    expect(getMarkdownHighlightOptions(true)).toEqual({
      enable: true,
      lineNumber: true,
      style: "github",
    });
    expect(getMarkdownHighlightOptions(false).lineNumber).toBe(false);
  });

  it("阅读预览仅在显示大纲时启用双列布局", () => {
    expect(getMarkdownPreviewShellClass(false)).toBe("markdown-preview-shell");
    expect(getMarkdownPreviewShellClass(true)).toBe(
      "markdown-preview-shell has-outline"
    );
    expect(getMarkdownPreviewShellClass(false, true)).toBe(
      "markdown-preview-shell is-wide"
    );
    expect(getMarkdownPreviewShellClass(true, true)).toBe(
      "markdown-preview-shell has-outline is-wide"
    );
  });
  it("只修改目标代码块的语言，不破坏代码内容或其他围栏", () => {
    const markdown = [
      "```js",
      "const first = 1;",
      "```",
      "",
      "~~~python",
      "print('second')",
      "~~~",
    ].join("\n");

    expect(updateMarkdownCodeFenceLanguage(markdown, 0, "typescript")).toBe(
      markdown.replace("```js", "```typescript")
    );
    expect(updateMarkdownCodeFenceLanguage(markdown, 1, "bash")).toBe(
      markdown.replace("~~~python", "~~~bash")
    );
    expect(updateMarkdownCodeFenceLanguage(markdown, 9, "java")).toBe(markdown);
  });
});
