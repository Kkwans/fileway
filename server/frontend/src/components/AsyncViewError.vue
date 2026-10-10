<template>
  <header-bar showMenu showLogo show-task-center />
  <section class="async-view-error" role="alert">
    <h2>无法打开文件预览</h2>
    <p>{{ message }}</p>
    <button class="button" type="button" @click="reloadAfterConfirmation">
      刷新页面
    </button>
  </section>
</template>

<script setup lang="ts">
import { computed } from "vue";
import HeaderBar from "@/components/header/HeaderBar.vue";
import {
  assetLoadMessage,
  isAssetLoadError,
  reloadAfterConfirmation,
} from "@/utils/assetLoadRecovery";

const props = defineProps<{ error?: unknown }>();
const message = computed(() =>
  isAssetLoadError(props.error)
    ? assetLoadMessage
    : "预览组件加载失败。请先保存未完成的内容，再刷新重试；若仍失败，请联系管理员检查。"
);
</script>

<style scoped>
.async-view-error {
  padding: 24px;
  color: var(--textPrimary);
}

.async-view-error h2 {
  margin: 0 0 12px;
  font-size: 1.125rem;
}

.async-view-error p {
  max-width: 65ch;
  margin: 0 0 20px;
  line-height: 1.6;
}

.async-view-error .button {
  min-height: 44px;
}
</style>
