import type { ViewModeType } from "@/types/user";

export type ListingLayout = "table" | "mobile";

export type ListingFieldVisibility = {
  quickActions: boolean;
  tags: boolean;
  type: boolean;
  size: boolean;
  modified: boolean;
};

export type ListingTagPresentation = "names" | "dots" | "none";

export function getListingTagPresentation(
  mode: ViewModeType | string | undefined
): ListingTagPresentation {
  if (mode === "mosaic") return "dots";
  if (mode === "details") return "names";
  return "none";
}

/** Compact layouts deliberately avoid persistent action controls. */
export function getListingFieldVisibility(
  mode: ViewModeType | string | undefined
): ListingFieldVisibility {
  if (mode === "compact-grid" || mode === "compact-list") {
    return {
      quickActions: false,
      tags: mode === "compact-list",
      type: mode === "compact-list",
      size: mode === "compact-list",
      modified: mode === "compact-list",
    };
  }

  return {
    quickActions: true,
    tags: true,
    type: true,
    size: true,
    modified: true,
  };
}

export function shouldRenderListingTagSlot(
  mode: ViewModeType | string | undefined,
  tagCount: number
): boolean {
  if (!getListingFieldVisibility(mode).tags) return false;
  return tagCount > 0;
}

export function shouldRenderListingSize(
  mode: ViewModeType | string | undefined,
  isDirectory: boolean
): boolean {
  return getListingFieldVisibility(mode).size && !isDirectory;
}

/**
 * The detailed list switches layout from the available listing container,
 * not from the viewport. This keeps the contract correct when the sidebar is
 * resized or a tablet is used in split view.
 */
export function chooseListingLayout(containerWidth: number): ListingLayout {
  return containerWidth >= 900 ? "table" : "mobile";
}

/** The mobile selection bar only exists while selection is actionable. */
export function shouldRenderMobileSelection(
  isMobile: boolean,
  _multiple: boolean,
  selectedCount: number
): boolean {
  return isMobile && selectedCount > 0;
}

/** A double click on row content opens it; embedded action controls never do. */
export function shouldOpenDetailedRow(isActionControl: boolean): boolean {
  return !isActionControl;
}

export function shouldOpenDetailedRowFromClick(
  clickCount: number,
  singleClickEnabled: boolean,
  multipleSelection: boolean
): boolean {
  return clickCount >= 2 || (singleClickEnabled && !multipleSelection);
}

export interface TapSelectionInput {
  isTouch: boolean;
  multiple: boolean;
  selectedCount: number;
}

export type MobileTouchAction = "none" | "open" | "select" | "toggle-selection";

export interface MobileTouchInput {
  tapCount: number;
  longPress: boolean;
  moved: boolean;
  multiple?: boolean;
}

type EditableKeyboardTarget = {
  tagName?: string;
  isContentEditable?: boolean;
};

/** File-list shortcuts must never intercept text entry or rich-text editing. */
export function isEditableKeyboardTarget(
  target: EventTarget | EditableKeyboardTarget | null
): boolean {
  if (!target || typeof target !== "object") return false;
  const element = target as EditableKeyboardTarget;
  return (
    element.isContentEditable === true ||
    ["INPUT", "TEXTAREA", "SELECT"].includes(
      (element.tagName || "").toUpperCase()
    )
  );
}

/** Keep touch selection additive and prevent a second selection from opening an item. */
export function getTapSelectionBehavior({
  isTouch,
  multiple,
  selectedCount,
}: TapSelectionInput) {
  const preserveExisting = multiple || (isTouch && selectedCount > 0);
  return {
    preserveExisting,
    allowDoubleOpen: !preserveExisting,
  };
}

/**
 * Touch gestures intentionally do not reuse the desktop click contract:
 * a single tap is inert, a double tap opens, and a stationary long press
 * enters selection without opening a second action layer.
 */
export function getMobileTouchAction({
  tapCount,
  longPress,
  moved,
  multiple = false,
}: MobileTouchInput): MobileTouchAction {
  if (moved) return "none";
  if (longPress) return "select";
  if (multiple) return "toggle-selection";
  if (tapCount >= 2) return "open";
  return "none";
}

export function shouldSuppressTouchContextMenu(
  touchInteraction: boolean
): boolean {
  return touchInteraction;
}

/**
 * Return a stable grid column count. CSS remains the source of truth for the
 * final width; this helper is only used for responsive state and tests.
 */
export function getGridColumnCount(
  mode: Extract<ViewModeType, "mosaic" | "compact-grid">,
  containerWidth: number,
  compactGridSize: "small" | "medium" | "large" | "xlarge" = "medium"
): number {
  if (containerWidth <= 736) {
    if (mode === "compact-grid" && compactGridSize === "small") return 4;
    if (mode === "compact-grid" && compactGridSize === "medium") return 3;
    return 2;
  }

  const minimumCardWidth = mode === "compact-grid" ? 108 : 180;
  return Math.max(
    2,
    Math.min(6, Math.floor(containerWidth / minimumCardWidth))
  );
}
