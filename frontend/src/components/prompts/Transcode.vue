<template>
  <PathPicker
    v-if="picking"
    title="选择转码输出目录"
    :model-value="destination"
    @select="selectDestination"
    @close="picking = false"
  />
  <div v-else class="card floating transcode-dialog">
    <div class="card-title">
      <h2>后台转码</h2>
      <p>{{ pendingPaths.length }} 项选择 · 目录会递归查找视频</p>
    </div>
    <form @submit.prevent="submit">
      <div class="card-content">
        <label for="transcode-quality">画质 / 分辨率</label>
        <select
          id="transcode-quality"
          v-model="quality"
          class="input"
          :disabled="submitting"
        >
          <option value="source">原画 · 原始最高分辨率</option>
          <option value="4k">4K · 最高 3840 × 2160</option>
          <option value="2k">2K · 最高 2560 × 1440</option>
          <option value="1080p">1080p</option>
          <option value="720p">720p</option>
          <option value="480p">480p</option>
        </select>
        <label>输出目录</label>
        <button
          class="transcode-destination"
          :aria-label="`选择转码输出目录，当前 ${destination}`"
          type="button"
          :disabled="submitting"
          @click="picking = true"
        >
          <AppIcon name="folder" :size="20" /><span>{{ destination }}</span
          ><AppIcon name="chevron-right" :size="18" />
        </button>
        <p class="transcode-explanation">
          输出新 MP4（H.264 /
          AAC），使用默认音轨。原文件保留；内挂字幕仍可在原文件中使用。同名成品自动添加序号，低分辨率视频不会被放大。
        </p>
        <p class="transcode-explanation">
          首段生成后可在任务中心边转边播，也可随时取消或重试。关闭此页面不会停止后台任务。
        </p>
        <p v-if="error" role="alert" class="transcode-error">{{ error }}</p>
      </div>
      <div class="card-action">
        <button
          type="button"
          class="button button--flat"
          :disabled="submitting"
          @click="emit('close')"
        >
          取消
        </button>
        <button
          type="submit"
          class="button"
          :disabled="submitting || !destination"
        >
          {{ submitting ? "正在提交…" : "开始后台转码" }}
        </button>
      </div>
    </form>
  </div>
</template>
<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { media } from "@/api";
import { useTasksStore } from "@/stores/tasks";
import { canonicalResourcePath } from "@/utils/url";
import PathPicker from "./PathPicker.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
const props = defineProps<{ paths: string[]; initialDestination: string }>();
const emit = defineEmits<{ close: [] }>();
const router = useRouter();
const tasks = useTasksStore();
const quality = ref("source");
const pendingPaths = ref([...props.paths]);
const destination = ref(canonicalResourcePath(props.initialDestination));
const picking = ref(false);
const submitting = ref(false);
const error = ref("");
function selectDestination(value: string | string[]) {
  destination.value = canonicalResourcePath(
    Array.isArray(value) ? value[0] : value
  );
  picking.value = false;
}
async function submit() {
  if (submitting.value) return;
  submitting.value = true;
  error.value = "";
  try {
    const result = await media.startTranscodes(
      pendingPaths.value.map(canonicalResourcePath),
      quality.value,
      destination.value
    );
    if (!result.items.length)
      throw new Error(result.failures[0]?.error || "没有可提交的视频");
    await tasks.load({ category: "background" });
    if (result.failures.length) {
      pendingPaths.value = result.failures.map((item) => item.path);
      error.value = `已提交 ${result.items.length} 个视频，${result.failures.length} 项失败：${result.failures[0].error}。重试只提交失败项。`;
      return;
    }
    emit("close");
    await router.push({ path: "/tasks", query: { tab: "background" } });
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : "转码提交失败，请重试";
  } finally {
    submitting.value = false;
  }
}
</script>
<style scoped>
.transcode-dialog {
  max-width: 36rem;
  width: min(36rem, calc(100vw - 24px));
}
.card-title p,
.transcode-explanation {
  color: var(--color-text-muted);
  font-size: 0.8125rem;
  line-height: 1.6;
}
.card-content {
  display: grid;
  gap: 0.625rem;
}
.card-content label {
  font-size: 0.875rem;
  font-weight: 600;
}
.input {
  min-height: 44px;
  width: 100%;
}
.transcode-destination {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  min-height: 44px;
  padding: 0.75rem;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  color: var(--color-text);
  text-align: left;
}
.transcode-destination span {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
}
.transcode-destination svg {
  flex-shrink: 0;
}
.card-action {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 0.75rem;
}
.card-action button {
  min-height: 44px;
}
.transcode-error {
  color: var(--color-danger);
  font-size: 0.875rem;
}
</style>
