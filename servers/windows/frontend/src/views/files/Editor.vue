<template>
  <div id="editor-container">
    <header-bar>
      <action app-icon="x" label="关闭" @action="close()" />
      <span class="header-title" :title="fileStore.req?.name ?? ''">{{
        fileStore.req?.name ?? ""
      }}</span>

      <template #mobile-actions>
        <action
          v-if="authStore.user?.perm.modify"
          id="save-button-mobile"
          app-icon="save"
          label="保存"
          @action="save()"
        />
      </template>

      <template #actions>
        <action
          v-if="authStore.user?.perm.modify"
          id="save-button"
          class="editor-save-desktop"
          app-icon="save"
          label="保存"
          @action="save()"
        />

        <!-- 代码语言标识 + 行号切换（非 Markdown 文件） -->
        <template v-if="!usesVditor && aceEditorReady">
          <span class="editor-lang-badge" :title="langCaption">
            <AppIcon name="code" :size="16" :stroke-width="1.8" />
            {{ langCaption }}
          </span>
          <action
            :app-icon="showLineNumbers ? 'list-ordered' : 'list'"
            :label="showLineNumbers ? '关闭行号' : '显示行号'"
            @action="toggleLineNumbers()"
            :class="{ active: showLineNumbers }"
          />
        </template>

        <!-- Markdown 模式切换 -->
        <template v-if="usesVditor">
          <action
            app-icon="sparkles"
            label="即时渲染（类似 Typora）"
            class="editor-mode-action"
            @action="switchMode('ir')"
            :class="{ active: currentMode === 'ir' }"
          />
          <action
            app-icon="columns"
            label="分屏对照"
            class="editor-mode-action"
            @action="switchMode('sv')"
            :class="{ active: currentMode === 'sv' }"
          />
          <action
            app-icon="eye"
            label="预览模式"
            class="editor-mode-action"
            @action="switchMode('preview')"
            :class="{ active: currentMode === 'preview' }"
          />
          <action
            :app-icon="showLineNumbers ? 'list-ordered' : 'list'"
            :label="showLineNumbers ? '关闭行号' : '显示行号'"
            class="editor-mode-action"
            @action="toggleLineNumbers()"
            :class="{ active: showLineNumbers }"
            :disabled="currentMode === 'sv'"
          />
          <action
            app-icon="list-tree"
            :label="showOutline ? '关闭大纲' : '显示大纲'"
            class="editor-mode-action"
            @action="toggleOutline()"
            :class="{ active: showOutline }"
          />
          <action
            v-if="currentMode === 'preview'"
            :app-icon="showWidePreview ? 'minimize-2' : 'maximize-2'"
            :label="showWidePreview ? '可读正文（96ch）' : '宽版正文（1200px）'"
            class="editor-mode-action"
            @action="togglePreviewWidth()"
            :class="{ active: showWidePreview }"
          />
        </template>
      </template>
    </header-bar>

    <div
      v-if="markdownImageUploading"
      class="markdown-image-upload-status"
      role="status"
      aria-live="polite"
    >
      <span aria-hidden="true"></span>
      {{ markdownImageUploadLabel }}
    </div>

    <div class="loading delayed" v-if="layoutStore.loading">
      <div class="spinner">
        <div class="bounce1"></div>
        <div class="bounce2"></div>
        <div class="bounce3"></div>
      </div>
    </div>
    <template v-else>
      <!-- Vditor 容器（Markdown 文件） -->
      <div
        v-if="usesVditor"
        id="vditor-mount"
        class="vditor-mount markdown-editor-active"
        :class="{ 'markdown-wide': showWidePreview }"
      ></div>
      <!-- Ace 编辑器（非 Markdown 文件） -->
      <div v-else class="editor-lightweight-shell">
        <div
          v-if="isLargeMarkdown"
          class="editor-degraded-notice"
          role="status"
        >
          此 Markdown 文件超过 2
          MiB，已切换到轻量源码模式；内容仍只会在手动保存后写回 NAS。
        </div>
        <form id="editor"></form>
      </div>
    </template>
    <div
      v-if="showCodeLanguagePicker"
      class="markdown-language-picker-backdrop"
      @click.self="closeCodeLanguagePicker"
    >
      <div
        class="markdown-language-picker"
        role="dialog"
        aria-modal="true"
        aria-labelledby="markdown-language-picker-title"
      >
        <div class="markdown-language-picker-header">
          <strong id="markdown-language-picker-title">{{
            codeLanguageTargetIndex === null ? "插入代码块" : "修改代码语言"
          }}</strong>
          <span class="markdown-language-count"
            >支持 {{ MARKDOWN_CODE_LANGUAGES.length }} 种</span
          >
          <button
            type="button"
            class="markdown-language-picker-close"
            aria-label="关闭"
            @click="closeCodeLanguagePicker"
          >
            <AppIcon name="x" :size="18" :stroke-width="1.9" />
          </button>
        </div>
        <div class="markdown-language-search">
          <AppIcon name="search" :size="18" :stroke-width="1.9" />
          <input
            ref="codeLanguageSearchInput"
            v-model.trim="codeLanguageQuery"
            type="search"
            placeholder="搜索语言"
            aria-label="搜索代码语言"
            role="combobox"
            aria-controls="markdown-language-options"
            :aria-expanded="showCodeLanguagePicker"
            :aria-activedescendant="activeCodeLanguageOptionId"
            autocomplete="off"
            @keydown.down.prevent="moveCodeLanguageSelection(1)"
            @keydown.up.prevent="moveCodeLanguageSelection(-1)"
            @keydown.enter.prevent="selectActiveCodeLanguage"
            @keydown.esc.prevent="closeCodeLanguagePicker"
          />
        </div>
        <div
          id="markdown-language-options"
          class="markdown-language-options"
          role="listbox"
        >
          <button
            v-for="(option, index) in filteredCodeLanguageOptions"
            :key="option.value"
            type="button"
            role="option"
            :id="`markdown-language-option-${option.value}`"
            :aria-selected="codeLanguageActiveIndex === index"
            :class="{ active: codeLanguageActiveIndex === index }"
            @mouseenter="codeLanguageActiveIndex = index"
            @click="applyMarkdownCodeLanguage(option.value)"
          >
            <AppIcon name="code" :size="18" :stroke-width="1.9" />
            <span>{{ option.label }}</span>
          </button>
          <p
            v-if="filteredCodeLanguageOptions.length === 0"
            class="markdown-language-empty"
          >
            没有匹配的语言
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { files as api } from "@/api";
import type { ApiContent } from "@/types/api";
import buttons from "@/utils/buttons";
import url from "@/utils/url";
import {
  loadMarkdownResources,
  loadHighlight,
  isDarkTheme,
  highlightAndAnnotateCodeBlocks,
  highlightMarkdownEditorPreviews,
  observeMarkdownThemeChanges,
  getAceAssetRoot,
  getVditorAssetRoot,
} from "@/utils/externalResources";
import {
  createMarkdownCodeFence,
  filterMarkdownCodeLanguages,
  getMarkdownHighlightOptions,
  getMarkdownLineNumberStorageKey,
  getMarkdownOutlineStorageKey,
  getMarkdownPreviewShellClass,
  getMarkdownWidthStorageKey,
  MARKDOWN_CODE_LANGUAGES,
  updateMarkdownCodeFenceLanguage,
} from "@/utils/markdownCode";
import {
  markdownImagePreviewContent,
  markdownImagePreviewSource,
  storeMarkdownImage,
} from "@/utils/markdownImages";
import type { Ace } from "ace-builds";

