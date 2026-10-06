import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const sourceRoot = resolve(process.cwd(), "src");
const readSource = (path: string) =>
  readFileSync(resolve(sourceRoot, path), "utf8");

describe("标签与收藏分组图标契约", () => {
  it("标签管理、标签选择和收藏分组使用本地语义图标", () => {
    for (const path of [
      "components/TagManager.vue",
      "components/TagPicker.vue",
      "components/FavoriteGroupPicker.vue",
    ]) {
      const source = readSource(path);
      expect(source).not.toContain("material-icons");
      expect(source).toContain(
        'import AppIcon from "@/components/ui/AppIcon.vue"'
      );
    }

    const registrySource = readSource("components/ui/iconRegistry.ts");
    expect(registrySource).toContain('"color-picker": Pipette');
    expect(registrySource).toContain('"star-off": StarOff');
  });
});
