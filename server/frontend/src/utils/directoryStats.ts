export interface DirectoryEntry {
  isDir: boolean;
  size: number;
}

export function summarizeDirectory(entries: DirectoryEntry[]) {
  return entries.reduce(
    (summary, entry) => {
      if (entry.isDir) {
        summary.directories += 1;
      } else {
        summary.files += 1;
        summary.size += entry.size;
      }
      return summary;
    },
    { directories: 0, files: 0, size: 0 }
  );
}
