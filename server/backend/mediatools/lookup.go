// Package mediatools resolves the same media tools for all server platforms.
package mediatools

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// Lookup uses an explicit override, tools beside the server, then PATH.
func Lookup(tool string) (string, error) {
	if tool != "ffmpeg" && tool != "ffprobe" {
		return "", fmt.Errorf("unsupported media tool %q", tool)
	}
	if configured := os.Getenv(strings.ToUpper(tool) + "_PATH"); configured != "" {
		return exec.LookPath(configured)
	}
	if executable, err := os.Executable(); err == nil {
		name := tool
		if runtime.GOOS == "windows" {
			name += ".exe"
		}
		root := filepath.Dir(executable)
		for _, candidate := range []string{filepath.Join(root, "bin", name), filepath.Join(root, name)} {
			if found, err := exec.LookPath(candidate); err == nil {
				return found, nil
			}
		}
	}
	return exec.LookPath(tool)
}
