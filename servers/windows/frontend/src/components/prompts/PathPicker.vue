<template>
  <div class="path-picker-backdrop" @click.self="close">
    <section
      ref="dialog"
      class="path-picker"
      :class="{ 'has-shortcuts': visibleShortcuts.length > 0 }"
      role="dialog"
      aria-modal="true"
      aria-labelledby="path-picker-title"
      tabindex="-1"
    >
      <header class="path-picker__header">
        <div>
          <p>选择位置</p>
          <h2 id="path-picker-title">{{ title }}</h2>
        </div>
        <button type="button" aria-label="关闭路径选择器" @click="close">
          <AppIcon name="x" :size="19" />
        </button>
      </header>

      <div class="path-picker__location" aria-live="polite">
        <AppIcon name="folder" :size="18" />
        <nav aria-label="当前位置">
          <template v-for="(crumb, index) in breadcrumbs" :key="crumb.path">
            <span
              v-if="index > 0"
              class="path-picker__location-separator"
              aria-hidden="true"
              >/</span
            >
            <button type="button" @click="load(crumb.path)">
              {{ crumb.name }}
            </button>
          </template>
        </nav>
      </div>

      <nav
        v-if="visibleShortcuts.length"
        class="path-picker__shortcuts"
        aria-label="快捷位置"
      >
        <button
          v-for="shortcut in visibleShortcuts"
          :key="shortcut.path"
          type="button"
          :class="{
            selected: currentPath === normalizePath(shortcut.path, true),
          }"
          @click="load(shortcut.path)"
        >
          <AppIcon name="folder" :size="16" />{{ shortcut.label }}
        </button>
      </nav>

      <div v-if="error" class="path-picker__error" role="alert">
        <AppIcon name="circle-alert" :size="18" />
        <span>{{ error }}</span>
        <button type="button" @click="load(currentPath)">重试</button>
      </div>
      <div v-else-if="loading" class="path-picker__loading" aria-live="polite">
        <AppIcon name="loader" :size="20" />正在读取目录…
      </div>
      <ul v-else class="path-picker__list" aria-label="路径列表">
        <li v-for="item in entries" :key="item.path">
          <div
            class="path-picker__entry"
            :class="{
              selected: !item.isParent && selectedPaths.includes(item.path),
              'has-enter':
                interactionMode === 'analysis' && item.isDir && !item.isParent,
            }"
          >
            <button
              type="button"
              class="path-picker__entry-main"
              @click="handleEntryClick(item)"
              @dblclick="handleEntryDoubleClick(item)"
              @keydown.enter.prevent="handleEntryEnter(item)"
              @keydown.space.prevent="toggleEntrySelection(item)"
            >
              <AppIcon
                :name="
                  item.isParent ? 'arrow-left' : item.isDir ? 'folder' : 'file'
                "
                :size="19"
              />
              <span>{{ item.name }}</span>
            </button>
            <button
              v-if="
                interactionMode === 'analysis' && item.isDir && !item.isParent
              "
              type="button"
              class="path-picker__entry-enter"
              :aria-label="`进入 ${item.name}`"
              @click.stop="open(item.path)"
            >
              <AppIcon name="chevron-right" :size="17" />
            </button>
            <label class="path-picker__entry-action" @click.stop>
              <input
                v-if="!item.isParent && (mode !== 'directory' || item.isDir)"
                type="checkbox"
                :checked="selectedPaths.includes(item.path)"
                :aria-label="`选择 ${item.name}`"
                @change="select(item.path)"
              />
              <AppIcon
                v-else-if="selectedPaths.includes(item.path)"
                name="circle-check"
                :size="17"
              />
            </label>
          </div>
        </li>
        <li v-if="entries.length === 0" class="path-picker__empty">
          当前目录没有可选择的项目
        </li>
      </ul>

      <footer class="path-picker__footer">
        <p v-if="interactionMode === 'analysis'">
          单击选择，双击进入目录；按 Space 切换选择。
        </p>
        <p v-else>单击目录进入；使用右侧选择控件选中路径。</p>
        <div>
          <button
            v-if="interactionMode === 'analysis'"
            type="button"
            @click="selectCurrentDirectory"
          >
            选择当前目录
          </button>
          <button type="button" @click="close">取消</button>
          <button
            type="button"
            class="primary"
            :disabled="
              selectedPaths.length === 0 &&
              (mode === 'file' || interactionMode === 'analysis')
            "
            @click="confirm"
          >
            <AppIcon name="circle-check" :size="17" />
            {{
              multiple
                ? `选择 ${selectedPaths.length} 项`
                : mode === "file"
                  ? "选择此文件"
                  : "选择此目录"
            }}
          </button>
        </div>
      </footer>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import { files } from "@/api";
