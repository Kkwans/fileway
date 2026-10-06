/**
 * Markdown 代码块的纯函数契约。
 *
 * 代码块的 DOM 装饰由 externalResources.ts 负责，这里只处理语言规范化、
 * 受控自动识别、行号包装和用户偏好键，方便在没有浏览器环境时测试。
 */

const LANGUAGE_ALIASES: Record<string, string> = {
  bash: "bash",
  c: "c",
  cpp: "cpp",
  cs: "csharp",
  css: "css",
  csharp: "csharp",
  docker: "dockerfile",
  dockerfile: "dockerfile",
  go: "go",
  html: "xml",
  htm: "xml",
  java: "java",
  javascript: "javascript",
  js: "javascript",
  json: "json",
  jsonc: "json",
  kotlin: "kotlin",
  md: "markdown",
  markdown: "markdown",
  php: "php",
  py: "python",
  python: "python",
  rb: "ruby",
  ruby: "ruby",
  rust: "rust",
  rs: "rust",
  sh: "bash",
  shell: "bash",
  powershell: "powershell",
  ps: "powershell",
  ps1: "powershell",
  sql: "sql",
  scss: "scss",
  less: "less",
  swift: "swift",
  ts: "typescript",
  tsx: "typescript",
  jsx: "javascript",
  typescript: "typescript",
  txt: "plaintext",
  text: "plaintext",
  xml: "xml",
  yaml: "yaml",
  yml: "yaml",
  toml: "ini",
  ini: "ini",
  graphql: "graphql",
};

export type MarkdownCodeLanguage = {
  value: string;
  label: string;
  keywords: string;
};

/** highlight.js common build 中可稳定使用的语言。 */
export const MARKDOWN_CODE_LANGUAGES: MarkdownCodeLanguage[] = [
  { value: "plaintext", label: "纯文本", keywords: "text txt plain 文本" },
  { value: "bash", label: "Bash / Shell", keywords: "sh shell 命令 脚本" },
  { value: "powershell", label: "PowerShell", keywords: "ps ps1 windows 命令" },
  { value: "javascript", label: "JavaScript", keywords: "js jsx node" },
  { value: "typescript", label: "TypeScript", keywords: "ts tsx" },
  { value: "java", label: "Java", keywords: "jdk spring" },
  { value: "python", label: "Python", keywords: "py" },
  { value: "go", label: "Go", keywords: "golang" },
  { value: "rust", label: "Rust", keywords: "rs" },
  { value: "c", label: "C", keywords: "clang" },
  { value: "cpp", label: "C++", keywords: "cxx" },
  { value: "csharp", label: "C#", keywords: "cs dotnet" },
  { value: "kotlin", label: "Kotlin", keywords: "kt android" },
  { value: "swift", label: "Swift", keywords: "ios" },
  { value: "php", label: "PHP", keywords: "php" },
  { value: "ruby", label: "Ruby", keywords: "rb rails" },
  { value: "sql", label: "SQL", keywords: "mysql sqlite database 数据库" },
  { value: "json", label: "JSON", keywords: "jsonc 配置" },
  { value: "yaml", label: "YAML", keywords: "yml compose 配置" },
  { value: "xml", label: "HTML / XML", keywords: "html htm svg" },
  { value: "css", label: "CSS", keywords: "style 样式" },
  { value: "scss", label: "SCSS", keywords: "sass 样式" },
  { value: "less", label: "Less", keywords: "css 样式" },
  { value: "markdown", label: "Markdown", keywords: "md 文档" },
  { value: "dockerfile", label: "Dockerfile", keywords: "docker 容器 镜像" },
  { value: "ini", label: "INI / TOML", keywords: "toml config 配置" },
  { value: "graphql", label: "GraphQL", keywords: "gql api" },
];

const LANGUAGE_DECLARATION = /(?:^|\s)(?:language|lang)-([a-z0-9+_.-]+)/i;

export function normalizeMarkdownCodeLanguage(value: string): string | null {
  const token = value.trim().toLowerCase().replace(/^\+/, "");
  return LANGUAGE_ALIASES[token] ?? null;
}

export function filterMarkdownCodeLanguages(
  query: string
): MarkdownCodeLanguage[] {
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) return MARKDOWN_CODE_LANGUAGES;

  return MARKDOWN_CODE_LANGUAGES.filter((language) =>
    `${language.value} ${language.label} ${language.keywords}`
      .toLowerCase()
      .includes(normalizedQuery)
  );
}

/** Create a persistent fenced block for the language picker. */
export function createMarkdownCodeFence(language: string): string {
  const normalized = normalizeMarkdownCodeLanguage(language) ?? "";
  return `\`\`\`${normalized}\n\n\`\`\`\n`;
}

