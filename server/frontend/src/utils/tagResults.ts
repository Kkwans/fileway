import { normalizeTagPath } from "./tagPath";
import { encodePath } from "./url";

/** 将标签中保存的 NAS 路径转换成可直接跳转的文件路由。 */
export function buildTaggedPathUrl(path: string, isDir: boolean): string {
  const normalized = normalizeTagPath(path);
  const suffix = isDir ? "/" : "";
  return `/files${encodePath(normalized)}${suffix}`;
}

/** 取标签路径最后一段作为结果标题，同时兼容历史编码路径。 */
export function getTaggedPathName(path: string): string {
  const normalized = normalizeTagPath(path);
  return normalized.split("/").filter(Boolean).pop() || "/";
}

/**
 * 返回结果所在目录的可读路径。当前目录搜索会去掉共同的目录前缀，
 * 同时始终移除结果本身的文件名，避免标题和路径重复。
 */
export function getResultParentPath(path: string, base = "/"): string {
  const normalizedPath = normalizeTagPath(path);
  const basePath = normalizeTagPath(base);
  const normalizedBase = basePath.endsWith("/") ? basePath : `${basePath}/`;

  let relativePath = normalizedPath;
  if (normalizedPath.startsWith(normalizedBase)) {
    relativePath = normalizedPath.slice(normalizedBase.length);
  } else if (normalizedPath.startsWith("/")) {
    relativePath = normalizedPath.slice(1);
  }

  const segments = relativePath.split("/").filter(Boolean);
  segments.pop();
  return segments.length > 0 ? `${segments.join("/")}/` : "当前目录";
}

/** 构建“打开文件所在位置”使用的父目录文件路由。 */
export function buildResultParentRoute(path: string): string {
  const normalized = normalizeTagPath(path).replace(/\/+$/, "");
  const separator = normalized.lastIndexOf("/");
  const parent = separator > 0 ? normalized.slice(0, separator) : "/";
  return parent === "/" ? "/files/" : `/files${encodePath(parent)}/`;
}
