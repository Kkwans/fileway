import { describe, expect, it } from "vitest";
import { filesize } from "../index";

describe("文件大小格式", () => {
  it("使用 Windows 风格的 B、KB、MB 单位", () => {
    expect(filesize(1024)).toBe("1 KB");
    expect(filesize(1024 * 1024)).toBe("1 MB");
    expect(filesize(1024 * 1024 * 1024)).toBe("1 GB");
  });
});
