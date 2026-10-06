<template>
  <span ref="host" class="file-action-menu-host" @click.stop>
    <button
      ref="trigger"
      :class="[triggerClass, 'file-action-menu-trigger']"
      type="button"
      :aria-label="`${name}的更多操作`"
      title="更多操作"
      aria-haspopup="menu"
      :aria-expanded="open"
      :aria-controls="menuId"
      @click.stop="toggleMenu"
      @keydown.down.prevent.stop="openMenu(false)"
      @keydown.up.prevent.stop="openMenu(true)"
    >
      <AppIcon name="ellipsis" :size="20" :stroke-width="2" />
    </button>
  </span>

  <Teleport to="body">
    <div
      v-if="open"
      :id="menuId"
      ref="menu"
      class="file-action-popover"
      :style="menuStyle"
      role="menu"
      :aria-label="`${name}的文件操作`"
      @click.stop
      @keydown="handleMenuKeydown"
    >
      <button
        v-for="action in actions"
        :key="action.id"
        type="button"
        role="menuitem"
        :class="{ danger: action.id === 'delete' }"
        :data-file-action="action.id"
        @click="selectAction(action.id)"
      >
        <AppIcon :name="action.icon" :size="18" :stroke-width="1.9" />
        <span>{{ action.label }}</span>
      </button>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  useId,
  type CSSProperties,
} from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import {
  getFileActionMenuPosition,
  type FileActionMenuAction,
} from "@/utils/fileActionMenu";

type MenuAction = {
  id: FileActionMenuAction;
  label: string;
  icon: AppIconName;
};

const props = withDefaults(
  defineProps<{
    name: string;
    canRename?: boolean;
    canDownload?: boolean;
    canDelete?: boolean;
    triggerClass?: string;
  }>(),
  {
    canRename: false,
    canDownload: false,
    canDelete: false,
    triggerClass: "detail-action-button",
  }
);

const emit = defineEmits<{
  select: [action: FileActionMenuAction];
}>();

const host = ref<HTMLElement | null>(null);
const trigger = ref<HTMLButtonElement | null>(null);
const menu = ref<HTMLElement | null>(null);
const open = ref(false);
const menuStyle = ref<CSSProperties>({ visibility: "hidden" });
const menuId = `file-action-menu-${useId()}`;

const actions = computed<MenuAction[]>(() => {
  const items: MenuAction[] = [{ id: "info", label: "详细信息", icon: "info" }];
  if (props.canRename) {
    items.push(
      { id: "rename", label: "重命名", icon: "rename" },
      { id: "move", label: "移动", icon: "move" }
    );
  }
  if (props.canDownload) {
    items.push({ id: "download", label: "下载", icon: "download" });
  }
  if (props.canDelete) {
    items.push({ id: "delete", label: "删除", icon: "trash" });
  }
  return items;
});

const focusMenuItem = (last = false) => {
  const items =
    menu.value?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]');
  if (!items?.length) return;
  items[last ? items.length - 1 : 0].focus({ preventScroll: true });
};

const positionMenu = () => {
  if (!trigger.value || !menu.value) return;
  const position = getFileActionMenuPosition({
    trigger: trigger.value.getBoundingClientRect(),
    menuWidth: menu.value.offsetWidth,
    menuHeight: menu.value.offsetHeight,
    viewportWidth: window.innerWidth,
    viewportHeight: window.innerHeight,
  });
  menuStyle.value = {
    left: `${position.left}px`,
    top: `${position.top}px`,
    visibility: "visible",
  };
};

const openMenu = async (focusLast = false) => {
  if (open.value) {
    focusMenuItem(focusLast);
    return;
  }
  menuStyle.value = { visibility: "hidden" };
  open.value = true;
  await nextTick();
  positionMenu();
  await nextTick();
  focusMenuItem(focusLast);
};

const closeMenu = (restoreFocus = false) => {
  if (!open.value) return;
  open.value = false;
  if (restoreFocus)
    void nextTick(() => trigger.value?.focus({ preventScroll: true }));
};

const toggleMenu = () => {
  if (open.value) closeMenu(true);
  else void openMenu();
};

const selectAction = (action: FileActionMenuAction) => {
  closeMenu();
  emit("select", action);
};

const handleMenuKeydown = (event: KeyboardEvent) => {
  if (event.key === "Escape") {
    event.preventDefault();
    event.stopPropagation();
    closeMenu(true);
    return;
  }
  if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
  const items = Array.from(
    menu.value?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? []
  );
  if (!items.length) return;
  event.preventDefault();
  const current = items.indexOf(document.activeElement as HTMLButtonElement);
  let next = current;
  if (event.key === "Home") next = 0;
  else if (event.key === "End") next = items.length - 1;
  else if (event.key === "ArrowDown") next = (current + 1) % items.length;
  else next = (current - 1 + items.length) % items.length;
  items[next].focus();
};

const handleOutsidePointer = (event: PointerEvent) => {
  if (!open.value) return;
  const target = event.target as Node | null;
  if (host.value?.contains(target) || menu.value?.contains(target)) return;
  closeMenu();
};

const handleViewportChange = () => closeMenu();

onMounted(() => {
  document.addEventListener("pointerdown", handleOutsidePointer, true);
  document.addEventListener("scroll", handleViewportChange, true);
  window.addEventListener("resize", handleViewportChange);
});

onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", handleOutsidePointer, true);
  document.removeEventListener("scroll", handleViewportChange, true);
  window.removeEventListener("resize", handleViewportChange);
});
</script>
