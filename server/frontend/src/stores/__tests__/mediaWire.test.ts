import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useMediaStore } from "../media";
import { favoriteGroupAudioQueue } from "@/utils/audioQueue";

vi.mock("@/api/utils", () => ({
  createURL: () => "https://fixture.invalid/base/api/raw?inline=true",
}));

describe("favorite-group media wire selection", () => {
  beforeEach(() => setActivePinia(createPinia()));
  it("selects and remembers each same-display track by source bytes", () => {
    const base = {
      id: "opaque",
      path: "/中文.mp3",
      name: "中文.mp3",
      groupId: "g",
      order: 0,
      addedAt: 0,
    };
    const queue = favoriteGroupAudioQueue(
      [
        { ...base, wirePath: "/%D6%D0%CE%C4.mp3" },
        { ...base, id: "utf8", order: 1, wirePath: "/%E4%B8%AD%E6%96%87.mp3" },
      ],
      "g"
    );
    const store = useMediaStore();
    store.openAudioQueue(queue, base.path, true, queue[1].wirePath);
    expect(store.audioIndex).toBe(1);
    expect(store.currentAudio?.source).toBe(
      "https://fixture.invalid/base/api/raw/%E4%B8%AD%E6%96%87.mp3?inline=true"
    );
    store.updateAudioPosition(12, 60);
    store.selectAudio(0);
    expect(store.audioCurrentTime).toBe(0);
    expect(store.currentAudio?.source).toBe(
      "https://fixture.invalid/base/api/raw/%D6%D0%CE%C4.mp3?inline=true"
    );
    store.updateAudioPosition(3, 60);
    store.selectAudio(1);
    expect(store.audioCurrentTime).toBe(12);
    store.selectAudio(0);
    expect(store.audioCurrentTime).toBe(3);
  });
});
