package fbhttp

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"

	"github.com/Kkwans/nas-file-browser/backend/files"
	"github.com/Kkwans/nas-file-browser/backend/risk"
	"github.com/shirou/gopsutil/v4/disk"
)

// Volume has one API schema for Linux filesystems and Windows logical drives.
type Volume struct {
	Path        string   `json:"path"`
	Name        string   `json:"name"`
	Type        string   `json:"type"` // system, usb, network, docker
	TotalSpace  uint64   `json:"totalSpace"`
	UsedSpace   uint64   `json:"usedSpace"`
	FreeSpace   uint64   `json:"freeSpace"`
	DriveLetter string   `json:"driveLetter,omitempty"`
	VolumeLabel string   `json:"volumeLabel,omitempty"`
	SubDirs     []SubDir `json:"subDirs,omitempty"`
}

// SubDir represents a notable subdirectory within a volume.
type SubDir struct {
	Path string     `json:"path"`
	Name string     `json:"name"`
	Risk risk.Level `json:"risk"`
}

// knownSubDirs returns the list of notable subdirectories for a given volume.
func knownSubDirs(serverRoot, volumePath string) []SubDir {
	dirs := []struct {
		suffix string
		name   string
	}{
		{"@home", "用户主目录"},
		{"@docker", "Docker 数据"},
		{"@appstore", "应用数据"},
		{"@tmp", "临时文件"},
		{"@upload", "上传缓存"},
		{"@search", "搜索索引"},
		{"@thumbnail", "缩略图缓存"},
		{"@appcache", "应用缓存"},
		{"@appdata", "应用配置"},
		{"@applog", "应用日志"},
		{"@exif", "EXIF 数据"},
		{"@FileManager", "文件管理器数据"},
		{"@video", "视频索引"},
		{"@RecentlyScan", "最近扫描"},
		{"@eaDir", "NAS 元数据"},
		{"Docker", "Docker 项目"},
		{"OpenClaw", "OpenClaw"},
		{"Project", "Project"},
		{"Hermes", "Hermes"},
		{"docker", "docker"},
		{"DockerProject", "DockerProject"},
		{"docker-apps", "docker-apps"},
		{"Download", "下载"},
		{"Movie", "电影"},
		{"Movies", "电影"},
		{"Music", "音乐"},
		{"Photos", "照片"},
		{"Pictures", "图片"},
		{"TV", "电视剧"},
		{"Video", "视频"},
		{"Videos", "视频"},
		{"Documents", "文档"},
		{"Common", "Common"},
		{"ViEDO", "ViEDO"},
		{"迅雷下载", "迅雷下载"},
	}

	result := make([]SubDir, 0, len(dirs))
	for _, d := range dirs {
		virtualPath := path.Join(volumePath, d.suffix)
		hostPath := filepath.Join(serverRoot, filepath.FromSlash(strings.TrimPrefix(virtualPath, "/")))
		if info, err := os.Stat(hostPath); err == nil && info.IsDir() {
			result = append(result, SubDir{Path: virtualPath, Name: d.name, Risk: risk.Classify(virtualPath)})
		}
	}
	return result
}

// volumeType determines the type label for a given mount path.
func volumeType(path string) string {
	base := filepath.Base(path)
	switch {
	case strings.HasPrefix(base, "volumeUSB"):
		return "usb"
	case strings.HasPrefix(base, "volumeSATA"), strings.HasPrefix(base, "volumeNVMe"):
		return "network"
	default:
		return "system"
	}
}

// volumeName returns a human-readable name for a volume path.
func volumeName(path string) string {
	base := filepath.Base(path)
	switch {
	case strings.HasPrefix(base, "volumeUSB"):
		return "USB 存储 " + strings.TrimPrefix(base, "volumeUSB")
	case base == "volume1":
		return "存储卷 1"
	case base == "volume2":
		return "存储卷 2"
	case strings.HasPrefix(base, "volume"):
		return "存储卷 " + strings.TrimPrefix(base, "volume")
	default:
		return base
	}
}

