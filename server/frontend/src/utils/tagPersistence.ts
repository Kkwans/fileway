import { favoriteIdentity, favoriteWirePath } from "./favoritePersistence";

export interface TagPathRef {
  path: string;
  wirePath?: string;
  pathVerified: boolean;
}
export type TagReferences = { paths: string[]; pathRefs?: TagPathRef[] };

export function tagReferences(tag: TagReferences): TagPathRef[] {
  if (
    tag.pathRefs !== undefined &&
    (!Array.isArray(tag.pathRefs) ||
      tag.pathRefs.some(
        (ref) =>
          typeof ref.path !== "string" ||
          typeof ref.pathVerified !== "boolean" ||
          (ref.wirePath !== undefined && typeof ref.wirePath !== "string")
      ))
  )
    throw new Error("标签原始路径数据格式无效");
  const values =
    tag.pathRefs ??
    tag.paths.map((path) => ({ path, pathVerified: undefined }));
  const seen = new Set<string>();
  return values.flatMap((ref) => {
    const wire = favoriteWirePath(ref);
    if (ref.pathVerified === true && !wire)
      throw new Error("标签原始路径无效，请刷新后重试");
    const value: TagPathRef = {
      path: ref.path,
      ...(wire ? { wirePath: wire } : {}),
      pathVerified: wire !== null,
    };
    const identity = favoriteIdentity(value);
    if (identity !== null && seen.has(identity)) return [];
    if (identity !== null) seen.add(identity);
    return [value];
  });
}

export function tagAssociationBody(ref: {
  path: string;
  wirePath?: string;
  pathVerified?: boolean;
}) {
  const wirePath = favoriteWirePath(ref);
  if (!wirePath)
    throw new Error("标签原始路径无法确认，不能按显示名称修改关联");
  let compatible = false;
  try {
    compatible =
      wirePath.split("/").map(decodeURIComponent).join("/") === ref.path;
  } catch {
    /* Opaque requests never send display fallback. */
  }
  return { wirePath, ...(compatible ? { path: ref.path } : {}) };
}

export interface PersistedTag {
  id: string;
  name: string;
}

export function replaceTagByName<T extends PersistedTag>(
  tags: T[],
  savedTag: T
): T[] {
  return tags.map((tag) => (tag.name === savedTag.name ? savedTag : tag));
}
