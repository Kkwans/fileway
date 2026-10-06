import { fetchURL } from "./utils";
import type { RiskLevel } from "@/types/file";

export interface SubDir {
  path: string;
  name: string;
  risk: RiskLevel;
}

export interface Volume {
  path: string;
  name: string;
  type: "system" | "usb" | "network" | "docker";
  totalSpace: number;
  usedSpace: number;
  subDirs?: SubDir[];
}

export async function getVolumes(): Promise<Volume[]> {
  const res = await fetchURL("/api/volumes", {});
  return (await res.json()) as Volume[];
}
