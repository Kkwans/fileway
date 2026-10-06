import type { AppIconName } from "@/components/ui/iconRegistry";

export type AnalysisTool = "duplicates" | "storage";

export interface AnalysisToolContent {
  label: string;
  summary: string;
  title: string;
  description: string;
  action: string;
  icon: AppIconName;
}

export const analysisToolContent: Record<AnalysisTool, AnalysisToolContent> = {
  duplicates: {
    label: "重复文件",
    summary: "按内容哈希确认",
    title: "查找内容完全相同的文件",
    description:
      "先按大小和首尾样本缩小范围，再用完整 SHA-256 确认；扫描只读，不会自动删除文件。",
    action: "开始查找重复文件",
    icon: "analysis-duplicates",
  },
  storage: {
    label: "空间分布",
    summary: "目录与大文件排行",
    title: "看清所选范围的空间占用",
    description:
      "读取目录和文件元数据，汇总实际占用并列出最大的目录与文件；每次均由你主动开始。",
    action: "开始分析空间",
    icon: "analysis-storage",
  },
};

export function getAnalysisToolContent(tool: AnalysisTool) {
  return analysisToolContent[tool];
}