import Action from "@/components/header/Action.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import HeaderBar from "@/components/header/HeaderBar.vue";
import { useAuthStore } from "@/stores/auth";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import { getEditorTheme } from "@/utils/editorTheme";
import { getTheme } from "@/utils/theme";
import {
  computed,
  inject,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  useRoute,
  useRouter,
} from "vue-router";
const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess")!;

const fileStore = useFileStore();
const authStore = useAuthStore();
const layoutStore = useLayoutStore();

const route = useRoute();
const router = useRouter();

const isMarkdownFile =
  fileStore.req?.name.endsWith(".md") ||
  fileStore.req?.name.endsWith(".markdown");
const MARKDOWN_RICH_EDITOR_MAX_BYTES = 2 * 1024 * 1024;
const isLargeMarkdown =
  isMarkdownFile &&
  Math.max(fileStore.req?.size ?? 0, fileStore.req?.content?.length ?? 0) >
    MARKDOWN_RICH_EDITOR_MAX_BYTES;
const usesVditor = isMarkdownFile && !isLargeMarkdown;
const aceEditorReady = ref(false);
const showLineNumbers = ref(!usesVditor);
const langCaption = ref("");
const markdownImageUploading = ref(false);
const markdownImageUploadLabel = ref("");
let markdownImagePreviewObserver: MutationObserver | null = null;
let markdownImagePreviewScheduled = false;
let markdownImagePreviewSources = new Map<string, string>();
const showCodeLanguagePicker = ref(false);
const codeLanguageQuery = ref("");
const codeLanguageSearchInput = ref<HTMLInputElement | null>(null);
const codeLanguageActiveIndex = ref(0);
const codeLanguageTargetIndex = ref<number | null>(null);
const filteredCodeLanguageOptions = computed(() =>
  filterMarkdownCodeLanguages(codeLanguageQuery.value)
);
const activeCodeLanguageOptionId = computed(() => {
  const option =
    filteredCodeLanguageOptions.value[codeLanguageActiveIndex.value];
  return option ? `markdown-language-option-${option.value}` : undefined;
});

watch(codeLanguageQuery, () => {
  codeLanguageActiveIndex.value = 0;
});

type MarkdownMode = "ir" | "sv" | "preview";
type MarkdownEditMode = Exclude<MarkdownMode, "preview">;

const currentMode = ref<MarkdownMode>("ir");
const showOutline = ref(true);
const showWidePreview = ref(false);
let vditorInstance: VditorInstance | null = null;
let aceEditor: Ace.Editor | null = null;
// Content tracking removed - unused variables
let mdInitialized = false; // Vditor 是否已完成初始化
let userEdited = false; // 用户是否实际修改过内容
let initialContent = ""; // 文件初始内容，用于 close 时的内容比对兜底
let markdownBuffer = "";
let editorGeneration = 0;
let markdownPreviewHighlightTimer: number | null = null;
let markdownHighlightObserver: MutationObserver | null = null;
let markdownBaselineReady = false;
let stopThemeObserver: (() => void) | null = null;

const markdownLineNumberKey = () =>
  getMarkdownLineNumberStorageKey(authStore.user?.id);
const markdownOutlineKey = () =>
  getMarkdownOutlineStorageKey(authStore.user?.id);
const markdownWidthKey = () => getMarkdownWidthStorageKey(authStore.user?.id);

const scheduleMarkdownPreviewHighlight = (generation: number) => {
  // Vditor can emit a burst of mutations while an IR block settles. Keep the
  // first pending pass instead of continually postponing highlighting until
  // the mutation burst ends; the pass itself compares the current source and
  // is safe to repeat when Vditor replaces the preview node.
  if (markdownPreviewHighlightTimer !== null) return;
  markdownPreviewHighlightTimer = window.setTimeout(() => {
    markdownPreviewHighlightTimer = null;
    if (generation !== editorGeneration) return;
    const currentMount = document.getElementById("vditor-mount");
    if (currentMount) {
      highlightMarkdownEditorPreviews(currentMount, {
        showLineNumbers: showLineNumbers.value,
      });
    }
  }, 120);
};

const setupMarkdownPreviewHighlightObserver = (generation: number) => {
  markdownHighlightObserver?.disconnect();
  markdownHighlightObserver = null;
  const mountEl = document.getElementById("vditor-mount");
  if (!mountEl) return;

  markdownHighlightObserver = new MutationObserver(() => {
    if (generation !== editorGeneration || currentMode.value !== "ir") return;
    const needsHighlight = Array.from(
      mountEl.querySelectorAll<HTMLElement>(".vditor-ir__preview code")
    ).some((code) => {
      const hasOurMarkup = Boolean(
        code.querySelector(".code-line, .code-line-content")
      );
      const sourceNode = code
        .closest<HTMLElement>(".vditor-ir__node")
        ?.querySelector<HTMLElement>(".vditor-ir__marker--pre > code");
      const rawSource = (
        hasOurMarkup && code.dataset.rawSource !== undefined
          ? code.dataset.rawSource
          : (sourceNode?.textContent ?? code.textContent ?? "")
      ).replace(/\u200b/g, "");
      const hasHighlightMarkup = Boolean(code.querySelector("span"));
      return (
        code.dataset.nfbHighlighted !== "true" ||
        code.dataset.rawSource !== rawSource ||
        code.dataset.nfbLineNumbers !== String(showLineNumbers.value) ||
        (code.dataset.nfbHighlightMarkup === "true" && !hasHighlightMarkup)
      );
    });
    if (needsHighlight) scheduleMarkdownPreviewHighlight(generation);
  });
  markdownHighlightObserver.observe(mountEl, {
    childList: true,
    characterData: true,
    subtree: true,
  });
  scheduleMarkdownPreviewHighlight(generation);
};

