import type { DuplicateGroup } from "@/api/analysis";

export type DuplicateCleanupSelectionState = {
  included: boolean;
  keepPath: string;
};

export function isDuplicateGroupCleanable(
  schemaVersion: number | undefined,
  group: DuplicateGroup
) {
  return (
    schemaVersion === 3 &&
    group.files.length === group.totalFiles &&
    group.totalFiles > 1 &&
    group.keepReason !== "truncated" &&
    group.keepReason !== "unsafe-identity" &&
    group.files.every(
      (file) =>
        file.identity?.links === 1 &&
        (file.identity.mode & 0o170000) === 0o100000
    )
  );
}

export function initialDuplicateCleanupSelection(
  schemaVersion: number | undefined,
  group: DuplicateGroup
): DuplicateCleanupSelectionState {
  const cleanable = isDuplicateGroupCleanable(schemaVersion, group);
  return {
    included: cleanable && Boolean(group.suggestedKeepPath),
    keepPath: cleanable ? (group.suggestedKeepPath ?? "") : "",
  };
}

export function duplicateCleanupSummary(groups: DuplicateGroup[]) {
  return groups.reduce(
    (summary, group) => ({
      groups: summary.groups + 1,
      files: summary.files + group.totalFiles - 1,
      bytes: summary.bytes + group.reclaimableBytes,
    }),
    { groups: 0, files: 0, bytes: 0 }
  );
}
