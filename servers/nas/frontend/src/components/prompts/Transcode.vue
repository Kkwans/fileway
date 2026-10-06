<template>
  <PathPicker
    v-if="picking"
    title="选择转码输出目录"
    :model-value="destination"
    @select="selectDestination"
    @close="picking = false"
  />
  <div v-else class="card floating transcode-dialog">
    <header class="transcode-header">
      <h2>后台转码</h2>
      <p>已选择 {{ pendingPaths.length }} 项，文件夹内的视频也会加入任务。</p>
    </header>
    <form @submit.prevent="submit">
      <div class="transcode-body">
        <div class="transcode-field">
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
          <p class="transcode-hint">保留视频比例，低分辨率视频不会被放大。</p>
        </div>
        <fieldset class="transcode-field transcode-location">
          <legend>保存位置</legend>
          <label class="transcode-location-option">
            <input
              v-model="destinationMode"
              type="radio"
              name="transcode-location"
              value="source"
              :disabled="submitting"
            />
            <span
              ><strong>源文件所在目录</strong
              ><small>每个视频保存到各自目录，原文件保留。</small></span
            >
          </label>
          <label class="transcode-location-option">
            <input
              v-model="destinationMode"
              type="radio"
              name="transcode-location"
              value="custom"
              :disabled="submitting"
            />
            <span
              ><strong>指定目录</strong
              ><small>将所有转码成品保存到同一个目录。</small></span
            >
          </label>
          <button
            v-if="destinationMode === 'custom'"
            class="transcode-destination"
            :aria-label="`选择转码输出目录，当前 ${destination}`"
            type="button"
            :disabled="submitting"
            @click="picking = true"
          >
            <AppIcon name="folder" :size="20" /><span>{{ destination }}</span
            ><AppIcon name="chevron-right" :size="18" />
          </button>
        </fieldset>
        <div class="transcode-note">
          <AppIcon name="info" :size="18" />
          <div>
            <p>首段就绪后，即可在任务中心边转边播。</p>
            <p>
              输出
              MP4，使用默认音轨；内挂字幕保留在原文件。同名成品自动添加序号。
            </p>
          </div>
        </div>
        <p v-if="error" role="alert" class="transcode-error">{{ error }}</p>
      </div>
      <div class="transcode-footer">
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
          :disabled="
            submitting || (destinationMode === 'custom' && !destination)
          "
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
const destinationMode = ref<"source" | "custom">("source");
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
      destinationMode.value === "custom" ? destination.value : undefined
    );
    if (!result.items.length)
      throw new Error(result.failures[0]?.error || "没有可提交的视频");
    // The server has accepted these tasks. A panel refresh failure must not
    // turn that success into an invitation to submit the same videos again.
    await tasks.load({ category: "background" }).catch(() => {});
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
.card.floating.transcode-dialog {
  max-width: 36rem;
  width: min(36rem, calc(100vw - 24px));
  color: var(--color-text);
  background: var(--color-surface);
  overflow: hidden;
}
.transcode-header {
  padding: 24px 24px 20px;
  border-bottom: 1px solid var(--color-border);
}
.transcode-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 650;
  line-height: 1.4;
}
.transcode-header p {
  margin: 8px 0 0;
}
.transcode-header p,
.transcode-hint,
.transcode-location-option small,
.transcode-note {
  color: var(--color-text-muted);
  font-size: 0.8125rem;
  line-height: 1.6;
}
.transcode-body {
  padding: 24px;
  display: grid;
  gap: 24px;
}
.transcode-field {
  display: grid;
  gap: 8px;
  min-width: 0;
}
.transcode-field > label,
.transcode-field legend {
  font-size: 0.875rem;
  font-weight: 600;
}
.transcode-hint {
  margin: 0;
}
.transcode-location {
  margin: 0;
  padding: 0;
  border: 0;
}
.transcode-location legend {
  margin-bottom: 8px;
  padding: 0;
}
.transcode-location-option {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  cursor: pointer;
}
.transcode-location-option:has(input:checked) {
  border-color: var(--color-accent);
  background: color-mix(in srgb, var(--color-accent) 5%, var(--color-surface));
}
.transcode-location-option input {
  margin: 3px 0 0;
  flex: 0 0 auto;
  width: 16px;
  height: 16px;
  accent-color: var(--color-accent);
}
.transcode-location-option strong,
.transcode-location-option small {
  display: block;
}
.transcode-location-option strong {
  font-size: 14px;
  line-height: 1.5;
  font-weight: 600;
}
.transcode-location-option small {
  margin-top: 3px;
  font-weight: 400;
}
.transcode-note {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px;
  background: var(--color-surface-muted);
  border-radius: 8px;
}
.transcode-note > .app-icon {
  flex-shrink: 0;
  margin-top: 2px;
}
.transcode-note p {
  margin: 0;
}
.transcode-note p:first-child {
  color: var(--color-text);
}
.transcode-note p + p {
  margin-top: 4px;
}
.input {
  min-height: 44px;
  width: 100%;
  box-sizing: border-box;
  margin: 0;
  color: var(--color-text);
  background: var(--color-surface);
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
.transcode-footer {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 0.75rem;
  padding: 16px 24px;
  border-top: 1px solid var(--color-border);
}
.transcode-footer button {
  min-height: 44px;
}
.transcode-destination:focus-visible,
.input:focus-visible,
.transcode-location-option:has(input:focus-visible) {
  outline: 2px solid var(--color-focus);
  outline-offset: 3px;
}
@media (max-width: 480px) {
  .transcode-header,
  .transcode-body {
    padding: 20px;
  }
  .transcode-footer {
    padding: 16px 20px;
  }
  .transcode-footer .button:last-child {
    flex: 1;
  }
}
.transcode-error {
  color: var(--color-danger);
  font-size: 0.875rem;
}
</style>
