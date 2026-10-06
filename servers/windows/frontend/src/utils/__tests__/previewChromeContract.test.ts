import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const previewSource = readFileSync(
  new URL("../../views/files/Preview.vue", import.meta.url),
  "utf8"
);
const previewStyles = readFileSync(
  new URL("../../css/styles.css", import.meta.url),
  "utf8"
);
const workspaceStyles = readFileSync(
  new URL("../../css/workspace-ui.css", import.meta.url),
  "utf8"
);

describe("preview chrome contract", () => {
  it("isolates unified media from the globally centered header", () => {
    expect(previewSource).toContain("'media-preview-header': isUnifiedMedia");
    expect(previewSource).toContain('class="header-title"');
    expect(previewSource).toContain("{{ name }}");
    expect(previewStyles).toContain(
      "#previewer header.media-preview-header > .header-center"
    );
    expect(previewStyles).toMatch(
      /header\.media-preview-header > \.header-center[\s\S]*?position: static;[\s\S]*?transform: none;/
    );
    expect(previewStyles).toMatch(
      /header\.media-preview-header > \.header-center \.header-title[\s\S]*?color: #fff;[\s\S]*?text-align: left;[\s\S]*?text-overflow: ellipsis;/
    );
  });

  it("keeps a short media title sized to its content instead of stretching across the chrome", () => {
    expect(previewStyles).toMatch(
      /header\.media-preview-header > \.header-center\s*\{[\s\S]*?flex:\s*0 1 auto;[\s\S]*?width:\s*fit-content;[\s\S]*?max-width:\s*min\(48rem, calc\(100vw - 27rem\)\);/
    );
  });

  it("uses fixed square navigation targets with explicit optical alignment", () => {
    expect(previewSource).toContain("'preview-nav--previous'");
    expect(previewSource).toContain("'preview-nav--next'");
    expect(previewStyles).toMatch(
      /#previewer > \.preview-nav \{[\s\S]*?width: 48px;[\s\S]*?height: 48px;[\s\S]*?place-items: center;/
    );
    expect(previewStyles).toMatch(
      /#previewer > \.preview-nav > i \{[\s\S]*?display: grid;[\s\S]*?place-items: center;[\s\S]*?line-height: 1;[\s\S]*?translateY\(2px\);/
    );
  });

  it("keeps the breadcrumb gap in its sticky offsets", () => {
    expect(workspaceStyles).toContain(
      "top: calc(var(--app-header-height, 56px) + 10px);"
    );
    expect(workspaceStyles).toContain(
      "top: calc(var(--app-mobile-header-height, 96px) + 8px);"
    );
  });
});
