<template>
  <div
    class="context-menu"
    ref="contextMenu"
    role="menu"
    tabindex="-1"
    v-show="show"
    :style="{
      top: `${position.top}px`,
      left: `${position.left}px`,
    }"
  >
    <slot />
  </div>
</template>

<script setup lang="ts">
import { nextTick, onUnmounted, ref, watch } from "vue";

const emit = defineEmits(["hide"]);
const props = defineProps<{ show: boolean; pos: { x: number; y: number } }>();
const contextMenu = ref<HTMLElement | null>(null);
const position = ref({ top: props.pos.y, left: props.pos.x });
let previouslyFocused: HTMLElement | null = null;

const updatePosition = () => {
  const menu = contextMenu.value;
  if (!menu) return;

  const viewportPadding = 8;
  const menuWidth = menu.offsetWidth;
  const menuHeight = menu.offsetHeight;
  const maxLeft = Math.max(
    viewportPadding,
    window.innerWidth - menuWidth - viewportPadding
  );
  const maxTop = Math.max(
    viewportPadding,
    window.innerHeight - menuHeight - viewportPadding
  );

  // The menu is viewport anchored. Clamp against its measured dimensions so
  // a context click near the bottom/right edge never renders off-screen.
  position.value = {
    left: Math.max(viewportPadding, Math.min(props.pos.x, maxLeft)),
    top: Math.max(viewportPadding, Math.min(props.pos.y, maxTop)),
  };
};

const hideContextMenu = () => {
  emit("hide");
};

const menuItems = () =>
  Array.from(
    contextMenu.value?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [role="menuitem"]:not([aria-disabled="true"])'
    ) ?? []
  ).filter((item) => item.getClientRects().length > 0);

const handleKeydown = (e: KeyboardEvent) => {
  if (e.key === "Escape") {
    e.preventDefault();
    hideContextMenu();
    return;
  }
  if (
    e.key !== "ArrowDown" &&
    e.key !== "ArrowUp" &&
    e.key !== "Home" &&
    e.key !== "End"
  )
    return;
  const items = menuItems();
  if (items.length === 0) return;
  e.preventDefault();
  const current = items.indexOf(document.activeElement as HTMLElement);
  const index =
    e.key === "Home"
      ? 0
      : e.key === "End"
        ? items.length - 1
        : (current + (e.key === "ArrowDown" ? 1 : -1) + items.length) %
          items.length;
  items[index].focus();
};

watch(
  () => [props.show, props.pos.x, props.pos.y],
  ([show]) => {
    if (show) {
      // v-show applies display after the component update. Wait for that
      // update before measuring the menu, otherwise clientHeight is zero and
      // the initial position can still be clipped by the viewport edge.
      void nextTick(updatePosition);
    }
  },
  { immediate: true }
);

watch(
  () => props.show,
  (val) => {
    if (val) {
      previouslyFocused =
        document.activeElement instanceof HTMLElement
          ? document.activeElement
          : null;
      // Use setTimeout to avoid the current click event immediately closing the menu
      setTimeout(() => {
        const items = menuItems();
        (items[0] ?? contextMenu.value)?.focus();
        document.addEventListener("click", hideContextMenu);
        document.addEventListener("keydown", handleKeydown);
        window.addEventListener("resize", updatePosition);
        window.addEventListener("scroll", updatePosition, true);
      }, 0);
    } else {
      document.removeEventListener("click", hideContextMenu);
      document.removeEventListener("keydown", handleKeydown);
      window.removeEventListener("resize", updatePosition);
      window.removeEventListener("scroll", updatePosition, true);
      if (previouslyFocused && document.contains(previouslyFocused)) {
        previouslyFocused.focus({ preventScroll: true });
      }
      previouslyFocused = null;
    }
  }
);

onUnmounted(() => {
  document.removeEventListener("click", hideContextMenu);
  document.removeEventListener("keydown", handleKeydown);
  window.removeEventListener("resize", updatePosition);
  window.removeEventListener("scroll", updatePosition, true);
});
</script>
