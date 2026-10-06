<template>
  <div id="modal-background" @click="backgroundClick">
    <div
      ref="modalContainer"
      role="dialog"
      aria-modal="true"
      :aria-label="props.labelledBy ? undefined : props.prompt"
      :aria-labelledby="props.labelledBy"
      tabindex="-1"
    >
      <slot></slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";

const props = withDefaults(
  defineProps<{
    prompt?: string;
    labelledBy?: string;
  }>(),
  { prompt: "对话框" }
);

const emit = defineEmits(["closed"]);

const modalContainer = ref<HTMLElement | null>(null);
let previouslyFocused: HTMLElement | null = null;
let previousBodyOverflow = "";

const focusableSelector =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

const focusInitialControl = () => {
  const root = modalContainer.value;
  if (!root) return;
  const element =
    root.querySelector<HTMLElement>("#focus-prompt") ??
    root.querySelector<HTMLElement>(focusableSelector);
  (element ?? root).focus();
};

const handleKeydown = (event: KeyboardEvent) => {
  if (event.key === "Escape") {
    event.preventDefault();
    emit("closed");
    return;
  }
  if (event.key !== "Tab") return;

  const root = modalContainer.value;
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
};

onMounted(() => {
  previouslyFocused =
    document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
  previousBodyOverflow = document.body.style.overflow;
  document.body.style.overflow = "hidden";
  document.addEventListener("keydown", handleKeydown);
  void nextTick(focusInitialControl);
});

const backgroundClick = (event: Event) => {
  const target = event.target as HTMLElement;
  if (target.id == "modal-background") {
    emit("closed");
  }
};

onBeforeUnmount(() => {
  document.removeEventListener("keydown", handleKeydown);
  document.body.style.overflow = previousBodyOverflow;
  if (previouslyFocused && document.contains(previouslyFocused)) {
    previouslyFocused.focus({ preventScroll: true });
  }
});
</script>

<style scoped>
#modal-background {
  position: fixed;
  inset: 0;
  background-color: #00000096;
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 10000;
  animation: ease-in 150ms opacity-enter;
}

@keyframes opacity-enter {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}
</style>