const teardownMarkdownPreviewHighlightObserver = () => {
  markdownHighlightObserver?.disconnect();
  markdownHighlightObserver = null;
};

const restoreLineNumberPreference = () => {
  if (!isMarkdownFile) return;
  showLineNumbers.value =
    localStorage.getItem(markdownLineNumberKey()) === "true";
};

const persistLineNumberPreference = () => {
  if (!isMarkdownFile) return;
  localStorage.setItem(markdownLineNumberKey(), String(showLineNumbers.value));
};

const restoreOutlinePreference = () => {
  if (!isMarkdownFile) return;
  showOutline.value = localStorage.getItem(markdownOutlineKey()) !== "false";
};

const persistOutlinePreference = () => {
  if (!isMarkdownFile) return;
  localStorage.setItem(markdownOutlineKey(), String(showOutline.value));
};

const restoreWidthPreference = () => {
  if (!isMarkdownFile) return;
  showWidePreview.value = localStorage.getItem(markdownWidthKey()) === "wide";
};

const persistWidthPreference = () => {
  if (!isMarkdownFile) return;
  localStorage.setItem(
    markdownWidthKey(),
    showWidePreview.value ? "wide" : "readable"
  );
};

restoreLineNumberPreference();
restoreOutlinePreference();
restoreWidthPreference();

watch(
  () => authStore.user?.id,
  () => {
    restoreLineNumberPreference();
    restoreOutlinePreference();
    restoreWidthPreference();
  }
);

