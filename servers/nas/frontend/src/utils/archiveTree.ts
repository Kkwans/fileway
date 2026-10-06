import type { ArchiveEntry } from "@/api/archive";

export interface ArchiveTreeNode extends ArchiveEntry {
  implicit: boolean;
  children: ArchiveTreeNode[];
}

export interface ArchiveTreeRow extends ArchiveTreeNode {
  depth: number;
}

interface MutableNode extends ArchiveTreeNode {
  childMap: Map<string, MutableNode>;
  children: MutableNode[];
}

export function buildArchiveTree(entries: ArchiveEntry[]): ArchiveTreeNode[] {
  const roots = new Map<string, MutableNode>();
  for (const entry of entries) {
    const segments = entry.path.split("/").filter(Boolean);
    let children = roots;
    let currentPath = "";
    for (const [index, segment] of segments.entries()) {
      currentPath = currentPath ? `${currentPath}/${segment}` : segment;
      const leaf = index === segments.length - 1;
      let node = children.get(segment);
      if (!node) {
        node = {
          path: currentPath,
          name: segment,
          isDir: !leaf || entry.isDir,
          size: leaf ? entry.size : 0,
          modified: leaf ? entry.modified : 0,
          implicit: !leaf,
          children: [],
          childMap: new Map(),
        };
        children.set(segment, node);
      }
      if (leaf) {
        node.name = entry.name || segment;
        node.isDir = entry.isDir;
        node.size = entry.size;
        node.modified = entry.modified;
        node.implicit = false;
      }
      children = node.childMap;
    }
  }
  return finalizeNodes(roots);
}

function finalizeNodes(nodes: Map<string, MutableNode>): ArchiveTreeNode[] {
  return [...nodes.values()]
    .map((node) => ({
      path: node.path,
      name: node.name,
      isDir: node.isDir,
      size: node.size,
      modified: node.modified,
      implicit: node.implicit,
      children: finalizeNodes(node.childMap),
    }))
    .sort((left, right) => {
      if (left.isDir !== right.isDir) return left.isDir ? -1 : 1;
      return left.name.localeCompare(right.name, undefined, {
        numeric: true,
        sensitivity: "base",
      });
    });
}

export function flattenArchiveTree(
  nodes: ArchiveTreeNode[],
  expanded: ReadonlySet<string>,
  depth = 0
): ArchiveTreeRow[] {
  const rows: ArchiveTreeRow[] = [];
  for (const node of nodes) {
    rows.push({ ...node, depth });
    if (node.isDir && expanded.has(node.path)) {
      rows.push(...flattenArchiveTree(node.children, expanded, depth + 1));
    }
  }
  return rows;
}

export function pathCoveredBySelection(
  selected: ReadonlySet<string>,
  entryPath: string
) {
  for (const root of selected) {
    if (
      root === "." ||
      entryPath === root ||
      entryPath.startsWith(`${root}/`)
    ) {
      return true;
    }
  }
  return false;
}

export function hasSelectedAncestor(
  selected: ReadonlySet<string>,
  entryPath: string
) {
  for (const root of selected) {
    if (root === "." || entryPath.startsWith(`${root}/`)) return true;
  }
  return false;
}

export function selectedArchiveStats(
  entries: ArchiveEntry[],
  selected: ReadonlySet<string>
) {
  let items = 0;
  let files = 0;
  let bytes = 0;
  for (const entry of entries) {
    if (!pathCoveredBySelection(selected, entry.path)) continue;
    items++;
    if (!entry.isDir) {
      files++;
      bytes += entry.size;
    }
  }
  return { items, files, bytes };
}
