import { useAuthStore } from "@/stores/auth";
import { renew, logout } from "@/utils/auth";
import { baseURL } from "@/utils/constants";
import { encodePath } from "@/utils/url";
import type { ApiOpts } from "@/types/api";

export class StatusError extends Error {
  constructor(
    message: string,
    public status?: number,
    public is_canceled?: boolean
  ) {
    super(message);
    this.name = "StatusError";
  }
}

export async function fetchURL(
  url: string,
  opts: ApiOpts,
  auth = true
): Promise<Response> {
  const authStore = useAuthStore();

  opts = opts || {};
  opts.headers = opts.headers || {};

  const { headers, ...rest } = opts;
  const requestHeaders = new Headers(headers as HeadersInit);
  if (!requestHeaders.has("X-Auth"))
    requestHeaders.set("X-Auth", authStore.jwt);
  const requestToken = requestHeaders.get("X-Auth");
  const owner = () =>
    JSON.stringify(
      authStore.user ? [authStore.user.id, authStore.user.scope] : null
    );
  const requestOwner = owner();
  const currentRequest = () =>
    authStore.jwt === requestToken && owner() === requestOwner;
  let res;
  try {
    res = await fetch(`${baseURL}${url}`, {
      headers: requestHeaders,
      ...rest,
    });
  } catch (e) {
    // Check if the error is an intentional cancellation
    if (e instanceof Error && e.name === "AbortError") {
      throw new StatusError("000 No connection", 0, true);
    }
    throw new StatusError("000 No connection", 0);
  }

  if (auth && res.headers.get("X-Renew-Token") === "true" && currentRequest()) {
    try {
      await renew(requestToken!, currentRequest);
    } catch (error) {
      // The server has already accepted this write. Losing its response can
      // cause duplicate task creation or another destructive submission.
      const acknowledgedWrite = res.ok && (rest.method ?? "GET") !== "GET";
      if (!acknowledgedWrite) throw error;
    }
  }

  if (res.status < 200 || res.status > 299) {
    const body = await res.text();
    const error = new StatusError(
      body || `${res.status} ${res.statusText}`,
      res.status
    );

    if (auth && res.status == 401 && currentRequest()) {
      logout();
    }

    throw error;
  }

  return res;
}

export async function fetchJSON<T>(url: string, opts?: ApiOpts): Promise<T> {
  const res = await fetchURL(url, opts ?? {});

  if (res.status === 200) {
    return res.json() as Promise<T>;
  }

  throw new StatusError(`${res.status} ${res.statusText}`, res.status);
}

export function removePrefix(url: string): string {
  url = url.split("/").splice(2).join("/");

  if (url === "") url = "/";
  if (url[0] !== "/") url = "/" + url;
  return url;
}

export function createURL(endpoint: string, searchParams = {}): string {
  let prefix = baseURL;
  if (!prefix.endsWith("/")) {
    prefix = prefix + "/";
  }
  const url = new URL(prefix + encodePath(endpoint), origin);
  url.search = new URLSearchParams(searchParams).toString();

  return url.toString();
}

export function setSafeTimeout(callback: () => void, delay: number): number {
  const MAX_DELAY = 86_400_000;
  let remaining = delay;

  function scheduleNext(): number {
    if (remaining <= MAX_DELAY) {
      return window.setTimeout(callback, remaining);
    } else {
      return window.setTimeout(() => {
        remaining -= MAX_DELAY;
        scheduleNext();
      }, MAX_DELAY);
    }
  }

  return scheduleNext();
}
