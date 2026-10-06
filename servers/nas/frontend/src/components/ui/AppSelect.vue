<template>
  <div
    ref="root"
    class="app-select"
    :class="{ 'app-select--open': open, 'app-select--disabled': disabled }"
    @focusout="onFocusOut"
  >
    <button
      :id="id"
      ref="trigger"
      role="combobox"
      aria-haspopup="listbox"
      :aria-controls="listId"
      :aria-activedescendant="open ? `${listId}-${activeIndex}` : undefined"
      type="button"
      class="app-select__trigger"
      :disabled="disabled"
      :aria-label="ariaLabel || '打开下拉菜单'"
      :aria-expanded="open"
      :name="name"
      @click="toggle"
      @keydown="onKeydown"
    >
      <span class="app-select__value">{{ selectedLabel }}</span>
      <AppIcon name="chevron-down" :size="16" class="app-select__caret" />
    </button>
    <ul
      v-if="open"
      :id="listId"
      :aria-label="ariaLabel || '选项'"
      class="app-select__menu"
      role="listbox"
    >
      <li
        v-for="(opt, index) in options"
        :id="`${listId}-${index}`"
        :key="String(opt.value)"
        role="option"
        :aria-selected="String(opt.value) === String(modelValue)"
        class="app-select__option"
        :class="{ active: index === activeIndex }"
        @mousedown.prevent
        @click="pick(opt.value)"
      >
        {{ opt.label }}
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  onMounted,
  ref,
  useId,
  watch,
  nextTick,
} from "vue";
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
const trigger = ref<HTMLButtonElement | null>(null);
const listId = `select-${useId()}`;
const activeIndex = ref(0);
watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) open.value = false;
  }
);
function toggle() {
  if (props.disabled || !props.options.length) return;
  activeIndex.value = Math.max(
    0,
    props.options.findIndex((o) => String(o.value) === String(props.modelValue))
  );
  open.value = !open.value;
}
function onFocusOut(event: FocusEvent) {
  if (!root.value?.contains(event.relatedTarget as Node)) open.value = false;
}
function onKeydown(event: KeyboardEvent) {
  if (props.disabled) return;
  if (event.key === "Tab") {
    open.value = false;
    return;
  }
  if (
    !["ArrowDown", "ArrowUp", "Home", "End", "Enter", " ", "Escape"].includes(
      event.key
    )
  )
    return;
  event.preventDefault();
  event.stopPropagation();
  if (event.key === "Escape") {
    open.value = false;
    return;
  }
  if (!open.value) {
    toggle();
    return;
  }
  if (event.key === "Enter" || event.key === " ") {
    const option = props.options[activeIndex.value];
    if (option) pick(option.value);
    return;
  }
  const last = props.options.length - 1;
  activeIndex.value =
    event.key === "Home"
      ? 0
      : event.key === "End"
        ? last
        : Math.max(
            0,
            Math.min(
              last,
              activeIndex.value + (event.key === "ArrowDown" ? 1 : -1)
            )
          );
  void nextTick(() =>
    document
      .getElementById(`${listId}-${activeIndex.value}`)
      ?.scrollIntoView({ block: "nearest" })
  );
}
const root = ref<HTMLElement | null>(null);

const selectedLabel = computed(() => {
  const hit = props.options.find(
    (o) => String(o.value) === String(props.modelValue ?? "")
  );
  return hit?.label ?? "请选择";
});

function pick(value: string | number) {
  if (props.disabled) return;
  emit("update:modelValue", value);
  open.value = false;
  trigger.value?.focus();
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

@media (pointer: coarse) {
  .app-select__trigger,
  .app-select__option {
    min-height: 44px;
  }
}
</style>
