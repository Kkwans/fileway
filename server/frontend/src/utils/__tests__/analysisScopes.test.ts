import { describe, expect, it } from "vitest";
import {
  addAnalysisScope,
  analysisScopesFromQuery,
  normalizeAnalysisScope,
} from "../analysisScopes";

describe("analysis scopes", () => {
  it("normalizes paths without changing Linux case semantics", () => {
    expect(normalizeAnalysisScope(" docs//Report ")).toBe("/docs/Report");
    expect(normalizeAnalysisScope("/docs/report")).toBe("/docs/report");
  });

  it("removes redundant descendants and keeps similar prefixes separate", () => {
    expect(addAnalysisScope(["/docs/report", "/docs-old"], "/docs")).toEqual([
      "/docs-old",
      "/docs",
    ]);
    expect(addAnalysisScope(["/docs"], "/docs/report")).toEqual(["/docs"]);
  });

  it("accepts repeated query parameters without duplicate scans", () => {
    expect(
      analysisScopesFromQuery(["/photos/2025", "/photos", "/Photos"])
    ).toEqual(["/photos", "/Photos"]);
  });
});
