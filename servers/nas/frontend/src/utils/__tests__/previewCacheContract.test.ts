import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const filesApi = readFileSync(
  resolve(process.cwd(), "src/api/files.ts"),
  "utf8"
);

describe("preview cache identity contract", () => {
  it("把文件大小和修改时间带入预览 URL 身份", () => {
    expect(filesApi).toContain(
      "key: `${Date.parse(file.modified)}-${file.size}`"
    );
  });
});
