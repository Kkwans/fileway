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

// Classify returns a Linux case-sensitive risk level for a normalized virtual
// path. A root only matches itself or a slash-delimited descendant.
func Classify(rawPath string) Level {
	if rawPath == "" || !strings.HasPrefix(rawPath, "/") {
		return Low
	}
	cleaned := path.Clean(rawPath)

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
