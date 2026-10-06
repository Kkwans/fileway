type Upload = {
  transferId: string;
  path: string;
  name: string;
  file: File | null;
  type: ResourceType;
  overwrite: boolean;
  totalBytes: number;
  sentBytes: number;
  createdAt: number;
  speedBytesPerSecond: number;
  rawProgress: {
    sentBytes: number;
    sampledAt: number;
  };
  /** Browser-side grouping metadata for folder uploads. */
  batchId?: string;
  batchName?: string;
  batchItems?: number;
  batchBytes?: number;
  relativePath?: string;
  isFolderUpload?: boolean;
};

interface UploadEntry {
  name: string;
  size: number;
  isDir: boolean;
  fullPath?: string;
  to?: string;
  file?: File;
  overwrite?: boolean;
  batchId?: string;
  batchName?: string;
  batchItems?: number;
  batchBytes?: number;
  relativePath?: string;
  isFolderUpload?: boolean;
}

type UploadList = UploadEntry[];
