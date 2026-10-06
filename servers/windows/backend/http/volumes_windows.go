package fbhttp

import (
	"context"
	"fmt"
	"sort"
	"strings"

	"github.com/shirou/gopsutil/v4/disk"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/risk"
)

// windowsVolumeName builds an Explorer-like display name for a drive.
func windowsVolumeName(letter, label, driveType string) string {
	letter = strings.ToUpper(letter)
	label = strings.TrimSpace(label)
	switch driveType {
	case "usb":
		if label != "" {
			return fmt.Sprintf("%s (%s:)", label, letter)
		}
		return fmt.Sprintf("可移动磁盘 (%s:)", letter)
	case "network":
		if label != "" {
			return fmt.Sprintf("%s (%s:)", label, letter)
		}
		return fmt.Sprintf("网络驱动器 (%s:)", letter)
	case "cdrom":
		if label != "" {
			return fmt.Sprintf("%s (%s:)", label, letter)
		}
		return fmt.Sprintf("光驱 (%s:)", letter)
	}

	if label != "" {
		return fmt.Sprintf("%s (%s:)", label, letter)
	}
	if letter == "C" {
		return fmt.Sprintf("系统盘 (%s:)", letter)
	}
	return fmt.Sprintf("本地磁盘 (%s:)", letter)
}

func windowsSubDirs(driveRoot, virtualRoot string) []SubDir {
	// Notable Windows top-level folders, virtual paths under /C etc.
	dirs := []struct {
		suffix string
		name   string
	}{
		{"Users", "用户"},
		{"Users/Public", "公用"},
		{"Windows", "Windows 系统"},
		{"Program Files", "程序文件"},
		{"Program Files (x86)", "程序文件 x86"},
		{"ProgramData", "程序数据"},
		{"PerfLogs", "性能日志"},
		{"Documents", "文档"},
		{"Download", "下载"},
		{"Downloads", "下载"},
		{"Movie", "电影"},
		{"Movies", "电影"},
		{"Music", "音乐"},
		{"Pictures", "图片"},
		{"Photos", "照片"},
		{"Video", "视频"},
		{"Videos", "视频"},
		{"Project", "项目"},
		{"Projects", "项目"},
	}

	result := make([]SubDir, 0, len(dirs))
	for _, d := range dirs {
		virtualPath := strings.TrimRight(virtualRoot, "/") + "/" + d.suffix
		// host path via DriveFs mapping
		hostPath, err := files.VirtualToWindowsPath(virtualPath)
		if err != nil {
			continue
		}
		if info, err := osStatDir(hostPath); err == nil && info {
			result = append(result, SubDir{
				Path: virtualPath,
				Name: d.name,
				Risk: risk.Classify(virtualPath),
			})
		}
	}
	return result
}

func osStatDir(path string) (bool, error) {
	info, err := osStat(path)
	if err != nil {
		return false, err
	}
	return info, nil
}

// discoverWindowsVolumes enumerates logical drives for Explorer-like volumes.
func discoverWindowsVolumes(ctx context.Context) ([]Volume, error) {
	drives, err := files.ListLogicalDrives()
	if err != nil {
		return nil, fmt.Errorf("枚举磁盘失败: %w", err)
	}

	volumes := make([]Volume, 0, len(drives))
	for _, drv := range drives {
		virtualPath := "/" + strings.ToUpper(drv.Letter)
		usage, usageErr := disk.UsageWithContext(ctx, drv.Root)
		vol := Volume{
			Path:         virtualPath,
			Name:         windowsVolumeName(drv.Letter, drv.Label, drv.DriveType),
			Type:         normalizeWindowsDriveType(drv.DriveType, drv.Letter),
			DriveLetter:  strings.ToUpper(drv.Letter) + ":",
			VolumeLabel:  drv.Label,
			SubDirs:      windowsSubDirs(drv.Root, virtualPath),
		}
		if usageErr == nil && usage != nil {
			vol.TotalSpace = usage.Total
			vol.UsedSpace = usage.Used
			vol.FreeSpace = usage.Free
		}
		volumes = append(volumes, vol)
	}
	sort.SliceStable(volumes, func(i, j int) bool {
		if volumes[i].DriveLetter != volumes[j].DriveLetter {
			return volumes[i].DriveLetter < volumes[j].DriveLetter
		}
		return volumes[i].Path < volumes[j].Path
	})
	return volumes, nil
}

func normalizeWindowsDriveType(driveType, letter string) string {
	switch driveType {
	case "usb", "network", "cdrom":
		return driveType
	default:
		// Fixed disks are shown as system/local volumes.
		return "system"
	}
}