onMounted(() => {
  stopThemeObserver = observeMarkdownThemeChanges();
  window.addEventListener("keydown", keyEvent);
  window.addEventListener("beforeunload", handlePageChange);

  const fileContent = fileStore.req?.content || "";
  markdownBuffer = fileContent;

  const initEditor = () => {
    setTimeout(() => {
      if (usesVditor) {
        initVditor(fileContent);
      } else {
        void initAceEditor(fileContent).catch($showError);
      }
    }, 50);
  };

  if (!layoutStore.loading) {
    initEditor();
  } else {
    // 等待 loading 结束后再初始化编辑器
    const stop = watch(
      () => layoutStore.loading,
      (loading) => {
        if (!loading) {
          stop();
          initEditor();
        }
      }
    );
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("keydown", keyEvent);
  window.removeEventListener("beforeunload", handlePageChange);
  stopThemeObserver?.();
  stopThemeObserver = null;
  const mountEl = document.getElementById("vditor-mount");
  if (mountEl) {
    mountEl.removeEventListener("click", handleOutlineCapture, true);
  }
  if (markdownPreviewHighlightTimer !== null) {
    window.clearTimeout(markdownPreviewHighlightTimer);
    markdownPreviewHighlightTimer = null;
  }
  teardownMarkdownPreviewHighlightObserver();
  _outlineHandlerBound = false;
  teardownMarkdownImagePreviews();
  if (vditorInstance) {
    try {
      vditorInstance.destroy();
    } catch {}
    vditorInstance = null;
  }
  if (aceEditor) {
    aceEditor.destroy();
    aceEditor = null;
  }
});

onBeforeRouteUpdate((to, from, next) => {
  if (markdownImageUploading.value) {
    $showError("图片正在保存，请稍候再离开编辑器", false);
    next(false);
    return;
  }
  if (layoutStore.loading) {
    next();
    return;
  }

  const isDirty = usesVditor
    ? vditorInstance && userEdited && mdInitialized
    : !aceEditor?.session.getUndoManager().isClean();

  if (!isDirty) {
    next();
    return;
  }

  // 双重校验：flag 可能有误，用实际内容比对兜底
  if (usesVditor && vditorInstance && mdInitialized) {
    try {
      const currentContent = getVditorMarkdown();
      if (currentContent === initialContent) {
        userEdited = false;
        next();
        return;
      }
    } catch {}
  } else if (aceEditor) {
    const currentContent = aceEditor.getValue();
    if (currentContent === initialContent) {
      aceEditor.session.getUndoManager().markClean();
      next();
      return;
    }
  }

  layoutStore.showHover({
    prompt: "discardEditorChanges",
    confirm: (event: Event) => {
      event.preventDefault();
      if (aceEditor) aceEditor.session.getUndoManager().reset();
      userEdited = false;
      next();
    },
    saveAction: async () => {
      try {
        await save(true);
        next();
      } catch {}
    },
  });
});

onBeforeRouteLeave(() => {
  if (!markdownImageUploading.value) return true;
  $showError("图片正在保存，请稍候再离开编辑器", false);
  return false;
});

const rewriteMarkdownImagePreviews = () => {
  const documentPath = fileStore.req?.path;
  const mountEl = document.getElementById("vditor-mount");
  if (!documentPath || !mountEl) return;

  mountEl.querySelectorAll<HTMLImageElement>("img[src]").forEach((image) => {
    const currentSource = image.getAttribute("src") ?? "";
    const markdownSource =
      markdownImagePreviewSources.get(currentSource) ?? currentSource;

    const previewSource = markdownImagePreviewSource(
      documentPath,
      markdownSource
    );
    if (!previewSource) {
      return;
    }

    markdownImagePreviewSources.set(previewSource, markdownSource);
    if (currentSource !== previewSource) {
      image.setAttribute("src", previewSource);
    }
  });
};

const scheduleMarkdownImagePreviewRewrite = () => {
  if (markdownImagePreviewScheduled) return;
  markdownImagePreviewScheduled = true;
  queueMicrotask(() => {
    markdownImagePreviewScheduled = false;
    rewriteMarkdownImagePreviews();
  });
};

const setupMarkdownImagePreviews = () => {
  markdownImagePreviewObserver?.disconnect();
  markdownImagePreviewObserver = null;
  markdownImagePreviewScheduled = false;
  const mountEl = document.getElementById("vditor-mount");
  if (!mountEl) return;
  markdownImagePreviewObserver = new MutationObserver(
    scheduleMarkdownImagePreviewRewrite
  );
  markdownImagePreviewObserver.observe(mountEl, {
    attributes: true,
    attributeFilter: ["src"],
    childList: true,
    subtree: true,
  });
  rewriteMarkdownImagePreviews();
};

const prepareMarkdownImagePreviewContent = (content: string) => {
  const documentPath = fileStore.req?.path;
  if (!documentPath) return content;
  const prepared = markdownImagePreviewContent(documentPath, content);
  prepared.sources.forEach(({ markdownSource, previewSource }) => {
    markdownImagePreviewSources.set(previewSource, markdownSource);
  });
  return prepared.markdown;
};

function teardownMarkdownImagePreviews() {
  markdownImagePreviewObserver?.disconnect();
  markdownImagePreviewObserver = null;
  markdownImagePreviewScheduled = false;
  markdownImagePreviewSources = new Map();
}

const getVditorMarkdown = () => {
  if (!vditorInstance) return markdownBuffer;
  const mountEl = document.getElementById("vditor-mount");
  const previews = mountEl
    ? Array.from(mountEl.querySelectorAll<HTMLImageElement>("img[src]"))
        .map((image) => ({
          image,
          markdownSource: markdownImagePreviewSources.get(
            image.getAttribute("src") ?? ""
          ),
        }))
        .filter(
          (
            item
          ): item is {
            image: HTMLImageElement;
            markdownSource: string;
          } => Boolean(item.markdownSource)
        )
    : [];

  previews.forEach(({ image, markdownSource }) => {
    image.setAttribute("src", markdownSource);
  });
  try {
    return vditorInstance.getValue();
  } finally {
    rewriteMarkdownImagePreviews();
  }
};

const initVditor = async (content: string) => {
  initialContent = content;
  markdownBuffer = content;
  markdownBaselineReady = false;
  mdInitialized = false;
  userEdited = false;
  await initVditorWithMode(content, "ir");
};

const loadVditorResources = async () => {
  await loadMarkdownResources();
};

const initVditorWithMode = async (
  content: string,
  mode: MarkdownEditMode,
  dirtyState = false
) => {
  const generation = ++editorGeneration;
  await loadVditorResources();

  if (generation !== editorGeneration) return;

  const VditorClass = window.Vditor;
  const mountEl = document.getElementById("vditor-mount");
  if (!VditorClass || !mountEl) return;

  const isDark = isDarkTheme();

  vditorInstance = new VditorClass("vditor-mount", {
    cdn: getVditorAssetRoot(),
    value: prepareMarkdownImagePreviewContent(content),
    lang: "zh_CN",
    theme: isDark ? "dark" : "classic",
    mode: mode,
    toolbar: [
      "emoji",
      "headings",
      "bold",
      "italic",
      "strike",
      "|",
      "line",
      "quote",
      "list",
      "ordered-list",
      "check",
      "|",
      "code",
      {
        name: "code-language",
        tipPosition: "s",
        tip: "插入代码块",
        icon: '<svg><use xlink:href="#vditor-icon-code"></use></svg>',
        click: () => openCodeLanguagePicker(),
      },
      "inline-code",
      "table",
      "|",
      "link",
      "upload",
      "|",
      "undo",
      "redo",
      "|",
      "fullscreen",
      "|",
      {
        name: "source-mode",
        tipPosition: "s",
        tip: "源码模式",
        icon: '<svg><use xlink:href="#vditor-icon-both"></use></svg>',
        click: () => switchMode("sv"),
      },
    ],
    toolbarConfig: {
      hide: false,
    },
    outline: {
      enable: showOutline.value,
      position: "right",
    },
    counter: {
      enable: true,
    },
    typewriterMode: true,
    undoDelay: 200,
    tab: "\t",
    preview: {
      // Vditor 的内置行号插件会在 IR DOM 稳定后再次重写 code 内容，
      // 与应用逐行高亮/行号装饰冲突并把源码拼成一行。行号由应用层
      // `highlightMarkdownEditorPreviews` 统一负责，保留 Vditor 的高亮
      // 配置但关闭它自己的 gutter。
      hljs: {
        ...getMarkdownHighlightOptions(showLineNumbers.value),
        lineNumber: false,
      },
      theme: {
        current: isDark ? "dark" : "light",
      },
      markdown: {
        sanitize: true,
        toc: true,
        codeBlockPreview: true,
      },
    },
    upload: {
      accept: "image/*",
      multiple: true,
      handler: handleMarkdownImageUpload,
    },
    after: () => {
      if (generation !== editorGeneration) return;
      // 编辑器初始化完成后，捕获 Vditor 规范化后的内容作为基线
      // 这样即使 Vditor 对内容做了微调（如尾部换行），也不会误判为 dirty
      try {
        const normalized = getVditorMarkdown();
        markdownBuffer = normalized;
        if (!markdownBaselineReady) {
          initialContent = normalized;
          markdownBaselineReady = true;
        }
      } catch {}
      mdInitialized = true;
      // 初始化期间可能触发 input 事件，重置 dirty 标记
      userEdited = dirtyState;
      setupMarkdownImagePreviews();
      // 确保大纲目录点击可以跳转
      setupOutlineClickHandler();
      setupMarkdownPreviewHighlightObserver(generation);
      if (currentMode.value === "preview") refreshMarkdownCodeBlocks();
    },
    input: () => {
      // 用户实际编辑内容时标记为 dirty
      if (mdInitialized) {
        userEdited = true;
      }
      try {
        markdownBuffer = getVditorMarkdown();
      } catch {}
      scheduleMarkdownPreviewHighlight(generation);
    },
  });
};

// 确保大纲目录点击可以跳转到对应标题
// 使用捕获阶段事件监听，绕过 Vditor 内置的 stopPropagation
let _outlineHandlerBound = false;

const handleOutlineCapture = (e: MouseEvent) => {
  const target = e.target as HTMLElement;
  if (!target) return;

  // 检查是否点击了展开/折叠按钮，放行
  if (target.closest(".vditor-outline__action")) return;

  // 向上查找带有 data-target-id 的元素
  const outlineItem = target.closest("[data-target-id]") as HTMLElement;
  if (!outlineItem) return;

  const targetId = outlineItem.getAttribute("data-target-id");
  if (!targetId) return;

  e.preventDefault();
  e.stopPropagation();
  e.stopImmediatePropagation();

  const headingEl = document.getElementById(targetId);
  if (!headingEl) return;

  // 找到实际的滚动容器（vditor-mount 内部的可滚动元素）
  const mountEl = document.getElementById("vditor-mount");
  if (!mountEl) return;

  // 在 IR/SV 模式下，内容区域是可滚动的
  // 查找从标题元素到 mountEl 之间的可滚动父元素
  let scrollContainer: HTMLElement | null = null;
  let cur: HTMLElement | null = headingEl;
  while (cur && cur !== mountEl) {
    const style = window.getComputedStyle(cur);
    if (
      /(auto|scroll)/.test(style.overflow + style.overflowY) &&
      cur.scrollHeight > cur.clientHeight
    ) {
      scrollContainer = cur;
      break;
    }
    cur = cur.parentElement;
  }

  if (scrollContainer) {
    // 标题在内部滚动容器中，手动计算偏移量
    const containerRect = scrollContainer.getBoundingClientRect();
    const headingRect = headingEl.getBoundingClientRect();
    const offset =
      headingRect.top - containerRect.top + scrollContainer.scrollTop - 20;
    scrollContainer.scrollTo({ top: Math.max(0, offset), behavior: "smooth" });
  } else {
    // 回退: scrollIntoView 或 window scrollTo
    headingEl.scrollIntoView({ behavior: "smooth", block: "start" });
  }
};

const setupOutlineClickHandler = () => {
  const mountEl = document.getElementById("vditor-mount");
  if (!mountEl || _outlineHandlerBound) return;

  // 使用捕获阶段监听，确保在 Vditor 的冒泡处理器之前执行
  mountEl.addEventListener("click", handleOutlineCapture, true);
  _outlineHandlerBound = true;
};

const initVditorPreview = async (content: string) => {
  const generation = ++editorGeneration;
  await loadHighlight();

  if (generation !== editorGeneration) return;

  const VditorClass = window.Vditor;
  const mountEl = document.getElementById("vditor-mount");
  if (!VditorClass || !mountEl) return;

  const isDark = isDarkTheme();

  // 阅读模式：用 Vditor 的预览方法渲染为纯 HTML
  // md2html 可能返回 Promise 或 string，统一用 Promise.resolve 包裹确保 await
  const htmlResult = VditorClass.md2html(
    prepareMarkdownImagePreviewContent(content),
    {
      cdn: getVditorAssetRoot(),
      mode: isDark ? "dark" : "light",
    }
  );
  const html = await Promise.resolve(htmlResult);
  if (typeof html !== "string") {
    console.error("[Vditor] md2html returned non-string:", html);
    return;
  }

  const previewShell = document.createElement("div");
  previewShell.className = getMarkdownPreviewShellClass(
    showOutline.value,
    showWidePreview.value
  );

  const previewElement = document.createElement("div");
  previewElement.className = "vditor-reset vditor-preview--content";
  previewElement.innerHTML = html;
  previewShell.appendChild(previewElement);

  let outlineElement: HTMLElement | null = null;
  if (showOutline.value) {
    outlineElement = document.createElement("aside");
    outlineElement.className = "markdown-preview-outline";
    outlineElement.setAttribute("aria-label", "文档大纲");
    const title = document.createElement("strong");
    title.className = "markdown-preview-outline-title";
    title.textContent = "大纲";
    outlineElement.appendChild(title);

    const list = document.createElement("nav");
    list.className = "markdown-preview-outline-list";
    previewElement
      .querySelectorAll<HTMLElement>("h1, h2, h3, h4, h5, h6")
      .forEach((heading, index) => {
        const targetId = heading.id || `markdown-heading-${index + 1}`;
        heading.id = targetId;
        const link = document.createElement("a");
        link.href = `#${targetId}`;
        link.className = `outline-level-${heading.tagName.slice(1)}`;
        link.textContent = heading.textContent?.trim() || `章节 ${index + 1}`;
        link.addEventListener("click", (event) => {
          event.preventDefault();
          heading.scrollIntoView({ behavior: "smooth", block: "start" });
        });
        list.appendChild(link);
      });
    outlineElement.appendChild(list);
    previewShell.appendChild(outlineElement);
  }
  mountEl.appendChild(previewShell);

  // 为代码块添加语法高亮、语言标签和可选行号
  highlightAndAnnotateCodeBlocks(previewElement, {
    showLineNumbers: showLineNumbers.value,
  });

  // 保存一个伪实例，getValue 时返回原始内容
  vditorInstance = {
    getValue: () => content,
    destroy: () => {
      previewShell.remove();
    },
  };
  // 预览模式基线与实际内容一致
  mdInitialized = true;
  setupMarkdownImagePreviews();
};

const initAceEditor = async (content: string) => {
  const { default: ace } = await import("ace-builds");
  const aceGlobal = globalThis as typeof globalThis & { ace: typeof ace };
  aceGlobal.ace = ace;
  await import("ace-builds/src-noconflict/ext-language_tools");
  const { default: modelist } =
    await import("ace-builds/src-noconflict/ext-modelist");
  const editorTheme = await getEditorTheme(
    authStore.user?.aceEditorTheme ?? ""
  );

  initialContent = content;
  const aceAssetRoot = getAceAssetRoot();
  ace.config.set("basePath", aceAssetRoot);
  ace.config.set("modePath", aceAssetRoot);
  ace.config.set("themePath", aceAssetRoot);
  ace.config.set("workerPath", aceAssetRoot);

  const isDark = getTheme() === "dark";

  aceEditor = ace.edit("editor", {
    value: content,
    showPrintMargin: false,
    readOnly: fileStore.req?.type === "textImmutable",
    theme: editorTheme,
    mode: modelist.getModeForPath(fileStore.req!.name).mode,
    wrap: true,
    enableBasicAutocompletion: true,
    enableLiveAutocompletion: true,
    enableSnippets: true,
    // 代码可读性增强
    displayIndentGuides: true,
    highlightActiveLine: true,
    highlightSelectedWord: true,
    highlightGutterLine: true,
    showGutter: true,
    showLineNumbers: true,
    // 括号匹配
    behavioursEnabled: true,
    wrapBehavioursEnabled: true,
    // 滚动与导航
    animatedScroll: true,
    cursorStyle: "smooth",
    fadeFoldWidgets: false,
    // 字体渲染
    fontFamily:
      "'JetBrains Mono', 'Fira Code', 'SF Mono', 'Cascadia Code', 'Consolas', 'Monaco', monospace",
    fontSize: parseInt(localStorage.getItem("editorFontSize") || "14"),
    // 偏好设置
    tabSize: 2,
    useSoftTabs: true,
    navigateWithinSoftTabs: true,
  });

  // 暗色模式下覆盖选区和活动行颜色，确保可读性
  if (isDark) {
    aceEditor.setOptions({
      selectionStyle: "text",
    });
  }

  // 确保 undo manager 标记为 clean，避免误判为 dirty
  aceEditor.session.getUndoManager().markClean();
  aceEditor.focus();
  aceEditorReady.value = true;

  // 获取并设置语言名称
  const detectedMode = modelist.getModeForPath(fileStore.req!.name);
  langCaption.value = detectedMode.caption || detectedMode.name || "";
};

const openCodeLanguagePicker = () => {
  if (!usesVditor || !vditorInstance) return;
  codeLanguageTargetIndex.value = getActiveMarkdownCodeBlockIndex();
  codeLanguageQuery.value = "";
  codeLanguageActiveIndex.value = 0;
  showCodeLanguagePicker.value = true;
  nextTick(() => codeLanguageSearchInput.value?.focus());
};

const getActiveMarkdownCodeBlockIndex = (): number | null => {
  if (currentMode.value !== "ir") return null;
  const selection = window.getSelection();
  const anchor = selection?.anchorNode;
  const anchorElement =
    anchor?.nodeType === Node.ELEMENT_NODE
      ? (anchor as Element)
      : anchor?.parentElement;
  const editor = document.querySelector("#vditor-mount .vditor-ir");
  const activeBlock = anchorElement?.closest('[data-type="code-block"]');
  if (!editor || !activeBlock || !editor.contains(activeBlock)) return null;

  const blocks = Array.from(
    editor.querySelectorAll('[data-type="code-block"]')
  );
  const index = blocks.indexOf(activeBlock);
  return index >= 0 ? index : null;
};

const moveCodeLanguageSelection = (direction: number) => {
  const count = filteredCodeLanguageOptions.value.length;
  if (count === 0) return;
  codeLanguageActiveIndex.value =
    (codeLanguageActiveIndex.value + direction + count) % count;
  nextTick(() => {
    document
      .getElementById(activeCodeLanguageOptionId.value ?? "")
      ?.scrollIntoView({ block: "nearest" });
  });
};

const selectActiveCodeLanguage = () => {
  const option =
    filteredCodeLanguageOptions.value[codeLanguageActiveIndex.value];
  if (option) applyMarkdownCodeLanguage(option.value);
};

const closeCodeLanguagePicker = () => {
  showCodeLanguagePicker.value = false;
  codeLanguageTargetIndex.value = null;
  vditorInstance?.focus?.();
};

const applyMarkdownCodeLanguage = (language: string) => {
  const targetIndex = codeLanguageTargetIndex.value;
  closeCodeLanguagePicker();

  if (targetIndex !== null && vditorInstance?.setValue) {
    const current = getVditorMarkdown();
    const updated = updateMarkdownCodeFenceLanguage(
      current,
      targetIndex,
      language
    );
    if (updated !== current) {
      vditorInstance.setValue(updated);
      markdownBuffer = updated;
      userEdited = true;
    }
    return;
  }

  if (!vditorInstance?.insertValue) return;
  vditorInstance.insertValue(createMarkdownCodeFence(language));
  try {
    markdownBuffer = getVditorMarkdown();
  } catch {}
  userEdited = true;
};

const handleMarkdownImageUpload = async (files: File[]): Promise<null> => {
  if (!authStore.user?.perm.create || !authStore.user?.perm.modify) {
    $showError("拖入图片需要创建文件和修改 Markdown 的权限", false);
    return null;
  }
  if (markdownImageUploading.value) {
    $showError("已有图片正在保存，请稍候", false);
    return null;
  }
  if (!vditorInstance?.insertMD) {
    $showError("Markdown 编辑器尚未准备好", false);
    return null;
  }
  const documentPath = fileStore.req?.path;
  if (!documentPath) {
    $showError("无法确定 Markdown 文档路径", false);
    return null;
  }

  markdownImageUploading.value = true;
  try {
    for (const [index, file] of files.entries()) {
      markdownImageUploadLabel.value = `正在保存图片 ${index + 1} / ${files.length}`;
      const stored = await storeMarkdownImage(
        documentPath,
        file,
        api.postExclusive
      );
      vditorInstance.insertMD(stored.markdown);
      vditorInstance.insertEmptyBlock?.("afterend");
      rewriteMarkdownImagePreviews();
      markdownBuffer = getVditorMarkdown();
      userEdited = true;
    }
    $showSuccess(
      files.length === 1
        ? "图片已保存到 assets；请手动保存 Markdown"
        : `${files.length} 张图片已保存到 assets；请手动保存 Markdown`
    );
    return null;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    $showError(`图片保存失败：${message}`, false);
    return null;
  } finally {
    markdownImageUploading.value = false;
    markdownImageUploadLabel.value = "";
  }
};

const rebuildMarkdownMode = async (mode: MarkdownMode) => {
  if (!vditorInstance) return;

  let content: string;
  try {
    content = getVditorMarkdown();
  } catch {
    content = markdownBuffer;
  }
  const dirtyState = userEdited;
  markdownBuffer = content;

  teardownMarkdownPreviewHighlightObserver();
  teardownMarkdownImagePreviews();
  try {
    vditorInstance.destroy();
  } catch {}
  vditorInstance = null;
  mdInitialized = false;
  editorGeneration++;
  _outlineHandlerBound = false;

  await new Promise((resolve) => setTimeout(resolve, 50));

  if (mode === "preview") {
    await initVditorPreview(content);
    userEdited = dirtyState;
    return;
  }
  await initVditorWithMode(content, mode, dirtyState);
};

const toggleLineNumbers = async () => {
  if (markdownImageUploading.value) {
    $showError("图片正在保存，请稍候再切换行号", false);
    return;
  }
  if (usesVditor && currentMode.value === "sv") return;
  showLineNumbers.value = !showLineNumbers.value;
  persistLineNumberPreference();
  if (aceEditor) {
    aceEditor.setOptions({
      showGutter: showLineNumbers.value,
      showLineNumbers: showLineNumbers.value,
    });
  }
  if (usesVditor && currentMode.value !== "preview") {
    await rebuildMarkdownMode(currentMode.value);
    return;
  }
  refreshMarkdownCodeBlocks();
};

const toggleOutline = async () => {
  if (markdownImageUploading.value) {
    $showError("图片正在保存，请稍候再切换大纲", false);
    return;
  }
  if (!usesVditor) return;
  showOutline.value = !showOutline.value;
  persistOutlinePreference();
  await rebuildMarkdownMode(currentMode.value);
};

const togglePreviewWidth = () => {
  if (markdownImageUploading.value) {
    $showError("图片正在保存，请稍候再切换正文宽度", false);
    return;
  }
  if (!usesVditor || currentMode.value !== "preview") return;

  showWidePreview.value = !showWidePreview.value;
  persistWidthPreference();
  document
    .querySelector("#vditor-mount .markdown-preview-shell")
    ?.classList.toggle("is-wide", showWidePreview.value);
};

const refreshMarkdownCodeBlocks = () => {
  const mountEl = document.getElementById("vditor-mount");
  if (!mountEl || !usesVditor || currentMode.value !== "preview") return;
  highlightAndAnnotateCodeBlocks(mountEl, {
    showLineNumbers: showLineNumbers.value,
  });
};

const switchMode = async (mode: MarkdownMode) => {
  if (markdownImageUploading.value) {
    $showError("图片正在保存，请稍候再切换模式", false);
    return;
  }
  if (!vditorInstance) return;
  if (currentMode.value === mode) return;

  // 保存当前内容
  let content: string;
  try {
    // getValue() is authoritative, including an intentionally empty document.
    // The buffer is only a fallback if Vditor has already detached its DOM.
    content = getVditorMarkdown();
  } catch {
    content = markdownBuffer;
  }
  markdownBuffer = content;
  const dirtyState = userEdited;
  currentMode.value = mode;
  // 模式切换不算用户编辑
  // userEdited 保持不变，因为用户之前可能已经编辑过

  // 销毁旧实例，按新模式重建（Vditor 不支持运行时切换模式）
  teardownMarkdownPreviewHighlightObserver();
  teardownMarkdownImagePreviews();
  try {
    vditorInstance.destroy();
  } catch {}
  vditorInstance = null;
  mdInitialized = false;
  editorGeneration++;

  // 重置大纲处理器绑定标记（新实例需要重新绑定）
  _outlineHandlerBound = false;

  // 等待 DOM 更新
  await new Promise((r) => setTimeout(r, 50));

  if (mode === "preview") {
    // 阅读模式：用 Vditor 的预览模式（只有预览面板）
    await initVditorPreview(content);
  } else {
    // ir 或 sv 模式
    await initVditorWithMode(content, mode, dirtyState);
  }
};

const keyEvent = (event: KeyboardEvent) => {
  if (event.code === "Escape") {
    if (showCodeLanguagePicker.value) {
      showCodeLanguagePicker.value = false;
      return;
    }
    close();
  }

  if (!event.ctrlKey && !event.metaKey) return;
  if (event.key !== "s") return;

  event.preventDefault();
  save();
};

const handlePageChange = (event: BeforeUnloadEvent) => {
  if (markdownImageUploading.value) {
    event.preventDefault();
    event.returnValue = true;
    return;
  }
  // 编辑器未初始化完成时，不提示
  if (layoutStore.loading) return;

  if (usesVditor && vditorInstance) {
    // Vditor dirty 检测：用 userEdited 标记 + 内容比对双重判断
    if (userEdited && mdInitialized) {
      try {
        const currentContent = getVditorMarkdown();
        if (currentContent === initialContent) return;
      } catch {}
      event.preventDefault();
      event.returnValue = true;
    }
    return;
  }
  if (aceEditor && !aceEditor.session.getUndoManager().isClean()) {
    const currentContent = aceEditor.getValue();
    if (currentContent === initialContent) return;
    event.preventDefault();
    event.returnValue = true;
  }
};

const save = async (throwError?: boolean) => {
  if (markdownImageUploading.value) {
    const error = new Error("图片正在保存，请稍候再保存 Markdown");
    $showError(error, false);
    if (throwError) throw error;
    return;
  }
  const button = "save";
  buttons.loading("save");

  try {
    let content = "";
    if (usesVditor && vditorInstance) {
      content = getVditorMarkdown();
      markdownBuffer = content;
    } else if (aceEditor) {
      content = aceEditor.getValue();
    }
    await api.put(route.path, content as ApiContent);
    if (isMarkdownFile) {
      userEdited = false;
    }
    initialContent = content;
    if (aceEditor) {
      aceEditor.session.getUndoManager().markClean();
    }
    buttons.success(button);
  } catch (e: any) {
    buttons.done(button);
    $showError(e);
    if (throwError) throw e;
  }
};

const close = () => {
  if (markdownImageUploading.value) {
    $showError("图片正在保存，请稍候再关闭编辑器", false);
    return;
  }
  // 编辑器未初始化完成时，直接关闭
  if (layoutStore.loading) {
    finishClose();
    return;
  }

  const isDirty = usesVditor
    ? vditorInstance && userEdited && mdInitialized
    : !aceEditor?.session.getUndoManager().isClean();

  if (!isDirty) {
    finishClose();
    return;
  }

  // 双重校验：flag 可能有误，用实际内容比对兜底
  if (usesVditor && vditorInstance && mdInitialized) {
    try {
      const currentContent = getVditorMarkdown();
      if (currentContent === initialContent) {
        userEdited = false;
        finishClose();
        return;
      }
    } catch {}
  } else if (aceEditor) {
    const currentContent = aceEditor.getValue();
    if (currentContent === initialContent) {
      aceEditor.session.getUndoManager().markClean();
      finishClose();
      return;
    }
  }

  layoutStore.showHover({
    prompt: "discardEditorChanges",
    confirm: (event: Event) => {
      event.preventDefault();
      if (aceEditor) aceEditor.session.getUndoManager().reset();
      userEdited = false;
      finishClose();
    },
    saveAction: async () => {
      try {
        await save(true);
        finishClose();
      } catch {}
    },
  });
};

const finishClose = () => {
  const uri = url.removeLastDir(route.path) + "/";
  router.push({ path: uri });
};
</script>

<style scoped>
.vditor-mount {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.editor-lightweight-shell {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
}

.editor-lightweight-shell #editor {
  min-height: 0;
  flex: 1;
}

.markdown-image-upload-status {
  display: flex;
  min-height: 2.25rem;
  align-items: center;
  justify-content: center;
  gap: 0.55rem;
  padding: 0 1rem;
  border-bottom: 1px solid var(--borderSecondary);
  color: var(--textPrimary);
  background: color-mix(in srgb, var(--blue) 8%, var(--surfacePrimary));
  font-size: 0.82rem;
}

.markdown-image-upload-status span {
  width: 0.8rem;
  height: 0.8rem;
  border: 2px solid color-mix(in srgb, var(--blue) 28%, transparent);
  border-top-color: var(--blue);
  border-radius: 50%;
  animation: markdown-image-upload-spin 700ms linear infinite;
}

@keyframes markdown-image-upload-spin {
  to {
    transform: rotate(1turn);
  }
}

@media (prefers-reduced-motion: reduce) {
  .markdown-image-upload-status span {
    animation: none;
    border-color: var(--blue);
  }
}

.editor-degraded-notice {
  padding: 0.55rem 0.85rem;
  border-bottom: 1px solid var(--borderSecondary);
  color: var(--textSecondary);
  background: color-mix(in srgb, var(--icon-yellow) 16%, var(--surfacePrimary));
  font-size: 0.82rem;
  line-height: 1.45;
}

/* 编辑器标题与操作占满整条工具栏，避免桌面端两侧出现无意义空白。 */
#editor-container :deep(header > .header-center) {
  position: static;
  width: auto;
  padding-inline: 0;
  justify-content: flex-start;
  transform: none;
}

