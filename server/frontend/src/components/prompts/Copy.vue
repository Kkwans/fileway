<template>
  <PathPicker title="选择复制目标目录" @select="copyTo" @close="closeHovers" />
</template>

<script setup lang="ts">
import { inject } from "vue";
import { useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import { useAuthStore } from "@/stores/auth";
import PathPicker from "./PathPicker.vue";
import type { ConflictResult, MoveCopyItem } from "@/types/file";
import { files as api } from "@/api";
import * as upload from "@/utils/upload";
import {
  appendResourceRouteSegment,
  canonicalResourcePath,
  encodeResourceRoute,
} from "@/utils/url";
import buttons from "@/utils/buttons";

const $showError = inject<IToastError>("$showError")!;
const router = useRouter();
const fileStore = useFileStore();
const sourceScope = fileStore.scope;
const layoutStore = useLayoutStore();
const authStore = useAuthStore();
const { selectedItems, reload } = storeToRefs(fileStore);
const { user } = storeToRefs(authStore);
const { showHover, closeHovers } = layoutStore;

function firstPath(value: string | string[]) {
  return Array.isArray(value) ? value[0] : value;
}

function buildItems(destination: string): MoveCopyItem[] {
  return selectedItems.value.map((item) => ({
    from: item.url,
    to: appendResourceRouteSegment(destination, item.name),
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
  buttons.loading("copy");
  try {
    await api.copy(items, false, false);
    if (fileStore.scope !== sourceScope) return;
    buttons.success("copy");
    fileStore.setPreselect(
      {
        path: canonicalResourcePath(items[0].to),
        wirePath: items[0].to.slice("/files".length),
      },
      sourceScope
    );
    reload.value = true;
    if (user.value?.redirectAfterCopyMove) {
      await router.push({ path: encodeResourceRoute(destination) });
    }
  } catch (error) {
    buttons.done("copy");
    $showError(error as Error);
  }
}

async function copyTo(value: string | string[]) {
  if (fileStore.scope !== sourceScope) return;
  const destination = firstPath(value);
  if (!destination) return;
  const items = buildItems(destination);
  if (items.length === 0) return;

  const conflict = await upload.checkConflict(
    items,
    encodeResourceRoute(destination)
  );
  if (fileStore.scope !== sourceScope) return;
  if (conflict.length > 0) {
    showHover({
      prompt: "resolve-conflict",
      props: { conflict },
      confirm: (event: Event, result: ConflictResult[]) => {
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
}
</script>
