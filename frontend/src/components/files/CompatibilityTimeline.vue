<template>
  <div class="compat-timeline" @click.stop @pointerdown.stop @touchstart.stop>
    <div class="compat-timeline__summary">
      <span
        >{{ formatMediaTime(dragging ? draft : position) }} /
        {{ formatMediaTime(duration) }}</span
      >
      <span>{{ busy ? "正在准备目标位置…" : "可跳转到任意位置" }}</span>
    </div>
    <div class="compat-timeline__range">
      <span
        v-if="transcodedEnd"
        class="compat-timeline__available"
        :style="{
          left: '0%',
          width: `${(100 * Math.min(duration, transcodedEnd)) / duration}%`,
        }"
      ></span>
      <span
        class="compat-timeline__available"
        :style="{
          left: `${(100 * availableStart) / duration}%`,
          width: `${(100 * Math.max(0, availableEnd - availableStart)) / duration}%`,
        }"
      ></span>
      <span
        class="compat-timeline__played"
        :style="{
          width: `${(100 * (dragging ? draft : position)) / duration}%`,
        }"
      ></span>
      <input
        type="range"
        min="0"
        :max="Math.max(0, duration - 0.1)"
        step="0.1"
        :value="dragging ? draft : position"
        aria-label="视频完整时间线"
        :aria-valuetext="`${formatMediaTime(dragging ? draft : position)} / ${formatMediaTime(duration)}`"
        @input="updateDraft"
        @change="commit"
        @pointermove="preview"
        @pointerleave="hover = null"
      />
      <div
        v-if="hover !== null"
        class="compat-timeline__preview"
        :style="{
          left: `${Math.min(88, Math.max(12, (hover / duration) * 100))}%`,
        }"
      >
        <div v-if="sprite" :style="thumbnailStyle"></div>
        <span>{{ formatMediaTime(hover) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import type { VideoSprite } from "@/api/media";
import { formatMediaTime } from "@/utils/taskProgress";

const props = defineProps<{
  duration: number;
  position: number;
  availableStart: number;
  availableEnd: number;
  busy: boolean;
  sprite?: VideoSprite;
  transcodedEnd?: number;
}>();
const emit = defineEmits<{ seek: [seconds: number] }>();
const dragging = ref(false);
const draft = ref(0);
const hover = ref<number | null>(null);
function updateDraft(event: Event) {
  dragging.value = true;
  draft.value = Number((event.target as HTMLInputElement).value);
}
function commit(event: Event) {
  emit("seek", Number((event.target as HTMLInputElement).value));
  dragging.value = false;
}
function preview(event: PointerEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  hover.value = Math.min(
    props.duration,
    Math.max(0, ((event.clientX - rect.left) / rect.width) * props.duration)
  );
}
const thumbnailStyle = computed(() => {
  const sprite = props.sprite;
  if (!sprite) return {};
  const index = Math.min(
    sprite.number - 1,
    Math.max(0, Math.floor((hover.value || 0) / sprite.interval))
  );
  return {
    width: `${sprite.width}px`,
    height: `${sprite.height}px`,
    backgroundImage: `url(${JSON.stringify(sprite.url)})`,
    backgroundPosition: `${(-index % sprite.column) * sprite.width}px ${-Math.floor(index / sprite.column) * sprite.height}px`,
  };
});
</script>

<style scoped>
.compat-timeline {
  position: absolute;
  bottom: calc(var(--art-control-height, 46px) + 10px);
  left: var(--art-padding, 10px);
  right: var(--art-padding, 10px);
  z-index: 110;
  color: #fff;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.compat-timeline__summary {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  line-height: 1.5;
  text-shadow: 0 1px 4px #000;
}
.compat-timeline__summary span:last-child {
  opacity: 0.75;
  font-size: 11px;
}
.compat-timeline__range {
  position: relative;
  height: 24px;
}
.compat-timeline__range::before,
.compat-timeline__available,
.compat-timeline__played {
  position: absolute;
  top: 10px;
  height: 4px;
  border-radius: 4px;
  pointer-events: none;
}
.compat-timeline__range::before {
  content: "";
  inset-inline: 0;
  background: #ffffff35;
}
.compat-timeline__available {
  background: #ffffff80;
}
.compat-timeline__played {
  background: var(--art-theme, #2979ff);
}
.compat-timeline input {
  position: absolute;
  inset: -10px 0;
  height: 44px;
  width: 100%;
  margin: 0;
  appearance: none;
  background: transparent;
  cursor: pointer;
  touch-action: pan-y;
}
.compat-timeline input::-webkit-slider-runnable-track {
  background: transparent;
  height: 4px;
}
.compat-timeline input::-webkit-slider-thumb {
  appearance: none;
  height: 12px;
  width: 12px;
  border-radius: 50%;
  background: #fff;
  margin-top: -4px;
  box-shadow: 0 1px 5px #0008;
}
.compat-timeline input::-moz-range-thumb {
  height: 12px;
  width: 12px;
  border: 0;
  border-radius: 50%;
  background: #fff;
}
.compat-timeline input:focus-visible {
  outline: 2px solid var(--art-theme, #2979ff);
  outline-offset: 1px;
  border-radius: 4px;
}
.compat-timeline__preview {
  position: absolute;
  bottom: 36px;
  transform: translateX(-50%);
  border-radius: 6px;
  overflow: hidden;
  background: #16191e;
  padding: 4px;
  display: grid;
  gap: 4px;
  text-align: center;
  box-shadow: 0 4px 16px #0008;
  pointer-events: none;
}
</style>
