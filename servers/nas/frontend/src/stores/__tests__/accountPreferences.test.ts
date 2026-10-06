import { createPinia, setActivePinia } from "pinia";
import { beforeEach, expect, it, vi } from "vitest";
import { users } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { useAccountPreferencesStore } from "@/stores/accountPreferences";
import type { IUser } from "@/types/user";
vi.mock("@/api", () => ({ users: { update: vi.fn() } }));
beforeEach(() => {
  setActivePinia(createPinia());
  vi.clearAllMocks();
});
it("serializes writes and preserves unrelated player preferences", async () => {
  const auth = useAuthStore();
  auth.setUser({ id: 1, playerPreferences: { resumeMinSec: 20 } } as IUser);
  let finish!: () => void;
  vi.mocked(users.update)
    .mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        })
    )
    .mockResolvedValueOnce(undefined);
  const store = useAccountPreferencesStore();
  const first = store.save({ playerPreferences: { playbackRate: 1.5 } });
  const second = store.save({ singleClick: true });
  await vi.waitFor(() => expect(users.update).toHaveBeenCalledTimes(1));
  finish();
  await Promise.all([first, second]);
  expect(auth.user?.playerPreferences).toEqual({
    resumeMinSec: 20,
    playbackRate: 1.5,
  });
  expect(auth.user?.singleClick).toBe(true);
});
it("does not mutate confirmed state on failure and permits retry", async () => {
  const auth = useAuthStore();
  auth.setUser({ id: 1, singleClick: false } as IUser);
  vi.mocked(users.update)
    .mockRejectedValueOnce(new Error("offline"))
    .mockResolvedValueOnce(undefined);
  const store = useAccountPreferencesStore();
  await expect(store.save({ singleClick: true })).rejects.toThrow("offline");
  expect(auth.user?.singleClick).toBe(false);
  await store.save({ singleClick: true });
  expect(auth.user?.singleClick).toBe(true);
});
it("discards old responses and queued writes after an account change", async () => {
  const auth = useAuthStore();
  auth.setUser({ id: 1 } as IUser);
  let finish!: () => void;
  vi.mocked(users.update).mockImplementationOnce(
    () =>
      new Promise<void>((resolve) => {
        finish = resolve;
      })
  );
  const store = useAccountPreferencesStore();
  const first = store.save({ singleClick: true });
  const second = store.save({ dateFormat: true });
  const rejected = expect(second).rejects.toThrow("账号已切换");
  await vi.waitFor(() => expect(users.update).toHaveBeenCalledTimes(1));
  auth.setUser({ id: 2, singleClick: false } as IUser);
  finish();
  await first;
  await rejected;
  expect(auth.user).toEqual({ id: 2, singleClick: false });
  expect(users.update).toHaveBeenCalledTimes(1);
});
