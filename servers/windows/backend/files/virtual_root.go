package files

import (
	"path"
	"runtime"
	"strings"
)

// VirtualComputerRoot is the multi-drive root token used on Windows.
// Virtual paths look like /C/Users, /D/Movie.
const VirtualComputerRoot = "computer"

// IsVirtualComputerRoot reports whether server root should use DriveFs.
func IsVirtualComputerRoot(serverRoot string) bool {
	if runtime.GOOS != "windows" {
		return false
	}
	s := strings.TrimSpace(serverRoot)
	if s == "" {
		return false
	}
	s = strings.TrimSuffix(strings.ReplaceAll(s, "\\", "/"), "/")
	switch strings.ToLower(s) {
	case "/", "computer", "computer://", "drives", "drives://", "\\":
		return true
	}
	return false
}

// UseDriveFs reports whether the user filesystem should be multi-drive.
func UseDriveFs(baseScope, userScope string) bool {
	if runtime.GOOS != "windows" {
		return false
	}
	if IsVirtualComputerRoot(baseScope) {
		return true
	}
	// Empty/base-only user scope under virtual root also enables DriveFs.
	joined := path.Clean("/" + strings.TrimPrefix(strings.ReplaceAll(userScope, "\\", "/"), "/"))
	return joined == "/" && IsVirtualComputerRoot(baseScope)
}

// NormalizeVirtualPath cleans a web path into /segment form.
func NormalizeVirtualPath(p string) string {
	p = strings.ReplaceAll(p, "\\", "/")
	if p == "" {
		return "/"
	}
	if !strings.HasPrefix(p, "/") {
		p = "/" + p
	}
	return path.Clean(p)
}

// IsDriveSegment reports whether segment is a single drive letter.
func IsDriveSegment(segment string) bool {
	if len(segment) != 1 {
		return false
	}
	c := segment[0]
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
}

// VirtualToWindowsPath maps /C/Users -> C:\Users.
// Root "/" returns an error; callers handle root listing separately.
func VirtualToWindowsPath(virtual string) (string, error) {
	cleaned := NormalizeVirtualPath(virtual)
	if cleaned == "/" {
		return "", errVirtualRoot
	}
	trimmed := strings.TrimPrefix(cleaned, "/")
	parts := strings.SplitN(trimmed, "/", 2)
	letter := parts[0]
	if !IsDriveSegment(letter) {
		return "", errNotDrivePath
	}
	drive := strings.ToUpper(letter) + `:\`
	if len(parts) == 1 {
		return drive, nil
	}
	rest := strings.ReplaceAll(parts[1], "/", `\`)
	return drive + rest, nil
}

// WindowsPathToVirtual maps C:\Users -> /C/Users.
func WindowsPathToVirtual(real string) string {
	real = strings.ReplaceAll(real, `\`, "/")
	real = strings.TrimRight(real, "/")
	if real == "" {
		return "/"
	}
	// Already virtual.
	if strings.HasPrefix(real, "/") && len(real) >= 3 && IsDriveSegment(strings.TrimPrefix(real, "/")[:1]) {
		if len(real) == 2 {
			return strings.ToUpper(real)
		}
		head := strings.ToUpper(real[:2])
		return head + real[2:]
	}
	// Drive-relative absolute like C:/Users
	if len(real) >= 2 && real[1] == ':' && IsDriveSegment(real[:1]) {
		head := strings.ToUpper(real[:1])
		if len(real) == 2 {
			return "/" + head
		}
		return "/" + head + real[2:]
	}
	if !strings.HasPrefix(real, "/") {
		real = "/" + real
	}
	return path.Clean(real)
}
