import { describe, expect, it } from "vitest";
import { getLoginTitle, getLogoutReasonText } from "../login";

describe("login copy", () => {
  it("replaces the upstream default name with a Chinese title", () => {
    expect(getLoginTitle("File Browser")).toBe(
      "NAS \u6587\u4ef6\u6d4f\u89c8\u5668"
    );
    expect(getLoginTitle("")).toBe("NAS \u6587\u4ef6\u6d4f\u89c8\u5668");
  });

  it("keeps a configured custom name", () => {
    expect(getLoginTitle("\u5bb6\u5ead\u6570\u636e\u4e2d\u5fc3")).toBe(
      "\u5bb6\u5ead\u6570\u636e\u4e2d\u5fc3"
    );
  });

  it("returns fixed Chinese copy for known logout reasons", () => {
    expect(getLogoutReasonText("unknown")).toBe(
      "\u60a8\u5df2\u9000\u51fa\u767b\u5f55"
    );
    expect(getLogoutReasonText("logout")).toBe(
      "\u5df2\u6210\u529f\u9000\u51fa\u767b\u5f55"
    );
    expect(getLogoutReasonText("expired")).toBe(
      "\u4f1a\u8bdd\u5df2\u8fc7\u671f\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55"
    );
    expect(getLogoutReasonText("inactivity")).toBe(
      "\u957f\u65f6\u95f4\u672a\u64cd\u4f5c\uff0c\u767b\u5f55\u72b6\u6001\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55"
    );
  });

  it("does not expose an unknown upstream reason", () => {
    expect(getLogoutReasonText("upstream-code")).toBe(
      "\u8bf7\u91cd\u65b0\u767b\u5f55"
    );
    expect(getLogoutReasonText(null)).toBe("");
  });
});
