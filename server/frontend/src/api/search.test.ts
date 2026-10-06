import { beforeEach, describe, expect, it, vi } from "vitest";

const fetchURL = vi.hoisted(() => vi.fn());
vi.mock("./utils", () => ({
  fetchURL,
  StatusError: class StatusError extends Error {
    constructor(
      message: string,
      public status?: number,
      public is_canceled?: boolean
    ) {
      super(message);
      this.name = "StatusError";
    }
  },
}));

import search from "./search";

describe("search stream contract", () => {
  beforeEach(() => fetchURL.mockReset());

  it("rejects a stream that ends before its summary", async () => {
    fetchURL.mockResolvedValue(
      new Response(
        `${JSON.stringify({
          type: "result",
          item: {
            dir: false,
            path: "report.md",
            name: "report.md",
            size: 1,
            modified: "2026-08-07T00:00:00Z",
          },
        })}\n`
      )
    );

    await expect(
      search(
        "/docs",
        "report",
        "current",
        new AbortController().signal,
        () => {}
      )
    ).rejects.toThrow("搜索连接在完成摘要前中断");
  });

  it("accepts a completed stream with an explicit summary", async () => {
    fetchURL.mockResolvedValue(
      new Response(
        `${JSON.stringify({ type: "summary", reason: "completed", count: 0 })}\n`
      )
    );

    await expect(
      search(
        "/docs",
        "report",
        "current",
        new AbortController().signal,
        () => {}
      )
    ).resolves.toEqual({ reason: "completed", count: 0 });
  });
});
