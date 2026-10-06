<template>
  <div class="card floating risk-confirm-card">
    <div class="card-content">
      <div class="risk-confirm-header">
        <AppIcon
          :name="riskIconName"
          class="risk-confirm-icon"
          :class="'risk-' + riskLevel"
          :size="28"
          :stroke-width="2.1"
        />
        <span class="risk-confirm-title">风险操作确认</span>
      </div>
      <div class="risk-confirm-body">
        <p class="risk-confirm-level">
          <span class="risk-tag" :class="'risk-' + riskLevel">{{
            riskLevelText
          }}</span>
          <span class="risk-path">{{ targetPath }}</span>
        </p>
        <p class="risk-confirm-message">
          您正在对一个受保护的目录执行操作，请确认您了解可能的后果。
        </p>
        <div class="risk-confirm-details">
          <p v-if="actionType === 'delete'">
            删除此目录可能导致系统组件无法正常运行，数据可能无法恢复。
          </p>
          <p v-else-if="actionType === 'rename'">
            重命名此目录可能导致依赖它的系统组件无法正常工作。
          </p>
          <p v-else-if="actionType === 'move'">
            移动此目录可能导致依赖它的系统组件无法正常工作。
          </p>
          <p v-else>对此目录的操作可能影响系统稳定性。</p>
        </div>
      </div>
    </div>
    <div class="card-action">
      <button
        @click="cancel"
        class="button button--flat button--grey"
        aria-label="取消"
        title="取消"
        tabindex="2"
      >
        取消
      </button>
      <button
        id="focus-prompt"
        @click="confirm"
        class="button button--flat button--orange"
        aria-label="确认执行"
        title="确认执行"
        tabindex="1"
      >
        确认执行
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useLayoutStore } from "@/stores/layout";
import { storeToRefs } from "pinia";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
const layoutStore = useLayoutStore();
const { currentPrompt } = storeToRefs(layoutStore);
const { closeHovers } = layoutStore;

const riskLevel = computed(
  () => currentPrompt.value?.props?.riskLevel || "high"
);
const targetPath = computed(() => currentPrompt.value?.props?.targetPath || "");
const actionType = computed(
  () => currentPrompt.value?.props?.actionType || "generic"
);
const riskLevelText = computed(() => {
  if (riskLevel.value === "high") return "高危";
  if (riskLevel.value === "medium") return "中危";
  return "低危";
});
const riskIconName = computed<AppIconName>(() => {
  if (riskLevel.value === "high") return "risk-high";
  if (riskLevel.value === "medium") return "risk-medium";
  return "shield-check";
});

const cancel = () => {
  closeHovers();
  currentPrompt.value?.props?.oncancel?.();
};

const confirm = () => {
  currentPrompt.value?.props?.onconfirm?.();
  closeHovers();
};
</script>
