import type { AppIconName } from "@/components/ui/iconRegistry";

const VIEW_ICONS: Record<string, AppIconName> = {
  mosaic: "view-mosaic",
  "compact-grid": "view-compact-grid",
  details: "view-details",
  "compact-list": "view-compact-list",
};

const SORT_ICONS: Record<string, AppIconName> = {
  name: "text",
  size: "chart-storage",
  modified: "clock",
  type: "categories",
};

export function listingViewIcon(value: string): AppIconName {
  return VIEW_ICONS[value] ?? "view-mosaic";
}

export function listingGridSizeIcon(value: string): AppIconName {
  if (value === "small" || value === "medium") return "view-compact-grid";
  return value === "large" ? "view-mosaic" : "view-mosaic";
}

export function listingSortIcon(value: string): AppIconName {
  return SORT_ICONS[value] ?? "sort";
}

export function listingSortDirectionIcon(ascending: boolean): AppIconName {
  return ascending ? "arrow-up" : "arrow-down";
}
