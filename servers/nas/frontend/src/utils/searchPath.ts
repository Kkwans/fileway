import { normalizeFileKey } from "./fileListing";
import { encodePath } from "./url";

/**
 * 将搜索页路由中的路径转换成后端搜索接口使用的绝对目录。
 *
 * `Resource.path` 已经是 NAS 绝对路径，而 `/files/...` 只属于前端路由。
 * 不能对两者都使用旧的 removePrefix，否则真实路径会被误删前两级目录。
 */
export function normalizeSearchBase(rawBase: string): string {
  const base = normalizeFileKey(rawBase.trim());
  return base === "/" ? "/" : `${base}/`;
}

/** Decode an explicitly identified `/files` UI route exactly once. */
export function normalizeFilesRouteBase(routePath: string): string {
  let base = routePath.trim();
  if (base !== "/files" && !base.startsWith("/files/")) return "/";
  base = base.slice("/files".length) || "/";
  base = base
    .split("/")
    .map((segment) => {
      try {
        return decodeURIComponent(segment);
      } catch {
        return segment;
      }
    })
    .join("/");
  return normalizeSearchBase(base);
}

/** 将搜索上下文稳定映射回文件列表路由。 */
export function buildFilesRouteFromSearchBase(rawBase: string): string {
  const base = normalizeSearchBase(rawBase);
  return base === "/" ? "/files/" : `/files${encodePath(base)}`;
}

/** 切换搜索范围时保留进入搜索页前的目录，供“返回文件列表”使用。 */
export function buildTagSearchQuery(
  rawBase: string,
  scope: "current" | "global"
): { base: string; scope: "current" | "global" } {
  return {
    base: normalizeSearchBase(rawBase),
    scope,
  };
}

/** 标签筛选和关键词搜索互斥，进入标签路由时不能残留旧关键词。 */
export function getSearchPromptFromRoute(query: unknown, tag: unknown): string {
  if (typeof tag === "string" && tag.length > 0) return "";
  return typeof query === "string" ? query : "";
}
