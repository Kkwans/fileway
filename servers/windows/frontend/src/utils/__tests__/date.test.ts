import { describe, expect, it } from "vitest";
import { formatRelativeTime } from "../date";

describe("中文时间显示", () => {
  it("将相对时间固定显示为中文", () => {
    expect(
      formatRelativeTime("2026-07-11T12:00:00Z", "2026-07-12T12:00:00Z")
    ).toBe("1 天前");
  });
});
