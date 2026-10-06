import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const readSource = (path: string) =>
  readFileSync(resolve(process.cwd(), "src", path), "utf8");

describe("application shell width contract", () => {
  it("applies sidebar geometry only to the root application main", () => {
    const layout = readSource("views/Layout.vue");
    const base = readSource("css/base.css");
    const mobile = readSource("css/mobile.css");

    expect(layout).toContain('<main class="app-main">');
    expect(base).toContain(".app-main {");
    expect(base).not.toMatch(/(^|\n)main\s*\{/);
    expect(mobile).toContain(".app-main {");
  });

  it("lets primary workspaces grow with the available shell width", () => {
    const taskCenter = readSource("css/task-center.css");
    const activity = readSource("css/activity.css");
    const trash = readSource("css/trash.css");
    const archive = readSource("views/Archive.vue");
    const analysis = readSource("views/Analysis.vue");

    for (const source of [taskCenter, activity, trash, archive, analysis]) {
      expect(source).toContain("width: calc(100% - 32px);");
    }
  });
});
