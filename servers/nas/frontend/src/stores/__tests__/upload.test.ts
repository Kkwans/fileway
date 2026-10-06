import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useUploadStore } from "../upload";

const mocks = vi.hoisted(() => ({
  post: vi.fn(),
  abortAllUploads: vi.fn(),
  uploadTransferId: vi.fn(() => "upload-live"),
  loading: vi.fn(),
  success: vi.fn(),
}));

vi.mock("@/api", () => ({ files: { post: mocks.post } }));
vi.mock("@/api/utils", () => ({
  removePrefix: (path: string) => path.replace(/^\/files/, ""),
}));
vi.mock("@/stores/file", () => ({
  useFileStore: () => ({ reload: false }),
}));
vi.mock("@/api/tus", () => ({
  abortAllUploads: mocks.abortAllUploads,
  uploadTransferId: mocks.uploadTransferId,
}));
vi.mock("@/utils/buttons", () => ({
  default: {
    loading: mocks.loading,
    success: mocks.success,
  },
}));

describe("upload store live metrics", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.useFakeTimers();
    vi.setSystemTime(1_000);
    vi.stubGlobal("window", {
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      setInterval: (...args: Parameters<typeof setInterval>) =>
        setInterval(...args),
      clearInterval: (id: ReturnType<typeof setInterval>) => clearInterval(id),
    });
    for (const mock of Object.values(mocks)) mock.mockClear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("publishes per-file and aggregate progress from one sampled source", async () => {
    let reportProgress: ((event: { loaded: number }) => void) | undefined;
    mocks.post.mockImplementation(
      (
        _path: string,
        _file: File,
        _overwrite: boolean,
        onUpload: (event: { loaded: number }) => void
      ) => {
        reportProgress = onUpload;
        return new Promise(() => {});
      }
    );

    const store = useUploadStore();
    const file = new File([new Uint8Array(2_000)], "movie.bin");
    store.upload("/files/movie.bin", file.name, file, false, "blob");

    reportProgress?.({ loaded: 1_000 });
    await vi.advanceTimersByTimeAsync(500);

    const active = Array.from(store.activeUploads)[0];
    expect(active.transferId).toBe("upload-live");
    expect(active.sentBytes).toBe(1_000);
    expect(active.speedBytesPerSecond).toBe(2_000);
    expect(store.sentBytes).toBe(1_000);
    expect(store.speedBytesPerSecond).toBe(2_000);
    expect(store.etaSeconds).toBe(0.5);
  });
});
