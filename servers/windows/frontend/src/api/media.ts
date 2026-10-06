import { fetchJSON, fetchURL } from "./utils";

export interface PlaybackPosition {
  path: string;
  identity: string;
  position: number;
  duration: number;
  updatedAt: number;
  exists: boolean;
}

type PlaybackWaiter = {
  resolve: (value: PlaybackPosition | void) => void;
  reject: (reason?: unknown) => void;
};

type PlaybackMutation =
  | {
      kind: "put";
      path: string;
      position: number;
      duration: number;
      waiters: PlaybackWaiter[];
    }
  | {
      kind: "delete";
      path: string;
      waiters: PlaybackWaiter[];
    };

type PlaybackQueue = {
  running: boolean;
  operations: PlaybackMutation[];
};

// Playback updates are frequent while a video is playing.  Keep the queue
// per path so another video remains responsive, and coalesce only contiguous
// PUTs.  A DELETE stays in the operation list as a strict ordering barrier:
// PUTs submitted after it can never overtake it.
const playbackQueues = new Map<string, PlaybackQueue>();

export interface MediaInformation {
  path: string;
  name: string;
  type: "image" | "video" | "audio";
  extension: string;
  size: number;
  modified: string;
  resolution?: { width: number; height: number };
  format?: string;
  duration?: number;
  bitRate?: number;
  videoCodec?: string;
  audioCodec?: string;
  channels?: number;
  sampleRate?: number;
  title?: string;
  artist?: string;
  album?: string;
  date?: string;
  location?: string;
  technicalError?: string;
}

export type HLSPlaybackState =
  | "queued"
  | "preparing"
  | "streamable"
  | "completed"
  | "failed"
  | "canceled";

export interface HLSPlaybackStatus {
  id: string;
  taskId?: string;
  path: string;
  identity: string;
  profile: string;
  state: HLSPlaybackState;
  error?: string;
  updatedAt: number;
  lastAccessAt?: number;
  sizeBytes?: number;
  processedSeconds?: number;
  durationSeconds?: number;
  format?: "hls" | "copy" | "mp4-copy" | "webm" | "webm-copy";
  playlistUrl?: string;
  sourceUrl?: string;
}

export function getPlayback(path: string): Promise<PlaybackPosition> {
  return fetchJSON<PlaybackPosition>(
    `/api/media/playback?path=${encodeURIComponent(path)}`
  );
}

export async function savePlayback(
  path: string,
  position: number,
  duration: number
): Promise<PlaybackPosition> {
  return enqueuePlaybackMutation<PlaybackPosition>(path, {
    kind: "put",
    path,
    position,
    duration,
    waiters: [],
  });
}

export function clearPlayback(path: string): Promise<void> {
  return enqueuePlaybackMutation<void>(path, {
    kind: "delete",
    path,
    waiters: [],
  });
}

function enqueuePlaybackMutation<T extends PlaybackPosition | void>(
  path: string,
  mutation: PlaybackMutation
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const queue = playbackQueues.get(path) ?? {
      running: false,
      operations: [],
    };
    playbackQueues.set(path, queue);

    const tail = queue.operations[queue.operations.length - 1];
    if (mutation.kind === "put" && tail?.kind === "put") {
      // Every caller waiting on the coalesced operation receives the response
      // for the newest position rather than an obsolete intermediate value.
      tail.position = mutation.position;
      tail.duration = mutation.duration;
      tail.waiters.push({
        resolve: (value) => resolve(value as T),
        reject,
      });
    } else {
      mutation.waiters.push({
        resolve: (value) => resolve(value as T),
        reject,
      });
      queue.operations.push(mutation);
    }
    runPlaybackQueue(path, queue);
  });
}

function runPlaybackQueue(path: string, queue: PlaybackQueue) {
  if (queue.running) return;
  const mutation = queue.operations.shift();
  if (!mutation) {
    playbackQueues.delete(path);
    return;
  }

  queue.running = true;
  const request =
    mutation.kind === "put"
      ? fetchJSON<PlaybackPosition>("/api/media/playback", {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            path: mutation.path,
            position: mutation.position,
            duration: mutation.duration,
          }),
        })
      : fetchURL(
          `/api/media/playback?path=${encodeURIComponent(mutation.path)}`,
          { method: "DELETE" }
        ).then(() => undefined);

  void request
    .then((value) => {
      mutation.waiters.forEach((waiter) => {
        if (mutation.kind === "put") {
          waiter.resolve(value as PlaybackPosition);
        } else {
          waiter.resolve(undefined);
        }
      });
    })
    .catch((error: unknown) => {
      mutation.waiters.forEach((waiter) => waiter.reject(error));
    })
    .finally(() => {
      queue.running = false;
      runPlaybackQueue(path, queue);
    });
}

export interface VideoSpriteMeta {
  path: string;
  number: number;
  column: number;
  width: number;
  height: number;
  url?: string;
}

export function getVideoSprite(path: string): Promise<VideoSpriteMeta> {
  return fetchJSON<VideoSpriteMeta>(
    `/api/media/sprite?path=${encodeURIComponent(path)}`
  );
}

export function getMediaInformation(
  path: string,
  includeLocation = false,
  signal?: AbortSignal
): Promise<MediaInformation> {
  const query = new URLSearchParams({ path });
  if (includeLocation) query.set("includeLocation", "true");
  return fetchJSON<MediaInformation>(`/api/media/info?${query.toString()}`, {
    signal,
  });
}

export async function startHLSPlayback(
  path: string,
  format: "hls" | "mp4" | "webm" = "hls",
  quality?: "source" | "2160p" | "1440p" | "1080p" | "720p" | "480p"
): Promise<HLSPlaybackStatus> {
  const body: Record<string, string> = { path };
  if (format !== "hls") body.format = format;
  if (quality && quality !== "source") body.quality = quality;
  const response = await fetchURL("/api/media/hls", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return response.json() as Promise<HLSPlaybackStatus>;
}

export function getHLSPlayback(id: string): Promise<HLSPlaybackStatus> {
  return fetchJSON<HLSPlaybackStatus>(
    `/api/media/hls/${encodeURIComponent(id)}`
  );
}

export async function cancelHLSPlayback(
  id: string
): Promise<HLSPlaybackStatus> {
  const response = await fetchURL(
    `/api/media/hls/${encodeURIComponent(id)}/cancel`,
    { method: "POST" }
  );
  return response.json() as Promise<HLSPlaybackStatus>;
}
