import { describe, expect, it } from "vitest";

import { getResourceIconName } from "../fileIcons";

describe("文件列表资源图标语义", () => {
  it("为目录和媒体使用稳定的本地语义图标", () => {
    expect(getResourceIconName("Photos", "dir", true)).toBe("folder");
    expect(getResourceIconName("photo.jpg", "image")).toBe("file-image");
    expect(getResourceIconName("movie.mp4", "video")).toBe("file-video");
    expect(getResourceIconName("track.mp3", "audio")).toBe("file-music");
  });

  it("按文档、表格、压缩包和代码保持可识别区分", () => {
    expect(getResourceIconName("readme.md", "blob")).toBe("file-text");
    expect(getResourceIconName("data.csv", "blob")).toBe("file-spreadsheet");
    expect(getResourceIconName("backup.tar.gz", "blob")).toBe("file-archive");
    expect(getResourceIconName("vite.config.ts", "blob")).toBe("file-code");
    expect(getResourceIconName("unknown.bin", "blob")).toBe("file-type");
  });
});
