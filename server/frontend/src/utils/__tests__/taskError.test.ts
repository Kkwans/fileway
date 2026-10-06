import { describe, expect, it } from "vitest";
import { summarizeTaskError } from "../taskError";

describe("task error presentation", () => {
  it("keeps the first useful line while bounding long command output", () => {
    const error =
      "FFmpeg HLS 转码失败: [libx264 @ 0x7f8cd9] height not divisible by 2\n" +
      "[hls @ 0x7f8] Error while opening encoder - maybe incorrect parameters\n" +
      "Task finished with error code: -22 (Invalid argument)";

    const summary = summarizeTaskError(error);

    expect(summary).toContain("FFmpeg HLS 转码失败");
    expect(summary).not.toContain("\n");
    expect(summary.length).toBeLessThanOrEqual(160);
  });

  it("returns a useful fallback for whitespace-only errors", () => {
    expect(summarizeTaskError("  \n  ")).toBe("任务失败，未提供详细原因");
  });
});
