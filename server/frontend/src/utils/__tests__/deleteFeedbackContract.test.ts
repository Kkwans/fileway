import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = resolve(process.cwd(), "src");
const readSource = (path: string) =>
  readFileSync(resolve(sourceRoot, path), "utf8");

describe("删除和消息反馈契约", () => {
  it("危险删除使用统一对话框并将常用移入回收站放在右侧", () => {
    const source = readSource("components/prompts/Delete.vue");

    expect(source).toContain("<AppDialog");
    expect(source).toContain('tone="danger"');
    expect(source).toContain("永久删除将在 3 秒后执行");
    expect(source.indexOf("delete-dialog-actions__permanent")).toBeLessThan(
      source.indexOf("delete-dialog-actions__trash")
    );
    expect(source).not.toContain("5 秒");
  });

  it("普通提示、轻提示和操作提示使用分级时长", () => {
    const source = readSource("main.ts");

    expect(source).toContain("timeout: 2000");
    expect(source).toContain("fallback = 2000");
    expect(source).toContain('importance === "minor"');
    expect(source).toContain("timeoutFor(normalized, 3000)");
  });
});
