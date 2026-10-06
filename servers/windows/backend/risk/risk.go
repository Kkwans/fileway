// Package risk classifies virtual NAS paths for UI and operation safeguards.
package risk

import (
	"path"
	"strings"
)

// Level is the risk classification exposed by resource APIs.
type Level string

const (
	Low    Level = "low"
	Medium Level = "medium"
	High   Level = "high"
)

var highRiskRoots = []string{
	"/bin",
	"/boot",
	"/dev",
	"/etc",
	"/lib",
	"/lib32",
	"/lib64",
	"/libx32",
	"/proc",
	"/root",
	"/run",
	"/sbin",
	"/sys",
	"/usr",
	"/var",
}

var highRiskVolumeEntries = map[string]struct{}{
	"@appstore": {},
	"@home":     {},
	"@tmp":      {},
	"@upload":   {},
}

var mediumRiskVolumeEntries = map[string]struct{}{
	"@appcache":     {},
	"@appdata":      {},
	"@applog":       {},
	"@docker":       {},
	"@eaDir":        {},
	"@exif":         {},
	"@FileManager":  {},
	"@RecentlyScan": {},
	"@search":       {},
	"@thumbnail":    {},
	"@video":        {},
	"Docker":        {},
	"DockerProject": {},
	"docker":        {},
	"docker-apps":   {},
}

var mediumRiskRoots = []string{
	"/.filebrowser-cache",
	"/.filebrowser-trash",
	"/.nas-file-browser-cache",
	"/.nas-file-browser-trash",
	"/config",
	"/database",
}

// Windows per-drive trash/cache prefixes (virtual /C/.nas-file-browser-trash).
var mediumRiskWindowsDirs = map[string]struct{}{
	".filebrowser-cache":      {},
	".filebrowser-trash":      {},
	".nas-file-browser-cache": {},
	".nas-file-browser-trash": {},
}

// Classify returns a Linux case-sensitive risk level for a normalized virtual
// path. A root only matches itself or a slash-delimited descendant.
func Classify(rawPath string) Level {
	if rawPath == "" || !strings.HasPrefix(rawPath, "/") {
		return Low
	}
	cleaned := path.Clean(rawPath)

	if level, ok := classifyWindows(cleaned); ok {
		return level
	}

	for _, root := range highRiskRoots {
		if containsPath(root, cleaned) {
			return High
		}
	}

	parts := strings.Split(strings.TrimPrefix(cleaned, "/"), "/")
	if len(parts) >= 2 && isNASVolume(parts[0]) {
		if _, ok := highRiskVolumeEntries[parts[1]]; ok {
			return High
		}
		if _, ok := mediumRiskVolumeEntries[parts[1]]; ok {
			return Medium
		}
	}

	for _, root := range mediumRiskRoots {
		if containsPath(root, cleaned) {
			return Medium
		}
	}

	return Low
}

// classifyWindows handles virtual multi-drive paths like /C/Windows.
// Returns false when the path is not a windows drive path.
func classifyWindows(cleaned string) (Level, bool) {
	parts := strings.Split(strings.TrimPrefix(cleaned, "/"), "/")
	if len(parts) == 0 || !isWindowsDriveSegment(parts[0]) {
		return Low, false
	}
	if len(parts) == 1 {
		return Low, true
	}
	second := strings.ToLower(parts[1])
	if _, ok := mediumRiskWindowsDirs[second]; ok {
		return Medium, true
	}
	switch second {
	case "windows", "program files", "program files (x86)", "programdata",
		"system volume information", "$recycle.bin", "recovery", "perflogs",
		"config", "boot", "bootmgr":
		return High, true
	}
	// User profile system folders under C:/Users/<name>/AppData etc.
	if len(parts) >= 4 && strings.EqualFold(parts[1], "Users") {
		fourth := strings.ToLower(parts[3])
		if fourth == "appdata" {
			return Medium, true
		}
	}
	return Low, true
}

func isWindowsDriveSegment(segment string) bool {
	if len(segment) != 1 {
		return false
	}
	c := segment[0]
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
}

func containsPath(root, candidate string) bool {
	return candidate == root || strings.HasPrefix(candidate, root+"/")
}

func isNASVolume(segment string) bool {
	if !strings.HasPrefix(segment, "volume") {
		return false
	}
	suffix := strings.TrimPrefix(segment, "volume")
	if suffix == "" {
		return false
	}
	for _, character := range suffix {
		if character < '0' || character > '9' {
			return false
		}
	}
	return true
}
