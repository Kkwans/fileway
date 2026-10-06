import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const sourceRoot = new URL("../../", import.meta.url);
const readSource = (relativePath: string) =>
  readFileSync(new URL(relativePath, sourceRoot), "utf8");

describe("本地图标表面契约", () => {
  it("编辑器和分享流程不再渲染 Material 字体图标", () => {
    const editorSource = readSource("views/files/Editor.vue");
    const shareSource = readSource("views/Share.vue");
    const sharePromptSource = readSource("components/prompts/Share.vue");

    for (const source of [editorSource, shareSource, sharePromptSource]) {
      expect(source).not.toContain("material-icons");
    }

    expect(editorSource).toContain('app-icon="save"');
    expect(editorSource).toContain('<AppIcon name="code"');
    expect(shareSource).toContain('app-icon="download"');
    expect(shareSource).toContain('<AppIcon name="copy"');
    expect(sharePromptSource).toContain('<AppIcon name="copy"');
  });

  it("通用 Action 的旧图标名通过本地语义映射兼容", () => {
    const actionSource = readSource("components/header/Action.vue");
    const registrySource = readSource("components/ui/iconRegistry.ts");

    expect(actionSource).not.toContain('class="material-icons"');
    expect(actionSource).toContain("resolveLegacyAppIcon");
    expect(registrySource).toContain("save: Save");
    expect(registrySource).toContain("code: Code2");
    expect(registrySource).toContain('"list-ordered": ListOrdered');
    expect(registrySource).toContain("resolveLegacyAppIcon");
  });

  it("搜索、Shell、错误页和登录页不再渲染 Material 字体图标", () => {
    const searchSource = readSource("components/Search.vue");
    const shellSource = readSource("components/Shell.vue");
    const errorsSource = readSource("views/Errors.vue");
    const loginSource = readSource("views/Login.vue");
    const markdownSource = readSource("utils/externalResources.ts");

    for (const source of [
      searchSource,
      shellSource,
      errorsSource,
      loginSource,
      markdownSource,
    ]) {
      expect(source).not.toContain("material-icons");
    }

    expect(searchSource).toContain('name="search"');
    expect(searchSource).toContain("getResourceIconName");
    expect(shellSource).toContain('<AppIcon name="chevron-right"');
    expect(errorsSource).toContain("AppIconName");
    expect(loginSource).toContain('name="loader"');
    expect(markdownSource).toContain("AppIcon");
  });
});
