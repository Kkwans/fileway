export type FileActionMenuAction =
  | "info"
  | "rename"
  | "move"
  | "download"
  | "delete";

type Rectangle = Pick<DOMRect, "top" | "right" | "bottom">;

export interface FileActionMenuPositionInput {
  trigger: Rectangle;
  menuWidth: number;
  menuHeight: number;
  viewportWidth: number;
  viewportHeight: number;
  gap?: number;
  viewportPadding?: number;
}

export function getFileActionMenuPosition({
  trigger,
  menuWidth,
  menuHeight,
  viewportWidth,
  viewportHeight,
  gap = 6,
  viewportPadding = 8,
}: FileActionMenuPositionInput) {
  const maximumLeft = Math.max(
    viewportPadding,
    viewportWidth - menuWidth - viewportPadding
  );
  const left = Math.min(
    maximumLeft,
    Math.max(viewportPadding, trigger.right - menuWidth)
  );
  const preferredTop = trigger.bottom + gap;
  const top =
    preferredTop + menuHeight <= viewportHeight - viewportPadding
      ? preferredTop
      : Math.max(viewportPadding, trigger.top - menuHeight - gap);

  return { left, top };
}