/**
 * Update one fenced code block without rebuilding or reformatting the rest of
 * the document. Both backtick and tilde fences are supported.
 */
export function updateMarkdownCodeFenceLanguage(
  markdown: string,
  targetIndex: number,
  language: string
): string {
  if (targetIndex < 0) return markdown;

  const normalizedLanguage = normalizeMarkdownCodeLanguage(language) ?? "";
  const lines = markdown.split(/(\r?\n)/);
  let blockIndex = -1;
  let activeFence: { marker: string; length: number } | null = null;

  for (let index = 0; index < lines.length; index += 2) {
    const line = lines[index];
    const match = line.match(/^(\s*)(`{3,}|~{3,})([^\r\n]*)$/);
    if (!match) continue;

    const marker = match[2][0];
    if (activeFence) {
      if (
        marker === activeFence.marker &&
        match[2].length >= activeFence.length &&
        match[3].trim() === ""
      ) {
        activeFence = null;
      }
      continue;
    }

    blockIndex += 1;
    if (blockIndex === targetIndex) {
      lines[index] = `${match[1]}${match[2]}${normalizedLanguage}`;
      return lines.join("");
    }
    activeFence = { marker, length: match[2].length };
  }

  return markdown;
}

/** Resolve an explicitly declared fence language. Unknown declarations return null. */
export function resolveMarkdownCodeLanguage(
  className: string,
  source: string
): string | null {
  const declaration = className.match(LANGUAGE_DECLARATION);
  if (declaration) return normalizeMarkdownCodeLanguage(declaration[1]);
  return inferMarkdownCodeLanguage(source);
}

/**
 * 已装饰的代码节点不再包含文本换行，重复开关行号时必须读取缓存的原始文本。
 */
export function getMarkdownCodeSource(
  textContent: string,
  cachedSource: string | undefined,
  decorated: boolean
): string {
  if (decorated && cachedSource !== undefined) return cachedSource;
  return textContent || cachedSource || "";
}

/**
 * Perform deliberately conservative language detection.
 * A low-confidence block remains plain text instead of receiving a wrong theme.
 */
export function inferMarkdownCodeLanguage(source: string): string | null {
  const code = source.trim();
  if (!code) return null;

  // README/deployment snippets are frequently fenced without a language.
  // Recognize a small, high-confidence command vocabulary so they receive
  // the bundled Bash theme instead of rendering as an undifferentiated black
  // block.  A lone prose line or an arbitrary identifier remains plaintext.
  if (
    /(?:^|\n)\s*(?:#\s+|(?:sudo\s+)?)(?:npm|pnpm|yarn|node|docker(?:-compose)?|git|cd|tar|ssh|scp|curl|wget|chmod|chown|mkdir|rm|cp|mv|go|make|ffmpeg|ffprobe)\b/im.test(
      code
    )
  ) {
    return "bash";
  }

  if (/^#!\s*\/usr\/bin\/env\s+(?:ba)?sh\b|^#!\/bin\/(?:ba)?sh\b/.test(code)) {
    return "bash";
  }
  if (
    /\bpublic\s+(?:final\s+)?class\s+\w+/.test(code) ||
    /System\.out\.(?:print|println)\s*\(/.test(code)
  ) {
    return "java";
  }
  if (
    /\b(?:const|let|var)\s+[A-Za-z_$][\w$]*\s*=/.test(code) ||
    /\bconsole\.(?:log|error|warn)\s*\(/.test(code) ||
    /=>/.test(code)
  ) {
    return "javascript";
  }
  if (
    /^\s*(?:def\s+\w+\s*\(|from\s+\w+\s+import\s+|import\s+\w+)/m.test(code)
  ) {
    return "python";
  }
  if (
    /^\s*(?:SELECT|INSERT\s+INTO|UPDATE\s+\w+\s+SET|CREATE\s+TABLE)\b/im.test(
      code
    )
  ) {
    return "sql";
  }
  if (/^\s*[<{][\s\S]*[>}]\s*$/.test(code) && /<\/?[a-z][^>]*>/i.test(code)) {
    return "xml";
  }

  return null;
}

/** Wrap rendered HTML lines only when the user enabled line numbers. */
export function wrapMarkdownCodeLines(
  html: string,
  showLineNumbers: boolean
): { html: string; hasLineNumbers: boolean } {
  const normalized = html.replace(/\r\n?/g, "\n").replace(/\n$/, "");
  if (!showLineNumbers) {
    return { html: normalized, hasLineNumbers: false };
  }

  // Highlight.js keeps a token span open when a string/comment crosses a
  // newline. Split only the visual row while closing and reopening the active
  // tags, so every row stays valid HTML and the token's cross-line context is
  // still represented by the same class on the next row.
  const lines: string[] = [];
  const openTags: Array<{ name: string; markup: string }> = [];
  let current = "";

  const closeOpenTags = () =>
    openTags
      .slice()
      .reverse()
      .map(({ name }) => `</${name}>`)
      .join("");

  const reopenTags = () => openTags.map(({ markup }) => markup).join("");

  const pushLine = () => {
    lines.push(
      `<span class="code-line"><span class="code-line-content">${current}${closeOpenTags()}</span></span>`
    );
    current = reopenTags();
  };

  const voidTags = new Set([
    "area",
    "base",
    "br",
    "col",
    "embed",
    "hr",
    "img",
    "input",
    "link",
    "meta",
    "param",
    "source",
    "track",
    "wbr",
  ]);

  for (let index = 0; index < normalized.length; ) {
    if (normalized[index] === "\n") {
      pushLine();
      index++;
      continue;
    }

    if (normalized[index] !== "<") {
      const nextTag = normalized.indexOf("<", index);
      const nextNewline = normalized.indexOf("\n", index);
      const end =
        nextTag < 0
          ? nextNewline < 0
            ? normalized.length
            : nextNewline
          : nextNewline < 0
            ? nextTag
            : Math.min(nextTag, nextNewline);
      current += normalized.slice(index, end);
      index = end;
      continue;
    }

    const tagEnd = normalized.indexOf(">", index + 1);
    if (tagEnd < 0) {
      current += normalized.slice(index);
      break;
    }

    const tag = normalized.slice(index, tagEnd + 1);
    const closing = tag.match(/^<\/\s*([\w:-]+)/);
    const opening = tag.match(/^<\s*([\w:-]+)/);
    current += tag;
    if (closing) {
      const position = openTags.map(({ name }) => name).lastIndexOf(closing[1]);
      if (position >= 0) openTags.splice(position, 1);
    } else if (
      opening &&
      !tag.startsWith("<!") &&
      !tag.startsWith("<?") &&
      !tag.endsWith("/>") &&
      !voidTags.has(opening[1].toLowerCase())
    ) {
      openTags.push({ name: opening[1], markup: tag });
    }
    index = tagEnd + 1;
  }

  current += closeOpenTags();
  lines.push(
    `<span class="code-line"><span class="code-line-content">${current}</span></span>`
  );
  return {
    // 每个代码行由 CSS 独立占据一行；不要再插入文本换行，否则
    // white-space: pre 会把它渲染成额外的空白行。
    html: lines.join(""),
    hasLineNumbers: true,
  };
}

/**
 * Highlight a complete source block before adding the gutter. Highlighting the
 * full block preserves string/comment state across newlines; wrapMarkdownCodeLines
 * then keeps the resulting visual rows as valid, independently laid-out HTML.
 */
export function renderMarkdownCodeLines(
  source: string,
  showLineNumbers: boolean,
  renderBlock: (source: string) => string
): { html: string; hasLineNumbers: boolean } {
  const normalized = source.replace(/\r\n?/g, "\n").replace(/\n$/, "");
  const highlighted = renderBlock(normalized);
  return wrapMarkdownCodeLines(highlighted, showLineNumbers);
}

export function getMarkdownLineNumberStorageKey(
  userId: string | number | null | undefined
): string {
  const namespace = String(userId ?? "").trim() || "guest";
  return `nas-file-browser:markdown-line-numbers:${namespace}`;
}

export function getMarkdownOutlineStorageKey(
  userId: string | number | null | undefined
): string {
  const namespace = String(userId ?? "").trim() || "guest";
  return `nas-file-browser:markdown-outline:${namespace}`;
}

export function getMarkdownWidthStorageKey(
  userId: string | number | null | undefined
): string {
  const namespace = String(userId ?? "").trim() || "guest";
  return `nas-file-browser:markdown-width:${namespace}`;
}

/** 预览正文只有在大纲实际可见时才保留右侧栏。 */
export function getMarkdownPreviewShellClass(
  showOutline: boolean,
  wide = false
): string {
  return [
    "markdown-preview-shell",
    showOutline ? "has-outline" : "",
    wide ? "is-wide" : "",
  ]
    .filter(Boolean)
    .join(" ");
}

/** Keep Vditor's editable renderer and the custom reading renderer in sync. */
export function getMarkdownHighlightOptions(showLineNumbers: boolean) {
  return {
    enable: true,
    lineNumber: showLineNumbers,
    style: "github",
  } as const;
}
