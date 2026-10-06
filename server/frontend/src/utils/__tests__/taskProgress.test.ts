import { describe, expect, it } from "vitest";
import {
  formatTaskBytes,
  getTaskProgress,
  mediaTaskEstimate,
  formatMediaTime,
} from "../taskProgress";
import type { MediaProgress } from "@/api/tasks";

describe("task progress presentation", () => {
  const media: MediaProgress = {
    phase: "encoding",
    durationSeconds: 600,
    processedSeconds: 120,
    speed: 2,
    fps: 48,
    startedAt: 1000,
    updatedAt: 61000,
    advancedAt: 61000,
  };
  it("uses media time and dynamically estimates both remaining and total wall time", () => {
    expect(
      getTaskProgress({
        media,
        processedBytes: 0,
        totalBytes: 0,
        processedItems: 0,
        totalItems: 0,
      })
    ).toEqual({ mode: "media", value: 120, max: 600 });
    expect(mediaTaskEstimate(media, "running", 61000)).toMatchObject({
      remaining: 240,
      total: 300,
      stale: false,
    });
    expect(
      mediaTaskEstimate({ ...media, speed: 1 }, "running", 61000).total
    ).toBe(540);
  });
  it("does not invent an ETA for queued, stopped or stalled work", () => {
    expect(
      mediaTaskEstimate({ ...media, phase: "queued" }, "running", 61000)
        .remaining
    ).toBeUndefined();
    expect(
      mediaTaskEstimate({ ...media, speed: 0 }, "running", 61000).remaining
    ).toBeUndefined();
    expect(
      mediaTaskEstimate(media, "completed", 61000).remaining
    ).toBeUndefined();
    expect(mediaTaskEstimate(media, "running", 91001)).toMatchObject({
      stale: true,
      stalled: true,
      total: undefined,
    });
    expect(
      mediaTaskEstimate({ ...media, updatedAt: 91001 }, "running", 91001)
    ).toMatchObject({ stale: false, stalled: true });
    expect(formatMediaTime()).toBe("估算中");
    expect(formatMediaTime(3661)).toBe("01:01:01");
  });
  it("prefers bytes and clamps an over-reported transfer", () => {
    expect(
      getTaskProgress({
        processedBytes: 120,
        totalBytes: 100,
        processedItems: 1,
        totalItems: 4,
      })
    ).toEqual({ mode: "bytes", value: 100, max: 100 });
  });

  it("falls back to item progress when byte totals are unavailable", () => {
    expect(
      getTaskProgress({
        processedBytes: 0,
        totalBytes: 0,
        processedItems: 2,
        totalItems: 5,
      })
    ).toEqual({ mode: "items", value: 2, max: 5 });
  });

  it("keeps progress indeterminate when neither total is known", () => {
    expect(
      getTaskProgress({
        processedBytes: 0,
        totalBytes: 0,
        processedItems: 0,
        totalItems: 0,
      })
    ).toEqual({ mode: "indeterminate" });
  });

  it("formats the compact byte label used in task rows", () => {
    expect(formatTaskBytes(512)).toBe("512 B");
    expect(formatTaskBytes(1024 * 1024)).toBe("1.0 MB");
  });
});
