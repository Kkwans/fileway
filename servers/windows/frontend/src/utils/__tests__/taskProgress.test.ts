import { describe, expect, it } from "vitest";
import { formatTaskBytes, getTaskProgress } from "../taskProgress";

describe("task progress presentation", () => {
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
