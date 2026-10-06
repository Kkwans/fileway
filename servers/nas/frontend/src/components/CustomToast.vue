<template>
  <div class="t-container">
    <span>{{ message }}</span>
    <button
      v-if="actionLabel && !actionHandled"
      class="action action--primary"
      :disabled="actionPending"
      @click.stop="runAction"
    >
      {{ actionPending ? "处理中…" : actionLabel }}
    </button>
    <button v-else-if="isReport" class="action" @click.stop="openReport">
      {{ reportText }}
    </button>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";

const props = defineProps<{
  message: string;
  reportText?: string;
  isReport?: boolean;
  actionLabel?: string;
  onAction?: () => void | Promise<void>;
}>();

const actionPending = ref(false);
const actionHandled = ref(false);

const runAction = async () => {
  if (!props.onAction || actionPending.value) return;
  actionPending.value = true;
  try {
    await props.onAction();
    actionHandled.value = true;
  } catch {
    actionHandled.value = false;
  } finally {
    actionPending.value = false;
  }
};

const openReport = () => {
  window.open(
    "https://github.com/filebrowser/filebrowser/issues/new/choose",
    "_blank",
    "noopener,noreferrer"
  );
};
</script>

<style scoped>
.t-container {
  width: 100%;
  padding: 5px 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.action {
  flex: 0 0 auto;
  text-align: center;
  height: 40px;
  padding: 0 10px;
  margin-left: 20px;
  border-radius: 5px;
  color: white;
  cursor: pointer;
  border: thin solid currentColor;
  white-space: nowrap;
}

.action--primary {
  border-color: rgba(255, 255, 255, 0.72);
  font-weight: 700;
}

.action:focus-visible {
  outline: 2px solid white;
  outline-offset: 2px;
}

html[dir="rtl"] .action {
  margin-left: initial;
  margin-right: 20px;
}
</style>
