import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";

const source = readFileSync(
  new URL("../../views/Archive.vue", import.meta.url),
  "utf8"
);

describe("archive browser UI contract", () => {
  it("uses local semantic icons for archive states and entries", () => {
    expect(source).not.toContain("material-icons");
    expect(source).toContain(
      'import AppIcon from "@/components/ui/AppIcon.vue"'
    );
    expect(source).toContain("getResourceIconName");
    expect(source).toContain('"circle-alert"');
    expect(source).toContain('"archive-restore"');
  });

  it("保留安全与解压能力，但移除重复 Hero、步骤和并发宣传", () => {
    expect(source).toContain("<PathPicker");
    expect(source).toContain('class="archive-blocked"');
    expect(source).toContain('class="archive-warning"');
    expect(source).not.toContain('class="archive-hero"');
    expect(source).not.toContain("SAFE ARCHIVE BROWSER");
    expect(source).not.toContain("EXTRACTION COMPLETE");
    expect(source).not.toContain("解压任务全局并发 1");
    expect(source).not.toContain(">01<");
    expect(source).not.toContain(">02<");
  });
});
