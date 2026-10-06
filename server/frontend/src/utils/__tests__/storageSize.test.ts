import { describe, expect, it } from "vitest";
import { formatStorageSize, formatExplorerUsage } from "@/utils/storageSize";

describe("formatStorageSize", () => {
  it("使用十进制中文常用容量单位", () => {
    expect(formatStorageSize(999)).toBe("999 B");
    expect(formatStorageSize(1_500)).toBe("1.5 KB");
    expect(formatStorageSize(2_500_000)).toBe("2.5 MB");
    expect(formatStorageSize(7_200_000_000_000)).toBe("7.2 TB");
  });

  it("非法容量回退为 0 B", () => {
    expect(formatStorageSize(Number.NaN)).toBe("0 B");
    expect(formatStorageSize(-1)).toBe("0 B");
  });

  it("满盘明确显示零可用空间，不使用过期已用值推算", () => {
    expect(formatExplorerUsage(1024, 0, 1)).toBe("0 B 可用，共 1 KB");
  });
});
