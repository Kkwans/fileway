import { defineStore } from "pinia";
import { ref, computed } from "vue";
import type { Volume, SubDir } from "@/api/volumes";
import { getVolumes } from "@/api/volumes";
import { formatStorageSize } from "@/utils/storageSize";
import type { AppIconName } from "@/components/ui/iconRegistry";

export interface VolumeDisplay extends Volume {
  displayName: string;
  usedFormatted: string;
  totalFormatted: string;
  usedPercentage: number;
  icon: AppIconName;
  color: string;
}

const VOLUME_ICONS: Record<
  Volume["type"],
  { icon: AppIconName; color: string }
> = {
  system: { icon: "hard-drive", color: "#4CAF50" },
  usb: { icon: "usb", color: "#2196F3" },
  network: { icon: "cloud", color: "#9C27B0" },
  docker: { icon: "container", color: "#FF9800" },
};

export const useVolumesStore = defineStore("volumes", () => {
  const volumes = ref<Volume[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  const displayVolumes = computed<VolumeDisplay[]>(() => {
    return volumes.value.map((vol) => {
      const { icon, color } = VOLUME_ICONS[vol.type] || VOLUME_ICONS.system;
      return {
        ...vol,
        displayName: vol.name,
        usedFormatted: formatStorageSize(vol.usedSpace),
        totalFormatted: formatStorageSize(vol.totalSpace),
        usedPercentage:
          vol.totalSpace > 0
            ? Math.round((vol.usedSpace / vol.totalSpace) * 100)
            : 0,
        icon,
        color,
      };
    });
  });

  const systemVolumes = computed(() =>
    displayVolumes.value.filter((v) => v.type === "system")
  );

  const otherVolumes = computed(() =>
    displayVolumes.value.filter((v) => v.type !== "system")
  );

  // Flatten all subdirectories from all volumes
  const allSubDirs = computed<SubDir[]>(() => {
    const result: SubDir[] = [];
    for (const vol of volumes.value) {
      if (vol.subDirs) {
        result.push(...vol.subDirs);
      }
    }
    return result;
  });

  async function fetchVolumes() {
    loading.value = true;
    error.value = null;
    try {
      volumes.value = await getVolumes();
    } catch (e: any) {
      error.value = e.message || "获取存储卷失败";
      // Fallback: don't break the UI if API fails
      volumes.value = [];
    } finally {
      loading.value = false;
    }
  }

  return {
    volumes,
    loading,
    error,
    displayVolumes,
    systemVolumes,
    otherVolumes,
    allSubDirs,
    fetchVolumes,
  };
});
