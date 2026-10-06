import { describe, expect, it } from "vitest";
import {
  clampMediaValue,
  detectVideoGestureAxis,
  formatMediaTime,
  seekFromDoubleTap,
  seekFromSwipe,
  shouldShowResumePosition,
} from "../videoGestures";

describe("视频移动端手势", () => {
  it("区分横向进度、左侧亮度和右侧音量", () => {
    expect(detectVideoGestureAxis(8, 4, 20, 400)).toBeNull();
    expect(detectVideoGestureAxis(30, 14, 20, 400)).toBe("seek");
    expect(detectVideoGestureAxis(8, -30, 100, 400)).toBe("brightness");
    expect(detectVideoGestureAxis(8, -30, 300, 400)).toBe("volume");
  });

  it("横向滑动和双击始终限制在真实时长内", () => {
    expect(seekFromSwipe(50, 100, 400, 100)).toBe(75);
    expect(seekFromSwipe(95, 100, 400, 100)).toBe(100);
    expect(seekFromDoubleTap(5, 10, 400, 100)).toBe(0);
    expect(seekFromDoubleTap(95, 390, 400, 100)).toBe(100);
  });

  it("格式化长短媒体时间并限制画面值", () => {
    expect(formatMediaTime(5.9)).toBe("0:05");
    expect(formatMediaTime(65)).toBe("1:05");
    expect(formatMediaTime(3661)).toBe("1:01:01");
    expect(clampMediaValue(2, 0.4, 1.6)).toBe(1.6);
  });

  it("只为可感知的续播位置显示恢复提示", () => {
    expect(shouldShowResumePosition(0)).toBe(false);
    expect(shouldShowResumePosition(0.999)).toBe(false);
    expect(shouldShowResumePosition(1)).toBe(true);
    expect(shouldShowResumePosition(Number.NaN)).toBe(false);
  });
});
