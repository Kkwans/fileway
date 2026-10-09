<template>
  <AppDialog
    title="删除项目？"
    description="移入回收站可恢复；永久删除将在 3 秒后执行。"
    tone="danger"
    size="small"
    :close-disabled="submitting"
    @closed="closeDialog"
  >
    <template #icon>
      <AppIcon name="trash" :size="22" />
    </template>

    <p class="delete-dialog-copy">
      {{ itemSummary }}
    </p>
    <p class="delete-dialog-note">
      永久删除会清除原文件，执行前可在底部提示中撤回。
    </p>

    <template #footer>
      <div class="delete-dialog-actions">
        <button type="button" :disabled="submitting" @click="closeDialog">
          取消
        </button>
        <button
          id="focus-prompt"
          type="button"
          class="danger delete-dialog-actions__permanent"
          :disabled="submitting"
          aria-label="永久删除"
          title="永久删除（3 秒内可撤回）"
          @click="submitPermanent"
        >
          {{ submitting ? "提交中…" : "永久删除" }}
        </button>
        <button
          type="button"
          class="delete-dialog-actions__trash"
          :disabled="submitting"
          aria-label="移入回收站"
          title="移入回收站"
          @click="submit"
        >
          移入回收站
        </button>
      </div>
    </template>
  </AppDialog>
</template>

<script setup lang="ts">
import { computed, inject, ref } from "vue";
import { files as api } from "@/api";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import * as taskApi from "@/api/tasks";
import { operationResourceSnapshot } from "@/utils/resourceOperationWire";
import { fileResourceIdentity } from "@/utils/fileListing";
import AppDialog from "@/components/ui/AppDialog.vue";
import AppIcon from "@/components/ui/AppIcon.vue";

const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess")!;
const $showAction = inject<IToastAction>("$showAction")!;
const fileStore = useFileStore();
const layoutStore = useLayoutStore();
const { closeHovers, showHover } = layoutStore;
const sourceScope = fileStore.scope;
const listingAtOpen = fileStore.isListing;
const sourceResource = fileStore.req;
const resourceAtOpen = sourceResource && fileResourceIdentity(sourceResource);
const sourcePrompt = layoutStore.currentPrompt;
const confirmed = sourcePrompt?.confirm;
const snapshot = operationResourceSnapshot(
  sourceScope,
  listingAtOpen
    ? fileStore.selectedItems
    : sourceResource
      ? [sourceResource]
      : []
);
const firstIndex = Math.min(...snapshot.rows.map((item) => item.index));
const originalNearby = sourceResource?.items?.[Math.max(0, firstIndex - 1)];
const nearbyItem = originalNearby ? { ...originalNearby } : undefined;
const current = () => fileStore.scope === snapshot.scope;
const submitting = ref(false);
let executing = false;

const itemSummary = computed(() => {
  if (listingAtOpen && snapshot.rows.length > 1)
    return `即将处理 ${snapshot.rows.length} 个已选项目。`;
  const name = snapshot.rows[0]?.name;
  return name ? `即将处理“${name}”。` : "即将处理当前项目。";
});

function closeOwnedDialog() {
  if (!current()) return;
  if (layoutStore.currentPrompt === sourcePrompt) closeHovers();
  else if (sourcePrompt)
    layoutStore.prompts = layoutStore.prompts.filter(
      (prompt) => prompt !== sourcePrompt
    );
}
function checkRisk(onconfirm: () => void) {
  if (!current()) return false;
  for (const item of snapshot.rows) {
    const risk = item.riskLevel ?? "low";
    if (risk === "high" || risk === "medium") {
      showHover({
        prompt: "risk-confirm",
        props: {
          riskLevel: risk,
          targetPath: item.path,
          actionType: "delete",
          onconfirm: () => {
            if (current()) onconfirm();
          },
        },
      });
      return true;
    }
  }
  return false;
}
const closeDialog = () => {
  if (!submitting.value) closeOwnedDialog();
};
const submit = async () => {
  if (!current() || submitting.value) return;
  await executeDelete("trash");
};
const submitPermanent = async () => {
  if (!current() || submitting.value) return;
  if (checkRisk(() => void executeDelete("permanent"))) return;
  await executeDelete("permanent");
};
const executeDelete = async (mode: "trash" | "permanent") => {
  if (!current() || executing) return;
  executing = true;
  submitting.value = true;
  try {
    if (!snapshot.rows.length) {
      closeOwnedDialog();
      return;
    }
    if (mode === "permanent") {
      const task = await api.schedulePermanentDeletion([...snapshot.rows]);
      if (!current()) return;
      closeOwnedDialog();
      $showAction("永久删除将在 3 秒后执行", "撤回", async () => {
        if (!current()) return;
        try {
          await taskApi.cancel(task.id);
        } catch (error) {
          if (current()) throw error;
          return;
        }
        if (!current()) return;
        $showSuccess("已撤回永久删除", { importance: "minor" });
        fileStore.reload = true;
      });
      fileStore.reload = true;
      return;
    }
    const failures: unknown[] = [];
    for (const item of snapshot.rows) {
      if (!current()) return;
      try {
        await api.remove(item, "trash");
      } catch (error) {
        failures.push(error);
      }
      if (!current()) return;
    }
    if (failures.length) throw failures[0];
    if (!listingAtOpen) {
      if (
        fileStore.req &&
        fileResourceIdentity(fileStore.req) === resourceAtOpen
      )
        confirmed?.();
      closeOwnedDialog();
      $showSuccess("已移入回收站", { importance: "minor" });
      return;
    }
    closeOwnedDialog();
    $showSuccess(
      snapshot.rows.length === 1
        ? "已移入回收站"
        : `${snapshot.rows.length} 项已移入回收站`,
      { importance: "minor" }
    );
    fileStore.setPreselect(nearbyItem, sourceScope);
    fileStore.reload = true;
  } catch (error) {
    if (!current()) return;
    $showError(error instanceof Error ? error : String(error));
    if (listingAtOpen) fileStore.reload = true;
  } finally {
    executing = false;
    submitting.value = false;
  }
};
</script>
