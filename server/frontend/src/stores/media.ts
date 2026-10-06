import { defineStore } from "pinia";
import { computed, reactive, ref } from "vue";
import type { AudioQueueItem } from "@/utils/audioQueue";

export const useMediaStore = defineStore("media", () => {
  const audioQueue = ref<AudioQueueItem[]>([]);
  const audioIndex = ref(-1);
  const audioPlaying = ref(false);
  const desiredAudioPlaying = ref(false);
  const audioCurrentTime = ref(0);
  const audioDuration = ref(0);
  const audioVolume = ref(1);
  const audioCommand = ref(0);
  const audioError = ref("");
  const sessionPositions = reactive<Record<string, number>>({});

  const currentAudio = computed(
    () => audioQueue.value[audioIndex.value] ?? null
  );
  const hasPreviousAudio = computed(() => audioIndex.value > 0);
  const hasNextAudio = computed(
    () =>
      audioIndex.value >= 0 && audioIndex.value < audioQueue.value.length - 1
  );

  function openAudioQueue(
    queue: AudioQueueItem[],
    path: string,
    autoplay = false
  ) {
    const nextIndex = queue.findIndex((item) => item.path === path);
    if (nextIndex < 0) return;
    const sameItem = currentAudio.value?.path === path;
    audioQueue.value = queue.map((item) => ({ ...item }));
    audioIndex.value = nextIndex;
    if (!sameItem) {
      audioCurrentTime.value = sessionPositions[path] ?? 0;
      audioDuration.value = 0;
      audioError.value = "";
      desiredAudioPlaying.value = autoplay;
      audioCommand.value++;
    }
  }

  function requestAudioPlay() {
    if (!currentAudio.value) return;
    desiredAudioPlaying.value = true;
    audioCommand.value++;
  }

  function requestAudioPause() {
    desiredAudioPlaying.value = false;
    audioCommand.value++;
  }

  function toggleAudio() {
    if (audioPlaying.value || desiredAudioPlaying.value) requestAudioPause();
    else requestAudioPlay();
  }

  function selectAudio(index: number, autoplay = true) {
    if (index < 0 || index >= audioQueue.value.length) return;
    audioIndex.value = index;
    audioCurrentTime.value = sessionPositions[currentAudio.value!.path] ?? 0;
    audioDuration.value = 0;
    audioError.value = "";
    desiredAudioPlaying.value = autoplay;
    audioCommand.value++;
  }

  function previousAudio() {
    if (hasPreviousAudio.value) selectAudio(audioIndex.value - 1);
  }

  function nextAudio() {
    if (hasNextAudio.value) selectAudio(audioIndex.value + 1);
    else requestAudioPause();
  }

  function updateAudioPosition(position: number, duration: number) {
    audioCurrentTime.value = position;
    audioDuration.value = duration;
    if (currentAudio.value && Number.isFinite(position) && position >= 0) {
      sessionPositions[currentAudio.value.path] = position;
    }
  }

  function setAudioPlaybackState(playing: boolean) {
    audioPlaying.value = playing;
    desiredAudioPlaying.value = playing;
  }

  function setAudioVolume(volume: number) {
    audioVolume.value = Math.min(1, Math.max(0, volume));
  }

  function setAudioError(message: string) {
    audioError.value = message;
    if (message) setAudioPlaybackState(false);
  }

  function closeAudio() {
    audioQueue.value = [];
    audioIndex.value = -1;
    audioPlaying.value = false;
    desiredAudioPlaying.value = false;
    audioCurrentTime.value = 0;
    audioDuration.value = 0;
    audioError.value = "";
    audioCommand.value++;
  }

  return {
    audioQueue,
    audioIndex,
    audioPlaying,
    desiredAudioPlaying,
    audioCurrentTime,
    audioDuration,
    audioVolume,
    audioCommand,
    audioError,
    currentAudio,
    hasPreviousAudio,
    hasNextAudio,
    openAudioQueue,
    requestAudioPlay,
    requestAudioPause,
    toggleAudio,
    selectAudio,
    previousAudio,
    nextAudio,
    updateAudioPosition,
    setAudioPlaybackState,
    setAudioVolume,
    setAudioError,
    closeAudio,
  };
});
