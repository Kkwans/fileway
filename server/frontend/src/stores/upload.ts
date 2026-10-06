import { defineStore } from "pinia";
import { useFileStore } from "./file";
import { files as api } from "@/api";
import buttons from "@/utils/buttons";
import { computed, inject, markRaw, ref } from "vue";
import * as tus from "@/api/tus";
import { removePrefix } from "@/api/utils";
import type { ResourceType } from "@/types/file";

// TODO: make this into a user setting
const UPLOADS_LIMIT = 5;

const beforeUnload = (event: Event) => {
  event.preventDefault();
  // To remove >> is deprecated
  // event.returnValue = "";
};

export const useUploadStore = defineStore("upload", () => {
  const $showError = inject<IToastError>("$showError")!;

  let progressInterval: number | null = null;

  //
  // STATE
  //

  const allUploads = ref<Upload[]>([]);
  const activeUploads = ref<Set<Upload>>(new Set());
  const lastUpload = ref<number>(-1);
  const totalBytes = ref<number>(0);
  const sentBytes = ref<number>(0);

  //
  // ACTIONS
  //

  const upload = (
    path: string,
    name: string,
    file: File | null,
    overwrite: boolean,
    type: ResourceType,
    metadata: Pick<
      Upload,
      | "batchId"
      | "batchName"
      | "batchItems"
      | "batchBytes"
      | "relativePath"
      | "isFolderUpload"
    > = {}
  ) => {
    if (!hasActiveUploads() && !hasPendingUploads()) {
      window.addEventListener("beforeunload", beforeUnload);
      buttons.loading("upload");
    }

    const upload: Upload = {
      transferId:
        file === null
          ? `directory-${Date.now()}-${encodeURIComponent(path)}`
          : tus.uploadTransferId(removePrefix(path), file),
      path,
      name,
      file,
      overwrite,
      type,
      totalBytes: file?.size || 1,
      sentBytes: 0,
      createdAt: Date.now(),
      speedBytesPerSecond: 0,
      ...metadata,
      // Stores rapidly changing sent bytes value without causing component re-renders
      rawProgress: markRaw({
        sentBytes: 0,
        sampledAt: Date.now(),
      }),
    };

    totalBytes.value += upload.totalBytes;
    allUploads.value.push(upload);

    processUploads();
  };

  const abort = () => {
    // Resets the state by preventing the processing of the remaning uploads
    lastUpload.value = Infinity;
    tus.abortAllUploads();
  };

  //
  // GETTERS
  //

  const pendingUploadCount = computed(
    () =>
      allUploads.value.length -
      (lastUpload.value + 1) +
      activeUploads.value.size
  );

  //
  // PRIVATE FUNCTIONS
  //

  const hasActiveUploads = () => activeUploads.value.size > 0;

  const hasPendingUploads = () =>
    allUploads.value.length > lastUpload.value + 1;

  const isActiveUploadsOnLimit = () => activeUploads.value.size < UPLOADS_LIMIT;

  const processUploads = async () => {
    if (!hasActiveUploads() && !hasPendingUploads()) {
      const fileStore = useFileStore();
      window.removeEventListener("beforeunload", beforeUnload);
      buttons.success("upload");
      reset();
      fileStore.reload = true;
    }

    if (isActiveUploadsOnLimit() && hasPendingUploads()) {
      if (!hasActiveUploads()) {
        // Update the state in a fixed time interval
        progressInterval = window.setInterval(syncState, 500);
      }

      const upload = nextUpload();

      if (upload.type === "dir") {
        await api.post(upload.path).catch($showError);
      } else {
        const onUpload = (event: { loaded: number }) => {
          upload.rawProgress.sentBytes = event.loaded;
        };

        await api
          .post(upload.path, upload.file!, upload.overwrite, onUpload, upload)
          .catch((err) => err.message !== "Upload aborted" && $showError(err));
      }

      finishUpload(upload);
    }
  };

  const nextUpload = (): Upload => {
    lastUpload.value++;

    const upload = allUploads.value[lastUpload.value];
    upload.rawProgress.sampledAt = Date.now();
    activeUploads.value.add(upload);

    return upload;
  };

  const finishUpload = (upload: Upload) => {
    sentBytes.value += upload.totalBytes - upload.sentBytes;
    upload.sentBytes = upload.totalBytes;
    upload.file = null;

    activeUploads.value.delete(upload);
    processUploads();
  };

  const syncState = () => {
    const now = Date.now();
    for (const upload of activeUploads.value) {
      const delta = upload.rawProgress.sentBytes - upload.sentBytes;
      const elapsed = Math.max(
        (now - upload.rawProgress.sampledAt) / 1000,
        0.001
      );
      const currentSpeed = Math.max(0, delta / elapsed);
      upload.speedBytesPerSecond =
        upload.speedBytesPerSecond === 0
          ? currentSpeed
          : currentSpeed * 0.35 + upload.speedBytesPerSecond * 0.65;
      sentBytes.value += delta;
      upload.sentBytes = upload.rawProgress.sentBytes;
      upload.rawProgress.sampledAt = now;
    }
  };

  const speedBytesPerSecond = computed(() =>
    Array.from(activeUploads.value).reduce(
      (total, upload) => total + upload.speedBytesPerSecond,
      0
    )
  );

  const etaSeconds = computed(() => {
    if (speedBytesPerSecond.value <= 0) return Infinity;
    return (
      Math.max(0, totalBytes.value - sentBytes.value) /
      speedBytesPerSecond.value
    );
  });

  const reset = () => {
    if (progressInterval !== null) {
      clearInterval(progressInterval);
      progressInterval = null;
    }

    allUploads.value = [];
    activeUploads.value = new Set();
    lastUpload.value = -1;
    totalBytes.value = 0;
    sentBytes.value = 0;
  };

  return {
    // STATE
    allUploads,
    activeUploads,
    totalBytes,
    sentBytes,
    speedBytesPerSecond,
    etaSeconds,

    // ACTIONS
    upload,
    abort,

    // GETTERS
    pendingUploadCount,
  };
});
