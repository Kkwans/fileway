import type { AppIconName } from "@/components/ui/iconRegistry";

const CATEGORY_ICON_ALIASES: Record<string, AppIconName> = {
  person: "user",
  group: "collection",
  settings: "settings",
  folder: "folder",
};

export function resolveCategoryIcon(icon: string | undefined): AppIconName {
  return CATEGORY_ICON_ALIASES[icon ?? ""] ?? "folder";
}

export function resolveRiskIcon(risk: string | undefined): AppIconName {
  if (risk === "high") return "risk-high";
  if (risk === "medium") return "risk-medium";
  return "shield-check";
}
