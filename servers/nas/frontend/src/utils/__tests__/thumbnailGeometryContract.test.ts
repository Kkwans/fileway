import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const workspaceStyles = readFileSync(
  fileURLToPath(new URL("../../css/workspace-ui.css", import.meta.url)),
  "utf8"
);
const thumbnailSource = readFileSync(
  fileURLToPath(
    new URL("../../components/files/FileThumbnail.vue", import.meta.url)
  ),
  "utf8"
);

describe("详细列表缩略图几何契约", () => {
  it("uses one 44px size variable for the visual wrapper and FileThumbnail", () => {
    expect(workspaceStyles).toContain("--details-thumbnail-size: 44px;");
    expect(workspaceStyles).toContain(".details-row-visual > .file-thumbnail");
    expect(workspaceStyles).toContain("width: var(--details-thumbnail-size);");
    expect(workspaceStyles).toContain(
      ".details-row-visual > .file-thumbnail > img"
    );
    expect(thumbnailSource).toContain('width="256"');
    expect(thumbnailSource).toContain('height="256"');
  });
});
