<template>
  <div
    v-if="uploadStore.activeUploads.size > 0"
    class="upload-files"
    v-bind:class="{ closed: !open }"
  >
    <div class="card floating">
      <div class="card-title">
        <div class="upload-heading-icon" aria-hidden="true">
          <AppIcon name="upload" :size="20" :stroke-width="2" />
        </div>
        <h2 class="upload-title">
          <span class="upload-title-main">正在上传</span>
          <span class="upload-count"
            >{{ uploadStore.pendingUploadCount }} 个文件</span
          >
        </h2>
        <button
          class="action upload-abort"
          @click="abortAll"
          aria-label="中止全部上传"
          title="中止全部上传"
        >
          <AppIcon name="x" :size="22" :stroke-width="2" />
        </button>
        <button
          class="action"
          @click="toggle"
          aria-label="切换列表"
          title="切换列表"
        >
          <AppIcon
            :name="open ? 'chevron-down' : 'chevron-up'"
            :size="22"
            :stroke-width="2"
          />
        </button>
      </div>

      <div class="upload-summary" aria-live="polite">
        <div class="upload-summary-topline">
          <strong>{{ sentPercent }}%</strong>
          <span>{{ transferredText }} / {{ totalText }}</span>
        </div>
        <div
          class="upload-summary-progress"
          role="progressbar"
          aria-label="上传总进度"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-valuenow="Number(sentPercent)"
        >
          <div :style="{ width: `${sentPercent}%` }"></div>
        </div>
        <div class="upload-info">
          <div>
            <span>速度</span>
            <strong>{{ speedText }}</strong>
          </div>
          <div>
            <span>预计剩余</span>
            <strong>{{ formattedETA }}</strong>
          </div>
        </div>
      </div>

      <div class="card-content file-icons">
        <div
          class="file"
          v-for="group in groupedUploads"
          :key="group.key"
          :data-dir="group.isFolderUpload"
          :aria-label="group.ariaLabel"
        >
          <div class="file-name">
            <AppIcon
              class="file-type-icon"
              :name="group.icon"
              :size="20"
              :stroke-width="1.9"
            />
            <span class="file-name-text" :title="group.name">{{
              group.name
            }}</span>
            <span v-if="group.isFolderUpload" class="file-group-count">
              {{ group.count }} 个文件
            </span>
          </div>
          <div class="file-progress">
            <div :style="{ width: `${group.percent}%` }"></div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useFileStore } from "@/stores/file";
import { useUploadStore } from "@/stores/upload";
import { computed, ref } from "vue";
import buttons from "@/utils/buttons";
import AppIcon from "@/components/ui/AppIcon.vue";
import { getResourceIconName } from "@/utils/fileIcons";
import { formatTaskBytes } from "@/utils/taskProgress";
const open = ref<boolean>(false);

const fileStore = useFileStore();
const uploadStore = useUploadStore();

type UploadGroup = {
  key: string;
  name: string;
  icon: ReturnType<typeof getResourceIconName>;
  count: number;
  totalBytes: number;
  sentBytes: number;
  percent: number;
  isFolderUpload: boolean;
  ariaLabel: string;
};

const groupedUploads = computed<UploadGroup[]>(() => {
  const groups = new Map<string, UploadGroup>();
  for (const upload of uploadStore.allUploads) {
    const key = upload.batchId || upload.transferId;
    const isFolderUpload = Boolean(upload.isFolderUpload && upload.batchId);
    const existing = groups.get(key);
    if (!existing) {
      const totalBytes = upload.batchBytes || upload.totalBytes;
      const count = isFolderUpload ? upload.batchItems || 1 : 1;
      groups.set(key, {
        key,
        name: isFolderUpload ? upload.batchName || "文件夹" : upload.name,
        icon: isFolderUpload
          ? "folder"
          : getResourceIconName(
              upload.name,
              upload.type,
              upload.type === "dir"
            ),
        count,
        totalBytes,
        sentBytes: upload.sentBytes,
        percent: totalBytes > 0 ? (upload.sentBytes / totalBytes) * 100 : 0,
        isFolderUpload,
        ariaLabel: isFolderUpload
          ? `${upload.batchName || "文件夹"}，${count} 个文件`
          : upload.name,
      });
      continue;
    }
    existing.sentBytes += upload.sentBytes;
    existing.totalBytes =
      upload.batchBytes || existing.totalBytes + upload.totalBytes;
    existing.percent =
      existing.totalBytes > 0
        ? (existing.sentBytes / existing.totalBytes) * 100
        : 0;
  }
  return [...groups.values()];
});

const sentPercent = computed(() =>
  uploadStore.totalBytes > 0
    ? ((uploadStore.sentBytes / uploadStore.totalBytes) * 100).toFixed(1)
    : "0.0"
);

const transferredText = computed(() => formatTaskBytes(uploadStore.sentBytes));
const totalText = computed(() => formatTaskBytes(uploadStore.totalBytes));
const speedText = computed(
  () => `${formatTaskBytes(Math.round(uploadStore.speedBytesPerSecond))}/秒`
);

const formattedETA = computed(() => {
  if (!Number.isFinite(uploadStore.etaSeconds)) {
    return "--:--:--";
  }

  let totalSeconds = Math.max(0, uploadStore.etaSeconds);
  const hours = Math.floor(totalSeconds / 3600);
  totalSeconds %= 3600;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = Math.round(totalSeconds % 60);

  return `${hours.toString().padStart(2, "0")}:${minutes
    .toString()
    .padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`;
});

const toggle = () => {
  open.value = !open.value;
};

const abortAll = () => {
  if (confirm("确定中止上传？")) {
    buttons.done("upload");
    open.value = false;
    uploadStore.abort();
    fileStore.reload = true; // Trigger reload in the file store
  }
};
</script>

<style scoped>
.upload-info {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.5rem;
  margin-top: 0.625rem;
  font-variant-numeric: tabular-nums;
}

.upload-info > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.5rem;
  min-width: 0;
  padding: 0.5rem 0.625rem;
  border-radius: 0.625rem;
  background: var(--surfaceSecondary, #f4f7fa);
}

.upload-info span {
  color: var(--textSecondary, #64748b);
  font-size: 0.6875rem;
}

.upload-info strong {
  overflow: hidden;
  color: var(--textPrimary, #1e293b);
  font-size: 0.75rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 450px) {
  .upload-info {
    gap: 0.375rem;
  }
}
</style>
