import { describe, expect, it } from "vitest";
import type { DuplicateGroup } from "@/api/analysis";
import {
  duplicateCleanupSummary,
  initialDuplicateCleanupSelection,
  isDuplicateGroupCleanable,
} from "../duplicateCleanup";

const identity = {
  deviceMajor: 1,
  deviceMinor: 0,
  inode: 1,
  links: 1,
  mode: 0o100640,
  uid: 1000,
  gid: 1000,
};

function group(overrides: Partial<DuplicateGroup> = {}): DuplicateGroup {
  return {
    sha256: "hash",
    size: 10,
    totalFiles: 2,
    reclaimableBytes: 10,
    keepReason: "oldest-created",
    suggestedKeepPath: "/oldest",
    files: [
      { path: "/oldest", size: 10, modified: 1, identity },
      { path: "/newer", size: 10, modified: 1, identity },
    ],
    ...overrides,
  };
}

describe("duplicate cleanup selection", () => {
  it("automatically includes only a safe group with a unique suggested keeper", () => {
    expect(initialDuplicateCleanupSelection(3, group())).toEqual({
      included: true,
      keepPath: "/oldest",
    });
    expect(
      initialDuplicateCleanupSelection(
        3,
        group({ keepReason: "missing-created", suggestedKeepPath: undefined })
      )
    ).toEqual({ included: false, keepPath: "" });
  });

  it("blocks old, truncated, hard-linked and symbolic-link reports", () => {
    expect(isDuplicateGroupCleanable(2, group())).toBe(false);
    expect(
      isDuplicateGroupCleanable(
        3,
        group({ totalFiles: 3, keepReason: "truncated" })
      )
    ).toBe(false);
    expect(
      isDuplicateGroupCleanable(
        3,
        group({
          files: [
            group().files[0],
            { ...group().files[1], identity: { ...identity, links: 2 } },
          ],
        })
      )
    ).toBe(false);
    expect(
      isDuplicateGroupCleanable(
        3,
        group({
          files: [
            group().files[0],
            { ...group().files[1], identity: { ...identity, mode: 0o120777 } },
          ],
        })
      )
    ).toBe(false);
  });

  it("summarizes only selected groups", () => {
    expect(
      duplicateCleanupSummary([
        group(),
        group({ sha256: "second", totalFiles: 4, reclaimableBytes: 30 }),
      ])
    ).toEqual({ groups: 2, files: 4, bytes: 40 });
  });
});