import { canonicalResourcePath, encodeResourceRoute } from "@/utils/url";

const props = withDefaults(
  defineProps<{
    modelValue?: string | string[];
    title?: string;
    mode?: "directory" | "file" | "both";
    multiple?: boolean;
    interactionMode?: "default" | "analysis";
    exclude?: string[];
    shortcuts?: Array<{ label: string; path: string }>;
    /** When set, only these file extensions are listed; directories always show. */
    fileExtensions?: string[];
  }>(),
  {
    modelValue: "/",
    title: "选择目录",
    mode: "directory",
    multiple: false,
    interactionMode: "default",
    exclude: () => [],
    // The current location breadcrumb already provides a root entry. Keep
    // the optional shortcut rail opt-in so copy/move dialogs do not render a
    // duplicate standalone “根目录” button.
    shortcuts: () => [],
    fileExtensions: undefined,
  }
);

const emit = defineEmits<{
  close: [];
  select: [path: string | string[]];
  "update:modelValue": [path: string | string[]];
}>();

const currentPath = ref(
  normalizePath(
    typeof props.modelValue === "string"
      ? props.modelValue
      : props.modelValue[0] || "/",
    true
  )
);
const selectedPaths = ref<string[]>(
  typeof props.modelValue === "string"
    ? [normalizePath(props.modelValue, props.mode === "directory")]
    : props.modelValue.map((value) =>
        normalizePath(value, props.mode === "directory" || value.endsWith("/"))
      )
);
const dialog = ref<HTMLElement | null>(null);
const loading = ref(false);
const error = ref("");
const entries = ref<
  Array<{ name: string; path: string; isDir: boolean; isParent?: boolean }>
>([]);

// Analysis starts at the current directory and uses the list plus the
// explicit “选择当前目录” action. Showing the generic root shortcut there
// duplicates the breadcrumb and used to consume the whole dialog grid row.
const visibleShortcuts = computed(() =>
  props.interactionMode === "analysis" ? [] : props.shortcuts
);

const breadcrumbs = computed(() => {
  const result = [{ name: "根目录", path: "/" }];
  if (currentPath.value === "/") return result;
  const parts = currentPath.value.replace(/^\/+|\/+$/g, "").split("/");
  let path = "";
  for (const part of parts) {
    path += `/${part}`;
    result.push({ name: part, path: `${path}/` });
  }
  return result;
});

const parentPath = computed(() => {
  if (currentPath.value === "/") return null;
  const trimmed = currentPath.value.replace(/\/+$/, "");
  const index = trimmed.lastIndexOf("/");
  return index <= 0 ? "/" : `${trimmed.slice(0, index)}/`;
});

function fileMatchesFilter(name: string) {
  const exts = props.fileExtensions;
  if (!exts || exts.length === 0) return true;
  const ext = (name.split(".").pop() || "").toLowerCase();
  return exts.includes(ext);
}

async function load(path: string) {
  currentPath.value = normalizePath(path);
  loading.value = true;
  error.value = "";
  try {
    const resource = await files.fetch(encodeResourceRoute(currentPath.value));
    // Directories are always listed so the user can navigate; only files are filtered.
    const next = resource.items
      .filter((item) => {
        if (item.isDir) return true;
        if (props.mode === "directory") return false;
        return fileMatchesFilter(item.name || "");
      })
      .map((item) => ({
        name: item.name,
        path: normalizePath(
          canonicalResourcePath(item.path || item.url),
          item.isDir
        ),
        isDir: item.isDir,
      }))
      .filter(
        (item) =>
          !props.exclude.some(
            (excluded) =>
              normalizePath(excluded, item.isDir) ===
              normalizePath(item.path, item.isDir)
          )
      );
    entries.value = parentPath.value
      ? [
          {
            name: "上一级",
            path: parentPath.value,
            isDir: true,
            isParent: true,
          },
          ...next,
        ]
      : next;
    if (props.mode === "directory") {
      selectedPaths.value = [currentPath.value];
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause);
  } finally {
    loading.value = false;
  }
}

function open(path: string) {
  void load(path);
}

function handleEntryClick(item: (typeof entries.value)[number]) {
  if (item.isParent || !item.isDir || props.interactionMode !== "analysis") {
    if (item.isDir) open(item.path);
    else select(item.path);
    return;
  }
  if (entryClickTimer !== undefined) window.clearTimeout(entryClickTimer);
  entryClickTimer = window.setTimeout(() => {
    select(item.path);
    entryClickTimer = undefined;
  }, 220);
}

