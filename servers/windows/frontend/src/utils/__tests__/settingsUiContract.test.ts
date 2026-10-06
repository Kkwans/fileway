import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const settingsViews = [
  "Global.vue",
  "Profile.vue",
  "Shares.vue",
  "Users.vue",
  "User.vue",
];

describe("settings UI contract", () => {
  it("keeps settings navigation semantically ordered as list items with links", () => {
    const settings = readFileSync(
      resolve(process.cwd(), "src/views/Settings.vue"),
      "utf8"
    );

    expect(settings).toMatch(/<ul>[\s\S]*<li[\s\S]*<router-link/);
    expect(settings).not.toMatch(/<router-link[^>]*>\s*<li/);
  });

  it("uses the shared local icon component across settings views", () => {
    for (const view of settingsViews) {
      const source = readFileSync(
        resolve(process.cwd(), `src/views/settings/${view}`),
        "utf8"
      );

      expect(source, view).not.toContain("material-icons");
    }

    const profile = readFileSync(
      resolve(process.cwd(), "src/views/settings/Profile.vue"),
      "utf8"
    );
    const registry = readFileSync(
      resolve(process.cwd(), "src/components/ui/iconRegistry.ts"),
      "utf8"
    );

    expect(profile).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(profile).toContain(":name=\"rule.visible ? 'eye' : 'eye-off'\"");
    expect(registry).toContain('"eye-off"');
  });

  it("账户设置 PC 双列 + 控件列 200px 对齐 + 无模块内保存", () => {
    const profile = readFileSync(
      resolve(process.cwd(), "src/views/settings/Profile.vue"),
      "utf8"
    );

    expect(profile).toContain('class="row profile-settings-grid"');
    expect(profile).toMatch(
      /\.profile-settings-grid\s*\{[\s\S]*?align-items:\s*start;/
    );
    expect(profile).toMatch(
      /grid-template-columns:\s*minmax\(0,\s*1\.4fr\)\s*minmax\(300px,\s*0\.9fr\)/
    );
    expect(profile).toMatch(
      /\.profile-settings-grid\s*>\s*\.column\s*>\s*\.card\s*\{[\s\S]*?height:\s*auto;/
    );
    expect(profile).toMatch(
      /\.setting-control-row\s*\{[\s\S]*?grid-template-columns:\s*minmax\(0,\s*1fr\)\s*200px;/
    );
    expect(profile).not.toMatch(/card-action/);
    expect(profile).not.toMatch(/>\s*更新\s*</);
  });

  it("设置页仅 tab 栏可见保存入口", () => {
    const settings = readFileSync(
      resolve(process.cwd(), "src/views/Settings.vue"),
      "utf8"
    );
    expect(settings).toContain("settings-nav-save");
    expect(settings).toContain("winfb-settings-save");
    for (const view of ["Profile.vue", "Global.vue", "Shares.vue"]) {
      const source = readFileSync(
        resolve(process.cwd(), `src/views/settings/${view}`),
        "utf8"
      );
      expect(source, view).not.toMatch(/>\s*保存\s*</);
      expect(source, view).not.toMatch(/>\s*更新\s*</);
    }
  });

  it("播放器超时仅用数字输入；默认播放策略为原生/兼容/每次询问", () => {
    const profile = readFileSync(
      resolve(process.cwd(), "src/views/settings/Profile.vue"),
      "utf8"
    );

    expect(profile).toContain('class="app-number"');
    expect(profile).toContain("默认播放策略");
    expect(profile).toContain('label: "原生优先"');
    expect(profile).toContain('label: "兼容优先"');
    expect(profile).toContain('label: "每次询问"');
    expect(profile).toContain('label: "默认续播"');
    expect(profile).toContain("AppSelect");
    expect(profile).toContain("check-card");
    expect(profile).toContain("resumeMinSec");
    // timeout row must not ship a preset dropdown
    const timeoutBlock = profile.split("播放器控件自动隐藏")[1]?.split("默认播放策略")[0] || "";
    expect(timeoutBlock).not.toContain("<select");
  });

  it("设置保存入口派发事件，toast 由各 tab 自理（避免双 toast）", () => {
    const settings = readFileSync(
      resolve(process.cwd(), "src/views/Settings.vue"),
      "utf8"
    );
    expect(settings).toContain("winfb-settings-save");
    expect(settings).not.toContain("设置已保存");
    const profile = readFileSync(
      resolve(process.cwd(), "src/views/settings/Profile.vue"),
      "utf8"
    );
    expect(profile).toContain("设置已保存");
  });
});
