import { beforeEach, describe, expect, it, vi } from "vitest";

const fetchJSON = vi.hoisted(() => vi.fn());
vi.mock("./utils", () => ({
  fetchJSON,
  StatusError: class StatusError extends Error {
    constructor(
      message: string,
      public status?: number
    ) {
      super(message);
    }
  },
}));

import { record } from "./recent";
import { StatusError } from "./utils";

describe("recent access wire protocol", () => {
  beforeEach(() => {
    fetchJSON.mockReset();
  });

  it("sends original opaque bytes without a potentially ambiguous display path", async () => {
    const row = {
      id: "owned",
      path: "/中文",
      wirePath: "/%D6%D0%CE%C4",
      name: "中文",
      isDir: true,
      accessedAt: 123,
    };
    fetchJSON.mockResolvedValue(row);
    await expect(record(row.path, row.wirePath)).resolves.toEqual(row);
    expect(JSON.parse(fetchJSON.mock.calls[0][1].body)).toEqual({
      wirePath: row.wirePath,
    });
  });

  it("falls back to the old JSON path protocol only when UTF-8 identity is exact", async () => {
    fetchJSON
      .mockRejectedValueOnce(new StatusError("old server", 400))
      .mockResolvedValueOnce({ id: "owned" });
    await record("/100% +?#/a%2Fb");
    expect(JSON.parse(fetchJSON.mock.calls[0][1].body)).toEqual({
      wirePath: "/100%25%20%2B%3F%23/a%252Fb",
    });
    expect(JSON.parse(fetchJSON.mock.calls[1][1].body)).toEqual({
      path: "/100% +?#/a%2Fb",
    });
  });

  it("does not turn opaque paths into a different display-name record on old servers", async () => {
    fetchJSON.mockRejectedValue(new StatusError("old server", 400));
    await expect(record("/中文", "/%D6%D0%CE%C4")).rejects.toThrow(
      "该次访问未同步"
    );
    expect(fetchJSON).toHaveBeenCalledTimes(1);
  });

  it("does not retry an unauthorized visit as a legacy write", async () => {
    fetchJSON.mockRejectedValue(new StatusError("forbidden", 403));
    await expect(record("/private")).rejects.toThrow("forbidden");
    expect(fetchJSON).toHaveBeenCalledTimes(1);
  });
});
