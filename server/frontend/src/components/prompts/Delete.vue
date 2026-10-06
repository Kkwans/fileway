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
import { useRoute } from "vue-router";
import { storeToRefs } from "pinia";
import { files as api } from "@/api";
import { useFileStore } from "@/stores/file";
import { useLayoutStore } from "@/stores/layout";
import * as taskApi from "@/api/tasks";
import AppDialog from "@/components/ui/AppDialog.vue";
import AppIcon from "@/components/ui/AppIcon.vue";

const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess")!;
const $showAction = inject<IToastAction>("$showAction")!;
const route = useRoute();

const fileStore = useFileStore();
const layoutStore = useLayoutStore();
const { closeHovers, showHover } = layoutStore;
const { isListing, selectedCount, req, selectedItems } = storeToRefs(fileStore);
const { reload, preselect } = storeToRefs(fileStore);
const submitting = ref(false);

const itemSummary = computed(() => {
  if (isListing.value && selectedCount.value > 1) {
    return `即将处理 ${selectedCount.value} 个已选项目。`;
  }
  const item = !isListing.value
    ? req.value?.name
    : selectedItems.value[0]?.name;
  return item ? `即将处理“${item}”。` : "即将处理当前项目。";
});

function candidates() {
  return isListing.value && selectedCount.value > 0
    ? [...selectedItems.value]
    : !isListing.value && req.value
      ? [req.value]
      : [];
}

function checkRisk(onconfirm: () => void) {
  for (const item of candidates()) {
    const risk = item.riskLevel ?? "low";
    if (risk === "high" || risk === "medium") {
      showHover({
        prompt: "risk-confirm",
        props: {
          riskLevel: risk,
          targetPath: item.path,
          actionType: "delete",
          onconfirm,
        },
      });
      return true;
    }
  }
  return false;
}

const closeDialog = () => {
  if (!submitting.value) closeHovers();
};

const submit = async () => {
  if (submitting.value) return;
  submitting.value = true;
  await executeDelete("trash");
};

const submitPermanent = async () => {
  if (submitting.value) return;
  submitting.value = true;
  if (checkRisk(() => void executeDelete("permanent"))) {
    submitting.value = false;
    return;
  }
  await executeDelete("permanent");
};

const executeDelete = async (mode: "trash" | "permanent") => {
  try {
    const items = candidates();
    if (items.length === 0) {
      closeHovers();
      return;
    }
    if (mode === "permanent") {
      const task = await api.schedulePermanentDeletion(
        items.map((item) => item.path)
      );
      closeHovers();
      $showAction("永久删除将在 3 秒后执行", "撤回", async () => {
        await taskApi.cancel(task.id);
        $showSuccess("已撤回永久删除", { importance: "minor" });
        reload.value = true;
      });
      reload.value = true;
      return;
    }
    if (!isListing.value) {
      await api.remove(route.path, "trash");
      const confirm = layoutStore.currentPrompt?.confirm;
      confirm?.();
      closeHovers();
      $showSuccess("已移入回收站", { importance: "minor" });
      return;
    }

    const deletingItems = items;
    const failures: unknown[] = [];
    for (const item of deletingItems) {
      try {
        await api.remove(item.url, "trash");
      } catch (error) {
        failures.push(error);
      }
    }
    if (failures.length > 0) throw failures[0];

    closeHovers();
    $showSuccess(
      deletingItems.length === 1
        ? "已移入回收站"
        : `${deletingItems.length} 项已移入回收站`,
      { importance: "minor" }
    );

    const firstSelectedIndex = Math.min(
      ...deletingItems.map((item) => item.index)
    );
    const nearbyItem = req.value!.items[Math.max(0, firstSelectedIndex - 1)];
    preselect.value = nearbyItem?.path;
    reload.value = true;
  } catch (error) {
    $showError(error instanceof Error ? error : String(error));
    if (isListing.value) reload.value = true;
  } finally {
    submitting.value = false;
  }
};
</script>
