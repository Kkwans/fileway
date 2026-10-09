import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const owned = vi.hoisted(() => ({
  auth: { jwt: "owned-first", user: { id: 1, scope: "/owned" } },
  renew: vi.fn(),
  logout: vi.fn(),
}));
vi.mock("@/stores/auth", () => ({ useAuthStore: () => owned.auth }));
vi.mock("@/utils/auth", () => ({ renew: owned.renew, logout: owned.logout }));
vi.mock("@/utils/constants", () => ({ baseURL: "" }));

import { fetchURL } from "./utils";

function pendingResponse() {
  let finish!: (response: Response) => void;
  const promise = new Promise<Response>((resolve) => {
    finish = resolve;
  });
  vi.stubGlobal(
    "fetch",
    vi.fn(() => promise)
  );
  return finish;
}
function changeAccount() {
  owned.auth.jwt = "owned-next";
  owned.auth.user = { id: 2, scope: "/other" };
}

describe("request authentication and acknowledged writes", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    owned.auth.jwt = "owned-first";
    owned.auth.user = { id: 1, scope: "/owned" };
    owned.renew.mockResolvedValue(undefined);
  });
  afterEach(() => vi.unstubAllGlobals());

  it.each(["POST", "PUT", "PATCH", "DELETE"] as const)(
    "keeps a successful %s response when renewal fails without replaying the write",
    async (method) => {
      const response = new Response('{"id":"owned-confirmed"}', {
        status: 200,
        headers: { "X-Renew-Token": "true" },
      });
      const send = vi.fn().mockResolvedValue(response);
      vi.stubGlobal("fetch", send);
      owned.renew.mockRejectedValue(new Error("owned renewal unavailable"));
      const actual = await fetchURL("/api/owned-write", { method });
      expect(actual).toBe(response);
      expect(await actual.json()).toEqual({ id: "owned-confirmed" });
      expect(send).toHaveBeenCalledTimes(1);
      expect(owned.renew).toHaveBeenCalledTimes(1);
    }
  );

  it("retains the established read failure behavior when renewal fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("owned", {
          headers: { "X-Renew-Token": "true" },
        })
      )
    );
    owned.renew.mockRejectedValue(new Error("owned renewal unavailable"));
    await expect(fetchURL("/api/owned-read", {})).rejects.toThrow(
      "owned renewal unavailable"
    );
  });

  it("does not log out a new account because an old request returned 401", async () => {
    const finish = pendingResponse();
    const request = fetchURL("/api/old-read", {});
    changeAccount();
    finish(new Response("old account denied", { status: 401 }));
    await expect(request).rejects.toMatchObject({ status: 401 });
    expect(owned.logout).not.toHaveBeenCalled();
    expect(owned.auth.jwt).toBe("owned-next");
  });

  it("does not renew a new account because an old response requested renewal", async () => {
    const finish = pendingResponse();
    const request = fetchURL("/api/old-write", { method: "POST" });
    changeAccount();
    finish(new Response("confirmed", { headers: { "X-Renew-Token": "true" } }));
    expect(await (await request).text()).toBe("confirmed");
    expect(owned.renew).not.toHaveBeenCalled();
  });

  it("does not apply authentication side effects for an explicitly different request token", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("denied", {
          status: 401,
          headers: { "X-Renew-Token": "true" },
        })
      )
    );
    await expect(
      fetchURL("/api/other-session", { headers: { "x-auth": "owned-other" } })
    ).rejects.toMatchObject({ status: 401 });
    expect(owned.renew).not.toHaveBeenCalled();
    expect(owned.logout).not.toHaveBeenCalled();
  });

  it("still logs out the bound account on its own unauthorized response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("denied", { status: 401 }))
    );
    await expect(fetchURL("/api/current-read", {})).rejects.toMatchObject({
      status: 401,
    });
    expect(owned.logout).toHaveBeenCalledTimes(1);
  });
});