/* 编辑器模式操作使用固定网格对齐，避免图标受字体行高和默认 padding 影响。 */
:global(#editor-container .editor-mode-action) {
  display: grid;
  width: 2.75rem;
  height: 2.75rem;
  box-sizing: border-box;
  place-items: center;
  line-height: 0;
}

/* Header 的桌面下拉区有更高特异性，需显式覆盖其 40px 通用按钮尺寸。 */
:global(header > .header-trailing #dropdown .editor-mode-action) {
  width: 2.75rem;
  height: 2.75rem;
}

:global(#editor-container .editor-mode-action > .app-icon) {
  display: block;
  width: 1.25rem;
  height: 1.25rem;
  padding: 0;
  line-height: 1;
}

/* Vditor 容器全屏 */
#vditor-mount :deep(.vditor) {
  border: none !important;
  height: 100% !important;
  border-radius: 0 !important;
}

/* 隐藏 Vditor 自带的标题栏（我们用自己的 header-bar） */
#vditor-mount :deep(.vditor-toolbar) {
  border-radius: 0 !important;
  border-bottom: 1px solid var(--borderSecondary) !important;
  background: var(--background) !important;
  padding: 4px 8px !important;
}

#vditor-mount :deep(.vditor-content) {
  background: var(--background) !important;
}

