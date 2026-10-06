import { fetchURL } from "./utils";
import type { RiskLevel } from "@/types/file";

export interface CategoryRule {
  id: string;
  name: string;
  icon: string;
  color: string;
  patterns: string[];
}

export interface CategoryPath {
  path: string;
  name: string;
  risk: RiskLevel;
  volumeType: string;
}

export interface CategoryGroup {
  id: string;
  name: string;
  icon: string;
  color: string;
  paths: CategoryPath[];
}

export interface CategoryInfo {
  categories: CategoryRule[];
}

export interface ClassifyResult {
  path: string;
  category: {
    id: string;
    name: string;
    icon: string;
    color: string;
  };
  risk: RiskLevel;
}

export async function getCategories(): Promise<CategoryInfo> {
  const res = await fetchURL("/api/categories", {});
  return (await res.json()) as CategoryInfo;
}

export async function classifyPath(path: string): Promise<ClassifyResult> {
  const res = await fetchURL(
    `/api/classify?path=${encodeURIComponent(path)}`,
    {}
  );
  return (await res.json()) as ClassifyResult;
}
