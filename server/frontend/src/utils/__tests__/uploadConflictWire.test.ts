import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { IUser } from "@/types/user";
import { useAuthStore } from "@/stores/auth";
const mocks = vi.hoisted(() => ({
  fetchAll: vi.fn(),
  fetchBatch: vi.fn(),
  fetchMetadata: vi.fn(),
}));
vi.mock("@/api", () => ({ files: mocks }));
vi.mock("@/stores/upload", () => ({ useUploadStore: vi.fn() }));
vi.mock("@/api/utils", () => ({
  removePrefix: (path: string) => path.replace(/^\/files/, ""),
}));
import { checkConflict } from "../upload";
const move = (wire: string, index = 0, isDir = false) => ({
  from: `/files/source/${index}`,
  to: `/files${wire}`,
  name: "中文",
  size: 9,
  modified: "2026-01-01T00:00:00Z",
  isDir,
  overwrite: false,
  rename: false,
});
const row = (wirePath: string, size: number, isDir = false) => {
  let path = "/中/中.txt";
  try {
    path = decodeURIComponent(wirePath);
  } catch {}
  return {
    path,
    wirePath,
    status: 200,
    item: {
      path,
      wirePath,
      name: path.split("/").at(-1),
      size,
      isDir,
      modified: "2026-01-02T00:00:00Z",
    },
  };
};
describe("wire conflict metadata preflight", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    useAuthStore().setUser({ id: 1, scope: "/one" } as IUser);
    mocks.fetchAll.mockResolvedValue([]);
  });
  it("same-display opaque and UTF8 targets compare their own metadata and keep source indices", async () => {
    const wires = ["/%D6%D0/%D6%D0.txt", "/%D6%D0/%E4%B8%AD.txt"];
    mocks.fetchBatch.mockResolvedValue([row(wires[0], 2), row(wires[1], 7)]);
    const conflicts = await checkConflict(
      wires.map((wire, index) => move(wire, index)),
      "/files/%D6%D0/"
    );
    expect(
      conflicts.map((conflict) => [conflict.index, conflict.dest.size])
    ).toEqual([
      [0, 2],
      [1, 7],
    ]);
    expect(mocks.fetchBatch.mock.calls[0][2]).toEqual(wires);
    expect(mocks.fetchAll).not.toHaveBeenCalled();
  });
  it("existing local upload directories merge while each nested leaf is checked", async () => {
    const base = "/%D6%D0";
    const wires = [base + "/folder", base + "/folder/leaf.txt"];
    mocks.fetchBatch.mockResolvedValue([
      row(wires[0], 0, true),
      row(wires[1], 3),
    ]);
    const inputs = [
      { name: "folder", fullPath: "folder", isDir: true, size: 0 },
      { name: "leaf.txt", fullPath: "folder/leaf.txt", isDir: false, size: 8 },
    ];
    const conflicts = await checkConflict(inputs, `/files${base}/`);
    expect(conflicts.map((conflict) => conflict.index)).toEqual([1]);
    expect(conflicts[0].dest.size).toBe(3);
  });
  it("a remote directory target collision is reported at top level rather than pretending child merge", async () => {
    mocks.fetchBatch.mockResolvedValue([row("/dest/folder", 0, true)]);
    mocks.fetchAll.mockResolvedValue([
      {
        path: "/dest/folder",
        name: "folder",
        size: 0,
        modified: "",
        isDir: true,
      },
    ]);
    const conflicts = await checkConflict(
      [move("/dest/folder", 0, true)],
      "/files/dest/"
    );
    expect(conflicts).toHaveLength(1);
    expect(conflicts[0].index).toBe(0);
  });
  it.each([403, 500])(
    "status %i is a failure, never a non-existent directory",
    async (status) => {
      mocks.fetchBatch.mockResolvedValue([
        {
          path: "/dest/file",
          wirePath: "/dest/file",
          status,
          error: "owned-read-denial",
        },
      ]);
      await expect(
        checkConflict([move("/dest/file")], "/files/dest/")
      ).rejects.toThrow();
    }
  );
  it("type mismatch and a wrong wire ACK fail closed", async () => {
    mocks.fetchBatch.mockResolvedValue([row("/dest/file", 0, true)]);
    await expect(
      checkConflict([move("/dest/file")], "/files/dest/")
    ).rejects.toThrow();
    mocks.fetchBatch.mockResolvedValue([row("/dest/other", 2)]);
    await expect(
      checkConflict([move("/dest/file")], "/files/dest/")
    ).rejects.toThrow();
  });
  it("125 recursive leaf targets are chunked without dropping entries", async () => {
    mocks.fetchBatch.mockImplementation(
      async (_paths: string[], _signal: unknown, wires: string[]) =>
        wires.map((wire) => ({ path: wire, wirePath: wire, status: 404 }))
    );
    const inputs = Array.from({ length: 125 }, (_, index) => ({
      name: `${index}.txt`,
      fullPath: `nested/${index}.txt`,
      isDir: false,
      size: 1,
    }));
    expect(await checkConflict(inputs, "/files/%D6%D0/")).toEqual([]);
    expect(mocks.fetchBatch).toHaveBeenCalledTimes(2);
    expect(
      mocks.fetchBatch.mock.calls.every((call) => call[2].length <= 100)
    ).toBe(true);
    expect(mocks.fetchBatch.mock.calls.flatMap((call) => call[2])).toHaveLength(
      125
    );
  });
  it("late preflight from another scope is rejected", async () => {
    let finish!: (value: unknown) => void;
    const gate = new Promise((resolve) => {
      finish = resolve;
    });
    mocks.fetchAll.mockReturnValue(gate);
    mocks.fetchBatch.mockReturnValue(gate);
    const pending = checkConflict([move("/dest/file")], "/files/dest/");
    useAuthStore().setUser({ id: 1, scope: "/two" } as IUser);
    finish([]);
    await expect(pending).rejects.toThrow("来源");
  });
  it("missing opaque ACK and malformed item metadata never turn into an empty conflict list", async () => {
    mocks.fetchBatch.mockResolvedValue([{ path: "/中/中.txt", status: 404 }]);
    await expect(
      checkConflict([move("/%D6%D0/%D6%D0.txt")], "/files/%D6%D0/")
    ).rejects.toThrow();
    mocks.fetchBatch.mockResolvedValue([
      {
        ...row("/dest/file", 2),
        item: { ...row("/dest/file", 2).item, isDir: undefined },
      },
    ]);
    await expect(
      checkConflict([move("/dest/file")], "/files/dest/")
    ).rejects.toThrow("格式");
  });
  it("local literal percent names in a real /files directory encode exactly once", async () => {
    mocks.fetchBatch.mockImplementation(
      async (_paths: string[], _signal: unknown, wires: string[]) =>
        wires.map((wire) => ({
          path: "/files/literal%2F.txt",
          wirePath: wire,
          status: 404,
        }))
    );
    expect(
      await checkConflict(
        [
          {
            name: "literal%2F.txt",
            fullPath: "literal%2F.txt",
            isDir: false,
            size: 1,
          },
        ],
        "/files/files/"
      )
    ).toEqual([]);
    expect(mocks.fetchBatch.mock.calls[0][2]).toEqual([
      "/files/literal%252F.txt",
    ]);
  });
});
