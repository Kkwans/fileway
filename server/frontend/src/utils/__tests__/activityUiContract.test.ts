import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const source = readFileSync(
  resolve(process.cwd(), "src/views/TaskCenter.vue"),
  "utf8"
);
const taskCenterCss = readFileSync(
  resolve(process.cwd(), "src/css/task-center.css"),
  "utf8"
);
const activityCss = readFileSync(
  resolve(process.cwd(), "src/css/activity.css"),
  "utf8"
);

describe("activity page UI contract", () => {
  it("uses the shared SVG icon class instead of retired font selectors", () => {
    expect(source).not.toContain("material-icons");
    expect(taskCenterCss).not.toContain("material-icons");
    expect(activityCss).not.toContain("material-icons");
  });

  it("keeps the five task categories in the product order", () => {
    const order = ["download", "upload", "file", "background", "history"];
    let previous = -1;
    for (const id of order) {
      const index = source.indexOf(`id: "${id}"`);
      expect(index, id).toBeGreaterThan(previous);
      previous = index;
    }
    expect(source).toContain(
      'type TaskCenterTab = "download" | "upload" | "file" | "background" | "history"'
    );
  });

  it("renders byte, item and indeterminate progress states", () => {
    expect(source).toContain("taskProgress(task).mode === 'bytes'");
    expect(source).toContain("taskProgress(task).mode === 'items'");
    expect(source).toContain("task-center-progress--indeterminate");
    expect(source).toContain("task-center-progress-indeterminate-track");
    expect(source).toContain("taskProgressPercent(taskProgress(task))");
    expect(source).toContain("item.bytesTransferred");
  });

  it("uses an animated running indicator with a reduced-motion fallback", () => {
    expect(taskCenterCss).toMatch(
      /\.task-center-item-icon\.is-running > \.app-icon\s*\{[\s\S]*?animation:\s*task-center-icon-spin/
    );
    expect(taskCenterCss).toContain("@keyframes task-center-icon-spin");
    expect(taskCenterCss).toContain(
      ".task-center-progress-indeterminate-track::after"
    );
    expect(taskCenterCss).toMatch(
      /prefers-reduced-motion: reduce[\s\S]*?task-center-progress-indeterminate-track::after[\s\S]*?animation:\s*none;/
    );
  });

  it("merges the current browser upload queue into live transfer rows", () => {
    expect(source).toContain("useUploadStore");
    expect(source).toContain("upload.transferId");
    expect(source).toContain("speedBytesPerSecond: local.speedBytesPerSecond");
    expect(source).toContain("task-center-progress-track");
    expect(source).not.toContain('completed: "服务端已完成"');
    expect(source).toContain("groupUploadTransfers");
    expect(source).toContain("清空记录");
    expect(source).toContain("loadMoreTransfers");
    expect(source).toContain("loadMoreTasks");
    expect(source).toContain("loadMoreHistory");
    expect(source).toContain(
      'type TaskFilter = "all" | "active" | "attention" | "canceled" | "completed"'
    );
    expect(source).toContain("task-center-item-duration");
    expect(source).toContain("taskTimeParts");
    expect(source).toContain("删除记录");
  });

  it("keeps the task title centered and moves back navigation into the leading group", () => {
    expect(source).toContain('back-placement="leading"');
    expect(source).toContain('back-label="返回"');
  });

  it("keeps task tabs and mobile task actions at accessible hit sizes", () => {
    expect(taskCenterCss).toMatch(
      /\.task-center-tabs button\s*\{[\s\S]*?min-height:\s*44px;/
    );
    expect(taskCenterCss).toMatch(
      /@media \(max-width: 640px\)[\s\S]*?\.task-center-item-actions button\s*\{[\s\S]*?min-height:\s*44px;/
    );
  });

  it("uses fixed centered columns for task time, duration, and actions", () => {
    expect(taskCenterCss).toContain(
      "grid-template-columns: 36px minmax(0, 1fr) 132px 88px 120px;"
    );
    expect(taskCenterCss).toMatch(
      /\.task-center-list-head > span:not\(:first-child\),[\s\S]*text-align:\s*center;/
    );
    expect(taskCenterCss).toMatch(
      /\.task-center-item-time\s*\{[\s\S]*font-size:\s*12px;[\s\S]*font-variant-numeric:\s*tabular-nums;/
    );
  });

  it("does not retain the removed TaskCenter hero or summary panel", () => {
    expect(taskCenterCss).not.toContain(".task-center-intro");
    expect(taskCenterCss).not.toContain(".task-center-summary");
    expect(source).not.toContain("task-center-intro");
    expect(source).not.toContain("task-center-summary");
  });

  it("does not issue a second all-transfer snapshot on TaskCenter mount", () => {
    expect(source).not.toMatch(
      /onMounted\(async \(\) => \{[\s\S]*?await transfersStore\.load\(\);/
    );
  });

  it("keeps recent page shared layout styles without legacy task selectors", () => {
    expect(activityCss).toContain(".activity-page {");
    expect(activityCss).toContain(".recent-entry {");
    expect(activityCss).toContain(".activity-state,");
    expect(activityCss).not.toContain(".task-row {");
    expect(activityCss).not.toContain(".history-entry {");
  });
});