func discoverVolumes(ctx context.Context, serverRoot string) ([]Volume, error) {
	if files.IsVirtualComputerRoot(serverRoot) {
		return discoverWindowsVolumes(ctx)
	}
	volumes := make([]Volume, 0, 8)

	// Scan the configured server root. API paths stay virtual (for example
	// /volume1) even when the host root is mounted at /srv in the container.
	entries, err := os.ReadDir(serverRoot)
	if err != nil {
		return nil, fmt.Errorf("读取根目录失败: %w", err)
	}

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		name := entry.Name()
		if !strings.HasPrefix(name, "volume") {
			continue
		}

		virtualPath := "/" + name
		hostPath := filepath.Join(serverRoot, name)

		// Skip Docker internal overlay paths
		if strings.Contains(virtualPath, "@docker") {
			continue
		}

		vol := Volume{
			Path:    virtualPath,
			Name:    volumeName(virtualPath),
			Type:    volumeType(virtualPath),
			SubDirs: knownSubDirs(serverRoot, virtualPath),
		}

		// Get disk usage for this volume
		usage, err := disk.UsageWithContext(ctx, hostPath)
		if err == nil {
			vol.TotalSpace = usage.Total
			vol.UsedSpace = usage.Used
			vol.FreeSpace = usage.Free
		}

		volumes = append(volumes, vol)
	}
	if len(volumes) == 0 {
		return discoverFilesystemVolumes(ctx, serverRoot)
	}

	sort.SliceStable(volumes, func(i, j int) bool { return volumes[i].Path < volumes[j].Path })
	return volumes, nil
}

// A normal Linux host need not have NAS-specific /volume* directories.
// Only expose mounts beneath the configured root, with virtual API paths.
func discoverFilesystemVolumes(ctx context.Context, serverRoot string) ([]Volume, error) {
	partitions, err := disk.PartitionsWithContext(ctx, true)
	if err != nil {
		return nil, err
	}
	seen := map[string]bool{"/": true}
	volumes := []Volume{{Path: "/", Name: "文件系统", Type: "system"}}
	for _, partition := range partitions {
		relative, err := filepath.Rel(serverRoot, partition.Mountpoint)
		if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
			continue
		}
		virtual := path.Clean("/" + filepath.ToSlash(relative))
		if seen[virtual] || virtual == "/proc" || strings.HasPrefix(virtual, "/proc/") ||
			virtual == "/sys" || strings.HasPrefix(virtual, "/sys/") ||
			virtual == "/dev" || strings.HasPrefix(virtual, "/dev/") {
			continue
		}
		info, err := os.Stat(partition.Mountpoint)
		if err != nil || !info.IsDir() {
			continue
		}
		seen[virtual] = true
		kind := "system"
		if strings.Contains(partition.Fstype, "nfs") || partition.Fstype == "cifs" || partition.Fstype == "smbfs" {
			kind = "network"
		}
		volumes = append(volumes, Volume{Path: virtual, Name: filepath.Base(partition.Mountpoint), Type: kind})
	}
	for index := range volumes {
		host := filepath.Join(serverRoot, filepath.FromSlash(strings.TrimPrefix(volumes[index].Path, "/")))
		if usage, err := disk.UsageWithContext(ctx, host); err == nil {
			volumes[index].TotalSpace, volumes[index].UsedSpace, volumes[index].FreeSpace = usage.Total, usage.Used, usage.Free
		}
	}
	sort.SliceStable(volumes, func(i, j int) bool { return volumes[i].Path < volumes[j].Path })
	return volumes, nil
}

var volumesHandler = withUser(func(w http.ResponseWriter, r *http.Request, d *data) (int, error) {
	if !d.user.Perm.Admin {
		return http.StatusForbidden, fmt.Errorf("没有访问权限")
	}

	volumes, err := discoverVolumes(r.Context(), d.server.Root)
	if err != nil {
		return http.StatusInternalServerError, err
	}

	return renderJSON(w, r, volumes)
})
