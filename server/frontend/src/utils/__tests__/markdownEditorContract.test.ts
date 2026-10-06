import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const editorSource = readFileSync(
  fileURLToPath(new URL("../../views/files/Editor.vue", import.meta.url)),
  "utf8"
);
const vditorStyles = readFileSync(
  fileURLToPath(new URL("../../css/vditor-overrides.css", import.meta.url)),
  "utf8"
);
const codeStyles = readFileSync(
  fileURLToPath(new URL("../../css/markdown-code.css", import.meta.url)),
  "utf8"
);
const contentStyles = readFileSync(
  fileURLToPath(new URL("../../css/markdown-content.css", import.meta.url)),
  "utf8"
);
const workspaceStyles = readFileSync(
  fileURLToPath(new URL("../../css/workspace-ui.css", import.meta.url)),
  "utf8"
);
const resourceLoaderSource = readFileSync(
  fileURLToPath(new URL("../externalResources.ts", import.meta.url)),
  "utf8"
);
const quickPreviewSource = readFileSync(
  fileURLToPath(
    new URL("../../components/prompts/QuickPreview.vue", import.meta.url)
  ),
  "utf8"
);

describe("Markdown 编辑器交互契约", () => {
  it("默认使用单一渲染面板的即时渲染模式", () => {
    expect(editorSource).toContain(
      'const currentMode = ref<MarkdownMode>("ir")'
    );
    expect(editorSource).toContain('initVditorWithMode(content, "ir")');
    expect(editorSource).toContain("switchMode('ir')");
    expect(editorSource).toContain("codeBlockPreview: true");
    expect(editorSource).toMatch(
      /hljs:\s*\{[\s\S]*getMarkdownHighlightOptions\(showLineNumbers\.value\),[\s\S]*lineNumber:\s*false/
    );
    expect(editorSource).toContain('class="editor-mode-action"');
    expect(editorSource).toMatch(
      /:global\(#editor-container \.editor-mode-action\)\s*\{[\s\S]*line-height:\s*0;/
    );
    expect(editorSource).toMatch(
      /:global\(#editor-container \.editor-mode-action\)\s*\{[\s\S]*width:\s*2\.75rem;[\s\S]*height:\s*2\.75rem;/
    );
    expect(editorSource).toMatch(
      /:global\(header > \.header-trailing #dropdown \.editor-mode-action\)\s*\{[\s\S]*width:\s*2\.75rem;[\s\S]*height:\s*2\.75rem;/
    );
    expect(editorSource).not.toContain(
      'const currentMode = ref<MarkdownMode>("wysiwyg")'
    );
  });

  it("即时渲染代码块聚焦时只保留一个可编辑表面，并为预览表面应用本地高亮", () => {
    expect(resourceLoaderSource).toContain(
      "export function highlightMarkdownEditorPreviews("
    );
    expect(resourceLoaderSource).toContain(".vditor-ir__preview pre > code");
    expect(editorSource).toContain(
      "highlightMarkdownEditorPreviews(currentMount, {"
    );
    expect(editorSource).toContain("showLineNumbers: showLineNumbers.value");
    expect(editorSource).toContain(
      "setupMarkdownPreviewHighlightObserver(generation)"
    );
    expect(resourceLoaderSource).toContain("nfbHighlighted");
    expect(resourceLoaderSource).toContain("nfbHighlightMarkup");
    expect(resourceLoaderSource).toContain("nfbLineNumbers");
    expect(resourceLoaderSource).toContain(
      ".vditor-ir__marker--pre > code, .vditor-wysiwyg__block > pre > code"
    );
    expect(editorSource).toContain(
      "scheduleMarkdownPreviewHighlight(generation)"
    );
    expect(editorSource).toMatch(
      /const scheduleMarkdownPreviewHighlight = \(generation: number\) => \{[\s\S]*if \(markdownPreviewHighlightTimer !== null\) return;/
    );
    expect(editorSource).toContain("}, 120);");
    expect(vditorStyles).toMatch(
      /\.vditor-ir__node--expand\s*>\s*\.vditor-ir__preview\s*\{[\s\S]*display:\s*none;/
    );
    expect(vditorStyles).toMatch(
      /\.vditor-ir__node:not\(\.vditor-ir__node--expand\)\s*>\s*\.vditor-ir__marker--pre[\s\S]*display:\s*none\s*!important;/
    );
  });

  it("代码语言选择器使用顶部下拉面板而不是居中模态框", () => {
    expect(editorSource).toMatch(
      /\.markdown-language-picker-backdrop\s*\{[\s\S]*place-items:\s*start end;[\s\S]*background:\s*transparent;/
    );
    expect(editorSource).toMatch(
      /\.markdown-language-picker\s*\{[\s\S]*max-height:[\s\S]*box-shadow:/
    );
  });

  it("预览模式隐藏大纲后不保留右侧空白列", () => {
    expect(editorSource).toContain(
      "previewShell.className = getMarkdownPreviewShellClass("
    );
    expect(editorSource).toContain("showWidePreview.value");
    expect(editorSource).toContain("togglePreviewWidth");
    expect(vditorStyles).toMatch(
      /\.markdown-preview-shell\.has-outline\s*\{[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\)\s+15rem;/
    );
    expect(vditorStyles).toMatch(
      /\.markdown-preview-shell\s*\{[\s\S]*grid-template-columns:\s*minmax\(0, 1fr\);/
    );
  });

  it("代码行号使用紧凑 gutter，避免左侧出现大块空白", () => {
    expect(codeStyles).toMatch(/flex:\s*0 0 1\.85rem;/);
    expect(codeStyles).toMatch(
      /\.code-line-content\s*\{[\s\S]*padding:\s*0 0\.65rem;/
    );
  });
  it("IR/预览只保留内部滚动容器，挂载壳层不重复滚动", () => {
    expect(vditorStyles).toMatch(
      /#vditor-mount \.vditor-content\s*\{[\s\S]*overflow:\s*hidden;/
    );
    expect(vditorStyles).toMatch(
      /#vditor-mount \.vditor-ir,[\s\S]*#vditor-mount \.vditor-preview\s*\{[\s\S]*overflow-y:\s*auto;/
    );
    expect(editorSource).toMatch(
      /\.vditor-mount\s*\{[\s\S]*overflow:\s*hidden;/
    );
  });
  it("uses a focused reading width and styles prose primitives", () => {
    expect(contentStyles).toMatch(/width:\s*min\(100%,\s*96ch\);/);
    expect(vditorStyles).toMatch(
      /\.markdown-preview-shell\.is-wide\s*>\s*\.vditor-preview--content\s*\{[\s\S]*width:\s*min\(100%,\s*1200px\);/
    );
    expect(contentStyles).toMatch(/blockquote[\s\S]*border-radius:\s*6px;/);
    expect(contentStyles).toContain("kbd {");
    expect(contentStyles).toContain("mark {");
  });
  it("语言选择器支持搜索、键盘导航并可修改当前代码块", () => {
    expect(editorSource).toContain('role="combobox"');
    expect(editorSource).toContain(
      '@keydown.down.prevent="moveCodeLanguageSelection(1)"'
    );
    expect(editorSource).toContain(
      '@keydown.up.prevent="moveCodeLanguageSelection(-1)"'
    );
    expect(editorSource).toContain("updateMarkdownCodeFenceLanguage(");
    expect(editorSource).toContain("getActiveMarkdownCodeBlockIndex()");
  });

  it("Vditor、highlight.js 和 Ace 只从应用同源静态资源加载", () => {
    expect(resourceLoaderSource).toContain('import("vditor")');
    expect(resourceLoaderSource).toContain('import("highlight.js")');
    expect(resourceLoaderSource).toContain('staticAssetURL("ace")');
    expect(editorSource).toContain("getAceAssetRoot()");
    expect(editorSource).toContain("cdn: getVditorAssetRoot()");
    expect(quickPreviewSource).toContain("cdn: getVditorAssetRoot()");
    expect(`${resourceLoaderSource}\n${editorSource}`).not.toMatch(
      /cdn\.jsdelivr|unpkg\.com/
    );
  });

  it("大 Markdown 降级为轻量源码编辑但仍只手动保存", () => {
    expect(editorSource).toContain(
      "const MARKDOWN_RICH_EDITOR_MAX_BYTES = 2 * 1024 * 1024"
    );
    expect(editorSource).toContain("const usesVditor =");
    expect(editorSource).toContain('class="editor-degraded-notice"');
    expect(editorSource).toContain(
      "await api.put(route.path, content as ApiContent)"
    );
    expect(editorSource).toContain('window.addEventListener("beforeunload"');
    expect(editorSource).not.toMatch(/autoSave|autosave|draft|草稿/);
  });

  it("移动端编辑器顶部操作保持 44px 触控目标", () => {
    expect(workspaceStyles).toMatch(
      /@media \(max-width: 899px\)[\s\S]*?#editor-container > \.app-header-bar > \.header-leading,[\s\S]*?#editor-container > \.app-header-bar > \.header-trailing\s*\{[\s\S]*flex-basis:\s*44px;/
    );
    expect(workspaceStyles).toMatch(
      /#editor-container\s*>\s*\.app-header-bar\s*>\s*\.header-center\s*>\s*\.action,[\s\S]*?#editor-container\s*>\s*\.app-header-bar\s*>\s*\.header-trailing\s+\.header-mobile-actions\s*>\s*\.action\s*\{[\s\S]*width:\s*44px;[\s\S]*height:\s*44px;/
    );
  });

  it("图片拖入使用同级 assets 且只标记 Markdown 为待手动保存", () => {
    expect(editorSource).toContain("handler: handleMarkdownImageUpload");
    expect(editorSource).toContain("api.postExclusive");
    expect(editorSource).toContain("vditorInstance.insertMD");
    expect(editorSource).toContain('insertEmptyBlock?.("afterend")');
    expect(editorSource).toContain("markdownImagePreviewSource");
    expect(editorSource).toContain("markdownImagePreviewContent");
    expect(editorSource).toContain("prepareMarkdownImagePreviewContent");
    expect(editorSource).toContain("setupMarkdownImagePreviews");
    expect(editorSource).toContain("getVditorMarkdown");
    expect(editorSource).toContain("new Map");
    expect(editorSource).toContain("userEdited = true");
    expect(editorSource).toContain("请手动保存 Markdown");
    expect(editorSource).toContain("onBeforeRouteLeave");
    expect(editorSource).not.toMatch(
      /handleMarkdownImageUpload[\s\S]{0,1800}await\s+save\(/
    );
  });
});
