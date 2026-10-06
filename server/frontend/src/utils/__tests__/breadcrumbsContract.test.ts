import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const readSource = (relativePath: string) =>
  readFileSync(
    fileURLToPath(new URL(`../../${relativePath}`, import.meta.url)),
    "utf8"
  );

describe("文件面包屑根入口契约", () => {
  it("文件页显示与权限对应的根入口名称", () => {
    const breadcrumbsSource = readSource("components/Breadcrumbs.vue");
    const filesSource = readSource("views/Files.vue");

    expect(breadcrumbsSource).toContain("rootLabel?: string");
    expect(breadcrumbsSource).toContain("breadcrumb-root-label");
    expect(filesSource).toContain(':root-label="rootLabel"');
    expect(filesSource).toContain(
      'user.value?.perm?.admin ? "根目录" : "我的文件"'
    );
    expect(filesSource).not.toContain("NAS 根目录");
  });
});
