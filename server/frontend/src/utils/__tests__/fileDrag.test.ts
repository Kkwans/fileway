import { describe, expect, it } from "vitest";
import {
  canDropFilePaths,
  clearFileDragPayload,
  fileNameFromPath,
  isExternalFileDrag,
  readFileDragPayload,
  writeFileDragPayload,
} from "../fileDrag";

function transfer() {
  const values = new Map<string, string>();
  return {
    setData(type: string, value: string) {
      values.set(type, value);
    },
    getData(type: string) {
      return values.get(type) ?? "";
    },
    effectAllowed: "none",
  } as unknown as DataTransfer;
}

describe("file drag payload", () => {
  it("keeps canonical paths and supports Chinese names", () => {
    const data = transfer();
    writeFileDragPayload(data, [
      "/volume2/电影/海报.png",
      "/volume2/电影/海报.png",
    ]);
    expect(readFileDragPayload(data)).toEqual(["/volume2/电影/海报.png"]);
    expect(fileNameFromPath("/volume2/电影/海报.png")).toBe("海报.png");
  });

  it("rejects self and descendant targets", () => {
    expect(canDropFilePaths(["/docs"], "/docs")).toBe(false);
    expect(canDropFilePaths(["/docs"], "/docs/archive")).toBe(false);
    expect(canDropFilePaths(["/docs"], "/documents")).toBe(true);
  });

  it("keeps percent-encoded routes opaque for legacy non-UTF8 names", () => {
    const source = "/files/tmp/%D6%D0%CE%C4.txt";
    const target = "/files/tmp/target/";
    const data = transfer();
    writeFileDragPayload(data, [source]);
    expect(readFileDragPayload(data)).toEqual([source]);
    expect(canDropFilePaths([source], target)).toBe(true);
    expect(canDropFilePaths([source], `${source}/child`)).toBe(false);
  });

  it("ignores external drops without the internal MIME type", () => {
    expect(readFileDragPayload(transfer())).toEqual([]);
  });

  it("recovers a same-document drag when the browser drops the MIME value", () => {
    const source = {
      setData() {
        // Synthetic WebView transfers may silently discard writes.
      },
      getData() {
        return "";
      },
      types: ["text/plain", "application/x-nas-file-paths"],
      effectAllowed: "none",
    } as unknown as DataTransfer;
    writeFileDragPayload(source, ["/tmp/中文.txt"]);

    const dropTransfer = {
      getData() {
        return "";
      },
      types: ["text/plain", "application/x-nas-file-paths"],
    } as unknown as DataTransfer;
    expect(readFileDragPayload(dropTransfer)).toEqual(["/tmp/中文.txt"]);
    clearFileDragPayload();
    expect(readFileDragPayload(dropTransfer)).toEqual([]);
  });

  it("recognizes OS file drops but not this app's internal drags", () => {
    expect(isExternalFileDrag(["Files"])).toBe(true);
    expect(isExternalFileDrag(["text/uri-list"])).toBe(true);
    expect(isExternalFileDrag(["Files", "application/x-nas-file-paths"])).toBe(
      false
    );
    expect(isExternalFileDrag([])).toBe(false);
  });
});