function handleEntryDoubleClick(item: (typeof entries.value)[number]) {
  if (!item.isDir || item.isParent) return;
  if (entryClickTimer !== undefined) {
    window.clearTimeout(entryClickTimer);
    entryClickTimer = undefined;
  }
  open(item.path);
}

function handleEntryEnter(item: (typeof entries.value)[number]) {
  if (item.isDir) open(item.path);
  else select(item.path);
}

function toggleEntrySelection(item: (typeof entries.value)[number]) {
  if (!item.isParent) select(item.path);
}

function select(path: string) {
  if (!props.multiple) {
    selectedPaths.value = [path];
    return;
  }
  selectedPaths.value = selectedPaths.value.includes(path)
    ? selectedPaths.value.filter((value) => value !== path)
    : [...selectedPaths.value, path];
}

function selectCurrentDirectory() {
  const path = normalizePath(currentPath.value, true);
  if (!props.multiple) {
    selectedPaths.value = [path];
    return;
  }
  if (!selectedPaths.value.includes(path)) {
    selectedPaths.value = [...selectedPaths.value, path];
  }
}

function confirm() {
  const values =
    selectedPaths.value.length > 0 ? selectedPaths.value : [currentPath.value];
  const value = props.multiple ? values : values[0];
  emit("update:modelValue", value);
  emit("select", value);
  emit("close");
}

function close() {
  emit("close");
}

const focusableSelector =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';
let previouslyFocused: HTMLElement | null = null;
let previousBodyOverflow = "";
let entryClickTimer: number | undefined;

function handleKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    close();
    return;
  }
  if (event.key !== "Tab") return;
  const root = dialog.value;
  if (!root) return;
  const controls = Array.from(
    root.querySelectorAll<HTMLElement>(focusableSelector)
  ).filter((element) => element.getClientRects().length > 0);
  if (controls.length === 0) {
    event.preventDefault();
    root.focus();
    return;
  }
  const first = controls[0];
  const last = controls[controls.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function normalizePath(value: string | undefined, directory = false) {
  const canonical = canonicalResourcePath(value || "/");
  if (canonical === "/") return "/";
  const trimmed = canonical.replace(/\/+$/, "");
  return directory || canonical.endsWith("/") ? `${trimmed}/` : trimmed;
}

onMounted(() => {
  previouslyFocused =
    document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
  previousBodyOverflow = document.body.style.overflow;
  document.body.style.overflow = "hidden";
  document.addEventListener("keydown", handleKeydown);
  void nextTick(() => {
    const closeButton = dialog.value?.querySelector<HTMLElement>(
      'button[aria-label="关闭路径选择器"]'
    );
    (closeButton ?? dialog.value)?.focus();
  });
  void load(currentPath.value);
});

onBeforeUnmount(() => {
  if (entryClickTimer !== undefined) window.clearTimeout(entryClickTimer);
  document.removeEventListener("keydown", handleKeydown);
  document.body.style.overflow = previousBodyOverflow;
  if (previouslyFocused && document.contains(previouslyFocused)) {
    previouslyFocused.focus({ preventScroll: true });
  }
});
</script>

<style scoped>
.path-picker-backdrop {
  position: fixed;
  inset: 0;
  z-index: 10001;
  display: grid;
  place-items: center;
  padding: 18px;
  background: rgb(15 23 42 / 38%);
}
.path-picker {
  display: grid;
  width: min(560px, 100%);
  /* Shrink to content; never leave a tall empty shell */
  height: auto;
  max-height: min(720px, calc(100dvh - 36px));
  min-height: 0;
  /* Analysis hides the generic shortcut row, so the list must own the
   * flexible track directly. The default picker adds the shortcut track via
   * .has-shortcuts below. */
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  overflow: hidden;
  border: 1px solid var(--borderPrimary);
  border-radius: 16px;
  background: var(--surfacePrimary);
  color: var(--textSecondary);
  box-shadow: 0 22px 64px rgb(15 23 42 / 22%);
}
.path-picker.has-shortcuts {
  grid-template-rows: auto auto auto minmax(0, 1fr) auto;
}
.path-picker__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--borderPrimary);
}
.path-picker__header p,
.path-picker__header h2 {
  margin: 0;
}
.path-picker__header p {
  color: var(--blue);
  font-size: 11px;
  font-weight: 700;
}
.path-picker__header h2 {
  margin-top: 3px;
  font-size: 18px;
}
.path-picker__header button {
  display: grid;
  width: 44px;
  min-width: 44px;
  height: 44px;
  min-height: 44px;
  place-items: center;
  border: 0;
  border-radius: 8px;
  color: var(--textPrimary);
  background: transparent;
  cursor: pointer;
}
.path-picker__header button:hover,
.path-picker__header button:focus-visible {
  color: var(--blue);
  background: var(--hover);
}
.path-picker__location {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 42px;
  padding: 0 20px;
  color: var(--blue);
  background: var(--surfaceSecondary);
}
.path-picker__location nav {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: auto;
}
.path-picker__location-separator {
  flex: 0 0 auto;
  color: var(--textSecondary);
  font-size: 12px;
  user-select: none;
}
.path-picker__location button {
  min-height: 32px;
  padding: 0 6px;
  border: 0;
  border-radius: 6px;
  color: var(--textSecondary);
  background: transparent;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  white-space: nowrap;
}
.path-picker__location button:hover,
.path-picker__location button:focus-visible {
  color: var(--blue);
  background: var(--hover);
}
.path-picker__shortcuts {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding: 8px 20px;
  border-bottom: 1px solid var(--borderPrimary);
}
.path-picker__shortcuts button {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  gap: 5px;
  padding: 0 9px;
  border: 1px solid var(--borderPrimary);
  border-radius: 7px;
  color: var(--textPrimary);
  background: var(--surfacePrimary);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
  white-space: nowrap;
}
.path-picker__shortcuts button.selected,
.path-picker__shortcuts button:hover,
.path-picker__shortcuts button:focus-visible {
  border-color: var(--blue);
  color: var(--blue);
}
.path-picker__location code {
  overflow: hidden;
  color: var(--textSecondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.path-picker__list {
  min-height: 0;
  max-height: none;
  margin: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  touch-action: pan-y;
  padding: 8px;
  list-style: none;
}
.path-picker__list li + li {
  margin-top: 2px;
}
.path-picker__entry {
  display: grid;
  width: 100%;
  min-height: 44px;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  color: var(--textSecondary);
  background: transparent;
}
.path-picker__entry.has-enter {
  grid-template-columns: minmax(0, 1fr) auto auto;
}
.path-picker__entry:hover,
.path-picker__entry:focus-within,
.path-picker__entry.selected {
  border-color: color-mix(in srgb, var(--blue) 25%, transparent);
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 8%, transparent);
}
.path-picker__entry-main {
  display: grid;
  width: 100%;
  min-width: 0;
  grid-template-columns: 26px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
}
.path-picker__entry-main span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.path-picker__entry-enter {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 7px;
  color: var(--textPrimary);
  background: transparent;
  cursor: pointer;
}
.path-picker__entry-enter:hover,
.path-picker__entry-enter:focus-visible {
  color: var(--blue);
  background: var(--hover);
}
.path-picker__entry-action {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  cursor: pointer;
}
.path-picker__entry-action input {
  width: 18px;
  height: 18px;
}
.path-picker__empty,
.path-picker__loading,
.path-picker__error {
  display: flex;
  min-height: 88px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--textPrimary);
  font-size: 12px;
}
.path-picker__error {
  min-height: 90px;
  padding: 0 20px;
  color: var(--icon-red);
}
.path-picker__error button {
  min-height: 44px;
  padding: 0 10px;
  border: 1px solid var(--borderPrimary);
  border-radius: 7px;
  color: var(--textSecondary);
  background: var(--surfacePrimary);
  cursor: pointer;
}
.path-picker__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px 20px;
  border-top: 1px solid var(--borderPrimary);
}
.path-picker__footer p {
  margin: 0;
  color: var(--textPrimary);
  font-size: 11px;
}
.path-picker__footer > div {
  display: inline-flex;
  gap: 8px;
}
.path-picker__footer button {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  border: 1px solid var(--borderPrimary);
  border-radius: 8px;
  color: var(--textSecondary);
  background: var(--surfacePrimary);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}
.path-picker__footer button.primary {
  color: var(--iconSecondary);
  border-color: var(--blue);
  background: var(--blue);
}
.path-picker__footer button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
@media (max-width: 560px) {
  .path-picker-backdrop {
    padding: 0;
    place-items: end center;
  }
  .path-picker {
    height: 100dvh;
    max-height: 100dvh;
    min-height: 0;
    border-radius: 16px 16px 0 0;
  }
  .path-picker__footer {
    align-items: stretch;
    flex-direction: column;
  }
  .path-picker__footer > div {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 6px;
  }
  .path-picker__footer button {
    min-width: 0;
    padding-inline: 6px;
    justify-content: center;
    white-space: nowrap;
  }
}
</style>
