export interface BatchRenameDraft {
  sourcePath: string;
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
    const duplicate = destinations.get(destination);
    if (duplicate !== undefined) {
      errors.set(index, `与第 ${duplicate + 1} 项的目标名称重复`);
      errors.set(duplicate, `与第 ${index + 1} 项的目标名称重复`);
      return;
    }
    destinations.set(destination, index);
    changes.push({ from: draft.sourcePath, to: destination });
  });

  return { changes, errors };
}