/* 活跃的模式按钮 */
.active :deep(.app-icon) {
  color: var(--blue) !important;
}

/* 语言标识 badge */
.editor-lang-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border-radius: 12px;
  font-size: 0.8em;
  font-weight: 500;
  color: var(--textSecondary);
  background: var(--surfaceSecondary);
  border: 1px solid var(--borderSecondary);
  text-transform: capitalize;
  white-space: nowrap;
  user-select: none;
}
.editor-lang-badge .app-icon {
  font-size: 14px;
  opacity: 0.7;
}

.markdown-language-picker-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2200;
  display: grid;
  place-items: start end;
  padding: 3.9rem 0.75rem 0.75rem;
  background: transparent;
}

.markdown-language-picker {
  display: flex;
  width: min(20rem, calc(100vw - 1.5rem));
  max-height: calc(100vh - 4.65rem);
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--borderSecondary);
  border-radius: 8px;
  background: var(--surfacePrimary);
  box-shadow:
    0 12px 28px rgb(15 23 42 / 14%),
    0 2px 6px rgb(15 23 42 / 7%);
}

.markdown-language-picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 2.45rem;
  padding: 0 0.6rem 0 0.75rem;
  border-bottom: 1px solid var(--borderSecondary);
  color: var(--textPrimary);
}

