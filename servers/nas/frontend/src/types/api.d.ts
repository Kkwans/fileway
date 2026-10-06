export type ApiMethod = "GET" | "POST" | "PUT" | "DELETE" | "PATCH";

export type ApiContent =
  | Blob
  | File
  | Pick<ReadableStreamDefaultReader<any>, "read">
  | "";

export interface ApiOpts {
  method?: ApiMethod;
  headers?: object;
  body?: any;
  signal?: AbortSignal;
}

export interface TusSettings {
  retryCount: number;
  chunkSize: number;
}

export type ChecksumAlg = "md5" | "sha1" | "sha256" | "sha512";

export interface Share {
  hash: string;
  path: string;
  expire?: any;
  userID?: number;
  token?: string;
  username?: string;
  password_hash?: string;
}

interface SearchParams {
  [key: string]: string;
}
