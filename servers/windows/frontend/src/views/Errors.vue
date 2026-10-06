<template>
  <div>
    <header-bar v-if="showHeader" showMenu showLogo />

    <section class="message error-state" role="alert" aria-live="assertive">
      <AppIcon :name="info.icon" :size="44" />
      <h2>{{ info.message }}</h2>
      <p>{{ info.detail }}</p>
      <button
        v-if="showRetry"
        type="button"
        class="button"
        @click="emit('retry')"
      >
        重试
      </button>
    </section>
  </div>
</template>

<script setup lang="ts">
import HeaderBar from "@/components/header/HeaderBar.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import { computed } from "vue";
const errors: {
  [key: number]: {
    icon: AppIconName;
    message: string;
    detail: string;
  };
} = {
  0: {
    icon: "cloud-off",
    message: "无法连接到服务器",
    detail: "请检查网络连接或服务状态，然后重试。",
  },
  403: {
    icon: "circle-alert",
    message: "没有权限访问此路径",
    detail: "当前账号无权读取该文件或目录。",
  },
  404: {
    icon: "locate-fixed",
    message: "路径不存在",
    detail: "文件或目录可能已被移动、重命名或删除。",
  },
  500: {
    icon: "circle-alert",
    message: "读取文件失败",
    detail: "服务器未能完成请求，请稍后重试。",
  },
};

const props = withDefaults(
  defineProps<{
    errorCode?: number;
    showHeader?: boolean;
    showRetry?: boolean;
  }>(),
  {
    errorCode: 500,
    showHeader: false,
    showRetry: false,
  }
);

const emit = defineEmits<{ retry: [] }>();

const info = computed(() => {
  return errors[props.errorCode] ? errors[props.errorCode] : errors[500];
});
</script>

<style scoped>
.error-state {
  display: grid;
  justify-items: center;
  gap: 0.5rem;
  max-width: 32rem;
  margin: 4rem auto;
  padding: 1.5rem;
  text-align: center;
}

.error-state h2,
.error-state p {
  margin: 0;
}

.error-state p {
  color: var(--textSecondary, #64748b);
  font-size: 0.95rem;
}

.error-state .button {
  margin-top: 0.5rem;
}
</style>
