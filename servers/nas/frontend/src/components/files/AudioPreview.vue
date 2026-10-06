<template>
  <section class="audio-preview" aria-label="音频预览">
    <div class="audio-orbit" aria-hidden="true">
      <span class="audio-disc" :class="{ playing: mediaStore.audioPlaying }">
        <AppIcon :name="mediaIcon('music_note')" :size="52" />
      </span>
    </div>
    <div class="audio-preview-copy">
      <span class="audio-kicker">正在预览</span>
      <h1>{{ mediaStore.currentAudio?.name || name }}</h1>
      <p>{{ queueDescription }}</p>
      <div class="audio-preview-actions">
        <button
          type="button"
          :disabled="!mediaStore.hasPreviousAudio"
          aria-label="上一首"
          @click="mediaStore.previousAudio()"
        >
          <AppIcon :name="mediaIcon('skip_previous')" :size="22" />
        </button>
        <button
          type="button"
          class="primary"
          :aria-label="mediaStore.audioPlaying ? '暂停' : '播放'"
          @click="mediaStore.toggleAudio()"
        >
          <AppIcon
            :name="mediaIcon(mediaStore.audioPlaying ? 'pause' : 'play_arrow')"
            :size="24"
          />
        </button>
        <button
          type="button"
          :disabled="!mediaStore.hasNextAudio"
          aria-label="下一首"
          @click="mediaStore.nextAudio()"
        >
          <AppIcon :name="mediaIcon('skip_next')" :size="22" />
        </button>
      </div>
      <div class="audio-preview-time" aria-live="polite">
        <span>{{ formatMediaTime(mediaStore.audioCurrentTime) }}</span>
        <span>{{ formatMediaTime(mediaStore.audioDuration) }}</span>
      </div>
    </div>
    <ol class="audio-preview-queue" aria-label="播放队列">
      <li v-for="(item, index) in mediaStore.audioQueue" :key="item.path">
        <button
          type="button"
          :class="{ active: index === mediaStore.audioIndex }"
          @click="mediaStore.selectAudio(index)"
        >
          <AppIcon
            :name="
              mediaIcon(
                index === mediaStore.audioIndex && mediaStore.audioPlaying
                  ? 'graphic_eq'
                  : 'music_note'
              )
            "
            :size="18"
          />
          <span>{{ item.name }}</span>
          <small>{{ index + 1 }}</small>
        </button>
      </li>
    </ol>
  </section>
</template>

<script setup lang="ts">
import { useMediaStore } from "@/stores/media";
import { formatMediaTime } from "@/utils/videoGestures";
import { computed } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import { mediaIcon } from "@/utils/mediaIconSemantics";

defineProps<{ name: string }>();
const mediaStore = useMediaStore();

const queueDescription = computed(() => {
  const item = mediaStore.currentAudio;
  if (!item) return "等待播放";
  const origin = item.origin === "favorite-group" ? "收藏分组" : "当前目录";
  return `${origin}队列 · ${mediaStore.audioIndex + 1} / ${mediaStore.audioQueue.length}`;
});
</script>

<style scoped>
.audio-preview {
  display: grid;
  width: min(980px, calc(100vw - 80px));
  min-height: 420px;
  grid-template-columns: minmax(240px, 0.85fr) minmax(260px, 1fr) minmax(
      220px,
      0.8fr
    );
  align-items: center;
  gap: clamp(24px, 5vw, 64px);
  padding: clamp(28px, 5vw, 64px);
  color: #fff;
  background: linear-gradient(145deg, rgb(18 23 34 / 96%), rgb(7 10 16 / 98%));
  border: 1px solid rgb(255 255 255 / 9%);
  border-radius: 28px;
  box-shadow: 0 30px 90px rgb(0 0 0 / 38%);
}

.audio-orbit {
  display: grid;
  aspect-ratio: 1;
  place-items: center;
  background:
    radial-gradient(circle, rgb(69 132 255 / 18%) 0 32%, transparent 33%),
    repeating-radial-gradient(
      circle,
      rgb(255 255 255 / 7%) 0 1px,
      transparent 2px 18px
    );
  border-radius: 50%;
}

.audio-disc {
  display: grid;
  width: 58%;
  aspect-ratio: 1;
  place-items: center;
  color: #fff;
  background: linear-gradient(145deg, var(--blue), #6058d6);
  border: 10px solid rgb(255 255 255 / 8%);
  border-radius: 50%;
  box-shadow: 0 24px 50px rgb(43 93 198 / 28%);
}

.audio-disc i {
  font-size: clamp(38px, 5vw, 64px);
}

.audio-disc.playing {
  animation: audio-spin 9s linear infinite;
}

.audio-preview-copy {
  min-width: 0;
}

.audio-kicker {
  color: #78a8ff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.16em;
}

.audio-preview h1 {
  margin: 12px 0 8px;
  overflow-wrap: anywhere;
  font-size: clamp(26px, 4vw, 44px);
  line-height: 1.08;
}

.audio-preview p {
  margin: 0;
  color: rgb(255 255 255 / 58%);
}

.audio-preview-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 30px;
}

.audio-preview-actions button {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  color: #fff;
  background: rgb(255 255 255 / 8%);
  border: 1px solid rgb(255 255 255 / 10%);
  border-radius: 50%;
  cursor: pointer;
}

.audio-preview-actions .primary {
  width: 62px;
  height: 62px;
  background: var(--blue);
  border-color: transparent;
  box-shadow: 0 16px 38px rgb(45 104 220 / 34%);
}

.audio-preview-actions button:disabled {
  opacity: 0.35;
  cursor: default;
}

.audio-preview-time {
  display: flex;
  justify-content: space-between;
  margin-top: 20px;
  color: rgb(255 255 255 / 48%);
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}

.audio-preview-queue {
  max-height: 310px;
  padding: 0;
  margin: 0;
  overflow: auto;
  list-style: none;
}

.audio-preview-queue li + li {
  margin-top: 4px;
}

.audio-preview-queue button {
  display: grid;
  width: 100%;
  min-height: 46px;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  color: rgb(255 255 255 / 68%);
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: 10px;
  cursor: pointer;
}

.audio-preview-queue button.active {
  color: #fff;
  background: rgb(83 139 255 / 16%);
}

.audio-preview-queue span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.audio-preview-queue small {
  color: rgb(255 255 255 / 35%);
  font-variant-numeric: tabular-nums;
}

button:focus-visible {
  outline: 2px solid #78a8ff;
  outline-offset: 2px;
}

@media (max-width: 860px) {
  .audio-preview {
    width: min(620px, calc(100vw - 36px));
    grid-template-columns: 180px minmax(0, 1fr);
  }

  .audio-preview-queue {
    grid-column: 1 / -1;
    max-height: 170px;
  }
}

@media (max-width: 560px) {
  .audio-preview {
    width: calc(100vw - 20px);
    min-height: calc(100dvh - 118px);
    grid-template-columns: 1fr;
    align-content: center;
    gap: 22px;
    padding: 24px;
    border-radius: 20px;
  }

  .audio-orbit {
    width: 190px;
    margin: 0 auto;
  }

  .audio-preview-copy {
    text-align: center;
  }

  .audio-preview-actions {
    justify-content: center;
  }

  .audio-preview-queue {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .audio-disc.playing {
    animation: none;
  }
}

@keyframes audio-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
