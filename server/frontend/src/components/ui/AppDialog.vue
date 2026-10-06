<template>
  <base-modal :prompt="title" :labelled-by="titleId" @closed="emit('closed')">
    <section
      class="app-dialog"
      :class="[`app-dialog--${size}`, `app-dialog--tone-${tone}`]"
      :aria-describedby="description ? descriptionId : undefined"
    >
      <header class="app-dialog__header">
        <div
          class="app-dialog__identity"
          :class="{ 'app-dialog__identity--with-icon': $slots.icon }"
        >
          <span v-if="$slots.icon" class="app-dialog__icon" aria-hidden="true">
            <slot name="icon" />
          </span>
          <div class="app-dialog__heading">
            <h2 :id="titleId">{{ title }}</h2>
            <p v-if="description" :id="descriptionId">{{ description }}</p>
            <slot name="subtitle" />
          </div>
        </div>
        <button
          type="button"
          class="app-dialog__close"
          :aria-label="closeLabel"
          :disabled="closeDisabled"
          @click="emit('closed')"
        >
          <app-icon name="x" :size="20" />
        </button>
      </header>

      <div class="app-dialog__body">
        <slot />
      </div>

      <footer v-if="$slots.footer" class="app-dialog__footer">
        <slot name="footer" />
      </footer>
    </section>
  </base-modal>
</template>

<script setup lang="ts">
import { ref } from "vue";
import BaseModal from "@/components/prompts/BaseModal.vue";
import AppIcon from "@/components/ui/AppIcon.vue";

const props = withDefaults(
  defineProps<{
    title: string;
    description?: string;
    closeLabel?: string;
    closeDisabled?: boolean;
    size?: "small" | "medium" | "large";
    tone?: "neutral" | "danger";
  }>(),
  {
    closeLabel: "关闭对话框",
    closeDisabled: false,
    size: "medium",
    tone: "neutral",
  }
);

const emit = defineEmits<{ closed: [] }>();
let nextDialogId = 0;
const dialogId = ++nextDialogId;
const titleId = ref(`app-dialog-title-${dialogId}`);
const descriptionId = ref(`app-dialog-description-${dialogId}`);
const { title, description, closeLabel, closeDisabled, size, tone } = props;
</script>

<style scoped>
.app-dialog {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  width: min(100%, 560px);
  max-height: min(780px, calc(100dvh - 32px));
  overflow: hidden;
  border: 1px solid var(--borderPrimary);
  border-radius: 16px;
  color: var(--textSecondary);
  background: var(--surfacePrimary);
  box-shadow: 0 24px 70px rgb(15 23 42 / 24%);
}

.app-dialog--small {
  width: min(100%, 440px);
}

.app-dialog--large {
  width: min(100%, 760px);
}

.app-dialog--tone-danger {
  border-color: color-mix(in srgb, var(--icon-red) 24%, var(--borderPrimary));
}

.app-dialog--tone-danger .app-dialog__icon {
  color: var(--icon-red);
  background: color-mix(in srgb, var(--icon-red) 10%, transparent);
}

.app-dialog--tone-danger .app-dialog__heading h2 {
  color: var(--textPrimary);
}

.app-dialog__header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 44px;
  align-items: start;
  gap: 12px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--borderPrimary);
}

.app-dialog__identity {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: start;
  gap: 12px;
  min-width: 0;
}

.app-dialog__identity:not(.app-dialog__identity--with-icon) {
  grid-template-columns: minmax(0, 1fr);
}

.app-dialog__icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 10px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 11%, transparent);
}

.app-dialog__heading {
  min-width: 0;
}

.app-dialog__heading h2,
.app-dialog__heading p {
  margin: 0;
}

.app-dialog__heading h2 {
  font-size: 17px;
  line-height: 1.35;
}

.app-dialog__heading p {
  margin-top: 4px;
  color: var(--textPrimary);
  font-size: 12px;
  line-height: 1.5;
}

.app-dialog__close {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 10px;
  color: var(--textPrimary);
  background: transparent;
  cursor: pointer;
}

.app-dialog__close:hover {
  color: var(--textSecondary);
  background: var(--hover);
}

.app-dialog__close:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.app-dialog__close:disabled {
  cursor: wait;
  opacity: 0.55;
}

.app-dialog__body {
  min-height: 0;
  overflow: auto;
  padding: 20px;
}

.app-dialog__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 20px;
  padding-bottom: max(14px, env(safe-area-inset-bottom));
  border-top: 1px solid var(--borderPrimary);
}

@media (max-width: 520px) {
  .app-dialog {
    align-self: end;
    width: 100%;
    max-height: calc(100dvh - 12px);
    border-radius: 16px 16px 0 0;
  }

  .app-dialog__header,
  .app-dialog__body,
  .app-dialog__footer {
    padding-left: 16px;
    padding-right: 16px;
  }
}
</style>
