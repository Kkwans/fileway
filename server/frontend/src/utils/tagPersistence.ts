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
