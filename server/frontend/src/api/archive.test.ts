import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ fetchJSON: vi.fn(), fetchURL: vi.fn() }));
vi.mock("./utils", () => mocks);
import { entries, extract } from "./archive";

describe("archive raw path protocol", () => {
  beforeEach(() => {
    mocks.fetchJSON.mockReset().mockResolvedValue({ entries: [] });
    mocks.fetchURL.mockReset();
  });

  it("passes original archive bytes as a query value without double decoding", async () => {
    const wire = "/%D6%D0%CE%C4%20%2B%25%3F%23.zip";
    await entries("/中文 +%?#.zip", wire);
    const request = new URL(
      mocks.fetchJSON.mock.calls[0][0],
      "https://fixture.invalid"
    );
    expect(request.pathname).toBe("/api/archives/entries");
    expect(request.searchParams.get("wirePath")).toBe(wire);
    expect(request.searchParams.has("path")).toBe(false);
  });

  it("keeps legacy UTF-8 calls compatible without guessing lost paths", async () => {
    await entries("/100% +?#.zip");
    const request = new URL(
      mocks.fetchJSON.mock.calls[0][0],
      "https://fixture.invalid"
    );
    expect(request.searchParams.get("path")).toBe("/100% +?#.zip");
    expect(() => entries("/lost�.zip")).toThrow("原始路径无法确认");
  });

  it("preserves source destination and selected relative wire identities in extraction", async () => {
    const request = {
      archivePath: "/中文.zip",
      archiveWirePath: "/%D6%D0%CE%C4.zip",
      destination: "/目录 ",
      destinationWirePath: "/%C4%BF%C2%BC%20",
      selected: ["中文.txt", "a%2Fb"],
      selectedWirePaths: ["%D6%D0%CE%C4.txt", "a%252Fb"],
    };
    const task = {
      id: "owned-task",
      type: "archive.extract",
      status: "queued",
    };
    mocks.fetchURL.mockResolvedValue(
      new Response(JSON.stringify(task), { status: 202 })
    );
    await expect(extract(request)).resolves.toEqual(task);
    expect(mocks.fetchURL.mock.calls[0][0]).toBe("/api/archives/extractions");
    expect(JSON.parse(mocks.fetchURL.mock.calls[0][1].body)).toEqual(request);
  });
});
