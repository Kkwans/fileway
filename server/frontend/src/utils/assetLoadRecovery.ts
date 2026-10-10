// A stale tab and a transient network failure can produce the same import error.
// Never reload automatically: an editor or upload may still have unsaved work.
export const assetLoadMessage =
  "页面资源加载失败，可能是应用已更新或网络中断。请先保存未完成的编辑或上传，再刷新页面。";

export function isAssetLoadError(error: unknown): boolean {
  const message =
    error && typeof error === "object" && "message" in error
      ? String(error.message)
      : typeof error === "string"
        ? error
        : "";
  return /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Loading (?:CSS )?chunk [\s\S]* failed|Unable to preload CSS/i.test(
    message
  );
}

export function reloadAfterConfirmation(): boolean {
  if (
    window.confirm(
      "刷新会重新加载当前页面。请确认已保存编辑内容，且没有需要保留的上传任务。现在刷新吗？"
    )
  ) {
    window.location.reload();
    return true;
  }
  return false;
}
