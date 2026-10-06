import { useAuthStore } from "@/stores/auth";
import { useFavoritesStore } from "@/stores/favorites";
import { useLayoutStore } from "@/stores/layout";
import { useTagsStore } from "@/stores/tags";
import { useRecentStore } from "@/stores/recent";
import { baseURL } from "@/utils/constants";
import {
  upload as postTus,
  uploadBatchHeaders,
  uploadTransferId,
  useTus,
} from "./tus";
import { createURL, fetchURL, removePrefix, StatusError } from "./utils";
import type { ApiMethod, ApiOpts, ApiContent, ChecksumAlg } from "@/types/api";
import type { TrashItem } from "./trash";
import type { TaskItem } from "./tasks";
import { isEncodableResponse, makeRawResource } from "@/utils/encodings";
import type {
  Resource,
  ResourceItem,
  RecursiveEntry,
  BatchResourceResult,
  DownloadFormat,
} from "@/types/file";
import urlUtils from "@/utils/url";
import * as transfersApi from "./transfers";

export interface BatchRenameItem {
  from: string;
  to: string;
}

export interface BatchRenameResultItem extends BatchRenameItem {
  status: "ready" | "completed" | "error";
  error?: string;
}

export interface BatchRenameResult {
  valid: boolean;
  executed: boolean;
  items: BatchRenameResultItem[];
  error?: string;
}

export async function fetch(url: string, signal?: AbortSignal) {
  const encoding = isEncodableResponse(url);
  url = removePrefix(url);
  const res = await fetchURL(`/api/resources${url}`, {
    signal,
    headers: {
      "X-Encoding": encoding ? "true" : "false",
    },
  });

  let data: Resource;
  try {
    if (res.headers.get("Content-Type") == "application/octet-stream") {
      data = await makeRawResource(res, url);
    } else {
      data = (await res.json()) as Resource;
    }
  } catch (e) {
    // Check if the error is an intentional cancellation
    if (e instanceof Error && e.name === "AbortError") {
      throw new StatusError("000 No connection", 0, true);
    }
    throw e;
  }
  data.url = `/files${data.wirePath ?? url}`;

  if (data.isDir) {
    if (!data.url.endsWith("/")) data.url += "/";
    // Perhaps change the any
    data.items = data.items.map((item: ResourceItem, index: number) => {
      item.index = index;
      item.url = item.wirePath
        ? `/files${item.wirePath}`
        : `${data.url}${encodeURIComponent(item.name)}`;

      if (item.isDir) {
        item.url += "/";
      }

      return item;
    });
  }

  return data;
}

/** Fetch only the target's metadata without listing a directory or reading
 * file content. The created field is optional and is omitted by filesystems
 * that do not expose a trustworthy birth time. */
export async function fetchMetadata(
  url: string,
  signal?: AbortSignal
): Promise<Resource> {
  const normalized = removePrefix(url);
  const res = await fetchURL(`/api/resources${normalized}?metadata=1`, {
    signal,
  });
  const data = (await res.json()) as Resource;
  data.url = `/files${data.wirePath ?? normalized}`;
  if (data.isDir && !data.url.endsWith("/")) data.url += "/";
  return data;
}

export async function fetchAll(url: string): Promise<RecursiveEntry[]> {
  url = removePrefix(url);
  const res = await fetchURL(`/api/resources/recursive${url}`, {});
  return (await res.json()) as RecursiveEntry[];
}

export async function fetchBatch(
  paths: string[],
  signal?: AbortSignal
): Promise<BatchResourceResult[]> {
  const res = await fetchURL("/api/resources/batch", {
    method: "POST",
    signal,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ paths }),
  });
  const results = (await res.json()) as BatchResourceResult[];
  return results.map((result, index) => {
    if (!result.item) return result;
    const item = result.item;
    item.index = index;
    item.url = `/files${item.wirePath ?? urlUtils.encodePath(item.path)}${item.isDir ? "/" : ""}`;
    return { ...result, item };
  });
}

