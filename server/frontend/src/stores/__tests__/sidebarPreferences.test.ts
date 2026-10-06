import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { users } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { useSidebarPreferencesStore } from "@/stores/sidebarPreferences";
import type { IUser } from "@/types/user";

vi.mock("@/api", () => ({
  users: {
    get: vi.fn(),
    update: vi.fn(),
  },
}));

const mockedUsers = vi.mocked(users);

describe("sidebar preferences store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it("loads preferences from the current user record", async () => {
    const authStore = useAuthStore();
    authStore.setUser({ id: 7, username: "admin" } as IUser);
    mockedUsers.get.mockResolvedValue({
      id: 7,
      username: "admin",
      sidebarPreferences: JSON.stringify({
        moduleOrder: ["user", "favorites", "system-options"],
      }),
    } as IUser);

    const store = useSidebarPreferencesStore();
    await store.load();

    expect(store.moduleOrder.slice(0, 3)).toEqual([
      "user",
      "favorites",
      "system-options",
    ]);
  });

  it("persists reordered modules on the current user", async () => {
    const authStore = useAuthStore();
    authStore.setUser({ id: 7, username: "admin" } as IUser);
    mockedUsers.get.mockResolvedValue({
      id: 7,
      username: "admin",
      sidebarPreferences: "",
    } as IUser);
    mockedUsers.update.mockResolvedValue(undefined);

    const store = useSidebarPreferencesStore();
    await store.load();
    await store.reorder(
      "moduleOrder",
      ["user", "system-options", "favorites"],
      "favorites",
      "system-options"
    );

    expect(mockedUsers.update).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 7,
        sidebarPreferences: expect.stringContaining(
          '"moduleOrder":["user","favorites","system-options"]'
        ),
      }),
      ["sidebarPreferences"]
    );
  });

  it("persists the desktop icon rail state on the current user", async () => {
    const authStore = useAuthStore();
    authStore.setUser({ id: 7, username: "admin" } as IUser);
    mockedUsers.get.mockResolvedValue({
      id: 7,
      username: "admin",
      sidebarPreferences: "",
    } as IUser);
    mockedUsers.update.mockResolvedValue(undefined);

    const store = useSidebarPreferencesStore();
    await store.load();
    await store.setDesktopCollapsed(true);

    expect(store.desktopCollapsed).toBe(true);
    expect(mockedUsers.update).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 7,
        sidebarPreferences: expect.stringContaining('"desktopCollapsed":true'),
      }),
      ["sidebarPreferences"]
    );
  });

  it("rolls back the icon rail state when persistence fails", async () => {
    const authStore = useAuthStore();
    authStore.setUser({ id: 7, username: "admin" } as IUser);
    mockedUsers.get.mockResolvedValue({
      id: 7,
      username: "admin",
      sidebarPreferences: "",
    } as IUser);
    mockedUsers.update.mockRejectedValue(new Error("offline"));

    const store = useSidebarPreferencesStore();
    await store.load();

    await expect(store.setDesktopCollapsed(true)).rejects.toThrow("offline");
    expect(store.desktopCollapsed).toBe(false);
    expect(authStore.user?.sidebarPreferences).toContain(
      '"desktopCollapsed":false'
    );
  });
});
