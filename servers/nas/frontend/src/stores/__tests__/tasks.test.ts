import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { TaskItem } from "@/api/tasks";
import { useTasksStore } from "../tasks";

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
  cancel: vi.fn(),
  retry: vi.fn(),
  archive: vi.fn(),
  unarchive: vi.fn(),
  batch: vi.fn(),
  removeAllRecords: vi.fn(),
}));

vi.mock("@/api/tasks", () => mocks);

const task = (
  id: string,
  status: TaskItem["status"],
  createdAt = 1
): TaskItem => ({
  id,
  userId: 7,
  ownerName: "owner",
  type: "trash.clear",
  title: "清空回收站",
  status,
  createdAt,
  totalItems: 2,
  processedItems: status === "completed" ? 2 : 0,
  totalBytes: 0,
  processedBytes: 0,
});

describe("tasks store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    for (const mock of Object.values(mocks)) mock.mockReset();
  });

  it("loads and orders recorded tasks", async () => {
    mocks.list.mockResolvedValue({
      items: [task("older", "completed", 1)],
      total: 1,
      counts: {
        all: 1,
        active: 0,
        attention: 0,
        canceled: 0,
        completed: 1,
        archived: 0,
      },
      owners: ["owner"],
    });
    const store = useTasksStore();
    await store.load();
    store.record(task("newer", "running", 2));

    expect(store.items.map((item) => item.id)).toEqual(["newer", "older"]);
    expect(store.activeItems.map((item) => item.id)).toEqual(["newer"]);
  });

  it("replays events received while a snapshot is in flight", async () => {
    let resolveList!: (value: unknown) => void;
    mocks.list.mockReturnValue(
      new Promise((resolve) => {
        resolveList = resolve;
      })
    );
    const store = useTasksStore();
    const loading = store.load();

    store.record(task("live", "running", 2));
    resolveList({
      items: [task("older", "completed", 1)],
      total: 1,
      counts: {
        all: 1,
        active: 0,
        attention: 0,
        canceled: 0,
        completed: 1,
        archived: 0,
      },
      owners: ["owner"],
    });
    await loading;

    expect(store.items.map((item) => item.id)).toEqual(["live", "older"]);
    expect(store.activeItems.map((item) => item.id)).toEqual(["live"]);
  });

  it("waits until the backend reports a terminal state", async () => {
    vi.useFakeTimers();
    try {
      mocks.get
        .mockResolvedValueOnce(task("clear", "running"))
        .mockResolvedValueOnce(task("clear", "completed"));
      const store = useTasksStore();
      const waiting = store.waitForTerminal("clear", 10);
      await vi.advanceTimersByTimeAsync(10);

      await expect(waiting).resolves.toMatchObject({ status: "completed" });
      expect(store.items[0].status).toBe("completed");
    } finally {
      vi.useRealTimers();
    }
  });

  it("records explicit retries as new tasks", async () => {
    mocks.retry.mockResolvedValue(task("retry", "queued", 3));
    const store = useTasksStore();

    await store.retry("failed");

    expect(mocks.retry).toHaveBeenCalledWith("failed");
    expect(store.items[0].id).toBe("retry");
  });

  it("clears terminal records for one task category", async () => {
    mocks.removeAllRecords.mockResolvedValue(1);
    const store = useTasksStore();
    store.items = [
      task("file", "completed"),
      { ...task("running", "running"), type: "file.copy" },
    ];
    store.items[0].type = "file.copy";

    await store.clearRecords("file");

    expect(mocks.removeAllRecords).toHaveBeenCalledWith("file");
    expect(store.items.map((item) => item.id)).toEqual(["running"]);
  });
});
