import { describe, expect, it } from "vitest";

import {
  chooseListingLayout,
  getMobileTouchAction,
  getTapSelectionBehavior,
  getGridColumnCount,
  getListingFieldVisibility,
  getListingTagPresentation,
  isEditableKeyboardTarget,
  shouldOpenDetailedRow,
  shouldOpenDetailedRowFromClick,
  shouldRenderListingSize,
  shouldRenderListingTagSlot,
  shouldRenderMobileSelection,
  shouldSuppressTouchContextMenu,
} from "../layoutContract";

describe("file listing layout contract", () => {
  it("shows color-only tag markers in the detailed grid", () => {
    expect(getListingTagPresentation("mosaic")).toBe("dots");
    expect(getListingTagPresentation("details")).toBe("names");
    expect(getListingTagPresentation("compact-grid")).toBe("none");
    expect(getListingTagPresentation("compact-list")).toBe("none");
  });

  it("uses a semantic table only when the listing container is wide enough", () => {
    expect(chooseListingLayout(899)).toBe("mobile");
    expect(chooseListingLayout(900)).toBe("table");
  });

  it("does not render an empty mobile selection bar", () => {
    expect(shouldRenderMobileSelection(true, false, 0)).toBe(false);
    expect(shouldRenderMobileSelection(true, true, 0)).toBe(false);
    expect(shouldRenderMobileSelection(true, false, 2)).toBe(true);
    expect(shouldRenderMobileSelection(false, true, 2)).toBe(false);
  });

  it("keeps detailed grids at two columns on phone widths", () => {
    expect(getGridColumnCount("mosaic", 360)).toBe(2);
    expect(getGridColumnCount("mosaic", 736)).toBe(2);
    expect(getGridColumnCount("mosaic", 1200)).toBeGreaterThanOrEqual(4);
  });

  it("uses four compact columns for small icons and three for medium icons on phones", () => {
    expect(getGridColumnCount("compact-grid", 360, "small")).toBe(4);
    expect(getGridColumnCount("compact-grid", 360, "medium")).toBe(3);
    expect(getGridColumnCount("compact-grid", 360, "large")).toBe(2);
  });

  it("keeps compact grids limited to the visual and file name", () => {
    expect(getListingFieldVisibility("compact-grid")).toEqual({
      quickActions: false,
      tags: false,
      type: false,
      size: false,
      modified: false,
    });

    expect(getListingFieldVisibility("compact-list")).toEqual({
      quickActions: false,
      tags: true,
      type: true,
      size: true,
      modified: true,
    });

    expect(getListingFieldVisibility("mosaic")).toEqual({
      quickActions: true,
      tags: true,
      type: true,
      size: true,
      modified: true,
    });
  });

  it("reserves a stable tag row only for detailed grids", () => {
    expect(shouldRenderListingTagSlot("mosaic", 0)).toBe(false);
    expect(shouldRenderListingTagSlot("mosaic", 2)).toBe(true);
    expect(shouldRenderListingTagSlot("details", 0)).toBe(false);
    expect(shouldRenderListingTagSlot("details", 2)).toBe(true);
    expect(shouldRenderListingTagSlot("compact-grid", 2)).toBe(false);
  });

  it("never renders a size row for folders", () => {
    expect(shouldRenderListingSize("mosaic", true)).toBe(false);
    expect(shouldRenderListingSize("mosaic", false)).toBe(true);
    expect(shouldRenderListingSize("compact-grid", false)).toBe(false);
  });

  it("keeps prior selections when a touch user taps another item", () => {
    expect(
      getTapSelectionBehavior({
        isTouch: true,
        multiple: false,
        selectedCount: 1,
      })
    ).toEqual({ preserveExisting: true, allowDoubleOpen: false });

    expect(
      getTapSelectionBehavior({
        isTouch: true,
        multiple: false,
        selectedCount: 0,
      })
    ).toEqual({ preserveExisting: false, allowDoubleOpen: true });

    expect(
      getTapSelectionBehavior({
        isTouch: false,
        multiple: false,
        selectedCount: 1,
      })
    ).toEqual({ preserveExisting: false, allowDoubleOpen: true });
  });

  it("opens a detailed row from its content but never from an action button", () => {
    expect(shouldOpenDetailedRow(false)).toBe(true);
    expect(shouldOpenDetailedRow(true)).toBe(false);
  });

  it("opens on the second click even when selection state updates between clicks", () => {
    expect(shouldOpenDetailedRowFromClick(2, false, false)).toBe(true);
    expect(shouldOpenDetailedRowFromClick(1, true, false)).toBe(true);
    expect(shouldOpenDetailedRowFromClick(1, true, true)).toBe(false);
    expect(shouldOpenDetailedRowFromClick(1, false, false)).toBe(false);
  });

  it("keeps a single mobile tap inert and opens on the second tap", () => {
    expect(
      getMobileTouchAction({ tapCount: 1, longPress: false, moved: false })
    ).toBe("none");
    expect(
      getMobileTouchAction({ tapCount: 2, longPress: false, moved: false })
    ).toBe("open");
  });

  it("enters selection without opening a second action menu after a stationary long press", () => {
    expect(
      getMobileTouchAction({ tapCount: 1, longPress: true, moved: false })
    ).toBe("select");
    expect(
      getMobileTouchAction({ tapCount: 2, longPress: true, moved: true })
    ).toBe("none");
  });

  it("toggles an item with one tap after mobile multi-select is active", () => {
    expect(
      getMobileTouchAction({
        tapCount: 1,
        longPress: false,
        moved: false,
        multiple: true,
      })
    ).toBe("toggle-selection");
  });

  it("suppresses a synthetic context menu only during a touch interaction", () => {
    expect(shouldSuppressTouchContextMenu(true)).toBe(true);
    expect(shouldSuppressTouchContextMenu(false)).toBe(false);
  });

  it("does not let file shortcuts capture keyboard input from editable controls", () => {
    expect(isEditableKeyboardTarget({ tagName: "INPUT" })).toBe(true);
    expect(isEditableKeyboardTarget({ tagName: "TEXTAREA" })).toBe(true);
    expect(isEditableKeyboardTarget({ isContentEditable: true })).toBe(true);
    expect(isEditableKeyboardTarget({ tagName: "DIV" })).toBe(false);
    expect(isEditableKeyboardTarget(null)).toBe(false);
  });
});