async function resourceAction(
  url: string,
  method: ApiMethod,
  content?: ApiContent
) {
  url = removePrefix(url);

  const opts: ApiOpts = {
    method,
  };

  if (content) {
    opts.body = content;
  }

  const res = await fetchURL(`/api/resources${url}`, opts);

  return res;
}

export async function remove(
  url: string,
  mode: "trash" | "permanent" = "permanent"
): Promise<TrashItem | null> {
  const removedPath = removePrefix(url);
  const response = await fetchURL(
    `/api/resources${removedPath}?mode=${encodeURIComponent(mode)}`,
    { method: "DELETE" }
  );
  useFavoritesStore().applyPathRemoval(removedPath);
  useTagsStore().applyPathRemoval(removedPath);
  useRecentStore().applyPathRemoval(removedPath);
  if (mode === "trash") return (await response.json()) as TrashItem;
  return null;
}

export async function schedulePermanentDeletion(
  paths: string[]
): Promise<TaskItem> {
  const response = await fetchURL("/api/deletions/pending", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ kind: "resources", paths }),
  });
  return (await response.json()) as TaskItem;
}

export async function put(url: string, content: ApiContent = "") {
  return resourceAction(url, "PUT", content);
}

/**
 * Creates a new resource without ever overwriting an existing path.
 * The backend performs the final existence check atomically; callers may
 * retry a 409 response with a different filename.
 */
export async function postExclusive(path: string, content: Blob) {
  const normalized = path.startsWith("/") ? path : `/${path}`;
  return fetchURL(
    `/api/resources${urlUtils.encodePath(normalized)}?override=false`,
    {
      method: "POST",
      headers: {
        "Content-Type": content.type || "application/octet-stream",
      },
      body: content,
    }
  );
}