.markdown-language-count {
  margin-left: auto;
  margin-right: 0.5rem;
  color: var(--textSecondary);
  font-size: 0.72rem;
}

.markdown-language-picker-close {
  display: inline-grid;
  width: 1.8rem;
  height: 1.8rem;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: var(--textSecondary);
  background: transparent;
  cursor: pointer;
}

.markdown-language-picker-close:hover,
.markdown-language-picker-close:focus-visible {
  color: var(--textPrimary);
  background: var(--surfaceSecondary);
  outline: none;
}

.markdown-language-options {
  display: grid;
  grid-template-columns: 1fr;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.2rem;
  max-height: min(22rem, calc(100vh - 12rem));
  overflow: auto;
  padding: 0.5rem;
}

.markdown-language-search {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  margin: 0.5rem 0.6rem 0.15rem;
  padding: 0 0.6rem;
  border: 1px solid var(--borderSecondary);
  border-radius: 6px;
  color: var(--textSecondary);
  background: var(--surfacePrimary);
}

.markdown-language-search input {
  flex: 1;
  min-width: 0;
  height: 2.25rem;
  padding: 0;
  border: 0;
  outline: 0;
  color: var(--textPrimary);
  background: transparent;
}

.markdown-language-empty {
  grid-column: 1 / -1;
  margin: 0;
  padding: 0.8rem;
  color: var(--textSecondary);
  text-align: center;
}

.markdown-language-options button {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  min-height: 2.25rem;
  padding: 0 0.55rem;
  border: 1px solid transparent;
  border-radius: 5px;
  color: var(--textPrimary);
  background: var(--surfacePrimary);
  cursor: pointer;
}

.markdown-language-options button:hover,
.markdown-language-options button:focus-visible,
.markdown-language-options button.active {
  border-color: color-mix(in srgb, var(--blue) 35%, transparent);
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 7%, var(--surfacePrimary));
  outline: none;
}

.markdown-language-options .app-icon {
  flex: 0 0 auto;
}

@media (max-width: 480px) {
  .markdown-language-picker-backdrop {
    padding-top: 4rem;
  }

  .markdown-language-options {
    grid-template-columns: 1fr;
  }
}
</style>
