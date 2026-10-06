import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const listingSource = readFileSync(
  fileURLToPath(new URL("../../views/files/FileListing.vue", import.meta.url)),
  "utf8"
);
const fileTypesSource = readFileSync(
  fileURLToPath(new URL("../../types/file.d.ts", import.meta.url)),
  "utf8"
);

describe("剪贴板目录类型契约", () => {
  it("复制或剪切时保留目录类型，并在粘贴时传给后端", () => {
    expect(listingSource).toContain("isDir: item.isDir,");
    expect(listingSource).not.toContain(
      "isDir: false, // clipboard items don't have isDir"
    );
  });

  it("剪贴板条目类型显式包含 isDir", () => {
    expect(fileTypesSource).toMatch(
      /export interface ClipItem \{[\s\S]*?isDir:\s*boolean;[\s\S]*?\}/
    );
  });
});
