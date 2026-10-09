import { encodePath } from "./url";

export interface BatchRenameDraft {
  sourcePath: string;
  sourceWirePath?: string;
  oldName: string;
  newName: string;
  isDir: boolean;
}

export type BatchRenameRule =
  | { type: "replace"; search: string; replacement: string }
  | { type: "prefix"; value: string }
  | { type: "suffix"; value: string }
  | {
      type: "number";
      base: string;
      start: number;
      padding: number;
      preserveExtension: boolean;
    };

export interface BatchRenameChange {
  from: string;
  to: string;
  fromWirePath?: string;
  toWirePath?: string;
}

export interface BatchRenameValidation {
  changes: BatchRenameChange[];
  errors: Map<number, string>;
}

function splitExtension(name: string, isDir: boolean) {
  if (isDir) return { stem: name, extension: "" };
  const separator = name.lastIndexOf(".");
  if (separator <= 0) return { stem: name, extension: "" };
  return {
    stem: name.slice(0, separator),
    extension: name.slice(separator),
  };
}

function destinationPath(sourcePath: string, name: string) {
  const separator = sourcePath.lastIndexOf("/");
  const directory = separator <= 0 ? "" : sourcePath.slice(0, separator);
  return `${directory}/${name}`;
}

/** Byte identity only. Never decode opaque filesystem bytes into display text. */
export function batchRenameWireKey(wirePath: string): string {
  if (
    !wirePath.startsWith("/") ||
    wirePath.startsWith("//") ||
    /[?#]/.test(wirePath) ||
    [...wirePath].some(
      (value) => value.charCodeAt(0) < 0x21 || value.charCodeAt(0) > 0x7e
    ) ||
    /%(?![0-9a-f]{2})/i.test(wirePath)
  )
    throw new Error("原始路径无效，请刷新后重试");
  const segments = wirePath
    .replace(/\/+$/, "")
    .split("/")
    .map((segment, index) => {
      const bytes = segment.replace(/%([0-9a-f]{2})/gi, (_, hex) =>
        String.fromCharCode(Number.parseInt(hex, 16))
      );
      if (
        (index > 0 && !bytes) ||
        bytes === "." ||
        bytes === ".." ||
        bytes.includes("/") ||
        bytes.includes("\0")
      )
        throw new Error("原始路径包含无效段，请刷新后重试");
      return bytes;
    });
  return segments.join("/") || "/";
}

export function applyBatchRenameRule(
  drafts: BatchRenameDraft[],
  rule: BatchRenameRule
) {
  return drafts.map((draft, index) => {
    const { stem, extension } = splitExtension(draft.oldName, draft.isDir);
    let newName = draft.oldName;
    if (rule.type === "replace" && rule.search !== "") {
      newName = draft.oldName.split(rule.search).join(rule.replacement);
    } else if (rule.type === "prefix") {
      newName = `${rule.value}${draft.oldName}`;
    } else if (rule.type === "suffix") {
      newName = `${stem}${rule.value}${extension}`;
    } else if (rule.type === "number") {
      const number = String(rule.start + index).padStart(rule.padding, "0");
      newName = `${rule.base}${number}${rule.preserveExtension ? extension : ""}`;
    }
    return { ...draft, newName };
  });
}

export function validateBatchRenameDrafts(
  drafts: BatchRenameDraft[]
): BatchRenameValidation {
  const errors = new Map<number, string>();
  const destinations = new Map<string, number>();
  const changes: BatchRenameChange[] = [];

  drafts.forEach((draft, index) => {
    if (
      draft.newName === "" ||
      draft.newName === "." ||
      draft.newName === ".." ||
      draft.newName.includes("/") ||
      draft.newName.includes("\0")
    ) {
      errors.set(index, "名称不能为空、点目录或包含 / 字符");
      return;
    }
    if (draft.newName === draft.oldName) return;

    const destination = destinationPath(draft.sourcePath, draft.newName);
    const wire = draft.sourceWirePath;
    let destinationWire: string | undefined;
    let destinationKey = batchRenameWireKey(encodePath(destination));
    if (wire) {
      try {
        batchRenameWireKey(wire);
        destinationWire = destinationPath(
          wire.replace(/\/+$/, ""),
          encodePath(draft.newName)
        );
        destinationKey = batchRenameWireKey(destinationWire);
      } catch (error) {
        errors.set(
          index,
          error instanceof Error ? error.message : "原始路径无效，请刷新后重试"
        );
        return;
      }
    }
    const duplicate = destinations.get(destinationKey);
    if (duplicate !== undefined) {
      errors.set(index, `与第 ${duplicate + 1} 项的目标名称重复`);
      errors.set(duplicate, `与第 ${index + 1} 项的目标名称重复`);
      return;
    }
    destinations.set(destinationKey, index);
    changes.push({
      from: draft.sourcePath,
      to: destination,
      ...(wire ? { fromWirePath: wire, toWirePath: destinationWire } : {}),
    });
  });

  return { changes, errors };
}
