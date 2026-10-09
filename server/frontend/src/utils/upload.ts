import { useLayoutStore } from "@/stores/layout";
import { useUploadStore } from "@/stores/upload";
import url from "@/utils/url";
import { files as api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { fileSelectionScope } from "@/utils/fileListing";
import {
  operationWireTarget,
  operationRelativeTarget,
  operationSource,
} from "@/utils/resourceOperationWire";
import { favoriteIdentity } from "@/utils/favoritePersistence";
import type {
  ConflictingResource,
  ResourceType,
  MoveCopyItemUploadList,
} from "@/types/file";

/**
 * Check every supplied target with bounded, ordered raw-wire metadata reads.
 * Browser folder uploads merge directories; remote transfers report a single
 * top-level directory collision instead of promising a child-by-child merge.
 */
export async function checkConflict(
  files: UploadList | MoveCopyItemUploadList,
  basePath: string
): Promise<ConflictingResource[]> {
  const source = operationSource(() => fileSelectionScope(useAuthStore().user));
  source.ensure();
  const base = operationWireTarget(basePath, true);
  type Input = {
    name: string;
    fullPath?: string;
    from?: string;
    to?: string;
    isDir: boolean;
    size?: number;
    modified?: string;
    file?: File | null;
  };
  const inputs = files as Input[];
  const seen = new Set<string>();
  const targets = inputs.map((input, index) => {
    const target = input.to
      ? operationWireTarget(input.to, true)
      : operationRelativeTarget(basePath, input.fullPath || input.name);
    if (
      base.identity !== "/" &&
      !target.identity.startsWith(base.identity + "/")
    )
      throw new Error("目标路径不在所选目录内");
    if (seen.has(target.identity))
      throw new Error("本次操作存在重复目标路径，请分批处理");
    seen.add(target.identity);
    return { input, index, target };
  });
  const conflicts: ConflictingResource[] = [];
  for (let offset = 0; offset < targets.length; offset += 100) {
    source.ensure();
    const chunk = targets.slice(offset, offset + 100);
    const rows = await api.fetchBatch(
      chunk.map(({ target }) => target.plainPath ?? target.wirePath),
      undefined,
      chunk.map(({ target }) => target.wirePath)
    );
    source.ensure();
    if (!Array.isArray(rows) || rows.length !== chunk.length)
      throw new Error("目标元数据响应数量不一致，请重试");
    for (let index = 0; index < chunk.length; index++) {
      const { input, target, index: originalIndex } = chunk[index];
      const row = rows[index];
      if (favoriteIdentity(row) !== target.identity)
        throw new Error("目标原始路径确认不一致，请重试");
      if (row.status === 404) continue;
      if (row.status !== 200 || !row.item)
        throw new Error(
          row.error || "无法确认目标冲突状态，请检查权限或服务器后重试"
        );
      if (favoriteIdentity(row.item) !== target.identity)
        throw new Error("目标文件原始路径确认不一致，请重试");
      if (
        typeof row.item.isDir !== "boolean" ||
        typeof row.item.size !== "number" ||
        !Number.isFinite(row.item.size) ||
        typeof row.item.modified !== "string"
      )
        throw new Error("目标元数据格式无效，请重试");
      if (row.item.isDir !== Boolean(input.isDir))
        throw new Error("目标同名路径的文件与目录类型不同，请调整名称或位置");
      if (input.isDir && !input.from) continue; // Local directory merge; leaves remain individually checked.
      conflicts.push({
        index: originalIndex,
        name: input.isDir ? "目录：" + row.item.path : row.item.path,
        origin: {
          lastModified: input.file?.lastModified ?? input.modified,
          size: input.size,
        },
        dest: { lastModified: row.item.modified, size: row.item.size },
        checked: ["origin"],
        isSmallerOnServer:
          input.size !== undefined && input.size > row.item.size,
      });
    }
  }
  return conflicts;
}

export function scanFiles(dt: DataTransfer): Promise<UploadList | FileList> {
  return new Promise((resolve) => {
    let reading = 0;
    const contents: UploadList = [];

    if (dt.items) {
      // ts didn't like the for of loop even tho
      // it is the official example on MDN
      // for (const item of dt.items) {
      for (let i = 0; i < dt.items.length; i++) {
        const item = dt.items[i];
        if (
          item.kind === "file" &&
          typeof item.webkitGetAsEntry === "function"
        ) {
          const entry = item.webkitGetAsEntry();
          entry && readEntry(entry);
        }
      }
    } else {
      resolve(dt.files);
    }

    function readEntry(entry: FileSystemEntry, directory = ""): void {
      if (entry.isFile) {
        reading++;
        (entry as FileSystemFileEntry).file((file) => {
          reading--;

          contents.push({
            file,
            name: file.name,
            size: file.size,
            isDir: false,
            fullPath: `${directory}${file.name}`,
          });

          if (reading === 0) {
            resolve(contents);
          }
        });
      } else if (entry.isDirectory) {
        const dir = {
          isDir: true,
          size: 0,
          fullPath: `${directory}${entry.name}`,
          name: entry.name,
        };

        contents.push(dir);

        readReaderContent(
          (entry as FileSystemDirectoryEntry).createReader(),
          `${directory}${entry.name}`
        );
      }
    }

    function readReaderContent(
      reader: FileSystemDirectoryReader,
      directory: string
    ): void {
      reading++;

      reader.readEntries((entries) => {
        reading--;
        if (entries.length > 0) {
          const dirWithSlash = directory.endsWith("/")
            ? directory
            : `${directory}/`;
          for (const entry of entries) {
            readEntry(entry, dirWithSlash);
          }

          readReaderContent(reader, dirWithSlash);
        }

        if (reading === 0) {
          resolve(contents);
        }
      });
    }
  });
}

function detectType(mimetype: string): ResourceType {
  if (mimetype.startsWith("video")) return "video";
  if (mimetype.startsWith("audio")) return "audio";
  if (mimetype.startsWith("image")) return "image";
  if (mimetype.startsWith("pdf")) return "pdf";
  if (mimetype.startsWith("text")) return "text";
  return "blob";
}

/**
 * Process files from an HTML file input element.
 * Shared utility used by both Upload.vue and FileListing.vue.
 */
export function processFileInput(
  event: Event,
  basePath: string,
  layoutStore: ReturnType<typeof useLayoutStore>
) {
  const files = (event.currentTarget as HTMLInputElement)?.files;
  if (files === null || files.length === 0) return;

  const folder_upload = !!files[0].webkitRelativePath;

  const batchMetadata = folder_upload
    ? {
        batchId: createUploadBatchId(),
        batchName: folderName(files),
        batchItems: files.length,
        batchBytes: Array.from(files).reduce(
          (total, file) => total + file.size,
          0
        ),
        isFolderUpload: true,
      }
    : undefined;

  const uploadFiles: UploadList = [];
  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    const fullPath = folder_upload ? file.webkitRelativePath : undefined;
    uploadFiles.push({
      file,
      name: file.name,
      size: file.size,
      isDir: false,
      fullPath,
      ...(batchMetadata ?? {}),
      ...(fullPath ? { relativePath: fullPath } : {}),
    });
  }

  const path = basePath.endsWith("/") ? basePath : basePath + "/";

  checkConflict(uploadFiles, path).then((conflict) => {
    if (conflict.length > 0) {
      layoutStore.showHover({
        prompt: "resolve-conflict",
        props: {
          conflict,
          isUploadAction: true,
        },
        confirm: (event: Event, result: Array<ConflictingResource>) => {
          event.preventDefault();
          layoutStore.closeHovers();
          for (let i = result.length - 1; i >= 0; i--) {
            const item = result[i];
            if (item.checked.length == 2) {
              continue;
            } else if (
              item.checked.length == 1 &&
              item.checked[0] == "origin"
            ) {
              uploadFiles[item.index].overwrite = true;
            } else {
              uploadFiles.splice(item.index, 1);
            }
          }
          if (uploadFiles.length > 0) {
            handleFiles(uploadFiles, path, true);
          }
        },
      });
      return;
    }
    handleFiles(uploadFiles, path);
  });
}

export function handleFiles(
  files: UploadList,
  base: string,
  overwrite = false
) {
  const uploadStore = useUploadStore();
  const layoutStore = useLayoutStore();

  layoutStore.closeHovers();

  for (const file of files) {
    let path = base;

    if (file.fullPath !== undefined) {
      path += url.encodePath(file.fullPath);
    } else {
      path += url.encodeRFC5987ValueChars(file.name);
    }

    if (file.isDir) {
      path += "/";
    }

    const type = file.isDir ? "dir" : detectType((file.file as File).type);

    uploadStore.upload(
      path,
      file.name,
      file.file ?? null,
      file.overwrite || overwrite,
      type,
      {
        batchId: file.batchId,
        batchName: file.batchName,
        batchItems: file.batchItems,
        batchBytes: file.batchBytes,
        relativePath: file.relativePath,
        isFolderUpload: file.isFolderUpload,
      }
    );
  }
}

function createUploadBatchId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `folder-${crypto.randomUUID()}`;
  }
  return `folder-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function folderName(files: FileList) {
  const relativePath =
    files[0]?.webkitRelativePath || files[0]?.name || "文件夹";
  return relativePath.split("/")[0] || "文件夹";
}
