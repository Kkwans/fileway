<template>
  <div>
    <div v-if="uploadStore.totalBytes" class="progress">
      <div
        v-bind:style="{
          width: sentPercent + '%',
        }"
      ></div>
    </div>
    <sidebar></sidebar>
    <main class="app-main">
      <router-view></router-view>
      <shell
        v-if="
          enableExec && authStore.isLoggedIn && authStore.user?.perm.execute
        "
      />
    </main>
    <prompts></prompts>
    <upload-files></upload-files>
    <GlobalAudioPlayer />
  </div>
</template>

<script setup lang="ts">
import { useAuthStore } from "@/stores/auth";
import { useLayoutStore } from "@/stores/layout";
import { useFileStore } from "@/stores/file";
import { useUploadStore } from "@/stores/upload";
import { useTasksStore } from "@/stores/tasks";
import { useTransfersStore } from "@/stores/transfers";
import { useHistoryStore } from "@/stores/history";
import Sidebar from "@/components/Sidebar.vue";
import Prompts from "@/components/prompts/Prompts.vue";
import Shell from "@/components/Shell.vue";
import UploadFiles from "@/components/prompts/UploadFiles.vue";
import GlobalAudioPlayer from "@/components/files/GlobalAudioPlayer.vue";
import {
  subscribeSharedTaskCenterEvents,
  type TaskCenterEvent,
} from "@/utils/taskCenterEvents";
import type { TaskItem } from "@/api/tasks";
import type { TransferItem } from "@/api/transfers";
import type { HistoryEntry } from "@/api/history";
import { enableExec } from "@/utils/constants";
import { computed, onBeforeUnmount, watch } from "vue";
import { useRoute } from "vue-router";

const layoutStore = useLayoutStore();
const authStore = useAuthStore();
const fileStore = useFileStore();
const uploadStore = useUploadStore();
const tasksStore = useTasksStore();
const transfersStore = useTransfersStore();
const historyStore = useHistoryStore();
const route = useRoute();

const sentPercent = computed(() =>
  ((uploadStore.sentBytes / uploadStore.totalBytes) * 100).toFixed(2)
);

let stopTaskEvents: (() => void) | undefined;
let streamUserId: number | null = null;

function isTask(value: unknown): value is TaskItem {
  return Boolean(
    value &&
    typeof value === "object" &&
    "id" in value &&
    "status" in value &&
    "type" in value
  );
}

function isTransfer(value: unknown): value is TransferItem {
  return Boolean(
    value &&
    typeof value === "object" &&
    "id" in value &&
    "kind" in value &&
    "status" in value
  );
}

function isHistory(value: unknown): value is HistoryEntry {
  return Boolean(
    value &&
    typeof value === "object" &&
    "id" in value &&
    "action" in value &&
    "createdAt" in value
  );
}

function handleTaskCenterEvent(event: TaskCenterEvent) {
  if (event.type === "task.changed" && isTask(event.data)) {
    tasksStore.record(event.data);
  } else if (event.type === "transfer.changed" && isTransfer(event.data)) {
    transfersStore.record(event.data);
  } else if (event.type === "history.created" && isHistory(event.data)) {
    historyStore.record(event.data);
  }
}

async function reloadTaskCenterSnapshot() {
  await Promise.allSettled([
    tasksStore.loadSummary(),
    transfersStore.load(),
    historyStore.loaded
      ? historyStore.load(historyStore.currentFilter)
      : Promise.resolve(),
  ]);
}

function stopTaskCenterEvents() {
  stopTaskEvents?.();
  stopTaskEvents = undefined;
  streamUserId = null;
  tasksStore.resetForUser();
  transfersStore.resetForUser();
  historyStore.resetForUser();
}

function startTaskCenterEvents() {
  const userId = authStore.user?.id ?? null;
  if (!authStore.isLoggedIn || userId === null) return;
  if (stopTaskEvents && streamUserId === userId) return;
  stopTaskEvents?.();
  tasksStore.resetForUser();
  transfersStore.resetForUser();
  historyStore.resetForUser();
  streamUserId = userId;
  void tasksStore.loadSummary();
  void transfersStore.load();
  stopTaskEvents = subscribeSharedTaskCenterEvents(
    handleTaskCenterEvent,
    () => {
      void reloadTaskCenterSnapshot();
    }
  );
}

watch(
  () => [authStore.isLoggedIn, authStore.user?.id] as const,
  ([loggedIn]) => {
    if (loggedIn) startTaskCenterEvents();
    else stopTaskCenterEvents();
  },
  { immediate: true }
);

watch(route, () => {
  fileStore.clearSelection();
  if (layoutStore.currentPromptName !== "success") {
    layoutStore.closeHovers();
  }
});

onBeforeUnmount(stopTaskCenterEvents);
</script>
