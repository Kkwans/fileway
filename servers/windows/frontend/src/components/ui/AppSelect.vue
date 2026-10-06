<template>
  <div
    ref="root"
    class="app-select"
    :class="{ 'app-select--open': open, 'app-select--disabled': disabled }"
  >
    <button
      :id="id"
      type="button"
      class="app-select__trigger"
      :disabled="disabled"
      :aria-label="ariaLabel || '打开下拉菜单'"
      :aria-expanded="open"
      :name="name"
      @click="open = !open"
      @keydown.enter.prevent="open = !open"
      @keydown.esc.prevent="open = false"
    >
      <span class="app-select__value">{{ selectedLabel }}</span>
      <AppIcon name="chevron-down" :size="16" class="app-select__caret" />
    </button>
    <ul v-if="open" class="app-select__menu" role="listbox">
      <li
        v-for="opt in options"
        :key="String(opt.value)"
        role="option"
        :aria-selected="String(opt.value) === String(modelValue)"
        class="app-select__option"
        :class="{ active: String(opt.value) === String(modelValue) }"
        @click="pick(opt.value)"
      >
        {{ opt.label }}
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";

const props = withDefaults(
  defineProps<{
    modelValue: string | number | null | undefined;
    options: Array<{ label: string; value: string | number }>;
    disabled?: boolean;
    ariaLabel?: string;
    id?: string;
    name?: string;
  }>(),
  {
    disabled: false,
    ariaLabel: "",
    id: undefined,
    name: undefined,
  }
);

const emit = defineEmits<{
  "update:modelValue": [value: string | number];
}>();

const open = ref(false);
const root = ref<HTMLElement | null>(null);

const selectedLabel = computed(() => {
  const hit = props.options.find(
    (o) => String(o.value) === String(props.modelValue ?? "")
  );
  return hit?.label ?? "请选择";
});

function pick(value: string | number) {
  emit("update:modelValue", value);
  open.value = false;
}

function onDocClick(event: MouseEvent) {
  if (!root.value) return;
  if (!root.value.contains(event.target as Node)) open.value = false;
}

onMounted(() => document.addEventListener("mousedown", onDocClick));
onBeforeUnmount(() => document.removeEventListener("mousedown", onDocClick));
</script>

<style scoped>
.app-select {
  position: relative;
  width: 100%;
  min-width: 0;
}

.app-select__trigger {
  display: flex;
  width: 100%;
  height: 36px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 10px 0 12px;
  border: 1px solid var(--borderPrimary, #d0d5dd);
  border-radius: 8px;
  color: var(--textPrimary, #1f2937);
  background: var(--surfacePrimary, #fff);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.app-select__trigger:hover {
  border-color: color-mix(in srgb, var(--blue, #2979ff) 45%, transparent);
}

.app-select--open .app-select__trigger,
.app-select__trigger:focus-visible {
  border-color: var(--blue, #2979ff);
  box-shadow: 0 0 0 3px rgba(41, 121, 255, 0.12);
  outline: none;
}

.app-select__value {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
}

.app-select__caret {
  flex-shrink: 0;
  color: var(--textSecondary, #667085);
  transition: transform 0.15s ease;
}

.app-select--open .app-select__caret {
  transform: rotate(180deg);
}

.app-select--disabled .app-select__trigger {
  opacity: 0.55;
  cursor: not-allowed;
}

.app-select__menu {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  z-index: 40;
  margin: 0;
  padding: 4px;
  list-style: none;
  max-height: 220px;
  overflow-y: auto;
  border: 1px solid var(--borderPrimary, #e5e7eb);
  border-radius: 10px;
  background: var(--surfacePrimary, #fff);
  box-shadow:
    0 12px 32px rgba(15, 23, 42, 0.12),
    0 2px 8px rgba(15, 23, 42, 0.06);
}

.app-select__option {
  padding: 8px 10px;
  border-radius: 7px;
  color: var(--textPrimary, #1f2937);
  font-size: 13px;
  cursor: pointer;
}

.app-select__option:hover,
.app-select__option.active {
  color: var(--blue, #2979ff);
  background: color-mix(in srgb, var(--blue, #2979ff) 10%, transparent);
}

:root.dark .app-select__menu,
:root.dark .app-select__trigger {
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(255, 255, 255, 0.1);
  color: var(--textSecondary, #cbd5e1);
}
</style>
