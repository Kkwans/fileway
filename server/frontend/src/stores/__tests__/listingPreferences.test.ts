import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { users } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { useListingPreferencesStore } from "@/stores/listingPreferences";
import type { IUser } from "@/types/user";

vi.mock("@/api", () => ({
  users: { update: vi.fn() },
}));

const mockedUsers = vi.mocked(users);

describe("listing preferences store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it("persists a prefix state to the current user", async () => {
    const authStore = useAuthStore();
    authStore.setUser({ id: 7, username: "admin" } as IUser);
    mockedUsers.update.mockResolvedValue(undefined);
    const store = useListingPreferencesStore();

    await store.updateRule("@", { expanded: false });

    expect(
      store.prefixRules.find((rule) => rule.prefix === "@")?.expanded
    ).toBe(false);
    expect(mockedUsers.update).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 7,
        listingPreferences: expect.objectContaining({ version: 1 }),
      }),
      ["listingPreferences"]
    );
  });

  it("rolls back the current state when persistence fails", async () => {
    const authStore = useAuthStore();
    authStore.setUser({ id: 7, username: "admin" } as IUser);
    mockedUsers.update.mockRejectedValue(new Error("offline"));
    const store = useListingPreferencesStore();

    await expect(store.updateRule("@", { visible: false })).rejects.toThrow(
      "offline"
    );
    expect(store.prefixRules.find((rule) => rule.prefix === "@")?.visible).toBe(
      true
    );
  });

  it("rolls consecutive failures back to the last server-confirmed snapshot", async () => {
    const authStore = useAuthStore();
    authStore.setUser({ id: 7, username: "admin" } as IUser);
    mockedUsers.update.mockRejectedValue(new Error("offline"));
    const store = useListingPreferencesStore();

    const first = store.updateRule("@", { visible: false });
    const second = store.updateRule("#", { expanded: false });
    const results = await Promise.allSettled([first, second]);

    expect(results.every((result) => result.status === "rejected")).toBe(true);
    expect(store.prefixRules.find((rule) => rule.prefix === "@")?.visible).toBe(
      true
    );
    expect(
      store.prefixRules.find((rule) => rule.prefix === "#")?.expanded
    ).toBe(true);
  });

  it("keeps the last successful write when the next queued write fails", async () => {
    const authStore = useAuthStore();
    authStore.setUser({ id: 7, username: "admin" } as IUser);
    mockedUsers.update
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error("offline"));
    const store = useListingPreferencesStore();

    const first = store.updateRule("@", { visible: false });
    const second = store.updateRule("#", { expanded: false });
    const results = await Promise.allSettled([first, second]);

    expect(results.map((result) => result.status)).toEqual([
      "fulfilled",
      "rejected",
    ]);
    expect(store.prefixRules.find((rule) => rule.prefix === "@")?.visible).toBe(
      false
    );
    expect(
      store.prefixRules.find((rule) => rule.prefix === "#")?.expanded
    ).toBe(true);
  });
});
