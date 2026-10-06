import { describe, expect, it, vi } from "vitest";

import {
  isMarkdownImageFile,
  markdownImageCandidateName,
  markdownImageLink,
  markdownImagePreviewContent,
  markdownImagePreviewSource,
  markdownImageTargetPath,
  normalizeMarkdownImageName,
  storeMarkdownImage,
} from "../markdownImages";

describe("Markdown 图片拖入", () => {
  it("保存到文档同级 assets 并保留原扩展名", async () => {
    const upload = vi.fn().mockResolvedValue(undefined);
    const file = new File(["image"], "家庭照片.JPEG", {
      type: "image/jpeg",
    });

    const stored = await storeMarkdownImage("/文档/记录.md", file, upload);

    expect(stored).toEqual({
      name: "家庭照片.JPEG",
      path: "/文档/assets/家庭照片.JPEG",
      markdown: "![家庭照片.JPEG](./assets/家庭照片.JPEG)",
    });
    expect(upload).toHaveBeenCalledWith("/文档/assets/家庭照片.JPEG", file);
  });

  it("冲突时按 -2、-3 递增且从不请求覆盖", async () => {
    const conflict = { status: 409 };
    const upload = vi
      .fn()
      .mockRejectedValueOnce(conflict)
      .mockRejectedValueOnce(conflict)
      .mockResolvedValueOnce(undefined);
    const file = new File(["image"], "photo.png", { type: "image/png" });

    const stored = await storeMarkdownImage("/note.md", file, upload);

    expect(upload.mock.calls.map(([path]) => path)).toEqual([
      "/assets/photo.png",
      "/assets/photo-2.png",
      "/assets/photo-3.png",
    ]);
    expect(stored.name).toBe("photo-3.png");
  });

  it("生成可读且安全的相对 Markdown 链接", () => {
    expect(markdownImageLink("图 片(终稿)#1.png")).toBe(
      "![图 片(终稿)#1.png](./assets/图%20片%28终稿%29%231.png)"
    );
    expect(markdownImageLink("a[b].png")).toContain("![a\\[b\\].png]");
  });

  it("只把相对图片地址映射到文档目录的原始内容接口", () => {
    expect(
      markdownImagePreviewSource(
        "/文档/记录.md",
        "./assets/图%20片%28终稿%29.png"
      )
    ).toBe(
      "/api/raw/%E6%96%87%E6%A1%A3/assets/%E5%9B%BE%20%E7%89%87%28%E7%BB%88%E7%A8%BF%29.png"
    );
    expect(
      markdownImagePreviewSource("/文档/子目录/记录.md", "../图片/photo.png")
    ).toBe("/api/raw/%E6%96%87%E6%A1%A3/%E5%9B%BE%E7%89%87/photo.png");
    expect(
      markdownImagePreviewSource("/记录.md", "assets/photo.png?size=2#view")
    ).toBe("/api/raw/assets/photo.png?size=2#view");
  });

  it("不改写绝对、远程和内嵌图片地址", () => {
    for (const source of [
      "/api/raw/photo.png",
      "https://example.com/photo.png",
      "//example.com/photo.png",
      "data:image/png;base64,AA==",
      "blob:https://example.com/id",
      "#preview",
      "http://[invalid",
    ]) {
      expect(markdownImagePreviewSource("/note.md", source)).toBeNull();
    }
  });

  it("渲染前只临时改写相对 Markdown 图片并返回反向映射", () => {
    const prepared = markdownImagePreviewContent(
      "/文档/记录.md",
      [
        "![图\\]片](./assets/photo.png)",
        "[普通链接](./other.md)",
        "![远程](https://example.com/photo.png)",
      ].join("\n\n")
    );

    expect(prepared.markdown).toContain(
      "![图\\]片](/api/raw/%E6%96%87%E6%A1%A3/assets/photo.png)"
    );
    expect(prepared.markdown).toContain("[普通链接](./other.md)");
    expect(prepared.markdown).toContain(
      "![远程](https://example.com/photo.png)"
    );
    expect(prepared.sources).toEqual([
      {
        markdownSource: "./assets/photo.png",
        previewSource: "/api/raw/%E6%96%87%E6%A1%A3/assets/photo.png",
      },
    ]);
  });

  it("清理路径片段、处理隐藏名并识别无 MIME 图片", () => {
    expect(
      normalizeMarkdownImageName({ name: "folder\\.photo.png", type: "" })
    ).toBe("image.photo.png");
    expect(normalizeMarkdownImageName({ name: "", type: "image/webp" })).toBe(
      "image.webp"
    );
    expect(isMarkdownImageFile({ name: "scan.HEIC", type: "" })).toBe(true);
    expect(isMarkdownImageFile({ name: "notes.txt", type: "text/plain" })).toBe(
      false
    );
  });

  it("保留复杂扩展名之前的基础名称", () => {
    expect(markdownImageCandidateName("archive.photo.png", 1)).toBe(
      "archive.photo-2.png"
    );
    expect(markdownImageTargetPath("/docs/note.md", "photo.png")).toBe(
      "/docs/assets/photo.png"
    );
  });

  it("非冲突失败立即返回且不尝试其他名称", async () => {
    const failure = Object.assign(new Error("forbidden"), { status: 403 });
    const upload = vi.fn().mockRejectedValue(failure);

    await expect(
      storeMarkdownImage(
        "/note.md",
        new File(["image"], "photo.png", { type: "image/png" }),
        upload
      )
    ).rejects.toBe(failure);
    expect(upload).toHaveBeenCalledTimes(1);
  });
});
