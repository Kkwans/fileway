/** Locally bundled Markdown resources shared by Editor and Quick Preview. */

import { staticURL } from "@/utils/constants";
import AppIcon from "@/components/ui/AppIcon.vue";
import {
  getMarkdownCodeSource,
  renderMarkdownCodeLines,
  resolveMarkdownCodeLanguage,
} from "@/utils/markdownCode";
import { createVNode, render } from "vue";

let vditorCSSPromise: Promise<unknown> | null = null;
let vditorJSPromise: Promise<void> | null = null;
let hljsJSPromise: Promise<void> | null = null;
let highlightThemeGeneration = 0;

const escapeHtml = (value: string): string =>
  value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/\"/g, "&quot;")
    .replace(/'/g, "&#39;");

const staticAssetURL = (path: string): string => {
  const root = staticURL.replace(/\/$/, "");
  return `${root}/${path.replace(/^\//, "")}`;
};

/** Root copied from the installed Vditor package by the Vite asset plugin. */
export const getVditorAssetRoot = (): string => staticAssetURL("vditor");

/** Root copied from the installed Ace package by the Vite asset plugin. */
export const getAceAssetRoot = (): string => staticAssetURL("ace");

export async function loadVditorCSS(): Promise<void> {
  vditorCSSPromise ??= import("vditor/dist/index.css");
  await vditorCSSPromise;
}

export async function loadVditorJS(): Promise<void> {
  vditorJSPromise ??= import("vditor").then(({ default: Vditor }) => {
    window.Vditor = Vditor;
  });
  await vditorJSPromise;
}

export async function loadVditor(): Promise<void> {
  await Promise.all([loadVditorCSS(), loadVditorJS()]);
}

export function isDarkTheme(): boolean {
  return document.documentElement.classList.contains("dark");
}

export async function loadHighlightCSS(): Promise<void> {
  const generation = ++highlightThemeGeneration;
  const css = isDarkTheme()
    ? (await import("highlight.js/styles/github-dark.css?inline")).default
    : (await import("highlight.js/styles/github.css?inline")).default;
  if (generation !== highlightThemeGeneration) return;

  let style = document.getElementById("hljs-theme") as HTMLStyleElement | null;
  if (!style) {
    style = document.createElement("style");
    style.id = "hljs-theme";
    document.head.appendChild(style);
  }
  style.textContent = css;
  style.dataset.theme = isDarkTheme() ? "dark" : "light";
}

export async function loadHighlightJS(): Promise<void> {
  hljsJSPromise ??= import("highlight.js").then(({ default: hljs }) => {
    window.hljs = hljs as unknown as Window["hljs"];
  });
  await hljsJSPromise;
}

export async function loadHighlight(): Promise<void> {
  await Promise.all([loadHighlightCSS(), loadHighlightJS()]);
}

export async function loadMarkdownResources(): Promise<void> {
  await Promise.all([loadVditor(), loadHighlight()]);
}

export type HighlightCodeOptions = {
  showLineNumbers?: boolean;
};

const getEditorCodeSource = (
  codeEl: HTMLElement,
  hasOurMarkup: boolean
): string => {
  if (hasOurMarkup && codeEl.dataset.rawSource !== undefined) {
    return codeEl.dataset.rawSource;
  }

  // IR/WYSIWYG 的预览 code 节点可能被 Lute 规范化为没有换行的文本，
  // 但相邻的可编辑 marker 仍保留真实源码。优先读取 marker，避免
  // 首次装饰就把多行代码错误折叠成一行。
  const block = codeEl.closest<HTMLElement>(
    ".vditor-ir__node, .vditor-wysiwyg__block"
  );
  const sourceNode = block?.querySelector<HTMLElement>(
    ".vditor-ir__marker--pre > code, .vditor-wysiwyg__block > pre > code"
  );
  return sourceNode?.textContent ?? codeEl.textContent ?? "";
};

/**
 * Highlight the rendered code surface used by Vditor's instant-rendering
 * editor.  Vditor owns the editable source node, so this deliberately only
 * touches the sibling preview node and never rewrites the source that the
 * user is typing into.
 */
export function highlightMarkdownEditorPreviews(
  container: HTMLElement,
  options: HighlightCodeOptions = {}
): void {
  const hljs = window.hljs;
  if (!hljs) return;
  const showLineNumbers = options.showLineNumbers ?? false;

  container
    .querySelectorAll<HTMLElement>(
      ".vditor-ir__preview pre > code, .vditor-ir__preview > code, " +
        ".vditor-wysiwyg__preview pre > code, .vditor-wysiwyg__preview > code"
    )
    .forEach((codeEl) => {
      // Once our line wrappers are present, textContent no longer contains
      // the source newlines. Keep the source in data-raw-source so toggling
      // the gutter never glues the block into one line. For a fresh Vditor
      // node, textContent remains authoritative and picks up edits made by
      // the editor before the next render pass.
      const hasOurMarkup = Boolean(
        codeEl.querySelector(".code-line, .code-line-content")
      );
      const rawSource = getEditorCodeSource(codeEl, hasOurMarkup).replace(
        /\u200b/g,
        ""
      );
      // Vditor can emit the same preview node more than once while an IR
      // block settles. Skip a node that already contains our current source;
      // this also prevents the observer from re-highlighting its own spans.
      const hasHighlightMarkup = Boolean(codeEl.querySelector("span"));
      if (
        codeEl.dataset.nfbHighlighted === "true" &&
        codeEl.dataset.rawSource === rawSource &&
        codeEl.dataset.nfbLineNumbers === String(showLineNumbers) &&
        (hasHighlightMarkup || codeEl.dataset.nfbHighlightMarkup === "false")
      ) {
        return;
      }
      codeEl.dataset.rawSource = rawSource;

      const lang = resolveMarkdownCodeLanguage(codeEl.className, rawSource);
      const highlightBlock = (source: string) => {
        if (!lang || !hljs.getLanguage(lang)) return escapeHtml(source);
        try {
          return hljs.highlight(source, {
            language: lang,
            ignoreIllegals: true,
          }).value;
        } catch {
          return escapeHtml(source);
        }
      };
      const rendered = renderMarkdownCodeLines(
        rawSource,
        showLineNumbers,
        highlightBlock
      );
      if (lang && hljs.getLanguage(lang)) {
        codeEl.dataset.lang = lang;
        codeEl.classList.add("hljs");
      } else {
        codeEl.removeAttribute("data-lang");
        codeEl.classList.remove("hljs");
      }
      codeEl.innerHTML = rendered.html;
      codeEl.classList.toggle("has-line-numbers", rendered.hasLineNumbers);
      codeEl.dataset.nfbHighlighted = "true";
      codeEl.dataset.nfbLineNumbers = String(showLineNumbers);
      // Vditor may later replace the preview's innerHTML while retaining the
      // data attributes. Remember whether this pass produced token markup so
      // the observer can repair a preview whose spans were discarded.
      codeEl.dataset.nfbHighlightMarkup = codeEl.querySelector("span")
        ? "true"
        : "false";
    });
}

export function observeMarkdownThemeChanges(
  onChange?: (isDark: boolean) => void
): () => void {
  const html = document.documentElement;
  const observer = new MutationObserver(() => {
    void loadHighlightCSS();
    onChange?.(isDarkTheme());
  });
  observer.observe(html, { attributes: true, attributeFilter: ["class"] });
  return () => observer.disconnect();
}

/** Apply controlled syntax highlighting and optional line numbers. */
export function highlightAndAnnotateCodeBlocks(
  container: HTMLElement,
  options: HighlightCodeOptions = {}
): void {
  const showLineNumbers = options.showLineNumbers ?? false;
  const codeBlocks = container.querySelectorAll("pre > code");
  const hljs = window.hljs;

  codeBlocks.forEach((element) => {
    const codeEl = element as HTMLElement;
    const pre = codeEl.parentElement;
    const textContent = codeEl.textContent || "";
    const rawText = getMarkdownCodeSource(
      textContent,
      codeEl.dataset.rawSource,
      codeEl.dataset.markdownDecorated === "true"
    );
    codeEl.dataset.rawSource = rawText;
    const lang = resolveMarkdownCodeLanguage(codeEl.className, rawText);
    if (lang) {
      codeEl.setAttribute("data-lang", lang);
    } else {
      codeEl.removeAttribute("data-lang");
    }

    codeEl.classList.remove("hljs");
    const highlightBlock = (source: string) => {
      if (!hljs || !lang || !hljs.getLanguage(lang)) return escapeHtml(source);
      try {
        return hljs.highlight(source, {
          language: lang,
          ignoreIllegals: true,
        }).value;
      } catch {
        return escapeHtml(source);
      }
    };
    const rendered = renderMarkdownCodeLines(
      rawText,
      showLineNumbers,
      highlightBlock
    );
    if (hljs && lang && hljs.getLanguage(lang)) codeEl.classList.add("hljs");
    codeEl.innerHTML = rendered.html;
    codeEl.classList.toggle("has-line-numbers", rendered.hasLineNumbers);
    codeEl.dataset.markdownDecorated = "true";

    if (pre) {
      let toolbar = pre.querySelector<HTMLElement>(
        ":scope > .markdown-code-toolbar"
      );
      if (!toolbar) {
        toolbar = document.createElement("div");
        toolbar.className = "markdown-code-toolbar";
        pre.prepend(toolbar);
      }
      const previousCopyIcon = toolbar.querySelector<HTMLElement>(
        ".markdown-code-copy-icon"
      );
      if (previousCopyIcon) {
        render(null, previousCopyIcon);
      }
      toolbar.replaceChildren();

      if (lang) {
        const language = document.createElement("span");
        language.className = "markdown-code-language";
        language.textContent = lang;
        toolbar.append(language);
      }

      const copy = document.createElement("button");
      copy.type = "button";
      copy.className = "markdown-code-copy";
      copy.setAttribute("aria-label", "复制代码");
      const copyIcon = document.createElement("span");
      copyIcon.className = "markdown-code-copy-icon";
      copyIcon.setAttribute("aria-hidden", "true");
      render(createVNode(AppIcon, { name: "copy", size: 16 }), copyIcon);
      copy.append(copyIcon);
      copy.addEventListener("click", async () => {
        try {
          await navigator.clipboard.writeText(rawText);
          copy.dataset.copied = "true";
          copy.setAttribute("aria-label", "已复制");
          window.setTimeout(() => {
            copy.dataset.copied = "false";
            copy.setAttribute("aria-label", "复制代码");
          }, 1400);
        } catch {
          copy.setAttribute("aria-label", "复制失败");
        }
      });
      toolbar.append(copy);
    }
  });
}