export function download(format: DownloadFormat, ...files: string[]) {
  let url = `${baseURL}/api/raw`;
  const transferId = downloadTransferId();

  if (files.length === 1) {
    url += removePrefix(files[0]) + "?";
  } else {
    let arg = "";

    for (const file of files) {
      arg += removePrefix(file) + ",";
    }

    arg = arg.substring(0, arg.length - 1);
    arg = encodeURIComponent(arg);
    url += `/?files=${arg}&`;
  }

  if (format) {
    url += `algo=${format}&`;
  }
  url += `transfer=${encodeURIComponent(transferId)}&`;

  const target = files.length === 1 ? removePrefix(files[0]) : files.join(",");
  const name =
    files.length === 1 ? files[0].split("/").pop() || target : "下载文件";
  // Public-share callers use a different API module, but keep this guard for
  // legacy integrations that route through files.download without a session.
  // A failed telemetry request must never trigger the auth store's 401 logout
  // side effect or prevent the native download from starting.
  if (useAuthStore().isLoggedIn) {
    void transfersApi
      .createDownload({
        id: transferId,
        name,
        target,
        url,
      })
      .catch(() => {
        // The raw route remains backwards compatible; telemetry is best effort.
      });
  }

  // Use a temporary <a> element to trigger download without popup blocker issues
  const a = document.createElement("a");
  a.href = url;
  a.style.display = "none";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

function downloadTransferId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `download-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export async function post(
  url: string,
  content: ApiContent = "",
  overwrite = false,
  onupload: (progress: { loaded: number }) => void = () => {},
  metadata?: Pick<
    Upload,
    | "batchId"
    | "batchName"
    | "batchItems"
    | "batchBytes"
    | "relativePath"
    | "isFolderUpload"
  >
) {
  // Use the pre-existing API if:
  const useResourcesApi =
    // a folder is being created
    url.endsWith("/") ||
    // We're not using http(s)
    (content instanceof Blob &&
      !["http:", "https:"].includes(window.location.protocol)) ||
    // Tus is disabled / not applicable
    !(await useTus(content));
  return useResourcesApi
    ? postResources(url, content, overwrite, onupload, metadata)
    : postTus(url, content, overwrite, onupload, metadata);
}

async function postResources(
  url: string,
  content: ApiContent = "",
  overwrite = false,
  onupload: (progress: { loaded: number }) => void = () => {},
  metadata?: Pick<
    Upload,
    | "batchId"
    | "batchName"
    | "batchItems"
    | "batchBytes"
    | "relativePath"
    | "isFolderUpload"
  >
) {
  url = removePrefix(url);

  let bufferContent: ArrayBuffer;
  if (
    content instanceof Blob &&
    !["http:", "https:"].includes(window.location.protocol)
  ) {
    bufferContent = await new Response(content).arrayBuffer();
  }

  const authStore = useAuthStore();
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open(
      "POST",
      `${baseURL}/api/resources${url}?override=${overwrite}`,
      true
    );
    request.setRequestHeader("X-Auth", authStore.jwt);
    if (content instanceof Blob) {
      request.setRequestHeader("X-Transfer-ID", uploadTransferId(url, content));
      for (const [name, value] of Object.entries(
        uploadBatchHeaders(metadata)
      )) {
        request.setRequestHeader(name, value);
      }
    }

    if (typeof onupload === "function") {
      request.upload.onprogress = onupload;
    }

    request.onload = () => {
      if (request.status === 200) {
        resolve(request.responseText);
      } else if (request.status === 409) {
        reject(new Error(request.status.toString()));
      } else {
        reject(new Error(request.responseText));
      }
    };

    request.onerror = () => {
      reject(new Error("001 Connection aborted"));
    };

    request.send(bufferContent || content);
  });
}

async function moveCopy(
  items: { from: string; to?: string; overwrite?: boolean; rename?: boolean }[],
  copy = false,
  overwrite = false,
  rename = false
) {
  // Copy and cross-directory move are durable file tasks. Same-directory move
  // remains the lightweight rename contract for keyboard and inline rename
  // callers that rely on its destination response header.
  const layoutStore = useLayoutStore();
  const sameDirectoryMove =
    !copy &&
    items.every((item) => {
      const source = urlUtils
        .canonicalResourcePath(item.from)
        .replace(/\/+$/, "");
      const destination = urlUtils
        .canonicalResourcePath(item.to ?? "")
        .replace(/\/+$/, "");
      const sourceParent = source.slice(0, source.lastIndexOf("/")) || "/";
      const destinationParent =
        destination.slice(0, destination.lastIndexOf("/")) || "/";
      return sourceParent === destinationParent;
    });

  if (!sameDirectoryMove) {
    const response = await fetchURL("/api/resources/transfer", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        action: copy ? "copy" : "move",
        items: items.map((item) => ({
          ...item,
          from: urlUtils.canonicalResourcePath(item.from),
          to: urlUtils.canonicalResourcePath(item.to ?? ""),
        })),
      }),
    });
    layoutStore.closeHovers();
    return [response];
  }

  const promises: Promise<Response>[] = [];

  for (const item of items) {
    // Listing URLs are already wire-safe routes. Keep them opaque so legacy
    // non-UTF-8 bytes (for example `%D6%D0`) are sent to the backend once;
    // re-encoding here would turn `%D6` into `%25D6` and target a different
    // filename. Canonical paths still go through the normal encoder.
    const from =
      item.from === "/files" || item.from.startsWith("/files/")
        ? item.from.replace(/\/+$/, "") || "/files"
        : urlUtils.encodeResourceRoute(item.from);
    const destinationPath = urlUtils.canonicalResourcePath(item.to ?? "");
    const to = encodeURIComponent(destinationPath);
    const finalOverwrite =
      item.overwrite == undefined ? overwrite : item.overwrite;
    const finalRename = item.rename == undefined ? rename : item.rename;
    const url = `${from}?action=${
      copy ? "copy" : "rename"
    }&destination=${to}&override=${finalOverwrite}&rename=${finalRename}`;
    promises.push(resourceAction(url, "PATCH"));
  }
  layoutStore.closeHovers();
  const outcomes = await Promise.allSettled(promises);
  if (!copy) {
    const favoritesStore = useFavoritesStore();
    const tagsStore = useTagsStore();
    outcomes.forEach((outcome, index) => {
      if (outcome.status !== "fulfilled") return;
      const source = urlUtils.canonicalResourcePath(items[index].from);
      const encodedDestination = outcome.value.headers.get(
        "X-Resource-Destination"
      );
      let destination = urlUtils.canonicalResourcePath(items[index].to ?? "");
      if (encodedDestination) {
        try {
          destination = decodeURIComponent(encodedDestination);
        } catch {
          // A malformed optional response header must not hide a successful
          // filesystem operation; the requested destination remains usable.
        }
      }
      favoritesStore.applyPathRewrite(source, destination);
      tagsStore.applyPathRewrite(source, destination);
      useRecentStore().applyPathRewrite(source, destination);
    });
  }

  const failure = outcomes.find(
    (outcome): outcome is PromiseRejectedResult => outcome.status === "rejected"
  );
  if (failure) throw failure.reason;
  return outcomes.map(
    (outcome) => (outcome as PromiseFulfilledResult<Response>).value
  );
}

export function move(
  items: { from: string; to?: string; overwrite?: boolean; rename?: boolean }[],
  overwrite = false,
  rename = false
) {
  return moveCopy(items, false, overwrite, rename);
}

function applyCompletedBatchRename(items: BatchRenameResultItem[]) {
  const completed = items.filter((item) => item.status === "completed");
  if (completed.length === 0) return;

  const token = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const staged = completed.map((item, index) => ({
    ...item,
    temp: `/.nas-file-browser-ui-rename-${token}-${index}`,
  }));
  const favoritesStore = useFavoritesStore();
  const tagsStore = useTagsStore();
  const recentStore = useRecentStore();
  for (const item of staged) {
    favoritesStore.applyPathRewrite(item.from, item.temp);
    tagsStore.applyPathRewrite(item.from, item.temp);
    recentStore.applyPathRewrite(item.from, item.temp);
  }
  for (const item of staged) {
    favoritesStore.applyPathRewrite(item.temp, item.to);
    tagsStore.applyPathRewrite(item.temp, item.to);
    recentStore.applyPathRewrite(item.temp, item.to);
  }
}

export async function batchRename(
  items: BatchRenameItem[],
  dryRun: boolean
): Promise<BatchRenameResult> {
  const response = await fetchURL("/api/resources/batch-rename", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ items, dryRun }),
  });
  const result = (await response.json()) as BatchRenameResult;
  if (result.executed) applyCompletedBatchRename(result.items);
  return result;
}

export function copy(
  items: { from: string; to?: string; overwrite?: boolean; rename?: boolean }[],
  overwrite = false,
  rename = false
) {
  return moveCopy(items, true, overwrite, rename);
}

export async function checksum(url: string, algo: ChecksumAlg) {
  const data = await resourceAction(`${url}?checksum=${algo}`, "GET");
  return (await data.json()).checksums[algo];
}

export function getDownloadURL(file: ResourceItem, inline: boolean) {
  const params = {
    ...(inline && { inline: "true" }),
  };

  return createURL("api/raw" + file.path, params);
}

export function getPreviewURL(
  file: ResourceItem,
  size: string,
  options: { warm?: "big"; fit?: "contain" } = {}
) {
  const params = {
    inline: "true",
    generator: "v3",
    key: `${Date.parse(file.modified)}-${file.size}`,
    ...(options.warm ? { warm: options.warm } : {}),
    ...(options.fit ? { fit: options.fit } : {}),
  };

  return createURL("api/preview/" + size + file.path, params);
}

export function getSubtitlesURL(file: ResourceItem) {
  const params = {
    inline: "true",
  };

  return file.subtitles?.map((d) => createURL("api/subtitle" + d, params));
}

export async function usage(url: string, signal: AbortSignal) {
  url = removePrefix(url);

  const res = await fetchURL(`/api/usage${url}`, { signal });

  try {
    return await res.json();
  } catch (e) {
    // Check if the error is an intentional cancellation
    if (e instanceof Error && e.name == "AbortError") {
      throw new StatusError("000 No connection", 0, true);
    }
    throw e;
  }
}
