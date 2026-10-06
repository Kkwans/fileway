import { describe, expect, it } from "vitest";
import { getAnalysisToolContent } from "@/utils/analysisTools";

describe("存储工具文案契约", () => {
  it("重复文件明确完整哈希与只读语义", () => {
    const content = getAnalysisToolContent("duplicates");

    expect(content.description).toContain("SHA-256");
    expect(content.description).toContain("只读");
    expect(content.action).toBe("开始查找重复文件");
  });

  it("空间分布明确主动扫描范围", () => {
    const content = getAnalysisToolContent("storage");

    expect(content.description).toContain("主动开始");
    expect(content.action).toBe("开始分析空间");
  });
});
