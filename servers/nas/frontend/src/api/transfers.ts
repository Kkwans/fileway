import { fetchJSON, fetchURL } from "./utils";

export type TransferKind = "upload" | "download";
export type TransferStatus =
  | "queued"
  | "running"
  | "completed"
  | "failed"
  | "canceled"
  | "interrupted";

export interface TransferItem {
  id: string;
  kind: TransferKind;
  status: TransferStatus;
  name: string;
  target: string;
  bytesTotal?: number;
  bytesTransferred: number;
  error?: string;
  createdAt: number;
  startedAt?: number;
  finishedAt?: number;
  batchId?: string;
  batchName?: string;
  batchItems?: number;
  batchBytes?: number;
  isFolderUpload?: boolean;
}

export interface TransferListResponse {
  items: TransferItem[];
  nextCursor?: string;
  total: number;
}

export interface TransferListFilter {
  kind?: TransferKind;
  statuses?: TransferStatus[];
  cursor?: string;
  limit?: number;
  signal?: AbortSignal;
}

export interface DownloadTransferInput {
  id: string;
  name: string;
  target: string;
  url: string;
  bytesTotal?: number;
}

export interface DownloadTransferResponse {
  item: TransferItem;
  url?: string;
}

export function list(
  kind?: TransferKind,
  signal?: AbortSignal
): Promise<TransferListResponse>;
export function list(
  kind?: TransferKind,
  cursor?: string,
  limit?: number,
  signal?: AbortSignal
): Promise<TransferListResponse>;
export function list(filter: TransferListFilter): Promise<TransferListResponse>;
export function list(
  kindOrFilter?: TransferKind | TransferListFilter,
  cursorOrSignal?: string | AbortSignal,
  limitOrSignal: number | AbortSignal = 10,
  signal?: AbortSignal
): Promise<TransferListResponse> {
  const filter =
    typeof kindOrFilter === "object" && kindOrFilter !== null
      ? kindOrFilter
      : undefined;
  const kind =
    filter?.kind ??
    (typeof kindOrFilter === "string" ? kindOrFilter : undefined);
  const cursor =
    filter?.cursor ??
    (typeof cursorOrSignal === "string" ? cursorOrSignal : undefined);
  const requestSignal =
    filter?.signal ??
    (typeof cursorOrSignal === "string"
      ? typeof limitOrSignal === "number"
        ? signal
        : limitOrSignal
      : cursorOrSignal);
  const limit =
    filter?.limit ?? (typeof limitOrSignal === "number" ? limitOrSignal : 10);
  const params = new URLSearchParams();
  if (kind) params.set("kind", kind);
  if (filter?.statuses?.length) params.set("status", filter.statuses.join(","));
  params.set("limit", String(limit));
  if (cursor) params.set("cursor", cursor);
  const query = `?${params.toString()}`;
  return fetchJSON<TransferListResponse>(`/api/transfers${query}`, {
    signal: requestSignal,
  });
}

export async function createDownload(
  input: DownloadTransferInput,
  signal?: AbortSignal
): Promise<DownloadTransferResponse> {
  const response = await fetchURL("/api/transfers/downloads", {
    method: "POST",
    signal,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
  return (await response.json()) as DownloadTransferResponse;
}

export function cancel(id: string): Promise<TransferItem> {
  return fetchJSON<TransferItem>(
    `/api/transfers/${encodeURIComponent(id)}/cancel`,
    { method: "POST" }
  );
}

export async function remove(id: string): Promise<void> {
  await fetchURL(`/api/transfers/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
}

export async function removeAll(kind: TransferKind): Promise<number> {
  const response = await fetchURL(
    `/api/transfers?kind=${encodeURIComponent(kind)}`,
    { method: "DELETE" }
  );
  const result = (await response.json()) as { deleted?: number };
  return result.deleted ?? 0;
}
