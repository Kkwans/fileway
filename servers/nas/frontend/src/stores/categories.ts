import { defineStore } from "pinia";
import { ref } from "vue";
import type { CategoryRule } from "@/api/categories";
import { getCategories } from "@/api/categories";

// Fallback category rules if API is unavailable
const FALLBACK_CATEGORIES: CategoryRule[] = [
  {
    id: "personal",
    name: "个人文件夹",
    icon: "person",
    color: "#4CAF50",
    patterns: ["/volume*/@home/*"],
  },
  {
    id: "shared",
    name: "共享文件夹",
    icon: "group",
    color: "#2196F3",
    patterns: [
      "/volume*/OpenClaw",
      "/volume*/Project",
      "/volume*/Hermes",
      "/volume*/docker",
      "/volume*/docker-apps",
      "/volume*/DockerProject",
      "/volume*/Download",
      "/volume*/Movie",
      "/volume*/Movies",
      "/volume*/Music",
      "/volume*/Photos",
      "/volume*/Pictures",
      "/volume*/TV",
      "/volume*/Video",
      "/volume*/Videos",
      "/volume*/Documents",
      "/volume*/Common",
      "/volume*/ViEDO",
      "/volume*/迅雷下载",
    ],
  },
  {
    id: "system",
    name: "系统文件夹",
    icon: "settings",
    color: "#FF9800",
    patterns: [
      "/volume*/@appstore",
      "/volume*/@docker",
      "/volume*/@home",
      "/volume*/@tmp",
      "/volume*/@upload",
      "/volume*/@search",
      "/volume*/@thumbnail",
      "/volume*/Docker",
    ],
  },
];

export interface CategoryGroup {
  id: string;
  name: string;
  icon: string;
  color: string;
  paths: string[];
}

export const useCategoriesStore = defineStore("categories", () => {
  const categories = ref<CategoryRule[]>(FALLBACK_CATEGORIES);
  const loading = ref(false);

  // Classify a path into its category using local pattern matching
  function classifyPath(path: string): CategoryRule {
    const cleaned = path.replace(/\/+$/, ""); // strip trailing slash

    for (const cat of categories.value) {
      for (const pattern of cat.patterns) {
        if (matchPattern(pattern, cleaned)) {
          return cat;
        }
      }
    }

    // Default "other" category
    return {
      id: "other",
      name: "其他",
      icon: "folder",
      color: "#9E9E9E",
      patterns: [],
    };
  }

  // Match a glob-like pattern against a path
  function matchPattern(pattern: string, path: string): boolean {
    const cleanPattern = pattern.replace(/\/+$/, "");
    const cleanPath = path.replace(/\/+$/, "");

    // Pattern ending with /* means "match this prefix"
    if (cleanPattern.endsWith("/*")) {
      const prefix = cleanPattern.slice(0, -2);
      return cleanPath === prefix || cleanPath.startsWith(prefix + "/");
    }

    // Direct match
    if (cleanPattern === cleanPath) return true;

    // Prefix match (pattern is a parent of path)
    if (cleanPath.startsWith(cleanPattern + "/")) return true;

    // Glob match using simple regex
    const regexStr = cleanPattern
      .replace(/[.+^${}()|[\]\\]/g, "\\$&")
      .replace(/\*/g, "[^/]*");
    const regex = new RegExp(`^${regexStr}$`);
    return regex.test(cleanPath);
  }

  async function fetchCategories() {
    loading.value = true;
    try {
      const info = await getCategories();
      if (info.categories?.length > 0) {
        categories.value = info.categories;
      }
    } catch {
      // Use fallback categories
      categories.value = FALLBACK_CATEGORIES;
    } finally {
      loading.value = false;
    }
  }

  return {
    categories,
    loading,
    classifyPath,
    fetchCategories,
  };
});
