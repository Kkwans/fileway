<template>
  <PathPicker
    title="选择移动目标目录"
    :exclude="excludedFolders"
    wire-paths
    @select-resource="moveTo"
    @close="closeHovers"
  />
</template>

<script setup lang="ts">
import { computed, inject } from "vue";
import { useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import { useAuthStore } from "@/stores/auth";
import PathPicker from "./PathPicker.vue";
import type { ConflictResult, MoveCopyItem } from "@/types/file";
import { files as api } from "@/api";
import * as upload from "@/utils/upload";
import buttons from "@/utils/buttons";

import {
  operationAcknowledgedTarget,
  operationDestinationRoute,
  operationWireTarget,
  operationResourceSnapshot,
} from "@/utils/resourceOperationWire";
import type { ListingResourceRef } from "@/utils/fileListing";

const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess");
const router = useRouter();
const fileStore = useFileStore();
const sourceScope = fileStore.scope;
const snapshot = operationResourceSnapshot(
  sourceScope,
  fileStore.selectedItems
);
const layoutStore = useLayoutStore();
const authStore = useAuthStore();
const { reload } = storeToRefs(fileStore);
const { user } = storeToRefs(authStore);
const { showHover, closeHovers } = layoutStore;

const excludedFolders = computed(() =>
  snapshot.rows
    .filter((item) => item.isDir)
    .map((item) => operationWireTarget(item).wirePath)
);

function firstPath(value: ListingResourceRef | ListingResourceRef[]) {
  return Array.isArray(value) ? value[0] : value;
}

function buildItems(destination: string): MoveCopyItem[] {
  return snapshot.rows.map((item) => ({
    from: item.url,
    to: operationDestinationRoute(destination, item),
    name: item.name,
    size: item.size,
    modified: item.modified,
    isDir: item.isDir,
    overwrite: false,
    rename: false,
  }));
}

async function submit(items: MoveCopyItem[], destination: string) {
  if (fileStore.scope !== sourceScope) return;
  buttons.loading("move");
  try {
    const responses = await api.move(items, false, false);
    if (fileStore.scope !== sourceScope) return;
    if (responses.some((response) => response.status === 202)) {
      buttons.done("move");
      $showSuccess?.("移动任务已提交，请在任务中心查看", {
        importance: "minor",
      });
    } else buttons.success("move");
    fileStore.clearSelection();
    fileStore.setPreselect(
      operationAcknowledgedTarget(responses[0], [items[0].from, items[0].to]),
      sourceScope
    );
    reload.value = true;
    if (user.value?.redirectAfterCopyMove) {
      await router.push({ path: destination });
    }
  } catch (error) {
    if (fileStore.scope !== sourceScope) return;
    buttons.done("move");
    $showError(error as Error);
  }
}

async function moveTo(value: ListingResourceRef | ListingResourceRef[]) {
  try {
    if (fileStore.scope !== sourceScope) return;
    const resource = firstPath(value);
    if (!resource) return;
    const destination = operationWireTarget(resource).legacyRoute;
    if (
      fileStore.req &&
      operationWireTarget(fileStore.req).identity ===
        operationWireTarget(resource).identity
    ) {
      $showError(new Error("目标目录与当前目录相同"), false);
      return;
    }
    const items = buildItems(destination);
    if (items.length === 0) return;

    const risky = snapshot.rows.find((item) => {
      const risk = item.riskLevel ?? "low";
      return risk === "high" || risk === "medium";
    });
    if (risky) {
      showHover({
        prompt: "risk-confirm",
        props: {
          riskLevel: risky.riskLevel ?? "high",
          targetPath: risky.path,
          actionType: "move",
          onconfirm: () => void resolveMove(items, destination),
        },
      });
      return;
    }
    await resolveMove(items, destination);
  } catch (error) {
    if (fileStore.scope === sourceScope) $showError(error as Error);
  }
}

async function resolveMove(items: MoveCopyItem[], destination: string) {
  try {
    if (fileStore.scope !== sourceScope) return;
    const conflict = await upload.checkConflict(items, destination);
    if (fileStore.scope !== sourceScope) return;
    if (conflict.length > 0) {
      showHover({
        prompt: "resolve-conflict",
        props: { conflict, files: items },
        confirm: (event: Event, result: ConflictResult[]) => {
          if (fileStore.scope !== sourceScope) return;
          event.preventDefault();
          closeHovers();
          for (let index = result.length - 1; index >= 0; index--) {
            const item = result[index];
            if (item.checked.length === 2) items[item.index].rename = true;
            else if (item.checked.length === 1 && item.checked[0] === "origin")
              items[item.index].overwrite = true;
            else items.splice(item.index, 1);
          }
          if (items.length > 0) void submit(items, destination);
        },
      });
      return;
    }
    await submit(items, destination);
  } catch (error) {
    if (fileStore.scope === sourceScope) $showError(error as Error);
  }
}
</script>
