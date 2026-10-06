<template>
  <AppSelect
    :id="id"
    :model-value="aceEditorTheme || ''"
    :options="selectOptions"
    :disabled="loading && themes.length === 0"
    aria-label="Ace 编辑器主题"
    @update:model-value="onSelect"
  />
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import AppSelect from "@/components/ui/AppSelect.vue";

type ThemeOption = {
  name: string;
  theme: string;
};

const FALLBACK_THEMES: ThemeOption[] = [
  { name: "默认", theme: "" },
  { name: "Chrome", theme: "chrome" },
  { name: "Clouds", theme: "clouds" },
  { name: "Crimson Editor", theme: "crimson_editor" },
  { name: "Dawn", theme: "dawn" },
  { name: "GitHub", theme: "github" },
  { name: "Monokai", theme: "monokai" },
  { name: "Solarized Light", theme: "solarized_light" },
  { name: "Solarized Dark", theme: "solarized_dark" },
  { name: "Terminal", theme: "terminal" },
  { name: "Tomorrow Night", theme: "tomorrow_night" },
  { name: "Twilight", theme: "twilight" },
  { name: "XCode", theme: "xcode" },
];

const themes = ref<ThemeOption[]>([...FALLBACK_THEMES]);
const loading = ref(true);

const props = defineProps<{
  aceEditorTheme: string;
  id?: string;
}>();

const emit = defineEmits<{
  (e: "update:aceEditorTheme", val: string | null): void;
}>();

const selectOptions = computed(() =>
  themes.value.map((t) => ({ label: t.name, value: t.theme }))
);

function onSelect(value: string | number) {
  emit("update:aceEditorTheme", value === "" ? "" : String(value));
}

function normalizeThemeList(raw: unknown): ThemeOption[] {
  const base: ThemeOption[] = [{ name: "默认", theme: "" }];
  if (Array.isArray(raw)) {
    const list = raw
      .map((item) => {
        const o = item as { name?: string; theme?: string; caption?: string };
        const theme = o?.theme || o?.name || "";
        if (!theme) return null;
        return { name: o.caption || o.name || theme, theme };
      })
      .filter(Boolean) as ThemeOption[];
    return [...base, ...list];
  }
  if (raw && typeof raw === "object") {
    const list = Object.entries(raw as Record<string, unknown>)
      .map(([key, val]) => {
        const o = val as { caption?: string; theme?: string };
        const theme = o?.theme || key;
        return { name: o?.caption || key, theme };
      })
      .filter((t) => !!t.theme);
    return [...base, ...list];
  }
  return [...FALLBACK_THEMES];
}

onMounted(async () => {
  try {
    const { default: ace } = await import("ace-builds");
    const aceGlobal = globalThis as typeof globalThis & { ace: typeof ace };
    aceGlobal.ace = ace;
    const themeList = await import("ace-builds/src-noconflict/ext-themelist");
    const list = (themeList as { themes?: unknown }).themes;
    const normalized = normalizeThemeList(list);
    const seen = new Set<string>();
    const unique = normalized.filter((t) => {
      const key = t.theme || "__default__";
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    themes.value = unique.length > 1 ? unique : [...FALLBACK_THEMES];
  } catch {
    themes.value = [...FALLBACK_THEMES];
  } finally {
    loading.value = false;
  }
});

// silence unused props warning if tree-shaken
void props;
</script>
