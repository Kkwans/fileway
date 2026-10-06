import { describe, expect, it } from "vitest";
import { videoClassHeight, videoResolutionLabel } from "../videoResolution";

describe("video resolution classification", () => {
  it("recognizes a 3840×1600 cinema frame as 4K", () => {
    expect(videoClassHeight(3840, 1600)).toBe(2160);
    expect(videoResolutionLabel(3840, 1600)).toBe("4K");
  });

  it("preserves 16:9, 2K cinema and smaller labels", () => {
    expect(videoResolutionLabel(3840, 2160)).toBe("4K");
    expect(videoResolutionLabel(2560, 1080)).toBe("2K");
    expect(videoResolutionLabel(1920, 800)).toBe("1080p");
    expect(videoResolutionLabel(1280, 720)).toBe("720p");
    expect(videoResolutionLabel(0, 0)).toBe("—");
  });
});
